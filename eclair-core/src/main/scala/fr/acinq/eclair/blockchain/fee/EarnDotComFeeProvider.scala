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
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import org.json4s.JsonAST.{JArray, JInt, JValue}
import org.json4s.{DefaultFormats, jackson}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 16/11/2017.
  */
class EarnDotComFeeProvider(implicit system: ActorSystem, ec: ExecutionContext) extends FeeProvider {

  import EarnDotComFeeProvider._

  implicit val materializer = ActorMaterializer()
  val httpClient = Http(system)
  implicit val serialization = jackson.Serialization
  implicit val formats = DefaultFormats

  override def getFeerates: Future[FeeratesPerKB] =
    for {
      httpRes <- httpClient.singleRequest(HttpRequest(uri = Uri("https://bitcoinfees.earn.com/api/v1/fees/list"), method = HttpMethods.GET))
      json <- Unmarshal(httpRes).to[JValue]
      feeRanges = parseFeeRanges(json)
    } yield extractFeerates(feeRanges)
}

object EarnDotComFeeProvider {

  case class FeeRange(minFee: Long, maxFee: Long, memCount: Long, minDelay: Long, maxDelay: Long)

  def parseFeeRanges(json: JValue): Seq[FeeRange] = {
    val JArray(items) = json \ "fees"
    items.map(item => {
      val JInt(minFee) = item \ "minFee"
      val JInt(maxFee) = item \ "maxFee"
      val JInt(memCount) = item \ "memCount"
      val JInt(minDelay) = item \ "minDelay"
      val JInt(maxDelay) = item \ "maxDelay"
      // earn.com returns fees in Satoshi/byte and we want Satoshi/KiloByte
      FeeRange(minFee = 1000 * minFee.toLong, maxFee = 1000 * maxFee.toLong, memCount = memCount.toLong, minDelay = minDelay.toLong, maxDelay = maxDelay.toLong)
    })
  }

  def extractFeerate(feeRanges: Seq[FeeRange], maxBlockDelay: Int): Long = {
    // first we keep only fee ranges with a max block delay below the limit
    val belowLimit = feeRanges.filter(_.maxDelay <= maxBlockDelay)
    // out of all the remaining fee ranges, we select the one with the minimum higher bound and make sure it is > 0
    Math.max(belowLimit.minBy(_.maxFee).maxFee, 1)
  }

  def extractFeerates(feeRanges: Seq[FeeRange]): FeeratesPerKB =
    FeeratesPerKB(
      block_1 = extractFeerate(feeRanges, 1),
      blocks_2 = extractFeerate(feeRanges, 2),
      blocks_6 = extractFeerate(feeRanges, 6),
      blocks_12 = extractFeerate(feeRanges, 12),
      blocks_36 = extractFeerate(feeRanges, 36),
      blocks_72 = extractFeerate(feeRanges, 72))

}
