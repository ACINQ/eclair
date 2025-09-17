package fr.acinq.eclair.router

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, Satoshi, SatoshiLong}
import fr.acinq.eclair.payment.IncomingPaymentPacket.{ChannelRelayPacket, FinalPacket, decrypt}
import fr.acinq.eclair.payment.OutgoingPaymentPacket.buildOutgoingPayment
import fr.acinq.eclair.payment.PaymentPacketSpec.{paymentHash, paymentSecret}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.payment.send.ClearRecipient
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.PaymentWeightRatios
import fr.acinq.eclair.router.RouteCalculation.{findMultiPartRoute, findRoute}
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, RealShortChannelId, ShortChannelId, TestConstants, TimestampSecond, TimestampSecondLong, randomBytes32, randomKey}
import org.scalatest.ParallelTestExecution
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

class Blip18RouteCalculationSpec extends AnyFunSuite with ParallelTestExecution {

  import Blip18RouteCalculationSpec._

  val (priv_a, priv_b, priv_c, priv_d, priv_e, priv_f) = (randomKey(), randomKey(), randomKey(), randomKey(), randomKey(), randomKey())
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

}

object Blip18RouteCalculationSpec {

  val DEFAULT_AMOUNT_MSAT = 10_000_000 msat
  val DEFAULT_MAX_FEE = 100_000 msat
  val DEFAULT_EXPIRY = CltvExpiry(TestConstants.defaultBlockHeight)
  val DEFAULT_CAPACITY = 100_000 sat

  val NO_WEIGHT_RATIOS: PaymentWeightRatios = PaymentWeightRatios(1, 0, 0, 0, RelayFees(0 msat, 0))
  val DEFAULT_ROUTE_PARAMS = PathFindingConf(
    randomize = false,
    boundaries = SearchBoundaries(21000 msat, 0.03, 6, CltvExpiryDelta(2016)),
    NO_WEIGHT_RATIOS,
    MultiPartParams(1000 msat, 10),
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
    val update = makeUpdateShort(ShortChannelId(shortChannelId), nodeId1, nodeId2, feeBase, feeProportionalMillionth, minHtlc, maxHtlc, cltvDelta, inboundFeeBase_opt = inboundFeeBase_opt, inboundFeeProportionalMillionth_opt = inboundFeeProportionalMillionth_opt)
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
}
