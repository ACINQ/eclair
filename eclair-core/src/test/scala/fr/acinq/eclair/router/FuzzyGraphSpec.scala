package fr.acinq.eclair.router

import java.io._
import java.nio.file.Paths
import java.sql.DriverManager
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32, ByteVector64}
import fr.acinq.eclair.db.sqlite.SqliteNetworkDb
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.wire.ChannelCodecsSpec.JsonSupport._
import fr.acinq.eclair.wire.ChannelUpdate
import fr.acinq.eclair.{CltvExpiryDelta, ShortChannelId, _}
import org.json4s.jackson
import org.scalatest.FunSuite
import scodec.bits.ByteVector

import scala.collection.immutable.TreeMap
import scala.collection.mutable
import scala.util.{Failure, Random, Success}

class FuzzyGraphSpec extends FunSuite {

  implicit val serialization = jackson.Serialization
  implicit val formats = org.json4s.DefaultFormats + new ByteVectorSerializer + new ByteVector32Serializer + new UInt64Serializer + new MilliSatoshiSerializer + new ShortChannelIdSerializer + new StateSerializer + new ShaChainSerializer + new PublicKeySerializer + new PrivateKeySerializer + new TransactionSerializer + new TransactionWithInputInfoSerializer + new InetSocketAddressSerializer + new OutPointSerializer + new OutPointKeySerializer + new InputInfoSerializer
  lazy val initChannelUpdates = TreeMap(loadFromMockGZIPCsvFile("src/test/resources/mockNetwork.csv.gz").toArray: _*)
  val DEFAULT_ROUTE_PARAMS = RouteParams(randomize = false, maxFeeBase = 21000 msat, maxFeePct = 0.5, routeMaxCltv = CltvExpiryDelta(3016), routeMaxLength = 10, ratios = Some(WeightRatios(
    cltvDeltaFactor = 0.15, ageFactor = 0.35, capacityFactor = 0.5
  )))
  val AMOUNT_TO_ROUTE = 1000 sat

  //  writeToCsvFile("src/test/resources/mockNetwork.csv.gz", initChannelUpdates)

  test("find 500 paths between random nodes in the graph") {
    val g = DirectedGraph.makeGraph(initChannelUpdates)
    val nodes = g.vertexSet().toList

    for (_ <- 0 until 500) {
      val List(randomSource, randomTarget) = pickRandomNodes(nodes, 2)

      val fallbackRoute = connectNodes(randomSource, randomTarget, g, nodes, length = 8)
      val g1 = fallbackRoute.foldLeft(g)((acc, edge) => acc.addEdge(edge))

      Router.findRoute(g1, randomSource, randomTarget, AMOUNT_TO_ROUTE.toMilliSatoshi, 0, Set.empty, Set.empty, Set.empty, DEFAULT_ROUTE_PARAMS, 123) match {
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

      Router.findRoute(g1, randomSource, randomTarget, 1000000 msat, 0, Set.empty, Set.empty, Set.empty, DEFAULT_ROUTE_PARAMS.copy(ratios = Some(wr)), 123) match {
        case Failure(exception) =>
          println(s"{$wr} $randomSource  -->  $randomTarget")
          throw exception
        case Success(_) =>
      }
    }
    true
  }

  // used to load the graph from a sqlite db file
  def loadFromDB(location: String, maxNodes: Int): Map[ShortChannelId, PublicChannel] = {
    val networkDbFile = Paths.get(location).toFile
    val dbConnection = DriverManager.getConnection(s"jdbc:sqlite:$networkDbFile")
    val db = new SqliteNetworkDb(dbConnection)

    val channels = db.listChannels()
    val networkNodes = (channels.map(_._2.ann.nodeId1).toSet ++ channels.map(_._2.ann.nodeId1).toSet).toSeq
    val nodesAmountSize = networkNodes.size
    val nodes = mutable.Set.empty[PublicKey]

    // pick #maxNodes random nodes
    for (_ <- 0 to maxNodes) {
      val randomNode = Random.nextInt(nodesAmountSize)
      nodes += networkNodes(randomNode)
    }

    val filtered = channels.filter { case (_, u) =>
      nodes.contains(u.ann.nodeId1) || nodes.contains(u.ann.nodeId2)
    }

    filtered
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
        update.cltvExpiryDelta + "," +
        update.htlcMinimumMsat + "," +
        update.feeBaseMsat + "," +
        update.feeProportionalMillionths + "," +
        update.htlcMaximumMsat.getOrElse(-1) + "\n"

      out.write(row.getBytes)
    }
    out.close()
  }

  def loadFromMockGZIPCsvFile(location: String): Map[ShortChannelId, PublicChannel] = {
    val mockFile = Paths.get(location).toFile
    val in = new GZIPInputStream(new FileInputStream(mockFile))
    val lines = new String(in.readAllBytes()).split("\n")
    println(s"loading ${lines.size} updates from '$location'")
    lines.drop(1).map { row =>
      val Array(shortChannelId, a, b, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsat) = row.split(",")
      val scId = ShortChannelId(shortChannelId)
      val update = ChannelUpdate(
        ByteVector64.Zeroes,
        ByteVector32.Zeroes,
        ShortChannelId(shortChannelId),
        0,
        messageFlags.trim.toByte,
        channelFlags.trim.toByte,
        CltvExpiryDelta(cltvExpiryDelta.trim.toInt),
        htlcMinimumMsat.trim.toLong.msat,
        feeBaseMsat.trim.toLong.msat,
        feeProportionalMillionths.trim.toLong,
        htlcMaximumMsat.trim.toLong match {
          case -1 => None
          case other => Some(other.msat)
        }
      )

      val ann = Announcements.makeChannelAnnouncement(
        Block.LivenetGenesisBlock.hash,
        scId,
        PublicKey(ByteVector.fromValidHex(a)),
        PublicKey(ByteVector.fromValidHex(b)),
        PublicKey(ByteVector.fromValidHex(a)),
        PublicKey(ByteVector.fromValidHex(b)),
        ByteVector64.Zeroes,
        ByteVector64.Zeroes,
        ByteVector64.Zeroes,
        ByteVector64.Zeroes
      )

      val update1 = if (Announcements.isNode1(update.channelFlags)) Some(update) else None
      val update2 = if (!Announcements.isNode1(update.channelFlags)) Some(update) else None

      scId -> PublicChannel(ann, ByteVector32.One, 2000 sat, update1, update2)
    }.toMap
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
        signature = ByteVector64.Zeroes,
        chainHash = ByteVector32.Zeroes,
        shortChannelId = shortChannelId,
        timestamp = 0,
        messageFlags = 1,
        channelFlags = 0,
        cltvExpiryDelta = CltvExpiryDelta(244),
        htlcMinimumMsat = 0 msat,
        htlcMaximumMsat = Some(AMOUNT_TO_ROUTE * 3 toMilliSatoshi),
        feeBaseMsat = 2000 msat,
        feeProportionalMillionths = 100
      )

      GraphEdge(desc, update)
    }
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


}