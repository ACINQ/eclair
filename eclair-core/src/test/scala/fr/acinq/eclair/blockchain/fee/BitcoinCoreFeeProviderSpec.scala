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

package fr.acinq.eclair.blockchain.fee

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.pattern.pipe
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.rpc.BasicBitcoinJsonRPCClient
import grizzled.slf4j.Logging
import org.json4s.DefaultFormats
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random


class BitcoinCoreFeeProviderSpec extends TestKit(ActorSystem("test")) with BitcoindService with FunSuiteLike with BeforeAndAfterAll with Logging {

  val commonConfig = ConfigFactory.parseMap(Map("eclair.chain" -> "regtest", "eclair.spv" -> false, "eclair.server.public-ips.1" -> "localhost", "eclair.bitcoind.port" -> 28333, "eclair.bitcoind.rpcport" -> 28332, "eclair.bitcoind.zmq" -> "tcp://127.0.0.1:28334", "eclair.router-broadcast-interval" -> "2 second", "eclair.auto-reconnect" -> false))
  val config = ConfigFactory.load(commonConfig).getConfig("eclair")

  val walletPassword = Random.alphanumeric.take(8).mkString

  implicit val formats = DefaultFormats.withBigDecimal

  override def beforeAll(): Unit = {
    startBitcoind()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  test("wait bitcoind ready") {
    waitForBitcoindReady()
  }

  test("parse error") {
    val raw =
      """
        |{
        |  "errors": [
        |    "Insufficient data or no feerate found"
        |  ],
        |  "blocks": 0
        |}
      """.stripMargin
    val json = JsonMethods.parse(raw)
    intercept[RuntimeException] {
      BitcoinCoreFeeProvider.parseFeeEstimate(json)
    }
  }

  test("get fee rates") {
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))

    // the regtest client doesn't have enough data to estimate fees yet, so it's suppose to fail
    val regtestProvider = new BitcoinCoreFeeProvider(bitcoinClient, FeeratesPerKB(1, 2, 3, 4, 5, 6))
    val sender = TestProbe()
    regtestProvider.getFeerates.pipeTo(sender.ref)
    assert(sender.expectMsgType[Failure].cause.asInstanceOf[RuntimeException].getMessage.contains("Insufficient data or no feerate found"))

    val fees = Map(
      1 -> 1500,
      2 -> 1400,
      6 -> 1300,
      12 -> 1200,
      36 -> 1100,
      72 -> 1000
    )

    val ref = FeeratesPerKB(
      block_1 = fees(1),
      blocks_2 = fees(2),
      blocks_6 = fees(6),
      blocks_12 = fees(12),
      blocks_36 = fees(36),
      blocks_72 = fees(72))

    val mockBitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport")) {
      override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] = method match {
        case "estimatesmartfee" =>
          val blocks = params(0).asInstanceOf[Int]
          val feerate = satoshi2btc(Satoshi(fees(blocks))).amount
          Future(JObject(List("feerate" -> JDecimal(feerate), "blocks" -> JInt(blocks))))
        case _ => Future.failed(new RuntimeException(s"Test BasicBitcoinJsonRPCClient: method $method is not supported"))
      }
    }

    val mockProvider = new BitcoinCoreFeeProvider(mockBitcoinClient, FeeratesPerKB(1, 2, 3, 4, 5, 6))
    mockProvider.getFeerates.pipeTo(sender.ref)
    assert(sender.expectMsgType[FeeratesPerKB] == ref)
  }

}
