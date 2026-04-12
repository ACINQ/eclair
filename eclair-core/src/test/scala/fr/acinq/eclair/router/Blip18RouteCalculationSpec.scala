package fr.acinq.eclair.router

import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Satoshi, SatoshiLong, TxId}
import fr.acinq.eclair.payment.IncomingPaymentPacket.{ChannelRelayPacket, FinalPacket, decrypt}
import fr.acinq.eclair.payment.OutgoingPaymentPacket.buildOutgoingPayment
import fr.acinq.eclair.payment.PaymentPacketSpec.{paymentHash, paymentSecret}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.payment.send.ClearRecipient
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.router.Announcements.makeNodeAnnouncement
import fr.acinq.eclair.router.BaseRouterSpec.channelHopFromUpdate
import fr.acinq.eclair.router.Graph.GraphStructure.DirectedGraph.graphEdgeToHop
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{HeuristicsConstants, PaymentPathWeight}
import fr.acinq.eclair.router.RouteCalculation._
import fr.acinq.eclair.router.Router.MultiPartParams.{FullCapacity, MaxExpectedAmount, Randomize}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, RealShortChannelId, ShortChannelId, TestConstants, TimestampSecond, TimestampSecondLong, ToMilliSatoshiConversion, randomBytes32, randomKey}
import org.scalatest.TryValues.convertTryToSuccessOrFailure
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.{ParallelTestExecution, Tag}
import scodec.bits._

import scala.collection.mutable
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Random, Success}

class Blip18RouteCalculationSpec extends AnyFunSuite with ParallelTestExecution {

  import Blip18RouteCalculationSpec._

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  val (priv_a, priv_b, priv_c, priv_d, priv_e, priv_f) = (
    PrivateKey(hex"a5fd7d10b2756c8415d22c0bc177a7ee2ce01fc0834f0962aa2a2314f5df8310"),
    PrivateKey(hex"9b6ce29f39ebb0be0d8dfdd6fcd32c52bb0b66b7df7ece9b882428d7eb39f3d6"),
    PrivateKey(hex"e398f98ec5949f6d59da72efc2418a602d8186d2107987c3478445cb62e5dd9e"),
    PrivateKey(hex"74cb9dde6bf4e983e442241c1f1fc6387af824c45b781d94e43e4dbb8f87f1eb"),
    PrivateKey(hex"7423aa471808b212fd1ca5796a4acba1eb99a9091056c6a3c26ff0ad1a1c0282"),
    PrivateKey(hex"244ddf51ca4cc0055a0368ec8676822546bf207436c41ec58046398712c9e627"),
  )

