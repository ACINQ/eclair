/*
 * Copyright 2026 ACINQ SAS
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

package fr.acinq.eclair.integration.basic.payment

import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.testkit.TestProbe
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.channel.NORMAL
import fr.acinq.eclair.db.IncomingPaymentStatus
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{connect, getChannelState, getRouterData, knownFundingTxs, nodeParamsFor, openChannel, watcherAutopilot}
import fr.acinq.eclair.integration.basic.fixtures.composite.FourNodesFixture
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceiveStandardPayment
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.payment.send.PaymentInitiator.SendTrampolinePayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshiLong, nodeFee}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.{Tag, TestData}
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

/**
 * Trampoline payments where the recipient is a wallet that can only be reached through a routing hint containing a
 * large fee, which is how service providers usually get paid when their users receive payments.
 *
 * Alice --- Bob --- Carol --- Dave
 *
 * Alice is a wallet sending a trampoline payment, Bob is the trampoline node, Carol is Dave's service provider and
 * Dave is a wallet receiving the payment.
 */
class TrampolinePaymentSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = FourNodesFixture

  private val IgnoreLocalFees = "ignore_local_fees"

  private val amount = 100_000_000 msat
  // Bob charges a large fee on the channels it uses to relay the payment.
  private val bobRelayFees = RelayFees(feeBase = 1000 msat, feeProportionalMillionths = 2000)
  // Carol advertises a larger fee than its actual relay fee in the routing hints of Dave's invoices.
  private val carolHintFees = RelayFees(feeBase = 1000 msat, feeProportionalMillionths = 1000)
  // The TrampolinePaymentLifecycle test actor pays a 0.2% trampoline fee on its first attempt and doubles it on every
  // retry: we use a value that only allows one attempt.
  private val maxTrampolineFee = 250_000 msat

  override def createFixture(testData: TestData): FixtureParam = {
    val aliceParams = nodeParamsFor("alice", ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3"))
      .modify(_.channelConf.channelFlags.announceChannel).setTo(false)
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
      .modify(_.channelConf.channelFlags.announceChannel).setTo(false)
      .modify(_.channelConf.expiryDelta).setTo(CltvExpiryDelta(48))
      .modify(_.enableTrampolinePayment).setTo(true)
      .modify(_.relayParams.privateChannelFees).setTo(bobRelayFees)
      .modify(_.relayParams.trampolineIgnoreLocalFees).setTo(testData.tags.contains(IgnoreLocalFees))
    val carolParams = nodeParamsFor("carol", ByteVector32(hex"ebd5a5d3abfb3ef73731eb3418d918f247445183180522674666db98a66411cc"))
      .modify(_.channelConf.channelFlags.announceChannel).setTo(false)
      .modify(_.channelConf.expiryDelta).setTo(CltvExpiryDelta(48))
    val daveParams = nodeParamsFor("dave", ByteVector32(hex"9451f9b0f0b1b6b6ba4ba4b4a0eb0af4d5e1b8ffa8bd0a04a72af9b64a1f7c58"))
      .modify(_.channelConf.channelFlags.announceChannel).setTo(false)

    val f = FourNodesFixture(aliceParams, bobParams, carolParams, daveParams, testData.name)
    import f._

    Seq(alice, bob, carol, dave).foreach(_.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol, dave))))

    connect(alice, bob)
    connect(bob, carol)
    connect(carol, dave)
    val channelId_ab = openChannel(alice, bob, 500_000 sat).channelId
    val channelId_bc = openChannel(bob, carol, 500_000 sat).channelId
    val channelId_cd = openChannel(carol, dave, 500_000 sat).channelId
    eventually {
      assert(Seq((alice, channelId_ab), (bob, channelId_ab), (bob, channelId_bc), (carol, channelId_bc), (carol, channelId_cd), (dave, channelId_cd)).forall {
        case (node, channelId) => getChannelState(node, channelId) == NORMAL
      })
    }

    f
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  /** Dave creates an invoice containing a routing hint for its channel with Carol, where Carol inflated its relay fee. */
  private def createInvoiceWithExpensiveHint(f: FixtureParam): Bolt11Invoice = {
    import f._
    val sender = TestProbe("sender")
    val hint = eventually {
      getRouterData(dave).privateChannels.values.head.toIncomingExtraHop.get
    }.copy(feeBase = carolHintFees.feeBase, feeProportionalMillionths = carolHintFees.feeProportionalMillionths)
    sender.send(dave.paymentHandler, ReceiveStandardPayment(sender.ref.toTyped, Some(amount), Left("trampoline to a wallet"), extraHops = List(List(hint))))
    sender.expectMsgType[Bolt11Invoice]
  }

  private def sendTrampolinePayment(f: FixtureParam, invoice: Bolt11Invoice): Either[PaymentFailed, PaymentSent] = {
    import f._
    val sender = TestProbe("sender")
    val routeParams = alice.routeParams
      .modify(_.boundaries.maxFeeFlat).setTo(maxTrampolineFee)
      .modify(_.boundaries.maxFeeProportional).setTo(0.0)
    sender.send(alice.paymentInitiator, SendTrampolinePayment(sender.ref, invoice, bob.nodeId, routeParams, blockUntilComplete = true))
    sender.expectMsgType[PaymentEvent](60 seconds) match {
      case e: PaymentSent => Right(e)
      case e: PaymentFailed => Left(e)
      case e => fail(s"unexpected payment event: $e")
    }
  }

  test("relay trampoline payment to a wallet behind an expensive routing hint", Tag(IgnoreLocalFees)) { f =>
    import f._

    val invoice = createInvoiceWithExpensiveHint(f)
    val relayListener = TestProbe("relay-listener")
    bob.system.eventStream.subscribe(relayListener.ref, classOf[TrampolinePaymentRelayed])

    val paymentSent = sendTrampolinePayment(f, invoice) match {
      case Right(paymentSent) => paymentSent
      case Left(paymentFailed) => fail(s"payment should not have failed: $paymentFailed")
    }
    assert(paymentSent.recipientAmount == amount)
    assert(paymentSent.feesPaid == amount * 0.002)
    assert(dave.nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status.isInstanceOf[IncomingPaymentStatus.Received]))

    // Bob relayed the payment, but earned less than the relay fee of its outgoing channel: it paid Carol's inflated
    // routing hint fee out of the trampoline fee it received.
    val relayed = relayListener.expectMsgType[TrampolinePaymentRelayed]
    assert(relayed.paymentHash == invoice.paymentHash)
    assert(relayed.amountOut >= amount + nodeFee(carolHintFees, amount))
    assert(relayed.relayFee > 0.msat)
    assert(relayed.relayFee < nodeFee(bobRelayFees, amount))
  }

  test("fail to relay trampoline payment when the routing hint fee is too high") { f =>
    import f._

    val invoice = createInvoiceWithExpensiveHint(f)
    // Bob cannot relay the payment: once Carol's routing hint fee is paid, the trampoline fee doesn't cover the relay
    // fee of Bob's outgoing channel.
    sendTrampolinePayment(f, invoice) match {
      case Right(paymentSent) => fail(s"payment should have failed: $paymentSent")
      case Left(_) => ()
    }
    assert(dave.nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).exists(_.status == IncomingPaymentStatus.Pending))
  }

}
