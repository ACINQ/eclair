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
import akka.actor.typed.eventstream.EventStream.{Publish, Subscribe}
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.blockchain.OnChainWallet.OnChainBalance
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{DummyOnChainWallet, OnChainWallet}
import fr.acinq.eclair.channel.DATA_NORMAL
import fr.acinq.eclair.channel.Register.ForwardShortId
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentReceived}
import fr.acinq.eclair.swap.SwapData.SwapData
import fr.acinq.eclair.swap.SwapEvents.{ClaimByInvoicePaid, SwapEvent, TransactionPublished}
import fr.acinq.eclair.swap.SwapRegister.{MessageReceived, SwapInRequested, SwapTerminated}
import fr.acinq.eclair.swap.SwapResponses.{Response, SwapOpened}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol.{OpeningTxBroadcasted, SwapInAgreement, SwapInRequest}
import fr.acinq.eclair.{CltvExpiryDelta, NodeParams, ShortChannelId, TestConstants, TimestampMilli, ToMilliSatoshiConversion, randomBytes32}
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{BeforeAndAfterAll, Outcome, ParallelTestExecution}
import scodec.bits.HexStringSyntax

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class SwapRegisterSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with BeforeAndAfterAll with Matchers with FixtureAnyFunSuiteLike with IdiomaticMockito with ParallelTestExecution {
  override implicit val timeout: Timeout = Timeout(30 seconds)
  val protocolVersion = 2
  val noAsset = ""
  val network: String = NodeParams.chainFromHash(TestConstants.Alice.nodeParams.chainHash)
  val amount: Satoshi = 1000 sat
  val swapId: String = ByteVector32.Zeroes.toHex
  val channelData: DATA_NORMAL = ChannelCodecsSpec.normal
  val shortChannelId: ShortChannelId = channelData.shortIds.real.toOption.get
  val channelId: ByteVector32 = channelData.channelId
  val bobPayoutPubkey: PublicKey = PublicKey(hex"0270685ca81a8e4d4d01beec5781f4cc924684072ae52c507f8ebe9daf0caaab7b")
  val premium = 10
  val scriptOut = 0
  val blindingKey = ""
  val txId: String = ByteVector32.One.toHex

  val alicePrivkey: PrivateKey = PrivateKey(randomBytes32())
  val alicePubkey: PublicKey = alicePrivkey.publicKey
  val bobPubkey: PublicKey = PrivateKey(randomBytes32()).publicKey
  val invoice: Bolt11Invoice = Bolt11Invoice(TestConstants.Alice.nodeParams.chainHash, Some(amount.toMilliSatoshi), ByteVector32.One, alicePrivkey, Left("PeerSwap invoice"), CltvExpiryDelta(18))
  val feeRatePerKw: FeeratePerKw = TestConstants.Alice.nodeParams.onChainFeeConf.feeEstimator.getFeeratePerKw(target = TestConstants.Alice.nodeParams.onChainFeeConf.feeTargets.fundingBlockTarget)

  override def withFixture(test: OneArgTest): Outcome = {
    val userCli = testKit.createTestProbe[Response]()
    val swapEvents = testKit.createTestProbe[SwapEvent]()
    val register = testKit.createTestProbe[Any]()
    val monitor = testKit.createTestProbe[SwapRegister.Command]()
    val paymentHandler = testKit.createTestProbe[Any]()
    val wallet = new DummyOnChainWallet() {
      override def onChainBalance()(implicit ec: ExecutionContext): Future[OnChainBalance] = Future.successful(OnChainBalance(6930 sat, 0 sat))
    }
    val watcher = testKit.createTestProbe[ZmqWatcher.Command]()

    // subscribe to notification events from SwapInSender when a payment is successfully received or claimed via coop or csv
    testKit.system.eventStream ! Subscribe[SwapEvent](swapEvents.ref)

    withFixture(test.toNoArgTest(FixtureParam(userCli, swapEvents, register, monitor, paymentHandler, wallet, watcher)))
  }

  case class FixtureParam(userCli: TestProbe[Response], swapEvents: TestProbe[SwapEvent], register: TestProbe[Any], monitor: TestProbe[SwapRegister.Command], paymentHandler: TestProbe[Any], wallet: OnChainWallet, watcher: TestProbe[ZmqWatcher.Command])

  test("restore the swap register from the database") { f =>
    import f._

    val swapInRequest: SwapInRequest = SwapInRequest(protocolVersion, swapId, noAsset, network, shortChannelId.toString, amount.toLong, alicePubkey.toString())
    val swapInAgreement: SwapInAgreement = SwapInAgreement(protocolVersion, swapId, bobPubkey.toString(), premium)
    val openingTxBroadcasted: OpeningTxBroadcasted = OpeningTxBroadcasted(swapId, invoice.toString, txId, scriptOut, blindingKey)
    val savedData: Set[SwapData] = Set(SwapData(swapInRequest, swapInAgreement, invoice, openingTxBroadcasted, isInitiator = true))
    val swapRegister = testKit.spawn(Behaviors.monitor(monitor.ref, SwapRegister(TestConstants.Alice.nodeParams, paymentHandler.ref.toClassic, watcher.ref, register.ref.toClassic, wallet, savedData)), "SwapRegister")

    // wait for SwapInSender to subscribe to PaymentEventReceived messages
    swapEvents.expectNoMessage()

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

  test("register a new swap in the swap register ") { f =>
    import f._

    // initialize SwapRegister
    val swapRegister = testKit.spawn(Behaviors.monitor(monitor.ref, SwapRegister(TestConstants.Alice.nodeParams, paymentHandler.ref.toClassic, watcher.ref, register.ref.toClassic, wallet)), "SwapRegister")
    swapEvents.expectNoMessage()
    userCli.expectNoMessage()

    // User:SwapInRequested -> SwapInRegister
    swapRegister ! SwapInRequested(userCli.ref, amount, shortChannelId)
    val swapId = userCli.expectMessageType[SwapOpened].swapId
    monitor.expectMessageType[SwapInRequested]

    // Alice:SwapInRequest -> Bob
    val swapInRequest = register.expectMessageType[ForwardShortId[SwapInRequest]]
    assert(swapId === swapInRequest.message.swapId)

    // Bob: SwapInAgreement -> Alice
    swapRegister ! MessageReceived(SwapInAgreement(swapInRequest.message.protocolVersion, swapInRequest.message.swapId, bobPayoutPubkey.toString(), premium))
    monitor.expectMessageType[MessageReceived]

    // SwapInSender confirms opening tx published
    swapEvents.expectMessageType[TransactionPublished]

    // Alice:OpeningTxBroadcasted -> Bob
    val openingTxBroadcasted = register.expectMessageType[ForwardShortId[OpeningTxBroadcasted]]

    // Bob: payment(paymentHash) -> Alice
    val paymentHash = Bolt11Invoice.fromString(openingTxBroadcasted.message.payreq).get.paymentHash
    val paymentReceived = PaymentReceived(paymentHash, Seq(PaymentReceived.PartialPayment(amount.toMilliSatoshi, channelId, TimestampMilli(1553784963659L))))
    testKit.system.eventStream ! Publish(paymentReceived)

    // SwapRegister received notice that SwapInSender completed
    assert(swapEvents.expectMessageType[ClaimByInvoicePaid].swapId === swapId)

    // SwapRegister receives notification that the swap actor stopped
    assert(monitor.expectMessageType[SwapTerminated].swapId === swapId)

    testKit.stop(swapRegister)
  }

}
