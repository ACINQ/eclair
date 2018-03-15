package fr.acinq.eclair.integration

import java.io.{File, PrintWriter}
import java.nio.file.Files
import java.util.{Properties, UUID}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.google.common.net.HostAndPort
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, Block, Crypto, MilliSatoshi, OP_CHECKSIG, OP_DUP, OP_EQUAL, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Satoshi, Script}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.blockchain.{Watch, WatchConfirmed}
import fr.acinq.eclair.channel.Register.Forward
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.io.Peer.Disconnect
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.{State => _, _}
import fr.acinq.eclair.router.{Announcements, AnnouncementsBatchValidationSpec}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, Kit, Setup}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JValue
import org.json4s.{DefaultFormats, JString}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.sys.process._

/**
  * Created by PM on 15/03/2017.
  */
@RunWith(classOf[JUnitRunner])
class IntegrationSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll with Logging {

  val INTEGRATION_TMP_DIR = s"${System.getProperty("buildDirectory")}/integration-${UUID.randomUUID().toString}"
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = new File(System.getProperty("buildDirectory"), "bitcoin-0.14.0/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")

  var bitcoind: Process = null
  var bitcoinrpcclient: BitcoinJsonRPCClient = null
  var bitcoincli: ActorRef = null
  var nodes: Map[String, Kit] = Map()

  implicit val formats = DefaultFormats

  case class BitcoinReq(method: String, params: Any*)

