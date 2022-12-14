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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{DummyOnChainWallet, OnChainWallet}
import fr.acinq.eclair.channel.DATA_NORMAL
import fr.acinq.eclair.io.Switchboard.ForwardUnknownMessage
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentReceived}
import fr.acinq.eclair.plugins.peerswap.SwapCommands._
import fr.acinq.eclair.plugins.peerswap.SwapEvents.{ClaimByInvoicePaid, SwapEvent, TransactionPublished}
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{AwaitClaimPayment, Status}
import fr.acinq.eclair.plugins.peerswap.db.sqlite.SqliteSwapsDb
import fr.acinq.eclair.plugins.peerswap.transactions.SwapTransactions.openingTxWeight
import fr.acinq.eclair.plugins.peerswap.wire.protocol.PeerSwapMessageCodecs.peerSwapMessageCodec
import fr.acinq.eclair.plugins.peerswap.wire.protocol.{OpeningTxBroadcasted, SwapOutAgreement, SwapOutRequest}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs
import fr.acinq.eclair.{NodeParams, ShortChannelId, TestConstants, TimestampMilli, ToMilliSatoshiConversion, randomBytes32}
import grizzled.slf4j.Logging
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{BeforeAndAfterAll, Outcome}

import java.sql.DriverManager
import scala.concurrent.duration._

// with BitcoindService
case class SwapOutReceiverSpec() extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike with BeforeAndAfterAll with Logging {
  override implicit val timeout: Timeout = Timeout(30 seconds)
  val protocolVersion = 3
  val noAsset = ""
  val network: String = NodeParams.chainFromHash(TestConstants.Alice.nodeParams.chainHash)
  val amount: Satoshi = 1000 sat
  val feeRatePerKw: FeeratePerKw = TestConstants.Alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = TestConstants.Alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
  val openingFee: Long = (feeRatePerKw * openingTxWeight / 1000).toLong // TODO: how should swap out initiator calculate an acceptable swap opening tx fee?
  val swapId: String = ByteVector32.Zeroes.toHex
  val channelData: DATA_NORMAL = ChannelCodecsSpec.normal
  val shortChannelId: ShortChannelId = channelData.shortIds.real.toOption.get
  val channelId: ByteVector32 = channelData.channelId
  val keyManager: SwapKeyManager = new LocalSwapKeyManager(TestConstants.Alice.seed, TestConstants.Alice.nodeParams.chainHash)
  val db = new SqliteSwapsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
  val makerPrivkey: PrivateKey = keyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  val takerPrivkey: PrivateKey = PrivateKey(randomBytes32())
  val makerNodeId: PublicKey = PrivateKey(randomBytes32()).publicKey
  val makerPubkey: PublicKey = makerPrivkey.publicKey
  val takerPubkey: PublicKey = takerPrivkey.publicKey
  val paymentPreimage: ByteVector32 = ByteVector32.One
  val feePreimage: ByteVector32 = ByteVector32.Zeroes
  val txid: String = ByteVector32.One.toHex
  val scriptOut: Long = 0
  val blindingKey: String = ""
  val request: SwapOutRequest = SwapOutRequest(protocolVersion, swapId, noAsset, network, shortChannelId.toString, amount.toLong, takerPubkey.toHex)
  val remoteNodeId: PublicKey = TestConstants.Alice.nodeParams.nodeId

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
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId
    val keyManager: SwapKeyManager = new LocalSwapKeyManager(TestConstants.Alice.seed, TestConstants.Alice.nodeParams.chainHash)

    // subscribe to notification events from SwapInReceiver when a payment is successfully received or claimed via coop or csv
    testKit.system.eventStream ! Subscribe[SwapEvent](swapEvents.ref)

    val swapOutReceiver = testKit.spawn(SwapMaker(remoteNodeId, TestConstants.Alice.nodeParams, watcher.ref, switchboard.ref.toClassic, wallet, keyManager, db), "swap-out-receiver")

    withFixture(test.toNoArgTest(FixtureParam(swapOutReceiver, userCli, switchboard, relayer, router, paymentInitiator, paymentHandler, TestConstants.Bob.nodeParams, watcher, wallet, swapEvents, remoteNodeId)))
  }

  case class FixtureParam(swapOutReceiver: ActorRef[SwapCommands.SwapCommand], userCli: TestProbe[Status], switchboard: TestProbe[Any], relayer: TestProbe[Any], router: TestProbe[Any], paymentInitiator: TestProbe[Any], paymentHandler: TestProbe[Any], nodeParams: NodeParams, watcher: TestProbe[ZmqWatcher.Command], wallet: OnChainWallet, swapEvents: TestProbe[SwapEvent], remoteNodeId: PublicKey)

  test("happy path for new swap out receiver") { f =>
    import f._

    // start new SwapOutReceiver
    swapOutReceiver ! StartSwapOutReceiver(request)

    // SwapOutReceiver:SwapOutAgreement -> SwapOutSender
    val agreement = expectSwapMessage[SwapOutAgreement](switchboard)
    assert(agreement.pubkey == makerPubkey.toHex)

    // SwapOutSender pays the fee invoice
    val feeInvoice = Bolt11Invoice.fromString(agreement.payreq).get
    val feeReceived = PaymentReceived(feeInvoice.paymentHash, Seq(PaymentReceived.PartialPayment(openingFee.sat.toMilliSatoshi, channelId, TimestampMilli(1553784963659L))))
    testKit.system.eventStream ! Publish(feeReceived)

    // SwapOutReceiver publishes opening tx on-chain
    val openingTx = swapEvents.expectMessageType[TransactionPublished].tx
    assert(openingTx.txOut.head.amount == amount)

    // SwapOutReceiver:OpeningTxBroadcasted -> SwapOutSender
    val openingTxBroadcasted = expectSwapMessage[OpeningTxBroadcasted](switchboard)
    val paymentInvoice = Bolt11Invoice.fromString(openingTxBroadcasted.payreq).get

    // SwapOutReceiver reports status of awaiting payment
    swapOutReceiver ! GetStatus(userCli.ref)
    userCli.expectMessageType[AwaitClaimPayment]

    // SwapOutReceiver receives a payment with the corresponding payment hash
    // TODO: convert from ShortChannelId to ByteVector32
    val paymentReceived = PaymentReceived(paymentInvoice.paymentHash, Seq(PaymentReceived.PartialPayment(amount.toMilliSatoshi, channelId, TimestampMilli(1553784963659L))))
    testKit.system.eventStream ! Publish(paymentReceived)

    // SwapOutReceiver reports a successful claim-by-invoice was paid for
    swapEvents.expectMessageType[ClaimByInvoicePaid]

    // wait for swap actor to stop
    testKit.stop(swapOutReceiver)

    // the swap result has been recorded in the db
    assert(db.list().head.result.contains("Invoice payment received"))
  }
}
