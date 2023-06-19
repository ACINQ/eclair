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

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
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
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient, buildRoute}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.offer.OfferManager
import fr.acinq.eclair.payment.receive.MultiPartHandler.{DummyBlindedHop, ReceivingRoute}
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentToNode, SendSpontaneousPayment}
import fr.acinq.eclair.payment.send.{OfferPayment, PaymentLifecycle}
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.wire.protocol.OfferTypes.{Offer, OfferPaths}
import fr.acinq.eclair.wire.protocol.{IncorrectOrUnknownPaymentDetails, InvalidOnionBlinding}
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, randomBytes32, randomKey}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.{Tag, TestData}
import scodec.bits.HexStringSyntax

import java.util.UUID

class OfferPaymentSpec extends FixtureSpec with IntegrationPatience {

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
    createChannels(f, testData)
    f
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  private def createChannels(f: FixtureParam, testData: TestData): Unit = {
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
      assert(getRouterData(alice).channels.size == 3 || testData.tags.contains(PrivateChannels))
      // Carol must have received Bob's alias to create usable blinded routes to herself.
      assert(getRouterData(carol).privateChannels.values.forall(_.shortIds.remoteAlias_opt.nonEmpty))
    }
  }

  def offerHandler(amount: MilliSatoshi, routes: Seq[ReceivingRoute]): Behavior[OfferManager.HandlerCommand] = {
    Behaviors.receiveMessage {
      case OfferManager.HandleInvoiceRequest(replyTo, _) =>
        replyTo ! OfferManager.InvoiceRequestActor.ApproveRequest(amount, routes)
        Behaviors.same
      case OfferManager.HandlePayment(replyTo, _, _) =>
        replyTo ! OfferManager.PaymentActor.AcceptPayment()
        Behaviors.same
    }
  }

  def sendOfferPayment(f: FixtureParam, payer: MinimalNodeFixture, recipient: MinimalNodeFixture, amount: MilliSatoshi, routes: Seq[ReceivingRoute]): (Offer, PaymentEvent) = {
    import f._

    val sender = TestProbe("sender")
    val offer = Offer(None, "test", recipient.nodeId, Features.empty, recipient.nodeParams.chainHash)
    val handler = recipient.system.spawnAnonymous(offerHandler(amount, routes))
    recipient.offerManager ! OfferManager.RegisterOffer(offer, recipient.nodeParams.privateKey, None, handler)
    val offerPayment = payer.system.spawnAnonymous(OfferPayment(payer.nodeParams, payer.postman, payer.paymentInitiator))
    val sendPaymentConfig = OfferPayment.SendPaymentConfig(None, connectDirectly = false, maxAttempts = 1, payer.routeParams, blocking = true)
    offerPayment ! OfferPayment.PayOffer(sender.ref, offer, amount, 1, sendPaymentConfig)
    (offer, sender.expectMsgType[PaymentEvent])
  }

  def sendPrivateOfferPayment(f: FixtureParam, payer: MinimalNodeFixture, recipient: MinimalNodeFixture, amount: MilliSatoshi, routes: Seq[ReceivingRoute]): (Offer, PaymentEvent) = {
    import f._

    val sender = TestProbe("sender")
    val recipientKey = randomKey()
    val pathId = randomBytes32()
    val offerPaths = routes.map(route => {
      route.nodes.dropRight(1).map(IntermediateNode(_))
      buildRoute(randomKey(), route.nodes.dropRight(1).map(IntermediateNode(_)), Recipient(route.nodes.last, Some(pathId)))
    })
    val offer = Offer(None, "test", recipientKey.publicKey, Features.empty, recipient.nodeParams.chainHash, additionalTlvs = Set(OfferPaths(offerPaths)))
    val handler = recipient.system.spawnAnonymous(offerHandler(amount, routes))
    recipient.offerManager ! OfferManager.RegisterOffer(offer, recipientKey, Some(pathId), handler)
    val offerPayment = payer.system.spawnAnonymous(OfferPayment(payer.nodeParams, payer.postman, payer.paymentInitiator))
    val sendPaymentConfig = OfferPayment.SendPaymentConfig(None, connectDirectly = false, maxAttempts = 1, payer.routeParams, blocking = true)
    offerPayment ! OfferPayment.PayOffer(sender.ref, offer, amount, 1, sendPaymentConfig)
    (offer, sender.expectMsgType[PaymentEvent])
  }

  def sendOfferPaymentWithInvalidAmount(f: FixtureParam, payer: MinimalNodeFixture, recipient: MinimalNodeFixture, payerAmount: MilliSatoshi, recipientAmount: MilliSatoshi, routes: Seq[ReceivingRoute]): PaymentFailed = {
    import f._

    val sender = TestProbe("sender")
    val paymentInterceptor = TestProbe("payment-interceptor")
    val offer = Offer(None, "test", recipient.nodeId, Features.empty, recipient.nodeParams.chainHash)
    val handler = recipient.system.spawnAnonymous(offerHandler(recipientAmount, routes))
    recipient.offerManager ! OfferManager.RegisterOffer(offer, recipient.nodeParams.privateKey, None, handler)
    val offerPayment = payer.system.spawnAnonymous(OfferPayment(payer.nodeParams, payer.postman, paymentInterceptor.ref))
    val sendPaymentConfig = OfferPayment.SendPaymentConfig(None, connectDirectly = false, maxAttempts = 1, payer.routeParams, blocking = true)
    offerPayment ! OfferPayment.PayOffer(sender.ref, offer, recipientAmount, 1, sendPaymentConfig)
    // We intercept the payment and modify it to use a different amount.
    val payment = paymentInterceptor.expectMsgType[SendPaymentToNode]
    payer.paymentInitiator ! payment.copy(recipientAmount = payerAmount)
    sender.expectMsgType[PaymentFailed]
  }

  def verifyPaymentSuccess(offer: Offer, amount: MilliSatoshi, result: PaymentEvent): PaymentSent = {
    assert(result.isInstanceOf[PaymentSent])
    val payment = result.asInstanceOf[PaymentSent]
    assert(payment.recipientAmount == amount)
    assert(payment.recipientNodeId == offer.nodeId)
    assert(payment.parts.map(_.amount).sum == amount)
    payment
  }

  test("send blinded payment a->b->c") { f =>
    import f._

    val amount = 25_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    val (offer, result) = sendOfferPayment(f, alice, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
  }

  test("send blinded multi-part payment a->b->c") { f =>
    import f._

    val amount = 125_000_000 msat
    val routes = Seq(
      ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta),
      ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta),
    )
    val (offer, result) = sendOfferPayment(f, alice, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 2)
  }

  test("send blinded payment a->b->c with dummy hops") { f =>
    import f._

    val amount = 125_000_000 msat
    val routes = Seq(
      ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta, Seq(DummyBlindedHop(150 msat, 0, CltvExpiryDelta(50)))),
      ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta, Seq(DummyBlindedHop(50 msat, 0, CltvExpiryDelta(20)), DummyBlindedHop(100 msat, 0, CltvExpiryDelta(30)))),
    )
    val (offer, result) = sendOfferPayment(f, alice, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 2)
  }

  test("send blinded payment a->b->c through private channels", Tag(PrivateChannels)) { f =>
    import f._

    val amount = 50_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    val (offer, result) = sendPrivateOfferPayment(f, alice, carol, amount, routes)
    verifyPaymentSuccess(offer, amount, result)
  }

  test("send blinded payment a->b") { f =>
    import f._

    val amount = 75_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId), maxFinalExpiryDelta))
    val (offer, result) = sendOfferPayment(f, alice, bob, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
  }

  test("send blinded payment a->b with dummy hops") { f =>
    import f._

    val amount = 250_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId), maxFinalExpiryDelta, Seq(DummyBlindedHop(10 msat, 25, CltvExpiryDelta(24)), DummyBlindedHop(5 msat, 10, CltvExpiryDelta(36)))))
    val (offer, result) = sendOfferPayment(f, alice, bob, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
  }

  test("send fully blinded payment b->c") { f =>
    import f._

    val amount = 50_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    val (offer, result) = sendOfferPayment(f, bob, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
  }

  test("send fully blinded payment b->c with dummy hops") { f =>
    import f._

    val amount = 50_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta, Seq(DummyBlindedHop(25 msat, 250, CltvExpiryDelta(75)))))
    val (offer, result) = sendOfferPayment(f, bob, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
  }

  def verifyBlindedFailure(payment: PaymentEvent, expectedNode: PublicKey): Unit = {
    assert(payment.isInstanceOf[PaymentFailed])
    val failed = payment.asInstanceOf[PaymentFailed]
    assert(failed.failures.head.isInstanceOf[RemoteFailure])
    val failure = failed.failures.head.asInstanceOf[RemoteFailure]
    assert(failure.e.originNode == expectedNode)
    assert(failure.e.failureMessage.isInstanceOf[InvalidOnionBlinding])
  }

  test("send blinded payment a->b->c failing at b") { f =>
    import f._

    val sender = TestProbe("sender")
    // Bob sends payments to Carol to reduce the liquidity on both of his channels.
    Seq(1, 2).foreach(_ => {
      sender.send(bob.paymentInitiator, SendSpontaneousPayment(50_000_000 msat, carol.nodeId, randomBytes32(), 1, routeParams = bob.routeParams))
      sender.expectMsgType[UUID]
      sender.expectMsgType[PaymentSent]
    })
    // Bob now doesn't have enough funds to relay the payment.
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    val (_, result) = sendOfferPayment(f, alice, carol, 75_000_000 msat, routes)
    verifyBlindedFailure(result, bob.nodeId)
  }

  test("send blinded payment a->b->c using expired route") { f =>
    import f._

    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), CltvExpiryDelta(-500)))
    val (_, result) = sendOfferPayment(f, alice, carol, 25_000_000 msat, routes)
    verifyBlindedFailure(result, bob.nodeId)
  }

  test("send blinded payment a->b->c failing at c") { f =>
    import f._

    val payerAmount = 20_000_000 msat
    val recipientAmount = 25_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    // The amount is below what Carol expects.
    val payment = sendOfferPaymentWithInvalidAmount(f, alice, carol, payerAmount, recipientAmount, routes)
    verifyBlindedFailure(payment, bob.nodeId)
  }

  test("send blinded payment a->b failing at b") { f =>
    import f._

    val payerAmount = 25_000_000 msat
    val recipientAmount = 50_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId), maxFinalExpiryDelta))
    // The amount is below what Bob expects: since he is both the introduction node and the final recipient, he sends
    // back a normal error.
    val payment = sendOfferPaymentWithInvalidAmount(f, alice, bob, payerAmount, recipientAmount, routes)
    assert(payment.failures.head.isInstanceOf[RemoteFailure])
    val failure = payment.failures.head.asInstanceOf[RemoteFailure]
    assert(failure.e.originNode == bob.nodeId)
    assert(failure.e.failureMessage.isInstanceOf[IncorrectOrUnknownPaymentDetails])
    assert(failure.e.failureMessage.asInstanceOf[IncorrectOrUnknownPaymentDetails].amount == payerAmount)
  }

  test("send blinded payment a->b with dummy hops failing at b") { f =>
    import f._

    val payerAmount = 25_000_000 msat
    val recipientAmount = 50_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId), maxFinalExpiryDelta, Seq(DummyBlindedHop(1 msat, 100, CltvExpiryDelta(48)))))
    // The amount is below what Bob expects: since he is both the introduction node and the final recipient, he sends
    // back a normal error.
    val payment = sendOfferPaymentWithInvalidAmount(f, alice, bob, payerAmount, recipientAmount, routes)
    assert(payment.failures.head.isInstanceOf[RemoteFailure])
    val failure = payment.failures.head.asInstanceOf[RemoteFailure]
    assert(failure.e.originNode == bob.nodeId)
    assert(failure.e.failureMessage.isInstanceOf[IncorrectOrUnknownPaymentDetails])
    assert(failure.e.failureMessage.asInstanceOf[IncorrectOrUnknownPaymentDetails].amount == payerAmount)
  }

  test("send fully blinded payment b->c failing at c") { f =>
    import f._

    val payerAmount = 45_000_000 msat
    val recipientAmount = 50_000_000 msat
    val routes = Seq(ReceivingRoute(Seq(bob.nodeId, carol.nodeId), maxFinalExpiryDelta))
    // The amount is below what Carol expects.
    val payment = sendOfferPaymentWithInvalidAmount(f, bob, carol, payerAmount, recipientAmount, routes)
    assert(payment.failures.head.isInstanceOf[LocalFailure])
    val failure = payment.failures.head.asInstanceOf[LocalFailure]
    assert(failure.t == PaymentLifecycle.UpdateMalformedException)
  }

}