  override def beforeAll(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf"), new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoinrpcclient = new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 28332)
    bitcoincli = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case BitcoinReq(method) => bitcoinrpcclient.invoke(method) pipeTo sender
        case BitcoinReq(method, params) => bitcoinrpcclient.invoke(method, params) pipeTo sender
      }
    }))
  }

  override def afterAll(): Unit = {
    // gracefully stopping bitcoin will make it store its state cleanly to disk, which is good for later debugging
    logger.info(s"stopping bitcoind")
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("stop"))
    sender.expectMsgType[JValue]
    bitcoind.exitValue()
    nodes.foreach {
      case (name, setup) =>
        logger.info(s"stopping node $name")
        setup.system.terminate()
    }
    //    logger.warn(s"starting bitcoin-qt")
    //    val PATH_BITCOINQT = new File(System.getProperty("buildDirectory"), "bitcoin-0.14.0/bin/bitcoin-qt").toPath
    //    bitcoind = s"$PATH_BITCOINQT -datadir=$PATH_BITCOIND_DATADIR".run()
  }

  test("wait bitcoind ready") {
    val sender = TestProbe()
    logger.info(s"waiting for bitcoind to initialize...")
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      sender.receiveOne(5 second).isInstanceOf[JValue]
    }, max = 30 seconds, interval = 500 millis)
    logger.info(s"generating initial blocks...")
    sender.send(bitcoincli, BitcoinReq("generate", 500))
    sender.expectMsgType[JValue](30 seconds)
  }

  def instantiateEclairNode(name: String, config: Config) = {
    val datadir = new File(INTEGRATION_TMP_DIR, s"datadir-eclair-$name")
    datadir.mkdirs()
    new PrintWriter(new File(datadir, "eclair.conf")) {
      write(config.root().render());
      close
    }
    val setup = new Setup(datadir, actorSystem = ActorSystem(s"system-$name"))
    val kit = Await.result(setup.bootstrap, 10 seconds)
    nodes = nodes + (name -> kit)
  }

  def javaProps(props: Seq[(String, String)]) = {
    val properties = new Properties()
    props.foreach(p => properties.setProperty(p._1, p._2))
    properties
  }

  test("starting eclair nodes") {
    import collection.JavaConversions._
    val commonConfig = ConfigFactory.parseMap(Map("eclair.chain" -> "regtest", "eclair.spv" -> false, "eclair.server.public-ips.1" -> "localhost", "eclair.bitcoind.port" -> 28333, "eclair.bitcoind.rpcport" -> 28332, "eclair.bitcoind.zmq" -> "tcp://127.0.0.1:28334", "eclair.router-broadcast-interval" -> "2 second", "eclair.auto-reconnect" -> false))
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.delay-blocks" -> 130, "eclair.server.port" -> 29730, "eclair.api.port" -> 28080, "eclair.channel-flags" -> 0)).withFallback(commonConfig)) // A's channels are private
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.delay-blocks" -> 131, "eclair.server.port" -> 29731, "eclair.api.port" -> 28081)).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.delay-blocks" -> 132, "eclair.server.port" -> 29732, "eclair.api.port" -> 28082)).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.delay-blocks" -> 133, "eclair.server.port" -> 29733, "eclair.api.port" -> 28083)).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.delay-blocks" -> 134, "eclair.server.port" -> 29734, "eclair.api.port" -> 28084)).withFallback(commonConfig))
    instantiateEclairNode("F1", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F1", "eclair.delay-blocks" -> 135, "eclair.server.port" -> 29735, "eclair.api.port" -> 28085, "eclair.payment-handler" -> "noop")).withFallback(commonConfig)) // NB: eclair.payment-handler = noop allows us to manually fulfill htlcs
    instantiateEclairNode("F2", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F2", "eclair.delay-blocks" -> 136, "eclair.server.port" -> 29736, "eclair.api.port" -> 28086, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
    instantiateEclairNode("F3", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F3", "eclair.delay-blocks" -> 137, "eclair.server.port" -> 29737, "eclair.api.port" -> 28087, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
    instantiateEclairNode("F4", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F4", "eclair.delay-blocks" -> 138, "eclair.server.port" -> 29738, "eclair.api.port" -> 28088, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
    instantiateEclairNode("F5", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F5", "eclair.delay-blocks" -> 139, "eclair.server.port" -> 29739, "eclair.api.port" -> 28089)).withFallback(commonConfig))
  }

  def connect(node1: Kit, node2: Kit, fundingSatoshis: Long, pushMsat: Long) = {
    val sender = TestProbe()
    val address = node2.nodeParams.publicAddresses.head
    sender.send(node1.switchboard, Peer.Connect(NodeURI(
      nodeId = node2.nodeParams.nodeId,
      address = HostAndPort.fromParts(address.getHostString, address.getPort))))
    sender.expectMsgAnyOf(10 seconds, "connected", "already connected")
    sender.send(node1.switchboard, Peer.OpenChannel(
      remoteNodeId = node2.nodeParams.nodeId,
      fundingSatoshis = Satoshi(fundingSatoshis),
      pushMsat = MilliSatoshi(pushMsat),
      fundingTxFeeratePerKw_opt = None,
      channelFlags = None))
    assert(sender.expectMsgType[String](10 seconds).startsWith("created channel"))
  }

  test("connect nodes") {
    //
    // A ---- B ---- C ==== D
    //        |     / \
    //        --E--'   F{1,2,3,4,5}
    //

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    connect(nodes("A"), nodes("B"), 10000000, 0)
    connect(nodes("B"), nodes("C"), 2000000, 0)
    connect(nodes("C"), nodes("D"), 5000000, 0)
    connect(nodes("C"), nodes("D"), 5000000, 0)
    connect(nodes("B"), nodes("E"), 10000000, 0)
    connect(nodes("E"), nodes("C"), 10000000, 0)
    connect(nodes("C"), nodes("F1"), 5000000, 0)
    connect(nodes("C"), nodes("F2"), 5000000, 0)
    connect(nodes("C"), nodes("F3"), 5000000, 0)
    connect(nodes("C"), nodes("F4"), 5000000, 0)
    connect(nodes("C"), nodes("F5"), 5000000, 0)

    val numberOfChannels = 11
    val channelEndpointsCount = 2 * numberOfChannels

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      val watches = nodes.values.foldLeft(Set.empty[Watch]) {
        case (watches, setup) =>
          sender.send(setup.watcher, 'watches)
          watches ++ sender.expectMsgType[Set[Watch]]
      }
      watches.count(_.isInstanceOf[WatchConfirmed]) == channelEndpointsCount
    }, max = 20 seconds, interval = 1 second)

    // confirming the funding tx
    sender.send(bitcoincli, BitcoinReq("generate", 2))
    sender.expectMsgType[JValue](10 seconds)

    within(60 seconds) {
      var count = 0
      while (count < channelEndpointsCount) {
        if (eventListener.expectMsgType[ChannelStateChanged](30 seconds).currentState == NORMAL) count = count + 1
      }
    }
  }

  def awaitAnnouncements(subset: Map[String, Kit], nodes: Int, channels: Int, updates: Int) = {
    val sender = TestProbe()
    subset.foreach {
      case (_, setup) =>
        awaitCond({
          sender.send(setup.router, 'nodes)
          sender.expectMsgType[Iterable[NodeAnnouncement]](20 seconds).size == nodes
        }, max = 60 seconds, interval = 1 second)
        awaitCond({
          sender.send(setup.router, 'channels)
          sender.expectMsgType[Iterable[ChannelAnnouncement]](20 seconds).size == channels
        }, max = 60 seconds, interval = 1 second)
        awaitCond({
          sender.send(setup.router, 'updates)
          sender.expectMsgType[Iterable[ChannelUpdate]](20 seconds).size == updates
        }, max = 60 seconds, interval = 1 second)
    }
  }

  test("wait for network announcements") {
    val sender = TestProbe()
    // generating more blocks so that all funding txes are buried under at least 6 blocks
    sender.send(bitcoincli, BitcoinReq("generate", 4))
    sender.expectMsgType[JValue]
    // A requires private channels, as a consequence:
    // - only A and B now about channel A-B
    // - A is not announced
    awaitAnnouncements(nodes.filterKeys(key => List("A", "B").contains(key)), 9, 10, 22)
    awaitAnnouncements(nodes.filterKeys(key => !List("A", "B").contains(key)), 9, 10, 20)
  }

  test("send an HTLC A->D") {
    val sender = TestProbe()
    val amountMsat = MilliSatoshi(4200000)
    // first we retrieve a payment hash from D
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator,
      SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.nodeId))
    sender.expectMsgType[PaymentSucceeded]
  }

  // TODO: reenable this test
  ignore("send an HTLC A->D with an invalid expiry delta for B") {
    val sender = TestProbe()
    // to simulate this, we will update B's relay params
    // first we find out the short channel id for channel B-C
    sender.send(nodes("B").router, 'channels)
    val shortIdBC = sender.expectMsgType[Iterable[ChannelAnnouncement]].find(c => Set(c.nodeId1, c.nodeId2) == Set(nodes("B").nodeParams.nodeId, nodes("C").nodeParams.nodeId)).get.shortChannelId
    val channelUpdateBC = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, nodes("B").nodeParams.privateKey, nodes("C").nodeParams.nodeId, shortIdBC, nodes("B").nodeParams.expiryDeltaBlocks + 1, nodes("C").nodeParams.htlcMinimumMsat, nodes("B").nodeParams.feeBaseMsat, nodes("B").nodeParams.feeProportionalMillionth)
    sender.send(nodes("B").relayer, channelUpdateBC)
    // first we retrieve a payment hash from D
    val amountMsat = MilliSatoshi(4200000)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment
    val sendReq = SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.nodeId)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will receive an error from B that include the updated channel update, then will retry the payment
    sender.expectMsgType[PaymentSucceeded](5 seconds)
    // in the meantime, the router will have updated its state
    awaitCond({
      sender.send(nodes("A").router, 'updates)
      sender.expectMsgType[Iterable[ChannelUpdate]].toSeq.contains(channelUpdateBC)
    }, max = 20 seconds, interval = 1 second)
  }

  test("send an HTLC A->D with an amount greater than capacity of B-C") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D
    val amountMsat = MilliSatoshi(300000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the payment (B-C has a smaller capacity than A-B and C-D)
    val sendReq = SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.nodeId)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    sender.expectMsgType[PaymentSucceeded](5 seconds)
  }

  test("send an HTLC A->D with an unknown payment hash") {
    val sender = TestProbe()
    val pr = SendPayment(100000000L, "42" * 32, nodes("D").nodeParams.nodeId)
    sender.send(nodes("A").paymentInitiator, pr)

    // A will receive an error from D and won't retry
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("D").nodeParams.nodeId, UnknownPaymentHash))
  }

  test("send an HTLC A->D with a lower amount than requested") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of only 1 mBTC
    val sendReq = SendPayment(100000000L, pr.paymentHash, nodes("D").nodeParams.nodeId)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("D").nodeParams.nodeId, IncorrectPaymentAmount))
  }

  test("send an HTLC A->D with too much overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of 6 mBTC
    val sendReq = SendPayment(600000000L, pr.paymentHash, nodes("D").nodeParams.nodeId)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("D").nodeParams.nodeId, IncorrectPaymentAmount))
  }

  test("send an HTLC A->D with a reasonable overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of 3 mBTC, more than asked but it should still be accepted
    val sendReq = SendPayment(300000000L, pr.paymentHash, nodes("D").nodeParams.nodeId)
    sender.send(nodes("A").paymentInitiator, sendReq)
    sender.expectMsgType[PaymentSucceeded]
  }

  test("send multiple HTLCs A->D with a failover when a channel gets exhausted") {
    val sender = TestProbe()
    // there are two C-D channels with 5000000 sat, so we should be able to make 7 payments worth 1000000 sat each
    for (i <- 0 until 7) {
      // first we retrieve a payment hash from D for 2 mBTC
      val amountMsat = MilliSatoshi(1000000000L)
      sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 payment"))
      val pr = sender.expectMsgType[PaymentRequest]

      // A send payment of 3 mBTC, more than asked but it should still be accepted
      val sendReq = SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.nodeId)
      sender.send(nodes("A").paymentInitiator, sendReq)
      sender.expectMsgType[PaymentSucceeded]
    }
  }

  /**
    * We currently use p2pkh script Helpers.getFinalScriptPubKey
    *
    * @param scriptPubKey
    * @return
    */
  def scriptPubKeyToAddress(scriptPubKey: BinaryData) = Script.parse(scriptPubKey) match {
    case OP_DUP :: OP_HASH160 :: OP_PUSHDATA(pubKeyHash, _) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil =>
      Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, pubKeyHash)
    case OP_HASH160 :: OP_PUSHDATA(scriptHash, _) :: OP_EQUAL :: Nil =>
      Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, scriptHash)
    case _ => ???
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (local commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F1").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F1").nodeParams.nodeId, maxAttempts = 1)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    sender.send(nodes("F1").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressF = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
    // we then kill the connection between C and F
    sender.send(nodes("F1").switchboard, 'peers)
    val peers = sender.expectMsgType[Map[PublicKey, ActorRef]]
    peers(nodes("C").nodeParams.nodeId) ! Disconnect
    // we then wait for F to be in disconnected state
    awaitCond({
      sender.send(nodes("F1").register, Forward(htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[State] == OFFLINE
    }, max = 20 seconds, interval = 1 second)
    // we then have C unilateral close the channel (which will make F redeem the htlc onchain)
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_FORCECLOSE))
    sender.expectMsg("ok")
    // we then wait for F to detect the unilateral close and go to CLOSING state
    awaitCond({
      sender.send(nodes("F1").register, Forward(htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[State] == CLOSING
    }, max = 20 seconds, interval = 1 second)
    // we then fulfill the htlc, which will make F redeem it on-chain
    sender.send(nodes("F1").register, Forward(htlc.channelId, CMD_FULFILL_HTLC(htlc.id, preimage)))
    // we then generate one block so that the htlc success tx gets written to the blockchain
    sender.send(bitcoincli, BitcoinReq("generate", 1))
    sender.expectMsgType[JValue](10 seconds)
    // C will extract the preimage from the blockchain and fulfill the payment upstream
    paymentSender.expectMsgType[PaymentSucceeded](30 seconds)
    // at this point F should have 1 recv transactions: the redeemed htlc
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      res.filter(_ \ "address" == JString(finalAddressF)).flatMap(_ \ "txids" \\ classOf[JString]).size == 1
    }, max = 30 seconds, interval = 1 second)
    // we then generate enough blocks so that C gets its main delayed output
    sender.send(bitcoincli, BitcoinReq("generate", 145))
    sender.expectMsgType[JValue](10 seconds)
    // and C will have its main output
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 1
    }, max = 30 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 8, 9, 20)
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (remote commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F2").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F2").nodeParams.nodeId, maxAttempts = 1)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    sender.send(nodes("F2").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressF = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
    // we then kill the connection between C and F
    sender.send(nodes("F2").switchboard, 'peers)
    val peers = sender.expectMsgType[Map[PublicKey, ActorRef]]
    peers(nodes("C").nodeParams.nodeId) ! Disconnect
    // we then wait for F to be in disconnected state
    awaitCond({
      sender.send(nodes("F2").register, Forward(htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[State] == OFFLINE
    }, max = 20 seconds, interval = 1 second)
    // then we have F unilateral close the channel
    sender.send(nodes("F2").register, Forward(htlc.channelId, CMD_FORCECLOSE))
    sender.expectMsg("ok")
    // we then fulfill the htlc (it won't be sent to C, and will be used to pull funds on-chain)
    sender.send(nodes("F2").register, Forward(htlc.channelId, CMD_FULFILL_HTLC(htlc.id, preimage)))
    // we then generate one block so that the htlc success tx gets written to the blockchain
    sender.send(bitcoincli, BitcoinReq("generate", 1))
    sender.expectMsgType[JValue](10 seconds)
    // C will extract the preimage from the blockchain and fulfill the payment upstream
    paymentSender.expectMsgType[PaymentSucceeded](30 seconds)
    // at this point F should have 1 recv transactions: the redeemed htlc
    // we then generate enough blocks so that F gets its htlc-success delayed output
    sender.send(bitcoincli, BitcoinReq("generate", 145))
    sender.expectMsgType[JValue](10 seconds)
    // at this point F should have 1 recv transactions: the redeemed htlc
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      res.filter(_ \ "address" == JString(finalAddressF)).flatMap(_ \ "txids" \\ classOf[JString]).size == 1
    }, max = 30 seconds, interval = 1 second)
    // and C will have its main output
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 1
    }, max = 30 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 7, 8, 18)
  }

  test("propagate a failure upstream when a downstream htlc times out (local commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F3").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F3").nodeParams.nodeId, maxAttempts = 1)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
    // we then generate enough blocks to make the htlc timeout
    sender.send(bitcoincli, BitcoinReq("generate", 11))
    sender.expectMsgType[JValue](10 seconds)
    // we generate more blocks for the htlc-timeout to reach enough confirmations
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
      paymentSender.msgAvailable
    }, max = 30 seconds, interval = 1 second)
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    assert(failed.paymentHash === paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("C").nodeParams.nodeId, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    sender.send(bitcoincli, BitcoinReq("generate", 150))
    sender.expectMsgType[JValue](10 seconds)
    // at this point C should have 2 recv transactions: its main output and the htlc timeout
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 6, 7, 16)
  }

  test("propagate a failure upstream when a downstream htlc times out (remote commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F4").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F4").nodeParams.nodeId, maxAttempts = 1)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
    // then we ask F to unilaterally close the channel
    sender.send(nodes("F4").register, Forward(htlc.channelId, CMD_FORCECLOSE))
    sender.expectMsg("ok")
    // we then generate enough blocks to make the htlc timeout
    sender.send(bitcoincli, BitcoinReq("generate", 11))
    sender.expectMsgType[JValue](10 seconds)
    // we generate more blocks for the claim-htlc-timeout to reach enough confirmations
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
      paymentSender.msgAvailable
    }, max = 30 seconds, interval = 1 second)
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    assert(failed.paymentHash === paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("C").nodeParams.nodeId, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    sender.send(bitcoincli, BitcoinReq("generate", 145))
    sender.expectMsgType[JValue](10 seconds)
    // at this point C should have 2 recv transactions: its main output and the htlc timeout
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 5, 6, 14)
  }

  test("punish a node that has published a revoked commit tx") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // first we send 3 mBTC to F so that it has a balance
    val amountMsat = MilliSatoshi(300000000L)
    sender.send(nodes("F5").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    val sendReq = SendPayment(300000000L, pr.paymentHash, nodes("F5").nodeParams.nodeId)
    sender.send(nodes("A").paymentInitiator, sendReq)
    sender.expectMsgType[PaymentSucceeded]
    // then we find the id of F's only channel
    sender.send(nodes("F5").register, 'channels)
    val channelId = sender.expectMsgType[Map[BinaryData, ActorRef]].head._1
    // we then wait for F to have a main output
    awaitCond({
      sender.send(nodes("F5").register, Forward(channelId, CMD_GETSTATEDATA))
      sender.expectMsgType[DATA_NORMAL].commitments.localCommit.index == 2
    }, max = 5 seconds)
    // and we use it to get its current commitment tx
    sender.send(nodes("F5").register, Forward(channelId, CMD_GETSTATEDATA))
    val localCommitTxF = sender.expectMsgType[DATA_NORMAL].commitments.localCommit.publishableTxs
    // we now send some more money to F so that it creates a new commitment tx
    val amountMsat1 = MilliSatoshi(100000000L)
    sender.send(nodes("F5").paymentHandler, ReceivePayment(Some(amountMsat1), "1 coffee"))
    val pr1 = sender.expectMsgType[PaymentRequest]
    val sendReq1 = SendPayment(100000000L, pr1.paymentHash, nodes("F5").nodeParams.nodeId)
    sender.send(nodes("A").paymentInitiator, sendReq1)
    sender.expectMsgType[PaymentSucceeded]
    // we also retrieve C's default final address
    sender.send(nodes("C").register, Forward(channelId, CMD_GETSTATEDATA))
    val finalAddressC = scriptPubKeyToAddress(sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey)
    // and we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
    // then we publish F's previous commit tx
    sender.send(bitcoincli, BitcoinReq("sendrawtransaction", localCommitTxF.commitTx.tx.toString()))
    sender.expectMsgType[JValue](10000 seconds)
    // at this point C should have 2 recv transactions: its previous main output and the one it took from F as a punishment
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddressC)).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    // this will remove the channel
    awaitAnnouncements(nodes.filter(_._1 == "A"), 4, 5, 12)
  }

  test("generate and validate lots of channels") {
    implicit val extendedClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    // we simulate fake channels by publishing a funding tx and sending announcement messages to a node at random
    logger.info(s"generating fake channels")
    val sender = TestProbe()
    val channels = for (i <- 0 until 242) yield {
      // let's generate a block every 10 txs so that we can compute short ids
      if (i % 10 == 0) {
        sender.send(bitcoincli, BitcoinReq("generate", 1))
        sender.expectMsgType[JValue](10 seconds)
      }
      AnnouncementsBatchValidationSpec.simulateChannel
    }
    sender.send(bitcoincli, BitcoinReq("generate", 1))
    sender.expectMsgType[JValue](10 seconds)
    logger.info(s"simulated ${channels.size} channels")
    // then we make the announcements
    val announcements = channels.map(c => AnnouncementsBatchValidationSpec.makeChannelAnnouncement(c))
    announcements.foreach(ann => nodes("A").router ! ann)
    // we need to send channel_update otherwise router won't validate the channels
    val updates = channels.zip(announcements).map(x => AnnouncementsBatchValidationSpec.makeChannelUpdate(x._1, x._2.shortChannelId))
    updates.foreach(update => nodes("A").router ! update)
    awaitCond({
      sender.send(nodes("D").router, 'channels)
      sender.expectMsgType[Iterable[ChannelAnnouncement]](5 seconds).size == channels.size + 5 // 5 remaining channels because  D->F{1-F4} have disappeared
    }, max = 120 seconds, interval = 1 second)
  }


}
