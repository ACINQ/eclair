package fr.acinq.eclair.integration

import java.io.{File, PrintWriter}
import java.nio.file.Files
import java.util.{Properties, UUID}

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, Block, Crypto, MilliSatoshi, OP_CHECKSIG, OP_DUP, OP_EQUAL, OP_EQUALVERIFY, OP_HASH160, OP_PUSHDATA, Satoshi, Script}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.blockchain.bitcoinj.BitcoinjWallet
import fr.acinq.eclair.blockchain.{Watch, WatchConfirmed}
import fr.acinq.eclair.channel.Register.Forward
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.io.Peer.Disconnect
import fr.acinq.eclair.io.{NodeURI, Peer, Switchboard}
import fr.acinq.eclair.payment.{State => _, _}
import fr.acinq.eclair.router.{Announcements, AnnouncementsBatchValidationSpec}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Bitcoinj, Globals, Kit, Setup}
import grizzled.slf4j.Logging
import org.bitcoinj.core.Transaction
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JValue
import org.junit.Ignore
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.sys.process._

/**
  * Created by PM on 15/03/2017.
  */
@RunWith(classOf[JUnitRunner])
@Ignore
class BasicIntegrationSpvSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll with Logging {

  val INTEGRATION_TMP_DIR = s"${System.getProperty("buildDirectory")}/integration-${UUID.randomUUID().toString}"
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  System.setProperty("spvtest", "true")

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
    Files.copy(classOf[BasicIntegrationSpvSpec].getResourceAsStream("/integration/bitcoin.conf"), new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoinrpcclient = new BitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 28332)
    bitcoincli = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case BitcoinReq(method) => bitcoinrpcclient.invoke(method) pipeTo sender
        case BitcoinReq(method, params) => bitcoinrpcclient.invoke(method, params) pipeTo sender
        case BitcoinReq(method, param1, param2) => bitcoinrpcclient.invoke(method, param1, param2) pipeTo sender
        case BitcoinReq(method, param1, param2, param3) => bitcoinrpcclient.invoke(method, param1, param2, param3) pipeTo sender
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
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
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
    setup.bitcoin.asInstanceOf[Bitcoinj].bitcoinjKit.awaitRunning()
    nodes = nodes + (name -> kit)
  }

  def javaProps(props: Seq[(String, String)]) = {
    val properties = new Properties()
    props.foreach(p => properties.setProperty(p._1, p._2))
    properties
  }

  test("starting eclair nodes") {
    import collection.JavaConversions._
    val commonConfig = ConfigFactory.parseMap(Map("eclair.chain" -> "regtest", "eclair.spv" -> true, "eclair.chain" -> "regtest", "eclair.bitcoinj.static-peers.0.host" -> "localhost", "eclair.bitcoinj.static-peers.0.port" -> 28333, "eclair.server.public-ips.1" -> "localhost", "eclair.bitcoind.port" -> 28333, "eclair.bitcoind.rpcport" -> 28332, "eclair.bitcoind.zmq" -> "tcp://127.0.0.1:28334", "eclair.router-broadcast-interval" -> "2 second", "eclair.auto-reconnect" -> false, "eclair.delay-blocks" -> 6))
    //instantiateEclairNode("A", ConfigFactory.parseMap(Map("eclair.node-alias" -> "A", "eclair.server.port" -> 29730, "eclair.api.port" -> 28080)).withFallback(commonConfig))
    //instantiateEclairNode("B", ConfigFactory.parseMap(Map("eclair.node-alias" -> "B", "eclair.server.port" -> 29731, "eclair.api.port" -> 28081)).withFallback(commonConfig))
    instantiateEclairNode("C", ConfigFactory.parseMap(Map("eclair.node-alias" -> "C", "eclair.server.port" -> 29732, "eclair.api.port" -> 28082)).withFallback(commonConfig))
    //instantiateEclairNode("D", ConfigFactory.parseMap(Map("eclair.node-alias" -> "D", "eclair.server.port" -> 29733, "eclair.api.port" -> 28083)).withFallback(commonConfig))
    //instantiateEclairNode("E", ConfigFactory.parseMap(Map("eclair.node-alias" -> "E", "eclair.server.port" -> 29734, "eclair.api.port" -> 28084)).withFallback(commonConfig))
    //instantiateEclairNode("F1", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F1", "eclair.server.port" -> 29735, "eclair.api.port" -> 28085, "eclair.payment-handler" -> "noop")).withFallback(commonConfig)) // NB: eclair.payment-handler = noop allows us to manually fulfill htlcs
    //instantiateEclairNode("F2", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F2", "eclair.server.port" -> 29736, "eclair.api.port" -> 28086, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
    instantiateEclairNode("F3", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F3", "eclair.server.port" -> 29737, "eclair.api.port" -> 28087, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
    instantiateEclairNode("F4", ConfigFactory.parseMap(Map("eclair.node-alias" -> "F4", "eclair.server.port" -> 29738, "eclair.api.port" -> 28088, "eclair.payment-handler" -> "noop")).withFallback(commonConfig))
  }

  def sendFunds(node: Kit) = {
    val sender = TestProbe()
    val address = Await.result(node.wallet.getFinalAddress, 10 seconds)
    logger.info(s"sending funds to $address")
    sender.send(bitcoincli, BitcoinReq("sendtoaddress", address, 1.0))
    sender.expectMsgType[JValue](10 seconds)
    awaitCond({
      node.wallet.getBalance.pipeTo(sender.ref)
      sender.expectMsgType[Satoshi] > Satoshi(0)
    }, max = 30 seconds, interval = 1 second)
  }

  test("fund eclair wallets") {
    //sendFunds(nodes("A"))
    //sendFunds(nodes("B"))
    sendFunds(nodes("C"))
    //sendFunds(nodes("D"))
    //sendFunds(nodes("E"))
  }

  def connect(node1: Kit, node2: Kit, fundingSatoshis: Long, pushMsat: Long) = {
    val eventListener1 = TestProbe()
    val eventListener2 = TestProbe()
    node1.system.eventStream.subscribe(eventListener1.ref, classOf[ChannelStateChanged])
    node2.system.eventStream.subscribe(eventListener2.ref, classOf[ChannelStateChanged])
    val sender = TestProbe()
    sender.send(node1.switchboard, Peer.Connect(NodeURI(
      nodeId = node2.nodeParams.privateKey.publicKey,
      address = node2.nodeParams.publicAddresses.head)))
    sender.expectMsgAnyOf(10 seconds, "connected", "already connected")
      sender.send(node1.switchboard, Peer.OpenChannel(
        remoteNodeId = node2.nodeParams.privateKey.publicKey,
        fundingSatoshis = Satoshi(fundingSatoshis),
        pushMsat = MilliSatoshi(pushMsat),
        channelFlags = None))
    sender.expectMsgAnyOf(10 seconds, "channel created")

    awaitCond(eventListener1.expectMsgType[ChannelStateChanged](10 seconds).currentState == WAIT_FOR_FUNDING_CONFIRMED, max = 30 seconds, interval = 1 seconds)
    awaitCond(eventListener2.expectMsgType[ChannelStateChanged](10 seconds).currentState == WAIT_FOR_FUNDING_CONFIRMED, max = 30 seconds, interval = 1 seconds)
  }

  test("connect nodes") {
    //
    // A ---- B ---- C ---- D
    //        |     / \
    //        --E--'   F{1,2,3,4}
    //

    val sender = TestProbe()
    val eventListener = TestProbe()
    nodes.values.foreach(_.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged]))

    //connect(nodes("A"), nodes("B"), 10000000, 0)
    //connect(nodes("B"), nodes("C"), 2000000, 0)
    //connect(nodes("C"), nodes("D"), 5000000, 0)
    //connect(nodes("B"), nodes("E"), 5000000, 0)
    //connect(nodes("E"), nodes("C"), 5000000, 0)
    //connect(nodes("C"), nodes("F1"), 5000000, 0)
    //connect(nodes("C"), nodes("F2"), 5000000, 0)
    connect(nodes("C"), nodes("F3"), 5000000, 0)
    connect(nodes("C"), nodes("F4"), 5000000, 0)

    // a channel has two endpoints
    val channelEndpointsCount = nodes.values.foldLeft(0) {
      case (sum, setup) =>
        sender.send(setup.register, 'channels)
        val channels = sender.expectMsgType[Map[BinaryData, ActorRef]]
        sum + channels.size
    }

    // we make sure all channels have set up their WatchConfirmed for the funding tx
    awaitCond({
      nodes.values.foldLeft(Set.empty[Watch]) {
        case (watches, setup) =>
          sender.send(setup.watcher, 'watches)
          watches ++ sender.expectMsgType[Set[Watch]]
      }.count(_.isInstanceOf[WatchConfirmed]) == channelEndpointsCount
    }, max = 10 seconds, interval = 1 second)

    // confirming the funding tx
    sender.send(bitcoincli, BitcoinReq("generate", 2))
    sender.expectMsgType[JValue](10 seconds)

    within(60 seconds) {
      var count = 0
      while (count < channelEndpointsCount) {
        if (eventListener.expectMsgType[ChannelStateChanged](10 seconds).currentState == NORMAL) count = count + 1
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
    awaitAnnouncements(nodes, 3, 2, 4)
  }

  ignore("send an HTLC A->D") {
    val sender = TestProbe()
    val amountMsat = MilliSatoshi(4200000)
    // first we retrieve a payment hash from D
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the actual payment
    sender.send(nodes("A").paymentInitiator,
      SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.privateKey.publicKey))
    sender.expectMsgType[PaymentSucceeded]
  }

  ignore("send an HTLC A->D with an invalid expiry delta for C") {
    val sender = TestProbe()
    // to simulate this, we will update C's relay params
    // first we find out the short channel id for channel C-D, easiest way is to ask D's register which has only one channel
    sender.send(nodes("D").register, 'shortIds)
    val shortIdCD = sender.expectMsgType[Map[Long, BinaryData]].keys.head
    val channelUpdateCD = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.blockId, nodes("C").nodeParams.privateKey, nodes("D").nodeParams.privateKey.publicKey, shortIdCD, nodes("D").nodeParams.expiryDeltaBlocks + 1, nodes("D").nodeParams.htlcMinimumMsat, nodes("D").nodeParams.feeBaseMsat, nodes("D").nodeParams.feeProportionalMillionth)
    sender.send(nodes("C").relayer, channelUpdateCD)
    // first we retrieve a payment hash from D
    val amountMsat = MilliSatoshi(4200000)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
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

  ignore("send an HTLC A->D with an amount greater than capacity of C-D") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D
    val amountMsat = MilliSatoshi(300000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    // then we make the payment (C-D has a smaller capacity than A-B and B-C)
    val sendReq = SendPayment(amountMsat.amount, pr.paymentHash, nodes("D").nodeParams.privateKey.publicKey)
    sender.send(nodes("A").paymentInitiator, sendReq)
    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    sender.expectMsgType[PaymentSucceeded](5 seconds)
  }

  ignore("send an HTLC A->D with an unknown payment hash") {
    val sender = TestProbe()
    val pr = SendPayment(100000000L, "42" * 32, nodes("D").nodeParams.privateKey.publicKey)
    sender.send(nodes("A").paymentInitiator, pr)

    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    val failed = sender.expectMsgType[PaymentFailed]
    assert(failed.paymentHash === pr.paymentHash)
    assert(failed.failures.size === 1)
    assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("D").nodeParams.privateKey.publicKey, UnknownPaymentHash))
  }

  ignore("send an HTLC A->D with a lower amount than requested") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
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

  ignore("send an HTLC A->D with too much overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
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

  ignore("send an HTLC A->D with a reasonable overpayment") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D for 2 mBTC
    val amountMsat = MilliSatoshi(200000000L)
    sender.send(nodes("D").paymentHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]

    // A send payment of 3 mBTC, more than asked but it should still be accepted
    val sendReq = SendPayment(300000000L, pr.paymentHash, nodes("D").nodeParams.privateKey.publicKey)
    sender.send(nodes("A").paymentInitiator, sendReq)
    sender.expectMsgType[PaymentSucceeded]
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
    case OP_HASH160 :: OP_PUSHDATA(pubKeyHash, _) :: OP_EQUAL :: Nil =>
      Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, pubKeyHash)
    case _ => ???
  }

  def incomingTxes(node: Kit) = {
    val sender = TestProbe()
    (for {
      w <- nodes("F1").wallet.asInstanceOf[BitcoinjWallet].fWallet
      txes = w.getWalletTransactions
      incomingTxes = txes.toSet.filter(tx => tx.getTransaction.getValueSentToMe(w).longValue() > 0)
    } yield incomingTxes).pipeTo(sender.ref)
    sender.expectMsgType[Set[Transaction]]
  }

  ignore("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (local commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // we retrieve transactions already received so that we don't take them into account when evaluating the outcome of this test
    val initialTxesC = incomingTxes(nodes("C"))
    val initialTxesF1 = incomingTxes(nodes("F1"))
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
    // at this point F should have received the on-chain tx corresponding to the redeemed htlc
    awaitCond({
      incomingTxes(nodes("F1")).size - initialTxesF1.size == 1
    }, max = 30 seconds, interval = 1 second)
    // we then generate enough blocks so that C gets its main delayed output
    for (i <- 0 until 7) {
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
    }
    // and C will have its main output
    awaitCond({
      incomingTxes(nodes("C")).size - initialTxesC.size == 1
    }, max = 30 seconds, interval = 1 second)
    // TODO: awaitAnnouncements(nodes.filter(_._1 == "A"), 8, 8, 16)
  }

  ignore("propagate a fulfill upstream when a downstream htlc is redeemed on-chain (remote commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("generate", 1))
    sender.expectMsgType[JValue](10 seconds)
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    sender.send(bitcoincli, BitcoinReq("getbestblockhash"))
    val currentBlockHash = sender.expectMsgType[JValue](10 seconds).extract[String]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
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
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalScriptPubkeyC = sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey
    sender.send(nodes("F2").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalScriptPubkeyF = sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey
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
    sender.send(bitcoincli, BitcoinReq("generate", 1))
    sender.expectMsgType[JValue](10 seconds)
    // C will extract the preimage from the blockchain and fulfill the payment upstream
    paymentSender.expectMsgType[PaymentSucceeded](30 seconds)
    // at this point F should have 1 recv transactions: the redeemed htlc
    // we then generate enough blocks so that F gets its htlc-success delayed output
    for (i <- 0 until 7) {
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
    }
    val ext = new ExtendedBitcoinClient(bitcoinrpcclient)
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
      ext.getTxsSinceBlockHash(currentBlockHash).pipeTo(sender.ref)
      val txes = sender.expectMsgType[Seq[fr.acinq.bitcoin.Transaction]].filterNot(fr.acinq.bitcoin.Transaction.isCoinbase(_))
      // at this point F should have 1 recv transactions: the redeemed htlc and C will have its main output
      txes.count(tx => tx.txOut(0).publicKeyScript == finalScriptPubkeyF) == 1 &&
        txes.count(tx => tx.txOut(0).publicKeyScript == finalScriptPubkeyC) == 1
    }, max = 30 seconds, interval = 1 second)
    // TODO: awaitAnnouncements(nodes.filter(_._1 == "A"), 7, 7, 14)
  }

  test("propagate a failure upstream when a downstream htlc times out (local commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("generate", 1))
    sender.expectMsgType[JValue](10 seconds)
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    sender.send(bitcoincli, BitcoinReq("getbestblockhash"))
    val currentBlockHash = sender.expectMsgType[JValue](10 seconds).extract[String]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F3").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F3").nodeParams.privateKey.publicKey, maxAttempts = 1)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("C").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalScriptPubkeyC = sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey
    sender.send(nodes("F3").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalScriptPubkeyF = sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey
    // we then generate enough blocks to make the htlc timeout
    for (i <- 0 until 11) {
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
    }
    // this will fail the htlc
    //val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    //assert(failed.paymentHash === paymentHash)
    //assert(failed.failures.size === 1)
    //assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("C").nodeParams.privateKey.publicKey, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    for (i <- 0 until 7) {
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
    }
    val ext = new ExtendedBitcoinClient(bitcoinrpcclient)
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
      ext.getTxsSinceBlockHash(currentBlockHash).pipeTo(sender.ref)
      val txes = sender.expectMsgType[Seq[fr.acinq.bitcoin.Transaction]].filterNot(fr.acinq.bitcoin.Transaction.isCoinbase(_))
      // at this point C should have 2 recv transactions: its main output and the htlc timeout
      txes.count(tx => tx.txOut(0).publicKeyScript == finalScriptPubkeyF) == 0 &&
        txes.count(tx => tx.txOut(0).publicKeyScript == finalScriptPubkeyC) == 2
    }, max = 30 seconds, interval = 1 second)
    // TODO: awaitAnnouncements(nodes.filter(_._1 == "A"), 6, 6, 12)
  }

  test("propagate a failure upstream when a downstream htlc times out (remote commit)") {
    val sender = TestProbe()
    // first we make sure we are in sync with current blockchain height
    sender.send(bitcoincli, BitcoinReq("generate", 1))
    sender.expectMsgType[JValue](10 seconds)
    sender.send(bitcoincli, BitcoinReq("getblockcount"))
    val currentBlockCount = sender.expectMsgType[JValue](10 seconds).extract[Long]
    sender.send(bitcoincli, BitcoinReq("getbestblockhash"))
    val currentBlockHash = sender.expectMsgType[JValue](10 seconds).extract[String]
    awaitCond(Globals.blockCount.get() == currentBlockCount, max = 20 seconds, interval = 1 second)
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    nodes("F4").paymentHandler ! htlcReceiver.ref
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = SendPayment(100000000L, paymentHash, nodes("F4").nodeParams.privateKey.publicKey, maxAttempts = 1)
    val paymentSender = TestProbe()
    paymentSender.send(nodes("C").paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[UpdateAddHtlc]
    // now that we have the channel id, we retrieve channels default final addresses
    sender.send(nodes("C").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalScriptPubkeyC = sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey
    sender.send(nodes("F4").register, Forward(htlc.channelId, CMD_GETSTATEDATA))
    val finalScriptPubkeyF = sender.expectMsgType[DATA_NORMAL].commitments.localParams.defaultFinalScriptPubKey
    // then we ask F to unilaterally close the channel
    sender.send(nodes("F4").register, Forward(htlc.channelId, INPUT_PUBLISH_LOCALCOMMIT))
    // we then generate enough blocks to make the htlc timeout
    for (i <- 0 until 11) {
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
    }
    // this will fail the htlc
    //val failed = paymentSender.expectMsgType[PaymentFailed](30 seconds)
    //assert(failed.paymentHash === paymentHash)
    //assert(failed.failures.size === 1)
    //assert(failed.failures.head.asInstanceOf[RemoteFailure].e === ErrorPacket(nodes("C").nodeParams.privateKey.publicKey, PermanentChannelFailure))
    // we then generate enough blocks to confirm all delayed transactions
    for (i <- 0 until 7) {
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
    }
    val ext = new ExtendedBitcoinClient(bitcoinrpcclient)
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("generate", 1))
      sender.expectMsgType[JValue](10 seconds)
      ext.getTxsSinceBlockHash(currentBlockHash).pipeTo(sender.ref)
      val txes = sender.expectMsgType[Seq[fr.acinq.bitcoin.Transaction]].filterNot(fr.acinq.bitcoin.Transaction.isCoinbase(_))
      // at this point C should have 2 recv transactions: its main output and the htlc timeout
      txes.count(tx => tx.txOut(0).publicKeyScript == finalScriptPubkeyF) == 0 &&
        txes.count(tx => tx.txOut(0).publicKeyScript == finalScriptPubkeyC) == 2
    }, max = 30 seconds, interval = 1 second)
    // TODO: awaitAnnouncements(nodes.filter(_._1 == "A"), 5, 5, 10)
  }

  ignore("generate and validate lots of channels") {
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
