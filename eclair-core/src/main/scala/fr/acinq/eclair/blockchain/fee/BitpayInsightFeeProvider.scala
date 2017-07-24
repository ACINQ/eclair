package fr.acinq.eclair.blockchain.fee

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import fr.acinq.bitcoin.{Btc, Satoshi}
import org.json4s.JsonAST.{JDouble, JValue}
import org.json4s.{DefaultFormats, jackson}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 09/07/2017.
  */
class BitpayInsightFeeProvider(implicit system: ActorSystem, ec: ExecutionContext) extends FeeProvider {

  implicit val materializer = ActorMaterializer()
  val httpClient = Http(system)
  implicit val serialization = jackson.Serialization
  implicit val formats = DefaultFormats

  override def getFeeratePerKB: Future[Long] =
    for {
      httpRes <- httpClient.singleRequest(HttpRequest(uri = Uri("https://test-insight.bitpay.com/api/utils/estimatefee?nbBlocks=3"), method = HttpMethods.GET))
      json <- Unmarshal(httpRes).to[JValue]
      JDouble(fee_per_kb) = json \ "3"
    } yield (Btc(fee_per_kb): Satoshi).amount
}
