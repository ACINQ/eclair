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

import akka.actor.ActorSystem
import akka.util.Timeout
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import fr.acinq.bitcoin.{Block, SatoshiLong}
import fr.acinq.eclair.TestTags
import org.json4s.DefaultFormats
import com.softwaremill.sttp._
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Await

/**
 * Created by PM on 27/01/2017.
 */

class EsploraFeeProviderSpec extends AnyFunSuite {

  import EsploraFeeProvider._
  import org.json4s.jackson.JsonMethods.parse

  implicit val formats = DefaultFormats

  val sample_response = """{"5":92.17500000000001,"2":92.17500000000001,"17":68.34,"4":92.17500000000001,"20":68.34,"21":68.34,"144":10.063,"11":87.284,"6":92.17500000000001,"16":71.31,"19":68.34,"15":87.284,"1":92.17500000000001,"8":91.666,"23":64.856,"10":91.666,"14":87.284,"22":68.34,"13":87.284,"18":68.34,"7":91.666,"25":61.724000000000004,"504":6.015,"1008":4.979,"24":64.856,"9":91.666,"3":92.17500000000001,"12":87.284}"""

  test("parse test") {
    val json = parse(sample_response)
    val feeRanges = parseFeeRanges(json)
    assert(feeRanges.size === 28)
  }

  test("extract fee for a particular block delay") {
    val json = parse(sample_response)
    val feeRanges = parseFeeRanges(json)
    val fee = extractFeerate(feeRanges, 6)
    assert(fee === FeeratePerKB(368000 sat))
  }

  test("extract all fees") {
    val json = parse(sample_response)
    val feeRanges = parseFeeRanges(json)
    val feerates = extractFeerates(feeRanges)
    val ref = FeeratesPerKB(
      mempoolMinFee = FeeratePerKB(20000 sat),
      block_1 = FeeratePerKB(368000 sat),
      blocks_2 = FeeratePerKB(368000 sat),
      blocks_6 = FeeratePerKB(368000 sat),
      blocks_12 = FeeratePerKB(348000 sat),
      blocks_36 = FeeratePerKB(248000 sat),
      blocks_72 = FeeratePerKB(248000 sat),
      blocks_144 = FeeratePerKB(40000 sat),
      blocks_1008 = FeeratePerKB(20000 sat))
    assert(feerates === ref)
  }

  test("make sure API hasn't changed", TestTags.ExternalApi) {
    import scala.concurrent.duration._
    implicit val system = ActorSystem("test")
    implicit val ec = system.dispatcher
    implicit val sttp = OkHttpFutureBackend()
    implicit val timeout = Timeout(30 seconds)
    val esplora = new EsploraFeeProvider(uri"https://mempool.space/api/fee-estimates", 5 seconds)
    assert(Await.result(esplora.getFeerates, timeout.duration).block_1.toLong > 0)
  }

  test("check that read timeout is enforced", TestTags.ExternalApi) {
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration._
    implicit val sttpBackend = OkHttpFutureBackend()
    implicit val timeout = Timeout(5 second)
    val esplora = new EsploraFeeProvider(uri"https://mempool.space/api/fee-estimates", 1 millisecond)
    intercept[Exception] {
      Await.result(esplora.getFeerates, timeout.duration)
    }
  }

}