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
import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, ClassicActorSystemOps}
import akka.testkit.TestProbe
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features.{KeySend, RouteBlinding}
import fr.acinq.eclair.channel.{DATA_NORMAL, NORMAL}
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture
import fr.acinq.eclair.integration.basic.fixtures.MinimalNodeFixture.{connect, getChannelState, getPeerChannels, getRouterData, knownFundingTxs, nodeParamsFor, openChannel, sendPayment, watcherAutopilot}
import fr.acinq.eclair.integration.basic.fixtures.composite.ThreeNodesFixture
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient, buildRoute}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.offer.OfferManager.InvoiceRequestActor
import fr.acinq.eclair.payment.offer.{OfferCreator, OfferManager}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.payment.send.OfferPayment
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentToNode, SendSpontaneousPayment}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.ChannelHop
import fr.acinq.eclair.testutils.FixtureSpec
import fr.acinq.eclair.wire.protocol.OfferTypes.{BlindedPath, Offer, OfferPaths}
import fr.acinq.eclair.wire.protocol.{IncorrectOrUnknownPaymentDetails, InvalidOnionBlinding}
import fr.acinq.eclair.{CltvExpiryDelta, EncodedNodeId, Features, MilliSatoshi, MilliSatoshiLong, ShortChannelId, randomBytes32, randomKey}
import org.scalatest.concurrent.{IntegrationPatience, PatienceConfiguration}
import org.scalatest.time.{Seconds, Span}
import org.scalatest.{Tag, TestData}
import scodec.bits.HexStringSyntax

import java.util.UUID
import scala.concurrent.duration.DurationInt

class OfferPaymentSpec extends FixtureSpec with IntegrationPatience {

  type FixtureParam = ThreeNodesFixture

  val PrivateChannels = "private_channels"
  val RouteBlindingDisabledBob = "route_blinding_disabled_bob"
  val RouteBlindingDisabledCarol = "route_blinding_disabled_carol"
  val NoChannels = "no_channels"

  val maxFinalExpiryDelta = CltvExpiryDelta(1000)

