package fr.acinq.eclair.integration

import java.nio.file.{Files, Paths}
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi, Satoshi}
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.channel.Register.ForwardShortId
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
import fr.acinq.eclair.payment.{CreatePayment, PaymentFailed, PaymentSucceeded}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{NodeParams, Setup}
import grizzled.slf4j.Logging
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JValue
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.sys.process._

/**
  * Created by PM on 15/03/2017.
  */
@RunWith(classOf[JUnitRunner])
class IntegrationSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll with Logging {

  val INTEGRATION_TMP_DIR = s"${System.getProperty("buildDirectory")}/integration-${UUID.randomUUID().toString}"
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = Paths.get(System.getProperty("buildDirectory"), "bitcoin-0.14.0/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = Paths.get(INTEGRATION_TMP_DIR, "datadir-bitcoin")
  val PATH_ECLAIR_DATADIR_A = Paths.get(INTEGRATION_TMP_DIR, "datadir-eclair-A")
  val PATH_ECLAIR_DATADIR_B = Paths.get(INTEGRATION_TMP_DIR, "datadir-eclair-B")
  val PATH_ECLAIR_DATADIR_C = Paths.get(INTEGRATION_TMP_DIR, "datadir-eclair-C")
  val PATH_ECLAIR_DATADIR_D = Paths.get(INTEGRATION_TMP_DIR, "datadir-eclair-D")
  val PATH_ECLAIR_DATADIR_E = Paths.get(INTEGRATION_TMP_DIR, "datadir-eclair-E")
  val PATH_ECLAIR_DATADIR_F = Paths.get(INTEGRATION_TMP_DIR, "datadir-eclair-F")

  var bitcoind: Process = null
  var bitcoincli: ActorRef = null
  var setupA: Setup = null
  var setupB: Setup = null
  var setupC: Setup = null
  var setupD: Setup = null
  var setupE: Setup = null
  var setupF: Setup = null

  implicit val formats = DefaultFormats

  case class BitcoinReq(method: String, params: Any*)

  override def beforeAll(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR)
    Files.createDirectories(PATH_ECLAIR_DATADIR_A)
    Files.createDirectories(PATH_ECLAIR_DATADIR_B)
    Files.createDirectories(PATH_ECLAIR_DATADIR_C)
    Files.createDirectories(PATH_ECLAIR_DATADIR_D)
    Files.createDirectories(PATH_ECLAIR_DATADIR_E)
    Files.createDirectories(PATH_ECLAIR_DATADIR_F)
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf"), Paths.get(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_A.conf"), Paths.get(PATH_ECLAIR_DATADIR_A.toString, "eclair.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_B.conf"), Paths.get(PATH_ECLAIR_DATADIR_B.toString, "eclair.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_C.conf"), Paths.get(PATH_ECLAIR_DATADIR_C.toString, "eclair.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_D.conf"), Paths.get(PATH_ECLAIR_DATADIR_D.toString, "eclair.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_E.conf"), Paths.get(PATH_ECLAIR_DATADIR_E.toString, "eclair.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_F.conf"), Paths.get(PATH_ECLAIR_DATADIR_F.toString, "eclair.conf"))

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoincli = system.actorOf(Props(new Actor {

      import scala.concurrent.ExecutionContext.Implicits.global

      val config = NodeParams.loadConfiguration(PATH_ECLAIR_DATADIR_A.toFile)
      val client = new BitcoinJsonRPCClient(
        user = config.getString("bitcoind.rpcuser"),
        password = config.getString("bitcoind.rpcpassword"),
        host = config.getString("bitcoind.host"),
        port = config.getInt("bitcoind.rpcport"))

      override def receive: Receive = {
        case BitcoinReq(method) => client.invoke(method) pipeTo sender
        case BitcoinReq(method, params) => client.invoke(method, params) pipeTo sender
      }
    }))
  }

  override def afterAll(): Unit = {
    logger.info(s"killing bitcoind")
    bitcoind.destroy()
    setupA.system.terminate()
    setupB.system.terminate()
    setupC.system.terminate()
    setupD.system.terminate()
    setupE.system.terminate()
    setupF.system.terminate()
//    logger.warn(s"starting bitcoin-qt")
//    val PATH_BITCOINQT = Paths.get(System.getProperty("buildDirectory"), "bitcoin-0.14.0/bin/bitcoin-qt")
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
    sender.send(bitcoincli, BitcoinReq("getinfo"))
    sender.expectMsgType[JValue](10 seconds)
  }

  test("starting eclair nodes") {
    setupA = new Setup(PATH_ECLAIR_DATADIR_A.toString, actorSystemName = "system-A")
    setupB = new Setup(PATH_ECLAIR_DATADIR_B.toString, actorSystemName = "system-B")
    setupC = new Setup(PATH_ECLAIR_DATADIR_C.toString, actorSystemName = "system-C")
    setupD = new Setup(PATH_ECLAIR_DATADIR_D.toString, actorSystemName = "system-D")
    setupE = new Setup(PATH_ECLAIR_DATADIR_E.toString, actorSystemName = "system-E")
    setupF = new Setup(PATH_ECLAIR_DATADIR_F.toString, actorSystemName = "system-F")
    setupA.boostrap
    setupB.boostrap
    setupC.boostrap
    setupD.boostrap
    setupE.boostrap
    setupF.boostrap
  }

  def connect(node1: Setup, node2: Setup, fundingSatoshis: Long, pushMsat: Long) = {
    val eventListener = TestProbe()
    node1.system.eventStream.subscribe(eventListener.ref, classOf[ChannelStateChanged])
    val sender = TestProbe()
    sender.send(node1.switchboard, NewConnection(
      remoteNodeId = node2.nodeParams.privateKey.publicKey,
      address = node2.nodeParams.address,
      newChannel_opt = Some(NewChannel(Satoshi(fundingSatoshis), MilliSatoshi(pushMsat)))))
    sender.expectMsg("connected")
    // waiting for channel to publish funding tx
    awaitCond(eventListener.expectMsgType[ChannelStateChanged](5 seconds).currentState == WAIT_FOR_FUNDING_CONFIRMED)
    // confirming funding tx
    sender.send(bitcoincli, BitcoinReq("generate", 3))
    sender.expectMsgType[JValue](10 seconds)
    // waiting for channel to reach normal
    awaitCond(eventListener.expectMsgType[ChannelStateChanged](5 seconds).currentState == NORMAL)
    node1.system.eventStream.unsubscribe(eventListener.ref)
  }

  test("connect A->B->C->D and B->E->C and C->F") {
    connect(setupA, setupB, 1000000, 0)
    connect(setupB, setupC, 200000, 0)
    connect(setupC, setupD, 500000, 0)

    connect(setupB, setupE, 500000, 0)
    connect(setupE, setupC, 500000, 0)

    connect(setupC, setupF, 500000, 0)
  }

  test("wait for network announcements") {
    val sender = TestProbe()
    // generating more blocks so that all funding txes are buried under at least 6 blocks
    sender.send(bitcoincli, BitcoinReq("generate", 6))
    sender.expectMsgType[JValue]
    // wait for A to know all nodes and channels
    awaitCond({
      sender.send(setupA.router, 'nodes)
      sender.expectMsgType[Iterable[NodeAnnouncement]].size == 6
    }, max = 20 seconds, interval = 1 second)
    awaitCond({
      sender.send(setupA.router, 'channels)
      sender.expectMsgType[Iterable[ChannelAnnouncement]].size == 6
    }, max = 20 seconds, interval = 1 second)
    awaitCond({
      sender.send(setupA.router, 'updates)
      sender.expectMsgType[Iterable[ChannelUpdate]].size == 12
    }, max = 20 seconds, interval = 1 second)
  }

  test("send an HTLC A->D") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D
    sender.send(setupD.paymentHandler, 'genh)
    val paymentHash = sender.expectMsgType[BinaryData]
    // then we make the actual payment
    sender.send(setupA.paymentInitiator, CreatePayment(4200000, paymentHash, setupD.nodeParams.privateKey.publicKey))
    sender.expectMsgType[PaymentSucceeded]
  }

  test("send an HTLC A->D with an invalid expiry delta for C") {
    val sender = TestProbe()
    // to simulate this, we will update C's relay params
    // first we find out the short channel id for channel C-D, easiest way is to ask D's register which has only one channel
    sender.send(setupD.register, 'shortIds)
    val shortIdCD = sender.expectMsgType[Map[Long, BinaryData]].keys.head
    val channelUpdateCD = Announcements.makeChannelUpdate(setupC.nodeParams.privateKey, setupD.nodeParams.privateKey.publicKey, shortIdCD, setupD.nodeParams.expiryDeltaBlocks + 1, setupD.nodeParams.htlcMinimumMsat, setupD.nodeParams.feeBaseMsat, setupD.nodeParams.feeProportionalMillionth, Platform.currentTime / 1000)
    sender.send(setupC.relayer, channelUpdateCD)
    // first we retrieve a payment hash from D
    sender.send(setupD.paymentHandler, 'genh)
    val paymentHash = sender.expectMsgType[BinaryData]
    // then we make the actual payment
    val paymentReq = CreatePayment(4200000, paymentHash, setupD.nodeParams.privateKey.publicKey)
    sender.send(setupA.paymentInitiator, paymentReq)
    // A will receive an error from C that include the updated channel update, then will retry the payment
    sender.expectMsgType[PaymentSucceeded](5 seconds)
    // in the meantime, the router will have updated its state
    awaitCond({
      sender.send(setupA.router, 'updates)
      sender.expectMsgType[Iterable[ChannelUpdate]].toSeq.contains(channelUpdateCD)
    }, max = 20 seconds, interval = 1 second)
    // finally we retry the same payment, this time successfully
  }

  test("send an HTLC A->D with an amount greater than capacity of C-D") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D
    sender.send(setupD.paymentHandler, 'genh)
    val paymentHash = sender.expectMsgType[BinaryData]
    // then we make the payment (C-D has a smaller capacity than A-B and B-C)
    val paymentReq = CreatePayment(300000000L, paymentHash, setupD.nodeParams.privateKey.publicKey)
    sender.send(setupA.paymentInitiator, paymentReq)
    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    sender.expectMsgType[PaymentSucceeded](5 seconds)
  }

  test("send an HTLC A->D with an unknown payment hash") {
    val sender = TestProbe()
    val paymentHash = "42" * 32
    val paymentReq = CreatePayment(100000000L, paymentHash, setupD.nodeParams.privateKey.publicKey)
    sender.send(setupA.paymentInitiator, paymentReq)
    // A will first receive an error from C, then retry and route around C: A->B->E->C->D
    sender.expectMsg(PaymentFailed(paymentHash, Some(ErrorPacket(setupD.nodeParams.privateKey.publicKey, UnknownPaymentHash))))
  }

  test("send an HTLC A->B->C->F which times out between C and F and is fulfilled by F") {
    // NB: F has a no-op payment handler, allowing us to manually fulfill htlcs
    val htlcReceiver = TestProbe()
    // we register this probe as the final payment handler
    setupF.paymentHandler ! htlcReceiver.ref
    val sender = TestProbe()
    // we will need the channel id CF later
    sender.send(setupF.register, 'shortIds)
    val shortIdCF = sender.expectMsgType[Map[Long, BinaryData]].keys.head
    val preimage: BinaryData = "42" * 32
    val paymentHash = Crypto.sha256(preimage)
    // A sends a payment to F
    val paymentReq = CreatePayment(10000000L, paymentHash, setupF.nodeParams.privateKey.publicKey)
    sender.send(setupA.paymentInitiator, paymentReq)
    // F gets the htlc
    val htlc = htlcReceiver.expectMsgType[(UpdateAddHtlc, BinaryData)]._1
    // we then kill the connection between C and F
    sender.send(setupC.switchboard, 'connections)
    val connections = sender.expectMsgType[Map[PublicKey, ActorRef]]
    val connCF = connections(setupF.nodeParams.privateKey.publicKey)
    connCF ! PoisonPill
    // we then wait for C to be in disconnected state
    awaitCond({
      sender.send(setupC.register, ForwardShortId(shortIdCF, CMD_GETSTATE))
      sender.expectMsgType[State] == OFFLINE
    }, max = 20 seconds, interval = 1 second)
    // we then fulfill the htlc on F's side (it will forward the fulfill to C but C won't get it)
    //htlcReceiver.reply(CMD_FULFILL_HTLC(htlc.id, preimage, commit = false))
    // we then generate enough blocks to make the htlc timeout
    sender.send(bitcoincli, BitcoinReq("generate", 10))
    sender.expectMsgType[JValue](10 seconds)
    // this will make C publish its commitment tx
    awaitCond({
      sender.send(setupC.register, ForwardShortId(shortIdCF, CMD_GETSTATE))
      sender.expectMsgType[State] == CLOSING
    }, max = 20 seconds, interval = 1 second)
    // which will in reaction make F publish its commitment tx
    awaitCond({
      sender.send(setupF.register, ForwardShortId(shortIdCF, CMD_GETSTATE))
      sender.expectMsgType[State] == CLOSING
    }, max = 20 seconds, interval = 1 second)
    // we then generate enough blocks to confirm all delayed transactions
    sender.send(bitcoincli, BitcoinReq("generate", 150))
    sender.expectMsgType[JValue](10 seconds)
    // at this point C should have 2 recv transactions: its main output and the htlc timeout
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("listreceivedbyaddress", 0))
      val res = sender.expectMsgType[JValue](10 seconds)
      res.children.exists(c => (c \ "address").extract[String] == setupC.finalAddress && (c \ "txids").children.size == 2)
    }, max = 60 seconds, interval = 1 second)

  }

}
