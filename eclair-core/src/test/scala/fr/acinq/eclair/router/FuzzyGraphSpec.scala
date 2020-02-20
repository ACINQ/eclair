package fr.acinq.eclair.router

import java.io._
import java.nio.file.{Files, Paths}
import java.sql.DriverManager
import java.time.LocalDateTime
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

import scala.collection.immutable.{SortedSet, TreeMap}
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

  test("find 500 routes between a fixed set of nodes") {
    val g = DirectedGraph.makeGraph(initChannelUpdates)
    val resultString = new StringBuilder

    // with payment amount = 1000 sat
    resultString.append("## 2 random nodes, payment amount 1000 sat ##\n")
    fixedList.foreach { case (node1, node2) =>
      resultString.append(s"$node1 -> $node2: ")
      Router.findRoute(g.copy(), node1, node2, AMOUNT_TO_ROUTE.toMilliSatoshi, 0, Set.empty, Set.empty, Set.empty, DEFAULT_ROUTE_PARAMS, 123) match {
        case Failure(exception) => resultString.append("not found")
        case Success(r) => resultString.append(r.map(hop => s"${hop.nodeId} -> ${hop.nextNodeId}"))
      }
      resultString.append("\n")
    }

    // with payment amount 100000 sat
    resultString.append("## 2 random nodes, payment amount 100000 sat ##\n")
    fixedList.foreach { case (node1, node2) =>
      resultString.append(s"$node1 -> $node2: ")
      Router.findRoute(g.copy(), node1, node2, 100000.sat.toMilliSatoshi, 0, Set.empty, Set.empty, Set.empty, DEFAULT_ROUTE_PARAMS, 123) match {
        case Failure(exception) => resultString.append("not found")
        case Success(r) => resultString.append(r.map(hop => s"${hop.nodeId} -> ${hop.nextNodeId}"))
      }
      resultString.append("\n")
    }

    // 1000 sat from ACINQ to a random node
    resultString.append("## ACINQ -> random node, payment amount 1000 sat ##\n")
    val acinq = PublicKey(ByteVector.fromValidHex("03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f"))
    fixedList.foreach { case (_, node2) =>
      resultString.append(s"$acinq -> $node2: ")
      Router.findRoute(g.copy(), acinq, node2, AMOUNT_TO_ROUTE.toMilliSatoshi, 0, Set.empty, Set.empty, Set.empty, DEFAULT_ROUTE_PARAMS, 123) match {
        case Failure(exception) => resultString.append("not found")
        case Success(r) => resultString.append(r.map(hop => s"${hop.nodeId} -> ${hop.nextNodeId}"))
      }
      resultString.append("\n")
    }


    Files.writeString(Paths.get("simulation_eclair_master.txt"), resultString.toString())
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

    val filtered = channels.filter { case (_, p) =>
      nodes.contains(p.ann.nodeId1) || nodes.contains(p.ann.nodeId2)
    }

    filtered
  }

  /**
    * Utils to read/write the mock network db, uses a custom encoding that strips signatures
    * and writes a csv formatted output to a zip compressed file.
    */
  def writeToCsvFile(location: String, data: Map[ShortChannelId, PublicChannel]) = {
    val g = DirectedGraph.makeGraph(TreeMap(data.toArray:_*))
    val updates = g.edgeSet().map(e => (e.desc, e.update)).toSet
    val mockFile = Paths.get(location).toFile
    if (!mockFile.exists()) mockFile.createNewFile()
    val out = new GZIPOutputStream(new FileOutputStream(mockFile)) // GZIPOutputStream is already buffered
    println(s"Writing to $location '${updates.size}' updates")
    val header = "shortChannelId, timestamp, a, b, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsat \n"
    out.write(header.getBytes)
    updates.foreach { case (desc, update) =>
      val row = desc.shortChannelId + "," +
        update.timestamp + "," +
        desc.a + "," +
        desc.b + "," +
        update.messageFlags + "," +
        update.channelFlags + "," +
        update.cltvExpiryDelta.toInt + "," +
        update.htlcMinimumMsat.toLong + "," +
        update.feeBaseMsat.toLong + "," +
        update.feeProportionalMillionths + "," +
        update.htlcMaximumMsat.map(_.toLong).getOrElse(-1) + "\n"

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
      val Array(shortChannelId, timestamp, a, b, messageFlags, channelFlags, cltvExpiryDelta, htlcMinimumMsat, feeBaseMsat, feeProportionalMillionths, htlcMaximumMsat) = row.split(",")
      val scId = ShortChannelId(shortChannelId)
      val update = ChannelUpdate(
        ByteVector64.Zeroes,
        ByteVector32.Zeroes,
        ShortChannelId(shortChannelId),
        timestamp.toLong,
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

  /**
    * A fixed sorted set of randomly chosen node pairs in the network, 1000 nodes in total
    */
  val fixedList = SortedSet(
    ("0354ad9584f02dbbf2cb7564b45dbbd60dd8aff361f6f7052cafae04e5084a0e06", "035be98895c4d4e3b91fbb88d27af0fa68de6023e3a6eae675cc8332d29f299af4"),
    ("02d78a24da9acbfef12e1b59f8153207863c01a05c7c7f2908952a026b5206595a", "0208253298ed55d449e496a79022c98055045395aedb74acd9b8c566ce517ed0e0"),
    ("023827c1b49803a4e52be9be16d368ef52909f7e44ff228166198cf7e198302951", "03405f42490b333b4875e6a4185168fe3d0382e33a637cece699d23749c16cc148"),
    ("0361f2ba330e76a0ebae598a1002c5e4eab3cfe266c32c19080e063608c270a88a", "024ae2136f960b87b97f11f7a6eb2ed2f346d933c068c7ca642d423f436d116abf"),
    ("03d7a756ee0cb50258879afbedcb88995ac54a499c9f8f4d64b073f2bae6262012", "0318f342854597b2a9af86ae818b794264b3c32e07626ad402a5c47b673482d105"),
    ("0360a41eb8c3fe09782ef6c984acbb003b0e1ebc4fe10ae01bab0e80d76618c8f4", "030474c6abc3ec16c163592480865f76a3c18ef206540a3554889973fbd5be6375"),
    ("02aa9a936f80a9a59bcb6270f15384f358b97a530d5b8cc67a3e1c18c7f723d742", "03793750d37ee8a50541d8aa8b3a7d4984cf1055752b62c4f42eb980e0fab9c674"),
    ("02272bd12e59324d0f2b231fb88f134b57eb26dd100d2cc007df50f18aa12455a2", "03b7567cb6a168d177311b4f3639226211173da50a5eed40fb5bd98150cabe6dd1"),
    ("028df6000ced1a2c46d51c459454285e74868c0f5482e810f338c7d5cecc160a25", "0352255f530c4b7dca4e7dc7e451935f3caaf183afd70d0d792f8f315edfad6cae"),
    ("03d94ea16e8d37e6702157cad71c63dfd9196c8b82d6984d7a1904f88f5c003458", "026bf1967b9b6296f3115749dc3d3624d6ee9ecb6ba339d37e4fd265021d465603"),
    ("028f7a1b624f622f89430f7c0f38acb7e41e4f64c6735852b035097a327e5637e4", "0399ca358e5e8168dd4c891a504cc689d741fd7884cee6ce669fac482625acce17"),
    ("03bcd9870e11e57fd6d92d4ae20f23a221d741fd0b8ba003039eb44ca1f78dd70d", "03fa8e31f94f5dbceaa185d2e8ff39949be479a8ccd4c5a5add7a4581dda8b196b"),
    ("02cac20d1bfabae843287a6dff23a6fbc4eacc0a9317f1d860991c25861266a31c", "02858856bf3449ddf2c778aa4e1ed449205d69bac417d2c553373171edfd998fd9"),
    ("02c2fe44214097ddc746abd1ed49070267b9eb388e446f06769952fa3ee4f4e876", "03f5b3dce85b7dc3d7e9e8a7faa32906971d5b5b10fb9d9aad69d36b4f8cd3fe9a"),
    ("0360836e515a0adf7ef11fcd798cda199746ee0b702e09d0d582e0c8ef74ceb600", "038317eb0af98795c87bf5156d58a97cfd7628c55cde5120122d0246d3157d11ae"),
    ("02e560640e988a1f7f05adca8208dd329ecd85d931ecd549f3a3b3cca3d13251c4", "026cd8fdb157cf093d524b6489d80e25e9884b6ec2571d60acac9352208a912816"),
    ("02bed1812d3824f7cc4ccd38da5d66a29fcfec146fe95e26cd2e0d3f930d653a8d", "02d13b9e4bdc10f49f38d98f40b9a30cd089276dda588613079b712987c42adf76"),
    ("026c2683f3a85822cbb262baaed39d3985cbe3b8e9838135edef7cab7776a1df0e", "0208ab52377f7976c82bc20cc0d8edc295e046714b1f781ee9cdbd3ead368401a8"),
    ("0286f3de56f3cd0d47698e92a6cc05c7507cd25c5c0617e444cb569e27c2275d49", "02595222407bb5cba50b51800d8fd2432d38d37eeccdb8fd9d8cf08e8141773c74"),
    ("03fab7f8655169ea77d9691d4bd359e97782cb6177a6f76383994ed9c262af97a5", "0290d863a9a022435f1794742167425dc660cfae87dc26999f2adb773c990e8a69"),
    ("02ff67d4d10652a12a91a519d1d0f17ec9bf05df6b4df22813a1866a2d19df7507", "024570675f274a9812350f5e849bb24eab70ec646f910e64983da43f8878e011ff"),
    ("036c4f89a5e99775c6df2242032bc3a23a5a546392c3e3b6b8ae320dc59a54867e", "0378029af3dd5d6c23bfa08b35ebb80192b1e02d3000f13ec893174d3463025ef9"),
    ("03495177d7e4d92f32288ba25e49f40ed6350f58450da1f6ad9dfbc357d3c3f7ef", "02a35e94b403dead4d08a00e3df3577682e34f5c4ba39476497be838dc76d15a09"),
    ("038d1dc54e81febe0f24d676b6078b803b12e8f7c19001a4a8c5afbfd502a5ce39", "03effdecc68084b5416b8f4bb49f4613893e51c4a632064c8ee38d710e53d20987"),
    ("02df69a28a83d63eb831cb6ba743477bf9ddfbe4222b4a3cef1e06fae63e6320de", "02c921ef88d90e8760b31227ccc82dd8d4bb8082d636935abcde0cde4827f1ebb9"),
    ("03759e3840f8b8dd907c16acbce9ab006d8e76502dee5327e0331c8f22672cc9fb", "02434b5b3077ff3064398fd85a29b6e077c2ee9b07a3284cca8b5f961a2cf1fa9e"),
    ("03ab2f6c79cae3d9c02ec1422cf2fccfe8c9b3e11bdddabf099348d9c0cfe164aa", "0217a06be02f137bfa6e038b6d2e6a8c3fefb26a07b37c8b4ce96534629b2d3877"),
    ("02b9481fa492ea730eef90f29954ee978321e7e3fefde23c0fed99c7b5ff5d7093", "02f2db91d9c63aeeff2b2661b5398e4146aeb2cdb10fa48e570a2c20a420072672"),
    ("03e1657b87d5f798067961f94a5f26166131ad896e56c47cf641cda4f3cfc3b731", "03ddc590a76f36892b9fd55b79f548c98a8926e8e1f267c6a9bf7063a40fd4c506"),
    ("0274ef03632cfb45a628a7f9fe20fd02db56980ebd6ef5f4c6e98f707fb3eebcff", "03f21fc2e8ab0540eeb74dd715b5b66638ec1cd392db00009b320b3ed8c409bd57"),
    ("027dc3b3ad13556101489a6fc4b7552b04c49c5fba3bf9aff804b7f31887bfba84", "0352b2651964338267b8cb003a1ce135ffc28c2f814653836693cc828f1e5bede3"),
    ("02356908491a41e6d585adc2ed2b7a1dfd7eae2b02ce2e6d8a86e10d35f2117c6a", "026d84c83601a98788f5bcc9d151a2e0607222cd922a8e07a37e8e71fe62e963ed"),
    ("03bf28c98bc98164bd6cd41ccdcf797a4fcde855c4c95ba7ec66c3f107f2d60af5", "02b873b95a7b654fd70aa7af6f3d37bff83317f5db6a4f32c9b45b6f4cb1b065dc"),
    ("038fb340c538ec7768f78526accbf68062cfd678829c3b27bad665adb1bf3a2e57", "0316f69073e64c698b7b7742666684b72963a3a10429cba9849214c1b598221465"),
    ("03b80fca5590c310f3989b00922160af9fff9019615c08e393f64dcf2847a42ea4", "03bde87b103c01db067fe2c7de87b526b95641d9959b573daa107595569a94fe43"),
    ("0300e252841c39b48f67a786a07b156eacad93c73ac31578b3872ddf49a05a09c0", "0230101e66a8df1b33c2df3c0c47ec8ce518f42a7ccf798c5f5ce9432b7f17a40b"),
    ("0286fa7fa44fdf0e2bd8c684e1b6e166d9fa95e2e2d2f4778d3d7de7ddbb51b1b7", "032a4ca7df22951948f48c8edd98fd19807d383ff294c7cc7ae653ded63bf8c3f5"),
    ("03248aa461ccddda04e30dda8ec94f0c70bdc12ca2494dd91d0e11e396954b1ba7", "025a596f3023eb914d6041bbb75d9d274b8131596aa9c6eecff72fa45de9927fbc"),
    ("03c630bcb7595c64ec2041a60bb59d53cd3eb8777ae516a1e3b5ee6a4286c21e80", "0348eb55a7cd2332c3bcf5cf1bd81732d96d99caa56290de57eb02fac3c997d217"),
    ("03a14a5ab6b23347a6e33fdd4377b359df38707f991a86a50fd8d2f189cde67a2d", "030738a835d3a5b56efbb66f6a849c86465424d5c1527bd5f6db5ddcb08b99987a"),
    ("02204f9caa8ed94c58ac4ae973601b4ff1ea31af86828ecbfbfc88613f060038ea", "03f84b4d4de050515ac58dd2b0ccf2c10d6a69178d9b0e0c70066d0d8cffedddd4"),
    ("035c5f43dc9e1afbe9162b395c52298f9cd1767c792fd695b42ce183f4472d8c4b", "032a435797e14784172598b62a8b889b400b5c7dcf51295135c0d82a72f6c6f25f"),
    ("0341d8de46ed6acb5e16c618bfeb5789d649ecb8df0186453b3b13aae7b4c05f59", "03e0bead102aa8cc650fe1b0581e1f1965578623c18d439fc15e79807fadffc2b2"),
    ("032b1cda51d110342b02905ba2da1aee54d24dd1f32e6401ffc41c7af9eb5bf75b", "0351a4a271354f671a6ed76ace5326de6cfb858e5989c8cf310abfef73af93602e"),
    ("0306aec8c054034e9490fbe398915987de1e1d9f272626496dbc7cf3a2e289281f", "03398603de59d47f16bed1232bcc3da6715745e6609e84b33548755322af391f23"),
    ("0262b1712a940a10f8cac1806ad0703d24fb8becf1383e00f59eeda5243fb66eb7", "029671acc9ecfae0af2699239b9e63ca3ce6bf87fb58c222cbc1d758bc72e71ec5"),
    ("03880ce1e70500e3a7ef36ebef65e34d6ec429d5ea0c10c4306bea51f102da6d8c", "02a54deb8d0f11d47c6f55cec5e673063c9fad2619559e8d87ae3eb4c381668449"),
    ("0382019f91e0d8a5e497849052b5bedad4329bc88ca24b5dbf499c0bea1af5b6c5", "036141acc2aa4fa2b38b6a46f86a8205d51835d137a1d601b3b80f9c4f63888a4f"),
    ("0281917ca7f19a08bbd3ca378a53b4d7c7bda5e976692a07180cc736cfbff7f411", "035a0d21758480330ab852703483ea8a90cf44f75c5f48ecde9b3c8844c29d813b"),
    ("02e751b58118e82dc9fb5cac69ba192f096ae54bc1b857a2f18cc90a3ab4e8445a", "03e375b3585c333f1275642a16dc10981b3d3ebdb4b5832ee2ea3dbaebf1600582"),
    ("0200d29f8c0e56629e5a72cb38081b8876fa0fa5ea901888037262a803e1d93ed6", "022f45ae28425e697c2c516c185887075bdfa75b11ec3346a16307b8be73a4d220"),
    ("02247f88ea46563f64f03ed174f33f27544b64cec85475a8c3961637596254aa54", "02b33a23440fe60ebab1aa737f1b3de5edbb0353f41f0dac79319d7652a9aed1be"),
    ("034649e353ede974529a15c3c40e3d0a9c33c461e6caece1b241dd9bf8c9d5273f", "0296bdf95048ea0f4e7828e69f1c0eca44ae236e1fc7b2445706697a6044a52a71"),
    ("03594eabc9699ad8f9c8fb5cb521d23b848d982e15376f37496baa686a33e9a23a", "0256f10d2a42627f584ab97697c1aaa3b90f38952fa922e1a453f4604a7d994605"),
    ("0230ca47a0eff2ec283c86acffb6afda09353d766bcebcd04f68033b2fdf39210a", "03c2d52cdcb5ddd40d62ba3c7197260b0f7b4dcc29ad64724c68426045919922f0"),
    ("02cd01ca0d51bb1ab20a943dfaca90c86992558b973eb8de56dd365ca2728e2292", "025d03912061771608cab55d84cefdecc3bba766085371cf63b454ce1f31fd441d"),
    ("031b8d80403bdf6486e2995811528419c59a5bcbf98325f8e9b22888c3be5a863c", "0298f985809ad42627b36af9fa1d57c4f668a6ed7ac9c300cd9ff0a27bce441e56"),
    ("0395b258853997fffb893956db1c0831446ebd27834a227c61198b217dccd91388", "02b8e9576f1ade4f339f31802d9cf6186a7e424b5889dcaa12ff9bf860602dd32b"),
    ("0351b6c92b4f1016eceff7842d22a29e66989a5d4d5543a6e81d3ef4635ad0cff2", "034c298c43cdb7b0c77cdf49ef30df33f34de7b179d1a14b3d43ffcf85e02b3914"),
    ("02398fd3bd9b43a86c49683865da7789e15744e92849bc7cee85c7b7b1729436e0", "034c4d5987d5b3b4704132ce5fefe044f7615c7882ad0ccccbf127ec29729bc7e6"),
    ("0208fbcafddef7e746ff727c2244b854a7f18a52f9ce9a03724a190cc5b999313a", "0376e6440662e741a188b1acd885907397587dad96914c451df1f86ca7d617b94e"),
    ("0397b318c5e0d09b16e6229ec50744c8a7a8452b2d7c6d9855c826ff14b8fa8b27", "0213a19d4548881cd8203ddfc34b7344514991637bfd5e6d2e4fc9e83e6dd5fcef"),
    ("03de67ca859242c0d5db2db50b5724bd2f351962147f92baa53a17fa8b6a0fa1ee", "035cf905f74151e5ab64d4cb785d028392db15ac0cea0e470382c055f486be3c01"),
    ("0283202f67ad31e79b45693f1a8fb7b379112adefec5a7bc7d3ee7672be36bf5b6", "034ae7db38b1e6bb5a199d701bb206c85645d015203cd5dd8c5a3dd5558c6bb707"),
    ("0243208ccdd171d3ca9e96d2535c667160e39fa37e8528c0006c09051a6c7f8edb", "0258113e6c6d66242bc9245ed7bdb9c8c530bf036d041a6a0e60f1209b36449219"),
    ("03f14195708b4b498c1fe414e748e522963d07b9105bf1578033fc7320e316d9b0", "03fc94ff9a9299b8fd593f6f597b81c0459f6be2f55bf2db4c9cbf56d7b891cee3"),
    ("026529dbe2199f083146aed0d53da2c4a3cfb3985371aaa4c597b1a309e7495731", "0344a727dfb9dd23ea179a5b2362c4e9212ff7591b8d7ed2ecb4d5fb6f60dc88b2"),
    ("02a40155178007eed45c336eb9d539cd4234a0e1717834c2626c84a78c5e059ddd", "035e3830484f74d867166f9efc7312a104e1fcf7daed9d3c8b20d7079380626e43"),
    ("025f879b1cd27a7664bba20d25bca70360cb635117c3a71cd77e5572d2cc0628a8", "033c89e9e4e80afe474a2ec9ba6f5a90107022893de7e0a13db7e2b86f41f7ed3a"),
    ("03619d89f577fb20dba8603092de628f0d34733d0598b4f7818cc61655edc84acd", "03e54da1f607eea4f3bc8bb4493b67975d9f8c517bd752bf5d61bbdca3e22e2bdf"),
    ("029ccef76c381b34e0dbe6efc73eed6e1e42ad266539cc7b0979b507bded34fed3", "02d73b667e695a81acf63f0f393c8652c0cca47b31754627237395c249828526e8"),
    ("026c72dbf5019c10cc3a90448be1624fdc6021beb4fb689d4a91f0cdc3f7614d28", "02125aa4ada72c695e28ad87e159bd1caf51584273e8b608c2141e3229e7d74372"),
    ("036a98bd339cd26d6335f4d78bd7d377316b90743f1a745631ccaafac02206b3e9", "0290ed68150c33b96f7369b4c10b9680fbc5130421ffe8c2ee253603ccbec4732b"),
    ("02c0a6e4f7e6d01edc5aa14f5445cd8c87c478ad1c72fcb8b594d4989cc2a1fa9e", "02be3064b93f5ada2ebc0a50a6d21f4987ffcb24a60c1c5afcef3ed89ebae007d5"),
    ("026d9b9c8e55b435f117496e6a885fba7bf76530a5aa6f8f61304f84f9a31dda09", "03be5c53f444e4aae7276c7bf47a9d54738894825e7ac0ce51caa73a9efe5d7b13"),
    ("03a830531053653d9988731de174d8cf340e8a1742df01a81d02f4c5556c514736", "0247122c742495db05584d3fd252ba0f03f312886e02901e4c88bb9ccbae3ba9f2"),
    ("02b6f799b1fbfb5bfa81380b466a586e8e47107cf7ee8b35f189bc08cdc540849b", "02c5aa0e6153eb00ebe075192a72080234bad4f5794b9d0d22ab7e9f18358839ba"),
    ("021a57a09dfa10f5b997c91c22ad45451470240671ca663bf0b900fc35801703b4", "03a39309a892ed2deba3221ffba424dbeda3964c0875db7e1f033593b603b0f7e5"),
    ("032154b2cb4d775459f918b838149b17907dd4d8c6457ab32f55e17fa06785bfb0", "03cb5f69a294d7bef871db44f01bb582d7c934bea2e5dd58669cf42a6123124a84"),
    ("02cdf83ef8e45908b1092125d25c68dcec7751ca8d39f557775cd842e5bc127469", "028bda4d8c0d34a0d777b8488d68d44ee1f2ed5006d8305a8650841af04ff01c17"),
    ("03c16b9bdbed5e7688d82d2b7c7a778e6b0d1cd63f481c44ee03d0f346b0405c80", "02c50b61e7c5f76a8dcf74817994c2ae0b85c1992646f1166459937b52bfd59b5c"),
    ("03245d3ecddc9254a8cae1ff0794bb4535785c3bac2b5b91fad6dd955e8f427eaa", "02fda0ac0ac76498b5be23652bee525521768cebd9d3eedc174bc9548d9d2850c8"),
    ("023dbae4008c7a814470d68c98254c5f90bfb886979d7aaee94eeac49453256449", "03fe2a6e92c43f59112eb36adf31729f0ab7a3f6609b30f62b17390fd44f0294a2"),
    ("039f730c025c6a2c8b66eece44350d0b17ef03a62ef9acab9154c91af5346872dd", "027cfb1cdb29fe76bf9f829397dd44646e692dca36a1c14ccb14d0de297a9174d0"),
    ("03774878eb997f238fb8ce1529e7edb5079a9fcbc6bde4da05f1be1687287c434e", "0322281ae81ce7d28fad0656eddb148e2e933b8c73025687563b9f4f6289799bad"),
    ("032690d7f75628de267261b9f9a6bdf589799555621007b686715690aa5bafb06c", "030995c0c0217d763c2274aa6ed69a0bb85fa2f7d118f93631550f3b6219a577f5"),
    ("03deab570615ad04c8f3fcd0dab7ab5ef14eae944a6e538ff03034ccd9caaf0858", "02c1f09ec838d3125c9ac39bcdb0e71e272f7cd11fb0e4c8daa920df2ec119eb80"),
    ("02b3349209dd7a4afd68e88bb19a870ef61c8aa44547330909b5a32b2fd79f00b5", "038e45be382603e062b43a1ac290160110aafe8f94abcedd6cabf90d8df55307e4"),
    ("023fe0a6b162d1400338c2c55f643e8feddc303b06a5700d5d5a861a2db654d659", "0313dade42dab619a592f2f21d3a6c10224743660edf08a7bd0ba5fd238b2c5eef"),
    ("0205fcfa041fdab19d46f5a1dd071ff67c096e86c2db4c5539d623016aaccc315c", "02537e3176a9140eadf4e297ff482290fba471ba08c2107a0bc36873f8b389c463"),
    ("03b17a5af08c266a4dd645a399c65d9aaeb059b2729a8aa5e0cc0c76597953d57f", "036d4c098b3554157ecc8b162daafe250eb8d89e6f18ce471b264cca536e3596b0"),
    ("020a95f21bd8841a4db4c1f56773503261e3735e977eaf74a88dfac5020563f526", "0266f7ae868e642a94d187b6153f41a71995975afbe821d64d647d7b8d46d91ec6"),
    ("038e134d9edb923422250ef041b08006404b9733601510d5a0e1a5ca413d4a58dc", "02fb65bcf294f47423be676c274afd100c29c6ff5863052fb38e767f3d60775a46"),
    ("02ad921d465e6ecfe493e90864275ffcf1b70c6050c76e6c33701b0c3686d687b9", "034bcc649f6d8f23abc397f6b51253dd151e973f2997a99929e695089f09fb3c39"),
    ("039fcde7836a25c0d37f24ac271128115e589bcee7d059d468ff2e59cc3fdf5dbe", "03bb88ccc444534da7b5b64b4f7b15e1eccb18e102db0e400d4b9cfe93763aa26d"),
    ("038599c3715114219b7927bcd8dfcf361c500be5b9b4113c8b82a12b252313b2c2", "0324f9a56a8272ff691994918692cfb9a5d77d1ca534559bc0720571aec491e5dc"),
    ("02b974116054be17576e6e8ed4110422e5a9d8775156fd829aa2eeb8aded87b290", "022d191b8b3eddeaa2ccfa94fe617405f3eebb2e7369467ea54ebe9bee87a725f7"),
    ("03f1741291061f6982ca1d606dff52ac5cb54ff28b1c437c8d466f5d059fc0728d", "02fd4115a3cf4e2d4f03f8947f2ba6366088d7a193fcd382b139b5a68d4531421e"),
    ("02e113dc70965e608ba18097e8d436a12b5496adf2245792f4e58168f815f3e882", "024e8048d516473f5d2032eb2a221faf955060e54fd5ac3bfa153649620487fa0e"),
    ("025b0c4707515c1754b399b142149944707ffda62af6173cd222aea3d321e239e7", "03cea4ea2716f4b3b2a0e87e4500481029a2d53b1bd08444b8f265bc51df2c1ff4"),
    ("03a006503fd4b2ab5b5862404956d4943c3ff8f8b48e6f5e17966cdf53f2ac1aad", "0246b7010a84e5335a606355a638acfc6d35de98dca8c9931e940908e0de008e04"),
    ("03160372debc2d5229364e36e7361ff6241dc5863fd45038d5e1af6af9984384f8", "02a26d57f1e246f62afce2c1c14e63225b69b302ecb93a384dbe2183c8fda32416"),
    ("03e5684ed7a5c61c0364ba2794ff1242209242238f42dbda513b39c91aba7ad11b", "03bb23b522d2a5d9c239addb6b788c4c1b0ded20a6a6d87c1b041fcd40fc21d2d6"),
    ("03842a085b8b89da9db708b83aae8e9baff7a0a6f34368a439aa8d2ca3e408a662", "02a05b852ec22abda45de11ed16ad77b4ef2e8c61a1ae94f620fa74f18c1dba1fa"),
    ("03ba97b0e0b0a48a7743ff545b4d13626e06d85679741bf930dba16ddd5c7862b3", "03c6254ffbe78b0db165c265d4e01e00a4aa217ab6a639b69261b41cb7dee22f28"),
    ("03b8aa80ca17206d12a35a4998b8a7d6b59caab3f31b6f03b1bd1e88132a179e8a", "03286b7d87c5df90f2656b8292de3b9297bcfb1d8a2c3db8529fbf13c1fa542bdb"),
    ("038f41f4a910c82a960ac9c8e5cc9daf729550bfb0b6a13d86edc2f79bff2e89ef", "03ed1abcd0fda387e3b7386ead8d78b456593428c96b902ad4aa7bcc572541ab27"),
    ("035736692722a47389d6be965d73a50b1ba63233a1b975aa55db7f90446f5708cb", "0295ea8a519a6f79a8b772d9365d14d3493a3ca1811667f134f09be8279170e053"),
    ("03ddb48a96540275fef19e614612b799616656d6e904b3dbbd1c0dd039c510d197", "03e898ec3d8cce70fcb2f085d6466c0691eb46e80ff9353f2f62fe473bce52216d"),
    ("033695f67d4b32066e6f9a7808b9bc4b8ed9a921c4417da4753084339d7c947da1", "03ddeea3e65f463f90e1ed5469a82451f9f2d2a38e930ab1f1962f6b87eec04753"),
    ("030048285665e5358e2bf8e46bb9a923102555d55a0dad8de854a3884ed7f1fbf9", "0319b565f91688b1efb9f8d43ea2b043ee781292a2737fa8a1e4d403b1b13e76b4"),
    ("037584aa529af342287a0f91427df19f497925b93f9e1a73c48af2fe5cd16a1542", "027d06253d4519ad7197347fc3be0d6805d9960da2f0eea110f2dcd86e678c8701"),
    ("034ea20ae28455d2ebe24233e725f7819738a8b4f96409541c905a5a168df3351b", "0267b1754e3f46a7bbe1b4493cf60c588cd1701548bb9262a8b922c7ca2d89e995"),
    ("03f0848805ea0de6c981d02322502c7e9427c8ff66a9b1b2a2b8b06c544a31ea31", "03205b045b0126c0d3fb826a38b9befc4babf51689544908c704d8e51fdec75ffb"),
    ("0307765e9a86057c8ff8e8127ad2a05c4ec03812f978b836c646c7b57acb0d69c5", "02a5a95669adcb170dc85a70eef8bdd3e60b459d83bb47b6aac3e17c58faffc919"),
    ("030d9dd2abcd6c159dbcc928314274f38d6b095a7ea936db3d0badfff5f98843e9", "03fd397019d66ddf1e54dd6b71a7ab0767faf9628a904333e8450f4fc0875d7a7e"),
    ("034745a4188e25db811efae00ed6428ee83dd840fd6c6161db4865d5517b2ebfbf", "0312bf5f0279d315fde0a831019ae0a47461e9cfb65376fee367d987ebc72b1e74"),
    ("0370f6269ca41ac1808ccb11794ef727c91670f6a3c4f4b233d67b9e6e2fe2c5c0", "02db10fa894ad7d6a9a1fe80c79ae38b975377f6e986f90f96e81023ffca464eac"),
    ("03d7c2045223dea00373459154b43d0baa140bdb0a618f7f81a566c3f928ed1881", "0361325ef4fca50d8f68aff3252449c31cb441403454313f324dbf730be24c3fac"),
    ("02e4e7607c55110a5f1a0c38f188b6817cfae7b1b161c2137879ccce785444a0ab", "030bd1ba4d9ae157bf2b317ade77feb6e8d879abcc534c44f00da0c07f010bf249"),
    ("0306a16b9955de84dd0db5c307eae184411e26063eaf8fd44ec5f068b8c73490b4", "0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3"),
    ("03a32888c5db46e13d1aa07cb03036f130415477a9ffb09d58c1cd54f777fbbdd9", "022c3b4bdf935d254a3b3689f0496b8ca55655bf7c5032664899fec252f993bc5e"),
    ("03085f40d6aa0d3e9eb1216edca883d83ae3b7815881f27d4f703001aa1a826de3", "02e13839b00f838d830181519eb4eac1ea49050bce13943fb343742e0aa70ff06a"),
    ("037570bba28823dc107cb9b4664fd6d23605868aa6bc415fdf52d5a5328b388cc9", "03cbc081bea4020f23b595b813b27cd044b1245c63d2383efb36814fe3fb982733"),
    ("03c7be17b94473f0b515541f4ed4aacceea9be6a5c15eecdd4c4bcf256bafccb08", "031ce29116eab7edd66148f5169f1fb658fad62bdc5091221ab895fe5d36db00b2"),
    ("02d362505e2d7201a9ba342beda0ace7bde0ef24d60ae9b5aa43e21f13df9f1968", "03104b543e4e66dfef0084dcd29fa354f21eb5fb8bfb9af5a9d55ac6fac3f7e7fc"),
    ("02db311935e2289b716bc9e8b732739e348939a9d9c039287c53d227eb95e650bc", "02d9d24dbd796ae755a20b5c5dfdfe82c2b97e5ed892b3ad35f48ecc5c4e2be959"),
    ("02dbd9298c0e87143276440838c4c3b6adfd183fd19c9181cf38a550c6ad216b51", "03ba0f6e588e032090e3a3c45ea338e12bc5bcac0b1366c6a1038689578c8fd1c0"),
    ("023ec3d1fa35f7fb8996374cf1848c1a40788df013551c5510c75617222bd2dd2d", "02edbab1f30224e4ccc4d70eae7dc5c06e742c1e0d79a375125ef9200592932ba7"),
    ("02bdec497f62a689e1a1094814fc02206ff98d1ce08c564e06269c136d4ecc3e4c", "021038bdf13575ae9783e547fbe385ce9a9e8ff7fb2d9ecfc557fd863745c12b49"),
    ("032068aaa2e220a26bdeab7d416bb37efa60b0d6c81247736a3de199b96a2b668e", "031ff2a08e6ada312980fc0cc4bbe380bc4c28bdd0fd76f4ca2753c47c91c61bed"),
    ("03df3f0a2fd6bea5429a596461ce784c922b2981ada1af89cfefcd9ccfb16c16a7", "023ebc455298c0e19689a675617b5892f0d648d118f91fe34a01f2aaac93e64334"),
    ("0325ad1acd887b7b4ee433d16553c2aa71d5e355bc330908f712700d0fbbf06ec7", "03f93c3786b3cde969e370d1b14a830de14887f93de10eccd48c8f42c5b5841080"),
    ("02214448836607586d32c42e46f555092e82363c62bb4dd06b9f625926f5f4a739", "03d91ffaf5c8ca34c8f01dfaba24654965f41b650ce67c031dbb49b0be1e965bca"),
    ("026d40876fde9ab07d012fee4c6620fdb5ce3e866185b7a6e9a3bd34386e6f6fb4", "03a45be5b499702903042ab8388151c59c0c5a624d73fe40b0d87780ca75c2f720"),
    ("03eed97adae0fc601f2fa3e12944f847969ab624d23014f4c3896f287270eecb0d", "03c4cfaf8373abf38ee4eb8ea4b064d140d29fbe20ec583e624f2ed0fa78154546"),
    ("031e3df7bc0626f38d1ac323bf1bc5f0642acb694b4f65d414411e350d6fd34aa1", "020d1cbb82676af152d87d5c2865f113936624c249bf9dcd54d4aeae52ffc8c5e1"),
    ("03deb7672c3db9e3bb0e5f9fdfe717dc12fff3311eb63dc2f0d38977730d03d642", "0256812a1cb2539a5500f3c1c20db5ed7626e3878a552e3356c032c7bec2b3060e"),
    ("02c3c538e2b4db05cc0190bb0a1c335c508c7472d9c03930acbab174b578b825ee", "03944919f1b2bf1f14a1c94a36c88476d916899e6e3632403b929ca11f37bbc7d7"),
    ("0264c1c5c416b481f4dfdc9b2fbfc7b56008461399fd3ee009dd677fd4d11a551f", "0382da438b762391fa69c857e13f431e53384039d804b4ae533a8b0f200f5894fa"),
    ("03d301eedc0949238bf919452ee7ef5c45bda4adbe17faba4037170b3573841446", "03c4485a19af0a3226b9d60c2af7ddb9789c0baa3107abf24b14211967ef1cb395"),
    ("03820d1537d31b122f3782048635603e1973d94f8f6536ddb3a6c2f666a5315512", "03ee1bf829773ee70054c6290e4186a0b056444e65d6dd720f86fb8134190c2ea7"),
    ("0240df8422414d60fd1727d9e5ac3851e7cfbbc02dba16f88b87113083ad9c5550", "02c69a0b4cb468660348d6d457d9212563ad08fb94d424395da6796fb74a13f276"),
    ("0331354daa3a95c3f27a522805e440d16cb7244983ca2e837e26b1759e7b19e755", "02f4d86b01fb06bb702707bef200e982bd3a71040410ad0d41128f3f6e653dd1d6"),
    ("02f56ce7aff3df6251572a8aad3ce7f370139dd7d276effbd8d1e058f5058d0681", "03ff89e6e0062dd2a798b0a9570478bb3218f72fb32bfe929418796238f854ed80"),
    ("0216b1bf5b3875e7d138950cf6d9ad135b5ccb18da75cc1e6f2601f759b8b41978", "02e708bcd8685044fec60699809f5e1d5523833e2c1aec3cf96dd1dedf0ba0db78"),
    ("02c2d7e11f38b46c863a6718d19d49f52e0859ddd4827192a7fd9d25ecd61657a4", "03a5886df676f3b3216a4520156157b8d653e262b520281d8d325c24fd8b456b9c"),
    ("03bf1b0492303a0be80bfe7cf4c896221465fb8eea7a9ef777f7ac1682d3ab4df8", "02c39f43c8fbdcac05b62e92b9f144b9719de14f39e6714a6a67f67aa4872c67a7"),
    ("02942b68065a656b0d7573aad66e54204aa8f7b22f2e8453e1919008f9c7d9348a", "031a060bc61b1dd9f8c0ffdf9d18ba22b9c95dd3c08f8e8c28f53f535a31f1ffe8"),
    ("039d5337f0c4afe82b7085be46480246de2506a094f3a6867979ca1b371cfbaa6f", "02dc4acf321e4ad61a07b4f3dd33ea93935290dfd66a12c599fab86143bfd88d80"),
    ("024cb8d75de288f7b4f36acc8877c98dd48e5174fe8db35a9c2331dc8ed76dd204", "02626f3294758e180ac67b792e403997fe77bed1df3de82026115eb325e5928bbf"),
    ("0327336968abfe97fc18cb25906cd90dd94c04a7073820426f4731e5781c16b838", "03d9366cb7fd24f4811483d5d2df0798779dc1ccabdcc080ea00ed11abda993e66"),
    ("02e9fa6b495d07ce19796b06b5c0f51a401dbb48935c3aafe54067c89299add0ed", "024e236a2dfe02cd4735efe609fb63d1c6ed32c0d99e66d16c6fb1c0e4487d7624"),
    ("0353325e099c2b657ca5c4bb975a20b0c3de1d2391dabe73f40484aac255628d22", "0249af59560a9950c1dafbe59650560334dab43df04022b1477d2263c4c88049e9"),
    ("03802f08967cdd0dac6b008ce27881695c2decd7c91392c97fa4fc067fb9d024dd", "0204c4b86bd2212883140f31da8829f4db1dced8864d9fe995182c2d9e332cf95c"),
    ("02b37013202570d6c89bf18fc783ea832e0b9ca469f90bfbb906ea376e36e75b7c", "0317b829c4cf34ad14f1cb250963649da0f42a74b972c87411bdf4ea23f43c4bf2"),
    ("02141e4062d5e7ddbd37233d8f01771bdb4d5ce4789030700ce36eaa512f783833", "02bd8d4e4355eef64735cf2d0bccb12647270bad0c615fc054e60bb28aa0b9bd84"),
    ("024136ddb00dd67b577993cd52742277d34da94c2eaeefeedd4ee32bbff99f6461", "03fe34e34b61b532fd8ea574e780cde80e02a96504f9600ef8404d8eb5a6b6d804"),
    ("02ea269e6c544be6f67fefff592a9af929b082db76a26747867e54b5ecbd893d5f", "02e98090a4180d431100e69e43aee3a2388169b6db6b159885fd47910ca20255aa"),
    ("02d1b799797534866accb3c88e2d8204c83aa9c1417e5d62e0f0c1339328c92e65", "02c0e39706b99ffdd0b27c209e3f2512c382ebe447f2c61586a8940c6c4efd02c0"),
    ("023509dec262771b4d1e1e12ca6e19db559e6832165bf069e61f9f8aa4bb7d0fb8", "0275848f93ba643dc7f2ef4594463200de7a45329660b647040da4c5ef75a3e16f"),
    ("03630ec1212282d24d366756139c1a0916f394cacd4e4f0346ca86fcb581b96a75", "02901c74676eeb0067bde0ac7c577f01a71e3bc628379aa3c8d00f805342fd2407"),
    ("02eb8aa5f4ed64fe3c6d6c0c5bc34bf3b27d99da64a6a2754068bb183528e4718b", "0367e072b7b6e40e5df3fbf8701bf1bc7efce021a5702dac7d0a9be7bb59f1f01c"),
    ("03197c91a41e6db4d1776b1e85c3cd8ac7f0d8186b677e467a8cbd7c8b1c75bcde", "03602eaf34ea34ac6c0cdb8efb18c136705b30ca3b8897f101bc96c4fe429a4990"),
    ("039c4249e620202add3c61cccfb406d4d4e56900ceaae12b4d2d298ee3e4838852", "03865e6864b62c0a194f03da62ca532d13530d8f3010d3c93bd6954299d3705aa8"),
    ("031ab26c31d74d0b4987f7c192b115425c404c53a0bd51085aa7e69975df009ea2", "034157be16f1d8ea468c3ad4115d543d3cf757ac51df60c9fd8efad69f9d20f189"),
    ("03919cafc20ef7c2e4bf49f8fda3b127944861f749a41496f96bf9a5ad2803cc00", "03dd80145ece2ff7d69114e2aa4b809c4e93135a869d5833c4b3d1b7b77294c5b8"),
    ("031e2f1f069072d8013a53a8dbc4a32051c194bddc9ce86cc1a308fc62eac13964", "03e03c56bb540c36b9e77c2aea2bb6529b907ece6c1395228c05459af13d0e2a5c"),
    ("021b6a273d174738eb808237ae3680a0d8b2ba088ad19449e35082b2642f756fdf", "036d1103b67ac8c4c376c133123abd69e242dec3be10b4f0d2f63177b526e4e8fc"),
    ("027e53e6a35c67d1535c89355254841ed82953b7fbbf5d0a554bd8cf4c94575572", "0309b984e585af52d4f95816d99eb9dcc62fdc1c3df4fd68865f88121a8d256170"),
    ("027fc588bedd4d4e97c64d3c8a77931f53c89216e2cb3c207b74ff4e19f062d0dc", "029602768fc5162f3e25e568190a76e13a782fa941c76aae1d3df56a93ccdce213"),
    ("0244fdfe89803d401b2a00bd80b8255df0a6d343abfc322d52b88b332aaf75ac53", "02333952daf3613ba138ae64bd757546af486aeea3c21b52d5b748e63ea8ac95a0"),
    ("02c68b87ac8c6c77d2a3e2b9d3b7dac00e0e3703e1834298e3a287e808d2bb29b4", "02e77aca86de54564ed1f96a0e2f503b90ccfc83e6bc0335371a784de7b6e2d6c2"),
    ("0303238e8f3e25a49da6c909452e320130f3f45e474df08dde7ca9a25f008b2b87", "024ed0e0d8ad5443cf1d20f765e58faadf75f73e51dd6103d3f359b98cebe6e140"),
    ("0253439edfc4be0e60ce93d6bbd133a960e5753a7a037955ecd01e2c10938e1c8f", "03b82978fe1a210f7cba11ad5e2eadf18a3a673ed964100bb4c366c5a571845e49"),
    ("027f7f15a352d3a810abe3fd4aec170b65418c68b57009e46c3d6617fc823dd2cc", "02b9400fe21553b6bbe064233247710fef1abb412bdf13b0ca9c94110e54609144"),
    ("025176c4be908ee06946c27991949391e50af961328469772dd04aead0f951f1cd", "037f94aae6f82eadec66a355e6d0c53e898f0ab82fcebb32d5b543388f98ac25f1"),
    ("02659867866aa19e7d6e72be3408b832ce28579322da603679054d753494da8d26", "02f86d855d968783d6e5f157b4bc99a0043f0ac809c486c386a764e7dd6637626b"),
    ("035277e90b16b227728767aa74b10f7f1ee79129281f2f4ec1211c89e3f131991a", "0337579aadc81356ed7f32587565e4c2a7f8d1561be8cc3bd976cbc6a409a4a71b"),
    ("027af98624687e3f6a51683344852d9eb122a51f49477434fe7392d1cadcf50539", "02ecf26a0ffb82bd7574b5bfebd3adfb68ab135c0652da78647a4472b08486a395"),
    ("030ab4c075b23fc025b3df0433ab0f78fd481ad194380ea4dcb2414007f4879308", "03fe41d3703d48a6b26d23ca245a2913be639a35e5a1283f76883dbb62c4e0f862"),
    ("029db3469cf8226a25de8b12b23c799f3e725dda9e065ff9da92a47378fbbf92ca", "02626318f968469fb1dcd0453536bbabaab8861be75d8cde7900e57aab1bd4f3ac"),
    ("03465f79f840a1169e2b4cb42d684e1723af861b8a82336aa72f9766643a6ef4e4", "02881faffa637dbf051593599d370385421e498585964a7b5b8016aa8329d27bf2"),
    ("038a8f39da8491ce6d53fc618459cfbb9ddc1fd273e0f3bbaf29f3551daa4b57f8", "020f48d0e53287bf12ad0f2e007278ef818569efb147d936a629e8355e35764d4c"),
    ("02016cd83b8638c08d702abdd48b44495cf957d5947a7e10701a53cc298a5e93b6", "02613a63a629c6b6bbb8a4c4b0e519a5c4d8255715038eef04b82a4b9b55ca98dd"),
    ("03d85e58805863c830cfd47b300060cf570003c0e4fc5a1bf5fb91aa0771b7a885", "03e336bf3ef3268b1f6e18ab563bb036d594bac758269cd40e0edf80a01b340cba"),
    ("029103778758aa48012a1395b1cc84e672af3a6d678180306b835931d985ff9f6b", "039481e95ee121dac3e0bd4f79a002432252c1fa91375789d2df4ee2ffc8ca3541"),
    ("021c958a5b83cd10d731216270fee35a3035ab9f42b53f9d0f47bda24366499175", "02d9bab9edd230e5447ebd9438dbbb564c4eb4afeb53564b6385b4d2cb1162a785"),
    ("03966d2d6ab9d8e780f76100b00631f02ba784d701aec7b3b610c0b13987bbc1e4", "03e5f092ab7089feffe0425cc56cf562a6adc413e195d9b4ecf87e1bcd6e0f5e48"),
    ("0307305c269a1ced7a5374ac21c420ca71056e9be54a60ea7679ceff10d4ac55f3", "0399a312311ad4faea5a57e4983766dc8f44a04627bc1dd446cc479fc0a194fe84"),
    ("02db61c479710cca4dcf4aaebb0e0a48e1e776676bb0a0fde4cb0b9645e52b6879", "039593e2b11f50bba8ec6372a8002e7381591b5f73689552c63c1ef4044823dda9"),
    ("02b9840eb6f3a64cfd2fe7a74dc74d3ca8510a19b4df038a171f142d85562b39ba", "023937a864b348b192e34cd0a0a9524f2225307b540317abd92800efac9baa56b8"),
    ("0208d142e09411fca84c73174f88e88bc02c2710726427a8c8a7bb32ccb1c9d9c0", "028c25ab618833dd31e16b90e1fd0ffb7e87a8dc177b02ab3c06fdcdf0bf887e88"),
    ("027469c8fb7a810a22c43a08d7c1e5e5b21355b4915e0e7657fa3c1d2f1f5735ff", "02990c5471b30476355ccf593824d959ab2a3e3be085aa1180743951f2d980f043"),
    ("03023090988c84c3566ff59c4fb17536c59279ace4f4c242243950d858787cdf42", "032c17323caa51269b5124cf07a0c03772587ad8199e692cc3aae8397454367d34"),
    ("03ba5701cf6cdcc92975f1899df48bf4770a0082d6b2a5a697e67f0949c53383c8", "02c79623006d6bdb4736b8d3cd9c7bbb1a8594e80591961983d31660c29834ef72"),
    ("025a1cba5457f9e789aeb9604bfd602b5d18b48014d4454316b7b6d74b56e76b1f", "03f8836ad5a6b6ad1cdba571ce67e611c1be23828066c31309a51facaff8f9535c"),
    ("02239c4e65d964b2eef0fa02c8722bccfa97f66716af34115f2a06fb8c575f48b4", "0297bf72f057c666107a1add41d0a14ef261aff4344c5e9a6c660cf99e916cbb50"),
    ("03301e633b25d769377bf75ce6b6ed2ec570270bc06c8c02bf33c5bd2aa47da098", "0268fb5ff483d584b81832b025b8ed122596d4b642171d71ab8b5893aa24eccece"),
    ("02a20247f515d978cbf9e9ce6a4287b5931d724068b7b88bbaead6380db3dd8e9a", "02401127b192e2cd944af5b6348e9f4d731e7a0de40cdb7cc4da0c039eee9f413a"),
    ("027d7f94667974b10d3e8330de403111229669273dc9024745d195ba035d746e57", "03150312f77e05ed4d06b8c1258bd34997ec9f42dff10c23e0ac9ea4f79304efae"),
    ("0258867235a07c074955885a6923149a9082c4a1ca5e587328988d75fd8f3fac7d", "03b31e5bbf2cdbe115b485a2b480e70a1ef3951a0dc6df4b1232e0e56f3dce18d6"),
    ("03d973769c40aee9eb6f0ea347c6e5d04d839e22dd2910ef4752bbbec78735faae", "028b3dc9fce972b0a171e7d91ef8ccd9cb7731438ec163c1bd072f047045d60043"),
    ("03c561e8d04f54a75f4fd6a52123ac029910b5193ac82b8732d8c3d6a58ffc129f", "028533290b957da6145d99825e811277d7cbd8f379686e147ab2fda9627be3acdb"),
    ("038e5c1c9b3892149c05bfb6980c5f250a362691ada5330b6c45f2e37ba5fc7ca7", "032f65f12d290b3af6d62acf3dfe0c7c0bc3561fb8e6057d0aa81b227e8b79d2ac"),
    ("025d3f013953c2f32f9cf27b1c66b6c25b915295983508ca1cde117d30f51d40cd", "0368a0d40bc24a19a9bb8d6e028b85078778cd4a39906050fed32f543b955d06a7"),
    ("02608bd929f9ffd9997d6ad8e03df49b82f2b02515c885780c3f4d87cc6b0a3081", "027cf99e95e346897a6f88212d5240fa790e3b4c97581bcffa354de10128998ce7"),
    ("02855fed8520257d2b29ea8daa0139d4e3910febfbe817207f3158423770db2dab", "032bca93286f787400b9822ac2dbacb1f7eb9a1fc98d321c8a53f3503e8155f40e"),
    ("023485c1f849476de941e95aed1adbd4ed3427f6041a4a5eaaf454047c0658bcfa", "03798c0b544f240f6aaa24ad235c1ab3a57179cb1431c7e9f378bd67d6f88ff6a6"),
    ("023007a18112b4f06f1e481fb01582a80762ac52ac1ff22abc61865289117999b1", "0305c998a3a39f72cd271dbe1786ee2fea4bdb7694fb7a11a17286c93954023e17"),
    ("0365dc280825324f35d0efde56c7259cb69e95ec28ddd6ec1f915323dd89de9b57", "021f201df8e916553acd1ed47cd0efa4cd3714c0d8f4b33423f7fb9cea54ae5d24"),
    ("02b3537b2096ccfc44bc12bdcc2e00b11338076d2550c614cb68e03ba1b387255d", "025bbebdfae7bc00b86433db8256b74283eed0e61304a5f56a7e11cd7f92073b8c"),
    ("0217cb7b5113467d756eff9a51971f203740a8fde713d4966ca951fe3a5e81fd8c", "02cda05df8b423306dbace1e6c5cc766dce7ce44a8f905e3eb32952d3be9608473"),
    ("03a628bb5091e600a0d36290bccbf5954b0861252cbb2432c95b56f14de1ab7368", "02f1f00bc83b33d33f9201c79a14aa131dc8fbda14f50e371ff3e1f2f4d6099163"),
    ("02eda0d90617d79d63b129452af9ee3df48a3e791b36022e82b3e88574e63be947", "02a2d98fd3ec1c8416f79fef65839a10dff83ee6b1efd87ec9ac7e4007ba057ad2"),
    ("02ba1e7a0266dea875bb11242b1a4f81411aa2bfc931993e043a293e6d7cbba7fb", "027388b2266aaf4d98dd54026e1f3d01dab3c5734debf57188442e84fe9bc9f7b5"),
    ("025731077fddc998300b4f358dfec833efdc412aa0bd78c06d5ab61963b60119fe", "03212d9093161e918dfd52862abeaed653ce58e8ee6524d46e5741f35bce1b9d50"),
    ("03244aa672f25d1c2ba7879a3ab87757727872698bf79c436f8435bd11e45a50bb", "028b892b15f5cabcea5165b236db0e36dc06553c323c84d3768867dac7c95e84e5"),
    ("03bcf72543046fd23c3dd5172c5dbf743c739c910b252228592fb6048b8e49dab0", "0227887f2335fa8bae5bb71c4fd24b086708446abc6973ce1af92ec9ed49c7d100"),
    ("03e3ae247eb613ca670d5b58dc6c5e224f79cb7de05940fbab70d9b40213deb31f", "02f273d3913ef936df398c537df63d9d0555ccbe8f7b0ce778182df898a78b32f4"),
    ("021e1a6d94209d154c92ddbfbf3a331fdc9bee52c771670f84612cd3dfc52bd9b5", "0226b3096fed6affd03e745d8340bfd54029f602a902372d4545fb76598735211d"),
    ("030d09e735de509d6784821117419c8cd20c22c018d8517cbd3d6b5de1ffc7d24f", "02ddc0e653386315299a8ca788c2e659f1ca6d96833c8abccdc7dcd84f4fad9700"),
    ("027390e3f364bd13aacc707c2e4bced66f21b3bebec46cde0ea0502c4cca0a907b", "029da80a069c1e6e854e127c022d4d6a725b3a8a5e7feb297e9ae1f336a2b74a8e"),
    ("023bf8ebe6e86969183ee2cceda58cabfcd5a90421290246409f42d7c32258fc4e", "02fd17656f394299d6b455827e406e54dec4f6f99f6b71a8d9785c263b8ab02888"),
    ("02df6d3454bc4c0cdd9b6e5f04451f53c48d22300d3066cf4a8fb49effd025c0af", "03a858dea8b0ece46ac5543dd89611fe8e17706bbc472722a585a8078eee9dd3cd"),
    ("02f7a2b766245e25039d386550a01b0eecb702e5524d41c7779b306e0f8d496746", "03c341bc04ce45730d91a003650d80a63f2f3f723a313cf9ec21b1e019f833a98a"),
    ("03fcd5306e6591551522aa52d37bd740fbaf6a7c93d016f38c1f071e55758971b7", "02e8867402fe367b719c3bb984c197af74d526e98cfcf5fd20ac5860b077352c54"),
    ("03c0cbcf53321781b202ed19a00d3fe92402e93ae7766a341f6cc96711f3739eac", "02b289f93133eea9b2268ea62f2f0acdc8465aa0fdaa59f4120a961440dfec6d2f"),
    ("02f3c32b3ecd2e258bdbeeab5748a47fce7f8b6ba4e169511fee5555f9c6ed94c6", "027d2456f6d4aaf27873b68b7717c8137aaa8043d687a2113b916a5016e9a880e9"),
    ("0373221d5cf9d958d3d22816b97b9fedc6c5437a968c4b4805ce71ee708431c4a8", "028c1f8d907879ee8691ddab3547e93c5ec2098cc0f38cc39c4ee989cb3a10ae71"),
    ("03adee7f4d4d8e12dc364676c0d124b5bdf6e5a4b187d05f98c4b312caa115824f", "02922b7b29105105f072b91b2e3d3bcdcd3c69c8b6b2becbfa4a67fa8abd6e58cd"),
    ("02e19a46649683b43ae66450e6b1d48516f943514e561569f5a8ecd8d7099a6588", "0252ace247fb99be552884ccff0032adfc96f46874111bfd152d5bc5ac881f4dd2"),
    ("023ff78ade39832e59918abcfc0e9ae833b9a9bd191ceec07dd236cb7e0cc8c1cb", "034d174bf8fee7b66e59a093b5703988082fe48e271237a47601d2f129d5ad74fa"),
    ("038eb6893f5b49e7362af61ee1398005a1cf0129c3167fcd05ee07d63670cd3bab", "02628d8e2f33869db7417c8da48947d441dcd839e0a3ce5e91f9c790d1131cf9f7"),
    ("02290714deafd0cb33d2be3b634fc977a98a9c9fa1dd6c53cf17d99b350c08c67b", "02c59145831a78bd2d9aeeef3b36de3d5ab8f548bd026e521907d09b6cd4ac9bfc"),
    ("03ca6c08d0a2399dc24f5e52c96ca3f8650988ca7a72f54de5da2f9fb40cddb541", "02b71aad0cdb6376f0ef75216d10a85052da0723af95481d6f8053b8b8fa51aeef"),
    ("020cfe991127e010836cf16429af817a6af3d1441c30726293a77adcbcd2081148", "03f8c846846aabbbe31970f776b59249d21b71170c83bd920b5304be52474e932b"),
    ("0346a1b8a2a85c56c5ff71b0881a8a66fc9e8c0a8f25738c04cb2155d15d9558c7", "0353da745b8c4c46d886b159e6b67cf6638e5bf4b145680c64244c092e9bb1b6af"),
    ("029485949616e3815a3d4880879d889052667b91ab966f095bcbfc903b2e2c68b4", "03a5f6541097beb9401d735dbb03921e242ab7158fc47ce9e2ff873074f2924403"),
    ("029ebf4f699a25d06f04d448bf23f0d2b2f5ef1a2429fa9887a3066831c446ba58", "03c1709405715450d746c6c6ef019e87c73904e2e5ddeae7c0ee463ed539700df4"),
    ("024300c307415b88acd0c027c5dcf405e5a59f8b19867ee253ff10093932559849", "0204630cc1c36426518889087eaf86c5fa7b88837d85a7f2139baef79a701be2fa"),
    ("0280d500886fd3bddb7160afa3d3fe41b94b74dd97853902c16d0575d2ecf8ff97", "03295d2e292565743a40bd44da227a820f8730877bc3dfadebade8785bcf355258"),
    ("035a2bab9508850f961dec02879314d8bd54fef051c7662cc07a8cedc1f3245ebc", "03747bbada2e94b4eff24f039546c94ddeff61a314438626b65a7948bfce5d5b92"),
    ("032ed16eb9bec4084b7bec502d105283c7145957221ceb2193817f46349aa0de62", "037719b7ae4de5e3d711255bd27585471c8101977f5d0867c633df4d0837e8b60c"),
    ("03d67f36c4f81789e2fe425028bacc96b199813eae426c517f589a45f1136c1fe5", "0354eb5641531f1a1e3bcfef21f5eaa8aad84ec921461a9ed3d1e4d3f75db4834d"),
    ("02fffb0baa1f8a64d92c8b4222278c33a9c16d0f37bba24104c678fd9e4e7f57d6", "02b576ab065016dc2eba02af14347ca70d5e920e79725767b1992a84b76a4c56ee"),
    ("03fd105d7df6595d5a36f016b8ff9ca74e5474e29593c4bba85f4d7ad4663bc5ee", "0349bf353b5449e9d4b08bab42a6ab7a57f0821a15a8d5f3017e3827b864e44964"),
    ("037bf73521971d5423ce640e1a15e2ab131da352e93c5bf35acf6402232321ea27", "031005b90058edcdb6a85e8071a5e9dd5bcd4eb0239355485b495e00e652a6b03a"),
    ("029d9cc5253b23cd24f5d6972150dd084f778519fca4e26633fb755e8c2392ec15", "02c16149d3ce6ccd670e2b719c9093f3cf61e3c775cd2f9fa879c97924c4b9d3a6"),
    ("02e0c7c42be040b4d7a13e7e0cd5b4c67ded1a78a308c68509a65a58f81ea51bd7", "02379f8ffd4e080f2a9ee5ada5e02c6be5625eb93dfadd45fb9aabe69ea97e3595"),
    ("0207b6076d08e71336aec4af9933651dc9894b89c13ff2c286a9cf3ccc233e9ca9", "02dbf16547a31bc3d89fbdee387accc19921df8edb3bed5dafe6d292d81d1e0b3e"),
    ("02c1129d1788b698324cf22ac52928a031abc23389ce62c0c45a2a9457f0e401e0", "0372b06465e770cf5bf39c65de8dc2425cf649a6eab00cf08d192f0edf35fa5cf5"),
    ("032a9cce9f5497434395d0db6eac0f2769525a26d0d67b08832e87cc9c00f23ea6", "02bfd4ec033e053a08c7b4cd3c7e0718f3929da9413ccc02dd2a06797a2b848653"),
    ("03cd8d692b963b5abcd451607ab7a8a9c1eeee69df1adb3c1ac575e1f8be3b8fee", "020c7f6d847bf0fc99418351a5c02b0d36babf833f0613216dff3e808ebedea656"),
    ("0318699928e2eceeaa96fb480b8563c8255b83d7206fff11d410c40df120f4a0fd", "02a8ef752d4c07b39ac16458fc02199f08e6856b4bca4c1b1ba2636ffacf74b99e"),
    ("02b3d2f5ac0bd668f3935ffe80d8afcbfc47702810f19f5eb79c800ba4f267b455", "02253120bd6f653f810c5597fe56f4e44a809a84543146f4795650a8b853115fbe"),
    ("020a59b83f13698e950d5c203bd59a1d3aa53cbf0d6c9f30f92bd590a91850bd35", "033868c219bdb51a33560d854d500fe7d3898a1ad9e05dd89d0007e11313588500"),
    ("0382a201b71dcfcdd9e9a35ba6ad0aa25dde8a6a322c8edf000db2a31f3f31f0a3", "02c2c50f159429c53523138bafa566cdc26db73af91b623707b33b722aba9665ea"),
    ("028b2251f344fad4647812250aa124f02c90f897a888fd0d331fa2c4cbcb6f0419", "036c0fa27b7f35d9801f7d0dc10b8a8dab6a621d8ca620e54851a42501b8efc83d"),
    ("036aaa27806a5e34c02c28011f3e2322d803d8202e2f485a564d786299b7b2f8b1", "0384a8884d0bf7525cc387884fc38ddf0e388b342ce4cd35335772259ba905a5df"),
    ("03aca93fccf150c2368df70034bda011fe6b0395aaf1fca2a9b6f92d72693e6ffa", "024d2387409269f3b79e2708bb39b895c9f4b6a8322153af54eba487d4993bf60f"),
    ("0383bcd5030220568a3fd44e08aabfbff877d27c04b949cc3e4aad4a6e0dfecbdb", "034a03d2a7e62e9e270d51acbcb3fdfba498f83ec9310b284442c810ea7151b3f6"),
    ("031b9abc330d6d798ff840a9c107cd4bc6c615273b86f6ff7ba4bdb259cc56e012", "021b7d3c5f49dcf9d2e00a1021394b62e9dabcbb3a549643f8b9f510fc77699e54"),
    ("02f08a9cfb9d7ed2cd5702aca018d6d663d72b555fd5a2ce9f37c0d14e34f6ad4f", "02c7bc1e5079d21b967874906710558393e42e2f998e5b9c2b15e0767ded87eca3"),
    ("022fc6fb0c0b1ccbcf8ff360b9e6aeb1c78f52ab7660e84996a5227d0a515ae6fb", "031d206b670071c5491f258ac6662e6d7ea5cc5d422677cd82e5a8236506d3ea62"),
    ("0358b16702a8562fd7ce4dcaf0df08e97e1665ceca15e2558663445b9c8a363224", "02cfda4768450a63d33120d08180a4aaf727a19d264262ddc88a6904b6f4766fd4"),
    ("0393b8910da3f3cae5036ad7277c5006005f8f16709eafb9454f9764311bc66d8f", "03a00049175db3b3eb61c446ee6bbf6320610b048f028f472e29fecf05ed0ff05d"),
    ("02e2a50e6a376bd09cb4b4c126fd0dae1ee3fe59aefac27e00c89bf7965b5a9b79", "021384cec84342b05b3f675188cbf98b76f1ea6a1e4625fea201b8355f0970806e"),
    ("0357d78ec7dac0bf049d494f2b1451c6a10608efd7432b4ef5f24ef17271d8ef9f", "03b547c368ff7cceb0b9a98d5e2dbf6ae045ee94602cda5e35f5cadc756b616dab"),
    ("038c736de97aa63b60bb58cca0945e65b36109f68ef47cb53a8002a059932936db", "02d5ebd3385840e6296bef346d69593f6ae80c649713d08c832feec81288d5f532"),
    ("035c7735413cb1fa92f8bfa3406c7f89a6ff31a93c72bb558da11d8b52d51fa47d", "038de60a0cb43eea67e0bcb6e207daebad312a245cedd8b09deb2c5ba4bb19f601"),
    ("03a0aaf1f9553dca05f8c9edd74c5ff6c5383337973a47015934dece259d646e3c", "026ba13a7a31fb7bdf7a5e592a2d82668c17d133dd9c5bd295ac1c199ccd3eca0d"),
    ("03c60c47a149c7233b4845244060dd52cfb913205f365c23c2bfa99319c842ac20", "036265cf7c7356b06b9d64a09dad1c7f7519971be475100ca893b2ff2c5120e4dd"),
    ("03e08816a37db285b7f65ad0fd50f1d3e9fff79f661de10c64843376d8566dde05", "034dd20a7b1ffa6b3c9e4ee7a910745fe66c703c3462ff5a469581a8ae91e116f3"),
    ("02eae56f155bae8a8eaab82ddc6fef04d5a79a6b0b0d7bcdd0b60d52f3015af031", "02d0d487572a10c1d4dc486f03f09205c657abc471d0f3258ce37b034b8c917797"),
    ("03040fb1acc87502adf74bf3653c0f567a51cd29d4592250a6389250ac8a6d65a8", "024b8a22f13d368e9dba7efdf679190ed97b823d3666717fdebdddaea464889aac"),
    ("02ad3fea735a00896607a626caddb03338f52c0651670bb05815401e5e74cc4cee", "02adccb7d7ab40b9c694d54643f73e332fc2cfb66f05c1479f083eaed66b34df59"),
    ("0207f999871315c74e83de1f15a0f6c4be12fc0eb4aeaeaed580cdb0102947a584", "0226dde84717cf1fce10fd796acbbfda0fb5c4b117be9cfd4dac0bf08e69c72e0c"),
    ("026b73b3ff65200026db80c580fa5004e79db4e13cffbcdd37cbff84bd5e16bfcd", "02bec1ddac2f98449da0e178db57e8d1a1d812c96e58aba15b491371d44d6e301a"),
    ("032434517e28f7b51665a525d5e11fa493bd4e30a59883b8156c2be4085f4aaf70", "02e7cda15016259bdd4cb50dd49a55d6766f30443e2ed9ed909f47e2f666303e65"),
    ("02087384bbcbab7b57756e8809d7741b3632b405a197cbcbb9270bd8fa9cea2dd3", "022a23f1333f982209477ea618a6a9c4a30360b0bc10aba1eaa2aecc5e4dda0989"),
    ("030421daf56787f0cbec4f5b8564ae37fa2ab3f28b30c711c8094c19c36e7a3388", "02d57dad035303775e416c959e4b31332fbe939e47272bb2207530134f121add47"),
    ("0249b5e183673d60e67de0bcefdb49db51ba28336da8c858a641f3f26a099bf133", "02282cfc83d4e4ac088a654184be62d7de0be6298e83af39431f9598c8c5631c09"),
    ("025537b1ae30299f02714199843650b58adf2ff59a013e40e56bb1330fefbe99df", "034f6643768d5c1b0568023459b9c2e1e6ed3db5dafe8fdad5739375f863fabcb3"),
    ("022ce570ad08ae51142b25a21ebd35ad1eb262e4cb6d4ed689e52fd3188384eeda", "024b76f5650488d84b4ffc43e78e9ba0df9164f77e5a1b3af06762b365c97eb4cc"),
    ("03735e149450763fdc3e28b17be3b6ef876c6ad2535da8cf34dbaf961c37b319df", "02f384dd3a8fa56e26dac7f05cfd297a64d8a0f7f1165f3ad9cf835ad2ac776550"),
    ("02775fa86a65cee7d22e27bdc9a8a963d58705246f29ec200b97102c8106b4f4b8", "0329a9c5cea487af9eb0209f4bdc5009aeeeb02730d5594488a97c931e726e0e11"),
    ("038ea621bd01652cd9a611d86b616c16d2c5fa0dbddff68b97f0f9f80a79d854e2", "02612b511b73b86ff211d6853ba6f702b3c9e3318c944c5130fce1d02dfa8eeef2"),
    ("03dd1f849041275b6e84e77ba9f7c7005f3cea73f383a73654e966ecd8ad842c9d", "03695fe5a67ad8b46516ea2cb7bd321abbb1a7c5b0a81385e021ed1d2252a0f6a1"),
    ("02e186a4d3c5b7c35d6631a66eca99405ba05891564281033a2181571bc2cd45b2", "03e0f1bb79bf2fa73bc8f068c4a5d3695cd856e3ff8334a365c3e01fa05a0d9970"),
    ("022c27b7140c6a5a3d0e6dc0db3d92103e0bfad98809b461a8136e07295688f999", "02037b334d04fd50953346234ee31aeaffcdaab8d238b57ccdb52978ab6bc871fa"),
    ("02b93586180ae4e4bff4d459ae2a1f8a63a0cc84af123c0c452270caee231d65e8", "0303ad2d0f33b441ca0d5fb311fae319daa2654ebb5abc10f0c691a5deb4be4792"),
    ("03688e738515c9b7413e0f035f62a28c2089afcbe01a2f9412b5e0fe48cceb5bb5", "024de13092f91201ac68494fac6f5e9cdc9392dece2ca0bd1c4261dc17b1ec0b66"),
    ("03b856dfa9e7b71becd81e7b9fd07b3fa7b33e26db35fc46ee1bf3f40062a0df06", "037f84a98839eac98e581551b5d6bc062b7140ab54626c0a3d9f859f698c8c87eb"),
    ("021e3c922b23a728cb4f229404a21ebe6908b9907836f777c73f8ba67b64d07a4c", "0221ce6d1d004c7ac1ecb715410bc205882357319f770c8c06c8bd1dae7058d2d3"),
    ("0227646082cde99f7f0c0309a23c0a5bacbe7a5bd6a93677ad33263ff52f491fb4", "022288432394a8211c1f67e232ed9ef0388d07908724496f9729652f346c80eb36"),
    ("02aaaa04fc00f3759ba0475c653b159b9c202c1af2218414b38d6211f317155511", "03ed22580fc68e4cfa79e9aa69c9af63c5793d16ba23b775b7967c9308dfd2f243"),
    ("03bf550c34b81d0b2bea7794142b6b9e9322c00b8fa1e7f104da4f29b30a038b45", "02a6d96a2a22c322824e52b476ad1bdb930f96f736d889d98befa7621abb738490"),
    ("03d68d93589ebbf6f8ebdc6ba6bd4acb91789c8af3ffb85ab2bc8d1e0d854d53f8", "02d372f4e3a9cdeb39dc446834bae12ba0845f8aed048d4c0e32eb176c8e0ae8cc"),
    ("03e6ef8c95dbed80bded4d5f36f1754bb401e92b5e6cf55f157f72f5c2b48aa084", "034c065b63aafec3f44515741e0924b190034bd40f61e7b0e5e1f01ba87a8d4984"),
    ("0329975881824b49dd9a7a9f8b5106cee4ffe4d80575596f0fe318d9859c6fb100", "027986b16bb9d8c541aff8e8df339548189f3077d0f42a517f7ce57135e8a9c19d"),
    ("025121975efae0bda4e0be1d83f3a892115b6cf5e14bee76178a8d70d22c7ef003", "033826ee2f7ac7d9b844844f9c3ccecfadc0e8783f2395ca038307cade6ee3cc68"),
    ("024e7888b5b1da7c80b00d68a61a46752c09ae9a27d4279d548334a0adea319620", "02c08d54381c346b4c7ff20dad139663249881874f242f9fe6923a03ad573abdec"),
    ("02d20596de409d6f068e58d2affc947a88cc4e1b905555ed05c767da361a416597", "03ae7b7518cf28557a7870e44ea53dfaefc2e07b1f8c6d34b83bd216c850b1d296"),
    ("03f320be942b9d47ca5cd438cbc6192f7bc9e98478312af8c4d1d90fb04cd15bf7", "029ccbe1f4835d0fd4258d215a03a3ba6860ff2c49ab58b9207aa27455c5d1815c"),
    ("02a8d6e5f9089bc459447843837a4bf3d35221cad716fad41f9fc5e5ae6955cd2c", "0330888c7f09c7318f1403a8b83ba465c4767bf3710bdafaece132072c23b2eda4"),
    ("0212e500e63f4816dc11e402393c9b7192746b32028fe9258a199a252d3995392e", "02dbaaf5bae72d36d8f66320c536d452e0344fac85611ed71f3d7cb8acbe37d7c2"),
    ("021ad379f92def6270705e8c20861e62e3757cbf6ef8bdbec8ed84a9d828ea1db7", "0202ca96e5240a938e53de235a7ac4855a3c028d8fd08b98a3e59fd845a0238201"),
    ("02e2670a2c2661a9eea13b7cfdcdd7f552f591b9ee60e5678b7abe77b7f9516f96", "032b888be6b714468e9876b50b7aedbc9ce87c561b455a364a59b94ea2f98a81a7"),
    ("02b4d2557c18418b3c03a2e8d1e09a0bf9cd079705aee9575891024878fdba1267", "02b39398d8b079256038fe3d9f58f4ac62f6c3ab6773a4e0c802bd25d6224315b0"),
    ("02c0549536c5e14f778dadf4da5511783f90c854d12696c0529df8b27680ad8079", "02029a5ea890afa1aa8a201ae66fabaaaa50bc5735bbc88239d9f05d241a328c99"),
    ("03238e5f00ed7e8bc3ebddb7d18ca81d50c7f1f50a7158388d650f14514249c720", "022c8f453a6b1a3a069bf3a65975b1760c4444a3e18ed50b1f97ccc2159df981a1"),
    ("03e0f68fec10077c135eaee274dc280d50af1539e564230a6872d893a019f31f9a", "02acb47876ac9f5f51a1c14dd928e4ef2c94e8c653fbf09eac032ab95a03b9fb08"),
    ("026e3a82965a23b0fec5e6431c94639322784277c2a02f15817a3fea4df9d60b97", "03745605e64a12dcb32a449de4003027dfe5f5b3cb09477b29ee9eb94c5d164b1e"),
    ("02cff432cd681409a7020048ffc9b36b2ce8c625ec01314f54d4d34d68de5798d7", "030449c95d199089c7ad37ac2aea2c27a9dc4fb9f456384bc5db713df67aedcf93"),
    ("021227d4be948ab84613d5dbd47b6e148cfb811432d27eb92e80d28382b1b27d27", "034d696d1088507ecab32a16e454dede22809f67461572011540894fe5083a1344"),
    ("029bbf65f3e2e00bf7d0f7dacbc011b79b2234c9818e6a8a96a0ffa779d5d91919", "03784896ce7b74fe105044d2f05dbab8e32d5bb7c99d7bab05e678469f4db3147e"),
    ("0200072fd301cb4a680f26d87c28b705ccd6a1d5b00f1b5efd7fe5f998f1bbb1f1", "03149c3a91425530dcf60c6c3b2145e53e6b5708a8fc28e1efc498b18edb73f5bd"),
    ("02d75539514aec0adc7899ae7a12b785f3b46a3b6e00e42b3baccce93e41295981", "030794cbbe8250ced13e1b6345e82a96073dfc78e667602f8279a73de48c1a9636"),
    ("0259bc196a8d5961d7a7af4f5626bef7998890db5f18adc53a38dcdc76c8755947", "0305564da23021c438ca52a793b5e0bb97a9e9e4ac6b5932c0f42a3d766564fe3b"),
    ("02c213c3c62d267457600e09dc113b244da17fbbda59e904d60d0f661d7b586012", "0246dae073f563308943c44f4d072f226f4968ea6a5a82079e9bfc9860a8a620f9"),
    ("03440b50d61eac3524b6d43fbc5c62552111bf35c840574a797449f339a547aa5e", "02f40890af885da4673f0ee9725ee74bb2c66d6491cc4334056a2701057993e61d"),
    ("032dcfbba1e056c4ec7b04a84e75b2e9d792473685718b534245dc58d9878983cd", "0214041761821afc171b6907ee9ba36cb86307c3454305137b94a23fd81fcb4089"),
    ("0276423df4b19f85a8a72890a7866aed398fdbde8dbd7d29c3fb58c7ffd685a270", "0218e34460681abd93f862c17799b2b45dade4dbc953bb8f67e9ee50e2deb7f8e8"),
    ("02e46cf7becfc79646e0580646c3915dfcebb34acefaaac1534b770ff6f45fc694", "02da2d67e6192eef7d41ff32c99058815e327a698446bed68f6daf79c624c5727f"),
    ("02f824e43682b32afe76cdb76cace7dee3c72fb8ab15029e36bbb1bb5ffffd1ce6", "03c54966f7b995990831e9e0bfcb2dd202bfa38e1c3aa02f23cf988c5791053326"),
    ("0331f80652fb840239df8dc99205792bba2e559a05469915804c08420230e23c7c", "02d98ee97760aba8124606c177d0e4c246fc6cd7bd8f8bd3857f20805a62ecbc26"),
    ("0257e4021c5ff7ef69bc023e86f16320dc47269f96ee7858362be1d626e59e7642", "031a02081118bcbd899756f8cdd9feaf5dbf3f1014a1d811e33e8f5a4d8079e2fe"),
    ("02e6a169b0826dfea51ee8bb81f629ec7445b267ef36354f2dd5012e1e8b69088c", "0395b5a9caaf4bf4722f0b08d0cc2af1eabad8e1603df0079711da7ae702fddd9a"),
    ("026914299a6f4e16bbe83380b23c2aff8c4d0e2824cddb38b4251ae3c83c5eb5c3", "03123960416d707cb60c5f659372367a31b79f2fe492ab69e305186db24edebc3a"),
    ("022c1f1ec7f9f065056c97f32a0f2275db0bc74583630c6592e7dda2d92df8e03a", "033f142bfa5185797a8251cfbe47d64c525b922610b2856c6bbcdd5b812505a17b"),
    ("0253fbb69ce477a3c714367432a9c3ffa53b5fdcc51b5b6db8fe768805ecc66d8c", "0323c9a99f61f07f2a0037ea677c2676b1215de53cf7c39ad06c09afa73d4642de"),
    ("021f98b9898720f8633c93faf0aa54ab399d277464e502d1111b233c2cf4064828", "02347645c96896120c1ce32ae4d9fc09422b732056d0987301bb61141bf0d631aa"),
    ("03ca3a17e199932618bbb2d9d51a3e8856477c24a11247d8bfc7ad3826408742a2", "02d9b25ff40f8d8ca7abad6bb73be76604f861ffc52af24a36129c80296640ac1b"),
    ("038ea0dbdbbe67044e13ac0ffddcd0b5a496e177f296fba58f2a97d293cb8225a1", "03a5a26dbc35fb5fdd97475d83955d6b6534974a62160062833444525743766d69"),
    ("036bfd570118f7d38c16c0de23a108510ada8e1446116cc9f1fbe9d81b9b79897a", "024ece7266b9d8a72082db4867edf6a2d5ccc4e9aac482bfa401680344ffd245a5"),
    ("034f3f1c55376cfaab506367553b6d732249080d54774e496736ab118ff0144cea", "0232c9cdca608482d3d4990b8b8dabcd6b1a5d09cc3ba6b284a372a037ee26d993"),
    ("0386cb8a01e19de1e1d18c3373fcaa404abd2e21aad9d1fc599a67a6f17bc2711b", "03790e10c296b12535baf03738765163dadf91ae1f48a6a6d074a6cf910d26d8c4"),
    ("03347c2fe1ff80007e7dd68696372b2f61d936c5ce6190f4fdce3ef972f1d812de", "0215a82f989529981dfe2539fa0a8e370d7355c7ae58626165a26d7cc4a61e2191"),
    ("025b827ba1f3c64b047aa246c20748e287bb8f7e5ffaff8911e32664c9a41db2aa", "02740c62f38896511eb98479036b06907fcac283f62d08b756a1e25fb55e3f6772"),
    ("02fe3bf758f87d9b0cee6bdafa52d04650daea8c48f7178358b130b62e132c6f5f", "02222dd443181165571d236af531295e61212737507aa1cb9a05c82646ff3170ed"),
    ("0362a0f1314f12dd5fef4a490ba8a9b3fc520d60ba2d2ca6191f4c0610eecb026e", "02f304f0ed1dd6ae7b8677025e40c9254b3b9d77a44718f21aa3e3322552af7562"),
    ("028f32a366b797c8fcce1d50d6d3ac450f5392481a075702a8c9c6c77c000d30bc", "03f123d4a8876b9531f6498058360642d0870038afe2da07381a7626546eaf3088"),
    ("028655dbf042c851ffe9d3c327af5d47a008cae43feb79a412bcfb238eff605dbc", "022457e61da38e1a7cac56a054eb0ef225d84dfc6b932dfa119ad7fe6fbecdb71d"),
    ("037ceda65cab326b6fb87f1bc4ea863833ebdf2148bfdbd1aa096cc62030cff540", "025528c4e73b87ada93830ae566025fd0688dc32d450e8bb9042be4c762b64aa8d"),
    ("024bd01f2abdbccf670e1b6c5e0fa33dbb5a32781d9e667b893bd37c72c3ae1fb2", "020bae58f77e89686f48247a22e351eb4538be46d71e14d7dcf1faddfd949adebd"),
    ("03fa3de2736e7752f4911e9eef40d26d1a0d32de8a22875b611c9bcb2bb3c129a8", "026c2039dadd6d8f5cf7388442118929dae8edce9503f2bc6adba3f5cae133a0e8"),
    ("022f58062b1deaad7fd76cd4ae41a4dcb49c2047e21ac4ece4e17c9bc68b0a3ef0", "03464340ffe96762ef1fb1d53d0e4aa9aa1527c01bc9cb6d152c49c207de4b2577"),
    ("02d4c67ca1b821efceba040a9c207c2304f1f489bdd98f6ed72e478498452bcc36", "039f96b5dc60b60596dafc870ae104beb6cfaa40aa718b3e9dab9db6b1e52ed3b8"),
    ("02cf21b344646e0c2d6a0cf443f0b1b51a3c77b05ed442bcc7833848bd30d1830e", "03861419a91912e913ab4fdb45a214f1d3b737be23826532a52729a113a8bb5864"),
    ("03c1f88fb334ebbd5a91aef7f4976f13a46fa058679de98d915354587f69e72e94", "02efe789fcf1d791e9dfc9494b81a9aea7aa0c2a8744c66165d16dc47ce6b88b91"),
    ("0265a117eae4c283e2a37a6aa5873d6e3528563745feeaee0a007e987e65b906f4", "03a503d8e30f2ff407096d235b5db63b4fcf3f89a653acb6f43d3fc492a7674019"),
    ("02f6f66e3a47a53599dceb83063d0b0c2ccda59eafb52185731481f8f6e0ed6f7e", "0379f276c9a7a4aa590dafb123c5fce383123a77ba5d1d44461bd632253c50fbc1"),
    ("038247a593349447d67eb2444132bb8b54ecdc146e910d1ce5959b91b0da5ff758", "03285f35ce6b08dac955cf5da9cee1cac59b0ef1e81aba56404cbcf1bd24ec5ca3"),
    ("03119eaec8ddd1aac5c553960efd349a52efb0543c9a0fa069ca6a09ae327ceacf", "03aabcb0cab7df30d0a0930b541343d74fc557e4b6b9e052bd88f110c6b5b7b4fd"),
    ("03de8bb3849100ed6e01e27766a8b75e4d42caabacab0e968e6a2d9194e9a9cbc9", "03f15ea69a56b06cfb9fb479c27821644f038df929837afd78774366de850ba848"),
    ("02281c99e9329f57481bf00357d0574d55f23427ca88c51fca6ccb276874026994", "03b7426d09cec1f310d1b83d4c3c660c747fb26b69687f4dc9987bec8455f320fc"),
    ("02d4531a2f2e6e5a9033d37d548cff4834a3898e74c3abe1985b493c42ebbd707d", "033d3b07aec77af943032cb5cb3730c6cb14270afddd1165e7c2e6ae6cc6760961"),
    ("021765b59971ff78a2116376bd1fa9f7d253d124c7c494e3323ce9c9835ae13cfa", "026d740c4a8387a0542fcc94b2ff485ecd0329cfa30dd0ae4091a80ad15fc8b4e8"),
    ("0328946a0693c2a4dd7e5afc2ce2e020c54e07d6693a293b4e1c1fd7e2d09a3eeb", "0292e2d1299753a1978b0818b24fff6aeb1879447b60ecbe096998cf5bf40b5e7a"),
    ("02ce669fcf31e3d33b136660e211500ff4dd24e81d8f236fd39d0e601f89f96b6e", "03c74a084d4df04fcaad526febbb2e52d491d79e88ad4f2d61504ccd140cfe6890"),
    ("0200da31ae309a42f955de9383c150a4167246bb22d5380898cd70aecc59136c50", "0263070df7ff4e00996ec87f3c1e2917baf9aba7f30b417c2d049546cdd84c77e9"),
    ("022b3aaa8cd6a652e4c424736dcff1098d1d4f5af0101abcb0ad351903bc26e2ec", "0222671c84e71543ede9b585327779b4baad3e6f46bf3935b2d898ed489d024a2f"),
    ("03d40a4ade94703907f83e0b74fc07633db6d0842fad6aae6c4acef3bfb09d5fa4", "02310841b34230632b2e8185f9429dbf27a463270cf049a66ce2d56b524b283b6d"),
    ("036b68f4a57a2cd518fe7cff5434a08ff0c7a16c6d1d9d1a1df383922545f4ffa4", "03434a39cd9a537c852fc8fb72454086d726f9111e9f730cef4985c39c11fae944"),
    ("025531d75a1f0e551b3a4b8754164df463b1839927847c630a70219c0eafd50858", "0363f6132fa34f0cffcf18b94218cacff7fefebbd085ffdd0cb1bd37d12461f685"),
    ("02cca6c5c966fcf61d121e3a70e03a1cd9eeeea024b26ea666ce974d43b242e636", "0282c03304843150091acd28fbbd2341d73571004ecd48b1da1e34844010c74f88"),
    ("03afdfd5020decc582a9aedfc5190403117ec83cc0e5993a1e5bfb8448b7c5ee59", "0217383b5f11169456f10d32b4474f2806de64cbd60b42512cc42c1f769f41ac8d"),
    ("031fdb66f0f182988c00ebb7a3df844410b89bb01daf2c1872b2eed9bc497b08aa", "02370cab756adfca2f64151090325b9fceb01490c27a6b4f85820a9a8c504d158c"),
    ("03e0a26dca5f4d4dea43ba104d41e4f54b6b66c5b2559811f83b20efafbe307183", "0379ed1af38632a780546953841bc6aeaf056d0e0fe2b9c49d45d9894937d5079a"),
    ("03f08fc43523d9ea5db97a02e0fce6cf23e93b4183eaf3ed2f84c94ece8f230e6c", "02c4fec754506ea170712588600bf23f11fa123aa6b0eaf1e3617c1e6a6cd02848"),
    ("02d13634c5cd765a5a9f8db5ddd25b399e9d7feb160f51e3b95c2aeeb1ee6e3156", "033a79ee73d58c4476e8ed95b65746e63e806540497bdaebd6bd21a633c41db70b"),
    ("0356be6cfe1a94eb09564cd51ad9d5fda4f90a7f8c6832cfb8c993f8be2a0a68d7", "022d1db4e470d02bfab04066eca93da448d8d75950aded717922d5f2c4f308c824"),
    ("02518e6f3999483c223393e9895171bc6aaf7982b3c8000c07b7f948646532e7ec", "0299dea525dbbb60fcb934213415303fb02db1e4feedf1e4d793c57fbd6a39119b"),
    ("02b3a79949fcb53da53050018837c8eff56ff571ef1e0552a80edeedbd7320ffb3", "0394396e6e8cf8d9f91de4269dfd2e8416b93b91cae6139057c7283b463e4bfa31"),
    ("021a25810ddc68167a9f7bd6b9760034349cc0f4f57d514e974394210bbfc42a39", "03d711f75f525b67c2484c5ab63862292b0d54577751478bbfc7a4189b81f19d3c"),
    ("0310c5e129986800bd8023d6172833d03d19d3acfd4f27c49c61a00a0538629a65", "03bfb3df7555af554f70d83b723ab6fa80b59fe826f567d584696bde0be2c7d792"),
    ("031015a7839468a3c266d662d5bb21ea4cea24226936e2864a7ca4f2c3939836e0", "025842afa59ad0240784c19d0589488977db78acbe6e57371d60cebd8c13e9ccb1"),
    ("02006870c8b24c5070e1eae20e7522268eed4236787dff4c050cbf52eb9eda1033", "02868e12f320073cad0c2959c42559fbcfd1aa326fcb943492ed7f02c9820aa399"),
    ("02bf3b1734c9fde67ed4901a326e6f39ee6c01b0138999d23ac047339652bdbb3c", "036bd9d2f579c0af0abeb2bc17d345d0b1d84c0965b280b19cab2eeb6fbe2ef72a"),
    ("020815a33a54159aca81b4b88decce0b550f2cbe1aa55af7a050718a5a03941fa5", "037a98db43b6a657ba397ad70bd51ed75dbd3dab3a1f573be8e53800583901e13f"),
    ("025f0a47fb2614dddaeda50cf0f18bf08811139a7647bcd5c2ccef1ffb2ad98224", "033ac2f9f7ff643c235cc247c521663924aff73b26b38118a6c6821460afcde1b3"),
    ("03dc37a0afe78d34ffb05759f0b5ddd739e556baff01aae7d78638238b3f734ad5", "02dd4e90a29169b2d4d7d1bb7b2304ab6eb93b94c06524a4bbeaf1e802eec9d538"),
    ("02b5a8fb877b4a9aecda40ed8e25eff1cd48fc9f478f92eeca1f8fb9a63bd02bae", "038a292d0de8df42caa3c6dac1bf978a65335fa9c96021a6722615b6f307528355"),
    ("025a6e703976ed856c6531beb778afa0d6e563a43b6aba8f2e1fadc92963e99acd", "0211d55d51c6614117de5f48096392e4a5884ef8f3d2155922cc65b43905d43517"),
    ("03f8382a85edfac59a0e3a47dc5d71eb4230270c1c4eb15248978d1fc0faadc35e", "02a4c2430e8e826b40cd863a35d2aa97c4d4985367d75fbd713d9bff6c78ba8c7c"),
    ("02354d23deeacfe0fecbfff109950cff14a1d2da72c1eb98a109b7ec0133b06609", "020c27b1e99dce7b952757be0b0d7c42ab825270c4f8f7571a349624da408b2dc8"),
    ("029104f6ececc08df799be577709e59f8ce8ca5ec0d997bd94ad74f1eacbd1558f", "0260fba90112a46a4110f166c573fab6173756a3dd4bbb3f0ed734d616e3b659cf"),
    ("0348c3600bf7a3d600b637cdec49f6b3661fe4bd055e54a2b7369ca56e4a805664", "0253666def43f9f0d2769be957086282f0dac56105b062006859116d1ce827b555"),
    ("0332317f2fdeab6fe83675ffde9d8092b5bdedcf7ab6e6c2dcac62a149f8eae8e1", "03a9250b464d6062ca9c982851e0873f9155ec7303a0c4b6933abe58b4c3d9900b"),
    ("0273d2a39127fd9e8b9139c1406ec8bb43cebddf66e5cf20bfdd20d827c2144d96", "03a4d23196b17e1f338c7143edd58c0a49c555bee17b0da159cfc4c62b8c7b4822"),
    ("03009dd4f2c037b715b22ccfea01f578069fc91a79cd033c71974e656e46cd5328", "03957c801187ade4aaeee6ca1e3f23eeb7413a84558ff1f3543cb41f5f86da609a"),
    ("0288511d2f59931bc478c7cf501d45e4f7c3b5ad968c06e3a9fd0b84c5329a140f", "020aa1b4448974defb5a2b4058700ffd53a9ab8c2fa3bea0e1cbdd71f2e452b81c"),
    ("02d37a83a9cc83364cb92a4c0df3f76d353b7f4fb0b20ec06485e15c2c51fe7a4f", "02ff584dac23163954c20f47ede2fd5415b3e155ef47b20c727bcd640b8f592892"),
    ("037c8b71d6bdd56e2c6e51643c91877ad9703c09a774049fc66063eed0539c9b55", "0373d5f33b34f18668b4135195db1eb988631f6889e6de76a9cfeedbfccc68bf32"),
    ("02a0bcc2b99673587d4a92028a2b2ce709b72c904962e2f783fd480c2c41e3dc7d", "03a98cedc0a9a0df3d5043e3d83bfab1aad11ea39aa000fa967752d83ab4f9d823"),
    ("03df830f7841960b750a824ce6f37ff0de6fc796960ae806ee038ba6d064e24e40", "02b037dae4dc43b17de3a883d993d3cccd359a2aaa5f7f7b44ee2bed6fb8d4d510"),
    ("02e80c2d39a31d13207b8262d814d739d00669665769c24d42b91c5377919a9302", "02264fe201466c8edfb3f88be77bdb4a1587ebfc556b00fe5a6da7aaa5e2aeedb4"),
    ("023767bd03ce0a4a9988de254850e05ccb0756f86cbd20de0cf56c7658f7ceb50a", "03d71b476061f4a81b9a0c456bbfe4c5a991dcdb2bbdf1305e9335bb84de90f062"),
    ("0217890e3aad8d35bc054f43acc00084b25229ecff0ab68debd82883ad65ee8266", "03620f31cf2bb95bb70c8c7bcdaa0698cd9bc7f17987290338af301eaa28c351be"),
    ("0318ba0b7478f8d420cdeae674992268df5405436b45d7ea54835fe6d38e0c33da", "038fedd27c896882039482509cd33f62143e6bc0a9ad8ff41ae5e353e30d745387"),
    ("039bb663846b683ae1028fd3089415111d0a3f6c2f4f6be24fb90614664a5fc727", "03db7ac914d745f489c5974a998c1984a7d015cb117f9c31e10f337e7df2166e58"),
    ("024d6ba76014592ca51210a6cccead5824fa889f5e2e36992fed51a0df7b41e1c4", "034593537dc19047de45ef90ec22b83243b184df595a613883fea6542caf39a656"),
    ("023d696f812e347d57219710ecdf7b55236a2c0a6bde1e2594fac99cffb41b92c1", "028b2c9ed9427b31db21ed75eac119b040cf3e9bdbfaa378cde8561cf1274b4228"),
    ("022b458daae27a4e42c4a523502b7f078a8e9e179310e30562759cfa97a35dd5fa", "025501f06098bfe9015fcc0955c60403e8b1ef5741180755c9443c6e144b343c38"),
    ("03ba90a8b6c280648aaccb911ad1e430a21c96765b3c1eb6271ecb67225fbba8dc", "0315485eaee45133b84e890257bcc614f146c0c615dc34bec21ae5c541c403763a"),
    ("02c16cca44562b590dd279c942200bdccfd4f990c3a69fad620c10ef2f8228eaff", "02bbd8307fd0c44e989e458fb76daa363651659d41568d8e7c8c56bc556154dcce"),
    ("032b2bee19fb0c5a58fb7310af09b64c301bb2a19e3226a53877e3f68ad6bd048f", "036137210df6abca20e28c7fc1e435cd158f91f2f0c52d9f2d7b8c7ecc51b090a2"),
    ("0254ff808f53b2f8c45e74b70430f336c6c76ba2f4af289f48d6086ae6e60462d3", "034b7b1b6f9d6c98c5db180a65987f407d46da3bbc706d5e7e326b1167310cd542"),
    ("03f8db42a3ed6a5300fa2dced326adac9e240c6514e15e50d8d0592515def19e47", "02a2584ad1c54300f0576507ed63a79727c55314628486f24582ac071153d18a03"),
    ("02be830b9c86c74abfe4c7af27a22564b387c076d33517d1cc9640702c96f87157", "03a232adf8e8f9bbd90a9f145b10f24c53c0c67d8f891dfcde6ae9f2828b34ed25"),
    ("030bb6a5e0c6b203c7e2180fb78c7ba4bdce46126761d8201b91ddac089cdecc87", "02c6e723e982b987b57434ba8aa04aff5471e2bd248f0c9db8afda2878a3d9e40c"),
    ("030341f398b2b9f638692190e72cd3f28162abedcfbaca61ceae27fa61c8e29451", "03e72fb9b743546a81df807f0b9f8664beed1b27f8ba768a119a3e5a9b40ba23d2"),
    ("02c38022478fc46244e229be5bfa3d3de94a009c64cd380bad5bfad8d8e2120917", "02d3e10afa2a34000005fa1fa80b7233ba636c568932968ff5f9603c842da4ed41"),
    ("03acb418d5b88c0009cf07d31ec53d0486814bc77917c352bd7e952520edf7bf3c", "032679fec1213e5b0a23e066c019d7b991b95c6e4d28806b9ebd1362f9e32775cf"),
    ("03487c994b4691a58e97da12efeee03be1a6515a90f6155544b40ece9b3bfa9fe6", "03b64dc961d5d3207f695645ff067469a5ba5fbd1f87c28b131d3d307c69dca082"),
    ("02a04e34c07fea8b15b7c551be516da6bb345d9b66fdaf36c3ef90dfd9accf002e", "03a20c0c2ae4508b669a70e5f82f08f69a3da5da3bf0c973673c3fe977db0f61e3"),
    ("02c6aa0cd54655ffe015b0539db33e712b2f1cdd37a89834261c06ec74cec26a7e", "02b6dabd436275044399002241195b82b7fed517b226d0a109b1d07a39d7b4a91a"),
    ("0362212b688529b3ed34aaad19b7c81a6233a6debcab70e5d3e8cb126dfb2e6c20", "03897815ed5bf459d9219d7232b6f7783515b6030b926948425a8f11dccd4b3091"),
    ("030239415c8f8a226b250ba892e2d798df5ffaa504bdc70d15adb95146d51e3522", "03d6fd03174dac8caf996de57e16cda288c168da437c31b1c9bd46836b087a883d"),
    ("02b81dc46f5647aa104766cd64648af305744d3fec2b262d45deaf72adc5fdf917", "03269b54b09982981f919e8d03b958b52c4248b589d420b9d1052c9d7dd59cbd24"),
    ("039da00599d756e6fda10b2f1b63759fbfebdf1c9587a0d6e47da99595f958e525", "02f8f981a3d6cb6536fc12ea2abdcfd20f7490e28197514aafc05a7a1f08d3de09"),
    ("0283065d6d5b1fbeaa1f21e62755b4b834c4fabd2765d9522f5a79ec216fec3013", "032ee8428ff4c072975fa6af8787121b5c0375c44d40cc675d47084684df36aa2b"),
    ("0342d159fa1685f8d2eebfc989de8103031f1f47a55b4f57f11007f572330fccb5", "02474521c814b031024fc295fe144ca46ebdde045a4a1d80abe444d68a08e272b5"),
    ("028a29151b53f059bcf1ac893516d58fe59e7280dc222af148bb2ff81f419c4e55", "03a17e8ec9570e1b71d1c719aeeafa88b0604068863dc88028b490b96c2898dda8"),
    ("0211946c513aa1cec6e28613e0cb9c4898c643c36f61c6249b08081fe3f0016c0f", "024a853d9cdedafc164e49c2cef301966e5a78091322a84175b52d059d71b4d672"),
    ("025de1ab430106fa6033637d0ff2946b48808c718d3202bd8183558119df98f25c", "03788bab8bfd9d044018a55691fe5721e6c29a12a5ff07e9aed04fa465eb0a94a6"),
    ("02ea01e2476f9c6c69f720e369aa302b43e5c101423d7a5dc9f0e5834d1b152fb7", "026dc53d6d6599cbe22d7e013578d97cc0bb354768227e1aacc81610b2db9f139f"),
    ("03a5fd566492a69f3653ca464c23d8678ec19634c440baa9a1366c9b39c89512cd", "03acc9f9176f511b67aed63ba040601fce5a2b7a4521854767e032e4cd71f5cf65"),
    ("021d9d8face3a195d272511f0efb4998eb30fff93f541d10757e8f4442dec2deb2", "02d529dfc58b7f8d35ada9c3986a1526fec6347f2422b8a80b82f90fdbba5e944e"),
    ("02c1ac5cee87e4f40f24ea4560ab0e63f398ef5fb630cf50a0e2f638ce51e3169e", "03a21c0c2a4fdc1179f07710c32fe49804d3e2b0b1bdc760dc11cbf116da3bb363"),
    ("02c8fb0ea1ef0b04353f75211833b46d69bee68662d204fa25a4b25fb8c5f2fc7e", "02dad347d27a84715502f8235ad2b6f971bc380720d53d2db76c50020a0f6d5423"),
    ("02d8be648914ac46eec73489f333057fe559ef3799adf13b14842260be80a2f847", "02610e28ef828988086d69fb9773475a93723beaa6f466e7dd75009e0af43643cb"),
    ("03e35a27fa8bfad8675aeb9e96530e7b00e6fa03b571d235f6b4e68cfb4ef9097c", "026fe566f268b8e88512cfb8ffda1426416dde5f8bbf0badf43007165be0f2bf76"),
    ("028a89f35c9afedfad6764ec7d32f9d058a702f4e6ecd8cfe327296fdceef6e8bc", "03ecc0c608148e7318364aaf519e9da623bb67802afc2896652ffb8006aae1e505"),
    ("02fdd429de697838125d44b4e21126fb8d0fc012854e8be4924185b701edfba3f3", "03835f425b54cc52be24788b2ffa82061e1e36a903c2417be27d35e5f0dd4cd684"),
    ("022297edebc55af78f4f472323fa8b827dacddc74bdd786cd90f77a8c845afaa68", "028cc7964eaae6d5a654292a1ca07afb405d8173b2f8d2305367c386e9f5c616ea"),
    ("03dab87ff8635982815c4567eb58af48f9944d11c56beb12b91e1049aaea06e187", "031b71cbad0cb4e22141e45f16c83c332f755e1ba6819597ab943574010b18bcad"),
    ("0373f93ed93aa0a2adf8844fa186e79690c739c8126d1ec71ec2f178d49b19bca1", "029799e356f54bceda8ceaaeee3d75a384272307ef3f0829831caa6de2158d1533"),
    ("02d7617044629514778fe063a5ea498b992ef65a2e7721648dc673983b3ba36264", "02abafb0e2bcc74467135beddfb5924f024412107c1d5a1ca3f4252aac5e6f13ad"),
    ("02e81795cb29fce0d39f57aecd09db334c6b74fe74319ddbbe9abe7c30cc1db1e9", "038e60cc7a9a05a5cb30fb8da4e6788c4aebccfea2ee3720539ca0a8bb7058d753"),
    ("0293f4e46212a07e1ac4a8ebd9ef9e90e2c77300d3756de0f54540a7a515bae56e", "02c61651a447bebc1b4d07dddd5bcc307381852fa5a0501a703075f8dbffc38f49"),
    ("03866caa73c87d22986563df555cc3a877db60c986c976548f3b537a53f2db94d8", "029bfe06bd3c4642fa9a1dee9d7888517a07181740fe158df27ef3b06153146ef0"),
    ("022e63bd7c3f4bb7b87bd87a04a6024dc6f76e633f9c1cae0ee0424a070dabc927", "028b7ab10a906f5581e9d81f0daca16f5a89ca95a59df18b6e9ef1e4352c602b0a"),
    ("02aa0919936cadd8e077bd57244d6ff9d018acd89f1654565e74bcc31b5ce15fc9", "0348cb5eb33343108bf04930c416630af5795484791035911a330dfa6964823c7a"),
    ("021abd04ff7ad5c13115985fc34bf504c14a2602db89c3e0827ac5f3355566abac", "030fbf4f6fbe1e578dd694f172fb981fb526c7f6f3fca49d60b5a1f7fcecf47800"),
    ("025a0ab02d28a5b6fd69217c8b49f878ba772d75b68b959e785c1a6c5b38a4b45b", "032bc8f9ad683e141660aeaad3a7c46fc0a38fe53bea41ac55c67cfd6a17a00714"),
    ("02bf9092c49c1cf3df0a3c42f8c109c7e1d47e561710e3faba16d3f5d62b2914bc", "02c4e76deefb30a0f92b98c30b43a23b7d5e9e466033345e2bf7b43fca4ef67f65"),
    ("03d2ccf36081de198bcc8ff6aeb37d175bf3a242974512b576d34d075ab014a539", "031a5b68b9ce7b9db5cf3ceeae69626cdd5b25eec35de4b2ca85c8ac245fbb0e87"),
    ("03ff13adbcccf80c9f9d64f11d3282ac4eba2ad170286efbbd0dcf416b0abd8c27", "024b5405c666fa3a555247030b0f6cb671d6cb67ebc93547857d33aca10657600a"),
    ("02da0713ab1b12eeb01f212944a435077f39f1b767ee5c24b01cdb4b0b9377b66b", "030ab14e129b86da1974755377e3061bb2d5b4e93e94f67433109e5d90edf382a7"),
    ("03166a818eb5b9245f2838795f96928815fcb8e010aac7f5e4d5e3e18f6f21c066", "02a020575d7c19419365f1f31810290868352eecf780db1cbbad1df8ae9b07694c"),
    ("022ff65ac5689256dfa9301f9b6fac53feacb07eaad5cd095fa95d3ddfedba91fc", "03271338633d2d37b285dae4df40b413d8c6c791fbee7797bc5dc70812196d7d5c"),
    ("03255e78098f6bf266fbd9a72a6c2777e8d006f5cd3767212f34182d285e47faac", "03c846be6a67f96337331d3e945e6fa4679fe93f6b3e5494364e86bc4a6e67366f"),
    ("02de4ba1b08b2895bde816f012d4b4a5fdd797dfdf6443c28db183ca4a83043835", "0320e6e0107a404de55e0ac73cf85e516627e48bf69b0fdb920af3df9afe446e3b"),
    ("02b8888b0ad2ef7380aec491d9598f91546f11f594033737b64cf7530e4b322b5a", "02b63f62d2109b05deb45d18dde6c829d1df4825dfc104f858e5ec165bc6b11690"),
    ("03aff7509b0384b9dea9155282370c609796e3a33422b809f5574b52255487b4e0", "024712c782d44c0cfa8bbb21e9c924144158e94fbc5ca5ed1f5b2a4295847487fb"),
    ("02e2b35e7895b042a5706687a289f6d94dfb6b7e06c917ff393854f92bea228dee", "03a5c8e7eef252b350f9f3f3e8683b3b1921dae787e4936bc64efde84517d967a2"),
    ("0307a3fb98c026148e69f51f1851b41db6dc2abf58e77e588636a60ce85c82f091", "0291c8191f69bcbfa8852fac91452a74807cb67263b9891c4dc7a94674c04ffadd"),
    ("032b54238564eb71608472d5e455885ad6d093748ddb070e132f84e37ee092ff0e", "024794d1446e510c75f84bbd75cc151124522aed03ad75a20f1708f77e5f3e674a"),
    ("03e3d3221dac23089eac9f0be069fd3fedf73c8f1f37e9e74632314a3e8dc99ed6", "02d904c9dfea36da9a0b87272208ca5b3af7ccad131b3c3f0e341112ed0c8f3adf"),
    ("032697927de68bfcb04090ffc8e2c1ae110e99f819f68001500e860d9d8488701d", "02ec378c56e60a59acf12f6daf123174493cdd02e826ab52e05ec6e6d6d7fd0b93"),
    ("02e84e47a22d19332b356a6a41906237eeacf37a71d39a622dd31a2a7170c70fec", "03ee59428b906bd4c2e743c31c97a8ae9f63b345dce003bb230c10d5901e4c4498"),
    ("03903e87eca3d95ff79ff0f880ef34a4ccf765d0e35d33346a09e345b87a1f1e08", "03e5a594f574f845409d474c105c5d233291f94d1fba4ee6a4de595c576bd2f4e1"),
    ("020ada67f7f801062f011efc045831674d37c171ae70f5ecda98491868e74df60e", "021aa85750f8273cc103ce8e5c0ca19587283547cff2f69e018f7159ba837796bd"),
    ("039bf7bf32b8bee5f0c15303580b39b0219572681b3254b62be497aac6b04b3d8d", "03aca0142349abf19e96a3c9231d556a1525182b659e8b2ae5328efbd01ff227e1"),
    ("030c3f19d742ca294a55c00376b3b355c3c90d61c6b6b39554dbc7ac19b141c14f", "029c77f50289c32a36c6d6e3d56334ab5a052f7be74ba881d4e494a3299c10515a"),
    ("03a065349c9147e7cca90f114700522ef595288463a018d15b8a41bf87a7ff2f5b", "034389078da79098ffa96712a659fe58086a9f22e0b3dfd543d931e270037516e3"),
    ("0312bcfabfbc4617d0474ac5ca5125e52c65c828ea495ed986e50f5bec5097b2a2", "0341f852422a16f0fdc8488280f4a455cf32dc6c5c019980fcc9fcc0e0788fe521"),
    ("03fd4d2608e6a8a0c2c96c302d7eeb1d34fc380063b26e1299d68f3aa3027db740", "02db4f3d829aa70c256ef184769fb15b94022ee1ae242c443cbbedcc6d261f26af"),
    ("035a5e6346c129f1898bf2da4977039bb8d4e141308135b9468c816fd74fe140d3", "03da2732d2b9a93c066ab2bf7ade31c5cb5c6b19a3d081f5e9af64bfb64e1f3afa"),
    ("02ef57e7bb1b1aea04c3bd8729e6fe1d904c2283529e610f10d00df2ba383086ca", "02091e45e3fe8d18f2da5a0ff81873a52410128116e402fd872dde6ae21d535956"),
    ("029140698ec4f5400ae4cb83a58221d3116f5a6decc4a535093bd5f8fd1a7f07d8", "03a81fcf659613d02a32682ab94be4687520aecd36e361e146fbc6e16bce880b7b"),
    ("02a6494a538998bee0378df13257c039f09c26cbe8028559d8815fb1dd7ece1e2a", "03a4ed9d2ac37ab2aa350653efdae520c9d2282b35970d23e21830b3c3f1050ea1"),
    ("022f3bfa1de8491bad95ea018b17d47efbfe713cd2da59ad5fc380198612b8954a", "02138ee1a3e5e2285044eb43016d686eef7757f592cdd4f3cb0663eb2e0c5f7b3d"),
    ("0272d72c958cabb909caac265519286bdf2d036d1f019a016cb202e8ef50f6d7a2", "038118e0490f8d3df07b6bdc5e7034dee90d769012764d31bfeb167269866824f0"),
    ("03ab5c1de60b2dbdfdce26acdb0074bd8b3b586082d36fb33ec4e70027d439cbb6", "024bda1ec91b277d311aa60d04b1fb856efcc7897c876746b680c1f985fe973f73"),
    ("02d1105beecbdc57040e6dba2b8f33fc50038164bd5e7c46cdeb2c70016aef09eb", "020bf0c421ce915ceb0c1702e136ac3be920c7f6afe3ed75f722babbcb726abd6b"),
    ("02b2012cb48304468627c689917397805b571f4e72d46dcc60d243a6382f6d787b", "029a6c374e25af038d22a0bdfd8b064d66e7795205d2471c66012053aa1577959e"),
    ("0211dd8642e353d98a4e9a9ff178d2b58c57c397fd71e78715ce9f9420e4fbd680", "03407297621ad78652044c77dcdc7fa3e9772629fd84979a3540f7ee7f5e291a6d"),
    ("025f6c7bd445c5b9f3c743e4a34dc288b90718a158c3c90c713674a805cb92a757", "03b6b0a2a5e8300e2645f7728e699606d1df0408492ad6c88631d569ebcf511151"),
    ("03da1c27ca77872ac5b3e568af30673e599a47a5e4497f85c7b5da42048807b3ed", "0259a63bbf20ecbe7a7cce947095e34b62c36b983629153ff32d591d1a206c45ba"),
    ("03cfea6e01d8735d29863d0ecc0f637e5da25c3afb1894e6cb1c25cc9c52dbceef", "024520e7ed6a8d435bd7ebf775b73d04c09ee87eda5f568470f6c7e8bb40258cf2"),
    ("035cd4fd66046ef238fd765c3c8a56a1108aa68ce484b0864788b89f364a96f5de", "032ddf47ee870526096fc65a715362f62b3f9ff635948d65bb102990a70392fe99"),
    ("0266d3b32fc5982b904bcf3ed7bb303ea84862b14237c5e78fac60180c0c1227cb", "02a4506a1ab1a9a17e187b8cb1ebfbda317ee2a59aaf2cd194f39e6dbd5b618e72"),
    ("02cb5e88867a2ebbd11fc067559b695c0cfd6e3c877e5eb979594c6f7eca235ab6", "030add47e1b9a6c6219d538a4f73b21333b51ed2c374db8add36a4ef692a9e08e4"),
    ("0200c06eaaef0ffc98447b5f0c5810c8325fc50fba4c509ebf558d67d1f088bdad", "0276c5e7cac95039994619d34238ce9ab32502199a931b4246d6e6baba979f2f3b"),
    ("02db6ff6cf968f403aa4ecf7741b7dacc50256ae79a4999edada24da40fdf2667d", "03297fd04fa076dad3ef95db361e710c388dc916bb7a1fcc64db4fe200221d3f2f"),
    ("02a006428931882ab48809fe0093d9e21092ea0c54a772c3d9883913a4bab1423d", "02dff7503794988717c6586bc0a2abd8ec247d0c14e61366bcec535c5432fee8c8"),
    ("0388dbb74996a2225873a96e1bb02783c6aec41afbbea778566cc54393da94306b", "03d69c8a0e020a10940aa54a5330b4b74600d32732aba38ac6a86611997f038a6a"),
    ("0330f0afe893e809d8790b26b40d9353f586ef0894d4ae704d06be24538c030457", "0394e00d56a9e54a3d29036d218a607eb87257c10a4109fbb964b5c5c03c405329"),
    ("03dd3babcbbfd79fb6c11594f450a03ce9e2e37fef393a423f9086342a954b494e", "02f9e25c85721f88a084bb46c9abfc070698e12675032640e0bce310d117ac0c55"),
    ("02ff945598642da70d453746a337e1358812105bf9f1baef9e22d3262a779eef40", "026b6c5dbd4da34c7991514cd04b1657cd9da218828769ba97bf3a75c4d2e4e874"),
    ("0347edaa959405a3537a9e744f8d9cfe6407aa453c0299b8d999e366e50a03603f", "02eec95b8e6089d2dc6025e9213fb24eb6af4fe3f25e9adb9a56f167487fc237c2"),
    ("0325be312a5da25ce1523186b33759a6bc33da4677cf08e3577e8eb6083aaea25f", "03030bf668f974e30e8e7ad8bd7445358afd86dd879bc8700e52cadd43a6e942f9"),
    ("026cd1a69fee4c9eab263c0b66e27ffbd1385f52ef9d3ce357c885c52d458270c7", "02047c886946e215d4b0c4496d6ad6c37da6e89d22676b15a561ac35a7759ca464"),
    ("02edf8140c98fe67726eaeba069aea6d5104aef9081c211228dc0df028bc49cdfa", "03ea8199ea26971ee3fdb00a32ab54e55172b13f2f723f07f5b81b8dbc9911d3de"),
    ("03d1e649bd31e12d6f0b7c2f733596c94c1ce8dc8f733cda2f3802aab336bdf522", "03012a23c769decc4fb40a92d5300692b576cb8dc887072580638b97911742554c"),
    ("0380e02956c9d09d2cf349bc7f5eb4610fda0775974352fa52f7d981783e45fe03", "030185cb3511bdf8077688b33573a699e0d73d25e6cab6685309a661d323fb0aa8"),
    ("03f5d0f7b048a881f044d8aa6a253611f6b46aaacaf1b5844946e921139cc94993", "025a95028c4339c60603d7af4212d53685badf8967d4fcc824c41f10f6baaebce8"))
    .map { case (n1, n2) => (PublicKey(ByteVector.fromValidHex(n1)), PublicKey(ByteVector.fromValidHex(n2))) }

}