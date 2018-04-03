/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.bitcoind

import java.io.File
import java.nio.file.Files
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{Block, MilliBtc, Satoshi, Script}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BasicBitcoinJsonRPCClient
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.{addressToPublicKeyScript, randomKey}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JValue
import org.json4s.{DefaultFormats, JString}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.sys.process.{Process, _}
import scala.util.Try

@RunWith(classOf[JUnitRunner])
class BitcoinCoreWalletSpec extends TestKit(ActorSystem("test")) with FunSuiteLike with BeforeAndAfterAll with Logging {

  val INTEGRATION_TMP_DIR = s"${System.getProperty("buildDirectory")}/bitcoinj-${UUID.randomUUID().toString}"
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = new File(System.getProperty("buildDirectory"), "bitcoin-0.16.0/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")

  var bitcoind: Process = null
  var bitcoinrpcclient: BasicBitcoinJsonRPCClient = null
  var bitcoincli: ActorRef = null

  implicit val formats = DefaultFormats

  case class BitcoinReq(method: String, params: Any*)

  override def beforeAll(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    Files.copy(classOf[BitcoinCoreWalletSpec].getResourceAsStream("/integration/bitcoin.conf"), new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoinrpcclient = new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 28332)
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
    bitcoind.exitValue()
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

  test("create/commit/rollback funding txes") {
    import collection.JavaConversions._
    val commonConfig = ConfigFactory.parseMap(Map("eclair.chain" -> "regtest", "eclair.spv" -> false, "eclair.server.public-ips.1" -> "localhost", "eclair.bitcoind.port" -> 28333, "eclair.bitcoind.rpcport" -> 28332, "eclair.bitcoind.zmq" -> "tcp://127.0.0.1:28334", "eclair.router-broadcast-interval" -> "2 second", "eclair.auto-reconnect" -> false))
    val config = ConfigFactory.load(commonConfig).getConfig("eclair")
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))
    val wallet = new BitcoinCoreWallet(bitcoinClient)

    val sender = TestProbe()

    wallet.getBalance.pipeTo(sender.ref)
    assert(sender.expectMsgType[Satoshi] > Satoshi(0))

    wallet.getFinalAddress.pipeTo(sender.ref)
    val address = sender.expectMsgType[String]
    assert(Try(addressToPublicKeyScript(address, Block.RegtestGenesisBlock.hash)).isSuccess)

    val fundingTxes = for (i <- 0 to 3) yield {
      val pubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(randomKey.publicKey, randomKey.publicKey)))
      wallet.makeFundingTx(pubkeyScript, MilliBtc(50), 10000).pipeTo(sender.ref)
      val MakeFundingTxResponse(fundingTx, _) = sender.expectMsgType[MakeFundingTxResponse]
      fundingTx
    }

    sender.send(bitcoincli, BitcoinReq("listlockunspent"))
    assert(sender.expectMsgType[JValue](10 seconds).children.size === 4)

    wallet.commit(fundingTxes(0)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    wallet.rollback(fundingTxes(1)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    wallet.commit(fundingTxes(2)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    wallet.rollback(fundingTxes(3)).pipeTo(sender.ref)
    assert(sender.expectMsgType[Boolean])

    sender.send(bitcoincli, BitcoinReq("getrawtransaction", fundingTxes(0).txid.toString()))
    assert(sender.expectMsgType[JString](10 seconds).s === fundingTxes(0).toString())

    sender.send(bitcoincli, BitcoinReq("getrawtransaction", fundingTxes(2).txid.toString()))
    assert(sender.expectMsgType[JString](10 seconds).s === fundingTxes(2).toString())

    // NB: bitcoin core doesn't clear the locks when a tx is published
    sender.send(bitcoincli, BitcoinReq("listlockunspent"))
    assert(sender.expectMsgType[JValue](10 seconds).children.size === 2)

  }

}