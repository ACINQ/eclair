package fr.acinq.eclair.router

import akka.actor.typed.scaladsl.adapter.ClassicActorRefOps
import akka.event.DiagnosticLoggingAdapter
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, Satoshi, SatoshiLong}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.HeuristicsConstants
import fr.acinq.eclair.router.Router.MultiPartParams.FullCapacity
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, RealShortChannelId, ShortChannelId, TestKitBaseClass, TimestampSecond, TimestampSecondLong, randomKey}
import org.scalatest.ParallelTestExecution
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.DurationInt

class Blip18FinalizeRouteSpec extends TestKitBaseClass with AnyFunSuiteLike with ParallelTestExecution {

  import Blip18FinalizeRouteSpec._

  val (priv_a, priv_b, priv_c, priv_d, priv_e) = (randomKey(), randomKey(), randomKey(), randomKey(), randomKey())
  val (a, b, c, d, e) = (priv_a.publicKey, priv_b.publicKey, priv_c.publicKey, priv_d.publicKey, priv_e.publicKey)

  // Create a test actor to get ActorContext
  class DummyActor extends akka.actor.Actor {
    def receive: Receive = { case _ => }
  }
  val dummyActor = system.actorOf(akka.actor.Props(new DummyActor))
  val dummyRef = akka.testkit.TestActorRef(new DummyActor)
  implicit val dummyContext: akka.actor.ActorContext = dummyRef.underlyingActor.context
  implicit val dummyLog: DiagnosticLoggingAdapter = new DiagnosticLoggingAdapter {
    override def isErrorEnabled: Boolean = false

    override def isWarningEnabled: Boolean = false

    override def isInfoEnabled: Boolean = false

    override def isDebugEnabled: Boolean = false

    override protected def notifyError(message: String): Unit = ()

    override protected def notifyError(cause: Throwable, message: String): Unit = ()

    override protected def notifyWarning(message: String): Unit = ()

    override protected def notifyInfo(message: String): Unit = ()

    override protected def notifyDebug(message: String): Unit = ()
  }

  test("finalizeRoute with PredefinedNodeRoute and BLIP-18 enabled") {
    val g = DirectedGraph(Seq(
      makeEdge(10L, a, b, minHtlc = 2 msat),
      makeEdge(10L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(-5000 msat), inboundFeeProportionalMillionth_opt = Some(-60_000)),
      makeEdge(11L, b, c, 1000 msat, 50_000, minHtlc = 2 msat),
      makeEdge(11L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(5000 msat), inboundFeeProportionalMillionth_opt = Some(0)),
      makeEdge(12L, c, d, 0 msat, 50_000, minHtlc = 2 msat),
      makeEdge(12L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(-8000 msat), inboundFeeProportionalMillionth_opt = Some(-50_000)),
    ))

    val data = Router.Data(
      nodes = Map.empty,
      channels = makeChannelsFromGraph(g),
      prunedChannels = collection.immutable.SortedMap.empty,
      stash = Router.Stash(Map.empty, Map.empty),
      rebroadcast = Router.Rebroadcast(Map.empty, Map.empty, Map.empty),
      awaiting = Map.empty,
      privateChannels = Map.empty,
      scid2PrivateChannels = Map.empty,
      excludedChannels = Map.empty,
      graphWithBalances = GraphWithBalanceEstimates(g, 1 day),
      sync = Map.empty,
      spentChannels = Map.empty
    )

    val route = PredefinedNodeRoute(100_000 msat, Seq(a, b, c, d))
    val probe = TestProbe()
    val replyTo = probe.ref.toTyped[Router.PaymentRouteResponse]
    val fr = Router.FinalizeRoute(replyTo, route, blip18InboundFees = true, excludePositiveInboundFees = false)


    RouteCalculation.finalizeRoute(data, a, fr)

    probe.expectMsgType[Router.RouteResponse] match {
      case Router.RouteResponse(routes) =>
        assert(routes.length == 1)
        val r = routes.head
        assert(r.hops.length == 3)
        // Check that inbound fees are enriched from back-edges
        assert(r.hops(0).params.inboundFees_opt.isEmpty) // First hop has no inbound fees
        assert(r.hops(1).params.inboundFees_opt.isDefined) // Second hop should have inbound fees from b->a
        assert(r.hops(1).params.inboundFees_opt.get.feeBase == -5000.msat)
        assert(r.hops(1).params.inboundFees_opt.get.feeProportionalMillionths == -60_000)
        assert(r.hops(2).params.inboundFees_opt.isDefined) // Third hop should have inbound fees from c->b
        assert(r.hops(2).params.inboundFees_opt.get.feeBase == 5000.msat)
        assert(r.hops(2).params.inboundFees_opt.get.feeProportionalMillionths == 0)
    }
  }

