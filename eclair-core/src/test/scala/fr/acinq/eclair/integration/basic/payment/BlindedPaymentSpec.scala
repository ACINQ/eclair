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

package fr.acinq.eclair.integration.basic.payment

import akka.testkit.TestProbe
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features.{KeySend, RouteBlinding}
import fr.acinq.eclair.channel.{DATA_NORMAL, RealScidStatus}
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{connect, getChannelData, getRouterData, knownFundingTxs, nodeParamsFor, openChannel, watcherAutopilot}
import fr.acinq.eclair.integration.basic.fixtures.composite.ThreeNodesFixture
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.receive.MultiPartHandler
import fr.acinq.eclair.payment.receive.MultiPartHandler.{DummyBlindedHop, ReceivingRoute}
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentToNode, SendSpontaneousPayment}
import fr.acinq.eclair.payment.send.PaymentLifecycle
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}
import fr.acinq.eclair.wire.protocol.{IncorrectOrUnknownPaymentDetails, InvalidOnionBlinding}
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, randomBytes32, randomKey}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.{Tag, TestData}
import scodec.bits.HexStringSyntax

import java.util.UUID

class BlindedPaymentSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = ThreeNodesFixture

  val PrivateChannels = "private_channels"
  val RouteBlindingDisabledBob = "route_blinding_disabled_bob"
  val RouteBlindingDisabledCarol = "route_blinding_disabled_carol"

  val maxFinalExpiryDelta = CltvExpiryDelta(1000)

  override def createFixture(testData: TestData): FixtureParam = {
    // seeds have been chosen so that node ids start with 02aaaa for alice, 02bbbb for bob, etc.
    val aliceParams = nodeParamsFor("alice", ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3"))
      .modify(_.features.activated).using(_ + (RouteBlinding -> Optional))
      .modify(_.channelConf.channelFlags.announceChannel).setTo(!testData.tags.contains(PrivateChannels))
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
      .modify(_.features.activated).using(_ + (RouteBlinding -> Optional))
      .modify(_.features.activated).usingIf(testData.tags.contains(RouteBlindingDisabledBob))(_ - RouteBlinding)
      .modify(_.channelConf.channelFlags.announceChannel).setTo(!testData.tags.contains(PrivateChannels))
    val carolParams = nodeParamsFor("carol", ByteVector32(hex"ebd5a5d3abfb3ef73731eb3418d918f247445183180522674666db98a66411cc"))
      .modify(_.features.activated).using(_ + (RouteBlinding -> Optional))
      .modify(_.features.activated).using(_ + (KeySend -> Optional))
      .modify(_.features.activated).usingIf(testData.tags.contains(RouteBlindingDisabledCarol))(_ - RouteBlinding)
      .modify(_.channelConf.channelFlags.announceChannel).setTo(!testData.tags.contains(PrivateChannels))

    val f = ThreeNodesFixture(aliceParams, bobParams, carolParams, testData.name)
    createChannels(f)
    f
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  private def createChannels(f: FixtureParam): Unit = {
    import f._

    alice.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob)))
    bob.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))
    carol.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(bob, carol)))

    connect(alice, bob)
    connect(bob, carol)

    val channelId_ab = openChannel(alice, bob, 500_000 sat).channelId
    val channelId_bc_1 = openChannel(bob, carol, 100_000 sat).channelId
    val channelId_bc_2 = openChannel(bob, carol, 100_000 sat).channelId

    eventually {
      assert(getChannelData(alice, channelId_ab).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])
      assert(getChannelData(bob, channelId_bc_1).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])
      assert(getChannelData(bob, channelId_bc_2).asInstanceOf[DATA_NORMAL].shortIds.real.isInstanceOf[RealScidStatus.Final])
      // Carol must have received Bob's alias to create usable blinded routes to herself.
      assert(getRouterData(carol).privateChannels.values.forall(_.shortIds.remoteAlias_opt.nonEmpty))
    }
  }

  def createInvoice(recipient: MinimalNodeFixture, amount: MilliSatoshi, routes: Seq[ReceivingRoute], sender: TestProbe): Bolt12Invoice = {
    val offerKey = randomKey()
    val offer = Offer(None, "test", offerKey.publicKey, Features.empty, recipient.nodeParams.chainHash)
    val invoiceReq = InvoiceRequest(offer, amount, 1, Features.empty, randomKey(), recipient.nodeParams.chainHash)
    sender.send(recipient.paymentHandler, MultiPartHandler.ReceiveOfferPayment(offerKey, invoiceReq, routes, recipient.router))
    val invoice = sender.expectMsgType[Bolt12Invoice]
    invoice
  }

  def sendPaymentToCarol(f: FixtureParam, payer: MinimalNodeFixture, amount: MilliSatoshi, routes: Seq[ReceivingRoute]): (Bolt12Invoice, PaymentEvent) = {
    import f._

    val sender = TestProbe("sender")
    val invoice = createInvoice(carol, amount, routes, sender)
    sender.send(payer.paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, maxAttempts = 1, routeParams = payer.routeParams, blockUntilComplete = true))
    (invoice, sender.expectMsgType[PaymentEvent])
  }

  def sendPaymentAliceToCarol(f: FixtureParam, amount: MilliSatoshi, routes: Seq[ReceivingRoute]): (Bolt12Invoice, PaymentEvent) = sendPaymentToCarol(f, f.alice, amount, routes)

  def sendPaymentBobToCarol(f: FixtureParam, amount: MilliSatoshi, routes: Seq[ReceivingRoute]): (Bolt12Invoice, PaymentEvent) = sendPaymentToCarol(f, f.bob, amount, routes)

  def verifyPaymentSuccess(invoice: Bolt12Invoice, result: PaymentEvent): PaymentSent = {
    assert(result.isInstanceOf[PaymentSent])
    val payment = result.asInstanceOf[PaymentSent]
    assert(payment.recipientAmount == invoice.amount)
    assert(payment.recipientNodeId == invoice.nodeId)
    assert(payment.parts.map(_.amount).sum == invoice.amount)
    payment
  }

  test("send blinded payment a->b->c") { f =>
    import f._

    val amount = 25_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    val (invoice, result) = sendPaymentAliceToCarol(f, amount, routes)
    val payment = verifyPaymentSuccess(invoice, result)
    assert(payment.parts.length == 1)
  }

  test("send blinded multi-part payment a->b->c") { f =>
    import f._

    val amount = 125_000_000 msat
    val routes = Seq(
      ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta),
      ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta),
    )
    val (invoice, result) = sendPaymentAliceToCarol(f, amount, routes)
    val payment = verifyPaymentSuccess(invoice, result)
    assert(payment.parts.length == 2)
  }

  test("send blinded payment a->b->c with dummy hops") { f =>
    import f._

    val amount = 125_000_000 msat
    val routes = Seq(
      ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta, Seq(DummyBlindedHop(150 msat, 0, CltvExpiryDelta(50)))),
      ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta, Seq(DummyBlindedHop(50 msat, 0, CltvExpiryDelta(20)), DummyBlindedHop(100 msat, 0, CltvExpiryDelta(30)))),
    )
    val (invoice, result) = sendPaymentAliceToCarol(f, amount, routes)
    val payment = verifyPaymentSuccess(invoice, result)
    assert(payment.parts.length == 2)
  }

  test("send blinded payment a->b->c through private channels", Tag(PrivateChannels)) { f =>
    import f._

    val amount = 50_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    val (invoice, result) = sendPaymentAliceToCarol(f, amount, routes)
    verifyPaymentSuccess(invoice, result)
  }

  test("send blinded payment a->b") { f =>
    import f._

    val sender = TestProbe("sender")
    val amount = 75_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId), maxFinalExpiryDelta))
    val invoice = createInvoice(bob, amount, routes, sender)
    sender.send(alice.paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, maxAttempts = 1, routeParams = alice.routeParams, blockUntilComplete = true))
    val payment = sender.expectMsgType[PaymentSent]
    assert(payment.recipientAmount == invoice.amount)
    assert(payment.recipientNodeId == invoice.nodeId)
    assert(payment.parts.map(_.amount).sum == invoice.amount)
  }

  test("send blinded payment a->b with dummy hops") { f =>
    import f._

    val sender = TestProbe("sender")
    val amount = 250_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId), maxFinalExpiryDelta, Seq(DummyBlindedHop(10 msat, 25, CltvExpiryDelta(24)), DummyBlindedHop(5 msat, 10, CltvExpiryDelta(36)))))
    val invoice = createInvoice(bob, amount, routes, sender)
    sender.send(alice.paymentInitiator, SendPaymentToNode(sender.ref, amount, invoice, maxAttempts = 1, routeParams = alice.routeParams, blockUntilComplete = true))
    val payment = sender.expectMsgType[PaymentSent]
    assert(payment.recipientAmount == invoice.amount)
    assert(payment.recipientNodeId == invoice.nodeId)
    assert(payment.parts.map(_.amount).sum == invoice.amount)
  }

  test("send fully blinded payment b->c") { f =>
    import f._

    val amount = 50_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    val (invoice, result) = sendPaymentBobToCarol(f, amount, routes)
    val payment = verifyPaymentSuccess(invoice, result)
    assert(payment.parts.length == 1)
  }

  test("send fully blinded payment b->c with dummy hops") { f =>
    import f._

    val amount = 50_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta, Seq(DummyBlindedHop(25 msat, 250, CltvExpiryDelta(75)))))
    val (invoice, result) = sendPaymentBobToCarol(f, amount, routes)
    val payment = verifyPaymentSuccess(invoice, result)
    assert(payment.parts.length == 1)
  }

  def verifyBlindedFailure(payment: PaymentFailed, expectedNode: PublicKey): Unit = {
    assert(payment.failures.head.isInstanceOf[RemoteFailure])
    val failure = payment.failures.head.asInstanceOf[RemoteFailure]
    assert(failure.e.originNode == expectedNode)
    assert(failure.e.failureMessage.isInstanceOf[InvalidOnionBlinding])
  }

  test("send blinded payment a->b->c failing at b") { f =>
    import f._

    val sender = TestProbe("sender")
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    val invoice = createInvoice(carol, 75_000_000 msat, routes, sender)
    // Bob sends payments to Carol to reduce the liquidity on both of his channels.
    Seq(1, 2).foreach(_ => {
      sender.send(bob.paymentInitiator, SendSpontaneousPayment(50_000_000 msat, carol.nodeId, randomBytes32(), 1, routeParams = bob.routeParams))
      sender.expectMsgType[UUID]
      sender.expectMsgType[PaymentSent]
    })
    // Bob now doesn't have enough funds to relay the payment.
    sender.send(alice.paymentInitiator, SendPaymentToNode(sender.ref, invoice.amount, invoice, maxAttempts = 1, routeParams = alice.routeParams, blockUntilComplete = true))
    val payment = sender.expectMsgType[PaymentFailed]
    verifyBlindedFailure(payment, bob.nodeId)
  }

  test("send blinded payment a->b->c using expired route") { f =>
    import f._

    val sender = TestProbe("sender")
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), CltvExpiryDelta(-500)))
    val invoice = createInvoice(carol, 25_000_000 msat, routes, sender)
    sender.send(alice.paymentInitiator, SendPaymentToNode(sender.ref, invoice.amount, invoice, maxAttempts = 1, routeParams = alice.routeParams, blockUntilComplete = true))
    val payment = sender.expectMsgType[PaymentFailed]
    verifyBlindedFailure(payment, bob.nodeId)
  }

  test("send blinded payment a->b->c failing at c") { f =>
    import f._

    val sender = TestProbe("sender")
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    val invoice = createInvoice(carol, 25_000_000 msat, routes, sender)
    // The amount is below what Carol expects.
    sender.send(alice.paymentInitiator, SendPaymentToNode(sender.ref, 20_000_000 msat, invoice, maxAttempts = 1, routeParams = alice.routeParams, blockUntilComplete = true))
    val payment = sender.expectMsgType[PaymentFailed]
    verifyBlindedFailure(payment, bob.nodeId)
  }

  test("send blinded payment a->b failing at b") { f =>
    import f._

    val sender = TestProbe("sender")
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId), maxFinalExpiryDelta))
    val invoice = createInvoice(bob, 50_000_000 msat, routes, sender)
    // The amount is below what Bob expects: since he is both the introduction node and the final recipient, he sends
    // back a normal error.
    sender.send(alice.paymentInitiator, SendPaymentToNode(sender.ref, 25_000_000 msat, invoice, maxAttempts = 1, routeParams = alice.routeParams, blockUntilComplete = true))
    val payment = sender.expectMsgType[PaymentFailed]
    assert(payment.failures.head.isInstanceOf[RemoteFailure])
    val failure = payment.failures.head.asInstanceOf[RemoteFailure]
    assert(failure.e.originNode == bob.nodeId)
    assert(failure.e.failureMessage.isInstanceOf[IncorrectOrUnknownPaymentDetails])
    assert(failure.e.failureMessage.asInstanceOf[IncorrectOrUnknownPaymentDetails].amount == 25_000_000.msat)
  }

  test("send blinded payment a->b with dummy hops failing at b") { f =>
    import f._

    val sender = TestProbe("sender")
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId), maxFinalExpiryDelta, Seq(DummyBlindedHop(1 msat, 100, CltvExpiryDelta(48)))))
    val invoice = createInvoice(bob, 50_000_000 msat, routes, sender)
    // The amount is below what Bob expects: since he is both the introduction node and the final recipient, he sends
    // back a normal error.
    sender.send(alice.paymentInitiator, SendPaymentToNode(sender.ref, 25_000_000 msat, invoice, maxAttempts = 1, routeParams = alice.routeParams, blockUntilComplete = true))
    val payment = sender.expectMsgType[PaymentFailed]
    assert(payment.failures.head.isInstanceOf[RemoteFailure])
    val failure = payment.failures.head.asInstanceOf[RemoteFailure]
    assert(failure.e.originNode == bob.nodeId)
    assert(failure.e.failureMessage.isInstanceOf[IncorrectOrUnknownPaymentDetails])
    assert(failure.e.failureMessage.asInstanceOf[IncorrectOrUnknownPaymentDetails].amount == 25_000_000.msat)
  }

  test("send fully blinded payment b->c failing at c") { f =>
    import f._

    val sender = TestProbe("sender")
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    val invoice = createInvoice(carol, 50_000_000 msat, routes, sender)
    // The amount is below what Carol expects.
    sender.send(bob.paymentInitiator, SendPaymentToNode(sender.ref, 45_000_000 msat, invoice, maxAttempts = 1, routeParams = bob.routeParams, blockUntilComplete = true))
    val payment = sender.expectMsgType[PaymentFailed]
    assert(payment.failures.head.isInstanceOf[LocalFailure])
    val failure = payment.failures.head.asInstanceOf[LocalFailure]
    assert(failure.t == PaymentLifecycle.UpdateMalformedException)
  }

}
