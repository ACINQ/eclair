package fr.acinq.eclair.blockchain

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.{Satoshi, Script}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.blockchain.bitcoinj.{BitcoinjKit, BitcoinjWallet, BitcoinjWatcher}
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, BITCOIN_FUNDING_SPENT}
import fr.acinq.eclair.randomKey
import fr.acinq.eclair.transactions.Scripts
import grizzled.slf4j.Logging
import org.bitcoinj.script.{Script => BitcoinjScript}
import org.json4s.DefaultFormats
import org.json4s.JsonAST.JValue
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.sys.process.{Process, _}
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class BitcoinjSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll with Logging {

  val INTEGRATION_TMP_DIR = s"${System.getProperty("buildDirectory")}/bitcoinj-${UUID.randomUUID().toString}"
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = new File(System.getProperty("buildDirectory"), "bitcoin-0.14.0/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")

  var bitcoind: Process = null
  var bitcoinrpcclient: BitcoinJsonRPCClient = null
  var bitcoincli: ActorRef = null

  implicit val formats = DefaultFormats

  case class BitcoinReq(method: String, params: Any*)

  override def beforeAll(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    Files.copy(classOf[BitcoinjSpec].getResourceAsStream("/integration/bitcoin.conf"), new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoinrpcclient = new BitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 28332)
    bitcoincli = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case BitcoinReq(method) => bitcoinrpcclient.invoke(method) pipeTo sender
        case BitcoinReq(method, params) => bitcoinrpcclient.invoke(method, params) pipeTo sender
        case BitcoinReq(method, param1, param2) => bitcoinrpcclient.invoke(method, param1, param2) pipeTo sender
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

  ignore("bitcoinj wallet commit") {
    val datadir = new File(INTEGRATION_TMP_DIR, s"datadir-bitcoinj")
    val bitcoinjKit = new BitcoinjKit("regtest", datadir, staticPeers = new InetSocketAddress("localhost", 28333) :: Nil)
    bitcoinjKit.startAsync()
    bitcoinjKit.awaitRunning()

    val sender = TestProbe()
    val wallet = new BitcoinjWallet(Future.successful(bitcoinjKit.wallet()))

    val address = Await.result(wallet.getFinalAddress, 10 seconds)
    logger.info(s"sending funds to $address")
    sender.send(bitcoincli, BitcoinReq("sendtoaddress", address, 1.0))
    sender.expectMsgType[JValue](10 seconds)
    awaitCond(Await.result(wallet.getBalance, 10 seconds) > Satoshi(0), max = 60 seconds, interval = 1 second)

    logger.info(s"generating blocks")
    sender.send(bitcoincli, BitcoinReq("generate", 10))
    sender.expectMsgType[JValue](10 seconds)

    val fundingPubkeyScript1 = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))
    val result1 = Await.result(wallet.makeFundingTx(fundingPubkeyScript1, Satoshi(10000L), 20000), 10 seconds)
    val fundingPubkeyScript2 = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))
    val result2 = Await.result(wallet.makeFundingTx(fundingPubkeyScript2, Satoshi(10000L), 20000), 10 seconds)

    assert(Await.result(wallet.commit(result1.fundingTx), 10 seconds) == true)
    assert(Await.result(wallet.commit(result2.fundingTx), 10 seconds) == false)

  }

  /*def ticket() = {
    val wallet: Wallet = ???

    def makeTx(amount: Coin, script: BitcoinjScript): Transaction = {
      val tx = new Transaction(wallet.getParams)
      tx.addOutput(amount, script)
      val req = SendRequest.forTx(tx)
      wallet.completeTx(req)
      tx
    }

    val tx1 = makeTx(amount1, script1)
    val tx2 = makeTx(amount2, script2)

    // everything is fine until here, and as expected tx1 and tx2 spend the same input

    wallet.maybeCommitTx(tx1) // returns true as expected
    wallet.maybeCommitTx(tx2) // returns true! how come?
  }*/

  ignore("manual publish/watch") {
    val datadir = new File(INTEGRATION_TMP_DIR, s"datadir-bitcoinj")
    val bitcoinjKit = new BitcoinjKit("regtest", datadir, staticPeers = new InetSocketAddress("localhost", 28333) :: Nil)
    bitcoinjKit.startAsync()
    bitcoinjKit.awaitRunning()

    val sender = TestProbe()
    val watcher = system.actorOf(Props(new BitcoinjWatcher(bitcoinjKit)), name = "bitcoinj-watcher")
    val wallet = new BitcoinjWallet(Future.successful(bitcoinjKit.wallet()))

    val address = Await.result(wallet.getFinalAddress, 10 seconds)
    logger.info(s"sending funds to $address")
    sender.send(bitcoincli, BitcoinReq("sendtoaddress", address, 1.0))
    sender.expectMsgType[JValue](10 seconds)
    awaitCond(Await.result(wallet.getBalance, 10 seconds) > Satoshi(0), max = 30 seconds, interval = 1 second)

    logger.info(s"generating blocks")
    sender.send(bitcoincli, BitcoinReq("generate", 10))
    sender.expectMsgType[JValue](10 seconds)

    val listener = TestProbe()
    val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))
    val result = Await.result(wallet.makeFundingTx(fundingPubkeyScript, Satoshi(10000L), 20000), 10 seconds)
    assert(Await.result(wallet.commit(result.fundingTx), 10 seconds))
    watcher ! WatchSpent(listener.ref, result.fundingTx, result.fundingTxOutputIndex, BITCOIN_FUNDING_SPENT)
    watcher ! WatchConfirmed(listener.ref, result.fundingTx, 3, BITCOIN_FUNDING_DEPTHOK)
    watcher ! PublishAsap(result.fundingTx)

    logger.info(s"waiting for confirmation of ${result.fundingTx.txid}")
    val event = listener.expectMsgType[WatchEventConfirmed](1000 seconds)
    assert(event.event === BITCOIN_FUNDING_DEPTHOK)
  }

  ignore("multiple publish/watch") {
    val datadir = new File(INTEGRATION_TMP_DIR, s"datadir-bitcoinj")
    val bitcoinjKit = new BitcoinjKit("regtest", datadir, staticPeers = new InetSocketAddress("localhost", 28333) :: Nil)
    bitcoinjKit.startAsync()
    bitcoinjKit.awaitRunning()

    val sender = TestProbe()
    val watcher = system.actorOf(Props(new BitcoinjWatcher(bitcoinjKit)), name = "bitcoinj-watcher")
    val wallet = new BitcoinjWallet(Future.successful(bitcoinjKit.wallet()))

    val address = Await.result(wallet.getFinalAddress, 10 seconds)
    logger.info(s"sending funds to $address")
    sender.send(bitcoincli, BitcoinReq("sendtoaddress", address, 1.0))
    sender.expectMsgType[JValue](10 seconds)
    awaitCond(Await.result(wallet.getBalance, 10 seconds) > Satoshi(0), max = 30 seconds, interval = 1 second)

    def send() = {
      val count = Random.nextInt(20)
      val listeners = (0 to count).map {
        case i =>
          val listener = TestProbe()
          val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))
          val result = Await.result(wallet.makeFundingTx(fundingPubkeyScript, Satoshi(10000L), 20000), 10 seconds)
          assert(Await.result(wallet.commit(result.fundingTx), 10 seconds))
          watcher ! WatchSpent(listener.ref, result.fundingTx, result.fundingTxOutputIndex, BITCOIN_FUNDING_SPENT)
          watcher ! WatchConfirmed(listener.ref, result.fundingTx, 3, BITCOIN_FUNDING_DEPTHOK)
          watcher ! PublishAsap(result.fundingTx)
          (result.fundingTx.txid, listener)
      }
      system.scheduler.scheduleOnce(2 seconds, new Runnable {
        override def run() = {
          logger.info(s"generating one block")
          sender.send(bitcoincli, BitcoinReq("generate", 3))
          sender.expectMsgType[JValue](10 seconds)
        }
      })
      for ((txid, listener) <- listeners) {
        logger.info(s"waiting for confirmation of $txid")
        val event = listener.expectMsgType[WatchEventConfirmed](1000 seconds)
        assert(event.event === BITCOIN_FUNDING_DEPTHOK)
      }
    }

    for (i <- 0 to 10) send()

  }
}