  val (a, b, c, d, e, f) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey, priv_f.publicKey)

  test("find a direct route") {
    val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
      makeEdge(10L, a, b, minHtlc = 2 msat, feeBase = 0 msat, feeProportionalMillionth = 120, inboundFeeBase_opt = Some(0.msat), inboundFeeProportionalMillionth_opt = Some(-71)),
    )), 1 day)

    val Success(route :: Nil) = findRoute(g, a, b, 10_000_000 msat, 10_000_000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(includeLocalChannelCost = true), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true, excludePositiveInboundFees = true)

    assert(route.channelFee(true) == 1200.msat)
  }

  test("test findRoute with Blip18 enabled") {
    // extracted from the LND code base
    val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
      makeEdge(10L, a, b, minHtlc = 2 msat),
      makeEdge(10L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(-5000 msat), inboundFeeProportionalMillionth_opt = Some(-60_000)),

      makeEdge(11L, b, c, 1000 msat, 50_000, minHtlc = 2 msat),
      makeEdge(11L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(5000 msat), inboundFeeProportionalMillionth_opt = Some(0)),

      makeEdge(12L, c, d, 0 msat, 50_000, minHtlc = 2 msat),
      makeEdge(12L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(-8000 msat), inboundFeeProportionalMillionth_opt = Some(-50_000)),

      makeEdge(13L, d, e, 9000 msat, 100_000, minHtlc = 2 msat),
      makeEdge(13L, e, d, minHtlc = 2 msat, inboundFeeBase_opt = Some(2000 msat), inboundFeeProportionalMillionth_opt = Some(80_000)),
    )), 1 day)

    val Success(route :: Nil) = findRoute(g, a, e, 100_000 msat, 100_000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true, excludePositiveInboundFees = false)

    assert(route.channelFee(false) == 15_302.msat)

    val recipient = ClearRecipient(e, Features.empty, 100_000 msat, CltvExpiry(400018), paymentSecret)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)

    assert(payment.outgoingChannel == ShortChannelId(10L))
    assert(payment.cmd.amount == 115_302.msat)

    val packet_b = payment.cmd.onion

    val add_b = UpdateAddHtlc(randomBytes32(), 0, 100_000 msat, paymentHash, CltvExpiry(400018), packet_b, None, 1, None)
    val Right(relay_b@ChannelRelayPacket(_, payload_b, packet_c, _)) = decrypt(add_b, priv_b, Features.empty)
    assert(payload_b.outgoing.contains(ShortChannelId(11L)))
    assert(relay_b.amountToForward == 115_302.msat)
    assert(relay_b.relayFeeMsat == -15_302.msat)

    val add_c = UpdateAddHtlc(randomBytes32(), 1, 100_000 msat, paymentHash, CltvExpiry(400018), packet_c, None, 1, None)
    val Right(relay_c@ChannelRelayPacket(_, payload_c, packet_d, _)) = decrypt(add_c, priv_c, Features.empty)
    assert(payload_c.outgoing.contains(ShortChannelId(12L)))
    assert(relay_c.amountToForward == 105_050.msat)
    assert(relay_c.relayFeeMsat == -5050.msat)

    val add_d = UpdateAddHtlc(randomBytes32(), 2, 100_000 msat, paymentHash, CltvExpiry(400018), packet_d, None, 1, None)
    val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d, Features.empty)
    assert(payload_d.outgoing.contains(ShortChannelId(13L)))
    assert(relay_d.amountToForward == 100_000.msat)
    assert(relay_d.relayFeeMsat == 0.msat)

    val add_e = UpdateAddHtlc(randomBytes32(), 2, 100_000 msat, paymentHash, CltvExpiry(400018), packet_e, None, 1, None)
    val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e, Features.empty)
    assert(payload_e.isInstanceOf[FinalPayload.Standard])
    assert(payload_e.amount == 100_000.msat)
    assert(payload_e.totalAmount == 100_000.msat)
  }

  test("test findRoute with Blip18 disabled") {
    // extracted from the LND code base
    val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
      makeEdge(10L, a, b, minHtlc = 2 msat),
      makeEdge(10L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(-5000 msat), inboundFeeProportionalMillionth_opt = Some(-60_000)),

      makeEdge(11L, b, c, 1000 msat, 50_000, minHtlc = 2 msat),
      makeEdge(11L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(5000 msat), inboundFeeProportionalMillionth_opt = Some(0)),

      makeEdge(12L, c, d, 0 msat, 50_000, minHtlc = 2 msat),
      makeEdge(12L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(-8000 msat), inboundFeeProportionalMillionth_opt = Some(-50_000)),

      makeEdge(13L, d, e, 9000 msat, 100_000, minHtlc = 2 msat),
      makeEdge(13L, e, d, minHtlc = 2 msat, inboundFeeBase_opt = Some(2000 msat), inboundFeeProportionalMillionth_opt = Some(80_000)),
    )), 1 day)

    val Success(route :: Nil) = findRoute(g, a, e, 100_000 msat, 100_000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = false, excludePositiveInboundFees = false)

    assert(route.channelFee(false) == 32_197.msat)

    val recipient = ClearRecipient(e, Features.empty, 100_000 msat, CltvExpiry(400018), paymentSecret)
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)

    assert(payment.outgoingChannel == ShortChannelId(10L))
    assert(payment.cmd.amount == 132_197.msat)

    val packet_b = payment.cmd.onion

    val add_b = UpdateAddHtlc(randomBytes32(), 0, 100_000 msat, paymentHash, CltvExpiry(400018), packet_b, None, 1, None)
    val Right(relay_b@ChannelRelayPacket(_, payload_b, packet_c, _)) = decrypt(add_b, priv_b, Features.empty)
    assert(payload_b.outgoing.contains(ShortChannelId(11L)))
    assert(relay_b.amountToForward == 124_950.msat)
    assert(relay_b.relayFeeMsat == -24_950.msat)

    val add_c = UpdateAddHtlc(randomBytes32(), 1, 100_000 msat, paymentHash, CltvExpiry(400018), packet_c, None, 1, None)
    val Right(relay_c@ChannelRelayPacket(_, payload_c, packet_d, _)) = decrypt(add_c, priv_c, Features.empty)
    assert(payload_c.outgoing.contains(ShortChannelId(12L)))
    assert(relay_c.amountToForward == 119_000.msat)
    assert(relay_c.relayFeeMsat == -19000.msat)

    val add_d = UpdateAddHtlc(randomBytes32(), 2, 100_000 msat, paymentHash, CltvExpiry(400018), packet_d, None, 1, None)
    val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d, Features.empty)
    assert(payload_d.outgoing.contains(ShortChannelId(13L)))
    assert(relay_d.amountToForward == 100_000.msat)
    assert(relay_d.relayFeeMsat == 0.msat)

    val add_e = UpdateAddHtlc(randomBytes32(), 2, 100_000 msat, paymentHash, CltvExpiry(400018), packet_e, None, 1, None)
    val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e, Features.empty)
    assert(payload_e.isInstanceOf[FinalPayload.Standard])
    assert(payload_e.amount == 100_000.msat)
    assert(payload_e.totalAmount == 100_000.msat)
  }

  test("test findRoute selects path with negative inbound fees when BLIP-18 enabled") {
    // Two paths from a to e:
    // Path 1: a -> b -> c -> d -> e
    // Path 2: a -> f -> e
    val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
      // Path 1: a -> b -> c -> d -> e (with inbound fees)
      makeEdge(10L, a, b, minHtlc = 2 msat),
      makeEdge(10L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(-5000 msat), inboundFeeProportionalMillionth_opt = Some(-60_000)),
      makeEdge(11L, b, c, 1000 msat, 50_000, minHtlc = 2 msat),
      makeEdge(11L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(5000 msat), inboundFeeProportionalMillionth_opt = Some(0)),
      makeEdge(12L, c, d, 0 msat, 50_000, minHtlc = 2 msat),
      makeEdge(12L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(-8000 msat), inboundFeeProportionalMillionth_opt = Some(-50_000)),
      makeEdge(13L, d, e, 9000 msat, 100_000, minHtlc = 2 msat),
      makeEdge(13L, e, d, minHtlc = 2 msat, inboundFeeBase_opt = Some(2000 msat), inboundFeeProportionalMillionth_opt = Some(80_000)),
      // Path 2: a -> f -> e (no inbound fees)
      makeEdge(30L, a, f, minHtlc = 2 msat),
      makeEdge(30L, f, a, minHtlc = 2 msat),
      makeEdge(31L, f, e, 15000 msat, 100_000, minHtlc = 2 msat),
      makeEdge(31L, e, f, minHtlc = 2 msat),
    )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, e, 100_000 msat, 100_000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true, excludePositiveInboundFees = false)

      // With BLIP-18, path 1 is cheaper thanks to negative inbound fee discounts
       assert(route.hops.length == 4)
      assert(route.channelFee(false) == 15_302.msat)

      val recipient = ClearRecipient(e, Features.empty, 100_000 msat, CltvExpiry(400018), paymentSecret)
      val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)

      assert(payment.outgoingChannel == ShortChannelId(10L))
      assert(payment.cmd.amount == 115_302.msat)

      val packet_b = payment.cmd.onion

      val add_b = UpdateAddHtlc(randomBytes32(), 0, 100_000 msat, paymentHash, CltvExpiry(400018), packet_b, None, 1, None)
      val Right(relay_b@ChannelRelayPacket(_, payload_b, packet_c, _)) = decrypt(add_b, priv_b, Features.empty)
      assert(payload_b.outgoing.contains(ShortChannelId(11L)))
      assert(relay_b.amountToForward == 115_302.msat)
      assert(relay_b.relayFeeMsat == -15_302.msat)

      val add_c = UpdateAddHtlc(randomBytes32(), 1, 100_000 msat, paymentHash, CltvExpiry(400018), packet_c, None, 1, None)
      val Right(relay_c@ChannelRelayPacket(_, payload_c, packet_d, _)) = decrypt(add_c, priv_c, Features.empty)
      assert(payload_c.outgoing.contains(ShortChannelId(12L)))
      assert(relay_c.amountToForward == 105_050.msat)
      assert(relay_c.relayFeeMsat == -5050.msat)

      val add_d = UpdateAddHtlc(randomBytes32(), 2, 100_000 msat, paymentHash, CltvExpiry(400018), packet_d, None, 1, None)
      val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d, Features.empty)
      assert(payload_d.outgoing.contains(ShortChannelId(13L)))
      assert(relay_d.amountToForward == 100_000.msat)
      assert(relay_d.relayFeeMsat == 0.msat)

      val add_e = UpdateAddHtlc(randomBytes32(), 3, 100_000 msat, paymentHash, CltvExpiry(400018), packet_e, None, 1, None)
      val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e, Features.empty)
      assert(payload_e.isInstanceOf[FinalPayload.Standard])
      assert(payload_e.amount == 100_000.msat)
      assert(payload_e.totalAmount == 100_000.msat)
    }

    test("test findRoute selects path without inbound fees when BLIP-18 disabled") {
      // Same graph as above. Without BLIP-18, inbound discounts are ignored and path 2 (a -> f -> e) wins.
      val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
        // Path 1: a -> b -> c -> d -> e (inbound fees ignored, total outbound fee = 32_197)
        makeEdge(10L, a, b, minHtlc = 2 msat),
        makeEdge(10L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(-5000 msat), inboundFeeProportionalMillionth_opt = Some(-60_000)),
        makeEdge(11L, b, c, 1000 msat, 50_000, minHtlc = 2 msat),
        makeEdge(11L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(5000 msat), inboundFeeProportionalMillionth_opt = Some(0)),
        makeEdge(12L, c, d, 0 msat, 50_000, minHtlc = 2 msat),
        makeEdge(12L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(-8000 msat), inboundFeeProportionalMillionth_opt = Some(-50_000)),
        makeEdge(13L, d, e, 9000 msat, 100_000, minHtlc = 2 msat),
        makeEdge(13L, e, d, minHtlc = 2 msat, inboundFeeBase_opt = Some(2000 msat), inboundFeeProportionalMillionth_opt = Some(80_000)),
        // Path 2: a -> f -> e (no inbound fees, outbound fee = 25_000)
        makeEdge(30L, a, f, minHtlc = 2 msat),
        makeEdge(30L, f, a, minHtlc = 2 msat),
        makeEdge(31L, f, e, 15000 msat, 100_000, minHtlc = 2 msat),
        makeEdge(31L, e, f, minHtlc = 2 msat),
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, e, 100_000 msat, 100_000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = false, excludePositiveInboundFees = false)

      // Without BLIP-18, path 2 is cheaper based on outbound fees alone
      assert(route.hops.length == 2)
      assert(route.channelFee(false) == 25_000.msat)

      val recipient = ClearRecipient(e, Features.empty, 100_000 msat, CltvExpiry(400018), paymentSecret)
      val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)

      assert(payment.outgoingChannel == ShortChannelId(30L))
      assert(payment.cmd.amount == 125_000.msat)

      val packet_f = payment.cmd.onion

      val add_f = UpdateAddHtlc(randomBytes32(), 0, 100_000 msat, paymentHash, CltvExpiry(400018), packet_f, None, 1, None)
      val Right(relay_f@ChannelRelayPacket(_, payload_f, packet_e, _)) = decrypt(add_f, priv_f, Features.empty)
      assert(payload_f.outgoing.contains(ShortChannelId(31L)))
      assert(relay_f.amountToForward == 100_000.msat)
      assert(relay_f.relayFeeMsat == 0.msat)

      val add_e = UpdateAddHtlc(randomBytes32(), 1, 100_000 msat, paymentHash, CltvExpiry(400018), packet_e, None, 1, None)
      val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e, Features.empty)
      assert(payload_e.isInstanceOf[FinalPayload.Standard])
      assert(payload_e.amount == 100_000.msat)
      assert(payload_e.totalAmount == 100_000.msat)
    }


    test("test findMultiPartRoute with Blip18 enabled") {
      // extracted from the LND code base
      val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
        makeEdge(10L, a, b, minHtlc = 2 msat),
        makeEdge(10L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(-5000 msat), inboundFeeProportionalMillionth_opt = Some(-60_000)),

        makeEdge(11L, b, c, 1000 msat, 50_000, minHtlc = 2 msat),
        makeEdge(11L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(5000 msat), inboundFeeProportionalMillionth_opt = Some(0)),

        makeEdge(12L, c, d, 0 msat, 50_000, minHtlc = 2 msat),
        makeEdge(12L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(-8000 msat), inboundFeeProportionalMillionth_opt = Some(-50_000)),

        makeEdge(13L, d, e, 9000 msat, 100_000, minHtlc = 2 msat),
        makeEdge(13L, e, d, minHtlc = 2 msat, inboundFeeBase_opt = Some(2000 msat), inboundFeeProportionalMillionth_opt = Some(80_000)),
      )), 1 day)

      val Success(route :: Nil) = findMultiPartRoute(g, a, e, 100_000 msat, 100_000 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true, excludePositiveInboundFees = false)

      assert(route.channelFee(false) == 15_302.msat)

      val recipient = ClearRecipient(e, Features.empty, 100_000 msat, CltvExpiry(400018), paymentSecret)
      val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)

      assert(payment.outgoingChannel == ShortChannelId(10L))
      assert(payment.cmd.amount == 115_302.msat)

      val packet_b = payment.cmd.onion

      val add_b = UpdateAddHtlc(randomBytes32(), 0, 100_000 msat, paymentHash, CltvExpiry(400018), packet_b, None, 1, None)
      val Right(relay_b@ChannelRelayPacket(_, payload_b, packet_c, _)) = decrypt(add_b, priv_b, Features.empty)
      assert(payload_b.outgoing.contains(ShortChannelId(11L)))
      assert(relay_b.amountToForward == 115_302.msat)
      assert(relay_b.relayFeeMsat == -15_302.msat)

      val add_c = UpdateAddHtlc(randomBytes32(), 1, 100_000 msat, paymentHash, CltvExpiry(400018), packet_c, None, 1, None)
      val Right(relay_c@ChannelRelayPacket(_, payload_c, packet_d, _)) = decrypt(add_c, priv_c, Features.empty)
      assert(payload_c.outgoing.contains(ShortChannelId(12L)))
      assert(relay_c.amountToForward == 105_050.msat)
      assert(relay_c.relayFeeMsat == -5050.msat)

      val add_d = UpdateAddHtlc(randomBytes32(), 2, 100_000 msat, paymentHash, CltvExpiry(400018), packet_d, None, 1, None)
      val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d, Features.empty)
      assert(payload_d.outgoing.contains(ShortChannelId(13L)))
      assert(relay_d.amountToForward == 100_000.msat)
      assert(relay_d.relayFeeMsat == 0.msat)

      val add_e = UpdateAddHtlc(randomBytes32(), 2, 100_000 msat, paymentHash, CltvExpiry(400018), packet_e, None, 1, None)
      val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e, Features.empty)
      assert(payload_e.isInstanceOf[FinalPayload.Standard])
      assert(payload_e.amount == 100_000.msat)
      assert(payload_e.totalAmount == 100_000.msat)
    }

    test("test findMultiPartRoute with Blip18 disabled") {
      // extracted from the LND code base
      val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
        makeEdge(10L, a, b, minHtlc = 2 msat),
        makeEdge(10L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(-5000 msat), inboundFeeProportionalMillionth_opt = Some(-60_000)),

        makeEdge(11L, b, c, 1000 msat, 50_000, minHtlc = 2 msat),
        makeEdge(11L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(5000 msat), inboundFeeProportionalMillionth_opt = Some(0)),

        makeEdge(12L, c, d, 0 msat, 50_000, minHtlc = 2 msat),
        makeEdge(12L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(-8000 msat), inboundFeeProportionalMillionth_opt = Some(-50_000)),

        makeEdge(13L, d, e, 9000 msat, 100_000, minHtlc = 2 msat),
        makeEdge(13L, e, d, minHtlc = 2 msat, inboundFeeBase_opt = Some(2000 msat), inboundFeeProportionalMillionth_opt = Some(80_000)),
      )), 1 day)

      val Success(route :: Nil) = findMultiPartRoute(g, a, e, 100_000 msat, 100_000 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = false, excludePositiveInboundFees = false)

      assert(route.channelFee(false) == 32_197.msat)

      val recipient = ClearRecipient(e, Features.empty, 100_000 msat, CltvExpiry(400018), paymentSecret)
      val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)

      assert(payment.outgoingChannel == ShortChannelId(10L))
      assert(payment.cmd.amount == 132_197.msat)

      val packet_b = payment.cmd.onion

      val add_b = UpdateAddHtlc(randomBytes32(), 0, 100_000 msat, paymentHash, CltvExpiry(400018), packet_b, None, 1, None)
      val Right(relay_b@ChannelRelayPacket(_, payload_b, packet_c, _)) = decrypt(add_b, priv_b, Features.empty)
      assert(payload_b.outgoing.contains(ShortChannelId(11L)))
      assert(relay_b.amountToForward == 124_950.msat)
      assert(relay_b.relayFeeMsat == -24_950.msat)

      val add_c = UpdateAddHtlc(randomBytes32(), 1, 100_000 msat, paymentHash, CltvExpiry(400018), packet_c, None, 1, None)
      val Right(relay_c@ChannelRelayPacket(_, payload_c, packet_d, _)) = decrypt(add_c, priv_c, Features.empty)
      assert(payload_c.outgoing.contains(ShortChannelId(12L)))
      assert(relay_c.amountToForward == 119_000.msat)
      assert(relay_c.relayFeeMsat == -19000.msat)

      val add_d = UpdateAddHtlc(randomBytes32(), 2, 100_000 msat, paymentHash, CltvExpiry(400018), packet_d, None, 1, None)
      val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d, Features.empty)
      assert(payload_d.outgoing.contains(ShortChannelId(13L)))
      assert(relay_d.amountToForward == 100_000.msat)
      assert(relay_d.relayFeeMsat == 0.msat)

      val add_e = UpdateAddHtlc(randomBytes32(), 2, 100_000 msat, paymentHash, CltvExpiry(400018), packet_e, None, 1, None)
      val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e, Features.empty)
      assert(payload_e.isInstanceOf[FinalPayload.Standard])
      assert(payload_e.amount == 100_000.msat)
      assert(payload_e.totalAmount == 100_000.msat)
    }

    test("test findRoute selects path without positive inbound fees when BLIP-18 enabled") {
      // Two paths from a to e:
      // Path 1: a -> b -> c -> d -> e (with positive inbound fees, more expensive with BLIP-18)
      // Path 2: a -> f -> e (no inbound fees, cheaper with BLIP-18)
      val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
        // Path 1: a -> b -> c -> d -> e (positive inbound fees on back-edges)
        makeEdge(40L, a, b, minHtlc = 2 msat),
        makeEdge(40L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(3000 msat), inboundFeeProportionalMillionth_opt = Some(30_000)),
        makeEdge(41L, b, c, 1000 msat, 20_000, minHtlc = 2 msat),
        makeEdge(41L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(3000 msat), inboundFeeProportionalMillionth_opt = Some(30_000)),
        makeEdge(42L, c, d, 1000 msat, 20_000, minHtlc = 2 msat),
        makeEdge(42L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(3000 msat), inboundFeeProportionalMillionth_opt = Some(30_000)),
        makeEdge(43L, d, e, 2000 msat, 20_000, minHtlc = 2 msat),
        makeEdge(43L, e, d, minHtlc = 2 msat),
        // Path 2: a -> f -> e (no inbound fees)
        makeEdge(50L, a, f, minHtlc = 2 msat),
        makeEdge(50L, f, a, minHtlc = 2 msat),
        makeEdge(51L, f, e, 8000 msat, 80_000, minHtlc = 2 msat),
        makeEdge(51L, e, f, minHtlc = 2 msat),
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, e, 100_000 msat, 100_000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true, excludePositiveInboundFees = false)

      // With BLIP-18, path 2 is cheaper because path 1's positive inbound fees make it more expensive
      assert(route.hops.length == 2)
      assert(route.channelFee(false) == 16_000.msat)

      val recipient = ClearRecipient(e, Features.empty, 100_000 msat, CltvExpiry(400018), paymentSecret)
      val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)

      assert(payment.outgoingChannel == ShortChannelId(50L))
      assert(payment.cmd.amount == 116_000.msat)

      val packet_f = payment.cmd.onion

      val add_f = UpdateAddHtlc(randomBytes32(), 0, 100_000 msat, paymentHash, CltvExpiry(400018), packet_f, None, 1, None)
      val Right(relay_f@ChannelRelayPacket(_, payload_f, packet_e, _)) = decrypt(add_f, priv_f, Features.empty)
      assert(payload_f.outgoing.contains(ShortChannelId(51L)))
      assert(relay_f.amountToForward == 100_000.msat)
      assert(relay_f.relayFeeMsat == 0.msat)

      val add_e = UpdateAddHtlc(randomBytes32(), 1, 100_000 msat, paymentHash, CltvExpiry(400018), packet_e, None, 1, None)
      val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e, Features.empty)
      assert(payload_e.isInstanceOf[FinalPayload.Standard])
      assert(payload_e.amount == 100_000.msat)
      assert(payload_e.totalAmount == 100_000.msat)
    }

    test("test findRoute selects path with positive inbound fees when BLIP-18 disabled") {
      // Same graph as above. Without BLIP-18, positive inbound fees are ignored and path 1 wins on lower outbound fees.
      val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
        // Path 1: a -> b -> c -> d -> e (positive inbound fees ignored, outbound fee = 10_221)
        makeEdge(40L, a, b, minHtlc = 2 msat),
        makeEdge(40L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(1000 msat), inboundFeeProportionalMillionth_opt = Some(10_000)),
        makeEdge(41L, b, c, 1000 msat, 20_000, minHtlc = 2 msat),
        makeEdge(41L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(2000 msat), inboundFeeProportionalMillionth_opt = Some(20_000)),
        makeEdge(42L, c, d, 1000 msat, 20_000, minHtlc = 2 msat),
        makeEdge(42L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(3000 msat), inboundFeeProportionalMillionth_opt = Some(30_000)),
        makeEdge(43L, d, e, 2000 msat, 20_000, minHtlc = 2 msat),
        makeEdge(43L, e, d, minHtlc = 2 msat),
        // Path 2: a -> f -> e (no inbound fees, outbound fee = 16_000)
        makeEdge(50L, a, f, minHtlc = 2 msat),
        makeEdge(50L, f, a, minHtlc = 2 msat),
        makeEdge(51L, f, e, 8000 msat, 80_000, minHtlc = 2 msat),
        makeEdge(51L, e, f, minHtlc = 2 msat),
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, e, 100_000 msat, 100_000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = false, excludePositiveInboundFees = false)

      // Without BLIP-18, path 1 is cheaper based on outbound fees alone
      assert(route.hops.length == 4)
      assert(route.channelFee(false) == 10_221.msat)

      val recipient = ClearRecipient(e, Features.empty, 100_000 msat, CltvExpiry(400018), paymentSecret)
      val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)

      assert(payment.outgoingChannel == ShortChannelId(40L))
      assert(payment.cmd.amount == 110_221.msat)

      val packet_b = payment.cmd.onion

      val add_b = UpdateAddHtlc(randomBytes32(), 0, 100_000 msat, paymentHash, CltvExpiry(400018), packet_b, None, 1, None)
      val Right(relay_b@ChannelRelayPacket(_, payload_b, packet_c, _)) = decrypt(add_b, priv_b, Features.empty)
      assert(payload_b.outgoing.contains(ShortChannelId(41L)))
      assert(relay_b.amountToForward == 107_080.msat)
      assert(relay_b.relayFeeMsat == -7_080.msat)

      val add_c = UpdateAddHtlc(randomBytes32(), 1, 100_000 msat, paymentHash, CltvExpiry(400018), packet_c, None, 1, None)
      val Right(relay_c@ChannelRelayPacket(_, payload_c, packet_d, _)) = decrypt(add_c, priv_c, Features.empty)
      assert(payload_c.outgoing.contains(ShortChannelId(42L)))
      assert(relay_c.amountToForward == 104_000.msat)
      assert(relay_c.relayFeeMsat == -4_000.msat)

      val add_d = UpdateAddHtlc(randomBytes32(), 2, 100_000 msat, paymentHash, CltvExpiry(400018), packet_d, None, 1, None)
      val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_e, _)) = decrypt(add_d, priv_d, Features.empty)
      assert(payload_d.outgoing.contains(ShortChannelId(43L)))
      assert(relay_d.amountToForward == 100_000.msat)
      assert(relay_d.relayFeeMsat == 0.msat)

      val add_e = UpdateAddHtlc(randomBytes32(), 3, 100_000 msat, paymentHash, CltvExpiry(400018), packet_e, None, 1, None)
      val Right(FinalPacket(_, payload_e, _)) = decrypt(add_e, priv_e, Features.empty)
      assert(payload_e.isInstanceOf[FinalPayload.Standard])
      assert(payload_e.amount == 100_000.msat)
      assert(payload_e.totalAmount == 100_000.msat)
    }

    test("prefer path with 1 msat cheaper negative inbound fee") {
      // Two structurally identical 2-hop paths from a to c, both with negative inbound fees.
      // Path 1 via b: relay fee = 5000 msat, inbound discount at b = -1000 msat → net cost = 4000 msat
      // Path 2 via d: relay fee = 5000 msat, inbound discount at d = -1001 msat → net cost = 3999 msat
      // Path 2 is 1 msat cheaper due to a more negative inbound fee and must be selected.
      val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
        // Path 1: a -> b -> c 105_000
        makeEdge(10L, a, b, minHtlc = 2 msat),
        makeEdge(10L, b, a, minHtlc = 2 msat,
          inboundFeeBase_opt = None, inboundFeeProportionalMillionth_opt = None),
        makeEdge(11L, b, c, feeBase = 5000 msat, minHtlc = 2 msat),
        makeEdge(11L, c, b, minHtlc = 2 msat),
        // Path 2: a -> d -> c 104_999
        makeEdge(20L, a, d, minHtlc = 2 msat),
        makeEdge(20L, d, a, minHtlc = 2 msat,
          inboundFeeBase_opt = Some(-1 msat), inboundFeeProportionalMillionth_opt = Some(0)),
        makeEdge(21L, d, c, feeBase = 5000 msat, minHtlc = 2 msat),
        makeEdge(21L, c, d, minHtlc = 2 msat),
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, c, 100_000 msat, 100_000 msat, numRoutes = 1,
        routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000),
        blip18InboundFees = true, excludePositiveInboundFees = false)

      // Path 2 (via d, channels 20 and 21) wins by 1 msat
      assert(route.hops.length == 2)
      assert(route.channelFee(false) == 4999.msat)

      val recipient = ClearRecipient(c, Features.empty, 100_000 msat, CltvExpiry(400018), paymentSecret)
      val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)

      assert(payment.outgoingChannel == ShortChannelId(20L))
      assert(payment.cmd.amount == 104_999.msat)

      val packet_d = payment.cmd.onion

      val add_d = UpdateAddHtlc(randomBytes32(), 0, 100_000 msat, paymentHash, CltvExpiry(400018), packet_d, None, 1, None)
      val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_c, _)) = decrypt(add_d, priv_d, Features.empty)
      assert(payload_d.outgoing.contains(ShortChannelId(21L)))
      assert(relay_d.amountToForward == 100_000.msat)
      assert(relay_d.relayFeeMsat == 0.msat)

      val add_c = UpdateAddHtlc(randomBytes32(), 1, 100_000 msat, paymentHash, CltvExpiry(400018), packet_c, None, 1, None)
      val Right(FinalPacket(_, payload_c, _)) = decrypt(add_c, priv_c, Features.empty)
      assert(payload_c.isInstanceOf[FinalPayload.Standard])
      assert(payload_c.amount == 100_000.msat)
      assert(payload_c.totalAmount == 100_000.msat)
    }

    test("prefer path with 1 msat cheaper outbound fee") {
      // Two structurally identical 2-hop paths from a to c, both with negative inbound fees.
      // Path 1 via b: relay fee = 5000 msat, inbound discount at b = -1000 msat → net cost = 4000 msat
      // Path 2 via d: relay fee = 4999 msat, inbound discount at d = -1000 msat → net cost = 3999 msat
      // Path 2 is 1 msat cheaper and must be selected.
      val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
        // Path 1: a -> b -> c
        makeEdge(10L, a, b, minHtlc = 2 msat),
        makeEdge(10L, b, a, minHtlc = 2 msat,
          inboundFeeBase_opt = Some(-1000 msat), inboundFeeProportionalMillionth_opt = Some(0)),
        makeEdge(11L, b, c, feeBase = 5000 msat, minHtlc = 2 msat),
        makeEdge(11L, c, b, minHtlc = 2 msat),
        // Path 2: a -> d -> c
        makeEdge(20L, a, d, minHtlc = 2 msat),
        makeEdge(20L, d, a, minHtlc = 2 msat,
          inboundFeeBase_opt = Some(-1000 msat), inboundFeeProportionalMillionth_opt = Some(0)),
        makeEdge(21L, d, c, feeBase = 4999 msat, minHtlc = 2 msat),
        makeEdge(21L, c, d, minHtlc = 2 msat),
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, c, 100_000 msat, 100_000 msat, numRoutes = 1,
        routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000),
        blip18InboundFees = true, excludePositiveInboundFees = false)

      // Path 2 (via d, channels 20 and 21) wins by 1 msat
      assert(route.hops.length == 2)
      assert(route.channelFee(false) == 3999.msat)

      val recipient = ClearRecipient(c, Features.empty, 100_000 msat, CltvExpiry(400018), paymentSecret)
      val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)

      assert(payment.outgoingChannel == ShortChannelId(20L))
      assert(payment.cmd.amount == 103_999.msat)

      val packet_d = payment.cmd.onion

      val add_d = UpdateAddHtlc(randomBytes32(), 0, 100_000 msat, paymentHash, CltvExpiry(400018), packet_d, None, 1, None)
      val Right(relay_d@ChannelRelayPacket(_, payload_d, packet_c, _)) = decrypt(add_d, priv_d, Features.empty)
      assert(payload_d.outgoing.contains(ShortChannelId(21L)))
      assert(relay_d.amountToForward == 100_000.msat)
      assert(relay_d.relayFeeMsat == 0.msat)

      val add_c = UpdateAddHtlc(randomBytes32(), 1, 100_000 msat, paymentHash, CltvExpiry(400018), packet_c, None, 1, None)
      val Right(FinalPacket(_, payload_c, _)) = decrypt(add_c, priv_c, Features.empty)
      assert(payload_c.isInstanceOf[FinalPayload.Standard])
      assert(payload_c.amount == 100_000.msat)
      assert(payload_c.totalAmount == 100_000.msat)
    }

    test("calculate Blip18 simple route with a positive inbound fees channel") {
      // channels with positive (greater than 0) inbound fees should be automatically excluded from path finding

      {
        val g = GraphWithBalanceEstimates(DirectedGraph(List(
          makeEdge(1L, a, b, 1 msat, 10, cltvDelta = CltvExpiryDelta(1), balance_opt = Some(DEFAULT_AMOUNT_MSAT * 2)),
          makeEdge(2L, b, c, 2 msat, 20, cltvDelta = CltvExpiryDelta(1)),
          makeEdge(2L, c, b, 2 msat, 20, cltvDelta = CltvExpiryDelta(1), inboundFeeBase_opt = Some(1 msat), inboundFeeProportionalMillionth_opt = Some(10)),
          makeEdge(3L, c, d, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
          makeEdge(4L, d, e, 1 msat, 10, cltvDelta = CltvExpiryDelta(1))
        )), 1 day)

        val res = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true, excludePositiveInboundFees = true)
        assert(res == Failure(RouteNotFound))
      }
      {
        val g = GraphWithBalanceEstimates(DirectedGraph(List(
          makeEdge(1L, a, b, 1 msat, 10, cltvDelta = CltvExpiryDelta(1), balance_opt = Some(DEFAULT_AMOUNT_MSAT * 2)),
          makeEdge(2L, b, c, 2 msat, 20, cltvDelta = CltvExpiryDelta(1)),
          makeEdge(2L, c, b, 2 msat, 20, cltvDelta = CltvExpiryDelta(1), inboundFeeBase_opt = Some(1 msat), inboundFeeProportionalMillionth_opt = Some(0)),
          makeEdge(3L, c, d, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
          makeEdge(4L, d, e, 1 msat, 10, cltvDelta = CltvExpiryDelta(1))
        )), 1 day)

        val res = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true, excludePositiveInboundFees = true)
        assert(res == Failure(RouteNotFound))
      }
      {
        val g = GraphWithBalanceEstimates(DirectedGraph(List(
          makeEdge(1L, a, b, 1 msat, 10, cltvDelta = CltvExpiryDelta(1), balance_opt = Some(DEFAULT_AMOUNT_MSAT * 2)),
          makeEdge(2L, b, c, 2 msat, 20, cltvDelta = CltvExpiryDelta(1)),
          makeEdge(2L, c, b, 2 msat, 20, cltvDelta = CltvExpiryDelta(1), inboundFeeBase_opt = Some(0 msat), inboundFeeProportionalMillionth_opt = Some(10)),
          makeEdge(3L, c, d, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
          makeEdge(4L, d, e, 1 msat, 10, cltvDelta = CltvExpiryDelta(1))
        )), 1 day)

        val res = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true, excludePositiveInboundFees = true)
        assert(res == Failure(RouteNotFound))
      }
      {
        val g = GraphWithBalanceEstimates(DirectedGraph(List(
          makeEdge(1L, a, b, 1 msat, 10, cltvDelta = CltvExpiryDelta(1), balance_opt = Some(DEFAULT_AMOUNT_MSAT * 2)),
          makeEdge(2L, b, c, 2 msat, 20, cltvDelta = CltvExpiryDelta(1)),
          makeEdge(2L, c, b, 2 msat, 20, cltvDelta = CltvExpiryDelta(1), inboundFeeBase_opt = Some(1 msat), inboundFeeProportionalMillionth_opt = Some(-10)),
          makeEdge(3L, c, d, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
          makeEdge(4L, d, e, 1 msat, 10, cltvDelta = CltvExpiryDelta(1))
        )), 1 day)

        val res = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true, excludePositiveInboundFees = true)
        assert(res == Failure(RouteNotFound))
      }
      {
        val g = GraphWithBalanceEstimates(DirectedGraph(List(
          makeEdge(1L, a, b, 1 msat, 10, cltvDelta = CltvExpiryDelta(1), balance_opt = Some(DEFAULT_AMOUNT_MSAT * 2)),
          makeEdge(2L, b, c, 2 msat, 20, cltvDelta = CltvExpiryDelta(1)),
          makeEdge(2L, c, b, 2 msat, 20, cltvDelta = CltvExpiryDelta(1), inboundFeeBase_opt = Some(-1 msat), inboundFeeProportionalMillionth_opt = Some(10)),
          makeEdge(3L, c, d, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
          makeEdge(4L, d, e, 1 msat, 10, cltvDelta = CltvExpiryDelta(1))
        )), 1 day)

        val res = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true, excludePositiveInboundFees = true)
        assert(res == Failure(RouteNotFound))
      }
    }

    // run tests from RouteCalculationSpec with inbound fees enabled

    test("calculate simple route") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 1 msat, 10, cltvDelta = CltvExpiryDelta(1), balance_opt = Some(DEFAULT_AMOUNT_MSAT * 2)),
        makeEdge(2L, b, c, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
        makeEdge(3L, c, d, 1 msat, 10, cltvDelta = CltvExpiryDelta(1)),
        makeEdge(4L, d, e, 1 msat, 10, cltvDelta = CltvExpiryDelta(1))
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 1 :: 2 :: 3 :: 4 :: Nil)
    }

    test("check fee against max pct properly") {
      // fee is acceptable if it is either:
      //  - below our maximum fee base
      //  - below our maximum fraction of the paid amount
      // here we have a maximum fee base of 1 msat, and all our updates have a base fee of 10 msat
      // so our fee will always be above the base fee, and we will always check that it is below our maximum percentage
      // of the amount being paid
      val routeParams = DEFAULT_ROUTE_PARAMS.modify(_.boundaries.maxFeeFlat).setTo(1 msat)
      val maxFee = routeParams.getMaxFee(DEFAULT_AMOUNT_MSAT)

      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 10 msat, 10, cltvDelta = CltvExpiryDelta(1)),
        makeEdge(2L, b, c, 10 msat, 10, cltvDelta = CltvExpiryDelta(1)),
        makeEdge(3L, c, d, 10 msat, 10, cltvDelta = CltvExpiryDelta(1)),
        makeEdge(4L, d, e, 10 msat, 10, cltvDelta = CltvExpiryDelta(1))
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, maxFee, numRoutes = 1, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 1 :: 2 :: 3 :: 4 :: Nil)
    }

    test("calculate the shortest path (correct fees)") {
      val (a, b, c, d, e, f) = (
        PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // a: source
        PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
        PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
        PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"), // d: target
        PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"),
        PublicKey(hex"020c65be6f9252e85ae2fe9a46eed892cb89565e2157730e78311b1621a0db4b22")
      )

      // note: we don't actually use floating point numbers
      // cost(CD) = 10005 = amountMsat + 1 + (amountMsat * 400 / 1000000)
      // cost(BC) = 10009,0015 = (cost(CD) + 1 + (cost(CD) * 300 / 1000000)
      // cost(FD) = 10002 = amountMsat + 1 + (amountMsat * 100 / 1000000)
      // cost(EF) = 10007,0008 = cost(FD) + 1 + (cost(FD) * 400 / 1000000)
      // cost(AE) = 10007 -> A is source, shortest path found
      // cost(AB) = 10009
      //
      // The amounts that need to be sent through each edge are then:
      //
      //                 +--- A ---+
      // 10009,0015 msat |         | 10007,0008 msat
      //                 B         E
      //      10005 msat |         | 10002 msat
      //                 C         F
      //      10000 msat |         | 10000 msat
      //                 +--> D <--+

      val amount = 10000 msat
      val expectedCost = 10007 msat
      val graph = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, feeBase = 1 msat, feeProportionalMillionth = 200, minHtlc = 0 msat),
        makeEdge(4L, a, e, feeBase = 1 msat, feeProportionalMillionth = 200, minHtlc = 0 msat),
        makeEdge(2L, b, c, feeBase = 1 msat, feeProportionalMillionth = 300, minHtlc = 0 msat),
        makeEdge(3L, c, d, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat),
        makeEdge(5L, e, f, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat),
        makeEdge(6L, f, d, feeBase = 1 msat, feeProportionalMillionth = 100, minHtlc = 0 msat)
      )), 1 day)

      val Success(route :: Nil) = findRoute(graph, a, d, amount, maxFee = 7 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      val weightedPath = Graph.pathWeight(graph.balances, a, route2Edges(route), amount, BlockHeight(0), NO_WEIGHT_RATIOS, includeLocalChannelCost = false, graph.graph, enableInboundFees = false)
      assert(route2Ids(route) == 4 :: 5 :: 6 :: Nil)
      assert(weightedPath.length == 3)
      assert(weightedPath.amount == expectedCost)

      // update channel 5 so that it can route the final amount (10000) but not the amount + fees (10002)
      val graph1 = graph.addEdge(makeEdge(5L, e, f, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat, maxHtlc = Some(10001 msat)))
      val graph2 = graph.addEdge(makeEdge(5L, e, f, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat, capacity = 10 sat))
      val graph3 = graph.addEdge(makeEdge(5L, e, f, feeBase = 1 msat, feeProportionalMillionth = 400, minHtlc = 0 msat, balance_opt = Some(10001 msat)))
      for (g <- Seq(graph1, graph2, graph3)) {
        val Success(route1 :: Nil) = findRoute(g, a, d, amount, maxFee = 10 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(route2Ids(route1) == 1 :: 2 :: 3 :: Nil)
      }
    }

    test("calculate route considering the direct channel pays no fees") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 5 msat, 0), // a -> b
        makeEdge(2L, a, d, 15 msat, 0), // a -> d  this goes a bit closer to the target and asks for higher fees but is a direct channel
        makeEdge(3L, b, c, 5 msat, 0), // b -> c
        makeEdge(4L, c, d, 5 msat, 0), // c -> d
        makeEdge(5L, d, e, 5 msat, 0) // d -> e
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 2 :: 5 :: Nil)
    }

    test("calculate simple route (add and remove edges") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 0 msat, 0),
        makeEdge(2L, b, c, 0 msat, 0),
        makeEdge(3L, c, d, 0 msat, 0),
        makeEdge(4L, d, e, 0 msat, 0)
      )), 1 day)

      val Success(route1 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route1) == 1 :: 2 :: 3 :: 4 :: Nil)

      val graphWithRemovedEdge = g.disableEdge(ChannelDesc(ShortChannelId(3L), c, d))
      val route2 = findRoute(graphWithRemovedEdge, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2 == Failure(RouteNotFound))
    }

    test("calculate the shortest path (hardcoded nodes)") {
      val (f, g, h, i) = (
        PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // source
        PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
        PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
        PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // target
      )

      val graph = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, f, g, 1 msat, 0),
        makeEdge(2L, g, h, 1 msat, 0),
        makeEdge(3L, h, i, 1 msat, 0),
        makeEdge(4L, f, h, 50 msat, 0) // more expensive but fee will be ignored since f is the payer
      )), 1 day)

      val Success(route :: Nil) = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 4 :: 3 :: Nil)
    }

    test("calculate the shortest path (select direct channel)") {
      val (f, g, h, i) = (
        PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // source
        PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
        PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
        PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // target
      )

      val graph = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, f, g, 0 msat, 0),
        makeEdge(4L, f, i, 50 msat, 0), // our starting node F has a direct channel with I
        makeEdge(2L, g, h, 0 msat, 0),
        makeEdge(3L, h, i, 0 msat, 0)
      )), 1 day)

      val Success(route1 :: route2 :: Nil) = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 2, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route1) == 4 :: Nil)
      assert(route2Ids(route2) == 1 :: 2 :: 3 :: Nil)
    }

    test("find a route using channels with htlMaximumMsat close to the payment amount") {
      val (f, g, h, i) = (
        PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
        PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
        PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
        PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
      )

      val graph = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, f, g, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 50.msat)),
        // the maximum htlc allowed by this channel is only 50 msat greater than what we're sending
        makeEdge(2L, g, h, 1 msat, 0, maxHtlc = Some(DEFAULT_AMOUNT_MSAT + 50.msat)),
        makeEdge(3L, h, i, 1 msat, 0)
      )), 1 day)

      val Success(route :: Nil) = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 1 :: 2 :: 3 :: Nil)
    }

    test("find a route using channels with htlMinimumMsat close to the payment amount") {
      val (f, g, h, i) = (
        PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
        PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
        PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
        PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
      )

      val graph = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, f, g, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 50.msat)),
        // this channel requires a minimum amount that is larger than what we are sending
        makeEdge(2L, g, h, 1 msat, 0, minHtlc = DEFAULT_AMOUNT_MSAT + 50.msat),
        makeEdge(3L, h, i, 1 msat, 0)
      )), 1 day)

      val route = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route == Failure(RouteNotFound))
    }

    test("if there are multiple channels between the same node, select the cheapest") {
      val (f, g, h, i) = (
        PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
        PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
        PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
        PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
      )

      val graph = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, f, g, 0 msat, 0),
        makeEdge(2L, g, h, 5 msat, 5), // expensive  g -> h channel
        makeEdge(6L, g, h, 0 msat, 0), // cheap      g -> h channel
        makeEdge(3L, h, i, 0 msat, 0)
      )), 1 day)

      val Success(route :: Nil) = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 1 :: 6 :: 3 :: Nil)
    }

    test("if there are multiple channels between the same node, select one that has enough balance") {
      val (f, g, h, i) = (
        PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"), // F source
        PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"), // G
        PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"), // H
        PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c") // I target
      )

      val graph = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, f, g, 0 msat, 0),
        makeEdge(2L, g, h, 5 msat, 5, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 1.msat)), // expensive g -> h channel with enough balance
        makeEdge(6L, g, h, 0 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT - 10.msat)), // cheap g -> h channel without enough balance
        makeEdge(3L, h, i, 0 msat, 0)
      )), 1 day)

      val Success(route :: Nil) = findRoute(graph, f, i, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 1 :: 2 :: 3 :: Nil)
    }

    test("calculate longer but cheaper route") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 0 msat, 0),
        makeEdge(2L, b, c, 0 msat, 0),
        makeEdge(3L, c, d, 0 msat, 0),
        makeEdge(4L, d, e, 0 msat, 0),
        makeEdge(5L, b, e, 10 msat, 10)
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 1 :: 2 :: 3 :: 4 :: Nil)
    }

    test("no local channels") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(2L, b, c, 0 msat, 0),
        makeEdge(4L, d, e, 0 msat, 0)
      )), 1 day)

      val route = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route == Failure(RouteNotFound))
    }

    test("route not found") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 0 msat, 0),
        makeEdge(2L, b, c, 0 msat, 0),
        makeEdge(4L, d, e, 0 msat, 0)
      )), 1 day)

      val route = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route == Failure(RouteNotFound))
    }

    test("route not found (source OR target node not connected)") {
      val priv_a = randomKey()
      val a = priv_a.publicKey
      val annA = makeNodeAnnouncement(priv_a, "A", Color(0, 0, 0), Nil, Features.empty)
      val priv_e = randomKey()
      val e = priv_e.publicKey
      val annE = makeNodeAnnouncement(priv_e, "E", Color(0, 0, 0), Nil, Features.empty)

      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(2L, b, c, 0 msat, 0),
        makeEdge(4L, c, d, 0 msat, 0)
      )).addOrUpdateVertex(annA).addOrUpdateVertex(annE), 1 day)

      assert(findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true) == Failure(RouteNotFound))
      assert(findRoute(g, b, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true) == Failure(RouteNotFound))
    }

    test("route not found (amount too high OR too low)") {
      val highAmount = DEFAULT_AMOUNT_MSAT * 10
      val lowAmount = DEFAULT_AMOUNT_MSAT / 10

      val edgesHi = List(
        makeEdge(1L, a, b, 0 msat, 0),
        makeEdge(2L, b, c, 0 msat, 0, maxHtlc = Some(DEFAULT_AMOUNT_MSAT)),
        makeEdge(3L, c, d, 0 msat, 0)
      )

      val edgesLo = List(
        makeEdge(1L, a, b, 0 msat, 0),
        makeEdge(2L, b, c, 0 msat, 0, minHtlc = DEFAULT_AMOUNT_MSAT),
        makeEdge(3L, c, d, 0 msat, 0)
      )

      val g = GraphWithBalanceEstimates(DirectedGraph(edgesHi), 1 day)
      val g1 = GraphWithBalanceEstimates(DirectedGraph(edgesLo), 1 day)

      assert(findRoute(g, a, d, highAmount, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true) == Failure(RouteNotFound))
      assert(findRoute(g1, a, d, lowAmount, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true) == Failure(RouteNotFound))
    }

    test("route not found (balance too low)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 1 msat, 2, minHtlc = 10000 msat),
        makeEdge(2L, b, c, 1 msat, 2, minHtlc = 10000 msat),
        makeEdge(3L, c, d, 1 msat, 2, minHtlc = 10000 msat)
      )), 1 day)
      assert(findRoute(g, a, d, 15000 msat, 100 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true).isSuccess)

      // not enough balance on the last edge
      val g1 = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 1 msat, 2, minHtlc = 10000 msat),
        makeEdge(2L, b, c, 1 msat, 2, minHtlc = 10000 msat),
        makeEdge(3L, c, d, 1 msat, 2, minHtlc = 10000 msat, balance_opt = Some(10000 msat))
      )), 1 day)
      // not enough balance on intermediate edge (taking fee into account)
      val g2 = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 1 msat, 2, minHtlc = 10000 msat),
        makeEdge(2L, b, c, 1 msat, 2, minHtlc = 10000 msat, balance_opt = Some(15000 msat)),
        makeEdge(3L, c, d, 1 msat, 2, minHtlc = 10000 msat)
      )), 1 day)
      // no enough balance on first edge (taking fee into account)
      val g3 = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 1 msat, 2, minHtlc = 10000 msat, balance_opt = Some(15000 msat)),
        makeEdge(2L, b, c, 1 msat, 2, minHtlc = 10000 msat),
        makeEdge(3L, c, d, 1 msat, 2, minHtlc = 10000 msat)
      )), 1 day)
      Seq(g1, g2, g3).foreach(g => assert(findRoute(g, a, d, 15000 msat, 100 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true) == Failure(RouteNotFound)))
    }

    test("route to self") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 0 msat, 0),
        makeEdge(2L, b, c, 0 msat, 0),
        makeEdge(3L, c, d, 0 msat, 0)
      )), 1 day)

      val route = findRoute(g, a, a, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route == Failure(CannotRouteToSelf))
    }

    test("route to immediate neighbor") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 0 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT)),
        makeEdge(2L, b, c, 0 msat, 0),
        makeEdge(3L, c, d, 0 msat, 0),
        makeEdge(4L, d, e, 0 msat, 0)
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, b, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 1 :: Nil)
    }

    test("directed graph") {
      // a->e works, e->a fails
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 0 msat, 0),
        makeEdge(2L, b, c, 0 msat, 0),
        makeEdge(3L, c, d, 0 msat, 0),
        makeEdge(4L, d, e, 0 msat, 0)
      )), 1 day)

      val Success(route1 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route1) == 1 :: 2 :: 3 :: 4 :: Nil)

      val route2 = findRoute(g, e, a, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2 == Failure(RouteNotFound))
    }

    test("calculate route and return metadata") {
      val DUMMY_SIG = Transactions.PlaceHolderSig

      val uab = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(1L), 0 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags.DUMMY, CltvExpiryDelta(1), 42 msat, 2500 msat, 140, DEFAULT_CAPACITY.toMilliSatoshi)
      val uba = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(1L), 1 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags(isNode1 = false, isEnabled = false), CltvExpiryDelta(1), 43 msat, 2501 msat, 141, DEFAULT_CAPACITY.toMilliSatoshi)
      val ubc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(2L), 1 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags.DUMMY, CltvExpiryDelta(1), 44 msat, 2502 msat, 142, DEFAULT_CAPACITY.toMilliSatoshi)
      val ucb = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(2L), 1 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags(isNode1 = false, isEnabled = false), CltvExpiryDelta(1), 45 msat, 2503 msat, 143, DEFAULT_CAPACITY.toMilliSatoshi)
      val ucd = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(3L), 1 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags.DUMMY, CltvExpiryDelta(1), 46 msat, 2504 msat, 144, 500_000_000 msat)
      val udc = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(3L), 1 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags(isNode1 = false, isEnabled = false), CltvExpiryDelta(1), 47 msat, 2505 msat, 145, DEFAULT_CAPACITY.toMilliSatoshi)
      val ude = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(4L), 1 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags.DUMMY, CltvExpiryDelta(1), 48 msat, 2506 msat, 146, DEFAULT_CAPACITY.toMilliSatoshi)
      val ued = ChannelUpdate(DUMMY_SIG, Block.RegtestGenesisBlock.hash, ShortChannelId(4L), 1 unixsec, ChannelUpdate.MessageFlags(dontForward = false), ChannelUpdate.ChannelFlags(isNode1 = false, isEnabled = false), CltvExpiryDelta(1), 49 msat, 2507 msat, 147, DEFAULT_CAPACITY.toMilliSatoshi)

      val edges = Seq(
        GraphEdge(ChannelDesc(ShortChannelId(1L), a, b), HopRelayParams.FromAnnouncement(uab), DEFAULT_CAPACITY, None),
        GraphEdge(ChannelDesc(ShortChannelId(1L), b, a), HopRelayParams.FromAnnouncement(uba), DEFAULT_CAPACITY, None),
        GraphEdge(ChannelDesc(ShortChannelId(2L), b, c), HopRelayParams.FromAnnouncement(ubc), DEFAULT_CAPACITY, None),
        GraphEdge(ChannelDesc(ShortChannelId(2L), c, b), HopRelayParams.FromAnnouncement(ucb), DEFAULT_CAPACITY, None),
        GraphEdge(ChannelDesc(ShortChannelId(3L), c, d), HopRelayParams.FromAnnouncement(ucd), DEFAULT_CAPACITY, None),
        GraphEdge(ChannelDesc(ShortChannelId(3L), d, c), HopRelayParams.FromAnnouncement(udc), DEFAULT_CAPACITY, None),
        GraphEdge(ChannelDesc(ShortChannelId(4L), d, e), HopRelayParams.FromAnnouncement(ude), DEFAULT_CAPACITY, None),
        GraphEdge(ChannelDesc(ShortChannelId(4L), e, d), HopRelayParams.FromAnnouncement(ued), DEFAULT_CAPACITY, None)
      )

      val g = GraphWithBalanceEstimates(DirectedGraph(edges), 1 day)
      val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route.hops == channelHopFromUpdate(a, b, uab) :: channelHopFromUpdate(b, c, ubc) :: channelHopFromUpdate(c, d, ucd) :: channelHopFromUpdate(d, e, ude) :: Nil)
    }

    test("blacklist routes") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 0 msat, 0),
        makeEdge(2L, b, c, 0 msat, 0),
        makeEdge(3L, c, d, 0 msat, 0),
        makeEdge(4L, d, e, 0 msat, 0)
      )), 1 day)

      val route1 = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, ignoredEdges = Set(ChannelDesc(ShortChannelId(3L), c, d)), routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route1 == Failure(RouteNotFound))

      // verify that we left the graph untouched
      assert(g.graph.containsEdge(ChannelDesc(ShortChannelId(3), c, d)))
      assert(g.graph.containsVertex(c))
      assert(g.graph.containsVertex(d))

      // make sure we can find a route if without the blacklist
      val Success(route2 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route2) == 1 :: 2 :: 3 :: 4 :: Nil)
    }

    test("route to a destination that is not in the graph (with assisted routes)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 10 msat, 10),
        makeEdge(2L, b, c, 10 msat, 10),
        makeEdge(3L, c, d, 10 msat, 10)
      )), 1 day)

      val route = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route == Failure(RouteNotFound))

      // now we add the missing edge to reach the destination
      val extraGraphEdges = Set(makeEdge(4L, d, e, 5 msat, 5))
      val Success(route1 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route1) == 1 :: 2 :: 3 :: 4 :: Nil)
    }

    test("route from a source that is not in the graph (with assisted routes)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(2L, b, c, 10 msat, 10),
        makeEdge(3L, c, d, 10 msat, 10)
      )), 1 day)

      val route = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route == Failure(RouteNotFound))

      // now we add the missing starting edge
      val extraGraphEdges = Set(makeEdge(1L, a, b, 5 msat, 5))
      val Success(route1 :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route1) == 1 :: 2 :: 3 :: Nil)
    }

    test("verify that extra hops takes precedence over known channels") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 10 msat, 10),
        makeEdge(2L, b, c, 10 msat, 10),
        makeEdge(3L, c, d, 10 msat, 10),
        makeEdge(4L, d, e, 10 msat, 10)
      )), 1 day)

      val Success(route1 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route1) == 1 :: 2 :: 3 :: 4 :: Nil)
      assert(route1.hops(1).params.relayFees.feeBase == 10.msat)

      val extraGraphEdges = Set(makeEdge(2L, b, c, 5 msat, 5))
      val Success(route2 :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, extraEdges = extraGraphEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route2) == 1 :: 2 :: 3 :: 4 :: Nil)
      assert(route2.hops(1).params.relayFees.feeBase == 5.msat)
    }

    test("compute ignored channels") {
      val f = randomKey().publicKey
      val g = randomKey().publicKey
      val h = randomKey().publicKey
      val i = randomKey().publicKey
      val j = randomKey().publicKey

      val channels = Map(
        ShortChannelId(1L) -> makeChannel(1L, a, b),
        ShortChannelId(2L) -> makeChannel(2L, b, c),
        ShortChannelId(3L) -> makeChannel(3L, c, d),
        ShortChannelId(4L) -> makeChannel(4L, d, e),
        ShortChannelId(5L) -> makeChannel(5L, f, g),
        ShortChannelId(6L) -> makeChannel(6L, f, h),
        ShortChannelId(7L) -> makeChannel(7L, h, i),
        ShortChannelId(8L) -> makeChannel(8L, i, j)
      )

      val edges = List(
        makeEdge(1L, a, b, 10 msat, 10),
        makeEdge(2L, b, c, 10 msat, 10),
        makeEdge(2L, c, b, 10 msat, 10),
        makeEdge(3L, c, d, 10 msat, 10),
        makeEdge(4L, d, e, 10 msat, 10),
        makeEdge(5L, f, g, 10 msat, 10),
        makeEdge(6L, f, h, 10 msat, 10),
        makeEdge(7L, h, i, 10 msat, 10),
        makeEdge(8L, i, j, 10 msat, 10)
      )

      val publicChannels = channels.map { case (shortChannelId, announcement) =>
        val HopRelayParams.FromAnnouncement(update, _) = edges.find(_.desc.shortChannelId == shortChannelId).get.params
        val (update_1_opt, update_2_opt) = if (update.channelFlags.isNode1) (Some(update), None) else (None, Some(update))
        val pc = PublicChannel(announcement, TxId(ByteVector32.Zeroes), Satoshi(1000), update_1_opt, update_2_opt, None)
        (shortChannelId, pc)
      }

      val ignored = getIgnoredChannelDesc(publicChannels, ignoreNodes = Set(c, j, randomKey().publicKey))
      assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(2L), b, c)))
      assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(2L), c, b)))
      assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(3L), c, d)))
      assert(ignored.toSet.contains(ChannelDesc(ShortChannelId(8L), i, j)))
    }

    test("limit routes to 20 hops") {
      val nodes = (for (_ <- 0 until 22) yield randomKey().publicKey).toList
      val edges = nodes
        .zip(nodes.drop(1)) // (0, 1) :: (1, 2) :: ...
        .zipWithIndex // ((0, 1), 0) :: ((1, 2), 1) :: ...
        .map { case ((na, nb), index) => makeEdge(index, na, nb, 5 msat, 0) }

      val g = GraphWithBalanceEstimates(DirectedGraph(edges), 1 day)

      assert(findRoute(g, nodes(0), nodes(18), DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true).map(r => route2Ids(r.head)) == Success(0 until 18))
      assert(findRoute(g, nodes(0), nodes(19), DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true).map(r => route2Ids(r.head)) == Success(0 until 19))
      assert(findRoute(g, nodes(0), nodes(20), DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true).map(r => route2Ids(r.head)) == Success(0 until 20))
      assert(findRoute(g, nodes(0), nodes(21), DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true) == Failure(RouteNotFound))
    }

    test("ignore cheaper route when it has more than 20 hops") {
      val nodes = (for (_ <- 0 until 50) yield randomKey().publicKey).toList

      val edges = nodes
        .zip(nodes.drop(1)) // (0, 1) :: (1, 2) :: ...
        .zipWithIndex // ((0, 1), 0) :: ((1, 2), 1) :: ...
        .map { case ((na, nb), index) => makeEdge(index, na, nb, 1 msat, 0) }

      val expensiveShortEdge = makeEdge(99, nodes(2), nodes(48), 1000 msat, 0) // expensive shorter route

      val g = GraphWithBalanceEstimates(DirectedGraph(expensiveShortEdge :: edges), 1 day)

      val Success(route :: Nil) = findRoute(g, nodes(0), nodes(49), DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 0 :: 1 :: 99 :: 48 :: Nil)
    }

    test("ignore cheaper route when it has more than the requested CLTV") {
      val f = randomKey().publicKey
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1, a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(50)),
        makeEdge(2, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(50)),
        makeEdge(3, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(50)),
        makeEdge(4, a, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
        makeEdge(5, e, f, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
        makeEdge(6, f, d, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9))
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.modify(_.boundaries.maxCltv).setTo(CltvExpiryDelta(28)), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 4 :: 5 :: 6 :: Nil)
    }

    test("ignore cheaper route when it grows longer than the requested size") {
      val f = randomKey().publicKey
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1, a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
        makeEdge(2, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
        makeEdge(3, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
        makeEdge(4, d, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
        makeEdge(5, e, f, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9)),
        makeEdge(6, b, f, feeBase = 5 msat, 0, minHtlc = 0 msat, maxHtlc = None, CltvExpiryDelta(9))
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, f, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.modify(_.boundaries.maxRouteLength).setTo(3), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 1 :: 6 :: Nil)
    }

    test("ignore loops") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 10 msat, 10),
        makeEdge(2L, b, c, 10 msat, 10),
        makeEdge(3L, c, a, 10 msat, 10),
        makeEdge(4L, c, d, 10 msat, 10),
        makeEdge(5L, d, e, 10 msat, 10)
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 1 :: 2 :: 4 :: 5 :: Nil)
    }

    test("ensure the route calculation terminates correctly when selecting 0-fees edges") {
      // the graph contains a possible 0-cost path that goes back on its steps ( e -> f, f -> e )
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 10 msat, 10), // a -> b
        makeEdge(2L, b, c, 10 msat, 10),
        makeEdge(4L, c, d, 10 msat, 10),
        makeEdge(3L, b, e, 0 msat, 0), // b -> e
        makeEdge(6L, e, f, 0 msat, 0), // e -> f
        makeEdge(6L, f, e, 0 msat, 0), // e <- f
        makeEdge(5L, e, d, 0 msat, 0) // e -> d
      )), 1 day)

      val Success(route :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route) == 1 :: 3 :: 5 :: Nil)
    }

    // +---+                       +---+    +---+
    // | A |-----+            +--->| B |--->| C |
    // +---+     |            |    +---+    +---+
    //   ^       |    +---+   |               |
    //   |       +--->| E |---+               |
    //   |       |    +---+   |               |
    // +---+     |            |    +---+      |
    // | D |-----+            +--->| F |<-----+
    // +---+                       +---+
    test("find the k-shortest paths in a graph, k=4") {
      val (a, b, c, d, e, f) = (
        PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"),
        PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
        PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
        PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"),
        PublicKey(hex"02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"),
        PublicKey(hex"03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc")
      )

      val g1 = GraphWithBalanceEstimates(DirectedGraph(Seq(
        makeEdge(1L, d, a, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 4.msat)),
        makeEdge(2L, d, e, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 3.msat)),
        makeEdge(3L, a, e, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 3.msat)),
        makeEdge(4L, e, b, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 2.msat)),
        makeEdge(5L, e, f, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT)),
        makeEdge(6L, b, c, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 1.msat)),
        makeEdge(7L, c, f, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT))
      )), 1 day)

      val fourShortestPaths = Graph.yenKshortestPaths(g1, d, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 4, NO_WEIGHT_RATIOS, BlockHeight(0), noopBoundaries, includeLocalChannelCost = false, blip18InboundFees = true)
      assert(fourShortestPaths.size == 4)
      assert(hops2Ids(fourShortestPaths(0).path.map(graphEdgeToHop)) == 2 :: 5 :: Nil) // D -> E -> F
      assert(hops2Ids(fourShortestPaths(1).path.map(graphEdgeToHop)) == 1 :: 3 :: 5 :: Nil) // D -> A -> E -> F
      assert(hops2Ids(fourShortestPaths(2).path.map(graphEdgeToHop)) == 2 :: 4 :: 6 :: 7 :: Nil) // D -> E -> B -> C -> F
      assert(hops2Ids(fourShortestPaths(3).path.map(graphEdgeToHop)) == 1 :: 3 :: 4 :: 6 :: 7 :: Nil) // D -> A -> E -> B -> C -> F

      // Update balance D -> A to evict the last path (balance too low)
      val g2 = g1.addEdge(makeEdge(1L, d, a, 1 msat, 0, balance_opt = Some(DEFAULT_AMOUNT_MSAT + 3.msat)))
      val threeShortestPaths = Graph.yenKshortestPaths(g2, d, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 4, NO_WEIGHT_RATIOS, BlockHeight(0), noopBoundaries, includeLocalChannelCost = false, blip18InboundFees = true)
      assert(threeShortestPaths.size == 3)
      assert(hops2Ids(threeShortestPaths(0).path.map(graphEdgeToHop)) == 2 :: 5 :: Nil) // D -> E -> F
      assert(hops2Ids(threeShortestPaths(1).path.map(graphEdgeToHop)) == 1 :: 3 :: 5 :: Nil) // D -> A -> E -> F
      assert(hops2Ids(threeShortestPaths(2).path.map(graphEdgeToHop)) == 2 :: 4 :: 6 :: 7 :: Nil) // D -> E -> B -> C -> F
    }

    test("find the k shortest path (wikipedia example)") {
      val (c, d, e, f, g, h) = (
        PublicKey(hex"02999fa724ec3c244e4da52b4a91ad421dc96c9a810587849cd4b2469313519c73"),
        PublicKey(hex"03f1cb1af20fe9ccda3ea128e27d7c39ee27375c8480f11a87c17197e97541ca6a"),
        PublicKey(hex"0358e32d245ff5f5a3eb14c78c6f69c67cea7846bdf9aeeb7199e8f6fbb0306484"),
        PublicKey(hex"029e059b6780f155f38e83601969919aae631ddf6faed58fe860c72225eb327d7c"),
        PublicKey(hex"02f38f4e37142cc05df44683a83e22dea608cf4691492829ff4cf99888c5ec2d3a"),
        PublicKey(hex"03fc5b91ce2d857f146fd9b986363374ffe04dc143d8bcd6d7664c8873c463cdfc")
      )

      val graph = GraphWithBalanceEstimates(DirectedGraph(Seq(
        makeEdge(10L, c, e, 2 msat, 0),
        makeEdge(20L, c, d, 3 msat, 0),
        makeEdge(30L, d, f, 4 msat, 5), // D- > F has a higher cost to distinguish it from the 2nd cheapest route
        makeEdge(40L, e, d, 1 msat, 0),
        makeEdge(50L, e, f, 2 msat, 0),
        makeEdge(60L, e, g, 3 msat, 0),
        makeEdge(70L, f, g, 2 msat, 0),
        makeEdge(80L, f, h, 1 msat, 0),
        makeEdge(90L, g, h, 2 msat, 0)
      )), 1 day)

      val twoShortestPaths = Graph.yenKshortestPaths(graph, c, h, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 2, NO_WEIGHT_RATIOS, BlockHeight(0), noopBoundaries, includeLocalChannelCost = false, blip18InboundFees = true)

      assert(twoShortestPaths.size == 2)
      val shortest = twoShortestPaths(0)
      assert(hops2Ids(shortest.path.map(graphEdgeToHop)) == 10 :: 50 :: 80 :: Nil) // C -> E -> F -> H

      val secondShortest = twoShortestPaths(1)
      assert(hops2Ids(secondShortest.path.map(graphEdgeToHop)) == 10 :: 60 :: 90 :: Nil) // C -> E -> G -> H
    }

    test("terminate looking for k-shortest path if there are no more alternative paths than k, must not consider routes going back on their steps") {
      val f = randomKey().publicKey

      // simple graph with only 2 possible paths from A to F
      val graph = GraphWithBalanceEstimates(DirectedGraph(Seq(
        makeEdge(1L, a, b, 1 msat, 0),
        makeEdge(1L, b, a, 1 msat, 0),
        makeEdge(2L, b, c, 1 msat, 0),
        makeEdge(2L, c, b, 1 msat, 0),
        makeEdge(3L, c, f, 1 msat, 0),
        makeEdge(3L, f, c, 1 msat, 0),
        makeEdge(4L, c, d, 1 msat, 0),
        makeEdge(4L, d, c, 1 msat, 0),
        makeEdge(41L, d, c, 1 msat, 0), // there is more than one D -> C channel
        makeEdge(5L, d, e, 1 msat, 0),
        makeEdge(5L, e, d, 1 msat, 0),
        makeEdge(6L, e, f, 1 msat, 0),
        makeEdge(6L, f, e, 1 msat, 0)
      )), 1 day)

      // we ask for 3 shortest paths but only 2 can be found
      val foundPaths = Graph.yenKshortestPaths(graph, a, f, DEFAULT_AMOUNT_MSAT, Set.empty, Set.empty, Set.empty, pathsToFind = 3, NO_WEIGHT_RATIOS, BlockHeight(0), noopBoundaries, includeLocalChannelCost = false, blip18InboundFees = true)
      assert(foundPaths.size == 2)
      assert(hops2Ids(foundPaths(0).path.map(graphEdgeToHop)) == 1 :: 2 :: 3 :: Nil) // A -> B -> C -> F
      assert(hops2Ids(foundPaths(1).path.map(graphEdgeToHop)) == 1 :: 2 :: 4 :: 5 :: 6 :: Nil) // A -> B -> C -> D -> E -> F
    }

    test("select a random route below the requested fee") {
      val strictFeeParams = DEFAULT_ROUTE_PARAMS
        .modify(_.boundaries.maxFeeFlat).setTo(7 msat)
        .modify(_.boundaries.maxFeeProportional).setTo(0)
        .modify(_.randomize).setTo(true)
        .modify(_.mpp.splittingStrategy).setTo(Randomize)
      val strictFee = strictFeeParams.getMaxFee(DEFAULT_AMOUNT_MSAT)
      assert(strictFee == 7.msat)

      // A -> B -> C -> D has total cost of 10000005
      // A -> E -> C -> D has total cost of 10000103 !!
      // A -> E -> F -> D has total cost of 10000006
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, feeBase = 1 msat, 0),
        makeEdge(2L, b, c, feeBase = 2 msat, 0),
        makeEdge(3L, c, d, feeBase = 3 msat, 0),
        makeEdge(4L, a, e, feeBase = 1 msat, 0),
        makeEdge(5L, e, f, feeBase = 3 msat, 0),
        makeEdge(6L, f, d, feeBase = 3 msat, 0),
        makeEdge(7L, e, c, feeBase = 100 msat, 0)
      )), 1 day)

      for (_ <- 0 to 10) {
        val Success(routes) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, strictFee, numRoutes = 3, routeParams = strictFeeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.length == 2, routes)
        val weightedPath = Graph.pathWeight(g.balances, a, route2Edges(routes.head), DEFAULT_AMOUNT_MSAT, BlockHeight(400000), NO_WEIGHT_RATIOS, includeLocalChannelCost = false, g.graph, enableInboundFees = false)
        val totalFees = weightedPath.amount - DEFAULT_AMOUNT_MSAT
        // over the three routes we could only get the 2 cheapest because the third is too expensive (over 7 msat of fees)
        assert(totalFees == 5.msat || totalFees == 6.msat)
        assert(weightedPath.length == 3)
      }
    }

    test("use weight ratios when computing the edge weight") {
      val defaultCapacity = 15000 sat
      val largeCapacity = 8000000 sat

      // A -> B -> C -> D is 'fee optimized', lower fees route (totFees = 2, totCltv = 4000)
      // A -> E -> F -> D is 'timeout optimized', lower CLTV route (totFees = 3, totCltv = 18)
      // A -> E -> C -> D is 'capacity optimized', more recent channel/larger capacity route
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, feeBase = 0 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(13)),
        makeEdge(4L, a, e, feeBase = 0 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(12)),
        makeEdge(2L, b, c, feeBase = 1 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(500)),
        makeEdge(3L, c, d, feeBase = 1 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(500)),
        makeEdge(5L, e, f, feeBase = 2 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(9)),
        makeEdge(6L, f, d, feeBase = 2 msat, 1000, minHtlc = 0 msat, capacity = defaultCapacity, cltvDelta = CltvExpiryDelta(9)),
        makeEdge(7L, e, c, feeBase = 2 msat, 1000, minHtlc = 0 msat, capacity = largeCapacity, cltvDelta = CltvExpiryDelta(12))
      )), 1 day)

      val Success(routeFeeOptimized :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Nodes(routeFeeOptimized) == (a, b) :: (b, c) :: (c, d) :: Nil)

      val Success(routeCltvOptimized :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = HeuristicsConstants(
        lockedFundsRisk = 1,
        failureFees = RelayFees(0 msat, 0),
        hopFees = RelayFees(0 msat, 0),
        useLogProbability = false,
        usePastRelaysData = false,
      )), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Nodes(routeCltvOptimized) == (a, e) :: (e, f) :: (f, d) :: Nil)

      val Success(routeCapacityOptimized :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = HeuristicsConstants(
        lockedFundsRisk = 0,
        failureFees = RelayFees(1000 msat, 1000),
        hopFees = RelayFees(0 msat, 0),
        useLogProbability = false,
        usePastRelaysData = false,
      )), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Nodes(routeCapacityOptimized) == (a, e) :: (e, c) :: (c, d) :: Nil)
    }

    test("prefer a route with a smaller total CLTV if fees and score are the same") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1, a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
        makeEdge(4, a, e, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
        makeEdge(2, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(10)), // smaller CLTV
        makeEdge(3, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
        makeEdge(5, e, f, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12)),
        makeEdge(6, f, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(12))
      )), 1 day)

      val Success(routeScoreOptimized :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = HeuristicsConstants(
        lockedFundsRisk = 1e-7,
        failureFees = RelayFees(100 msat, 100),
        hopFees = RelayFees(0 msat, 0),
        useLogProbability = false,
        usePastRelaysData = false,
      )), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)

      assert(route2Nodes(routeScoreOptimized) == (a, b) :: (b, c) :: (c, d) :: Nil)
    }

    test("avoid a route that breaks off the max CLTV") {
      // A -> B -> C -> D is cheaper but has a total CLTV > 2016!
      // A -> E -> F -> D is more expensive but has a total CLTV < 2016
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1, a, b, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
        makeEdge(4, a, e, feeBase = 100 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
        makeEdge(2, b, c, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(1000)),
        makeEdge(3, c, d, feeBase = 1 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(900)),
        makeEdge(5, e, f, feeBase = 100 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144)),
        makeEdge(6, f, d, feeBase = 100 msat, 0, minHtlc = 0 msat, maxHtlc = None, cltvDelta = CltvExpiryDelta(144))
      )), 1 day)

      val Success(routeScoreOptimized :: Nil) = findRoute(g, a, d, DEFAULT_AMOUNT_MSAT / 2, DEFAULT_MAX_FEE, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = HeuristicsConstants(
        lockedFundsRisk = 0,
        failureFees = RelayFees(100 msat, 100),
        hopFees = RelayFees(500 msat, 200),
        useLogProbability = false,
        usePastRelaysData = false,
      )), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)

      assert(route2Nodes(routeScoreOptimized) == (a, e) :: (e, f) :: (f, d) :: Nil)
    }

    test("validate path fees") {
      val ab = makeEdge(1L, a, b, feeBase = 100 msat, 10000, minHtlc = 150 msat, maxHtlc = Some(300 msat), capacity = 1 sat, balance_opt = Some(260 msat))
      val bc = makeEdge(10L, b, c, feeBase = 5 msat, 10000, minHtlc = 100 msat, maxHtlc = Some(400 msat), capacity = 1 sat)
      val cd = makeEdge(20L, c, d, feeBase = 5 msat, 10000, minHtlc = 50 msat, maxHtlc = Some(500 msat), capacity = 1 sat)

      assert(Graph.validatePath(Nil, 200 msat)) // ok
      assert(Graph.validatePath(Seq(ab), 260 msat)) // ok
      assert(!Graph.validatePath(Seq(ab), 10000 msat)) // above max-htlc
      assert(Graph.validatePath(Seq(ab, bc), 250 msat)) // ok
      assert(!Graph.validatePath(Seq(ab, bc), 255 msat)) // above balance (AB)
      assert(Graph.validatePath(Seq(ab, bc, cd), 200 msat)) // ok
      assert(!Graph.validatePath(Seq(ab, bc, cd), 25 msat)) // below min-htlc (CD)
      assert(!Graph.validatePath(Seq(ab, bc, cd), 60 msat)) // below min-htlc (BC)
      assert(!Graph.validatePath(Seq(ab, bc, cd), 110 msat)) // below min-htlc (AB)
      assert(!Graph.validatePath(Seq(ab, bc, cd), 550 msat)) // above max-htlc (CD)
      assert(!Graph.validatePath(Seq(ab, bc, cd), 450 msat)) // above max-htlc (BC)
      assert(!Graph.validatePath(Seq(ab, bc, cd), 350 msat)) // above max-htlc (AB)
      assert(!Graph.validatePath(Seq(ab, bc, cd), 250 msat)) // above balance (AB)
    }

    test("calculate multipart route to neighbor (many channels, known balance)") {
      val amount = 60000 msat
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat)),
        makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, balance_opt = Some(21000 msat)),
        makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, balance_opt = Some(17000 msat)),
        makeEdge(4L, a, b, 100 msat, 20, minHtlc = 1 msat, balance_opt = Some(16000 msat)),
      )), 1 day)
      // We set max-parts to 3, but it should be ignored when sending to a direct neighbor.
      val routeParams = DEFAULT_ROUTE_PARAMS.copy(mpp = MultiPartParams(2500 msat, 3, DEFAULT_ROUTE_PARAMS.mpp.splittingStrategy))

      {
        val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.length == 4, routes)
        assert(routes.forall(_.hops.length == 1), routes)
        checkRouteAmounts(routes, amount, 0 msat)
      }
      {
        val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = routeParams.copy(randomize = true).modify(_.mpp.splittingStrategy).setTo(Randomize), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.length >= 4, routes)
        assert(routes.forall(_.hops.length == 1), routes)
        checkRouteAmounts(routes, amount, 0 msat)
      }
      {
        val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = routeParams.modify(_.mpp.splittingStrategy).setTo(MaxExpectedAmount), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.length >= 4, routes)
        assert(routes.forall(_.hops.length == 1), routes)
        checkRouteAmounts(routes, amount, 0 msat)
      }
      {
        // We set min-part-amount to a value that excludes channels 1 and 4.
        val failure = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = routeParams.copy(mpp = MultiPartParams(16500 msat, 3, routeParams.mpp.splittingStrategy)), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(failure == Failure(RouteNotFound))
      }
    }

    test("calculate multipart route to neighbor (single channel, known balance)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(25000 msat)),
        makeEdge(2L, a, c, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(50000 msat)),
        makeEdge(3L, c, b, 1 msat, 0, minHtlc = 1 msat),
        makeEdge(4L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
      )), 1 day)

      val amount = 25000 msat
      val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.length == 1, routes)
      checkRouteAmounts(routes, amount, 0 msat)
      assert(route2Ids(routes.head) == 1L :: Nil)
    }

    test("calculate multipart route to neighbor (many channels, some balance unknown)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat)),
        makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, balance_opt = Some(25000 msat)),
        makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, balance_opt = None, capacity = 20 sat),
        makeEdge(4L, a, b, 100 msat, 20, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
        makeEdge(5L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
      )), 1 day)

      val amount = 65000 msat
      val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.length == 4, routes)
      assert(routes.forall(_.hops.length == 1), routes)
      checkRouteAmounts(routes, amount, 0 msat)
    }

    test("calculate multipart route to neighbor (many channels, some empty)") {
      val amount = 35000 msat
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat)),
        makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, balance_opt = Some(0 msat)),
        makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, balance_opt = None, capacity = 15 sat),
        makeEdge(4L, a, b, 1 msat, 0, minHtlc = 0 msat, balance_opt = Some(0 msat)),
        makeEdge(5L, a, b, 100 msat, 20, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
        makeEdge(6L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
        makeEdge(7L, a, d, 0 msat, 0, minHtlc = 0 msat, balance_opt = Some(0 msat)),
      )), 1 day)

      {
        val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.length == 3, routes)
        assert(routes.forall(_.hops.length == 1), routes)
        checkIgnoredChannels(routes, 2L)
        checkRouteAmounts(routes, amount, 0 msat)
      }
      {
        val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true).modify(_.mpp.splittingStrategy).setTo(Randomize), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.length >= 3, routes)
        assert(routes.forall(_.hops.length == 1), routes)
        checkIgnoredChannels(routes, 2L)
        checkRouteAmounts(routes, amount, 0 msat)
      }
      {
        val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS.modify(_.mpp.splittingStrategy).setTo(MaxExpectedAmount), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.length >= 3, routes)
        assert(routes.forall(_.hops.length == 1), routes)
        checkIgnoredChannels(routes, 2L)
        checkRouteAmounts(routes, amount, 0 msat)
      }
    }

    test("calculate multipart route to neighbor (ignored channels)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat)),
        makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, balance_opt = Some(25000 msat)),
        makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, balance_opt = None, capacity = 50 sat),
        makeEdge(4L, a, b, 100 msat, 20, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
        makeEdge(5L, a, b, 1 msat, 10, minHtlc = 1 msat, balance_opt = None, capacity = 10 sat),
        makeEdge(6L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
      )), 1 day)

      val amount = 20000 msat
      val ignoredEdges = Set(ChannelDesc(ShortChannelId(2L), a, b), ChannelDesc(ShortChannelId(3L), a, b))
      val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, ignoredEdges = ignoredEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.forall(_.hops.length == 1), routes)
      checkIgnoredChannels(routes, 2L, 3L)
      checkRouteAmounts(routes, amount, 0 msat)
    }

    test("calculate multipart route to neighbor (pending htlcs ignored for local channels)") {
      val edge_ab_1 = makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat))
      val edge_ab_2 = makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, balance_opt = Some(25000 msat))
      val edge_ab_3 = makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, balance_opt = None, capacity = 15 sat)
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        edge_ab_1,
        edge_ab_2,
        edge_ab_3,
        makeEdge(4L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
      )), 1 day)

      val amount = 50000 msat
      // These pending HTLCs will have already been taken into account in the edge's `balance_opt` field: findMultiPartRoute
      // should ignore this information.
      val pendingHtlcs = Seq(Route(10000 msat, graphEdgeToHop(edge_ab_1) :: Nil, None), Route(5000 msat, graphEdgeToHop(edge_ab_2) :: Nil, None))
      val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, pendingHtlcs = pendingHtlcs, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.forall(_.hops.length == 1), routes)
      checkRouteAmounts(routes, amount, 0 msat)
    }

    test("calculate multipart route to neighbor (restricted htlc_maximum_msat)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 25 msat, 15, minHtlc = 1 msat, maxHtlc = Some(5000 msat), balance_opt = Some(18000 msat)),
        makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1 msat, maxHtlc = Some(5000 msat), balance_opt = Some(23000 msat)),
        makeEdge(3L, a, b, 1 msat, 50, minHtlc = 1 msat, maxHtlc = Some(5000 msat), balance_opt = Some(21000 msat)),
        makeEdge(4L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
      )), 1 day)

      val amount = 50000 msat
      val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.forall(_.hops.length == 1), routes)
      assert(routes.length >= 10, routes)
      assert(routes.forall(_.amount <= 5000.msat), routes)
      checkRouteAmounts(routes, amount, 0 msat)
    }

    test("calculate multipart route to neighbor (restricted htlc_minimum_msat)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 25 msat, 15, minHtlc = 2500 msat, balance_opt = Some(18000 msat)),
        makeEdge(2L, a, b, 15 msat, 10, minHtlc = 2500 msat, balance_opt = Some(7000 msat)),
        makeEdge(3L, a, b, 1 msat, 50, minHtlc = 2500 msat, balance_opt = Some(10000 msat)),
        makeEdge(4L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
      )), 1 day)

      val amount = 30000 msat
      val routeParams = DEFAULT_ROUTE_PARAMS.copy(mpp = MultiPartParams(2500 msat, 5, DEFAULT_ROUTE_PARAMS.mpp.splittingStrategy))
      val Success(routes) = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.forall(_.hops.length == 1), routes)
      assert(routes.length == 3, routes)
      checkRouteAmounts(routes, amount, 0 msat)
    }

    test("calculate multipart route to neighbor (through remote channels)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 25 msat, 15, minHtlc = 1000 msat, balance_opt = Some(18000 msat)),
        makeEdge(2L, a, b, 15 msat, 10, minHtlc = 1000 msat, balance_opt = Some(7000 msat)),
        makeEdge(3L, a, c, 1000 msat, 10000, minHtlc = 1000 msat, balance_opt = Some(10000 msat)),
        makeEdge(4L, c, b, 10 msat, 1000, minHtlc = 1000 msat),
        makeEdge(5L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(25000 msat)),
      )), 1 day)

      val amount = 30000 msat
      val maxFeeTooLow = findMultiPartRoute(g, a, b, amount, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(maxFeeTooLow == Failure(RouteNotFound))

      val Success(routes) = findMultiPartRoute(g, a, b, amount, 20 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.forall(_.hops.length <= 2), routes)
      assert(routes.length == 3, routes)
      checkRouteAmounts(routes, amount, 20 msat)
    }

    test("cannot find multipart route to neighbor (not enough balance)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 0 msat, 0, minHtlc = 1 msat, balance_opt = Some(15000 msat)),
        makeEdge(2L, a, b, 0 msat, 0, minHtlc = 1 msat, balance_opt = Some(5000 msat)),
        makeEdge(3L, a, b, 0 msat, 0, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
        makeEdge(4L, a, d, 0 msat, 0, minHtlc = 1 msat, balance_opt = Some(45000 msat)),
      )), 1 day)

      {
        val result = findMultiPartRoute(g, a, b, 40000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(result == Failure(RouteNotFound))
      }
      {
        val result = findMultiPartRoute(g, a, b, 40000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true).modify(_.mpp.splittingStrategy).setTo(Randomize), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(result == Failure(RouteNotFound))
      }
      {
        val result = findMultiPartRoute(g, a, b, 40000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS.modify(_.mpp.splittingStrategy).setTo(MaxExpectedAmount), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(result == Failure(RouteNotFound))
      }
    }

    test("cannot find multipart route to neighbor (not enough capacity)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 0 msat, 0, minHtlc = 1 msat, capacity = 1500 sat),
        makeEdge(2L, a, b, 0 msat, 0, minHtlc = 1 msat, capacity = 2000 sat),
        makeEdge(3L, a, b, 0 msat, 0, minHtlc = 1 msat, capacity = 1200 sat),
        makeEdge(4L, a, d, 0 msat, 0, minHtlc = 1 msat, capacity = 4500 sat),
      )), 1 day)

      val result = findMultiPartRoute(g, a, b, 5000000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(result == Failure(RouteNotFound))
    }

    test("cannot find multipart route to neighbor (restricted htlc_minimum_msat)") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 25 msat, 15, minHtlc = 5000 msat, balance_opt = Some(6000 msat)),
        makeEdge(2L, a, b, 15 msat, 10, minHtlc = 5000 msat, balance_opt = Some(7000 msat)),
        makeEdge(3L, a, d, 0 msat, 0, minHtlc = 5000 msat, balance_opt = Some(9000 msat)),
      )), 1 day)

      {
        val result = findMultiPartRoute(g, a, b, 10000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(result == Failure(RouteNotFound))
      }
      {
        val result = findMultiPartRoute(g, a, b, 10000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true).modify(_.mpp.splittingStrategy).setTo(Randomize), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(result == Failure(RouteNotFound))
      }
      {
        val result = findMultiPartRoute(g, a, b, 10000 msat, 1 msat, routeParams = DEFAULT_ROUTE_PARAMS.modify(_.mpp.splittingStrategy).setTo(MaxExpectedAmount), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(result == Failure(RouteNotFound))
      }
    }

    test("calculate multipart route to remote node (many local channels)") {
      // +-------+
      // |       |
      // A ----- C ----- E
      // |               |
      // +--- B --- D ---+
      val (amount, maxFee) = (30000 msat, 150 msat)
      val edge_ab = makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(15000 msat))
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        edge_ab,
        makeEdge(2L, b, d, 15 msat, 0, minHtlc = 1 msat, capacity = 25 sat),
        makeEdge(3L, d, e, 15 msat, 0, minHtlc = 0 msat, capacity = 20 sat),
        makeEdge(4L, a, c, 1 msat, 50, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
        makeEdge(5L, a, c, 1 msat, 50, minHtlc = 1 msat, balance_opt = Some(8000 msat)),
        makeEdge(6L, c, e, 50 msat, 30, minHtlc = 1 msat, capacity = 20 sat),
      )), 1 day)

      {
        val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
        assert(routes2Ids(routes) == Set(Seq(1L, 2L, 3L), Seq(4L, 6L), Seq(5L, 6L)))
      }
      {
        // Update A - B with unknown balance, capacity should be used instead.
        val g1 = g.addEdge(makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, capacity = 15 sat, balance_opt = None))
        val Success(routes) = findMultiPartRoute(g1, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
        assert(routes2Ids(routes) == Set(Seq(1L, 2L, 3L), Seq(4L, 6L), Seq(5L, 6L)))
      }
      {
        // Randomize routes.
        val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true).modify(_.mpp.splittingStrategy).setTo(Randomize), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
      }
      {
        val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS.modify(_.mpp.splittingStrategy).setTo(MaxExpectedAmount), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
      }
      {
        // Update balance A - B to be too low.
        val g1 = g.addEdge(edge_ab.copy(balance_opt = Some(2000 msat)))
        val failure = findMultiPartRoute(g1, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(failure == Failure(RouteNotFound))
      }
      {
        // Update capacity A - B to be too low.
        val g1 = g.addEdge(makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, capacity = 5 sat, balance_opt = None))
        val failure = findMultiPartRoute(g1, a, e, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(failure == Failure(RouteNotFound))
      }
      {
        // Try to find a route with a maxFee that's too low.
        val maxFeeTooLow = 100 msat
        val failure = findMultiPartRoute(g, a, e, amount, maxFeeTooLow, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(failure == Failure(RouteNotFound))
      }
    }

    test("calculate multipart route to remote node (tiny amount)") {
      // A ----- C ----- E
      // |               |
      // +--- B --- D ---+
      // Our balance and the amount we want to send are below the minimum part amount.
      val routeParams = DEFAULT_ROUTE_PARAMS.copy(mpp = MultiPartParams(5000 msat, 5, DEFAULT_ROUTE_PARAMS.mpp.splittingStrategy))
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(1500 msat)),
        makeEdge(2L, b, d, 15 msat, 0, minHtlc = 1 msat, capacity = 25 sat),
        makeEdge(3L, d, e, 15 msat, 0, minHtlc = 1 msat, capacity = 20 sat),
        makeEdge(4L, a, c, 1 msat, 50, minHtlc = 1 msat, balance_opt = Some(1000 msat)),
        makeEdge(5L, c, e, 50 msat, 30, minHtlc = 1 msat, capacity = 20 sat),
      )), 1 day)

      {
        // We can send single-part tiny payments.
        val (amount, maxFee) = (1400 msat, 30 msat)
        val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
      }
      {
        // But we don't want to split such tiny amounts.
        val (amount, maxFee) = (2000 msat, 150 msat)
        val failure = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(failure == Failure(RouteNotFound))
      }
    }

    test("calculate multipart route to remote node (single path)") {
      val (amount, maxFee) = (100000 msat, 500 msat)
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(500000 msat)),
        makeEdge(2L, b, c, 10 msat, 30, minHtlc = 1 msat, capacity = 150 sat),
        makeEdge(3L, c, d, 15 msat, 50, minHtlc = 1 msat, capacity = 150 sat),
      )), 1 day)

      val Success(routes) = findMultiPartRoute(g, a, d, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      checkRouteAmounts(routes, amount, maxFee)
      assert(routes.length == 1, "payment shouldn't be split when we have one path with enough capacity")
      assert(routes2Ids(routes) == Set(Seq(1L, 2L, 3L)))
    }

    test("calculate multipart route to remote node (single local channel)") {
      //       +--- C ---+
      //       |         |
      // A --- B ------- D --- F
      //       |               |
      //       +----- E -------+
      val (amount, maxFee) = (400000 msat, 250 msat)
      val edge_ab = makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, balance_opt = Some(500000 msat))
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        edge_ab,
        makeEdge(2L, b, c, 10 msat, 30, minHtlc = 1 msat, capacity = 150 sat),
        makeEdge(3L, c, d, 15 msat, 50, minHtlc = 1 msat, capacity = 150 sat),
        makeEdge(4L, b, d, 20 msat, 75, minHtlc = 1 msat, capacity = 180 sat),
        makeEdge(5L, d, f, 5 msat, 50, minHtlc = 1 msat, capacity = 300 sat),
        makeEdge(6L, b, e, 15 msat, 80, minHtlc = 1 msat, capacity = 210 sat),
        makeEdge(7L, e, f, 15 msat, 100, minHtlc = 1 msat, capacity = 200 sat),
      )), 1 day)

      {
        val Success(routes) = findMultiPartRoute(g, a, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
        assert(routes2Ids(routes) == Set(Seq(1L, 2L, 3L, 5L), Seq(1L, 4L, 5L), Seq(1L, 6L, 7L)))
      }
      {
        // Randomize routes.
        val Success(routes) = findMultiPartRoute(g, a, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true).modify(_.mpp.splittingStrategy).setTo(Randomize), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
      }
      {
        val Success(routes) = findMultiPartRoute(g, a, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS.modify(_.mpp.splittingStrategy).setTo(MaxExpectedAmount), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
      }
      {
        // Update A - B with unknown balance, capacity should be used instead.
        val g1 = g.addEdge(makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, capacity = 500 sat, balance_opt = None))
        val Success(routes) = findMultiPartRoute(g1, a, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
        assert(routes2Ids(routes) == Set(Seq(1L, 2L, 3L, 5L), Seq(1L, 4L, 5L), Seq(1L, 6L, 7L)))
      }
      {
        // Update balance A - B to be too low to cover fees.
        val g1 = g.addEdge(edge_ab.copy(balance_opt = Some(400000 msat)))
        val failure = findMultiPartRoute(g1, a, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(failure == Failure(RouteNotFound))
      }
      {
        // Update capacity A - B to be too low to cover fees.
        val g1 = g.addEdge(makeEdge(1L, a, b, 50 msat, 100, minHtlc = 1 msat, capacity = 400 sat, balance_opt = None))
        val failure = findMultiPartRoute(g1, a, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(failure == Failure(RouteNotFound))
      }
      {
        // Try to find a route with a maxFee that's too low.
        val maxFeeTooLow = 100 msat
        val failure = findMultiPartRoute(g, a, f, amount, maxFeeTooLow, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(failure == Failure(RouteNotFound))
      }
    }

    test("calculate multipart route to remote node (ignore cheap routes with low capacity)") {
      //
      // +---> B1 -----+
      // |             |
      // +---> B2 -----+
      // |             |
      // +---> ... ----+
      // |             |
      // +---> B10 ----+
      // |             |
      // |             v
      // A ---> C ---> D
      val cheapEdges = (1 to 10).flatMap(i => {
        val bi = randomKey().publicKey
        List(
          makeEdge(2 * i, a, bi, 1 msat, 1, minHtlc = 1 msat, capacity = 1500 sat, balance_opt = Some(1_200_000 msat)),
          makeEdge(2 * i + 1, bi, d, 1 msat, 1, minHtlc = 1 msat, capacity = 1500 sat),
        )
      })
      val preferredEdges = List(
        makeEdge(100, a, c, 5 msat, 1000, minHtlc = 1 msat, capacity = 25000 sat, balance_opt = Some(20_000_000 msat)),
        makeEdge(101, c, d, 5 msat, 1000, minHtlc = 1 msat, capacity = 25000 sat),
      )
      val g = GraphWithBalanceEstimates(DirectedGraph(preferredEdges ++ cheapEdges), 1 day)

      {
        val amount = 15_000_000 msat
        val maxFee = 50_000 msat // this fee is enough to go through the preferred route
        val routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = false, mpp = MultiPartParams(50_000 msat, 5, FullCapacity))
        val Success(routes) = findMultiPartRoute(g, a, d, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
        assert(routes2Ids(routes) == Set(Seq(100L, 101L)))
      }
      {
        val amount = 15_000_000 msat
        val maxFee = 10_000 msat // this fee is too low to go through the preferred route
        val routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = false, mpp = MultiPartParams(50_000 msat, 5, FullCapacity))
        val failure = findMultiPartRoute(g, a, d, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(failure == Failure(RouteNotFound))
      }
      {
        val amount = 5_000_000 msat
        val maxFee = 10_000 msat // this fee is enough to go through the preferred route, but the cheaper ones can handle it
        val routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = false, mpp = MultiPartParams(50_000 msat, 5, FullCapacity))
        val Success(routes) = findMultiPartRoute(g, a, d, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.length == 5)
        routes.foreach(route => {
          assert(route.hops.length == 2)
          assert(route.amount <= 1_200_000.msat)
          assert(!route.hops.flatMap(h => Seq(h.nodeId, h.nextNodeId)).contains(c))
        })
      }
    }

    test("calculate multipart route to remote node (ignored channels and nodes)") {
      //  +----- B --xxx-- C -----+
      //  | +-------- D --------+ |
      //  | |                   | |
      // +---+    (empty x2)   +---+
      // | A | --------------- | F |
      // +---+                 +---+
      //  | |    (not empty)    | |
      //  | +-------------------+ |
      //  +---------- E ----------+
      val (amount, maxFee) = (25000 msat, 5 msat)
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(75000 msat)),
        makeEdge(2L, b, c, 1 msat, 0, minHtlc = 1 msat, capacity = 150 sat),
        makeEdge(3L, c, f, 1 msat, 0, minHtlc = 1 msat, capacity = 150 sat),
        makeEdge(4L, a, d, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(85000 msat)),
        makeEdge(5L, d, f, 1 msat, 0, minHtlc = 1 msat, capacity = 300 sat),
        makeEdge(6L, a, f, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(0 msat)),
        makeEdge(7L, a, f, 0 msat, 0, minHtlc = 0 msat, balance_opt = Some(0 msat)),
        makeEdge(8L, a, f, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(10000 msat)),
        makeEdge(9L, a, e, 1 msat, 0, minHtlc = 1 msat, balance_opt = Some(18000 msat)),
        makeEdge(10L, e, f, 1 msat, 0, minHtlc = 1 msat, capacity = 15 sat),
      )), 1 day)

      val ignoredNodes = Set(d)
      val ignoredChannels = Set(ChannelDesc(ShortChannelId(2L), b, c))
      val Success(routes) = findMultiPartRoute(g, a, f, amount, maxFee, ignoredEdges = ignoredChannels, ignoredVertices = ignoredNodes, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      checkRouteAmounts(routes, amount, maxFee)
      assert(routes2Ids(routes) == Set(Seq(8L), Seq(9L, 10L)))
    }

    test("calculate multipart route to remote node (complex graph)") {
      // +---+                       +---+    +---+
      // | A |-----+            +--->| B |--->| C |
      // +---+     |            |    +---+    +---+
      //   ^       |    +---+   |               |
      //   |       +--->| E |---+               |
      //   |       |    +---+   |               |
      // +---+     |            |    +---+      |
      // | D |-----+            +--->| F |<-----+
      // +---+                       +---+
      val g = GraphWithBalanceEstimates(DirectedGraph(Seq(
        makeEdge(1L, d, a, 100 msat, 1000, minHtlc = 1000 msat, balance_opt = Some(80000 msat)),
        makeEdge(2L, d, e, 100 msat, 1000, minHtlc = 1500 msat, balance_opt = Some(20000 msat)),
        makeEdge(3L, a, e, 5 msat, 50, minHtlc = 1200 msat, capacity = 100 sat),
        makeEdge(4L, e, f, 25 msat, 1000, minHtlc = 1300 msat, capacity = 25 sat),
        makeEdge(5L, e, b, 10 msat, 100, minHtlc = 1100 msat, capacity = 75 sat),
        makeEdge(6L, b, c, 5 msat, 50, minHtlc = 1000 msat, capacity = 20 sat),
        makeEdge(7L, c, f, 5 msat, 10, minHtlc = 1500 msat, capacity = 50 sat)
      )), 1 day)
      val routeParams = DEFAULT_ROUTE_PARAMS.copy(mpp = MultiPartParams(1500 msat, 10, DEFAULT_ROUTE_PARAMS.mpp.splittingStrategy))

      {
        val (amount, maxFee) = (15000 msat, 50 msat)
        val Success(routes) = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
      }
      {
        val (amount, maxFee) = (25000 msat, 100 msat)
        val Success(routes) = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
      }
      {
        val (amount, maxFee) = (25000 msat, 50 msat)
        val failure = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(failure == Failure(RouteNotFound))
      }
      {
        val (amount, maxFee) = (40000 msat, 100 msat)
        val Success(routes) = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
      }
      {
        val (amount, maxFee) = (40000 msat, 100 msat)
        val Success(routes) = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams.copy(randomize = true).modify(_.mpp.splittingStrategy).setTo(Randomize), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
      }
      {
        val (amount, maxFee) = (40000 msat, 100 msat)
        val Success(routes) = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams.modify(_.mpp.splittingStrategy).setTo(MaxExpectedAmount), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        checkRouteAmounts(routes, amount, maxFee)
      }
      {
        val (amount, maxFee) = (40000 msat, 50 msat)
        val failure = findMultiPartRoute(g, d, f, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(failure == Failure(RouteNotFound))
      }
    }

    test("calculate multipart route to remote node (with extra edges)") {
      // +--- B ---+
      // A         D (---) E (---) F
      // +--- C ---+
      val (amount, maxFeeE, maxFeeF) = (10000 msat, 50 msat, 100 msat)
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 1 msat, 0, minHtlc = 1 msat, maxHtlc = Some(4000 msat), balance_opt = Some(7000 msat)),
        makeEdge(2L, b, d, 1 msat, 0, minHtlc = 1 msat, capacity = 50 sat),
        makeEdge(3L, a, c, 1 msat, 0, minHtlc = 1 msat, maxHtlc = Some(4000 msat), balance_opt = Some(6000 msat)),
        makeEdge(4L, c, d, 1 msat, 0, minHtlc = 1 msat, capacity = 40 sat),
      )), 1 day)
      val extraEdges = Set(
        makeEdge(10L, d, e, 10 msat, 100, minHtlc = 500 msat, capacity = 15 sat),
        makeEdge(11L, e, f, 5 msat, 100, minHtlc = 500 msat, capacity = 10 sat),
      )

      val Success(routes1) = findMultiPartRoute(g, a, e, amount, maxFeeE, extraEdges = extraEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      checkRouteAmounts(routes1, amount, maxFeeE)
      assert(routes1.length >= 3, routes1)
      assert(routes1.forall(_.amount <= 4000.msat), routes1)

      val Success(routes2) = findMultiPartRoute(g, a, f, amount, maxFeeF, extraEdges = extraEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      checkRouteAmounts(routes2, amount, maxFeeF)
      assert(routes2.length >= 3, routes2)
      assert(routes2.forall(_.amount <= 4000.msat), routes2)

      val maxFeeTooLow = 40 msat
      val failure = findMultiPartRoute(g, a, f, amount, maxFeeTooLow, extraEdges = extraEdges, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(failure == Failure(RouteNotFound))
    }

    test("calculate multipart route to remote node (pending htlcs)") {
      // +----- B -----+
      // |             |
      // A----- C ---- E
      // |             |
      // +----- D -----+
      val (amount, maxFee) = (15000 msat, 100 msat)
      val edge_ab = makeEdge(1L, a, b, 1 msat, 0, minHtlc = 100 msat, balance_opt = Some(5000 msat))
      val edge_be = makeEdge(2L, b, e, 1 msat, 0, minHtlc = 100 msat, capacity = 5 sat)
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        // The A -> B -> E route is the most economic one, but we already have a pending HTLC in it.
        edge_ab,
        edge_be,
        makeEdge(3L, a, c, 50 msat, 0, minHtlc = 100 msat, balance_opt = Some(10000 msat)),
        makeEdge(4L, c, e, 50 msat, 0, minHtlc = 100 msat, capacity = 25 sat),
        makeEdge(5L, a, d, 50 msat, 0, minHtlc = 100 msat, balance_opt = Some(10000 msat)),
        makeEdge(6L, d, e, 50 msat, 0, minHtlc = 100 msat, capacity = 25 sat),
      )), 1 day)

      val pendingHtlcs = Seq(Route(5000 msat, graphEdgeToHop(edge_ab) :: graphEdgeToHop(edge_be) :: Nil, None))
      val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, pendingHtlcs = pendingHtlcs, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.forall(_.hops.length == 2), routes)
      checkRouteAmounts(routes, amount, maxFee)
      checkIgnoredChannels(routes, 1L, 2L)
    }

    test("calculate multipart route for full amount or fail", Tag("fuzzy")) {
      //   +------------------------------------+
      //   |                                    |
      //   |                                    v
      // +---+                       +---+    +---+
      // | A |-----+      +--------->| B |--->| C |
      // +---+     |      |          +---+    +---+
      //   ^       |    +---+                   |
      //   |       +--->| E |----------+        |
      //   |            +---+          |        |
      //   |              ^            v        |
      // +---+            |          +---+      |
      // | D |------------+          | F |<-----+
      // +---+                       +---+
      //   |                           ^
      //   |                           |
      //   +---------------------------+
      for (_ <- 1 to 100) {
        val amount = (100 + Random.nextLong(200000)).msat
        val maxFee = 50.msat.max(amount * 0.03)
        val g = GraphWithBalanceEstimates(DirectedGraph(List(
          makeEdge(1L, d, f, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat, balance_opt = Some(Random.nextLong(2 * amount.toLong).msat)),
          makeEdge(2L, d, a, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat, balance_opt = Some(Random.nextLong(2 * amount.toLong).msat)),
          makeEdge(3L, d, e, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat, balance_opt = Some(Random.nextLong(2 * amount.toLong).msat)),
          makeEdge(4L, a, c, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat),
          makeEdge(5L, a, e, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat),
          makeEdge(6L, e, f, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat),
          makeEdge(7L, e, b, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat),
          makeEdge(8L, b, c, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat),
          makeEdge(9L, c, f, Random.nextLong(250).msat, Random.nextInt(10000), minHtlc = Random.nextLong(100).msat, maxHtlc = Some((20000 + Random.nextLong(80000)).msat), CltvExpiryDelta(Random.nextInt(288)), capacity = (10 + Random.nextLong(100)).sat)
        )), 1 day)

        findMultiPartRoute(g, d, f, amount, maxFee, routeParams = DEFAULT_ROUTE_PARAMS.copy(randomize = true).modify(_.mpp.splittingStrategy).setTo(Randomize), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true) match {
          case Success(routes) => checkRouteAmounts(routes, amount, maxFee)
          case Failure(ex) => assert(ex == RouteNotFound)
        }
      }
    }

    test("calculate multipart route to remote node using max expected amount splitting strategy") {
      // A-------------E
      // |             |
      // +----- B -----+
      // |             |
      // +----- C ---- +
      // |             |
      // +----- D -----+
      val (amount, maxFee) = (60000 msat, 1000 msat)
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        // The A -> B -> E route is the most economic one, but we already have a pending HTLC in it.
        makeEdge(0L, a, e, 50 msat, 0, minHtlc = 100 msat, balance_opt = Some(10000 msat)),
        makeEdge(1L, a, b, 50 msat, 0, minHtlc = 100 msat, balance_opt = Some(100000 msat)),
        makeEdge(2L, b, e, 50 msat, 0, minHtlc = 100 msat, capacity = 50 sat),
        makeEdge(3L, a, c, 50 msat, 0, minHtlc = 100 msat, balance_opt = Some(100000 msat)),
        makeEdge(4L, c, e, 50 msat, 0, minHtlc = 100 msat, capacity = 25 sat),
        makeEdge(5L, a, d, 50 msat, 0, minHtlc = 100 msat, balance_opt = Some(100000 msat)),
        makeEdge(6L, d, e, 50 msat, 0, minHtlc = 100 msat, capacity = 25 sat),
      )), 1 day)

      val routeParams = DEFAULT_ROUTE_PARAMS.copy(mpp = DEFAULT_ROUTE_PARAMS.mpp.copy(splittingStrategy = MultiPartParams.MaxExpectedAmount))
      val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      checkRouteAmounts(routes, amount, maxFee)
      assert(routes.map(route => (route.amount, route.hops.head.shortChannelId.toLong)).toSet == Set((10000 msat, 0L), (25000 msat, 1L), (12500 msat, 3L), (12500 msat, 5L)))
    }

    test("calculate multipart route to remote node using max expected amount splitting strategy, respect minPartAmount") {
      // +----- B -----+
      // |             |
      // A----- C ---- E
      // |             |
      // +----- D -----+
      val (amount, maxFee) = (55000 msat, 1000 msat)
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        // The A -> B -> E route is the most economic one, but we already have a pending HTLC in it.
        makeEdge(1L, a, b, 50 msat, 0, minHtlc = 100 msat, balance_opt = Some(100000 msat)),
        makeEdge(2L, b, e, 50 msat, 0, minHtlc = 100 msat, capacity = 50 sat),
        makeEdge(3L, a, c, 50 msat, 0, minHtlc = 100 msat, balance_opt = Some(100000 msat)),
        makeEdge(4L, c, e, 50 msat, 0, minHtlc = 100 msat, capacity = 25 sat),
        makeEdge(5L, a, d, 50 msat, 0, minHtlc = 100 msat, balance_opt = Some(100000 msat)),
        makeEdge(6L, d, e, 50 msat, 0, minHtlc = 100 msat, capacity = 25 sat),
      )), 1 day)

      val routeParams = DEFAULT_ROUTE_PARAMS.copy(mpp = DEFAULT_ROUTE_PARAMS.mpp.copy(minPartAmount = 15000 msat, splittingStrategy = MultiPartParams.MaxExpectedAmount))
      val Success(routes) = findMultiPartRoute(g, a, e, amount, maxFee, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.forall(_.hops.length == 2), routes)
      checkRouteAmounts(routes, amount, maxFee)
      assert(routes.map(route => (route.amount, route.hops.head.shortChannelId.toLong)).toSet == Set((25000 msat, 1L), (15000 msat, 3L), (15000 msat, 5L)))
    }

    test("loop trap") {
      //       +-----------------+
      //       |                 |
      //       |                 v
      // A --> B --> C --> D --> E
      //       ^     |
      //       |     |
      //       F <---+
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 1000 msat, 1000),
        makeEdge(2L, b, c, 1000 msat, 1000),
        makeEdge(3L, c, d, 1000 msat, 1000),
        makeEdge(4L, d, e, 1000 msat, 1000),
        makeEdge(5L, b, e, 1000 msat, 1000),
        makeEdge(6L, c, f, 1000 msat, 1000),
        makeEdge(7L, f, b, 1000 msat, 1000),
      )), 1 day)

      val Success(routes) = findRoute(g, a, e, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 3, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.length == 2)
      val route1 :: route2 :: Nil = routes
      assert(route2Ids(route1) == 1 :: 5 :: Nil)
      assert(route2Ids(route2) == 1 :: 2 :: 3 :: 4 :: Nil)
    }

    test("reversed loop trap") {
      //       +-----------------+
      //       |                 |
      //       v                 |
      // A <-- B <-- C <-- D <-- E
      //       |     ^
      //       |     |
      //       F ----+
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, b, a, 1000 msat, 1000),
        makeEdge(2L, c, b, 1000 msat, 1000),
        makeEdge(3L, d, c, 1000 msat, 1000),
        makeEdge(4L, e, d, 1000 msat, 1000),
        makeEdge(5L, e, b, 1000 msat, 1000),
        makeEdge(6L, f, c, 1000 msat, 1000),
        makeEdge(7L, b, f, 1000 msat, 1000),
      )), 1 day)

      val Success(routes) = findRoute(g, e, a, DEFAULT_AMOUNT_MSAT, DEFAULT_MAX_FEE, numRoutes = 3, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.length == 2)
      val route1 :: route2 :: Nil = routes
      assert(route2Ids(route1) == 5 :: 1 :: Nil)
      assert(route2Ids(route2) == 4 :: 3 :: 2 :: 1 :: Nil)
    }

    test("k-shortest paths must be distinct") {
      //   +----> N ---> N         N ---> N ----+
      //  /        \    /           \    /        \
      // A          +--+    (...)    +--+          B
      //  \        /    \           /    \        /
      //   +----> N ---> N         N ---> N ----+

      def makeEdges(n: Int): Seq[GraphEdge] = {
        val nodes = new Array[(PublicKey, PublicKey)](n)
        for (i <- nodes.indices) {
          nodes(i) = (randomKey().publicKey, randomKey().publicKey)
        }
        val q = new mutable.Queue[GraphEdge]
        // One path is shorter to maximise the overlap between the n-shortest paths, they will all be like the shortest path with a single hop changed.
        q.enqueue(makeEdge(1L, a, nodes(0)._1, 100 msat, 90))
        q.enqueue(makeEdge(2L, a, nodes(0)._2, 100 msat, 100))
        for (i <- 0 until (n - 1)) {
          q.enqueue(makeEdge(4 * i + 3, nodes(i)._1, nodes(i + 1)._1, 100 msat, 90))
          q.enqueue(makeEdge(4 * i + 4, nodes(i)._1, nodes(i + 1)._2, 100 msat, 90))
          q.enqueue(makeEdge(4 * i + 5, nodes(i)._2, nodes(i + 1)._1, 100 msat, 100))
          q.enqueue(makeEdge(4 * i + 6, nodes(i)._2, nodes(i + 1)._2, 100 msat, 100))
        }
        q.enqueue(makeEdge(4 * n, nodes(n - 1)._1, b, 100 msat, 90))
        q.enqueue(makeEdge(4 * n + 1, nodes(n - 1)._2, b, 100 msat, 100))
        q.toSeq
      }

      val g = GraphWithBalanceEstimates(DirectedGraph(makeEdges(10)), 1 day)

      val Success(routes) = findRoute(g, a, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 10, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.distinct.length == 10)
    }

    test("all paths are shortest") {
      //   +----> N ---> N         N ---> N ----+
      //  /        \    /           \    /        \
      // A          +--+    (...)    +--+          B
      //  \        /    \           /    \        /
      //   +----> N ---> N         N ---> N ----+

      def makeEdges(n: Int): Seq[GraphEdge] = {
        val nodes = new Array[(PublicKey, PublicKey)](n)
        for (i <- nodes.indices) {
          nodes(i) = (randomKey().publicKey, randomKey().publicKey)
        }
        val q = new mutable.Queue[GraphEdge]
        q.enqueue(makeEdge(1L, a, nodes(0)._1, 100 msat, 100))
        q.enqueue(makeEdge(2L, a, nodes(0)._2, 100 msat, 100))
        for (i <- 0 until (n - 1)) {
          q.enqueue(makeEdge(4 * i + 3, nodes(i)._1, nodes(i + 1)._1, 100 msat, 100))
          q.enqueue(makeEdge(4 * i + 4, nodes(i)._1, nodes(i + 1)._2, 100 msat, 100))
          q.enqueue(makeEdge(4 * i + 5, nodes(i)._2, nodes(i + 1)._1, 100 msat, 100))
          q.enqueue(makeEdge(4 * i + 6, nodes(i)._2, nodes(i + 1)._2, 100 msat, 100))
        }
        q.enqueue(makeEdge(4 * n, nodes(n - 1)._1, b, 100 msat, 100))
        q.enqueue(makeEdge(4 * n + 1, nodes(n - 1)._2, b, 100 msat, 100))
        q.toSeq
      }

      val g = GraphWithBalanceEstimates(DirectedGraph(makeEdges(10)), 1 day)

      val Success(routes) = findRoute(g, a, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 10, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.distinct.length == 10)
      val fees = routes.map(_.channelFee(false))
      assert(fees.forall(_ == fees.head))
    }

    test("can't relay if fee is not sufficient") {
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 1000 msat, 7000),
      )), 1 day)

      assert(findRoute(g, a, b, 10000000 msat, 10000 msat, numRoutes = 3, routeParams = DEFAULT_ROUTE_PARAMS.copy(includeLocalChannelCost = true), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true) == Failure(RouteNotFound))
      assert(findRoute(g, a, b, 10000000 msat, 100000 msat, numRoutes = 3, routeParams = DEFAULT_ROUTE_PARAMS.copy(includeLocalChannelCost = true), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true).isSuccess)
    }

    test("penalty per hop") {
      //  S ---> A ---> B
      //         |      ^
      //         v      |
      //         C ---> D
      //         |      ^
      //         v      |
      //         E ---> F
      val start = randomKey().publicKey
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(0L, start, a, 0 msat, 0),
        makeEdge(1L, a, b, 1000 msat, 1000),
        makeEdge(2L, a, c, 0 msat, 0),
        makeEdge(3L, c, d, 700 msat, 1000),
        makeEdge(4L, d, b, 0 msat, 0),
        makeEdge(5L, c, e, 0 msat, 0),
        makeEdge(6L, e, f, 600 msat, 1000),
        makeEdge(7L, f, d, 0 msat, 0),
      )), 1 day)

      { // No hop cost
        val Success(routes) = findRoute(g, start, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.distinct.length == 1)
        val route :: Nil = routes
        assert(route2Ids(route) == 0 :: 2 :: 5 :: 6 :: 7 :: 4 :: Nil)
      }
      { // small base hop cost
        val Success(routes) = findRoute(g, start, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = HeuristicsConstants(0, RelayFees(0 msat, 0), RelayFees(100 msat, 0), useLogProbability = false, usePastRelaysData = false)), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.distinct.length == 1)
        val route :: Nil = routes
        assert(route2Ids(route) == 0 :: 2 :: 3 :: 4 :: Nil)
      }
      { // large proportional hop cost
        val Success(routes) = findRoute(g, start, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = HeuristicsConstants(0, RelayFees(0 msat, 0), RelayFees(0 msat, 200), useLogProbability = false, usePastRelaysData = false)), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.distinct.length == 1)
        val route :: Nil = routes
        assert(route2Ids(route) == 0 :: 1 :: Nil)
      }
    }

    test("most likely successful path") {
      //  S ---> A ---> B
      //         |      ^
      //         v      |
      //         C ---> D
      val start = randomKey().publicKey
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(0L, start, a, 0 msat, 0),
        makeEdge(1L, a, b, 1000 msat, 1000, capacity = (DEFAULT_AMOUNT_MSAT * 1.2).truncateToSatoshi),
        makeEdge(2L, a, c, 400 msat, 500, capacity = (DEFAULT_AMOUNT_MSAT * 3).truncateToSatoshi),
        makeEdge(3L, c, d, 400 msat, 500, capacity = (DEFAULT_AMOUNT_MSAT * 3).truncateToSatoshi),
        makeEdge(4L, d, b, 400 msat, 500, capacity = (DEFAULT_AMOUNT_MSAT * 3).truncateToSatoshi),
      )), 1 day)

      {
        val hc = HeuristicsConstants(
          lockedFundsRisk = 0.0,
          failureFees = RelayFees(1000 msat, 500),
          hopFees = RelayFees(0 msat, 0),
          useLogProbability = false,
          usePastRelaysData = true,
        )
        val Success(routes) = findRoute(g, start, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = hc), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.distinct.length == 1)
        val route :: Nil = routes
        assert(route2Ids(route) == 0 :: 2 :: 3 :: 4 :: Nil)

      }

      {
        val hc = HeuristicsConstants(
          lockedFundsRisk = 0.0,
          failureFees = RelayFees(10000 msat, 1000),
          hopFees = RelayFees(0 msat, 0),
          useLogProbability = true,
          usePastRelaysData = true,
        )
        val Success(routes) = findRoute(g, start, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = hc), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
        assert(routes.distinct.length == 1)
        val route :: Nil = routes
        assert(route2Ids(route) == 0 :: 2 :: 3 :: 4 :: Nil)
      }
    }

    test("no path that can get our funds stuck for too long") {
      //  S ---> A ---> B
      //         |      ^
      //         v      |
      //         C ---> D
      val start = randomKey().publicKey
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(0L, start, a, 0 msat, 0),
        makeEdge(1L, a, b, 1000 msat, 1000, cltvDelta = CltvExpiryDelta(1000)),
        makeEdge(2L, a, c, 350 msat, 350, cltvDelta = CltvExpiryDelta(10)),
        makeEdge(3L, c, d, 350 msat, 350, cltvDelta = CltvExpiryDelta(10)),
        makeEdge(4L, d, b, 350 msat, 350, cltvDelta = CltvExpiryDelta(10)),
      )), 1 day)

      val hc = HeuristicsConstants(
        lockedFundsRisk = 1e-7,
        failureFees = RelayFees(0 msat, 0),
        hopFees = RelayFees(0 msat, 0),
        useLogProbability = true,
        usePastRelaysData = true,
      )
      val Success(routes) = findRoute(g, start, b, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = hc), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.distinct.length == 1)
      val route :: Nil = routes
      assert(route2Ids(route) == 0 :: 2 :: 3 :: 4 :: Nil)
    }

    test("edge too small to relay payment is ignored") {
      // A ===> B ===> C <--- D
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 100 msat, 100),
        makeEdge(2L, b, c, 100 msat, 100),
        makeEdge(3L, d, c, 100 msat, 100, capacity = 1000 sat),
      )), 1 day)

      val hc = HeuristicsConstants(
        lockedFundsRisk = 1e-7,
        failureFees = RelayFees(0 msat, 0),
        hopFees = RelayFees(0 msat, 0),
        useLogProbability = true,
        usePastRelaysData = true,
      )
      val Success(routes) = findRoute(g, a, c, DEFAULT_AMOUNT_MSAT, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = hc), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.distinct.length == 1)
      val route :: Nil = routes
      assert(route2Ids(route) == 1 :: 2 :: Nil)
    }

    test("use direct channel when available") {
      // A ===> B ===> C
      //  \___________/
      val recentChannelId = ShortChannelId.fromCoordinates("399990x1x2").success.value.toLong
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 1 msat, 1, capacity = 100_000_000 sat),
        makeEdge(2L, b, c, 1 msat, 1, capacity = 100_000_000 sat),
        makeEdge(recentChannelId, a, c, 1000 msat, 100),
      )), 1 day)

      val wr = HeuristicsConstants(
        lockedFundsRisk = 0,
        failureFees = RelayFees(100 msat, 100),
        hopFees = RelayFees(500 msat, 200),
        useLogProbability = false,
        usePastRelaysData = false,
      )
      val Success(routes) = findRoute(g, a, c, DEFAULT_AMOUNT_MSAT, 100_000_000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = wr), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(routes.distinct.length == 1)
      val route :: Nil = routes
      assert(route2Ids(route) == recentChannelId :: Nil)
    }

    test("trampoline relay with direct channel to target") {
      val amount = 100_000_000 msat
      val g = GraphWithBalanceEstimates(DirectedGraph(List(makeEdge(1L, a, b, 1000 msat, 1000, capacity = 100_000_000 sat))), 1 day)

      {
        val routeParams = DEFAULT_ROUTE_PARAMS.copy(includeLocalChannelCost = true, boundaries = SearchBoundaries(100_999 msat, 0.0, 6, CltvExpiryDelta(576)))
        assert(findMultiPartRoute(g, a, b, amount, 100_999 msat, Set.empty, Set.empty, Set.empty, Nil, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true) == Failure(RouteNotFound))
      }
      {
        val routeParams = DEFAULT_ROUTE_PARAMS.copy(includeLocalChannelCost = true, boundaries = SearchBoundaries(101_000 msat, 0.0, 6, CltvExpiryDelta(576)))
        assert(findMultiPartRoute(g, a, b, amount, 101_000 msat, Set.empty, Set.empty, Set.empty, Nil, routeParams = routeParams, currentBlockHeight = BlockHeight(400000), blip18InboundFees = true).isSuccess)
      }
    }

    test("small local edge with liquidity is better than big remote edge") {
      // A == B == C -- D
      //  \_______/
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 100 msat, 100, minHtlc = 1000 msat, capacity = 100000000 sat, balance_opt = Some(10000000 msat)),
        makeEdge(2L, b, c, 100 msat, 100, minHtlc = 1000 msat, capacity = 100000000 sat),
        makeEdge(3L, a, c, 100 msat, 100, minHtlc = 1000 msat, capacity = 100 sat, balance_opt = Some(100000 msat)),
        makeEdge(4L, c, d, 100 msat, 100, minHtlc = 1000 msat, capacity = 100000000 sat),
      )), 1 day)

      val wr = HeuristicsConstants(
        lockedFundsRisk = 0,
        failureFees = RelayFees(1000 msat, 1000),
        hopFees = RelayFees(0 msat, 0),
        useLogProbability = false,
        usePastRelaysData = false,
      )
      val Success(routes) = findRoute(g, a, d, 50000 msat, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = wr, includeLocalChannelCost = true), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      val route :: Nil = routes
      assert(route2Ids(route) == 3 :: 4 :: Nil)
    }

    test("take past attempts into account") {
      //        C
      //       / \
      // A -- B   E
      //       \ /
      //        D
      val g = GraphWithBalanceEstimates(DirectedGraph(List(
        makeEdge(1L, a, b, 100 msat, 100, minHtlc = 1000 msat, capacity = 100000000 sat),
        makeEdge(2L, b, c, 100 msat, 100, minHtlc = 1000 msat, capacity = 100000000 sat),
        makeEdge(3L, c, e, 100 msat, 100, minHtlc = 1000 msat, capacity = 100000000 sat),
        makeEdge(4L, b, d, 1000 msat, 1000, minHtlc = 1000 msat, capacity = 100000 sat),
        makeEdge(5L, d, e, 1000 msat, 1000, minHtlc = 1000 msat, capacity = 100000 sat),
      )), 1 day)

      val amount = 50000 msat

      val hc = HeuristicsConstants(
        lockedFundsRisk = 0,
        failureFees = RelayFees(1000 msat, 1000),
        hopFees = RelayFees(500 msat, 200),
        useLogProbability = true,
        usePastRelaysData = true
      )
      val Success(route1 :: Nil) = findRoute(g, a, e, amount, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = hc, includeLocalChannelCost = true), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route1) == 1 :: 2 :: 3 :: Nil)

      val h = g.routeCouldRelay(route1.stopAt(c)).channelCouldNotSend(route1.hops.last, amount)

      val Success(route2 :: Nil) = findRoute(h, a, e, amount, 100000000 msat, numRoutes = 1, routeParams = DEFAULT_ROUTE_PARAMS.copy(heuristics = hc, includeLocalChannelCost = true), currentBlockHeight = BlockHeight(400000), blip18InboundFees = true)
      assert(route2Ids(route2) == 1 :: 4 :: 5 :: Nil)
    }

}

