package fr.acinq.eclair.router

import java.io._
import java.nio.file.{Files, Paths}
import java.sql.DriverManager
import java.util.zip.{GZIPInputStream, GZIPOutputStream, ZipEntry, ZipOutputStream}

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

class FuzzyGraphSpec extends FunSuite {

  implicit val serialization = jackson.Serialization
  implicit val formats = org.json4s.DefaultFormats + new ByteVectorSerializer + new ByteVector32Serializer + new UInt64Serializer + new MilliSatoshiSerializer + new ShortChannelIdSerializer + new StateSerializer + new ShaChainSerializer + new PublicKeySerializer + new PrivateKeySerializer + new ScalarSerializer + new PointSerializer + new TransactionSerializer + new TransactionWithInputInfoSerializer + new InetSocketAddressSerializer + new OutPointSerializer + new OutPointKeySerializer + new InputInfoSerializer + new ColorSerializer + new RouteResponseSerializer + new ThrowableSerializer + new FailureMessageSerializer + new NodeAddressSerializer + new DirectionSerializer + new PaymentRequestSerializer
  implicit val shouldWritePretty: ShouldWritePretty = ShouldWritePretty.True

  val DEFAULT_ROUTE_PARAMS = RouteParams(randomize = false, maxFeeBaseMsat = 21000, maxFeePct = 0.5, routeMaxCltv = 3016, routeMaxLength = 10, ratios = Some(WeightRatios(
    cltvDeltaFactor = 0.15, ageFactor = 0.35, capacityFactor = 0.5
  )))

  val AMOUNT_TO_ROUTE = MilliSatoshi(1000000).toLong // 1000sat

  lazy val initChannelUpdates = loadFromMockGZIPCsvFile("src/test/resources/mockNetwork.csv.gz")

//  writeToCsvFile("src/test/resources/mockNetwork.csv.gz", initChannelUpdates)

  test("find 500 paths between random nodes in the graph") {

    val g = DirectedGraph.makeGraph(initChannelUpdates)
    val nodes = g.vertexSet().toList

    for (_ <- 0 until 500) {
      val List(randomSource, randomTarget) = pickRandomNodes(nodes, 2)

      val fallbackRoute = connectNodes(randomSource, randomTarget, g, nodes, length = 8)
      val g1 = fallbackRoute.foldLeft(g)((acc, edge) => acc.addEdge(edge))

      Router.findRoute(g1, randomSource, randomTarget, AMOUNT_TO_ROUTE, 0, Set.empty, Set.empty, DEFAULT_ROUTE_PARAMS) match {
        case Failure(exception) => throw exception
        case Success(_) =>
      }
    }
    true
  }

  test("find 500 paths using random heuristics weight") {

    val g = DirectedGraph.makeGraph(initChannelUpdates)
    val nodes = g.vertexSet().toList

    for (_ <- 0 until 500) {
      val List(randomSource, randomTarget) = pickRandomNodes(nodes, 2)

      val fallbackRoute = connectNodes(randomSource, randomTarget, g, nodes, length = 8)
      val g1 = fallbackRoute.foldLeft(g)((acc, edge) => acc.addEdge(edge))

      val x1 = (Random.nextInt(97) + 1) / 100D
      val x2 = (Random.nextInt(97) + 1) / 100D

      val high = math.max(x1, x2)
      val low = if (high == x1) x2 else x1

      val wr = WeightRatios(
        cltvDeltaFactor = low,
        ageFactor = high - low,
        capacityFactor = 1 - high
      )

      Router.findRoute(g1, randomSource, randomTarget, 1000000, 0, Set.empty, Set.empty, DEFAULT_ROUTE_PARAMS.copy(ratios = Some(wr))) match {
        case Failure(exception) =>
          println(s"{$wr} $randomSource  -->  $randomTarget")
          throw exception
        case Success(_) =>
      }
    }
    true
  }

