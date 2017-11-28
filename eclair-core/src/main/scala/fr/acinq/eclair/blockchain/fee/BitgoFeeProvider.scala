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

class BitgoFeeProvider(implicit system: ActorSystem, ec: ExecutionContext) extends FeeProvider {

  import BitgoFeeProvider._

  implicit val materializer = ActorMaterializer()
  val httpClient = Http(system)
  implicit val serialization = jackson.Serialization
  implicit val formats = DefaultFormats

  override def getFeerates: Future[FeeratesPerByte] =
    for {
      httpRes <- httpClient.singleRequest(HttpRequest(uri = Uri("https://www.bitgo.com/api/v1/tx/fee"), method = HttpMethods.GET))
      json <- Unmarshal(httpRes).to[JValue]
      feeRanges = parseFeeRanges(json)
    } yield extractFeerates(feeRanges)
}

object BitgoFeeProvider {

  case class BlockTarget(block: Int, fee: Long)

  def parseFeeRanges(json: JValue): Seq[BlockTarget] = {
    val blockTargets = json \ "feeByBlockTarget"
    blockTargets.foldField(Seq.empty[BlockTarget]) {
      case (list, (strBlockTarget, JInt(feePerKb))) => list :+ BlockTarget(strBlockTarget.toInt, feePerKb.longValue() / 1024)
    }
  }

  def extractFeerate(feeRanges: Seq[BlockTarget], maxBlockDelay: Int): Long = {
    // first we keep only fee ranges with a max block delay below the limit
    val belowLimit = feeRanges.filter(_.block <= maxBlockDelay)
    // out of all the remaining fee ranges, we select the one with the minimum higher bound
    belowLimit.map(_.fee).min
  }

  def extractFeerates(feeRanges: Seq[BlockTarget]): FeeratesPerByte =
    FeeratesPerByte(
      block_1 = extractFeerate(feeRanges, 1),
      blocks_2 = extractFeerate(feeRanges, 2),
      blocks_6 = extractFeerate(feeRanges, 6),
      blocks_12 = extractFeerate(feeRanges, 12),
      blocks_36 = extractFeerate(feeRanges, 36),
      blocks_72 = extractFeerate(feeRanges, 72))

}
