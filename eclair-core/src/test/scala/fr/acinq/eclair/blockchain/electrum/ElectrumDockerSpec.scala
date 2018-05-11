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

package fr.acinq.eclair.blockchain.electrum


import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.spotify.docker.client.{DefaultDockerClient, DockerClient}
import com.whisk.docker.impl.spotify.SpotifyDockerFactory
import com.whisk.docker.scalatest.DockerTestKit
import com.whisk.docker.{DockerContainer, DockerFactory, DockerReadyChecker, LogLineReceiver}
import fr.acinq.bitcoin.{BinaryData, Block, DeterministicWallet, MnemonicCode, Satoshi}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinJsonRPCClient}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JValue
import org.junit.runner.RunWith
import org.scalatest.FunSuiteLike
import org.scalatest.junit.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

trait BitcoindService extends Logging {
  implicit val system: ActorSystem

  import scala.sys.process._

  val INTEGRATION_TMP_DIR = s"${System.getProperty("buildDirectory")}/integration-${UUID.randomUUID().toString}"
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = new File(System.getProperty("buildDirectory"), "bitcoin-0.16.0/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")

  var bitcoind: Process = null
  var bitcoinrpcclient: BitcoinJsonRPCClient = null
  var bitcoincli: ActorRef = null

  case class BitcoinReq(method: String, params: Seq[Any] = Nil)

  def startBitcoind(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf"), new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoinrpcclient = new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 28332)
    bitcoincli = system.actorOf(Props(new Actor {
      override def receive: Receive = {
          case BitcoinReq(method, Nil) =>
            bitcoinrpcclient.invoke(method) pipeTo sender
          case BitcoinReq(method, params) =>
            bitcoinrpcclient.invoke(method, params: _*) pipeTo sender
      }
    }))
  }

  def stopBitcoind(): Unit = {
    // gracefully stopping bitcoin will make it store its state cleanly to disk, which is good for later debugging
    val sender = TestProbe()
    sender.send(bitcoincli, BitcoinReq("stop"))
    sender.expectMsgType[JValue]
    bitcoind.exitValue()
  }
}

@RunWith(classOf[JUnitRunner])
class ElectrumDockerSpec extends TestKit(ActorSystem("test")) with BitcoindService with FunSuiteLike with DockerTestKit with Logging {
  require(System.getProperty("buildDirectory") != null, "please define system property buildDirectory")

  val electrumxContainer = DockerContainer("lukechilds/electrumx")
    .withNetworkMode("host")
    //.withPorts(50001 -> Some(50001))
    .withEnv("DAEMON_URL=http://foo:bar@localhost:28332", "COIN=BitcoinSegwit", "NET=regtest")
//    .withReadyChecker(
//      DockerReadyChecker.LogLineContains("INFO:Controller:TCP server listening on :50001").looped(15, 1 second)
//    )
    .withLogLineReceiver(LogLineReceiver(true, println))

  import ElectrumWallet._

  val entropy = BinaryData("01" * 32)
  val mnemonics = MnemonicCode.toMnemonics(entropy)
  val seed = MnemonicCode.toSeed(mnemonics, "")
  logger.info(s"mnemonic codes for our wallet: $mnemonics")
  val master = DeterministicWallet.generate(seed)
  var wallet: ActorRef = _
  var electrumClient: ActorRef = _

  override def dockerContainers: List[DockerContainer] = electrumxContainer :: super.dockerContainers

  private val client: DockerClient = DefaultDockerClient.fromEnv().build()

  override implicit val dockerFactory: DockerFactory = new SpotifyDockerFactory(client)

  override def beforeAll(): Unit = {
    logger.info("starting bitcoind")
    startBitcoind()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    logger.info("stopping bitcoind")
    stopBitcoind()
    super.afterAll()
  }

  test("generate 500 blocks") {
    val sender = TestProbe()
    logger.info(s"waiting for bitcoind to initialize...")
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      sender.receiveOne(5 second).isInstanceOf[JValue]
    }, max = 30 seconds, interval = 500 millis)
    logger.info(s"generating initial blocks...")
    sender.send(bitcoincli, BitcoinReq("generate", 500 :: Nil))
    sender.expectMsgType[JValue](30 seconds)
    DockerReadyChecker.LogLineContains("INFO:BlockProcessor:height: 501").looped(attempts = 15, delay = 1 second)
  }

  test("wait until wallet is ready") {
    electrumClient = system.actorOf(Props(new ElectrumClientPool(Set(new InetSocketAddress("localhost", 50001)))))
    wallet = system.actorOf(Props(new ElectrumWallet(seed, electrumClient, WalletParameters(Block.RegtestGenesisBlock.hash, minimumFee = Satoshi(5000)))), "wallet")
    val probe = TestProbe()
    awaitCond({
      probe.send(wallet, GetData)
      val GetDataResponse(state) = probe.expectMsgType[GetDataResponse]
      state.status.size == state.accountKeys.size + state.changeKeys.size
    }, max = 30 seconds, interval = 1 second)
    logger.info(s"wallet is ready")
  }

  test("receive funds") {
    val probe = TestProbe()
    probe.send(wallet, GetBalance)
    logger.info(s"initial balance: ${probe.expectMsgType[GetBalanceResponse]}")

    // send money to our wallet
    probe.send(wallet, GetCurrentReceiveAddress)
    val GetCurrentReceiveAddressResponse(address) = probe.expectMsgType[GetCurrentReceiveAddressResponse]

    logger.info(s"sending 1 btc to $address")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address :: 1.0 :: Nil))
    val foo = probe.expectMsgType[JValue]

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(confirmed, unconfirmed) = probe.expectMsgType[GetBalanceResponse]
      unconfirmed == Satoshi(100000000L)
    }, max = 30 seconds, interval = 1 second)

    // confirm our tx
    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    probe.expectMsgType[JValue]

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(confirmed, unconfirmed) = probe.expectMsgType[GetBalanceResponse]
      confirmed == Satoshi(100000000L)
    }, max = 30 seconds, interval = 1 second)

    probe.send(wallet, GetCurrentReceiveAddress)
    val GetCurrentReceiveAddressResponse(address1) = probe.expectMsgType[GetCurrentReceiveAddressResponse]

    logger.info(s"sending 1 btc to $address1")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address1 :: 1.0 :: Nil))
    probe.expectMsgType[JValue]
    logger.info(s"sending 0.5 btc to $address1")
    probe.send(bitcoincli, BitcoinReq("sendtoaddress", address1 :: 0.5 :: Nil))
    probe.expectMsgType[JValue]

    probe.send(bitcoincli, BitcoinReq("generate", 1 :: Nil))
    probe.expectMsgType[JValue]

    awaitCond({
      probe.send(wallet, GetBalance)
      val GetBalanceResponse(confirmed, unconfirmed) = probe.expectMsgType[GetBalanceResponse]
      confirmed == Satoshi(250000000L)
    }, max = 30 seconds, interval = 1 second)
  }

}