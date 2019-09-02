/*
 * Copyright 2019 ACINQ SAS
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
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import fr.acinq.eclair.TestUtils
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, BitcoinJsonRPCClient}
import fr.acinq.eclair.integration.IntegrationSpec
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JArray, JDecimal, JInt, JString, JValue}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.io.Source

trait BitcoindService extends Logging {
  self: TestKitBase =>

  implicit val system: ActorSystem
  implicit val sttpBackend  = OkHttpFutureBackend()

  val bitcoindPort: Int = TestUtils.availablePort

  val bitcoindRpcPort: Int = TestUtils.availablePort

  val bitcoindZmqBlockPort: Int = TestUtils.availablePort

  val bitcoindZmqTxPort: Int = TestUtils.availablePort

  import scala.sys.process._

  val INTEGRATION_TMP_DIR = new File(TestUtils.BUILD_DIRECTORY, s"integration-${UUID.randomUUID()}")
  logger.info(s"using tmp dir: $INTEGRATION_TMP_DIR")

  val PATH_BITCOIND = new File(TestUtils.BUILD_DIRECTORY, "bitcoin-0.17.1/bin/bitcoind")
  val PATH_BITCOIND_DATADIR = new File(INTEGRATION_TMP_DIR, "datadir-bitcoin")

  var bitcoind: Process = null
  var bitcoinrpcclient: BitcoinJsonRPCClient = null
  var bitcoincli: ActorRef = null

  case class BitcoinReq(method: String, params: Any*)

  def startBitcoind(): Unit = {
    Files.createDirectories(PATH_BITCOIND_DATADIR.toPath)
    if (!Files.exists(new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath)) {
      val is = classOf[IntegrationSpec].getResourceAsStream("/integration/bitcoin.conf")
      val conf = Source.fromInputStream(is).mkString
          .replace("28333", bitcoindPort.toString)
          .replace("28332", bitcoindRpcPort.toString)
          .replace("28334", bitcoindZmqBlockPort.toString)
          .replace("28335", bitcoindZmqTxPort.toString)
      Files.writeString(new File(PATH_BITCOIND_DATADIR.toString, "bitcoin.conf").toPath, conf)
    }

    bitcoind = s"$PATH_BITCOIND -datadir=$PATH_BITCOIND_DATADIR".run()
    bitcoinrpcclient = new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = bitcoindRpcPort)
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
    val sender = TestProbe()
    logger.info(s"waiting for bitcoind to initialize...")
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getnetworkinfo"))
      sender.expectMsgType[Any](5 second) match {
        case j: JValue => j \ "version" match {
          case JInt(_) => true
          case _ => false
        }
        case _ => false
      }
    }, max = 3 minutes, interval = 2 seconds)
    logger.info(s"generating initial blocks...")
    sender.send(bitcoincli, BitcoinReq("generate", 150))
    val JArray(res) = sender.expectMsgType[JValue](3 minutes)
    assert(res.size == 150)
    awaitCond({
      sender.send(bitcoincli, BitcoinReq("getbalance"))
      val JDecimal(balance) = sender.expectMsgType[JDecimal](30 seconds)
      balance > 100
    }, max = 3 minutes, interval = 2 second)
  }

}