  // picks an arbitrary number of random nodes over a given list, guarantees there are no repetitions
  def pickRandomNodes(nodes: Seq[PublicKey], number: Int): List[PublicKey] = {
    val resultList = new mutable.MutableList[PublicKey]()
    val size = nodes.size

    (0 until number).foreach { _ =>
      var found = false

      while (!found) {
        val r = Random.nextInt(size)
        val node = nodes(r)
        if (!resultList.contains(node)) {
          resultList += node
          found = true
        }
      }
    }

    resultList.toList
  }

  /**
    * Creates an arbitraty long path that connects source -> ... -> target through random nodes. Conjunction channels will have
    * high fee, low capacity, recent-aged and higher than average cltv
    */
  private def connectNodes(source: PublicKey, target: PublicKey, g: DirectedGraph, nodes: List[PublicKey], length: Int = 6): Seq[GraphEdge] = {

    // pick 'length' conjunctions and add source/target at the beginning/end
    val randomNodes = source +: pickRandomNodes(nodes, length - 2) :+ target

    (0 until length).dropRight(1).map { i =>
      val from = randomNodes(i)
      val to = randomNodes(i + 1)

      val shortChannelId = ShortChannelId(
        blockHeight = 600000 + Random.nextInt(90000), // height from 600k onward, those channels will be younger than the rest
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
        feeBaseMsat = 2000,
        feeProportionalMillionths = 100
      )

      GraphEdge(desc, update)
    }
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
    for (_ <- 0 to maxNodes) {
      val randomNode = Random.nextInt(nodesAmountSize)
      nodes += networkNodes(randomNode)
    }

    val updatesFiltered = initChannelUpdatesDB.filter { case (d, u) =>
      nodes.contains(d.a) || nodes.contains(d.b)
    }

    updatesFiltered
  }

  /**
    * Utils to read/write the mock network db, uses a custom encoding that strips signatures
    * and writes a csv formatted output to a zip compressed file.
    */

  def writeToCsvFile(location: String, updates: Map[ChannelDesc, ChannelUpdate]) = {

    val mockFile = Paths.get(location).toFile
    if (!mockFile.exists()) mockFile.createNewFile()

    val out = new GZIPOutputStream(new FileOutputStream(mockFile)) // GZIPOutputStream is already buffered

    println(s"Writing to $location '${updates.size}' updates")
    val header = "shortChannelId, a, b, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsat \n"
    out.write(header.getBytes)

    updates.foreach { case (desc, update) =>
      val row = desc.shortChannelId + "," +
        desc.a + "," +
        desc.b + "," +
        update.messageFlags + "," +
        update.channelFlags + "," +
        update.cltvExpiryDelta  + "," +
        update.htlcMinimumMsat + "," +
        update.feeBaseMsat + "," +
        update.feeProportionalMillionths + "," +
        update.htlcMaximumMsat.getOrElse(-1) + "\n"

      out.write(row.getBytes)
    }
    out.close()
  }

  def loadFromMockGZIPCsvFile(location: String): Map[ChannelDesc, ChannelUpdate] = {
    val mockFile = Paths.get(location).toFile
    val in = new GZIPInputStream(new FileInputStream(mockFile))
    val lines = new String(in.readAllBytes()).split("\n")
    println(s"loading ${lines.size} updates from '$location'")
    lines.drop(1).map { row =>
      val Array(shortChannelId, a, b, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsat) = row.split(",")
      val desc = ChannelDesc(ShortChannelId(shortChannelId), PublicKey(ByteVector.fromValidHex(a)), PublicKey(ByteVector.fromValidHex(b)))
      val update = ChannelUpdate(
        ByteVector32.Zeroes.bytes,
        ByteVector32.Zeroes,
        ShortChannelId(shortChannelId),
        0,
        messageFlags.trim.toByte,
        channelFlags.trim.toByte,
        cltvExpiryDelta.trim.toInt,
        htlcMinimumMsat.trim.toLong,
        feeBaseMsat.trim.toLong,
        feeProportionalMillionths.trim.toLong,
        htlcMaximumMsat.trim.toLong match {
          case -1 => None
          case other => Some(other)
        }
      )

      desc -> update
    }.toMap
  }

}