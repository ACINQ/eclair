package fr.acinq.eclair.blockchain.fee

import com.softwaremill.sttp._
import com.softwaremill.sttp.json4s._
import fr.acinq.bitcoin.Satoshi
import org.json4s.DefaultFormats
import org.json4s.JsonAST.{JDouble, JValue}
import org.json4s.jackson.Serialization

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class EsploraFeeProvider(uri: Uri, readTimeOut: Duration)(implicit http: SttpBackend[Future, Nothing], ec: ExecutionContext) extends FeeProvider {

  import EsploraFeeProvider._

  implicit val formats: DefaultFormats.type = DefaultFormats
  implicit val serialization: Serialization.type = Serialization

  override def getFeerates: Future[FeeratesPerKB] =
    for {
      res <- sttp.readTimeout(readTimeOut).get(uri)
        .response(asJson[JValue])
        .send()
      feeRanges = parseFeeRanges(res.unsafeBody)
    } yield extractFeerates(feeRanges)
}

object EsploraFeeProvider {

  case class BlockTarget(block: Int, satPerVByte: Long)

  def parseFeeRanges(json: JValue): Seq[BlockTarget] =
    json.foldField(Seq.empty[BlockTarget]) {
      // Esplora returns estimates in Satoshi/VByte
      case (list, (strBlockTarget, JDouble(satPerVByte))) => list :+ BlockTarget(strBlockTarget.toInt, math.round(satPerVByte))
    }

  def extractFeerate(feeRanges: Seq[BlockTarget], maxBlockDelay: Int): FeeratePerKB = {
    // first we keep only fee ranges with a max block delay below the limit
    val belowLimit = feeRanges.filter(_.block <= maxBlockDelay)
    // out of all the remaining fee ranges, we select the one with the minimum higher bound
    FeeratePerKB(FeeratePerKw(FeeratePerVByte(Satoshi(belowLimit.map(_.satPerVByte).min))))
  }

  def extractFeerates(feeRanges: Seq[BlockTarget]): FeeratesPerKB =
    FeeratesPerKB(
      mempoolMinFee = extractFeerate(feeRanges, 1008),
      block_1 = extractFeerate(feeRanges, 1),
      blocks_2 = extractFeerate(feeRanges, 2),
      blocks_6 = extractFeerate(feeRanges, 6),
      blocks_12 = extractFeerate(feeRanges, 12),
      blocks_36 = extractFeerate(feeRanges, 36),
      blocks_72 = extractFeerate(feeRanges, 72),
      blocks_144 = extractFeerate(feeRanges, 144),
      blocks_1008 = extractFeerate(feeRanges, 1008))
}