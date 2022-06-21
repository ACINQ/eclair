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

package fr.acinq.eclair.swap

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
import fr.acinq.eclair.channel.Register.ForwardShortId
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentSent}
import fr.acinq.eclair.swap.SwapCommands._
import fr.acinq.eclair.swap.SwapData.SwapInReceiverData
import fr.acinq.eclair.swap.SwapEvents.{ClaimByInvoiceConfirmed, SwapEvent, TransactionPublished}
import fr.acinq.eclair.swap.SwapResponses.{Status, SwapInStatus}
import fr.acinq.eclair.swap.SwapTransactions.{claimByInvoiceTxWeight, makeSwapClaimByInvoiceTx, makeSwapOpeningTxOut}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol.{OpeningTxBroadcasted, SwapInAgreement, SwapInRequest}
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, NodeParams, ShortChannelId, TestConstants, ToMilliSatoshiConversion, randomBytes32}
import grizzled.slf4j.Logging
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{BeforeAndAfterAll, Outcome}

import java.util.UUID
import scala.concurrent.duration._

// with BitcoindService
case class SwapInReceiverSpec() extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike with BeforeAndAfterAll with Logging {
  override implicit val timeout: Timeout = Timeout(30 seconds)
  val protocolVersion = 1
  val noAsset = ""
  val network: String = NodeParams.chainFromHash(TestConstants.Bob.nodeParams.chainHash)
  val amount: Satoshi = 1000 sat
  val swapId: String = ByteVector32.Zeroes.toHex
  val channelData: DATA_NORMAL = ChannelCodecsSpec.normal
  val shortChannelId: ShortChannelId = channelData.shortIds.real.toOption.get
  val channelId: ByteVector32 = channelData.channelId
  val keyManager: SwapKeyManager = TestConstants.Bob.nodeParams.swapKeyManager
  val makerPrivkey: PrivateKey = PrivateKey(randomBytes32())
  val takerPrivkey: PrivateKey = keyManager.openingPrivateKey(SwapKeyManager.keyPath(swapId)).privateKey
  val makerNodeId: PublicKey = PrivateKey(randomBytes32()).publicKey
  val makerPubkey: PublicKey = makerPrivkey.publicKey
  val takerPubkey: PublicKey = takerPrivkey.publicKey
  val feeRatePerKw: FeeratePerKw = TestConstants.Bob.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = TestConstants.Bob.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)
  val premium: Long = (feeRatePerKw * claimByInvoiceTxWeight / 1000).toLong
  val paymentPreimage: ByteVector32 = ByteVector32.One
  val invoice: Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(amount.toMilliSatoshi), Crypto.sha256(paymentPreimage), makerPrivkey, Left("SwapInReceiver invoice"), CltvExpiryDelta(18))
  val txid: String = ByteVector32.One.toHex
  val scriptOut: Long = 0
  val blindingKey: String = ""
  val request: SwapInRequest = SwapInRequest(protocolVersion, swapId, noAsset, network, shortChannelId.toString, amount.toLong, makerPubkey.toHex)

  override def withFixture(test: OneArgTest): Outcome = {
    val watcher = testKit.createTestProbe[ZmqWatcher.Command]()
    val paymentHandler = testKit.createTestProbe[Any]()
    val register = testKit.createTestProbe[Any]()
    val relayer = testKit.createTestProbe[Any]()
    val router = testKit.createTestProbe[Any]()
    val switchboard = testKit.createTestProbe[Any]()
    val paymentInitiator = testKit.createTestProbe[Any]()

    val wallet = new DummyOnChainWallet()
    val userCli = testKit.createTestProbe[Status]()
    val sender = testKit.createTestProbe[Any]()
    val swapEvents = testKit.createTestProbe[SwapEvent]()
    val monitor = testKit.createTestProbe[SwapCommands.SwapCommand]()

    // subscribe to notification events from SwapInReceiver when a payment is successfully received or claimed via coop or csv
    testKit.system.eventStream ! Subscribe[SwapEvent](swapEvents.ref)

    val swapInReceiver = testKit.spawn(Behaviors.monitor(monitor.ref, SwapInReceiver(request, TestConstants.Bob.nodeParams, paymentInitiator.ref.toClassic, watcher.ref, register.ref.toClassic, wallet)), "swap-in-sender")

    withFixture(test.toNoArgTest(FixtureParam(swapInReceiver, userCli, monitor, register, relayer, router, paymentInitiator, switchboard, paymentHandler, sender, TestConstants.Bob.nodeParams, watcher, wallet, swapEvents)))
  }

  case class FixtureParam(swapInReceiver: ActorRef[SwapCommands.SwapCommand], userCli: TestProbe[Status], monitor: TestProbe[SwapCommands.SwapCommand], register: TestProbe[Any], relayer: TestProbe[Any], router: TestProbe[Any], paymentInitiator: TestProbe[Any], switchboard: TestProbe[Any], paymentHandler: TestProbe[Any], sender: TestProbe[Any], nodeParams: NodeParams, watcher: TestProbe[ZmqWatcher.Command], wallet: OnChainWallet, swapEvents: TestProbe[SwapEvent])

  test("happy path from restored swap") { f =>
    import f._

    // restore the SwapInReceiver actor state from a confirmed on-chain opening tx
    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txid, scriptOut, blindingKey)
    val agreement = SwapInAgreement(protocolVersion, swapId, takerPubkey.toHex, premium)
    val swapData = SwapInReceiverData(request, agreement, invoice, openingTxBroadcasted)
    swapInReceiver ! RestoreSwapInReceiver(swapData)
    monitor.expectMessageType[RestoreSwapInReceiver]

    // SwapInReceiver reports status of awaiting payment
    swapInReceiver ! GetStatus(userCli.ref)
    monitor.expectMessageType[GetStatus]
    assert(userCli.expectMessageType[SwapInStatus].behavior == "awaitOpeningTxConfirmed")

    // ZmqWatcher -> SwapInReceiver, trigger confirmation of opening transaction
    val openingTx = Transaction(2, Seq(), Seq(makeSwapOpeningTxOut((request.amount + agreement.premium).sat, makerPubkey, takerPubkey, invoice.paymentHash)), 0)
    swapInReceiver ! OpeningTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(1), 0, openingTx))
    monitor.expectMessageType[OpeningTxConfirmed]

    // SwapInReceiver validates invoice and opening transaction before paying the invoice
    monitor.expectMessageType[ValidInvoice]
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

    // SwapInReceiver reports a successful claim by invoice
    swapEvents.expectMessageType[TransactionPublished]
    val claimByInvoiceTx = makeSwapClaimByInvoiceTx((request.amount + agreement.premium).sat, makerPubkey, takerPrivkey, paymentPreimage, feeRatePerKw, openingTx.hash, openingTxBroadcasted.scriptOut.toInt)
    swapInReceiver ! ClaimTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(6), 0, claimByInvoiceTx))
    monitor.expectMessageType[ClaimTxConfirmed]
    swapEvents.expectMessageType[ClaimByInvoiceConfirmed]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)
  }

  test("happy path for new swap") { f =>
    import f._

    // start new SwapInSender
    swapInReceiver ! StartSwapInReceiver
    monitor.expectMessage(StartSwapInReceiver)

    // Taker:SwapInAgreement -> Maker
    val agreement = register.expectMessageType[ForwardShortId[SwapInAgreement]].message

    // Maker:OpeningTxBroadcasted -> Taker
    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txid, scriptOut, blindingKey)
    swapInReceiver ! SwapMessageReceived(openingTxBroadcasted)
    monitor.expectMessageType[SwapMessageReceived]

    // ZmqWatcher -> SwapInReceiver, trigger confirmation of opening transaction
    val openingTx = Transaction(2, Seq(), Seq(makeSwapOpeningTxOut((request.amount + agreement.premium).sat, makerPubkey, takerPubkey, invoice.paymentHash)), 0)
    swapInReceiver ! OpeningTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(1), 0, openingTx))
    monitor.expectMessageType[OpeningTxConfirmed]

    // SwapInReceiver validates invoice and opening transaction before paying the invoice
    monitor.expectMessageType[ValidInvoice]
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
    assert(userCli.expectMessageType[SwapInStatus].behavior == "claimSwap")

    // SwapInReceiver reports a successful claim by invoice
    swapEvents.expectMessageType[TransactionPublished]
    val claimByInvoiceTx = makeSwapClaimByInvoiceTx((request.amount + agreement.premium).sat, makerPubkey, takerPrivkey, paymentPreimage, feeRatePerKw, openingTx.hash, openingTxBroadcasted.scriptOut.toInt)
    swapInReceiver ! ClaimTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(6), 0, claimByInvoiceTx))
    monitor.expectMessageType[ClaimTxConfirmed]
    swapEvents.expectMessageType[ClaimByInvoiceConfirmed]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInReceiver)
  }
}