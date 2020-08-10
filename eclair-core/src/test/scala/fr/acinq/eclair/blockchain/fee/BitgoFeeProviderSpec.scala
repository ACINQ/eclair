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
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.LongToBtcAmount
import org.json4s.DefaultFormats
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.Await

/**
 * Created by PM on 27/01/2017.
 */

class BitgoFeeProviderSpec extends AnyFunSuite {

  import BitgoFeeProvider._
  import org.json4s.jackson.JsonMethods.parse

  implicit val formats = DefaultFormats

  val sample_response =
    """
      {"feePerKb":136797,"cpfpFeePerKb":136797,"numBlocks":2,"confidence":80,"multiplier":1,"feeByBlockTarget":{"1":149453,"2":136797,"5":122390,"6":105566,"8":100149,"9":96254,"10":122151,"13":116855,"15":110860,"17":87402,"27":82635,"33":71098,"42":105782,"49":68182,"73":59207,"97":17336,"121":16577,"193":13545,"313":12268,"529":11122,"553":9139,"577":5395,"793":5070}}
    """

  test("parse test") {
    val json = parse(sample_response)
    val feeRanges = parseFeeRanges(json)
    assert(feeRanges.size === 23)
  }

  test("extract fee for a particular block delay") {
    val json = parse(sample_response)
    val feeRanges = parseFeeRanges(json)
    val fee = extractFeerate(feeRanges, 6)
    assert(fee === FeeratePerKB(105566 sat))
  }

  test("extract all fees") {
    val json = parse(sample_response)
    val feeRanges = parseFeeRanges(json)
    val feerates = extractFeerates(feeRanges)
    val ref = FeeratesPerKB(
      block_1 = FeeratePerKB(149453 sat),
      blocks_2 = FeeratePerKB(136797 sat),
      blocks_6 = FeeratePerKB(105566 sat),
      blocks_12 = FeeratePerKB(96254 sat),
      blocks_36 = FeeratePerKB(71098 sat),
      blocks_72 = FeeratePerKB(68182 sat),
      blocks_144 = FeeratePerKB(16577 sat),
      blocks_1008 = FeeratePerKB(5070 sat))
    assert(feerates === ref)
  }

  test("make sure API hasn't changed") {
    import scala.concurrent.duration._
    implicit val system = ActorSystem("test")
    implicit val ec = system.dispatcher
    implicit val sttp = OkHttpFutureBackend()
    implicit val timeout = Timeout(30 seconds)
    val bitgo = new BitgoFeeProvider(Block.LivenetGenesisBlock.hash, 5 seconds)
    assert(Await.result(bitgo.getFeerates, timeout.duration).block_1.toLong > 0)
  }

  test("check that read timeout is enforced") {
    import scala.concurrent.duration._
    implicit val system = ActorSystem("test")
    implicit val ec = system.dispatcher
    implicit val sttp = OkHttpFutureBackend()
    implicit val timeout = Timeout(30 second)
    val bitgo = new BitgoFeeProvider(Block.LivenetGenesisBlock.hash, 1 millisecond)
    val e = intercept[Exception] {
      Await.result(bitgo.getFeerates, timeout.duration)
    }
    assert(e.getMessage.contains("timed out") || e.getMessage.contains("timeout"))
  }

}