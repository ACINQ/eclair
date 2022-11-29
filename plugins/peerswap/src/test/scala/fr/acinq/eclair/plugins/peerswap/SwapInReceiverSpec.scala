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
import akka.actor.typed.scaladsl.Behaviors
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
import fr.acinq.eclair.db.OutgoingPaymentStatus.Pending
import fr.acinq.eclair.db.PaymentsDbSpec.alice
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentType}
import fr.acinq.eclair.io.Switchboard.ForwardUnknownMessage
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentFailed, PaymentSent}
import fr.acinq.eclair.plugins.peerswap.SwapCommands._
import fr.acinq.eclair.plugins.peerswap.SwapEvents.{ClaimByInvoiceConfirmed, SwapEvent, TransactionPublished}
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{Status, SwapStatus}
import fr.acinq.eclair.plugins.peerswap.db.sqlite.SqliteSwapsDb
import fr.acinq.eclair.plugins.peerswap.transactions.SwapScripts.claimByCsvDelta
import fr.acinq.eclair.plugins.peerswap.transactions.SwapTransactions.{claimByInvoiceTxWeight, makeSwapClaimByInvoiceTx, makeSwapOpeningTxOut}
import fr.acinq.eclair.plugins.peerswap.wire.protocol.PeerSwapMessageCodecs.peerSwapMessageCodec
import fr.acinq.eclair.plugins.peerswap.wire.protocol.{CoopClose, OpeningTxBroadcasted, SwapInAgreement, SwapInRequest}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, NodeParams, ShortChannelId, TestConstants, TimestampMilliLong, ToMilliSatoshiConversion, randomBytes32}
import grizzled.slf4j.Logging
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{BeforeAndAfterAll, Outcome}

import java.sql.DriverManager
import java.util.UUID
import scala.concurrent.duration._

