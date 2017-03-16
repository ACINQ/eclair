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
import fr.acinq.eclair.payment.CreatePayment
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import fr.acinq.eclair.{NodeParams, Setup}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JValue
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

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

  var bitcoind: Process = null
  var bitcoincli: ActorRef = null
  var setupA: Setup = null
  var setupB: Setup = null
  var setupC: Setup = null

  case class BitcoinReq(method: String, params: Any*)

  override def beforeAll(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR)
    Files.createDirectories(PATH_ECLAIR_DATADIR_A)
    Files.createDirectories(PATH_ECLAIR_DATADIR_B)
    Files.createDirectories(PATH_ECLAIR_DATADIR_C)
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf"), Paths.get(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_A.conf"), Paths.get(PATH_ECLAIR_DATADIR_A.toString, "eclair.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_B.conf"), Paths.get(PATH_ECLAIR_DATADIR_B.toString, "eclair.conf"))
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/eclair_C.conf"), Paths.get(PATH_ECLAIR_DATADIR_C.toString, "eclair.conf"))

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
    sender.expectMsgType[JValue]
  }

  test("starting eclair nodes") {
    setupA = new Setup(PATH_ECLAIR_DATADIR_A.toString)
    setupB = new Setup(PATH_ECLAIR_DATADIR_B.toString)
    setupC = new Setup(PATH_ECLAIR_DATADIR_C.toString)
    setupA.boostrap
    setupB.boostrap
    setupC.boostrap
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
    sender.expectMsgType[JValue]
    // waiting for channel to reach normal
    awaitCond(eventListener.expectMsgType[ChannelStateChanged](5 seconds).currentState == NORMAL)
    node1.system.eventStream.unsubscribe(eventListener.ref)
  }

  test("connect A->B->C") {
    connect(setupA, setupB, 10000000, 0)
    connect(setupB, setupC, 10000000, 0)
  }

  test("wait for network announcements") {
    val sender = TestProbe()
    // generating more blocks so that all funding txes are buried under at least 6 blocks
    sender.send(bitcoincli, BitcoinReq("generate", 6))
    sender.expectMsgType[JValue]
    // wait for A to know all nodes and channels
    awaitCond({
      sender.send(setupA.router, 'nodes)
      sender.expectMsgType[Iterable[NodeAnnouncement]].size == 3
    })
    awaitCond({
      sender.send(setupA.router, 'channels)
      sender.expectMsgType[Iterable[ChannelAnnouncement]].size == 2
    })
    awaitCond({
      sender.send(setupA.router, 'updates)
      sender.expectMsgType[Iterable[ChannelUpdate]].size == 4
    })
  }

  test("send an HTLC A->C") {
    val sender = TestProbe()
    // first we retrieve a payment hash from C
    sender.send(setupC.paymentHandler, 'genh)
    val paymentHash = sender.expectMsgType[BinaryData]
    // then we make the actual payment
    sender.send(setupA.paymentInitiator, CreatePayment(42000000, paymentHash, setupC.nodeParams.privateKey.publicKey))
    sender.expectMsg("sent")
  }

}
