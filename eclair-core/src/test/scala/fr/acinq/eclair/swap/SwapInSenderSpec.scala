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
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Satoshi, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.OnChainWallet.OnChainBalance
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.{DummyOnChainWallet, OnChainWallet}
import fr.acinq.eclair.channel.Register.Forward
import fr.acinq.eclair.channel.{CMD_GET_CHANNEL_DATA, DATA_NORMAL, RES_GET_CHANNEL_DATA}
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentReceived}
import fr.acinq.eclair.swap.SwapCommands._
import fr.acinq.eclair.swap.SwapData.SwapInSenderData
import fr.acinq.eclair.swap.SwapEvents._
import fr.acinq.eclair.swap.SwapResponses.{Status, SwapInStatus}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol.{CoopClose, OpeningTxBroadcasted, SwapInAgreement, SwapInRequest}
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, ShortChannelId, TestConstants, TimestampMilli, ToMilliSatoshiConversion, randomBytes32}
import grizzled.slf4j.Logging
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{BeforeAndAfterAll, Outcome}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

// with BitcoindService
case class SwapInSenderSpec() extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike with BeforeAndAfterAll with Logging {
  override implicit val timeout: Timeout = Timeout(30 seconds)
  val protocolVersion = 2
  val noAsset = ""
  val network: String = Block.RegtestGenesisBlock.hash.toString()
  val amount: Satoshi = 1000 sat
  val swapId: String = ByteVector32.Zeroes.toHex
  val channelData: DATA_NORMAL = ChannelCodecsSpec.normal
  val shortChannelId: ShortChannelId = channelData.shortIds.real.toOption.get
  val channelId: ByteVector32 = channelData.channelId
  val makerPrivkey: PrivateKey = PrivateKey(randomBytes32())
  val takerPrivkey: PrivateKey = PrivateKey(randomBytes32())
  val makerNodeId: PublicKey = PrivateKey(randomBytes32()).publicKey
  val makerPubkey: PublicKey = makerPrivkey.publicKey
  val takerPubkey: PublicKey = takerPrivkey.publicKey
  val premium = 10
  val txid: String = ByteVector32.One.toHex
  val scriptOut: Long = 0
  val blindingKey: String = ""
  val request: SwapInRequest = SwapInRequest(protocolVersion, swapId, noAsset, network, shortChannelId.toString, amount.toLong, makerPubkey.toHex)
  val agreement: SwapInAgreement = SwapInAgreement(protocolVersion, swapId, makerPubkey.toHex, premium)

  override def withFixture(test: OneArgTest): Outcome = {
    val watcher = testKit.createTestProbe[ZmqWatcher.Command]()
    val paymentHandler = testKit.createTestProbe[Any]()
    val register = testKit.createTestProbe[Any]()
    val relayer = testKit.createTestProbe[Any]()
    val router = testKit.createTestProbe[Any]()
    val switchboard = testKit.createTestProbe[Any]()
    val paymentInitiator = testKit.createTestProbe[Any]()
    val wallet = new DummyOnChainWallet() {
      override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(6930 sat, 0 sat))
    }
    val userCli = testKit.createTestProbe[Status]()
    val sender = testKit.createTestProbe[Any]()
    val swapEvents = testKit.createTestProbe[SwapEvent]()
    val monitor = testKit.createTestProbe[SwapCommands.SwapCommand]()

    // subscribe to notification events from SwapInSender when a payment is successfully received or claimed via coop or csv
    testKit.system.eventStream ! Subscribe[SwapEvent](swapEvents.ref)

    val swapInSender = testKit.spawn(Behaviors.monitor(monitor.ref, SwapInSender(TestConstants.Alice.nodeParams, watcher.ref, register.ref.toClassic, wallet)), "swap-in-sender")

    withFixture(test.toNoArgTest(FixtureParam(swapInSender, userCli, monitor, register, relayer, router, paymentInitiator, switchboard, paymentHandler, sender, watcher, wallet, swapEvents)))
  }

  case class FixtureParam(swapInSender: ActorRef[SwapCommands.SwapCommand], userCli: TestProbe[Status], monitor: TestProbe[SwapCommands.SwapCommand], register: TestProbe[Any], relayer: TestProbe[Any], router: TestProbe[Any], paymentInitiator: TestProbe[Any], switchboard: TestProbe[Any], paymentHandler: TestProbe[Any], sender: TestProbe[Any], watcher: TestProbe[ZmqWatcher.Command], wallet: OnChainWallet, swapEvents: TestProbe[SwapEvent])

  test("happy path from restored swap") { f =>
    import f._

    // restore the SwapInSender actor state from a confirmed on-chain opening tx
    val invoice: Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(amount.toMilliSatoshi), ByteVector32.One, makerPrivkey, Left("SwapInSender invoice"), CltvExpiryDelta(18))
    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txid, scriptOut, blindingKey)
    val swapData = SwapInSenderData(channelId, request, agreement, invoice, openingTxBroadcasted)
    swapInSender ! RestoreSwapInSender(swapData)

    // resend OpeningTxBroadcasted when swap restored
    register.expectMessageType[Forward[OpeningTxBroadcasted]]

    // wait for SwapInSender to subscribe to PaymentEventReceived messages
    swapEvents.expectNoMessage()

    // subscribe to notification when SwapInSender successfully receives payment
    val paymentEvent = testKit.createTestProbe[PaymentReceived]()
    testKit.system.eventStream ! Subscribe(paymentEvent.ref)

    // SwapInSender receives a payment with the corresponding payment hash
    val paymentReceived = PaymentReceived(invoice.paymentHash, Seq(PaymentReceived.PartialPayment(amount.toMilliSatoshi, channelId, TimestampMilli(1553784963659L))))
    testKit.system.eventStream ! Publish(paymentReceived)

    // SwapInSender reports a successful payment
    paymentEvent.expectMessageType[PaymentReceived]

    // SwapInSender reports a successful coop close
    swapEvents.expectMessageType[ClaimByInvoicePaid]

    val deathWatcher = testKit.createTestProbe[Any]()
    deathWatcher.expectTerminated(swapInSender)
  }

  test("happy path for new swap") { f =>
    import f._

    // start new SwapInSender
    swapInSender ! StartSwapInSender(amount, swapId, channelId)

    // SwapInSender will first request channel data to get shortChannelId
    val getChannelData = register.expectMessageType[Forward[CMD_GET_CHANNEL_DATA]]
    getChannelData.replyTo.toClassic ! RES_GET_CHANNEL_DATA(channelData)

    // SwapInSender: SwapInRequest -> SwapInSender
    val swapInRequest = register.expectMessageType[Forward[SwapInRequest]]

    // SwapInReceiver: SwapInAgreement -> SwapInSender
    swapInSender ! SwapMessageReceived(SwapInAgreement(swapInRequest.message.protocolVersion, swapInRequest.message.swapId, takerPubkey.toString(), premium))

    // SwapInSender publishes opening tx on-chain
    val openingTx = swapEvents.expectMessageType[TransactionPublished].tx

    // SwapInSender:OpeningTxBroadcasted -> SwapInReceiver
    val openingTxBroadcasted = register.expectMessageType[Forward[OpeningTxBroadcasted]]
    val invoice = Bolt11Invoice.fromString(openingTxBroadcasted.message.payreq).get

    // wait for SwapInSender to subscribe to PaymentEventReceived messages
    swapEvents.expectNoMessage()

    // SwapInSender reports status of awaiting payment
    swapInSender ! GetStatus(userCli.ref)
    assert(userCli.expectMessageType[SwapInStatus].behavior == "awaitClaimPayment")

    // SwapInSender receives a payment with the corresponding payment hash
    // TODO: convert from ShortChannelId to ByteVector32
    val paymentReceived = PaymentReceived(invoice.paymentHash, Seq(PaymentReceived.PartialPayment(amount.toMilliSatoshi, channelId, TimestampMilli(1553784963659L))))
    testKit.system.eventStream ! Publish(paymentReceived)

    // SwapInSender reports a successful coop close
    swapEvents.expectMessageType[ClaimByInvoicePaid]

    // wait for swap actor to stop
    testKit.stop(swapInSender)
  }

  test("claim refund by coop close path from restored swap") { f =>
    import f._

    // restore the SwapInSender actor state from a confirmed on-chain opening tx
    val invoice: Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(amount.toMilliSatoshi), ByteVector32.One, makerPrivkey, Left("SwapInSender invoice"), CltvExpiryDelta(18))
    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txid, scriptOut, blindingKey)
    val swapData = SwapInSenderData(channelId, request, agreement, invoice, openingTxBroadcasted)
    swapInSender ! RestoreSwapInSender(swapData)

    // resend OpeningTxBroadcasted when swap restored
    register.expectMessageType[Forward[OpeningTxBroadcasted]]

    // wait for SwapInSender to subscribe to PaymentEventReceived messages
    swapEvents.expectNoMessage()

    // SwapInReceiver: CoopClose -> SwapInSender
    swapInSender ! SwapMessageReceived(CoopClose(swapId, "oops", takerPrivkey.toHex))

    // SwapInSender confirms that opening tx on-chain
    watcher.expectMessageType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(BlockHeight(1), 0, Transaction(2, Seq(), Seq(), 0))

    // SwapInSender reports status of awaiting claim by cooperative close tx to confirm
    swapInSender ! GetStatus(userCli.ref)
    assert(userCli.expectMessageType[SwapInStatus].behavior == "claimSwapCoop")

    // ZmqWatcher -> SwapInSender, trigger confirmation of coop close transaction
    swapEvents.expectMessageType[TransactionPublished]
    swapInSender ! ClaimTxConfirmed(WatchTxConfirmedTriggered(BlockHeight(6), scriptOut.toInt, Transaction(2, Seq(), Seq(), 0)))

    // SwapInSender reports a successful coop close
    swapEvents.expectMessageType[ClaimByCoopConfirmed]

    // wait for swap actor to stop
    testKit.stop(swapInSender)
  }

  test("claim refund by csv path from restored swap") { f =>
    import f._

    // restore the SwapInSender actor state from a confirmed on-chain opening tx
    val invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(amount.toMilliSatoshi), ByteVector32.One, makerPrivkey, Left("SwapInSender invoice with short expiry"), CltvExpiryDelta(18),
      expirySeconds = Some(2))
    val openingTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txid, scriptOut, blindingKey)
    val swapData = SwapInSenderData(channelId, request, agreement, invoice, openingTxBroadcasted)
    swapInSender ! RestoreSwapInSender(swapData)

    // resend OpeningTxBroadcasted when swap restored
    register.expectMessageType[Forward[OpeningTxBroadcasted]]

    // wait to subscribe to PaymentEventReceived messages
    swapEvents.expectNoMessage()

    // watch for and trigger that the opening tx has been buried by csv delay blocks
    watcher.expectMessageType[WatchFundingDeeplyBuried].replyTo ! WatchFundingDeeplyBuriedTriggered(BlockHeight(0), scriptOut.toInt, Transaction(2, Seq(), Seq(), 0))

    // SwapInSender reports status of awaiting claim by csv tx to confirm
    swapInSender ! GetStatus(userCli.ref)
    assert(userCli.expectMessageType[SwapInStatus].behavior == "claimSwapCsv")

    // watch for and trigger that the claim-by-csv tx has been confirmed on chain
    watcher.expectMessageType[WatchTxConfirmed].replyTo ! WatchTxConfirmedTriggered(BlockHeight(0), scriptOut.toInt, Transaction(2, Seq(), Seq(), 0))

    // SwapInSender reports a successful csv close
    swapEvents.expectMessageType[TransactionPublished]
    swapEvents.expectMessageType[ClaimByCsvConfirmed]

    // wait for swap actor to stop
    testKit.stop(swapInSender)
  }

}
