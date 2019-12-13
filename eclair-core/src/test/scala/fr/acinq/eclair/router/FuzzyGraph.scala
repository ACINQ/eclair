package fr.acinq.eclair.router

import java.nio.file.Paths
import java.sql.DriverManager

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.db.sqlite.SqliteNetworkDb
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.Router.getDesc
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import org.scalatest.{Outcome, fixture}

import scala.collection.immutable.TreeMap
import scala.util.{Failure, Random, Success}
import scodec.bits._


class FuzzyGraph extends fixture.FunSuite {

  val DEFAULT_ROUTE_PARAMS = RouteParams(randomize = false, maxFeeBaseMsat = 21000, maxFeePct = 0.03, routeMaxCltv = 2016, routeMaxLength = 6, ratios = Some(WeightRatios(
    cltvDeltaFactor = 0.15, ageFactor = 0.35, capacityFactor = 0.5
  )))

  case class FixtureParam(networkData: Map[ChannelDesc, ChannelUpdate])

  override def withFixture(test: OneArgTest): Outcome = {
    val networkDbFile = Paths.get("eclair-core/src/test/resources/network-db-mainnet-20032019.sqlite").toFile
    assert(networkDbFile.exists() && networkDbFile.canRead)
    println(s"${networkDbFile.getAbsolutePath}")

    val dbConnection = DriverManager.getConnection(s"jdbc:sqlite:$networkDbFile")
    val db = new SqliteNetworkDb(dbConnection)

    val channels = db.listChannels()
    val updates = db.listChannelUpdates()
    val initChannels = channels.keys.foldLeft(TreeMap.empty[ShortChannelId, ChannelAnnouncement]) { case (m, c) => m + (c.shortChannelId -> c) }
    val initChannelUpdates = updates.map { u =>
      val desc = getDesc(u, initChannels(u.shortChannelId))
      desc -> u
    }.toMap

    super.withFixture(test.toNoArgTest(FixtureParam(initChannelUpdates)))
  }

  test("find a path between two random nodes in the graph") { fixtureParam =>

    val g = DirectedGraph.makeGraph(fixtureParam.networkData)
    val nodes = g.vertexSet().toList
    println(s"Nodes: ${nodes.size}")

    for(i <- 0 to 100) {

      val randomSource = nodes(Random.nextInt(nodes.size))  // PublicKey(hex"02feac67caa47b1d718c685c885d062b7eb36383295ed701a476af503306691884")
      val randomTarget = nodes(Random.nextInt(nodes.size))  // PublicKey(hex"03b6a0e7cb7b2829384fc3ac95eeb3f19c71334d9ab32381572bf03b9313c66db7")

      println(s"[$i]   ${randomSource.toString()}  --->   ${randomTarget.toString()}")

      val fallbackRoute = connectNodes(randomSource, randomTarget, g, nodes)
      val g1 = fallbackRoute.foldLeft(g)((acc, edge) => acc.addEdge(edge))

      Router.findRoute(g1, randomSource, randomTarget, 1000000, 0, Set.empty, Set.empty, DEFAULT_ROUTE_PARAMS) match {
        case Failure(exception) => throw exception
        case Success(route) =>
          if(route.map(_.lastUpdate.shortChannelId) == fallbackRoute.map(_.desc.shortChannelId)){
            println(s"Using fallback route!")
          }
      }

    }

    true
  }

  private def connectNodes(source: PublicKey, target: PublicKey, g: DirectedGraph, nodes: List[PublicKey], length: Int = 4): Seq[GraphEdge] = {

    (0 until length).map { i =>
      val from = if(i == 0) source else nodes(Random.nextInt(nodes.size))
      val to = if(i == length - 1) target else nodes(Random.nextInt(nodes.size))

      val shortChannelId = ShortChannelId(
        blockHeight = 600000 + Random.nextInt(90000),
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
        cltvExpiryDelta = 144,
        htlcMinimumMsat = 0,
        htlcMaximumMsat = Some(16000000),
        feeBaseMsat = 10,
        feeProportionalMillionths = 1
      )

      println(s" ADDING AN EDGE ${from.toString().take(8)}  -->  ${to.toString().take(8)}")

      GraphEdge(desc, update)
    }

  }

}
