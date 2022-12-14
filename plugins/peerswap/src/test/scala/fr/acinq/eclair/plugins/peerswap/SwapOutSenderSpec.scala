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
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream.{Publish, Subscribe}
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.WatchTxConfirmedTriggered
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{DummyOnChainWallet, OnChainWallet}
import fr.acinq.eclair.channel.DATA_NORMAL
import fr.acinq.eclair.io.Switchboard.ForwardUnknownMessage
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentSent}
import fr.acinq.eclair.plugins.peerswap.SwapCommands._
import fr.acinq.eclair.plugins.peerswap.SwapEvents.{ClaimByInvoiceConfirmed, SwapEvent, TransactionPublished}
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{AwaitOpeningTxConfirmation, Status}
import fr.acinq.eclair.plugins.peerswap.db.sqlite.SqliteSwapsDb
import fr.acinq.eclair.plugins.peerswap.transactions.SwapTransactions.{makeSwapClaimByInvoiceTx, makeSwapOpeningTxOut}
import fr.acinq.eclair.plugins.peerswap.wire.protocol.PeerSwapMessageCodecs.peerSwapMessageCodec
import fr.acinq.eclair.plugins.peerswap.wire.protocol.{OpeningTxBroadcasted, SwapOutAgreement, SwapOutRequest}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, NodeParams, ShortChannelId, TestConstants, ToMilliSatoshiConversion, randomBytes32}
import grizzled.slf4j.Logging
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{BeforeAndAfterAll, Outcome}

import java.sql.DriverManager
import java.util.UUID
import scala.concurrent.duration._

