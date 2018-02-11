package fr.acinq.eclair.blockchain.electrum

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinJsonRPCClient}
import grizzled.slf4j.Logging
import org.json4s.DefaultFormats
import org.json4s.JsonAST.{JInt, JValue}
import org.json4s.jackson.JsonMethods
import org.junit.Ignore
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.sys.process._
import scala.util.{Success, Try}

@Ignore
class IntegrationSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll with Logging {
  implicit val formats = DefaultFormats

  require(System.getProperty("buildDirectory") != null, "please define system property buildDirectory")
  require(System.getProperty("electrumxPath") != null, "please define system property electrumxPath")

  val INTEGRATION_TMP_DIR = s"${System.getProperty("buildDirectory")}/integration-${UUID.randomUUID().toString}"
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = new File(System.getProperty("buildDirectory"), "bitcoin-0.15.0/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")
  val PATH_ELECTRUMX_DBDIR = new File(INTEGRATION_TMP_DIR, "electrumx-db")
  val PATH_ELECTRUMX = new File(System.getProperty("electrumxPath"))

  var bitcoind: Process = _
  var bitcoinrpcclient: BitcoinJsonRPCClient = _
  var bitcoincli: ActorRef = _

  var elecxtrumx: Process = _
  var electrumClient: ActorRef = _

  case class BitcoinReq(method: String, params: Seq[Any] = Nil)

  override protected def beforeAll(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf"), new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)

    bitcoinrpcclient = new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 28332)
    bitcoincli = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case BitcoinReq(method, Nil) =>
          bitcoinrpcclient.invoke(method) pipeTo sender
        case BitcoinReq(method, params) =>
          bitcoinrpcclient.invoke(method, params: _*) pipeTo sender
      }
    }))
    Files.createDirectories(PATH_ELECTRUMX_DBDIR.toPath)
    startBitcoind
    logger.info(s"generating initial blocks...")
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("generate", 500 :: Nil))
    sender.expectMsgType[JValue](10 seconds)

    startElectrum
    electrumClient = system.actorOf(Props(new ElectrumClient(Seq(new InetSocketAddress("localhost", 51001)))))
    sender.send(electrumClient, ElectrumClient.AddStatusListener(sender.ref))
    sender.expectMsg(3 seconds, ElectrumClient.ElectrumReady)
  }

  override protected def afterAll(): Unit = {
    logger.info(s"stopping bitcoind")
    stopBitcoind
    bitcoind.destroy()
    logger.info(s"stopping electrumx")
    elecxtrumx.destroy()
  }

  def startBitcoind: Unit = {
    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    val sender = TestProbe()
    logger.info(s"waiting for bitcoind to initialize...")
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      sender.receiveOne(5 second).isInstanceOf[JValue]
    }, max = 30 seconds, interval = 500 millis)
    logger.info(s"bitcoind is ready")
  }

  def stopBitcoind: Unit = {
    // gracefully stopping bitcoin will make it store its state cleanly to disk, which is good for later debugging
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("stop"))
    bitcoind.exitValue()
  }

  def restartBitcoind: Unit = {
    stopBitcoind
    startBitcoind
  }

  def startElectrum: Unit = {
    elecxtrumx = Process(s"$PATH_ELECTRUMX/electrumx_server.py",
      None,
      "DB_DIRECTORY" -> PATH_ELECTRUMX_DBDIR.getAbsolutePath,
      "DAEMON_URL" -> "foo:bar@localhost:28332",
      "COIN" -> "BitcoinSegwit",
      "NET" -> "regtest",
      "TCP_PORT" -> "51001").run()

    logger.info(s"waiting for electrumx to initialize...")
    awaitCond({
      val result = s"$PATH_ELECTRUMX/electrumx_rpc.py getinfo".!!
      Try(JsonMethods.parse(result) \ "daemon_height") match {
        case Success(JInt(value)) if value.intValue() == 500 => true
        case _ => false
      }
    }, max = 30 seconds, interval = 500 millis)
  }
}
