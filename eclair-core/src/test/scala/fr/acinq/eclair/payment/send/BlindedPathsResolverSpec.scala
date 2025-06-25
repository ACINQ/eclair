/*
 * Copyright 2024 ACINQ SAS
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

package fr.acinq.eclair.payment.send

import akka.actor.ActorSystem
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.payment.Invoice.ExtraEdge
import fr.acinq.eclair.payment.PaymentBlindedRoute
import fr.acinq.eclair.payment.send.BlindedPathsResolver.{FullBlindedRoute, PartialBlindedRoute, Resolve, ResolvedPath}
import fr.acinq.eclair.router.Router.{ChannelHop, HopRelayParams}
import fr.acinq.eclair.router.{BlindedRouteCreation, Router}
import fr.acinq.eclair.wire.protocol.OfferTypes.PaymentInfo
import fr.acinq.eclair.{Alias, BlockHeight, CltvExpiry, CltvExpiryDelta, EncodedNodeId, Features, MilliSatoshiLong, NodeParams, RealShortChannelId, TestConstants, randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.duration.DurationInt

class BlindedPathsResolverSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, router: TestProbe, register: TestProbe, resolver: ActorRef[BlindedPathsResolver.Command])

  implicit val classicSystem: ActorSystem = system.classicSystem

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = TestConstants.Alice.nodeParams
    val router = TestProbe("router")
    val register = TestProbe("register")
    val resolver = testKit.spawn(BlindedPathsResolver(nodeParams, randomBytes32(), router.ref, register.ref))
    try {
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, router, register, resolver)))
    } finally {
      testKit.stop(resolver)
    }
  }

  test("resolve plain node_id") { f =>
    import f._

    val probe = TestProbe()
    val Seq(a, b, c) = Seq(randomKey(), randomKey(), randomKey()).map(_.publicKey)
    val paymentInfo = PaymentInfo(100 msat, 250, CltvExpiryDelta(36), 1 msat, 50_000_000 msat, ByteVector.empty)
    val blindedPaths = Seq(
      RouteBlinding.create(randomKey(), Seq(a), Seq(hex"deadbeef")),
      RouteBlinding.create(randomKey(), Seq(b, randomKey().publicKey), Seq(hex"deadbeef", hex"deadbeef")),
      RouteBlinding.create(randomKey(), Seq(c, randomKey().publicKey, randomKey().publicKey), Seq(hex"deadbeef", hex"deadbeef", hex"deadbeef")),
    ).map(r => PaymentBlindedRoute(r.route, paymentInfo))
    resolver ! Resolve(probe.ref, blindedPaths)
    val resolved = probe.expectMsgType[Seq[ResolvedPath]]
    assert(resolved.size == 3)
    assert(resolved.map(_.route.firstNodeId).toSet == Set(a, b, c))
    resolved.foreach(r => {
      assert(r.route.isInstanceOf[FullBlindedRoute])
      assert(r.paymentInfo == paymentInfo)
    })
    // We directly have access to the introduction node_id, no need to call the router or register.
    router.expectNoMessage(100 millis)
    register.expectNoMessage(100 millis)
  }

  test("resolve scid_dir") { f =>
    import f._

    val probe = TestProbe()
    val introductionNodeId = randomKey().publicKey
    val scidDir = EncodedNodeId.ShortChannelIdDir(isNode1 = false, RealShortChannelId(BlockHeight(750_000), 3, 7))
    val route = RouteBlinding.create(randomKey(), Seq(introductionNodeId), Seq(hex"deadbeef")).route.copy(firstNodeId = scidDir)
    val paymentInfo = PaymentInfo(100 msat, 250, CltvExpiryDelta(36), 1 msat, 50_000_000 msat, ByteVector.empty)
    resolver ! Resolve(probe.ref, Seq(PaymentBlindedRoute(route, paymentInfo)))
    // We must resolve the scid_dir to a node_id.
    val routerReq = router.expectMsgType[Router.GetNodeId]
    assert(routerReq.shortChannelId == scidDir.scid)
    assert(!routerReq.isNode1)
    routerReq.replyTo ! Some(introductionNodeId)
    val resolved = probe.expectMsgType[Seq[ResolvedPath]]
    assert(resolved.size == 1)
    assert(resolved.head.route.isInstanceOf[FullBlindedRoute])
    val fullRoute = resolved.head.route.asInstanceOf[FullBlindedRoute]
    assert(fullRoute.firstNodeId == introductionNodeId)
    assert(fullRoute.firstpathKey == route.firstPathKey)
    assert(fullRoute.blindedHops == route.blindedHops)
    assert(resolved.head.paymentInfo == paymentInfo)
  }

  test("resolve route starting at our node") { f =>
    import f._

    val probe = TestProbe()
    val nextNodeId = randomKey().publicKey
    val edges = Seq(
      ExtraEdge(nodeParams.nodeId, nextNodeId, RealShortChannelId(BlockHeight(750_000), 3, 7), 600_000 msat, 100, CltvExpiryDelta(144), 1 msat, None),
      ExtraEdge(nextNodeId, randomKey().publicKey, RealShortChannelId(BlockHeight(700_000), 1, 0), 750_000 msat, 150, CltvExpiryDelta(48), 1 msat, None),
    )
    val hops = edges.map(e => ChannelHop(e.shortChannelId, e.sourceNodeId, e.targetNodeId, HopRelayParams.FromHint(e)))
    val route = BlindedRouteCreation.createBlindedRouteFromHops(hops, hops.last.nextNodeId, hex"deadbeef", 1 msat, CltvExpiry(800_000)).route
    val paymentInfo = BlindedRouteCreation.aggregatePaymentInfo(100_000_000 msat, hops, CltvExpiryDelta(12))
    Seq(true, false).foreach { useScidDir =>
      val toResolve = if (useScidDir) {
        val scidDir = EncodedNodeId.ShortChannelIdDir(isNode1 = true, edges.head.shortChannelId.asInstanceOf[RealShortChannelId])
        route.copy(firstNodeId = scidDir)
      } else {
        route
      }
      val resolver = testKit.spawn(BlindedPathsResolver(nodeParams, randomBytes32(), router.ref, register.ref))
      resolver ! Resolve(probe.ref, Seq(PaymentBlindedRoute(toResolve, paymentInfo)))
      if (useScidDir) {
        // We first resolve the scid_dir to a node_id.
        val routerReq = router.expectMsgType[Router.GetNodeId]
        assert(routerReq.shortChannelId == edges.head.shortChannelId)
        assert(routerReq.isNode1)
        routerReq.replyTo ! Some(nodeParams.nodeId)
      }
      // We are the introduction node: we decrypt the payload and resolve the next node's ID.
      val registerReq = register.expectMsgType[Register.GetNextNodeId]
      assert(registerReq.shortChannelId == edges.head.shortChannelId)
      registerReq.replyTo ! Some(nextNodeId)
      // We return a partially unwrapped blinded path.
      val resolved = probe.expectMsgType[Seq[ResolvedPath]]
      assert(resolved.size == 1)
      assert(resolved.head.route.isInstanceOf[PartialBlindedRoute])
      val partialRoute = resolved.head.route.asInstanceOf[PartialBlindedRoute]
      assert(partialRoute.firstNodeId == nextNodeId)
      assert(partialRoute.blindedHops == route.subsequentNodes)
      assert(partialRoute.nextPathKey != route.firstPathKey)
      // The payment info for the partial route should be greater than the actual payment info.
      assert(750_000.msat <= resolved.head.paymentInfo.feeBase && resolved.head.paymentInfo.feeBase <= 1_000_000.msat)
      assert(150 <= resolved.head.paymentInfo.feeProportionalMillionths && resolved.head.paymentInfo.feeProportionalMillionths <= 200)
      assert(resolved.head.paymentInfo.cltvExpiryDelta == CltvExpiryDelta(60)) // this includes the final expiry delta
    }
  }

  test("resolve route starting at our node (wallet node)") { f =>
    import f._

    val probe = TestProbe()
    val walletNodeId = randomKey().publicKey
    val edge = ExtraEdge(nodeParams.nodeId, walletNodeId, Alias(561), 5_000_000 msat, 200, CltvExpiryDelta(144), 1 msat, None)
    val hop = ChannelHop(edge.shortChannelId, nodeParams.nodeId, walletNodeId, HopRelayParams.FromHint(edge))
    val route = BlindedRouteCreation.createBlindedRouteToWallet(hop, hex"deadbeef", 1 msat, CltvExpiry(800_000)).route
    val paymentInfo = BlindedRouteCreation.aggregatePaymentInfo(100_000_000 msat, Seq(hop), CltvExpiryDelta(12))
    val resolver = testKit.spawn(BlindedPathsResolver(nodeParams, randomBytes32(), router.ref, register.ref))
    resolver ! Resolve(probe.ref, Seq(PaymentBlindedRoute(route, paymentInfo)))
    // We are the introduction node: we decrypt the payload and discover that the next node is a wallet node.
    val resolved = probe.expectMsgType[Seq[ResolvedPath]]
    assert(resolved.size == 1)
    assert(resolved.head.route.isInstanceOf[PartialBlindedRoute])
    val partialRoute = resolved.head.route.asInstanceOf[PartialBlindedRoute]
    assert(partialRoute.firstNodeId == walletNodeId)
    assert(partialRoute.nextNodeId == EncodedNodeId.WithPublicKey.Wallet(walletNodeId))
    assert(partialRoute.blindedHops == route.subsequentNodes)
    assert(partialRoute.nextPathKey != route.firstPathKey)
    // We don't need to resolve the nodeId.
    register.expectNoMessage(100 millis)
    router.expectNoMessage(100 millis)
  }

  test("ignore blinded paths that cannot be resolved") { f =>
    import f._

    val probe = TestProbe()
    val scid = RealShortChannelId(BlockHeight(750_000), 3, 7)
    val edge = ExtraEdge(nodeParams.nodeId, randomKey().publicKey, scid, 600_000 msat, 100, CltvExpiryDelta(144), 1 msat, None)
    val hop = ChannelHop(edge.shortChannelId, edge.sourceNodeId, edge.targetNodeId, HopRelayParams.FromHint(edge))
    val route = BlindedRouteCreation.createBlindedRouteFromHops(Seq(hop), edge.targetNodeId, hex"deadbeef", 1 msat, CltvExpiry(800_000)).route
    val paymentInfo = BlindedRouteCreation.aggregatePaymentInfo(50_000_000 msat, Seq(hop), CltvExpiryDelta(12))
    val toResolve = Seq(
      PaymentBlindedRoute(route.copy(firstNodeId = EncodedNodeId.ShortChannelIdDir(isNode1 = true, scid)), paymentInfo),
      PaymentBlindedRoute(route, paymentInfo),
      PaymentBlindedRoute(route, paymentInfo),
    )
    resolver ! Resolve(probe.ref, toResolve)
    // The scid_dir of the first route cannot be found in the graph.
    router.expectMsgType[Router.GetNodeId].replyTo ! Option.empty[PublicKey]
    // The next node of the second route cannot be found based on the outgoing channel_id.
    register.expectMsgType[Register.GetNextNodeId].replyTo ! Option.empty[PublicKey]
    // The next node of the third route is actually ourselves, which shouldn't happen.
    register.expectMsgType[Register.GetNextNodeId].replyTo ! Some(nodeParams.nodeId)
    // All routes failed to resolve.
    probe.expectMsg(Seq.empty[ResolvedPath])
  }

  test("ignore invalid blinded paths starting at our node") { f =>
    import f._

    val probe = TestProbe()
    val scid = RealShortChannelId(BlockHeight(750_000), 3, 7)
    val nextNodeId = randomKey().publicKey
    val edgeLowFees = ExtraEdge(nodeParams.nodeId, nextNodeId, scid, 100 msat, 5, CltvExpiryDelta(144), 1 msat, None)
    val edgeLowExpiryDelta = ExtraEdge(nodeParams.nodeId, nextNodeId, scid, 600_000 msat, 100, CltvExpiryDelta(36), 1 msat, None)
    val toResolve = Seq(
      // We don't allow paying blinded routes to ourselves.
      BlindedRouteCreation.createBlindedRouteFromHops(Nil, nodeParams.nodeId, hex"deadbeef", 1 msat, CltvExpiry(800_000)).route,
      // We reject blinded routes with low fees.
      BlindedRouteCreation.createBlindedRouteFromHops(Seq(ChannelHop(scid, nodeParams.nodeId, edgeLowFees.targetNodeId, HopRelayParams.FromHint(edgeLowFees))), edgeLowFees.targetNodeId, hex"deadbeef", 1 msat, CltvExpiry(800_000)).route,
      // We reject blinded routes with low cltv_expiry_delta.
      BlindedRouteCreation.createBlindedRouteFromHops(Seq(ChannelHop(scid, nodeParams.nodeId, edgeLowExpiryDelta.targetNodeId, HopRelayParams.FromHint(edgeLowExpiryDelta))), edgeLowExpiryDelta.targetNodeId, hex"deadbeef", 1 msat, CltvExpiry(800_000)).route,
      // We reject blinded routes with low fees, even when the next node seems to be a wallet node.
      BlindedRouteCreation.createBlindedRouteToWallet(ChannelHop(scid, nodeParams.nodeId, edgeLowFees.targetNodeId, HopRelayParams.FromHint(edgeLowFees)), hex"deadbeef", 1 msat, CltvExpiry(800_000)).route,
      // We reject blinded routes that cannot be decrypted.
      BlindedRouteCreation.createBlindedRouteFromHops(Seq(ChannelHop(scid, nodeParams.nodeId, edgeLowFees.targetNodeId, HopRelayParams.FromHint(edgeLowFees))), edgeLowFees.targetNodeId, hex"deadbeef", 1 msat, CltvExpiry(800_000)).route.copy(firstPathKey = randomKey().publicKey)
    ).map(r => PaymentBlindedRoute(r, PaymentInfo(1_000_000 msat, 2500, CltvExpiryDelta(300), 1 msat, 500_000_000 msat, ByteVector.empty)))
    resolver ! Resolve(probe.ref, toResolve)
    // The routes with low fees or expiry require resolving the next node.
    register.expectMsgType[Register.GetNextNodeId].replyTo ! Some(edgeLowFees.targetNodeId)
    register.expectMsgType[Register.GetNextNodeId].replyTo ! Some(edgeLowExpiryDelta.targetNodeId)
    // All routes are ignored.
    probe.expectMsg(Seq.empty[ResolvedPath])
  }

}
