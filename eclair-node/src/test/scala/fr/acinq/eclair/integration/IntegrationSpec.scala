package fr.acinq.eclair.integration

import java.nio.file.{Files, Paths}
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
import fr.acinq.eclair.payment.{CreatePayment, PaymentError, PaymentFailed, PaymentSucceeded}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import fr.acinq.eclair.{NodeParams, Setup}
import grizzled.slf4j.Logging
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

  var bitcoind: Process = null
  var bitcoincli: ActorRef = null
  var setupA: Setup = null
  var setupB: Setup = null
  var setupC: Setup = null
  var setupD: Setup = null

  case class BitcoinReq(method: String, params: Any*)

  override def beforeAll(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR)
    Files.createDirectories(PATH_ECLAIR_DATADIR_A)
    Files.createDirectories(PATH_ECLAIR_DATADIR_B)
    Files.createDirectories(PATH_ECLAIR_DATADIR_C)
    Files.createDirectories(PATH_ECLAIR_DATADIR_D)
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf"), Paths.get(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_A.conf"), Paths.get(PATH_ECLAIR_DATADIR_A.toString, "eclair.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_B.conf"), Paths.get(PATH_ECLAIR_DATADIR_B.toString, "eclair.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_C.conf"), Paths.get(PATH_ECLAIR_DATADIR_C.toString, "eclair.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_D.conf"), Paths.get(PATH_ECLAIR_DATADIR_D.toString, "eclair.conf"))

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
    setupA = new Setup(PATH_ECLAIR_DATADIR_A.toString)
    setupB = new Setup(PATH_ECLAIR_DATADIR_B.toString)
    setupC = new Setup(PATH_ECLAIR_DATADIR_C.toString)
    setupD = new Setup(PATH_ECLAIR_DATADIR_D.toString)
    setupA.boostrap
    setupB.boostrap
    setupC.boostrap
    setupD.boostrap
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

  test("connect A->B->C->D") {
    connect(setupA, setupB, 1000000, 0)
    connect(setupB, setupC, 1000000, 0)
    connect(setupC, setupD, 500000, 0)
  }

  test("wait for network announcements") {
    val sender = TestProbe()
    // generating more blocks so that all funding txes are buried under at least 6 blocks
    sender.send(bitcoincli, BitcoinReq("generate", 6))
    sender.expectMsgType[JValue]
    // wait for A to know all nodes and channels
    awaitCond({
      sender.send(setupA.router, 'nodes)
      sender.expectMsgType[Iterable[NodeAnnouncement]].size == 4
    }, max = 20 seconds, interval = 1 second)
    awaitCond({
      sender.send(setupA.router, 'channels)
      sender.expectMsgType[Iterable[ChannelAnnouncement]].size == 3
    }, max = 20 seconds, interval = 1 second)
    awaitCond({
      sender.send(setupA.router, 'updates)
      sender.expectMsgType[Iterable[ChannelUpdate]].size == 6
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
    // A calculated the cltv expiry like so: PaymentLifecycle.defaultHtlcExpiry + previous-expiry-delta-C = 10 + 144 = 154
    sender.expectMsgType[PaymentFailed].error === Some(PaymentError(setupC.nodeParams.privateKey.publicKey, FailureMessage.incorrect_cltv_expiry(154, channelUpdateCD)))
    // let's say than A is notified later on about C's channel update
    sender.send(setupA.router, channelUpdateCD)
    // we wait for A to receive it
    awaitCond({
      sender.send(setupA.router, 'updates)
      sender.expectMsgType[Iterable[ChannelUpdate]].toSeq.contains(channelUpdateCD)
    }, max = 20 seconds, interval = 1 second)
    // finally we retry the same payment, this time successfully
    sender.send(setupA.paymentInitiator, paymentReq)
    sender.expectMsgType[PaymentSucceeded]
  }

  test("send an HTLC A->D with an amount greater than capacity of C-D") {
    val sender = TestProbe()
    // first we retrieve a payment hash from D
    sender.send(setupD.paymentHandler, 'genh)
    val paymentHash = sender.expectMsgType[BinaryData]
    // then we make the payment (C-D has a smaller capacity than A-B and B-C)
    val paymentReq = CreatePayment(600000000L, paymentHash, setupD.nodeParams.privateKey.publicKey)
    sender.send(setupA.paymentInitiator, paymentReq)
    sender.expectMsgType[PaymentFailed].error === Some(PaymentError(setupC.nodeParams.privateKey.publicKey, FailureMessage.permanent_node_failure))
  }

}
