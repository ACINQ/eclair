package fr.acinq.eclair.router

import java.nio.file.Paths
import java.sql.DriverManager

import fr.acinq.bitcoin.{ByteVector32, MilliSatoshi}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.db.sqlite.SqliteNetworkDb
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.Router.getDesc
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import org.scalatest.FunSuite

import scala.collection.immutable.TreeMap
import scala.util.{Failure, Random, Success}
import scodec.bits._


class FuzzyGraph extends FunSuite {

  val DEFAULT_ROUTE_PARAMS = RouteParams(randomize = false, maxFeeBaseMsat = 21000, maxFeePct = 0.03, routeMaxCltv = 2016, routeMaxLength = 8, ratios = Some(WeightRatios(
    cltvDeltaFactor = 0.15, ageFactor = 0.35, capacityFactor = 0.5
  )))

  val AMOUNT_TO_ROUTE = MilliSatoshi(1000000).toLong // 1000sat

  val networkDbFile = Paths.get("eclair-core/src/test/resources/network-db-mainnet-20032019.sqlite").toFile
  val dbConnection = DriverManager.getConnection(s"jdbc:sqlite:$networkDbFile")
  val db = new SqliteNetworkDb(dbConnection)

  val channels = db.listChannels()
  val updates = db.listChannelUpdates()
  val initChannels = channels.keys.foldLeft(TreeMap.empty[ShortChannelId, ChannelAnnouncement]) { case (m, c) => m + (c.shortChannelId -> c) }
  val initChannelUpdates = updates.map { u =>
    val desc = getDesc(u, initChannels(u.shortChannelId))
    desc -> u
  }.toMap
  println("Test ready")

  test("find 200 paths between random nodes in the graph") {

    val g = DirectedGraph.makeGraph(initChannelUpdates)
    val nodes = g.vertexSet().toList

    for(i <- 0 until 200) {
      if(i % 10 == 0) println(s"Iteration: $i")

      val randomSource = nodes(Random.nextInt(nodes.size))
      val randomTarget = nodes(Random.nextInt(nodes.size))

      val fallbackRoute = connectNodes(randomSource, randomTarget, g, nodes, length = 8)
      val g1 = fallbackRoute.foldLeft(g)((acc, edge) => acc.addEdge(edge))

      Router.findRoute(g1, randomSource, randomTarget, AMOUNT_TO_ROUTE, 0, Set.empty, Set.empty, DEFAULT_ROUTE_PARAMS) match {
        case Failure(exception) => throw exception
        case Success(route) =>
          if(route.map(_.lastUpdate.shortChannelId) == fallbackRoute.map(_.desc.shortChannelId)){
            println(s"Using fallback route!")
          }
      }

    }

    true
  }

  test("find 200 paths using random heuristics weight") {

    val g = DirectedGraph.makeGraph(initChannelUpdates)
    val nodes = g.vertexSet().toList

    for(i <- 0 until 200) {
      if(i % 10 == 0) println(s"Iteration: $i")

      val randomSource = nodes(Random.nextInt(nodes.size))
      val randomTarget = nodes(Random.nextInt(nodes.size))

      val fallbackRoute = connectNodes(randomSource, randomTarget, g, nodes, length = 8)
      val g1 = fallbackRoute.foldLeft(g)((acc, edge) => acc.addEdge(edge))

      val x1 = (Random.nextInt(97) + 1) / 100D
      val x2 = (Random.nextInt(97) + 1) / 100D

      val high = math.max(x1, x2)
      val low = if(high == x1) x2 else x1

      val wr = WeightRatios(
        cltvDeltaFactor = low,
        ageFactor = high - low,
        capacityFactor = 1 - high
      )

      Router.findRoute(g1, randomSource, randomTarget, 1000000, 0, Set.empty, Set.empty, DEFAULT_ROUTE_PARAMS.copy(ratios = Some(wr))) match {
        case Failure(exception) => throw exception
        case Success(route) =>
          if(route.map(_.lastUpdate.shortChannelId) == fallbackRoute.map(_.desc.shortChannelId)){
            println(s"Using fallback route!")
          }
      }
    }
    true
  }

  /**
    * Creates an arbitraty long path that connects source -> ... -> target through random nodes. Conjunction channels will have
    * high fee, low capacity, recent-aged and higher than average cltv
    *
    * @param source
    * @param target
    * @param g
    * @param nodes
    * @param length
    * @return
    */
  private def connectNodes(source: PublicKey, target: PublicKey, g: DirectedGraph, nodes: List[PublicKey], length: Int = 6): Seq[GraphEdge] = {

    val iterations = 0 until length
    // pick 'length' conjunctions and add source/target at the beginning/end
    val randomNodes = source +: iterations.drop(2).map(_ => nodes(Random.nextInt(nodes.size))) :+ target

    iterations.dropRight(1).map { i =>
      val from = randomNodes(i)
      val to = randomNodes(i + 1)

      val shortChannelId = ShortChannelId(
        blockHeight = 600000 + Random.nextInt(90000),  // height from 600k onward, those channels will be younger than the rest
        txIndex = Random.nextInt(2000),
        0
      )

      val desc = ChannelDesc(shortChannelId, from, to)
      val update = ChannelUpdate(
        signature = ByteVector32.Zeroes.bytes,
        chainHash = ByteVector32.Zeroes,
        shortChannelId = shortChannelId,
        timestamp = 0,
        messageFlags = 1,
        channelFlags = 0,
        cltvExpiryDelta = 244,
        htlcMinimumMsat = 0,
        htlcMaximumMsat = Some(AMOUNT_TO_ROUTE * 3),
        feeBaseMsat = 100,
        feeProportionalMillionths = 10
      )

      GraphEdge(desc, update)
    }

  }

}
