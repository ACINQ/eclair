/*
 * Copyright 2022 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.plugins.peerswap

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.eventstream.EventStream.{Publish, Subscribe}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.OnChainWallet.OnChainBalance
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchTxConfirmed, WatchTxConfirmedTriggered}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{DummyOnChainWallet, OnChainWallet}
import fr.acinq.eclair.channel.DATA_NORMAL
import fr.acinq.eclair.channel.Register.ForwardShortId
import fr.acinq.eclair.io.UnknownMessageReceived
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentReceived, PaymentSent}
import fr.acinq.eclair.plugins.peerswap.SwapEvents.{ClaimByInvoiceConfirmed, ClaimByInvoicePaid, SwapEvent, TransactionPublished}
import fr.acinq.eclair.plugins.peerswap.SwapHelpers.makeUnknownMessage
import fr.acinq.eclair.plugins.peerswap.SwapRegister.{SwapInRequested, SwapOutRequested, SwapTerminated, WrappedUnknownMessageReceived}
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{Response, SwapExistsForChannel, SwapOpened}
import fr.acinq.eclair.plugins.peerswap.db.sqlite.SqliteSwapsDb
import fr.acinq.eclair.plugins.peerswap.transactions.SwapTransactions.{makeSwapClaimByInvoiceTx, makeSwapOpeningTxOut}
import fr.acinq.eclair.plugins.peerswap.wire.protocol.PeerSwapMessageCodecs.{openingTxBroadcastedCodec, swapInRequestCodec}
import fr.acinq.eclair.plugins.peerswap.wire.protocol._
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol.UnknownMessage
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, NodeParams, ShortChannelId, TestConstants, TimestampMilli, ToMilliSatoshiConversion, randomBytes32}
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, Outcome, ParallelTestExecution}
import scodec.bits.HexStringSyntax

import java.sql.DriverManager
import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

class SwapRegisterSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with BeforeAndAfterAll with Matchers with FixtureAnyFunSuiteLike with ParallelTestExecution {
  override implicit val timeout: Timeout = Timeout(30 seconds)
  val protocolVersion = 2
  val noAsset = ""
  val network: String = NodeParams.chainFromHash(TestConstants.Alice.nodeParams.chainHash)
  val amount: Satoshi = 1000 sat
  val fee: Satoshi = 22 sat
  val channelData: DATA_NORMAL = ChannelCodecsSpec.normal
  val shortChannelId: ShortChannelId = channelData.shortIds.real.toOption.get
  val channelId: ByteVector32 = channelData.channelId
  val bobPayoutPubkey: PublicKey = PublicKey(hex"0270685ca81a8e4d4d01beec5781f4cc924684072ae52c507f8ebe9daf0caaab7b")
  val premium = 10
  val scriptOut = 0
  val blindingKey = ""
  val txId: String = ByteVector32.One.toHex
  val aliceKeyManager: SwapKeyManager = new LocalSwapKeyManager(TestConstants.Alice.seed, TestConstants.Alice.nodeParams.chainHash)
  val aliceDb = new SqliteSwapsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
  val bobKeyManager: SwapKeyManager = new LocalSwapKeyManager(TestConstants.Bob.seed, TestConstants.Bob.nodeParams.chainHash)
  val bobDb = new SqliteSwapsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
  val aliceNodeId: PublicKey = TestConstants.Alice.nodeParams.nodeId
  val feeInvoice: Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(fee.toMilliSatoshi), Crypto.sha256(paymentPreimage(1)), bobPrivkey(swapId(1)), Left("PeerSwap fee invoice 1"), CltvExpiryDelta(18))
  val feeRatePerKw: FeeratePerKw = TestConstants.Alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = TestConstants.Alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
  val swapInRequest: SwapInRequest = SwapInRequest(protocolVersion, swapId(0), noAsset, network, shortChannelId.toString, amount.toLong, alicePubkey(swapId(0)).toString())
  val swapInAgreement: SwapInAgreement = SwapInAgreement(protocolVersion, swapId(0), bobPubkey(swapId(0)).toString(), premium)
  val swapOutRequest: SwapOutRequest = SwapOutRequest(protocolVersion, swapId(1), noAsset, network, shortChannelId.toString, amount.toLong, bobPubkey(swapId(1)).toString())
  val swapOutAgreement: SwapOutAgreement = SwapOutAgreement(protocolVersion, swapId(1), bobPubkey(swapId(1)).toString(), feeInvoice.toString)

  def paymentPreimage(index: Int): ByteVector32 = index match {
    case 0 => ByteVector32.Zeroes
    case 1 => ByteVector32.One
    case _ => randomBytes32()
  }
  def privKey(index: Int): PrivateKey = index match {
    case 0 => alicePrivkey(swapId(0))
    case _ => bobPrivkey(swapId(index))
  }
  def swapId(index: Int): String = paymentPreimage(index).toHex
  def alicePrivkey(swapId: String): PrivateKey = aliceKeyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  def alicePubkey(swapId: String): PublicKey = alicePrivkey(swapId).publicKey
  def bobPrivkey(swapId: String): PrivateKey = bobKeyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  def bobPubkey(swapId: String): PublicKey = bobPrivkey(swapId).publicKey
  def invoice(index: Int): Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(amount.toMilliSatoshi), Crypto.sha256(paymentPreimage(index)), privKey(index), Left(s"PeerSwap payment invoice $index"), CltvExpiryDelta(18))
  def openingTxBroadcasted(index: Int): OpeningTxBroadcasted = OpeningTxBroadcasted(swapId(index), invoice(index).toString, txId, scriptOut, blindingKey)
  def makePluginMessage(message: HasSwapId): WrappedUnknownMessageReceived = WrappedUnknownMessageReceived(UnknownMessageReceived(null, alicePubkey(""), makeUnknownMessage(message), null))
  def expectUnknownMessage(register: TestProbe[Any]): UnknownMessage = register.expectMessageType[ForwardShortId[UnknownMessage]].message

  override def withFixture(test: OneArgTest): Outcome = {
    val userCli = testKit.createTestProbe[Response]()
    val swapEvents = testKit.createTestProbe[SwapEvent]()
    val register = testKit.createTestProbe[Any]()
    val monitor = testKit.createTestProbe[SwapRegister.Command]()
    val paymentHandler = testKit.createTestProbe[Any]()
    val wallet = new DummyOnChainWallet() {
      override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(6930 sat, 0 sat))
    }
    val watcher = testKit.createTestProbe[Any]()

    // subscribe to notification events from SwapInSender when a payment is successfully received or claimed via coop or csv
    testKit.system.eventStream ! Subscribe[SwapEvent](swapEvents.ref)

    withFixture(test.toNoArgTest(FixtureParam(userCli, swapEvents, register, monitor, paymentHandler, wallet, watcher)))
  }

  case class FixtureParam(userCli: TestProbe[Response], swapEvents: TestProbe[SwapEvent], register: TestProbe[Any], monitor: TestProbe[SwapRegister.Command], paymentHandler: TestProbe[Any], wallet: OnChainWallet, watcher: TestProbe[Any])

  test("restore the swap register from the database") { f =>
    import f._

    val savedData: Set[SwapData] = Set(SwapData(swapInRequest, swapInAgreement, invoice(0), openingTxBroadcasted(0), swapRole = SwapRole.Maker, isInitiator = true),
      SwapData(swapOutRequest, swapOutAgreement, invoice(1), openingTxBroadcasted(1), swapRole = SwapRole.Taker, isInitiator = true))
    val swapRegister = testKit.spawn(Behaviors.monitor(monitor.ref, SwapRegister(TestConstants.Alice.nodeParams, paymentHandler.ref.toClassic, watcher.ref, register.ref.toClassic, wallet, aliceKeyManager, aliceDb, savedData)), "SwapRegister")

    // wait for SwapMaker and SwapTaker to subscribe to PaymentEventReceived messages
    swapEvents.expectNoMessage()

    // swapId0 - Taker: payment(paymentHash) -> Maker
    val paymentHash0 = Bolt11Invoice.fromString(openingTxBroadcasted(0).payreq).get.paymentHash
    val paymentReceived0 = PaymentReceived(paymentHash0, Seq(PaymentReceived.PartialPayment(amount.toMilliSatoshi, channelId, TimestampMilli(1553784963659L))))
    testKit.system.eventStream ! Publish(paymentReceived0)

    // swapId0 - SwapRegister received notice that SwapInSender swap completed
    val swap0Completed = swapEvents.expectMessageType[ClaimByInvoicePaid]
    assert(swap0Completed.swapId === swapId(0))

    // swapId0: SwapRegister receives notification that the swap Maker actor stopped
    assert(monitor.expectMessageType[SwapTerminated].swapId === swapId(0))

    // swapId1 - ZmqWatcher -> Taker, trigger confirmation of opening transaction
    val openingTx = Transaction(2, Seq(), Seq(makeSwapOpeningTxOut(swapOutRequest.amount.sat, bobPubkey(swapId(1)), alicePubkey(swapId(1)), invoice(1).paymentHash)), 0)
    watcher.expectMessageType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(BlockHeight(1), 0, openingTx)

    // swapId1 - wait for Taker to subscribe to PaymentEventReceived messages
    swapEvents.expectNoMessage()

    // swapId1 - Taker validates the invoice and opening transaction before paying the invoice
    testKit.system.eventStream ! Publish(PaymentSent(UUID.randomUUID(), invoice(1).paymentHash, paymentPreimage(1), amount.toMilliSatoshi, aliceNodeId, PaymentSent.PartialPayment(UUID.randomUUID(), amount.toMilliSatoshi, 0.sat.toMilliSatoshi, channelId, None) :: Nil))

    // swapId1 - ZmqWatcher -> Taker, trigger confirmation of claim-by-invoice transaction
    val claimByInvoiceTx = makeSwapClaimByInvoiceTx(swapOutRequest.amount.sat, bobPubkey(swapId(1)), alicePrivkey(swapId(1)), paymentPreimage(1), feeRatePerKw, openingTx.txid, 0)
    watcher.expectMessageType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(BlockHeight(6), 0, claimByInvoiceTx)

    // swapId1 - SwapRegister received notice that SwapOutSender completed
    swapEvents.expectMessageType[TransactionPublished]
    assert(swapEvents.expectMessageType[ClaimByInvoiceConfirmed].swapId === swapId(1))

    // swapId1 - SwapRegister receives notification that the swap Taker actor stopped
    assert(monitor.expectMessageType[SwapTerminated].swapId === swapId(1))

    testKit.stop(swapRegister)
  }

  test("register a new swap in the swap register") { f =>
    import f._

    // initialize SwapRegister
    val swapRegister = testKit.spawn(Behaviors.monitor(monitor.ref, SwapRegister(TestConstants.Alice.nodeParams, paymentHandler.ref.toClassic, watcher.ref, register.ref.toClassic, wallet, aliceKeyManager, aliceDb, Set())), "SwapRegister")
    swapEvents.expectNoMessage()
    userCli.expectNoMessage()

    // User:SwapInRequested -> SwapInRegister
    swapRegister ! SwapInRequested(userCli.ref, amount, shortChannelId)
    val swapId = userCli.expectMessageType[SwapOpened].swapId
    monitor.expectMessageType[SwapInRequested]

    // Alice:SwapInRequest -> Bob
    val swapInRequest = swapInRequestCodec.decode(expectUnknownMessage(register).data.drop(2).toBitVector).require.value
    assert(swapId === swapInRequest.swapId)

    // Alice's database has no items before the opening tx is published
    assert(aliceDb.list().isEmpty)

    // Bob: SwapInAgreement -> Alice
    swapRegister ! makePluginMessage(SwapInAgreement(swapInRequest.protocolVersion, swapInRequest.swapId, bobPayoutPubkey.toString(), premium))
    monitor.expectMessageType[WrappedUnknownMessageReceived]

    // Alice's database should be updated before the opening tx is published
    eventually(PatienceConfiguration.Timeout(2 seconds), PatienceConfiguration.Interval(1 second)) {
      assert(aliceDb.list().size == 1)
    }

    // SwapInSender confirms opening tx published
    swapEvents.expectMessageType[TransactionPublished]

    // Alice:OpeningTxBroadcasted -> Bob
    val openingTxBroadcasted = openingTxBroadcastedCodec.decode(expectUnknownMessage(register).data.drop(2).toBitVector).require.value

    // Bob: payment(paymentHash) -> Alice
    val paymentHash = Bolt11Invoice.fromString(openingTxBroadcasted.payreq).get.paymentHash
    val paymentReceived = PaymentReceived(paymentHash, Seq(PaymentReceived.PartialPayment(amount.toMilliSatoshi, channelId, TimestampMilli(1553784963659L))))
    testKit.system.eventStream ! Publish(paymentReceived)

    // SwapRegister received notice that SwapInSender completed
    assert(swapEvents.expectMessageType[ClaimByInvoicePaid].swapId === swapId)

    // SwapRegister receives notification that the swap actor stopped
    assert(monitor.expectMessageType[SwapTerminated].swapId === swapId)

    testKit.stop(swapRegister)
  }

  test("fail second swap request on same channel") { f =>
    import f._

    // initialize SwapRegister
    val swapRegister = testKit.spawn(Behaviors.monitor(monitor.ref, SwapRegister(TestConstants.Alice.nodeParams, paymentHandler.ref.toClassic, watcher.ref, register.ref.toClassic, wallet, aliceKeyManager, aliceDb, Set())), "SwapRegister")
    swapEvents.expectNoMessage()
    userCli.expectNoMessage()

    // first swap request succeeds
    swapRegister ! SwapInRequested(userCli.ref, amount, shortChannelId)
    val response = userCli.expectMessageType[SwapOpened]
    val request = swapInRequestCodec.decode(expectUnknownMessage(register).data.drop(2).toBitVector).require.value
    assert(response.swapId === request.swapId)

    // subsequent swap requests with same channel id from the user or peer should fail
    swapRegister ! SwapInRequested(userCli.ref, amount, shortChannelId)
    userCli.expectMessageType[SwapExistsForChannel]
    register.expectNoMessage()

    swapRegister ! SwapOutRequested(userCli.ref, amount, shortChannelId)
    userCli.expectMessageType[SwapExistsForChannel]
    register.expectNoMessage()

    swapRegister ! makePluginMessage(swapInRequest)
    register.expectNoMessage()

    swapRegister ! makePluginMessage(swapOutRequest)
    register.expectNoMessage()
  }

  test("list the active swap in the register") { f =>



  }
}
