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

import akka.pattern.pipe
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat._
import fr.acinq.eclair.TestKitBaseClass
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.rpc.BasicBitcoinJsonRPCClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCAuthMethod.UserPassword
import grizzled.slf4j.Logging
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

class BitcoinCoreFeeProviderSpec extends TestKitBaseClass with BitcoindService with AnyFunSuiteLike with BeforeAndAfterAll with Logging {

  override def useEclairSigner = false

  override def beforeAll(): Unit = {
    startBitcoind()
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
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
    val sender = TestProbe()
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
      minimum = FeeratePerKB(300 sat),
      fastest = fees(1),
      fast = fees(2),
      medium = fees(12),
      slow = fees(1008)
    )

    val mockBitcoinClient = createMockBitcoinClient(fees, ref.minimum)

    val mockProvider = BitcoinCoreFeeProvider(mockBitcoinClient, FeeratesPerKB(minimum = FeeratePerKB(1 sat), slow = FeeratePerKB(2 sat), medium = FeeratePerKB(3 sat), fast = FeeratePerKB(4 sat), fastest = FeeratePerKB(5 sat)))
    mockProvider.getFeerates.pipeTo(sender.ref)
    assert(sender.expectMsgType[FeeratesPerKB] == ref)
  }

  test("get mempool minimum fee") {
    val regtestProvider = BitcoinCoreFeeProvider(bitcoinrpcclient, FeeratesPerKB(minimum = FeeratePerKB(1 sat), slow = FeeratePerKB(2 sat), medium = FeeratePerKB(3 sat), fast = FeeratePerKB(4 sat), fastest = FeeratePerKB(5 sat)))
    val sender = TestProbe()
    regtestProvider.mempoolMinFee().pipeTo(sender.ref)
    val mempoolMinFee = sender.expectMsgType[FeeratePerKB]
    // The regtest provider doesn't have any transaction in its mempool, so it defaults to the min_relay_fee.
    assert(mempoolMinFee.feerate.toLong == FeeratePerKw.MinimumRelayFeeRate)
  }

  private def createMockBitcoinClient(fees: Map[Int, FeeratePerKB], mempoolMinFee: FeeratePerKB): BasicBitcoinJsonRPCClient = {
    new BasicBitcoinJsonRPCClient(Block.RegtestGenesisBlock.hash, rpcAuthMethod = UserPassword("", ""), host = "localhost", port = 0) {
      override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] = method match {
        case "estimatesmartfee" =>
          val blocks = params(0).asInstanceOf[Int]
          val feerate = satoshi2btc(fees(blocks).feerate).toBigDecimal
          Future(JObject(List("feerate" -> JDecimal(feerate), "blocks" -> JInt(blocks))))(ec)
        case "getmempoolinfo" =>
          val mempoolInfo = List(
            "minrelaytxfee" -> JDecimal(satoshi2btc(100 sat).toBigDecimal),
            "mempoolminfee" -> JDecimal(satoshi2btc(mempoolMinFee.feerate).toBigDecimal)
          )
          Future(JObject(mempoolInfo))(ec)
        case _ => Future.failed(new RuntimeException(s"Test BasicBitcoinJsonRPCClient: method $method is not supported"))
      }
    }
  }

}
