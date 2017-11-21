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

  override def getFeerates: Future[FeeratesPerByte] =
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
      FeeRange(minFee = minFee.toLong, maxFee = maxFee.toLong, memCount = memCount.toLong, minDelay = minDelay.toLong, maxDelay = maxDelay.toLong)
    })
  }

  def extractFeerate(feeRanges: Seq[FeeRange], maxBlockDelay: Int): Long = {
    // first we keep only fee ranges with a max block delay below the limit
    val belowLimit = feeRanges.filter(_.maxDelay <= maxBlockDelay)
    // out of all the remaining fee ranges, we select the one with the minimum higher bound
    belowLimit.minBy(_.maxFee).maxFee
  }

  def extractFeerates(feeRanges: Seq[FeeRange]): FeeratesPerByte =
    FeeratesPerByte(
      block_1 = extractFeerate(feeRanges, 1),
      blocks_2 = extractFeerate(feeRanges, 2),
      blocks_6 = extractFeerate(feeRanges, 6),
      blocks_12 = extractFeerate(feeRanges, 12),
      blocks_36 = extractFeerate(feeRanges, 36),
      blocks_72 = extractFeerate(feeRanges, 72))

}