  override def createFixture(testData: TestData): FixtureParam = {
    // seeds have been chosen so that node ids start with 02aaaa for alice, 02bbbb for bob, etc.
    val aliceParams = nodeParamsFor("alice", ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3"))
      .modify(_.onionMessageConfig.timeout).setTo(5 minutes)
      .modify(_.features.activated).using(_ + (RouteBlinding -> Optional))
      .modify(_.channelConf.channelFlags.announceChannel).setTo(!testData.tags.contains(PrivateChannels))
    val bobParams = nodeParamsFor("bob", ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492"))
      .modify(_.onionMessageConfig.timeout).setTo(5 minutes)
      .modify(_.features.activated).using(_ + (RouteBlinding -> Optional))
      .modify(_.features.activated).usingIf(testData.tags.contains(RouteBlindingDisabledBob))(_ - RouteBlinding)
      .modify(_.channelConf.channelFlags.announceChannel).setTo(!testData.tags.contains(PrivateChannels))
    val carolParams = nodeParamsFor("carol", ByteVector32(hex"ebd5a5d3abfb3ef73731eb3418d918f247445183180522674666db98a66411cc"))
      .modify(_.onionMessageConfig.timeout).setTo(5 minutes)
      .modify(_.features.activated).using(_ + (RouteBlinding -> Optional))
      .modify(_.features.activated).using(_ + (KeySend -> Optional))
      .modify(_.features.activated).usingIf(testData.tags.contains(RouteBlindingDisabledCarol))(_ - RouteBlinding)
      .modify(_.channelConf.channelFlags.announceChannel).setTo(!testData.tags.contains(PrivateChannels))

    val f = ThreeNodesFixture(aliceParams, bobParams, carolParams, testData.name)
    import f._

    alice.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))
    bob.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))
    carol.watcher.setAutoPilot(watcherAutopilot(knownFundingTxs(alice, bob, carol)))

    connect(alice, bob)
    connect(bob, carol)

    if (!testData.tags.contains(NoChannels)) {
      createChannels(f, testData)
    }

    f
  }

  override def cleanupFixture(fixture: FixtureParam): Unit = {
    fixture.cleanup()
  }

  private def createChannels(f: FixtureParam, testData: TestData): Unit = {
    import f._

    val channelId_ab = openChannel(alice, bob, 500_000 sat).channelId
    val channelId_bc_1 = openChannel(bob, carol, 100_000 sat).channelId
    val channelId_bc_2 = openChannel(bob, carol, 100_000 sat).channelId

    waitForChannelCreatedAB(f, channelId_ab)
    waitForChannelCreatedBC(f, channelId_bc_1)
    waitForChannelCreatedBC(f, channelId_bc_2)

    eventually {
      assert(getRouterData(alice).channels.size == 3 || testData.tags.contains(PrivateChannels))
      assert(getRouterData(carol).graphWithBalances.graph.getEdgesBetween(alice.nodeId, bob.nodeId).nonEmpty || testData.tags.contains(PrivateChannels))
    }
  }

  private def waitForChannelCreatedAB(f: FixtureParam, channelId: ByteVector32): Unit = {
    import f._

    eventually {
      assert(getChannelState(alice, channelId) == NORMAL)
      assert(getChannelState(bob, channelId) == NORMAL)
    }
  }

  private def waitForChannelCreatedBC(f: FixtureParam, channelId: ByteVector32): Unit = {
    import f._

    eventually {
      assert(getChannelState(bob, channelId) == NORMAL)
      assert(getChannelState(carol, channelId) == NORMAL)
      // Carol must have received Bob's alias to create usable blinded routes to herself.
      assert(getRouterData(carol).privateChannels.values.forall(_.aliases.remoteAlias_opt.nonEmpty))
    }
  }

  private def waitForAllChannelUpdates(f: FixtureParam, channelsCount: Int): Unit = {
    import f._

    eventually(timeout = PatienceConfiguration.Timeout(Span(30, Seconds))) {
      // We wait for Alice and Carol to receive channel updates for the path Alice -> Bob -> Carol.
      Seq(getRouterData(alice), getRouterData(carol)).foreach(routerData => {
        assert(routerData.channels.size == channelsCount)
        routerData.channels.values.foreach {
          case c if c.nodeId1 == alice.nodeId && c.nodeId2 == bob.nodeId => assert(c.update_1_opt.nonEmpty)
          case c if c.nodeId1 == bob.nodeId && c.nodeId2 == alice.nodeId => assert(c.update_2_opt.nonEmpty)
          case c if c.nodeId1 == bob.nodeId && c.nodeId2 == carol.nodeId => assert(c.update_1_opt.nonEmpty)
          case c if c.nodeId1 == carol.nodeId && c.nodeId2 == bob.nodeId => assert(c.update_2_opt.nonEmpty)
          case _ => () // other channel updates are not necessary
        }
      })
    }
  }

  def offerHandler(amount: MilliSatoshi, routes: Seq[InvoiceRequestActor.Route]): Behavior[OfferManager.HandlerCommand] = {
    Behaviors.receiveMessage {
      case OfferManager.HandleInvoiceRequest(replyTo, _) =>
        replyTo ! InvoiceRequestActor.ApproveRequest(amount, routes)
        Behaviors.same
      case OfferManager.HandlePayment(replyTo, _, _) =>
        replyTo ! OfferManager.PaymentActor.AcceptPayment()
        Behaviors.same
    }
  }

  def createOffer(recipient: MinimalNodeFixture, description_opt: Option[String], amount_opt: Option[MilliSatoshi], issuer_opt: Option[String], blindedPathsFirstNodeId_opt: Option[PublicKey]): Offer = {
    val sender = TestProbe("sender")(recipient.system)
    val offerCreator = recipient.system.spawnAnonymous(OfferCreator(recipient.nodeParams, recipient.router, recipient.offerManager, recipient.defaultOfferHandler))
    offerCreator ! OfferCreator.Create(sender.ref.toTyped, description_opt, amount_opt, None, issuer_opt, blindedPathsFirstNodeId_opt)
    sender.expectMsgType[OfferCreator.CreatedOffer].offerData.offer
  }

  def payOffer(payer: MinimalNodeFixture, offer: Offer, amount: MilliSatoshi, maxAttempts: Int = 1): PaymentEvent = {
    val sender = TestProbe("sender")(payer.system)
    val offerPayment = payer.system.spawnAnonymous(OfferPayment(payer.nodeParams, payer.postman, payer.router, payer.register, payer.paymentInitiator))
    val sendPaymentConfig = OfferPayment.SendPaymentConfig(None, connectDirectly = false, maxAttempts, payer.routeParams, blocking = true)
    offerPayment ! OfferPayment.PayOffer(sender.ref, offer, amount, 1, sendPaymentConfig)
    sender.expectMsgType[PaymentEvent]
  }

  def sendOfferPayment(payer: MinimalNodeFixture, recipient: MinimalNodeFixture, amount: MilliSatoshi, routes: Seq[InvoiceRequestActor.Route], maxAttempts: Int = 1): (Offer, PaymentEvent) = {
    val offer = Offer(None, Some("test"), recipient.nodeId, Features.empty, recipient.nodeParams.chainHash)
    val handler = recipient.system.spawnAnonymous(offerHandler(amount, routes))
    recipient.offerManager ! OfferManager.RegisterOffer(offer, Some(recipient.nodeParams.privateKey), None, handler)
    (offer, payOffer(payer, offer, amount, maxAttempts))
  }

  def sendPrivateOfferPayment(payer: MinimalNodeFixture, recipient: MinimalNodeFixture, amount: MilliSatoshi, routes: Seq[InvoiceRequestActor.Route], maxAttempts: Int = 1): (Offer, PaymentEvent) = {
    val recipientKey = randomKey()
    val pathId = randomBytes32()
    val offerPaths = routes.map(route => {
      val intermediateNodes = route.hops.map(hop => IntermediateNode(hop.nodeId))
      buildRoute(randomKey(), intermediateNodes, Recipient(recipient.nodeId, Some(pathId))).route
    })
    val offer = Offer(None, Some("test"), recipientKey.publicKey, Features.empty, recipient.nodeParams.chainHash, additionalTlvs = Set(OfferPaths(offerPaths)))
    val handler = recipient.system.spawnAnonymous(offerHandler(amount, routes))
    recipient.offerManager ! OfferManager.RegisterOffer(offer, Some(recipientKey), Some(pathId), handler)
    (offer, payOffer(payer, offer, amount, maxAttempts))
  }

  def sendOfferPaymentWithInvalidAmount(f: FixtureParam, payer: MinimalNodeFixture, recipient: MinimalNodeFixture, payerAmount: MilliSatoshi, recipientAmount: MilliSatoshi, routes: Seq[InvoiceRequestActor.Route]): PaymentFailed = {
    import f._

    val sender = TestProbe("sender")
    val paymentInterceptor = TestProbe("payment-interceptor")
    val offer = Offer(None, Some("test"), recipient.nodeId, Features.empty, recipient.nodeParams.chainHash)
    val handler = recipient.system.spawnAnonymous(offerHandler(recipientAmount, routes))
    recipient.offerManager ! OfferManager.RegisterOffer(offer, Some(recipient.nodeParams.privateKey), None, handler)
    val offerPayment = payer.system.spawnAnonymous(OfferPayment(payer.nodeParams, payer.postman, payer.router, payer.register, paymentInterceptor.ref))
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
    assert(offer.nodeId.forall(_ == payment.recipientNodeId))
    assert(payment.parts.map(_.amount).sum == amount)
    payment
  }

  test("send blinded payment a->b->c") { f =>
    import f._

    val amount = 25_000_000 msat

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(amount, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))
    val (offer, result) = sendOfferPayment(alice, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
    assert(payment.parts.head.feesPaid > 0.msat)
  }

  test("send blinded payment a->b->c, hidden fees") { f =>
    import f._

    val amount = 25_000_000 msat

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(amount, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta, feeOverride_opt = Some(RelayFees.zero)))
    val (offer, result) = sendOfferPayment(alice, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
    assert(payment.parts.head.feesPaid == 0.msat)
  }

  test("send blinded multi-part payment a->b->c") { f =>
    import f._

    val amount = 125_000_000 msat

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(10_000_000 msat, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(
      InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta),
      InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta),
    )
    val (offer, result) = sendOfferPayment(alice, carol, amount, routes, maxAttempts = 4)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 2)
    assert(payment.parts.forall(_.feesPaid > 0.msat))
  }

  test("send blinded multi-part payment a->b->c, hidden fees") { f =>
    import f._

    val amount = 125_000_000 msat

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(10_000_000 msat, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(
      InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta, feeOverride_opt = Some(RelayFees.zero)),
      InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta, feeOverride_opt = Some(RelayFees.zero)),
    )
    val (offer, result) = sendOfferPayment(alice, carol, amount, routes, maxAttempts = 4)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 2)
    assert(payment.parts.forall(_.feesPaid == 0.msat))
  }

  test("send blinded multi-part payment a->b->c (single channel a->b)", Tag(PrivateChannels)) { f =>
    import f._

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(10_000_000 msat, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    // Carol advertises a single blinded path from Bob to herself.
    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))

    // We make a first set of payments to ensure channels have less than 50 000 sat on Bob's side.
    Seq(50_000_000 msat, 50_000_000 msat).foreach(amount => {
      val (offer, result) = sendPrivateOfferPayment(alice, carol, amount, routes)
      verifyPaymentSuccess(offer, amount, result)
    })

    // None of the channels between Bob and Carol have enough balance for the payment: Alice needs to split it.
    val amount = 50_000_000 msat
    val (offer, result) = sendPrivateOfferPayment(alice, carol, amount, routes, maxAttempts = 4)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length > 1)
  }

  test("send blinded multi-part payment a->b->c (single channel b->c)", Tag(PrivateChannels), Tag(NoChannels)) { f =>
    import f._

    // We create two channels between Alice and Bob.
    val channelId_ab_1 = openChannel(alice, bob, 100_000 sat).channelId
    waitForChannelCreatedAB(f, channelId_ab_1)
    val channelId_ab_2 = openChannel(alice, bob, 100_000 sat).channelId
    waitForChannelCreatedAB(f, channelId_ab_2)

    // We create a single channel between Bob and Carol.
    val channelId_bc_1 = openChannel(bob, carol, 250_000 sat).channelId
    waitForChannelCreatedBC(f, channelId_bc_1)

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(10_000_000 msat, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))
    val amount1 = 150_000_000 msat
    val (offer, result) = sendPrivateOfferPayment(alice, carol, amount1, routes, maxAttempts = 4)
    val payment = verifyPaymentSuccess(offer, amount1, result)
    assert(payment.parts.length > 1)
  }

  test("send blinded payment a->b->c with dummy hops") { f =>
    import f._

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(10_000_000 msat, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val amount = 125_000_000 msat
    val routes = Seq(
      InvoiceRequestActor.Route(route.hops :+ ChannelHop.dummy(carol.nodeId, 150 msat, 0, CltvExpiryDelta(50)), maxFinalExpiryDelta),
      InvoiceRequestActor.Route(route.hops ++ Seq(ChannelHop.dummy(carol.nodeId, 50 msat, 0, CltvExpiryDelta(20)), ChannelHop.dummy(carol.nodeId, 100 msat, 0, CltvExpiryDelta(30))), maxFinalExpiryDelta),
    )
    val (offer, result) = sendOfferPayment(alice, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 2)
    assert(payment.parts.forall(_.feesPaid > 0.msat))
  }

  test("send blinded payment a->b->c with dummy hops, hidden fees") { f =>
    import f._

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(10_000_000 msat, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val amount = 125_000_000 msat
    val routes = Seq(
      InvoiceRequestActor.Route(route.hops :+ ChannelHop.dummy(carol.nodeId, 150 msat, 0, CltvExpiryDelta(50)), maxFinalExpiryDelta, feeOverride_opt = Some(RelayFees.zero)),
      InvoiceRequestActor.Route(route.hops ++ Seq(ChannelHop.dummy(carol.nodeId, 50 msat, 0, CltvExpiryDelta(20)), ChannelHop.dummy(carol.nodeId, 100 msat, 0, CltvExpiryDelta(30))), maxFinalExpiryDelta, feeOverride_opt = Some(RelayFees.zero)),
    )
    val (offer, result) = sendOfferPayment(alice, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 2)
    assert(payment.parts.forall(_.feesPaid == 0.msat))
  }

  test("send blinded payment a->b->c through private channels", Tag(PrivateChannels)) { f =>
    import f._

    val amount = 50_000_000 msat

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(amount, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))
    val (offer, result) = sendPrivateOfferPayment(alice, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.forall(_.feesPaid > 0.msat))
  }

  test("send blinded payment a->b->c through private channels, hidden fees", Tag(PrivateChannels)) { f =>
    import f._

    val amount = 50_000_000 msat

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(amount, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta, feeOverride_opt = Some(RelayFees.zero)))
    val (offer, result) = sendPrivateOfferPayment(alice, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.forall(_.feesPaid == 0.msat))
  }

  test("send blinded payment a->b") { f =>
    import f._

    val amount = 75_000_000 msat
    val routes = Seq(InvoiceRequestActor.Route(Nil, maxFinalExpiryDelta))
    val (offer, result) = sendOfferPayment(alice, bob, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
  }

  test("send blinded payment a->b with dummy hops") { f =>
    import f._

    val amount = 250_000_000 msat
    val routes = Seq(InvoiceRequestActor.Route(Seq(ChannelHop.dummy(bob.nodeId, 10 msat, 25, CltvExpiryDelta(24)), ChannelHop.dummy(bob.nodeId, 5 msat, 10, CltvExpiryDelta(36))), maxFinalExpiryDelta))
    val (offer, result) = sendOfferPayment(alice, bob, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
    assert(payment.parts.forall(_.feesPaid > 0.msat))
  }

  test("send blinded payment a->b with dummy hops, hidden fees") { f =>
    import f._

    val amount = 250_000_000 msat
    val routes = Seq(InvoiceRequestActor.Route(Seq(ChannelHop.dummy(bob.nodeId, 10 msat, 25, CltvExpiryDelta(24)), ChannelHop.dummy(bob.nodeId, 5 msat, 10, CltvExpiryDelta(36))), maxFinalExpiryDelta, feeOverride_opt = Some(RelayFees.zero)))
    val (offer, result) = sendOfferPayment(alice, bob, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
    assert(payment.parts.forall(_.feesPaid == 0.msat))
  }

  test("send fully blinded payment b->c") { f =>
    import f._

    val amount = 50_000_000 msat

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(amount, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))
    val (offer, result) = sendOfferPayment(bob, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
  }

  test("send fully blinded multi-part payment b->c", Tag(PrivateChannels), Tag(NoChannels)) { f =>
    import f._

    // We create a first channel between Bob and Carol.
    val channelId_bc_1 = openChannel(bob, carol, 200_000 sat).channelId
    waitForChannelCreatedBC(f, channelId_bc_1)

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(50_000_000 msat, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    // Carol creates a blinded path using that channel.
    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))

    // We make a payment to ensure that the channel contains less than 150 000 sat on Bob's side.
    assert(sendPayment(bob, carol, 50_000_000 msat).isRight)

    // We open another channel between Bob and Carol.
    val channelId_bc_2 = openChannel(bob, carol, 100_000 sat).channelId
    waitForChannelCreatedBC(f, channelId_bc_2)

    // None of the channels have enough balance for the payment: it must be split.
    val amount = 150_000_000 msat
    val (offer, result) = sendOfferPayment(bob, carol, amount, routes, maxAttempts = 4)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length > 1)
  }

  test("send fully blinded payment b->c with dummy hops") { f =>
    import f._

    val amount = 50_000_000 msat

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(amount, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(InvoiceRequestActor.Route(route.hops :+ ChannelHop.dummy(carol.nodeId, 25 msat, 250, CltvExpiryDelta(75)), maxFinalExpiryDelta))
    val (offer, result) = sendOfferPayment(bob, carol, amount, routes)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
  }

  test("send fully blinded multi-part payment a->b->c", Tag(NoChannels)) { f =>
    import f._

    // We create a first channel between Bob and Carol.
    val channelId_bc_1 = openChannel(bob, carol, 300_000 sat).channelId
    waitForChannelCreatedBC(f, channelId_bc_1)

    // We create a first channel between Alice and Bob.
    val channelId_ab_1 = openChannel(alice, bob, 300_000 sat).channelId
    waitForChannelCreatedAB(f, channelId_ab_1)

    // We wait for Carol to receive information about the channel between Alice and Bob.
    waitForAllChannelUpdates(f, channelsCount = 2)

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(10_000_000 msat, Seq(alice.nodeId, bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    // Carol receives a first payment through those channels.
    {
      val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))
      val amount1 = 100_000_000 msat
      val (offer, result) = sendOfferPayment(alice, carol, amount1, routes)
      val payment = verifyPaymentSuccess(offer, amount1, result)
      assert(payment.parts.length == 1)
    }

    // We create another channel route from Alice to Carol.
    val channelId_bc_2 = openChannel(bob, carol, 150_000 sat).channelId
    waitForChannelCreatedBC(f, channelId_bc_2)
    val channelId_ab_2 = openChannel(alice, bob, 150_000 sat).channelId
    waitForChannelCreatedAB(f, channelId_ab_2)
    waitForAllChannelUpdates(f, channelsCount = 4)

    // Carol receives a second payment that requires using MPP.
    {
      val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))
      val amount2 = 200_000_000 msat
      val (offer, result) = sendOfferPayment(alice, carol, amount2, routes, maxAttempts = 4)
      val payment = verifyPaymentSuccess(offer, amount2, result)
      assert(payment.parts.length > 1)
    }
  }

  test("send fully blinded multi-part payment a->b->c (single channel b->c)", Tag(NoChannels)) { f =>
    import f._

    // We create a channel between Bob and Carol.
    val channelId_bc_1 = openChannel(bob, carol, 500_000 sat).channelId
    waitForChannelCreatedBC(f, channelId_bc_1)

    // We create two channels between Alice and Bob.
    val channelId_ab_1 = openChannel(alice, bob, 300_000 sat).channelId
    waitForChannelCreatedAB(f, channelId_ab_1)
    val channelId_ab_2 = openChannel(alice, bob, 200_000 sat).channelId
    waitForChannelCreatedAB(f, channelId_ab_2)

    // We wait for Carol to receive information about the channel between Alice and Bob.
    waitForAllChannelUpdates(f, channelsCount = 3)

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(10_000_000 msat, Seq(alice.nodeId, bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    // Carol receives a payment that requires using MPP.
    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))
    val amount = 300_000_000 msat
    val (offer, result) = sendOfferPayment(alice, carol, amount, routes, maxAttempts = 4)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length > 1)
  }

  test("send fully blinded multi-part payment a->b->c (single channel a->b)", Tag(NoChannels)) { f =>
    import f._

    // We create a channel between Alice and Bob.
    val channelId_ab_1 = openChannel(alice, bob, 300_000 sat).channelId
    waitForChannelCreatedAB(f, channelId_ab_1)

    // We create two channels between Bob and Carol.
    val channelId_bc_1 = openChannel(bob, carol, 100_000 sat).channelId
    waitForChannelCreatedBC(f, channelId_bc_1)
    val channelId_bc_2 = openChannel(bob, carol, 200_000 sat).channelId
    waitForChannelCreatedBC(f, channelId_bc_2)

    // We wait for Carol to receive information about the channel between Alice and Bob.
    waitForAllChannelUpdates(f, channelsCount = 3)

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(10_000_000 msat, Seq(alice.nodeId, bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    // Carol receives a payment that requires using MPP.
    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))
    val amount = 200_000_000 msat
    val (offer, result) = sendOfferPayment(alice, carol, amount, routes, maxAttempts = 4)
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length > 1)
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
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(75_000_000 msat, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    // Bob sends payments to Carol to reduce the liquidity on both of his channels.
    Seq(1, 2).foreach(_ => {
      sender.send(bob.paymentInitiator, SendSpontaneousPayment(50_000_000 msat, carol.nodeId, randomBytes32(), 1, routeParams = bob.routeParams))
      sender.expectMsgType[UUID]
      sender.expectMsgType[PaymentSent]
    })
    // Bob now doesn't have enough funds to relay the payment.
    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))
    val (_, result) = sendOfferPayment(alice, carol, 75_000_000 msat, routes)
    verifyBlindedFailure(result, bob.nodeId)
  }

  test("send blinded payment a->b->c using expired route") { f =>
    import f._

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(25_000_000 msat, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(InvoiceRequestActor.Route(route.hops, CltvExpiryDelta(-500)))
    val (_, result) = sendOfferPayment(alice, carol, 25_000_000 msat, routes)
    verifyBlindedFailure(result, bob.nodeId)
  }

  test("send blinded payment a->b->c failing at c") { f =>
    import f._

    val payerAmount = 20_000_000 msat
    val recipientAmount = 25_000_000 msat

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(recipientAmount, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))
    // The amount is below what Carol expects.
    val payment = sendOfferPaymentWithInvalidAmount(f, alice, carol, payerAmount, recipientAmount, routes)
    verifyBlindedFailure(payment, bob.nodeId)
  }

  test("send blinded payment a->b failing at b") { f =>
    import f._

    val payerAmount = 25_000_000 msat
    val recipientAmount = 50_000_000 msat
    val routes = Seq(InvoiceRequestActor.Route(Nil, maxFinalExpiryDelta))
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
    val routes = Seq(InvoiceRequestActor.Route(Seq(ChannelHop.dummy(bob.nodeId, 1 msat, 100, CltvExpiryDelta(48))), maxFinalExpiryDelta))
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

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(recipientAmount, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val routes = Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))
    // The amount is below what Carol expects.
    val payment = sendOfferPaymentWithInvalidAmount(f, bob, carol, payerAmount, recipientAmount, routes)
    assert(payment.failures.head.isInstanceOf[PaymentFailure])
  }

  test("send payment a->b->c compact offer") { f =>
    import f._

    val probe = TestProbe()
    val amount = 25_000_000 msat
    val recipientKey = randomKey()
    val pathId = randomBytes32()

    val blindedRoute = buildRoute(randomKey(), Seq(IntermediateNode(bob.nodeId), IntermediateNode(carol.nodeId)), Recipient(carol.nodeId, Some(pathId))).route
    val offer = Offer(None, Some("test"), recipientKey.publicKey, Features.empty, carol.nodeParams.chainHash, additionalTlvs = Set(OfferPaths(Seq(blindedRoute))))
    val scid_bc = getPeerChannels(bob, carol.nodeId).head.data.asInstanceOf[DATA_NORMAL].commitments.latest.shortChannelId_opt.get
    val compactBlindedRoute = buildRoute(randomKey(), Seq(IntermediateNode(bob.nodeId, EncodedNodeId(bob.nodeId), Some(scid_bc)), IntermediateNode(carol.nodeId, EncodedNodeId(carol.nodeId), Some(ShortChannelId.toSelf))), Recipient(carol.nodeId, Some(pathId))).route
    val compactOffer = Offer(None, Some("test"), recipientKey.publicKey, Features.empty, carol.nodeParams.chainHash, additionalTlvs = Set(OfferPaths(Seq(compactBlindedRoute))))
    assert(compactOffer.toString.length < offer.toString.length)

    val sender = TestProbe()
    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(amount, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val receivingRoute = InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta)
    val handler = carol.system.spawnAnonymous(offerHandler(amount, Seq(receivingRoute)))
    carol.offerManager ! OfferManager.RegisterOffer(compactOffer, Some(recipientKey), Some(pathId), handler)
    val offerPayment = alice.system.spawnAnonymous(OfferPayment(alice.nodeParams, alice.postman, alice.router, alice.register, alice.paymentInitiator))
    val sendPaymentConfig = OfferPayment.SendPaymentConfig(None, connectDirectly = false, maxAttempts = 1, alice.routeParams, blocking = true)
    offerPayment ! OfferPayment.PayOffer(probe.ref, compactOffer, amount, 1, sendPaymentConfig)
    val payment = verifyPaymentSuccess(compactOffer, amount, probe.expectMsgType[PaymentEvent])
    assert(payment.parts.length == 1)
  }

  test("send payment a->b->c offer with implicit node id") { f =>
    import f._

    val sender = TestProbe("sender")
    val pathId = randomBytes32()
    val amount = 25_000_000 msat

    carol.router ! Router.FinalizeRoute(sender.ref.toTyped, Router.PredefinedNodeRoute(amount, Seq(bob.nodeId, carol.nodeId)))
    val route = sender.expectMsgType[Router.RouteResponse].routes.head

    val offerPaths = Seq(OnionMessages.buildRoute(randomKey(), Seq(IntermediateNode(bob.nodeId)), Recipient(carol.nodeId, Some(pathId))).route)
    val offer = Offer.withPaths(None, Some("implicit node id"), offerPaths, Features.empty, carol.nodeParams.chainHash)
    val handler = carol.system.spawnAnonymous(offerHandler(amount, Seq(InvoiceRequestActor.Route(route.hops, maxFinalExpiryDelta))))
    carol.offerManager ! OfferManager.RegisterOffer(offer, None, Some(pathId), handler)
    val offerPayment = alice.system.spawnAnonymous(OfferPayment(alice.nodeParams, alice.postman, alice.router, alice.register, alice.paymentInitiator))
    val sendPaymentConfig = OfferPayment.SendPaymentConfig(None, connectDirectly = false, maxAttempts = 1, alice.routeParams, blocking = true)
    offerPayment ! OfferPayment.PayOffer(sender.ref, offer, amount, 1, sendPaymentConfig)
    val result = sender.expectMsgType[PaymentEvent]
    val payment = verifyPaymentSuccess(offer, amount, result)
    assert(payment.parts.length == 1)
  }

  test("create offer using public node id") { f =>
    import f._

    val amount = 20_000_000 msat
    val offer = createOffer(carol, description_opt = Some("test offer"), amount_opt = Some(amount), issuer_opt = None, blindedPathsFirstNodeId_opt = None)
    assert(offer.nodeId.contains(carol.nodeId))
    assert(offer.description.contains("test offer"))
    assert(offer.amount.contains(amount))

    val payment = payOffer(alice, offer, amount)
    assert(payment.isInstanceOf[PaymentSent])
    // Alice must pay fees for the non-blinded Bob->Carol channel.
    assert(payment.asInstanceOf[PaymentSent].feesPaid > 0.msat)
  }

  test("create offer without public node id (dummy hops only)") { f =>
    import f._

    val amount = 20_000_000 msat
    val offer = createOffer(carol, description_opt = Some("test offer"), amount_opt = Some(amount), issuer_opt = None, blindedPathsFirstNodeId_opt = Some(carol.nodeId))
    assert(offer.nodeId.isEmpty)
    assert(offer.contactInfos.size == 1)
    assert(offer.contactInfos.head.asInstanceOf[BlindedPath].route.firstNodeId == EncodedNodeId.WithPublicKey.Plain(carol.nodeId))
    assert(offer.contactInfos.head.asInstanceOf[BlindedPath].route.length == carol.nodeParams.offersConfig.messagePathMinLength)
    assert(offer.description.contains("test offer"))
    assert(offer.amount.contains(amount))

    val payment = payOffer(alice, offer, amount)
    assert(payment.isInstanceOf[PaymentSent])
    // Alice must pay fees for the non-blinded Bob->Carol channel.
    assert(payment.asInstanceOf[PaymentSent].feesPaid > 0.msat)
  }

  test("create offer without node id (real and dummy blinded hops)") { f =>
    import f._

    val amount = 20_000_000 msat
    val offer = createOffer(carol, description_opt = Some("test offer"), amount_opt = Some(amount), issuer_opt = None, blindedPathsFirstNodeId_opt = Some(bob.nodeId))
    assert(offer.nodeId.isEmpty)
    assert(offer.contactInfos.size == 1)
    assert(offer.contactInfos.head.asInstanceOf[BlindedPath].route.firstNodeId == EncodedNodeId.WithPublicKey.Plain(bob.nodeId))
    assert(offer.contactInfos.head.asInstanceOf[BlindedPath].route.length == carol.nodeParams.offersConfig.messagePathMinLength)
    assert(offer.description.contains("test offer"))
    assert(offer.amount.contains(amount))

    val payment = payOffer(alice, offer, amount)
    assert(payment.isInstanceOf[PaymentSent])
    // For offers managed by eclair, the fees of the blinded path are paid by the recipient, not by the payer.
    assert(payment.asInstanceOf[PaymentSent].feesPaid == 0.msat)
    assert(payment.asInstanceOf[PaymentSent].parts.nonEmpty)
    payment.asInstanceOf[PaymentSent].parts.foreach(p => {
      val blinded = p.route.flatMap(_.lastOption).get
      assert(blinded.isInstanceOf[Router.BlindedHop])
      // Carol added dummy hops to pad blinded paths.
      assert(blinded.asInstanceOf[Router.BlindedHop].resolved.route.blindedNodeIds.size == carol.nodeParams.offersConfig.paymentPathLength + 1)
    })
  }

  test("create offer without node id (payer is first node of blinded path)") { f =>
    import f._

    val amount = 20_000_000 msat
    val offer = createOffer(carol, description_opt = Some("test offer"), amount_opt = Some(amount), issuer_opt = None, blindedPathsFirstNodeId_opt = Some(alice.nodeId))
    assert(offer.nodeId.isEmpty)
    assert(offer.contactInfos.head.asInstanceOf[BlindedPath].route.firstNodeId == EncodedNodeId.WithPublicKey.Plain(alice.nodeId))
    assert(offer.contactInfos.head.asInstanceOf[BlindedPath].route.length == carol.nodeParams.offersConfig.messagePathMinLength)
    assert(offer.description.contains("test offer"))
    assert(offer.amount.contains(amount))

    val payment = payOffer(alice, offer, amount)
    assert(payment.isInstanceOf[PaymentSent])
    // For offers managed by eclair, the fees of the blinded path are paid by the recipient, not by the payer.
    // If the payer is part of the blinded path, it means that the recipient is refunding the fees of the payer's
    // first hop: but this hop is not part of the payer fees, since the channel belongs to the payer, so it looks
    // like the payment used negative fees. In reality, this simply means that we obtained the preimage while paying
    // less than the invoice amount, which is fine.
    assert(payment.asInstanceOf[PaymentSent].feesPaid < 0.msat)
    assert(payment.asInstanceOf[PaymentSent].parts.nonEmpty)
    payment.asInstanceOf[PaymentSent].parts.foreach(p => {
      val blinded = p.route.flatMap(_.lastOption).get
      assert(blinded.isInstanceOf[Router.BlindedHop])
      // Alice resolves the first (blinded) node as being Bob.
      assert(blinded.asInstanceOf[Router.BlindedHop].resolved.route.firstNodeId == bob.nodeId)
      // Carol added dummy hops to pad blinded paths.
      assert(blinded.asInstanceOf[Router.BlindedHop].resolved.route.blindedNodeIds.size == carol.nodeParams.offersConfig.paymentPathLength)
    })
  }
}
