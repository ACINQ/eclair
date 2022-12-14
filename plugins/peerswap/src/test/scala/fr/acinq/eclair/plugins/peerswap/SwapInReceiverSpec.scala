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
import fr.acinq.eclair.db.OutgoingPaymentStatus.{Failed, Pending}
import fr.acinq.eclair.db.PaymentsDbSpec.alice
import fr.acinq.eclair.db.{OutgoingPayment, OutgoingPaymentStatus, PaymentType}
import fr.acinq.eclair.io.Switchboard.ForwardUnknownMessage
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentFailed, PaymentSent}
import fr.acinq.eclair.plugins.peerswap.SwapCommands._
import fr.acinq.eclair.plugins.peerswap.SwapEvents.{ClaimByCoopOffered, ClaimByInvoiceConfirmed, SwapEvent, TransactionPublished}
import fr.acinq.eclair.plugins.peerswap.SwapResponses.{LightningPaymentFailed, Status}
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
  val paymentId: UUID = UUID.randomUUID()
  val request: SwapInRequest = SwapInRequest(protocolVersion, swapId, noAsset, network, shortChannelId.toString, amount.toLong, makerPubkey.toHex)
  val paymentFailed: PaymentFailed = PaymentFailed(paymentId, invoice.paymentHash, Seq(), 0 unixms)
  val pendingPayment: OutgoingPayment = OutgoingPayment(paymentId, UUID.randomUUID(), Some("1"), invoice.paymentHash, PaymentType.Standard, 123 msat, 123 msat, alice, 1100 unixms, Some(invoice), Pending)
  val openingTxBroadcasted: OpeningTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txid, scriptOut, blindingKey)
  val agreement: SwapInAgreement = SwapInAgreement(protocolVersion, swapId, takerPubkey.toHex, premium)
  def openingTx(agreement: SwapInAgreement): Transaction = Transaction(2, Seq(), Seq(makeSwapOpeningTxOut((request.amount + agreement.premium).sat, makerPubkey, takerPubkey, invoice.paymentHash)), 0)
  def claimByInvoiceTx(agreement: SwapInAgreement): Transaction = makeSwapClaimByInvoiceTx((request.amount + agreement.premium).sat, makerPubkey, takerPrivkey, paymentPreimage, feeRatePerKw, openingTx(agreement).txid, openingTxBroadcasted.scriptOut.toInt)

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
    val nodeParams = TestConstants.Bob.nodeParams
    val remoteNodeId = TestConstants.Bob.nodeParams.nodeId

    // subscribe to notification events from SwapInReceiver when a payment is successfully received or claimed via coop or csv
    testKit.system.eventStream ! Subscribe[SwapEvent](swapEvents.ref)

    val swapInReceiver = testKit.spawn(SwapTaker(remoteNodeId, nodeParams, paymentInitiator.ref.toClassic, watcher.ref, switchboard.ref.toClassic, wallet, keyManager, db), "swap-in-receiver")

    withFixture(test.toNoArgTest(FixtureParam(swapInReceiver, userCli, switchboard, relayer, router, paymentInitiator, paymentHandler, nodeParams, watcher, wallet, swapEvents, remoteNodeId)))
  }

  case class FixtureParam(swapInReceiver: ActorRef[SwapCommands.SwapCommand], userCli: TestProbe[Status], switchboard: TestProbe[Any], relayer: TestProbe[Any], router: TestProbe[Any], paymentInitiator: TestProbe[Any], paymentHandler: TestProbe[Any], nodeParams: NodeParams, watcher: TestProbe[ZmqWatcher.Command], wallet: OnChainWallet, swapEvents: TestProbe[SwapEvent], remoteNodeId: PublicKey)

  test("send cooperative close after a restore with no pending payment") { f =>
    import f._

    val swapData = SwapData(request, agreement, invoice, openingTxBroadcasted, swapRole = SwapRole.Taker, isInitiator = false, remoteNodeId)
    db.add(swapData)
    swapInReceiver ! RestoreSwap(swapData)

    // send the payment because no pending payment was in the database
    paymentInitiator.expectMessageType[SendPaymentToNode]

    // the payment fails
    testKit.system.eventStream ! Publish(paymentFailed)

    // send a cooperative close because the swap maker has committed the opening tx
    assert(expectSwapMessage[CoopClose](switchboard).message == LightningPaymentFailed(swapId, Left(paymentFailed), "swap").toString)

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)

    // the swap result has been recorded in the db
    assert(db.list().head.result == ClaimByCoopOffered(swapId, LightningPaymentFailed(swapId, Left(paymentFailed), "swap").toString).toString)
    db.remove(swapId)
  }

  test("send cooperative close after a restore with the payment already marked as failed") { f =>
    import f._

    // restore the SwapInReceiver actor state from a confirmed on-chain opening tx
    val swapData = SwapData(request, agreement, invoice, openingTxBroadcasted, swapRole = SwapRole.Taker, isInitiator = false, remoteNodeId)
    db.add(swapData)

    // add failed outgoing payment to the payments databases
    nodeParams.db.payments.addOutgoingPayment(pendingPayment)
    nodeParams.db.payments.updateOutgoingPayment(paymentFailed)
    assert(nodeParams.db.payments.listOutgoingPayments(invoice.paymentHash).nonEmpty)

    swapInReceiver ! RestoreSwap(swapData)

    // do not send a payment because a failed payment was in the database
    paymentInitiator.expectNoMessage(100 millis)

    // send a cooperative close because the swap maker has committed the opening tx
    assert(expectSwapMessage[CoopClose](switchboard).message == LightningPaymentFailed(swapId, Right(Failed(Seq(), paymentFailed.timestamp)), "swap").toString)

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)

    // the swap result has been recorded in the db
    assert(db.list().head.result == ClaimByCoopOffered(swapId, LightningPaymentFailed(swapId, Right(Failed(Seq(), 0 unixms)), "swap").toString()).toString)
    db.remove(swapId)
  }

  test("claim by invoice after a restore with the payment already marked as sent") { f =>
    import f._

    // restore the SwapInReceiver actor state from a confirmed on-chain opening tx
    val swapData = SwapData(request, agreement, invoice, openingTxBroadcasted, swapRole = SwapRole.Taker, isInitiator = false, remoteNodeId)
    db.add(swapData)

    // add paid outgoing payment to the payments databases
    val paymentId = UUID.randomUUID()
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(paymentId, UUID.randomUUID(), Some("1"), invoice.paymentHash, PaymentType.Standard, 123 msat, 123 msat, alice, 1100 unixms, Some(invoice), Pending))
    nodeParams.db.payments.updateOutgoingPayment(PaymentSent(paymentId, invoice.paymentHash, paymentPreimage, invoice.amount_opt.get, makerNodeId, Seq(PaymentSent.PartialPayment(paymentId, invoice.amount_opt.get, 0 msat, channelId, None))))
    assert(nodeParams.db.payments.listOutgoingPayments(invoice.paymentHash).nonEmpty)

    swapInReceiver ! RestoreSwap(swapData)

    // do not send a payment because a sent payment was in the database
    paymentInitiator.expectNoMessage(100 millis)

    // SwapInReceiver reports a successful claim by invoice
    swapEvents.expectMessageType[TransactionPublished]
    swapInReceiver ! ClaimTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(6), 0, claimByInvoiceTx(agreement)))
    swapEvents.expectMessageType[ClaimByInvoiceConfirmed]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)

    // the swap result has been recorded in the db
    assert(db.list().head.result == ClaimByInvoiceConfirmed(swapId, WatchTxConfirmedTriggered(BlockHeight(6), 0, claimByInvoiceTx(agreement))).toString)
    db.remove(swapId)
  }

  test("claim by invoice after a restore with the payment marked as pending and later paid") { f =>
    import f._

    // restore the SwapInReceiver actor state from a confirmed on-chain opening tx
    val swapData = SwapData(request, agreement, invoice, openingTxBroadcasted, swapRole = SwapRole.Taker, isInitiator = false, remoteNodeId)
    db.add(swapData)

    // add pending outgoing payment to the payments databases
    nodeParams.db.payments.addOutgoingPayment(OutgoingPayment(UUID.randomUUID(), UUID.randomUUID(), Some("1"), invoice.paymentHash, PaymentType.Standard, 123 msat, 123 msat, alice, 1100 unixms, Some(invoice), OutgoingPaymentStatus.Pending))
    assert(nodeParams.db.payments.listOutgoingPayments(invoice.paymentHash).nonEmpty)

    // restore with pending payment found in the database
    swapInReceiver ! RestoreSwap(swapData)

    // do not send a payment because a pending payment was in the database
    paymentInitiator.expectNoMessage(100 millis)

    // pending payment successfully sent by SwapInReceiver
    testKit.system.eventStream ! Publish(PaymentSent(UUID.randomUUID(), invoice.paymentHash, paymentPreimage, amount.toMilliSatoshi, makerNodeId, PaymentSent.PartialPayment(UUID.randomUUID(), amount.toMilliSatoshi, 0.sat.toMilliSatoshi, channelId, None) :: Nil))

    // SwapInReceiver reports a successful claim by invoice
    swapEvents.expectMessageType[TransactionPublished]
    swapInReceiver ! ClaimTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(6), 0, claimByInvoiceTx(agreement)))
    swapEvents.expectMessageType[ClaimByInvoiceConfirmed]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)

    // the swap result has been recorded in the db
    assert(db.list().head.result.contains("Claimed by paid invoice:"))
    db.remove(swapId)
  }

  test("happy path for new swap in") { f =>
    import f._

    // start new SwapInReceiver
    swapInReceiver ! StartSwapInReceiver(request)

    // SwapInReceiver:SwapInAgreement -> SwapInSender
    val agreement = expectSwapMessage[SwapInAgreement](switchboard)

    // Maker:OpeningTxBroadcasted -> Taker
    swapInReceiver ! SwapMessageReceived(openingTxBroadcasted)

    // ZmqWatcher -> SwapInReceiver, trigger confirmation of opening transaction
    swapInReceiver ! OpeningTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(1), 0, openingTx(agreement)))

    // SwapInReceiver validates invoice and opening transaction before paying the invoice
    assert(paymentInitiator.expectMessageType[SendPaymentToNode] === SendPaymentToNode(invoice.amount_opt.get, invoice, nodeParams.maxPaymentAttempts, Some(swapId), nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams))

    // SwapInReceiver ignores payment events that do not correspond to the invoice from SwapInSender
    testKit.system.eventStream ! Publish(PaymentSent(UUID.randomUUID(), ByteVector32.Zeroes, paymentPreimage, amount.toMilliSatoshi, makerNodeId, PaymentSent.PartialPayment(UUID.randomUUID(), amount.toMilliSatoshi, 0.sat.toMilliSatoshi, channelId, None) :: Nil))

    // SwapInReceiver commits a claim-by-invoice transaction after successfully paying the invoice from SwapInSender
    testKit.system.eventStream ! Publish(PaymentSent(UUID.randomUUID(), invoice.paymentHash, paymentPreimage, amount.toMilliSatoshi, makerNodeId, PaymentSent.PartialPayment(UUID.randomUUID(), amount.toMilliSatoshi, 0.sat.toMilliSatoshi, channelId, None) :: Nil))

    // SwapInReceiver reports a successful claim by invoice
    swapEvents.expectMessageType[TransactionPublished]
    swapInReceiver ! ClaimTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(6), 0, claimByInvoiceTx(agreement)))
    swapEvents.expectMessageType[ClaimByInvoiceConfirmed]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)

    // the swap result has been recorded in the db
    assert(db.list().head.result.contains("Claimed by paid invoice:"))
    db.remove(swapId)
  }

  test("invalid invoice, min_final-cltv-expiry of invoice greater than the claim-by-csv delta") { f =>
    import f._

    // start new SwapInReceiver
    swapInReceiver ! StartSwapInReceiver(request)

    // SwapInReceiver:SwapInAgreement -> SwapInSender
    expectSwapMessage[SwapInAgreement](switchboard)

    // Maker:OpeningTxBroadcasted -> Taker, with payreq invoice where minFinalCltvExpiry >= claimByCsvDelta
    val badInvoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(amount.toMilliSatoshi), Crypto.sha256(paymentPreimage), makerPrivkey, Left("SwapInReceiver invoice - bad min-final-cltv-expiry-delta"), claimByCsvDelta)
    swapInReceiver ! SwapMessageReceived(openingTxBroadcasted.copy(payreq = badInvoice.toString))

    // ZmqWatcher -> SwapInReceiver, trigger confirmation of opening transaction
    swapInReceiver ! OpeningTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(1), 0, openingTx(agreement)))

    // SwapInReceiver fails before paying the invoice
    paymentInitiator.expectNoMessage(100 millis)

    // SwapInReceiver:CoopClose -> SwapInSender
    val coopClose = expectSwapMessage[CoopClose](switchboard)
    assert(coopClose.message.contains("min-final-cltv-expiry delta too long"))
  }
}