// with BitcoindService
case class SwapInReceiverSpec() extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike with BeforeAndAfterAll with Logging {
  override implicit val timeout: Timeout = Timeout(30 seconds)
  val protocolVersion = 3
  val noAsset = ""
  val network: String = NodeParams.chainFromHash(TestConstants.Alice.nodeParams.chainHash)
  val amount: Satoshi = 1000 sat
  val swapId: String = ByteVector32.Zeroes.toHex
  val channelData: DATA_NORMAL = ChannelCodecsSpec.normal
  val shortChannelId: ShortChannelId = channelData.shortIds.real.toOption.get
  val channelId: ByteVector32 = channelData.channelId
  val keyManager: SwapKeyManager = new LocalSwapKeyManager(TestConstants.Alice.seed, TestConstants.Alice.nodeParams.chainHash)
  val db = new SqliteSwapsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
  val makerPrivkey: PrivateKey = PrivateKey(randomBytes32())
  val takerPrivkey: PrivateKey = keyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  val makerNodeId: PublicKey = PrivateKey(randomBytes32()).publicKey
  val makerPubkey: PublicKey = makerPrivkey.publicKey
  val takerPubkey: PublicKey = takerPrivkey.publicKey
  val feeRatePerKw: FeeratePerKw = TestConstants.Bob.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = TestConstants.Bob.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
  val premium: Long = (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong
  val paymentPreimage: ByteVector32 = ByteVector32.One
  val invoice: Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(amount.toMilliSatoshi), Crypto.sha256(paymentPreimage), makerPrivkey, Left("SwapInReceiver invoice"), TestConstants.Alice.nodeParams.channelConf.minFinalExpiryDelta)
  val txid: String = ByteVector32.One.toHex
  val scriptOut: Long = 0
  val blindingKey: String = ""
  val request: SwapInRequest = SwapInRequest(protocolVersion, swapId, noAsset, network, shortChannelId.toString, amount.toLong, makerPubkey.toHex)

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
    val monitor = testKit.createTestProbe[SwapCommands.SwapCommand]()
    val nodeParams = TestConstants.Bob.nodeParams
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId

    // subscribe to notification events from SwapInReceiver when a payment is successfully received or claimed via coop or csv
    testKit.system.eventStream ! Subscribe[SwapEvent](swapEvents.ref)

    val swapInReceiver = testKit.spawn(Behaviors.monitor(monitor.ref, SwapTaker(remoteNodeId, nodeParams, paymentInitiator.ref.toClassic, watcher.ref, switchboard.ref.toClassic, wallet, keyManager, db)), "swap-in-receiver")

    withFixture(test.toNoArgTest(FixtureParam(swapInReceiver, userCli, monitor, switchboard, relayer, router, paymentInitiator, paymentHandler, nodeParams, watcher, wallet, swapEvents, remoteNodeId)))
  }

  case class FixtureParam(swapInReceiver: ActorRef[SwapCommands.SwapCommand], userCli: TestProbe[Status], monitor: TestProbe[SwapCommands.SwapCommand], switchboard: TestProbe[Any], relayer: TestProbe[Any], router: TestProbe[Any], paymentInitiator: TestProbe[Any], paymentHandler: TestProbe[Any], nodeParams: NodeParams, watcher: TestProbe[ZmqWatcher.Command], wallet: OnChainWallet, swapEvents: TestProbe[SwapEvent], remoteNodeId: PublicKey)

  test("send cooperative close after a restore with no pending payment") { f =>
    import f._

    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txid, scriptOut, blindingKey)
    val agreement = SwapInAgreement(protocolVersion, swapId, takerPubkey.toHex, premium)
    val swapData = SwapData(request, agreement, invoice, openingTxBroadcasted, swapRole = SwapRole.Taker, isInitiator = false, remoteNodeId)
    db.add(swapData)
    swapInReceiver ! RestoreSwap(swapData)
    monitor.expectMessageType[RestoreSwap]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)

    // the swap result has been recorded in the db
    assert(db.list().head.result.contains("Coop close offered to peer: Lightning payment not sent."))
  }

  test("send cooperative close after a restore with the payment already marked as failed") { f =>
    import f._

    // restore the SwapInReceiver actor state from a confirmed on-chain opening tx
    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txid, scriptOut, blindingKey)
    val agreement = SwapInAgreement(protocolVersion, swapId, takerPubkey.toHex, premium)
    val swapData = SwapData(request, agreement, invoice, openingTxBroadcasted, swapRole = SwapRole.Taker, isInitiator = false, remoteNodeId)
    db.add(swapData)

    // add failed outgoing payment to the payments databases
    val paymentId = UUID.randomUUID()
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(paymentId, UUID.randomUUID(), Some("1"), invoice.paymentHash, PaymentType.Standard, 123 msat, 123 msat, alice, 1100 unixms, Some(invoice), Pending))
    nodeParams.db.payments.updateOutgoingPayment(PaymentFailed(paymentId, invoice.paymentHash, Seq()))
    assert(nodeParams.db.payments.listOutgoingPayments(invoice.paymentHash).nonEmpty)

    swapInReceiver ! RestoreSwap(swapData)
    monitor.expectMessageType[RestoreSwap]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)

    // the swap result has been recorded in the db
    assert(db.list().head.result.contains("Coop close offered to peer: Lightning payment failed"))
  }

  test("claim by invoice after a restore with the payment already marked as paid") { f =>
    import f._

    // restore the SwapInReceiver actor state from a confirmed on-chain opening tx
    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txid, scriptOut, blindingKey)
    val agreement = SwapInAgreement(protocolVersion, swapId, takerPubkey.toHex, premium)
    val swapData = SwapData(request, agreement, invoice, openingTxBroadcasted, swapRole = SwapRole.Taker, isInitiator = false, remoteNodeId)
    db.add(swapData)

    // add paid outgoing payment to the payments databases
    val paymentId = UUID.randomUUID()
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(paymentId, UUID.randomUUID(), Some("1"), invoice.paymentHash, PaymentType.Standard, 123 msat, 123 msat, alice, 1100 unixms, Some(invoice), Pending))
    nodeParams.db.payments.updateOutgoingPayment(PaymentSent(paymentId, invoice.paymentHash, paymentPreimage, invoice.amount_opt.get, makerNodeId, Seq(PaymentSent.PartialPayment(paymentId, invoice.amount_opt.get, 0 msat, channelId, None))))
    assert(nodeParams.db.payments.listOutgoingPayments(invoice.paymentHash).nonEmpty)

    swapInReceiver ! RestoreSwap(swapData)
    monitor.expectMessageType[RestoreSwap]

    // SwapInReceiver reports status of awaiting claim-by-invoice transaction
    swapInReceiver ! GetStatus(userCli.ref)
    monitor.expectMessageType[GetStatus]
    assert(userCli.expectMessageType[SwapStatus].behavior == "claimSwap")

    // SwapInReceiver reports a successful claim by invoice
    swapEvents.expectMessageType[TransactionPublished]
    val claimByInvoiceTx = makeSwapClaimByInvoiceTx((request.amount + agreement.premium).sat, makerPubkey, takerPrivkey, paymentPreimage, feeRatePerKw, openingTxBroadcasted.txid, openingTxBroadcasted.scriptOut.toInt)
    swapInReceiver ! ClaimTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(6), 0, claimByInvoiceTx))
    swapEvents.expectMessageType[ClaimByInvoiceConfirmed]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)

    // the swap result has been recorded in the db
    assert(db.list().head.result.contains("Claimed by paid invoice:"))
  }

  test("claim by invoice after a restore with the payment marked as pending and later paid") { f =>
    import f._

    // restore the SwapInReceiver actor state from a confirmed on-chain opening tx
    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txid, scriptOut, blindingKey)
    val agreement = SwapInAgreement(protocolVersion, swapId, takerPubkey.toHex, premium)
    val swapData = SwapData(request, agreement, invoice, openingTxBroadcasted, swapRole = SwapRole.Taker, isInitiator = false, remoteNodeId)
    db.add(swapData)

    // add pending outgoing payment to the payments databases
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), Some("1"), invoice.paymentHash, PaymentType.Standard, 123 msat, 123 msat, alice, 1100 unixms, Some(invoice), OutgoingPaymentStatus.Pending))
    assert(nodeParams.db.payments.listOutgoingPayments(invoice.paymentHash).nonEmpty)

    swapInReceiver ! RestoreSwap(swapData)
    monitor.expectMessageType[RestoreSwap]

    // SwapInReceiver reports status of awaiting opening transaction
    swapInReceiver ! GetStatus(userCli.ref)
    monitor.expectMessageType[GetStatus]
    assert(userCli.expectMessageType[SwapStatus].behavior == "payClaimInvoice")

    // pending payment successfully sent by SwapInReceiver
    testKit.system.eventStream ! Publish(PaymentSent(UUID.randomUUID(), invoice.paymentHash, paymentPreimage, amount.toMilliSatoshi, makerNodeId, PaymentSent.PartialPayment(UUID.randomUUID(), amount.toMilliSatoshi, 0.sat.toMilliSatoshi, channelId, None) :: Nil))
    val paymentEvent = monitor.expectMessageType[PaymentEventReceived].paymentEvent
    assert(paymentEvent.isInstanceOf[PaymentSent] && paymentEvent.paymentHash === invoice.paymentHash)

    // SwapInReceiver reports status of awaiting claim-by-invoice transaction
    swapInReceiver ! GetStatus(userCli.ref)
    monitor.expectMessageType[GetStatus]
    assert(userCli.expectMessageType[SwapStatus].behavior == "claimSwap")

    // SwapInReceiver reports a successful claim by invoice
    swapEvents.expectMessageType[TransactionPublished]
    val claimByInvoiceTx = makeSwapClaimByInvoiceTx((request.amount + agreement.premium).sat, makerPubkey, takerPrivkey, paymentPreimage, feeRatePerKw, openingTxBroadcasted.txid, openingTxBroadcasted.scriptOut.toInt)
    swapInReceiver ! ClaimTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(6), 0, claimByInvoiceTx))
    swapEvents.expectMessageType[ClaimByInvoiceConfirmed]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)

    // the swap result has been recorded in the db
    assert(db.list().head.result.contains("Claimed by paid invoice:"))
  }

  test("happy path for new swap in") { f =>
    import f._

    // start new SwapInReceiver
    swapInReceiver ! StartSwapInReceiver(request)
    monitor.expectMessage(StartSwapInReceiver(request))

    // SwapInReceiver:SwapInAgreement -> SwapInSender
    val agreement = expectSwapMessage[SwapInAgreement](switchboard)

    // Maker:OpeningTxBroadcasted -> Taker
    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txid, scriptOut, blindingKey)
    swapInReceiver ! SwapMessageReceived(openingTxBroadcasted)
    monitor.expectMessageType[SwapMessageReceived]

    // ZmqWatcher -> SwapInReceiver, trigger confirmation of opening transaction
    val openingTx = Transaction(2, Seq(), Seq(makeSwapOpeningTxOut((request.amount + agreement.premium).sat, makerPubkey, takerPubkey, invoice.paymentHash)), 0)
    swapInReceiver ! OpeningTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(1), 0, openingTx))
    monitor.expectMessageType[OpeningTxConfirmed]

    // SwapInReceiver validates invoice and opening transaction before paying the invoice
    assert(paymentInitiator.expectMessageType[SendPaymentToNode] === SendPaymentToNode(invoice.amount_opt.get, invoice, nodeParams.maxPaymentAttempts, Some(swapId), nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams, blockUntilComplete = true))

    // wait for SwapInReceiver to subscribe to PaymentEventReceived messages
    swapEvents.expectNoMessage()

    // SwapInReceiver ignores payments that do not correspond to the invoice from SwapInSender
    testKit.system.eventStream ! Publish(PaymentSent(UUID.randomUUID(), ByteVector32.Zeroes, paymentPreimage, amount.toMilliSatoshi, makerNodeId, PaymentSent.PartialPayment(UUID.randomUUID(), amount.toMilliSatoshi, 0.sat.toMilliSatoshi, channelId, None) :: Nil))
    monitor.expectMessageType[PaymentEventReceived].paymentEvent
    monitor.expectNoMessage()

    // SwapInReceiver commits a claim-by-invoice transaction after successfully paying the invoice from SwapInSender
    testKit.system.eventStream ! Publish(PaymentSent(UUID.randomUUID(), invoice.paymentHash, paymentPreimage, amount.toMilliSatoshi, makerNodeId, PaymentSent.PartialPayment(UUID.randomUUID(), amount.toMilliSatoshi, 0.sat.toMilliSatoshi, channelId, None) :: Nil))
    val paymentEvent = monitor.expectMessageType[PaymentEventReceived].paymentEvent
    assert(paymentEvent.isInstanceOf[PaymentSent] && paymentEvent.paymentHash === invoice.paymentHash)
    monitor.expectMessage(ClaimTxCommitted)

    // SwapInReceiver reports status of awaiting claim by invoice tx to confirm
    swapInReceiver ! GetStatus(userCli.ref)
    monitor.expectMessageType[GetStatus]
    assert(userCli.expectMessageType[SwapStatus].behavior == "claimSwap")

    // SwapInReceiver reports a successful claim by invoice
    swapEvents.expectMessageType[TransactionPublished]
    val claimByInvoiceTx = makeSwapClaimByInvoiceTx((request.amount + agreement.premium).sat, makerPubkey, takerPrivkey, paymentPreimage, feeRatePerKw, openingTx.txid, openingTxBroadcasted.scriptOut.toInt)
    swapInReceiver ! ClaimTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(6), 0, claimByInvoiceTx))
    monitor.expectMessageType[ClaimTxConfirmed]
    swapEvents.expectMessageType[ClaimByInvoiceConfirmed]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)

    // the swap result has been recorded in the db
    assert(db.list().head.result.contains("Claimed by paid invoice:"))
  }

  test("invalid invoice, min_final-cltv-expiry of invoice greater than the claim-by-csv delta") { f =>
    import f._

    // start new SwapInReceiver
    swapInReceiver ! StartSwapInReceiver(request)
    monitor.expectMessage(StartSwapInReceiver(request))

    // SwapInReceiver:SwapInAgreement -> SwapInSender
    val agreement = expectSwapMessage[SwapInAgreement](switchboard)

    // Maker:OpeningTxBroadcasted -> Taker, with payreq invoice where minFinalCltvExpiry >= claimByCsvDelta
    val badInvoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(amount.toMilliSatoshi), Crypto.sha256(paymentPreimage), makerPrivkey, Left("SwapInReceiver invoice - bad min-final-cltv-expiry-delta"), claimByCsvDelta)
    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, badInvoice.toString, txid, scriptOut, blindingKey)
    swapInReceiver ! SwapMessageReceived(openingTxBroadcasted)
    monitor.expectMessageType[SwapMessageReceived]

    // ZmqWatcher -> SwapInReceiver, trigger confirmation of opening transaction
    val openingTx = Transaction(2, Seq(), Seq(makeSwapOpeningTxOut((request.amount + agreement.premium).sat, makerPubkey, takerPubkey, invoice.paymentHash)), 0)
    swapInReceiver ! OpeningTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(1), 0, openingTx))
    monitor.expectMessageType[OpeningTxConfirmed]

    // SwapInReceiver validates fails before paying the invoice
    paymentInitiator.expectNoMessage()

    // SwapInReceiver:CoopClose -> SwapInSender
    val coopClose = expectSwapMessage[CoopClose](switchboard)
    assert(coopClose.message.contains("min-final-cltv-expiry delta too long"))
  }
}
