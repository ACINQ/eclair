package fr.acinq.eclair.blockchain.fee

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import fr.acinq.bitcoin.{BinaryData, Block}
import org.json4s.JsonAST.{JInt, JValue}
import org.json4s.{DefaultFormats, jackson}

import scala.concurrent.{ExecutionContext, Future}

class BitgoFeeProvider(chainHash: BinaryData)(implicit system: ActorSystem, ec: ExecutionContext) extends FeeProvider {

  import BitgoFeeProvider._

  implicit val materializer = ActorMaterializer()
  val httpClient = Http(system)
  implicit val serialization = jackson.Serialization
  implicit val formats = DefaultFormats

  val uri = chainHash match {
    case Block.LivenetGenesisBlock.hash => Uri("https://www.bitgo.com/api/v2/btc/tx/fee")
    case _ => Uri("https://test.bitgo.com/api/v2/tbtc/tx/fee")
  }

  override def getFeerates: Future[FeeratesPerByte] =
    for {
      httpRes <- httpClient.singleRequest(HttpRequest(uri = uri, method = HttpMethods.GET))
      json <- Unmarshal(httpRes).to[JValue]
      feeRanges = parseFeeRanges(json)
    } yield extractFeerates(feeRanges)
}

object BitgoFeeProvider {

  case class BlockTarget(block: Int, fee: Long)

  def parseFeeRanges(json: JValue): Seq[BlockTarget] = {
    val blockTargets = json \ "feeByBlockTarget"
    blockTargets.foldField(Seq.empty[BlockTarget]) {
      // we divide by 1024 because bitgo returns estimates in Satoshi/Kb and we use estimates in Satoshi/Byte
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
