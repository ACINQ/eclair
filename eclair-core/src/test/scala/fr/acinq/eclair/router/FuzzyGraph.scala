package fr.acinq.eclair.router

import java.nio.file.{Files, Paths}
import java.sql.DriverManager
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import fr.acinq.bitcoin.{ByteVector32, MilliSatoshi}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.api._
import fr.acinq.eclair.db.sqlite.SqliteNetworkDb
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, Color, NodeAddress}
import org.json4s.{FileInput, jackson}
import org.scalatest.FunSuite
import scodec.bits.ByteVector
import scala.util.{Failure, Random, Success}
import scala.collection.immutable.TreeMap
import scala.collection.mutable

class FuzzyGraph extends FunSuite {

  implicit val serialization = jackson.Serialization
  implicit val formats = org.json4s.DefaultFormats + new ByteVectorSerializer + new ByteVector32Serializer + new UInt64Serializer + new MilliSatoshiSerializer + new ShortChannelIdSerializer + new StateSerializer + new ShaChainSerializer + new PublicKeySerializer + new PrivateKeySerializer + new ScalarSerializer + new PointSerializer + new TransactionSerializer + new TransactionWithInputInfoSerializer + new InetSocketAddressSerializer + new OutPointSerializer + new OutPointKeySerializer + new InputInfoSerializer + new ColorSerializer +  new RouteResponseSerializer + new ThrowableSerializer + new FailureMessageSerializer + new NodeAddressSerializer + new DirectionSerializer +new PaymentRequestSerializer
  implicit val shouldWritePretty: ShouldWritePretty = ShouldWritePretty.True

  val DEFAULT_ROUTE_PARAMS = RouteParams(randomize = false, maxFeeBaseMsat = 21000, maxFeePct = 0.03, routeMaxCltv = 2016, routeMaxLength = 8, ratios = Some(WeightRatios(
    cltvDeltaFactor = 0.15, ageFactor = 0.35, capacityFactor = 0.5
  )))

  val AMOUNT_TO_ROUTE = MilliSatoshi(1000000).toLong // 1000sat

  lazy val initChannelUpdates = loadFromMockFile("eclair-core/src/test/resources/mockNetwork.json")

  test("find 500 paths between random nodes in the graph") {

    val g = DirectedGraph.makeGraph(initChannelUpdates)
    val nodes = g.vertexSet().toList

    for(i <- 0 until 500) {
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

  test("find 500 paths using random heuristics weight") {

    val g = DirectedGraph.makeGraph(initChannelUpdates)
    val nodes = g.vertexSet().toList

    for(i <- 0 until 500) {
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
        txIndex = 0,
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

  def loadFromMockFile(location: String): Map[ChannelDesc, ChannelUpdate] = {
    println(s"loading network data from '$location'")
    val mockFile = Paths.get(location).toFile
    val listOfUpdates = serialization.read[List[StrippedPublicChannel]](FileInput(mockFile))
    println(s"loaded ${listOfUpdates.size} updates")

    listOfUpdates.map { pc =>
      ChannelDesc(ShortChannelId(pc.desc.shortChannelId), PublicKey(ByteVector.fromHex(pc.desc.a).get), PublicKey(ByteVector.fromHex(pc.desc.b).get)) -> ChannelUpdate(
        signature = ByteVector32.Zeroes.bytes,
        chainHash = ByteVector32.Zeroes,
        shortChannelId = ShortChannelId(pc.update.shortChannelId),
        timestamp = 0,
        messageFlags = pc.update.messageFlags.toByte,
        channelFlags = pc.update.channelFlags.toByte,
        cltvExpiryDelta = pc.update.cltvExpiryDelta,
        htlcMinimumMsat = pc.update.htlcMinimumMsat,
        htlcMaximumMsat = pc.update.htlcMaximumMsat,
        feeBaseMsat = pc.update.feeBaseMsat,
        feeProportionalMillionths = pc.update.feeProportionalMillionths
      )
    }.toMap
  }

  def loadFromDB(location: String, maxNodes: Int): Map[ChannelDesc, ChannelUpdate] = {
    val networkDbFile = Paths.get(location).toFile
    val dbConnection = DriverManager.getConnection(s"jdbc:sqlite:$networkDbFile")
    val db = new SqliteNetworkDb(dbConnection)

    val channels = db.listChannels()
    val updates = db.listChannelUpdates()
    val initChannels = channels.keys.foldLeft(TreeMap.empty[ShortChannelId, ChannelAnnouncement]) { case (m, c) => m + (c.shortChannelId -> c) }
    val initChannelUpdatesDB = updates.map { u =>
      val desc = Router.getDesc(u, initChannels(u.shortChannelId))
      desc -> u
    }.toMap

    val networkNodes = initChannelUpdatesDB.map(_._1.a).toSeq
    val nodesAmountSize = networkNodes.size
    val nodes = mutable.Set.empty[PublicKey]

    // pick #maxNodes random nodes
    for(_ <- 0 to maxNodes){
      val randomNode = Random.nextInt(nodesAmountSize)
      nodes += networkNodes(randomNode)
    }

    val updatesFiltered = initChannelUpdatesDB.filter { case (d, u) =>
      nodes.contains(d.a) || nodes.contains(d.b)
    }

    updatesFiltered
  }

  def writeToFile(location: String, updates: Map[ChannelDesc, ChannelUpdate]) = {

    val mockFile = Paths.get(location).toFile
    if(!mockFile.exists()) mockFile.createNewFile()

    println(s"Writing to $location '${updates.size}' updates")
    val strippedUpdates = updates.map { case (desc, update) =>
        StrippedPublicChannel(StrippedDesc(desc), StrippedChannelUpdate(update))
    }
    val jsonUpdates = serialization.writePretty(strippedUpdates)
    Files.write(mockFile.toPath, jsonUpdates.getBytes)
  }



}

case class StrippedPublicChannel(desc: StrippedDesc, update: StrippedChannelUpdate)
case class StrippedDesc(shortChannelId: String, a: String, b: String)
case class StrippedChannelUpdate(shortChannelId: String, messageFlags: Int, channelFlags: Int, cltvExpiryDelta: Int, htlcMinimumMsat: Long, feeBaseMsat: Long, feeProportionalMillionths: Long, htlcMaximumMsat: Option[Long])

object StrippedDesc {
  def apply(d: ChannelDesc): StrippedDesc = new StrippedDesc(d.shortChannelId.toString, d.a.toString(), d.b.toString())
}

object StrippedChannelUpdate {
  def apply(u: ChannelUpdate): StrippedChannelUpdate = new StrippedChannelUpdate(
    u.shortChannelId.toString, u.messageFlags, u.channelFlags, u.cltvExpiryDelta, u.htlcMinimumMsat, u.feeBaseMsat, u.feeProportionalMillionths, u.htlcMaximumMsat)
}
