package fr.acinq.eclair.integration

import java.io.{File, PrintWriter}
import java.nio.file.Files
import java.util.{Properties, UUID}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, Crypto, MilliSatoshi, Satoshi, Script}
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, Watch, WatchConfirmed}
import fr.acinq.eclair.channel.Register.Forward
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.io.Disconnect
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
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
  var finalAddresses: Map[String, String] = Map()

  implicit val formats = DefaultFormats

  case class BitcoinReq(method: String, params: Any*)

  override def beforeAll(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf"), new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoinrpcclient = new BitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 28332)
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
    //bitcoind.destroy()
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
      sender.send(bitcoincli, BitcoinReq("getinfo"))
      sender.receiveOne(5 second).isInstanceOf[JValue]
    }, max = 30 seconds, interval = 500 millis)
    logger.info(s"generating initial blocks...")
    sender.send(bitcoincli, BitcoinReq("generate", 500))
    sender.expectMsgType[JValue](10 seconds)
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
    finalAddresses = finalAddresses + (name -> setup.finalAddress)
    nodes = nodes + (name -> kit)
  }

  def javaProps(props: Seq[(String, String)]) = {
    val properties = new Properties()
    props.foreach(p => properties.setProperty(p._1, p._2))
    properties
  }

  test("starting eclair nodes") {
    import collection.JavaConversions._
    val commonConfig = ConfigFactory.parseMap(Map("eclair.server.public-ips.1" -> "localhost", "eclair.bitcoind.port" -> 28333, "eclair.bitcoind.rpcport" -> 28332, "eclair.bitcoind.zmq" -> "tcp://127.0.0.1:28334", "eclair.router-broadcast-interval" -> "2 second", "eclair.auto-reconnect" -> false))
    instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.server.port" -> 29730, "eclair.api.port" -> 28080)).withFallback(commonConfig))
    instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.server.port" -> 29731, "eclair.api.port" -> 28081)).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.server.port" -> 29732, "eclair.api.port" -> 28082)).withFallback(commonConfig))
    instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.server.port" -> 29733, "eclair.api.port" -> 28083)).withFallback(commonConfig))
    instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.server.port" -> 29734, "eclair.api.port" -> 28084)).withFallback(commonConfig))
    instantiateEclairNode("F1", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F1", "eclair.server.port" -> 29735, "eclair.api.port" -> 28085, "eclair.payment-handler" -> "noop")).withFallback(commonConfig)) // NB: eclair.payment-handler = noop allows us to manually fulfill htlcs
    instantiateEclairNode("F2", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F2", "eclair.server.port" -> 29736, "eclair.api.port" -> 28086, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
    instantiateEclairNode("F3", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F3", "eclair.server.port" -> 29737, "eclair.api.port" -> 28087, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
    instantiateEclairNode("F4", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F4", "eclair.server.port" -> 29738, "eclair.api.port" -> 28088, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
  }

  def connect(node1: Kit, node2: Kit, fundingSatoshis: Long, pushMsat: Long) = {
    val eventListener1 = TestProbe()
    val eventListener2 = TestProbe()
    node1.system.eventStream.subscribe(eventListener1.ref, classOf[ChannelStateChanged])
    node2.system.eventStream.subscribe(eventListener2.ref, classOf[ChannelStateChanged])
    val sender = TestProbe()
    sender.send(node1.switchboard, NewConnection(
      remoteNodeId = node2.nodeParams.privateKey.publicKey,
      address = node2.nodeParams.publicAddresses.head,
      newChannel_opt = Some(NewChannel(Satoshi(fundingSatoshis), MilliSatoshi(pushMsat), None))))
    sender.expectMsgAnyOf(10 seconds, "connected", s"already connected to nodeId=${node2.nodeParams.privateKey.publicKey.toBin}")
    // funder transitions
    assert(eventListener1.expectMsgType[ChannelStateChanged](10 seconds).currentState == WAIT_FOR_ACCEPT_CHANNEL)
    assert(eventListener1.expectMsgType[ChannelStateChanged](10 seconds).currentState == WAIT_FOR_FUNDING_INTERNAL)
    assert(eventListener1.expectMsgType[ChannelStateChanged](10 seconds).currentState == WAIT_FOR_FUNDING_PARENT)
    // fundee transitions
    assert(eventListener2.expectMsgType[ChannelStateChanged](10 seconds).currentState == WAIT_FOR_OPEN_CHANNEL)
    assert(eventListener2.expectMsgType[ChannelStateChanged](10 seconds).currentState == WAIT_FOR_FUNDING_CREATED)
  }

  test("connect nodes") {
    //
    // A ---- B ---- C ---- D
    //        |     / \
    //        --E--'   F{1,2,3,4}
    //

    connect(nodes("A"), nodes("B"), 10000000, 0)
    connect(nodes("B"), nodes("C"), 2000000, 0)
    connect(nodes("C"), nodes("D"), 5000000, 0)
    connect(nodes("B"), nodes("E"), 5000000, 0)
    connect(nodes("E"), nodes("C"), 5000000, 0)
    connect(nodes("C"), nodes("F1"), 5000000, 0)
    connect(nodes("C"), nodes("F2"), 5000000, 0)
    connect(nodes("C"), nodes("F3"), 5000000, 0)
    connect(nodes("C"), nodes("F4"), 5000000, 0)

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    // a channel has two endpoints
    val channelEndpointsCount = nodes.values.foldLeft(0) {
      case (sum, setup) =>
        sender.send(setup.register, 'channels)
        val channels = sender.expectMsgType[Map[BinaryData, ActorRef]]
        sum + channels.size
    }

    // each funder sets up a WatchConfirmed on the parent tx, we need to make sure it has been received by the watcher
    var watches1 = Set.empty[Watch]
    awaitCond({
      watches1 = nodes.values.foldLeft(Set.empty[Watch]) {
        case (watches, setup) =>
          sender.send(setup.watcher, 'watches)
          watches ++ sender.expectMsgType[Set[Watch]]
      }
      watches1.count(_.isInstanceOf[WatchConfirmed]) == channelEndpointsCount / 2
    }, max = 10 seconds, interval = 1 second)

    // confirming the parent tx of the funding
    sender.send(bitcoincli, BitcoinReq("generate", 1))
    sender.expectMsgType[JValue](10 seconds)

    within(30 seconds) {
      var count = 0
      while (count < channelEndpointsCount) {
        if (eventListener.expectMsgType[ChannelStateChanged](10 seconds).currentState == WAIT_FOR_FUNDING_CONFIRMED) count = count + 1
      }
    }

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      val watches2 = nodes.values.foldLeft(Set.empty[Watch]) {
        case (watches, setup) =>
          sender.send(setup.watcher, 'watches)
          watches ++ sender.expectMsgType[Set[Watch]]
      }
      (watches2 -- watches1).count(_.isInstanceOf[WatchConfirmed]) == channelEndpointsCount
    }, max = 10 seconds, interval = 1 second)


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
          sender.expectMsgType[Iterable[NodeAnnouncement]].size == nodes
        }, max = 60 seconds, interval = 1 second)
        awaitCond({
          sender.send(setup.router, 'channels)
          sender.expectMsgType[Iterable[ChannelAnnouncement]].size == channels
        }, max = 60 seconds, interval = 1 second)
        awaitCond({
          sender.send(setup.router, 'updates)
          sender.expectMsgType[Iterable[ChannelUpdate]].size == updates
        }, max = 60 seconds, interval = 1 second)
    }
  }

  test("wait for network announcements") {
    val sender = TestProbe()
    // generating more blocks so that all funding txes are buried under at least 6 blocks
    sender.send(bitcoincli, BitcoinReq("generate", 4))
    sender.expectMsgType[JValue]
    awaitAnnouncements(nodes, 9, 9, 18)
  }

  test("send an HTLC A->D") {
    val sender = TestProbe()
    val amountMsat = MilliSatoshi(4200000)
    // first we retrieve a payment hash from D
    sender.send(nodes("D").paymentHandler, ReceivePayment(amountMsat, "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator,
      SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.privateKey.publicKey))
    sender.expectMsgType[PaymentSucceeded]
  }

  test("send an HTLC A->D with an invalid expiry delta for C") {
    val sender = TestProbe()
    // to simulate this, we will update C's relay params
    // first we find out the short channel id for channel C-D, easiest way is to ask D's register which has only one channel
    sender.send(nodes("D").register, 'shortIds)
    val shortIdCD = sender.expectMsgType[Map[Long, BinaryData]].keys.head
    val channelUpdateCD = Announcements.makeChannelUpdate(nodes("C").nodeParams.privateKey, nodes("D").nodeParams.privateKey.publicKey, shortIdCD, nodes("D").nodeParams.expiryDeltaBlocks + 1, nodes("D").nodeParams.htlcMinimumMsat, nodes("D").nodeParams.feeBaseMsat, nodes("D").nodeParams.feeProportionalMillionth)
    sender.send(nodes("C").relayer, channelUpdateCD)
    // first we retrieve a payment hash from D
    val amountMsat = MilliSatoshi(4200000)
    sender.send(nodes("D").paymentHandler, ReceivePayment(amountMsat, "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment
    val sendReq = SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.privateKey.publicKey)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will receive an error from C that include the updated channel update, then will retry the payment
    sender.expectMsgType[PaymentSucceeded](5 seconds)
    // in the meantime, the router will have updated its state
    awaitCond({
      sender.send(nodes("A").router, 'updates)
      sender.expectMsgType[Iterable[ChannelUpdate]].toSeq.contains(channelUpdateCD)
    }, max = 20 seconds, interval = 1 second)
    // finally we retry the same payment, this time successfully
  }

  test("send an HTLC A->D with an amount greater than capacity of C-D") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D
    val amountMsat = MilliSatoshi(300000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(amountMsat, "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the payment (C-D has a smaller capacity than A-B and B-C)
    val sendReq = SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.privateKey.publicKey)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    sender.expectMsgType[PaymentSucceeded](5 seconds)
  }

  test("send an HTLC A->D with an unknown payment hash") {
    val sender = TestProbe()
    val pr = SendPayment(100000000L, "42" * 32, nodes("D").nodeParams.privateKey.publicKey)
    sender.send(nodes("A").paymentInitiator, pr)

    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("D").nodeParams.privateKey.publicKey, UnknownPaymentHash))
  }

  test("send an HTLC A->D with a lower amount than requested") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(amountMsat, "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of only 1 mBTC
    val sendReq = SendPayment(100000000L, pr.paymentHash, nodes("D").nodeParams.privateKey.publicKey)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("D").nodeParams.privateKey.publicKey, IncorrectPaymentAmount))
  }

  test("send an HTLC A->D with too much overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(amountMsat, "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of 6 mBTC
    val sendReq = SendPayment(600000000L, pr.paymentHash, nodes("D").nodeParams.privateKey.publicKey)
    sender.send(nodes("A").paymentInitiator, sendReq)

    // A will first receive an IncorrectPaymentAmount error from D
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("D").nodeParams.privateKey.publicKey, IncorrectPaymentAmount))
  }

  test("send an HTLC A->D with a reasonable overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(amountMsat, "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of 3 mBTC, more than asked but it should still be accepted
    val sendReq = SendPayment(300000000L, pr.paymentHash, nodes("D").nodeParams.privateKey.publicKey)
    sender.send(nodes("A").paymentInitiator, sendReq)
    sender.expectMsgType[PaymentSucceeded]
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (local commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddresses("C"))).flatMap(_ \ "txids" \\ classOf[JString])
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F1").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F1").nodeParams.privateKey.publicKey, maxAttempts = 1)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
    // we then kill the connection between C and F
    sender.send(nodes("F1").switchboard, 'peers)
    val peers = sender.expectMsgType[Map[PublicKey, ActorRef]]
    peers(nodes("C").nodeParams.privateKey.publicKey) ! Disconnect
    // we then wait for F to be in disconnected state
    awaitCond({
      sender.send(nodes("F1").register, Forward(htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[State] == OFFLINE
    }, max = 20 seconds, interval = 1 second)
    // we then have C unilateral close the channel (which will make F redeem the htlc onchain)
    sender.send(nodes("C").register, Forward(htlc.channelId, INPUT_PUBLISH_LOCALCOMMIT))
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
      res.filter(_ \ "address" == JString(finalAddresses("F1"))).flatMap(_ \ "txids" \\ classOf[JString]).size == 1
    }, max = 30 seconds, interval = 1 second)
    // we then generate enough blocks so that C gets its main delayed output
    sender.send(bitcoincli, BitcoinReq("generate", 145))
    sender.expectMsgType[JValue](10 seconds)
    // and C will have its main output
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddresses("C"))).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 1
    }, max = 30 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 8, 8, 16)
  }

  test("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (remote commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddresses("C"))).flatMap(_ \ "txids" \\ classOf[JString])
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F2").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F2").nodeParams.privateKey.publicKey, maxAttempts = 1)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
    // we then kill the connection between C and F
    sender.send(nodes("F2").switchboard, 'peers)
    val peers = sender.expectMsgType[Map[PublicKey, ActorRef]]
    peers(nodes("C").nodeParams.privateKey.publicKey) ! Disconnect
    // we then wait for F to be in disconnected state
    awaitCond({
      sender.send(nodes("F2").register, Forward(htlc.channelId, CMD_GETSTATE))
      sender.expectMsgType[State] == OFFLINE
    }, max = 20 seconds, interval = 1 second)
    // then we have F unilateral close the channel
    sender.send(nodes("F2").register, Forward(htlc.channelId, INPUT_PUBLISH_LOCALCOMMIT))
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
      res.filter(_ \ "address" == JString(finalAddresses("F2"))).flatMap(_ \ "txids" \\ classOf[JString]).size == 1
    }, max = 30 seconds, interval = 1 second)
    // and C will have its main output
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddresses("C"))).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 1
    }, max = 30 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 7, 7, 14)
  }

  test("propagate a failure upstream when a downstream htlc times out (local commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddresses("C"))).flatMap(_ \ "txids" \\ classOf[JString])
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F3").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F3").nodeParams.privateKey.publicKey, maxAttempts = 1)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
    // we then generate enough blocks to make the htlc timeout
    sender.send(bitcoincli, BitcoinReq("generate", 11))
    sender.expectMsgType[JValue](10 seconds)
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    assert(failed.paymentHash === paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("C").nodeParams.privateKey.publicKey, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    sender.send(bitcoincli, BitcoinReq("generate", 150))
    sender.expectMsgType[JValue](10 seconds)
    // at this point C should have 2 recv transactions: its main output and the htlc timeout
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddresses("C"))).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 6, 6, 12)
  }

  test("propagate a failure upstream when a downstream htlc times out (remote commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // we also retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
    val res = sender.expectMsgType[JValue](10 seconds)
    val previouslyReceivedByC = res.filter(_ \ "address" == JString(finalAddresses("C"))).flatMap(_ \ "txids" \\ classOf[JString])
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F4").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F4").nodeParams.privateKey.publicKey, maxAttempts = 1)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("A").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
    // then we ask F to unilaterally close the channel
    sender.send(nodes("F4").register, Forward(htlc.channelId, INPUT_PUBLISH_LOCALCOMMIT))
    // we then generate enough blocks to make the htlc timeout
    sender.send(bitcoincli, BitcoinReq("generate", 11))
    sender.expectMsgType[JValue](10 seconds)
    // this will fail the htlc
    val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    assert(failed.paymentHash === paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("C").nodeParams.privateKey.publicKey, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    sender.send(bitcoincli, BitcoinReq("generate", 145))
    sender.expectMsgType[JValue](10 seconds)
    // at this point C should have 2 recv transactions: its main output and the htlc timeout
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      val receivedByC = res.filter(_ \ "address" == JString(finalAddresses("C"))).flatMap(_ \ "txids" \\ classOf[JString])
      (receivedByC diff previouslyReceivedByC).size == 2
    }, max = 30 seconds, interval = 1 second)
    awaitAnnouncements(nodes.filter(_._1 == "A"), 5, 5, 10)
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
    awaitCond({
      sender.send(nodes("D").router, 'channels)
      sender.expectMsgType[Iterable[ChannelAnnouncement]](5 seconds).size == channels.size + 5 // 5 remaining channels because  D->F{1-F4} have disappeared
    }, max = 120 seconds, interval = 1 second)
  }


}
