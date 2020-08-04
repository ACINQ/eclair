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

package fr.acinq.eclair.blockchain.fee

import akka.actor.Status.Failure
import akka.pattern.pipe
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin._
import fr.acinq.eclair.TestKitBaseClass
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.rpc.BasicBitcoinJsonRPCClient
import grizzled.slf4j.Logging
import org.json4s.DefaultFormats
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Random

class BitcoinCoreFeeProviderSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll with Logging {

  val commonConfig = ConfigFactory.parseMap(Map(
    "eclair.chain" -> "regtest",
    "eclair.spv" -> false,
    "eclair.server.public-ips.1" -> "localhost",
    "eclair.bitcoind.port" -> bitcoindPort,
    "eclair.bitcoind.rpcport" -> bitcoindRpcPort,
    "eclair.router-broadcast-interval" -> "2 second",
    "eclair.auto-reconnect" -> false).asJava)
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
    import scala.concurrent.ExecutionContext.Implicits.global
    val bitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport"))

    // the regtest client doesn't have enough data to estimate fees yet, so it's suppose to fail
    val regtestProvider = new BitcoinCoreFeeProvider(bitcoinClient, FeeratesPerKB(FeeratePerKB(1 sat), FeeratePerKB(2 sat), FeeratePerKB(3 sat), FeeratePerKB(4 sat), FeeratePerKB(5 sat), FeeratePerKB(6 sat), FeeratePerKB(7 sat), FeeratePerKB(8 sat)))
    val sender = TestProbe()
    regtestProvider.getFeerates.pipeTo(sender.ref)
    assert(sender.expectMsgType[Failure].cause.asInstanceOf[RuntimeException].getMessage.contains("Insufficient data or no feerate found"))

    val fees = Map(
      1 -> FeeratePerKB(1500 sat),
      2 -> FeeratePerKB(1400 sat),
      6 -> FeeratePerKB(1300 sat),
      12 -> FeeratePerKB(1200 sat),
      36 -> FeeratePerKB(1100 sat),
      72 -> FeeratePerKB(1000 sat),
      144 -> FeeratePerKB(900 sat),
      1008 -> FeeratePerKB(400 sat)
    )

    val ref = FeeratesPerKB(
      block_1 = fees(1),
      blocks_2 = fees(2),
      blocks_6 = fees(6),
      blocks_12 = fees(12),
      blocks_36 = fees(36),
      blocks_72 = fees(72),
      blocks_144 = fees(144),
      blocks_1008 = fees(1008))

    val mockBitcoinClient = new BasicBitcoinJsonRPCClient(
      user = config.getString("bitcoind.rpcuser"),
      password = config.getString("bitcoind.rpcpassword"),
      host = config.getString("bitcoind.host"),
      port = config.getInt("bitcoind.rpcport")) {
      override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] = method match {
        case "estimatesmartfee" =>
          val blocks = params(0).asInstanceOf[Int]
          val feerate = satoshi2btc(fees(blocks).feerate).toBigDecimal
          Future(JObject(List("feerate" -> JDecimal(feerate), "blocks" -> JInt(blocks))))(ec)
        case _ => Future.failed(new RuntimeException(s"Test BasicBitcoinJsonRPCClient: method $method is not supported"))
      }
    }

    val mockProvider = new BitcoinCoreFeeProvider(mockBitcoinClient, FeeratesPerKB(FeeratePerKB(1 sat), FeeratePerKB(2 sat), FeeratePerKB(3 sat), FeeratePerKB(4 sat), FeeratePerKB(5 sat), FeeratePerKB(6 sat), FeeratePerKB(7 sat), FeeratePerKB(8 sat)))
    mockProvider.getFeerates.pipeTo(sender.ref)
    assert(sender.expectMsgType[FeeratesPerKB] == ref)
  }

}