// with BitcoindService
case class SwapOutSenderSpec() extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike with BeforeAndAfterAll with Logging {
  override implicit val timeout: Timeout = Timeout(30 seconds)
  val protocolVersion = 3
  val noAsset = ""
  val network: String = NodeParams.chainFromHash(TestConstants.Bob.nodeParams.chainHash)
  val amount: Satoshi = 1000 sat
  val fee: Satoshi = 100 sat
  val swapId: String = ByteVector32.Zeroes.toHex
  val channelData: DATA_NORMAL = ChannelCodecsSpec.normal
  val shortChannelId: ShortChannelId = channelData.shortIds.real.toOption.get
  val channelId: ByteVector32 = channelData.channelId
  val keyManager: SwapKeyManager = new LocalSwapKeyManager(TestConstants.Bob.seed, TestConstants.Bob.nodeParams.chainHash)
  val db = new SqliteSwapsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
  val makerPrivkey: PrivateKey = PrivateKey(randomBytes32())
  val takerPrivkey: PrivateKey = keyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  val makerNodeId: PublicKey = PrivateKey(randomBytes32()).publicKey
  val makerPubkey: PublicKey = makerPrivkey.publicKey
  val takerPubkey: PublicKey = takerPrivkey.publicKey
  val feeRatePerKw: FeeratePerKw = TestConstants.Bob.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = TestConstants.Bob.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
  val paymentPreimage: ByteVector32 = ByteVector32.One
  val feePreimage: ByteVector32 = ByteVector32.Zeroes
  val paymentInvoice: Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(amount.toMilliSatoshi), Crypto.sha256(paymentPreimage), makerPrivkey, Left("SwapOutSender payment invoice"), CltvExpiryDelta(18))
  val feeInvoice: Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(fee.toMilliSatoshi), Crypto.sha256(feePreimage), makerPrivkey, Left("SwapOutSender fee invoice"), CltvExpiryDelta(18))
  val otherInvoice: Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(fee.toMilliSatoshi), randomBytes32(), makerPrivkey, Left("SwapOutSender other invoice"), CltvExpiryDelta(18))
  val txid: String = ByteVector32.One.toHex
  val scriptOut: Long = 0
  val blindingKey: String = ""
  val request: SwapOutRequest = SwapOutRequest(protocolVersion, swapId, noAsset, network, shortChannelId.toString, amount.toLong, makerPubkey.toHex)

  def expectSwapMessage[B](switchboard: TestProbe[Any]): B = {
    val unknownMessage = switchboard.expectMessageType[ForwardUnknownMessage].msg
    val encoded = LightningMessageCodecs.unknownMessageCodec.encode(unknownMessage).require.toByteVector
    peerSwapMessageCodec.decode(encoded.toBitVector).require.value.asInstanceOf[B]
  }
  override def withFixture(test: OneArgTest): Outcome = {
    val watcher = testKit.createTestProbe[ZmqWatcher.Command]()
    val paymentHandler = testKit.createTestProbe[Any]()
    val relayer = testKit.createTestProbe[Any]()
    val router = testKit.createTestProbe[Any]()
    val switchboard = testKit.createTestProbe[Any]()
    val paymentInitiator = testKit.createTestProbe[Any]()

    val wallet = new DummyOnChainWallet()
    val userCli = testKit.createTestProbe[Status]()
    val swapEvents = testKit.createTestProbe[SwapEvent]()
    val keyManager: SwapKeyManager = new LocalSwapKeyManager(TestConstants.Bob.seed, TestConstants.Bob.nodeParams.chainHash)
    val remoteNodeId: PublicKey = TestConstants.Bob.nodeParams.nodeId

    // subscribe to notification events from SwapInReceiver when a payment is successfully received or claimed via coop or csv
    testKit.system.eventStream ! Subscribe[SwapEvent](swapEvents.ref)

    val swapOutSender = testKit.spawn(SwapTaker(remoteNodeId, TestConstants.Bob.nodeParams, paymentInitiator.ref.toClassic, watcher.ref, switchboard.ref.toClassic, wallet, keyManager, db), "swap-out-sender")

    withFixture(test.toNoArgTest(FixtureParam(swapOutSender, userCli, switchboard, relayer, router, paymentInitiator, paymentHandler, TestConstants.Bob.nodeParams, watcher, wallet, swapEvents, remoteNodeId)))
  }

  case class FixtureParam(swapOutSender: ActorRef[SwapCommands.SwapCommand], userCli: TestProbe[Status], switchboard: TestProbe[Any], relayer: TestProbe[Any], router: TestProbe[Any], paymentInitiator: TestProbe[Any], paymentHandler: TestProbe[Any], nodeParams: NodeParams, watcher: TestProbe[ZmqWatcher.Command], wallet: OnChainWallet, swapEvents: TestProbe[SwapEvent], remoteNodeId: PublicKey)

  test("happy path for new swap out sender") { f =>
    import f._

    // start new SwapOutSender
    swapOutSender ! StartSwapOutSender(amount, swapId, shortChannelId)

    // SwapOutSender: SwapOutRequest -> SwapOutReceiver
    val request = expectSwapMessage[SwapOutRequest](switchboard)
    assert(request.pubkey == takerPubkey.toHex)

    // SwapOutReceiver: SwapOutAgreement -> SwapOutSender (request fee)
    swapOutSender ! SwapMessageReceived(SwapOutAgreement(request.protocolVersion, request.swapId, makerPubkey.toString(), feeInvoice.toString))

    // SwapOutSender validates fee invoice before paying the invoice
    assert(paymentInitiator.expectMessageType[SendPaymentToNode] === SendPaymentToNode(feeInvoice.amount_opt.get, feeInvoice, nodeParams.maxPaymentAttempts, Some(swapId), nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams))
    swapOutSender ! GetStatus(userCli.ref)

    // SwapOutSender confirms the fee invoice has been paid
    testKit.system.eventStream ! Publish(PaymentSent(UUID.randomUUID(), feeInvoice.paymentHash, feePreimage, amount.toMilliSatoshi, makerNodeId, PaymentSent.PartialPayment(UUID.randomUUID(), fee.toMilliSatoshi, 0.sat.toMilliSatoshi, channelId, None) :: Nil))

    // SwapOutSender reports status of awaiting opening transaction after paying claim invoice
    userCli.expectMessageType[AwaitOpeningTxConfirmation]

    // SwapOutReceiver:OpeningTxBroadcasted -> SwapOutSender
    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, paymentInvoice.toString, txid, scriptOut, blindingKey)
    swapOutSender ! SwapMessageReceived(openingTxBroadcasted)

    // ZmqWatcher -> SwapOutSender, trigger confirmation of opening transaction
    val openingTx = Transaction(2, Seq(), Seq(makeSwapOpeningTxOut(request.amount.sat, makerPubkey, takerPubkey, paymentInvoice.paymentHash)), 0)
    swapOutSender ! OpeningTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(1), 0, openingTx))

    // SwapOutSender validates invoice and opening transaction before paying the invoice
    assert(paymentInitiator.expectMessageType[SendPaymentToNode] === SendPaymentToNode(paymentInvoice.amount_opt.get, paymentInvoice, nodeParams.maxPaymentAttempts, Some(swapId), nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams))

    // SwapOutSender ignores payments that do not correspond to the invoice from SwapOutReceiver
    testKit.system.eventStream ! Publish(PaymentSent(UUID.randomUUID(), ByteVector32.Zeroes, paymentPreimage, amount.toMilliSatoshi, makerNodeId, PaymentSent.PartialPayment(UUID.randomUUID(), amount.toMilliSatoshi, 0.sat.toMilliSatoshi, channelId, None) :: Nil))

    // SwapOutSender successfully pays the invoice from SwapOutReceiver and then commits a claim-by-invoice transaction
    testKit.system.eventStream ! Publish(PaymentSent(UUID.randomUUID(), paymentInvoice.paymentHash, paymentPreimage, amount.toMilliSatoshi, makerNodeId, PaymentSent.PartialPayment(UUID.randomUUID(), amount.toMilliSatoshi, 0.sat.toMilliSatoshi, channelId, None) :: Nil))

    // SwapOutSender reports a successful claim by invoice
    swapEvents.expectMessageType[TransactionPublished]
    val claimByInvoiceTx = makeSwapClaimByInvoiceTx(request.amount.sat, makerPubkey, takerPrivkey, paymentPreimage, feeRatePerKw, openingTx.txid, openingTxBroadcasted.scriptOut.toInt)
    swapOutSender ! ClaimTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(6), 0, claimByInvoiceTx))
    swapEvents.expectMessageType[ClaimByInvoiceConfirmed]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapOutSender)

    // the swap result has been recorded in the db
    assert(db.list().head.result.contains("Claimed by paid invoice:"))
  }
}