  test("finalizeRoute with PredefinedNodeRoute and BLIP-18 disabled") {
    val g = DirectedGraph(Seq(
      makeEdge(10L, a, b, minHtlc = 2 msat),
      makeEdge(10L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(-5000 msat), inboundFeeProportionalMillionth_opt = Some(-60_000)),
      makeEdge(11L, b, c, 1000 msat, 50_000, minHtlc = 2 msat),
      makeEdge(11L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(5000 msat), inboundFeeProportionalMillionth_opt = Some(0)),
      makeEdge(12L, c, d, 0 msat, 50_000, minHtlc = 2 msat),
      makeEdge(12L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(-8000 msat), inboundFeeProportionalMillionth_opt = Some(-50_000)),
    ))

    val data = Router.Data(
      nodes = Map.empty,
      channels = makeChannelsFromGraph(g),
      prunedChannels = collection.immutable.SortedMap.empty,
      stash = Router.Stash(Map.empty, Map.empty),
      rebroadcast = Router.Rebroadcast(Map.empty, Map.empty, Map.empty),
      awaiting = Map.empty,
      privateChannels = Map.empty,
      scid2PrivateChannels = Map.empty,
      excludedChannels = Map.empty,
      graphWithBalances = GraphWithBalanceEstimates(g, 1 day),
      sync = Map.empty,
      spentChannels = Map.empty
    )

    val route = PredefinedNodeRoute(100_000 msat, Seq(a, b, c, d))
    val probe = TestProbe()
    val replyTo = probe.ref.toTyped[Router.PaymentRouteResponse]
    val fr = Router.FinalizeRoute(replyTo, route, blip18InboundFees = false, excludePositiveInboundFees = false)

    RouteCalculation.finalizeRoute(data, a, fr)

    probe.expectMsgType[Router.RouteResponse] match {
      case Router.RouteResponse(routes) =>
        assert(routes.length == 1)
        val r = routes.head
        assert(r.hops.length == 3)
        // Check that no inbound fees are enriched when BLIP-18 is disabled
        assert(r.hops(0).params.inboundFees_opt.isEmpty)
        assert(r.hops(1).params.inboundFees_opt.isEmpty)
        assert(r.hops(2).params.inboundFees_opt.isEmpty)
    }
  }

  test("finalizeRoute with PredefinedChannelRoute and BLIP-18 enabled") {
    val g = DirectedGraph(Seq(
      makeEdge(10L, a, b, minHtlc = 2 msat),
      makeEdge(10L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(-5000 msat), inboundFeeProportionalMillionth_opt = Some(-60_000)),

      makeEdge(11L, b, c, 1000 msat, 50_000, minHtlc = 2 msat),
      makeEdge(11L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(5000 msat), inboundFeeProportionalMillionth_opt = Some(0)),

      makeEdge(12L, c, d, 0 msat, 50_000, minHtlc = 2 msat),
      makeEdge(12L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(-8000 msat), inboundFeeProportionalMillionth_opt = Some(-50_000)),

      makeEdge(13L, d, e, 9000 msat, 100_000, minHtlc = 2 msat),
      makeEdge(13L, e, d, minHtlc = 2 msat, inboundFeeBase_opt = Some(2000 msat), inboundFeeProportionalMillionth_opt = Some(80_000)),
    ))

    val data = Router.Data(
      nodes = Map.empty,
      channels = makeChannelsFromGraph(g),
      prunedChannels = collection.immutable.SortedMap.empty,
      stash = Router.Stash(Map.empty, Map.empty),
      rebroadcast = Router.Rebroadcast(Map.empty, Map.empty, Map.empty),
      awaiting = Map.empty,
      privateChannels = Map.empty,
      scid2PrivateChannels = Map.empty,
      excludedChannels = Map.empty,
      graphWithBalances = GraphWithBalanceEstimates(g, 1 day),
      sync = Map.empty,
      spentChannels = Map.empty
    )

    val route = PredefinedChannelRoute(100_000 msat, e, Seq(ShortChannelId(10L), ShortChannelId(11L), ShortChannelId(12L), ShortChannelId(13L)))
    val probe = TestProbe()
    val replyTo = probe.ref.toTyped[Router.PaymentRouteResponse]
    val fr = Router.FinalizeRoute(replyTo, route, blip18InboundFees = true, excludePositiveInboundFees = false)

    RouteCalculation.finalizeRoute(data, a, fr)

    probe.expectMsgType[Router.RouteResponse] match {
      case Router.RouteResponse(routes) =>
        assert(routes.length == 1)
        val r = routes.head
        assert(r.hops.length == 4)
        // Check that inbound fees are enriched from back-edges
        assert(r.hops(0).params.inboundFees_opt.isEmpty) // First hop has no inbound fees
        assert(r.hops(1).params.inboundFees_opt.isDefined) // Second hop should have inbound fees from b->a
        assert(r.hops(1).params.inboundFees_opt.get.feeBase == -5000.msat)
        assert(r.hops(1).params.inboundFees_opt.get.feeProportionalMillionths == -60_000)
        assert(r.hops(2).params.inboundFees_opt.isDefined) // Third hop should have inbound fees from c->b
        assert(r.hops(2).params.inboundFees_opt.get.feeBase == 5000.msat)
        assert(r.hops(2).params.inboundFees_opt.get.feeProportionalMillionths == 0)
        assert(r.hops(3).params.inboundFees_opt.isDefined) // Third hop should have inbound fees from c->b
        assert(r.hops(3).params.inboundFees_opt.get.feeBase == -8000.msat)
        assert(r.hops(3).params.inboundFees_opt.get.feeProportionalMillionths == -50_000)
        assert(r.amount == 100000.msat)
        assert(r.channelFee(true) == 15302.msat)
    }
  }

