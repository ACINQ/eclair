package fr.acinq.eclair.blockchain

import java.io.File
import java.nio.file.Files
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.eclair.Kit
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.integration.IntegrationSpec
import grizzled.slf4j.Logging
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JValue
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FunSuite, FunSuiteLike}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import ExecutionContext.Implicits.global
import scala.sys.process._

@RunWith(classOf[JUnitRunner])
class ExtendedBitcoinClientSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll with Logging {

  val INTEGRATION_TMP_DIR = s"${System.getProperty("buildDirectory")}/integration-${UUID.randomUUID().toString}"
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = new File(System.getProperty("buildDirectory"), "bitcoin-0.14.0/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")

  var bitcoind: Process = null
  var bitcoinrpcclient: BitcoinJsonRPCClient = null
  var bitcoincli: ActorRef = null
  var client: ExtendedBitcoinClient = _

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
    client = new ExtendedBitcoinClient(bitcoinrpcclient)
  }

  override def afterAll(): Unit = {
    // gracefully stopping bitcoin will make it store its state cleanly to disk, which is good for later debugging
    logger.info(s"stopping bitcoind")
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("stop"))
    sender.expectMsgType[JValue]
    bitcoind.exitValue()
  }

  test("list unspent addresss") {
    val sender = TestProbe()
    logger.info(s"waiting for bitcoind to initialize...")
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      sender.receiveOne(5 second).isInstanceOf[JValue]
    }, max = 30 seconds, interval = 500 millis)
    logger.info(s"generating initial blocks...")
    sender.send(bitcoincli, BitcoinReq("generate", 500))
    sender.expectMsgType[JValue](20 seconds)

    val future = for {
      count <- client.getBlockCount
      _ = assert(count == 500)
      unspentAddresses <- client.listUnspentAddresses
      // coinbase txs need 100 confirmations to be spendable
      _ = assert(unspentAddresses.length == 500 - 100)
      // generate and import a new private key
      priv = PrivateKey("01" * 32)
      wif = Base58Check.encode(Base58.Prefix.SecretKeyTestnet, priv.toBin)
      _ = sender.send(bitcoincli, BitcoinReq("importprivkey", wif))
      _ = sender.expectMsgType[JValue](10 seconds)
      // send money to our private key
      address = Base58Check.encode(Base58.Prefix.PubkeyAddressTestnet, priv.publicKey.hash160)
      _ = client.sendFromAccount("", address, 1.0)
      _ = sender.send(bitcoincli, BitcoinReq("generate", 1))
      _ = sender.expectMsgType[JValue](10 seconds)
      // and check that we find a utxo four our private key
      unspentAddresses1 <- client.listUnspentAddresses
      _ = assert(unspentAddresses1 contains address)
    } yield ()

    Await.result(future, 10 seconds)
  }
}