object Blip18RouteCalculationSpec {

  val noopBoundaries = { _: PaymentPathWeight => true }

  val DEFAULT_AMOUNT_MSAT = 10_000_000 msat
  val DEFAULT_MAX_FEE = 100_000 msat
  val DEFAULT_EXPIRY = CltvExpiry(TestConstants.defaultBlockHeight)
  val DEFAULT_CAPACITY = 100_000 sat

  val NO_WEIGHT_RATIOS: HeuristicsConstants = HeuristicsConstants(0, RelayFees(0 msat, 0), RelayFees(0 msat, 0), useLogProbability = false, usePastRelaysData = false)
  val DEFAULT_ROUTE_PARAMS = PathFindingConf(
    randomize = false,
    boundaries = SearchBoundaries(21000 msat, 0.03, 6, CltvExpiryDelta(2016)),
    NO_WEIGHT_RATIOS,
    MultiPartParams(1000 msat, 10, FullCapacity),
    experimentName = "my-test-experiment",
    experimentPercentage = 100).getDefaultRouteParams

  val DUMMY_SIG = Transactions.PlaceHolderSig

  def makeChannel(shortChannelId: Long, nodeIdA: PublicKey, nodeIdB: PublicKey): ChannelAnnouncement = {
    val (nodeId1, nodeId2) = if (Announcements.isNode1(nodeIdA, nodeIdB)) (nodeIdA, nodeIdB) else (nodeIdB, nodeIdA)
    ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, Features.empty, Block.RegtestGenesisBlock.hash, RealShortChannelId(shortChannelId), nodeId1, nodeId2, randomKey().publicKey, randomKey().publicKey)
  }

  def makeEdge(shortChannelId: Long,
               nodeId1: PublicKey,
               nodeId2: PublicKey,
               feeBase: MilliSatoshi = 0 msat,
               feeProportionalMillionth: Int = 0,
               minHtlc: MilliSatoshi = DEFAULT_AMOUNT_MSAT,
               maxHtlc: Option[MilliSatoshi] = None,
               cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0),
               capacity: Satoshi = DEFAULT_CAPACITY,
               balance_opt: Option[MilliSatoshi] = None,
               inboundFeeBase_opt: Option[MilliSatoshi] = None,
               inboundFeeProportionalMillionth_opt: Option[Int] = None): GraphEdge = {
    val update = makeUpdateShort(ShortChannelId(shortChannelId), nodeId1, nodeId2, feeBase, feeProportionalMillionth, minHtlc, maxHtlc.orElse(Some(capacity.toMilliSatoshi)), cltvDelta, inboundFeeBase_opt = inboundFeeBase_opt, inboundFeeProportionalMillionth_opt = inboundFeeProportionalMillionth_opt)
    GraphEdge(ChannelDesc(RealShortChannelId(shortChannelId), nodeId1, nodeId2), HopRelayParams.FromAnnouncement(update), capacity, balance_opt)
  }

  def makeUpdateShort(shortChannelId: ShortChannelId, nodeId1: PublicKey, nodeId2: PublicKey, feeBase: MilliSatoshi, feeProportionalMillionth: Int, minHtlc: MilliSatoshi = DEFAULT_AMOUNT_MSAT, maxHtlc: Option[MilliSatoshi] = None, cltvDelta: CltvExpiryDelta = CltvExpiryDelta(0), timestamp: TimestampSecond = 0 unixsec, inboundFeeBase_opt: Option[MilliSatoshi] = None, inboundFeeProportionalMillionth_opt: Option[Int] = None): ChannelUpdate = {
    val tlvStream: TlvStream[ChannelUpdateTlv] = if (inboundFeeBase_opt.isDefined && inboundFeeProportionalMillionth_opt.isDefined) {
      TlvStream(ChannelUpdateTlv.Blip18InboundFee(inboundFeeBase_opt.get.toLong.toInt, inboundFeeProportionalMillionth_opt.get))
    } else {
      TlvStream.empty
    }
    ChannelUpdate(
      signature = DUMMY_SIG,
      chainHash = Block.RegtestGenesisBlock.hash,
      shortChannelId = shortChannelId,
      timestamp = timestamp,
      messageFlags = ChannelUpdate.MessageFlags(dontForward = false),
      channelFlags = ChannelUpdate.ChannelFlags(isEnabled = true, isNode1 = Announcements.isNode1(nodeId1, nodeId2)),
      cltvExpiryDelta = cltvDelta,
      htlcMinimumMsat = minHtlc,
      feeBaseMsat = feeBase,
      feeProportionalMillionths = feeProportionalMillionth,
      htlcMaximumMsat = maxHtlc.getOrElse(500_000_000 msat),
      tlvStream = tlvStream
    )
  }

  def hops2Ids(hops: Seq[ChannelHop]): Seq[Long] = hops.map(hop => hop.shortChannelId.toLong)

  def route2Ids(route: Route): Seq[Long] = hops2Ids(route.hops)

  def routes2Ids(routes: Seq[Route]): Set[Seq[Long]] = routes.map(route2Ids).toSet

  def route2Edges(route: Route): Seq[GraphEdge] = route.hops.map(hop => GraphEdge(ChannelDesc(hop.shortChannelId, hop.nodeId, hop.nextNodeId), hop.params, 1000000 sat, None))

  def route2Nodes(route: Route): Seq[(PublicKey, PublicKey)] = route.hops.map(hop => (hop.nodeId, hop.nextNodeId))

  def route2NodeIds(route: Route): Seq[PublicKey] = route.hops.head.nodeId +: route.hops.map(_.nextNodeId)

  def checkIgnoredChannels(routes: Seq[Route], shortChannelIds: Long*): Unit = {
    shortChannelIds.foreach(shortChannelId => routes.foreach(route => {
      assert(route.hops.forall(_.shortChannelId.toLong != shortChannelId), route)
    }))
  }

  def checkRouteAmounts(routes: Seq[Route], totalAmount: MilliSatoshi, maxFee: MilliSatoshi): Unit = {
    assert(routes.map(_.amount).sum == totalAmount, routes)
    assert(routes.map(_.channelFee(false)).sum <= maxFee, routes)
  }
}