  test("finalizeRoute with PredefinedChannelRoute and BLIP-18 disabled") {
    val g = DirectedGraph(Seq(
      makeEdge(10L, a, b, minHtlc = 2 msat),
      makeEdge(10L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(-5000 msat), inboundFeeProportionalMillionth_opt = Some(-60_000)),

      makeEdge(11L, b, c, 1000 msat, 50_000, minHtlc = 2 msat),
      makeEdge(11L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(5000 msat), inboundFeeProportionalMillionth_opt = Some(0)),

      makeEdge(12L, c, d, 0 msat, 50_000, minHtlc = 2 msat),
      makeEdge(12L, d, c, minHtlc = 2 msat, inboundFeeBase_opt = Some(-8000 msat), inboundFeeProportionalMillionth_opt = Some(-50_000)),

      makeEdge(13L, d, e, 9000 msat, 100_000, minHtlc = 2 msat),
      makeEdge(13L, e, d, minHtlc = 2 msat, inboundFeeBase_opt = Some(2000 msat), inboundFeeProportionalMillionth_opt = Some(80_000)),
    ))

    val data = Router.Data(
      nodes = Map.empty,
      channels = makeChannelsFromGraph(g),
      prunedChannels = collection.immutable.SortedMap.empty,
      stash = Router.Stash(Map.empty, Map.empty),
      rebroadcast = Router.Rebroadcast(Map.empty, Map.empty, Map.empty),
      awaiting = Map.empty,
      privateChannels = Map.empty,
      scid2PrivateChannels = Map.empty,
      excludedChannels = Map.empty,
      graphWithBalances = GraphWithBalanceEstimates(g, 1 day),
      sync = Map.empty,
      spentChannels = Map.empty
    )

    val route = PredefinedChannelRoute(100_000 msat, e, Seq(ShortChannelId(10L), ShortChannelId(11L), ShortChannelId(12L), ShortChannelId(13L)))
    val probe = TestProbe()
    val replyTo = probe.ref.toTyped[Router.PaymentRouteResponse]
    val fr = Router.FinalizeRoute(replyTo, route, blip18InboundFees = false, excludePositiveInboundFees = false)

    RouteCalculation.finalizeRoute(data, a, fr)

    probe.expectMsgType[Router.RouteResponse] match {
      case Router.RouteResponse(routes) =>
        assert(routes.length == 1)
        val r = routes.head
        assert(r.hops.length == 4)
        // Check that no inbound fees are enriched when BLIP-18 is disabled
        assert(r.hops(0).params.inboundFees_opt.isEmpty)
        assert(r.hops(1).params.inboundFees_opt.isEmpty)
        assert(r.hops(2).params.inboundFees_opt.isEmpty)
        assert(r.hops(3).params.inboundFees_opt.isEmpty)
        assert(r.amount == 100000.msat)
        assert(r.channelFee(true) == 32197.msat)
    }
  }

