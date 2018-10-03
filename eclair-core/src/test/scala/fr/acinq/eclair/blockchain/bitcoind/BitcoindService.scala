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
import java.nio.file.{Files, StandardCopyOption}
import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.{TestKitBase, TestProbe}
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.integration.IntegrationSpec
import grizzled.slf4j.Logging
import org.json4s.DefaultFormats
import org.json4s.JsonAST.{JObject, JValue}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

trait BitcoindService extends Logging {
  self: TestKitBase =>

  implicit val system: ActorSystem

  import scala.sys.process._

  val INTEGRATION_TMP_DIR = s"${System.getProperty("buildDirectory")}/integration-${UUID.randomUUID().toString}"
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = new File(System.getProperty("buildDirectory"), s"bitcoin-${System.getProperty("bitcoin.core.main_version")}/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")

  var bitcoind: Process = _
  var bitcoinrpcclient: BitcoinJsonRPCClient = _
  var bitcoincli: ActorRef = _

  var btcWallet: BitcoinCoreWallet = _
  var version: Int = _

  case class BitcoinReq(method: String, params: Any*)

  def startBitcoind(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    if (!Files.exists(new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)) {
      Files.copy(classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf"), new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath, StandardCopyOption.REPLACE_EXISTING)
    }

    //Bitcoin 0.17 prints to console by default when not using 'daemon'
    bitcoind = s"$PATH_BITCOIND -printtoconsole=0 -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoinrpcclient = new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 28332)
    bitcoincli = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case BitcoinReq(method) => bitcoinrpcclient.invoke(method) pipeTo sender
        case BitcoinReq(method, params) => bitcoinrpcclient.invoke(method, params) pipeTo sender
        case BitcoinReq(method, param1, param2) => bitcoinrpcclient.invoke(method, param1, param2) pipeTo sender
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

  def waitForBitcoindReady(): Unit = {
    implicit val formats = DefaultFormats
    val sender = TestProbe()
    logger.info(s"waiting for bitcoind to initialize...")
    var networkInfo: AnyRef = null
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      networkInfo = sender.receiveOne(5 second)
      networkInfo.isInstanceOf[JValue]
    }, max = 30 seconds, interval = 500 millis)

    version = (networkInfo.asInstanceOf[JObject] \\ "version").extract[Int]
    btcWallet = new BitcoinCoreWallet(bitcoinrpcclient, version)

    logger.info(s"generating initial blocks...")
    sender.send(bitcoincli, BitcoinReq("generate", 500))
    sender.expectMsgType[JValue](30 seconds)
  }

}