  test("finalizeRoute with positive inbound fees should fail when excludePositiveInboundFees is true") {
    val g = DirectedGraph(Seq(
      makeEdge(10L, a, b, minHtlc = 2 msat),
      makeEdge(10L, b, a, minHtlc = 2 msat, inboundFeeBase_opt = Some(5000 msat), inboundFeeProportionalMillionth_opt = Some(100)),
      makeEdge(11L, b, c, 1000 msat, 50_000, minHtlc = 2 msat),
      makeEdge(11L, c, b, minHtlc = 2 msat, inboundFeeBase_opt = Some(0 msat), inboundFeeProportionalMillionth_opt = Some(0)),
    ))

    val data = Router.Data(
      nodes = Map.empty,
      channels = makeChannelsFromGraph(g),
      prunedChannels = collection.immutable.SortedMap.empty,
      stash = Router.Stash(Map.empty, Map.empty),
      rebroadcast = Router.Rebroadcast(Map.empty, Map.empty, Map.empty),
      awaiting = Map.empty,
      privateChannels = Map.empty,
      scid2PrivateChannels = Map.empty,
      excludedChannels = Map.empty,
      graphWithBalances = GraphWithBalanceEstimates(g, 1 day),
      sync = Map.empty,
      spentChannels = Map.empty
    )

    val route = PredefinedNodeRoute(100_000 msat, Seq(a, b, c))
    val probe = TestProbe()
    val replyTo = probe.ref.toTyped[Router.PaymentRouteResponse]
    val fr = Router.FinalizeRoute(replyTo, route, blip18InboundFees = true, excludePositiveInboundFees = true)

    RouteCalculation.finalizeRoute(data, a, fr)

    probe.expectMsgType[Router.PaymentRouteNotFound] match {
      case Router.PaymentRouteNotFound(reason) =>
        assert(reason.getMessage.contains("positive inbound fees"))
    }
  }

}

object Blip18FinalizeRouteSpec {

  val DEFAULT_AMOUNT_MSAT: MilliSatoshi = 10_000_000 msat
  val DEFAULT_CAPACITY: Satoshi = 100_000 sat

  val NO_WEIGHT_RATIOS: HeuristicsConstants = HeuristicsConstants(0, RelayFees(0 msat, 0), RelayFees(0 msat, 0), useLogProbability = false, usePastRelaysData = false)
  val DEFAULT_ROUTE_PARAMS: Router.RouteParams = PathFindingConf(
    randomize = false,
    boundaries = SearchBoundaries(21000 msat, 0.03, 6, CltvExpiryDelta(2016)),
    NO_WEIGHT_RATIOS,
    MultiPartParams(1000 msat, 10, FullCapacity),
    experimentName = "my-test-experiment",
    experimentPercentage = 100).getDefaultRouteParams

  val DUMMY_SIG: fr.acinq.bitcoin.scalacompat.ByteVector64 = Transactions.PlaceHolderSig

  def makeChannel(shortChannelId: Long, nodeIdA: PublicKey, nodeIdB: PublicKey): ChannelAnnouncement = {
    val (nodeId1, nodeId2) = if (Announcements.isNode1(nodeIdA, nodeIdB)) (nodeIdA, nodeIdB) else (nodeIdB, nodeIdA)
    ChannelAnnouncement(DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, DUMMY_SIG, Features.empty, Block.RegtestGenesisBlock.hash, RealShortChannelId(shortChannelId), nodeId1, nodeId2, randomKey().publicKey, randomKey().publicKey)
  }

  def makeChannelsFromGraph(g: DirectedGraph): SortedMap[RealShortChannelId, Router.PublicChannel] = {
    // Group edges by channel ID
    val edgesByChannel = g.edgeSet().groupBy(_.desc.shortChannelId)

    SortedMap.from(edgesByChannel.map { case (scid, edges) =>
      // Get the two edges for this channel (forward and backward)
      val edgeList = edges.toList
      require(edgeList.length == 2, s"Expected 2 edges for channel $scid, got ${edgeList.length}")

      val edge1 = edgeList.head
      val edge2 = edgeList(1)

      // Create channel announcement (makeChannel will determine correct node1/node2 ordering)
      val ann = makeChannel(scid.toLong, edge1.desc.a, edge1.desc.b)

      // Extract channel updates from edges
      val update1 = edge1.params match {
        case HopRelayParams.FromAnnouncement(u, _) => u
        case _ => throw new IllegalArgumentException("Expected FromAnnouncement params")
      }
      val update2 = edge2.params match {
        case HopRelayParams.FromAnnouncement(u, _) => u
        case _ => throw new IllegalArgumentException("Expected FromAnnouncement params")
      }

      // Determine which update goes to which side based on isNode1 flag
      val (update_1_opt, update_2_opt) = if (update1.channelFlags.isNode1) {
        (Some(update1), Some(update2))
      } else {
        (Some(update2), Some(update1))
      }

      val rscid = RealShortChannelId(scid.toLong)

      rscid -> Router.PublicChannel(ann, fr.acinq.bitcoin.scalacompat.TxId(fr.acinq.bitcoin.scalacompat.ByteVector32.Zeroes), DEFAULT_CAPACITY, update_1_opt, update_2_opt, None)
    })
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