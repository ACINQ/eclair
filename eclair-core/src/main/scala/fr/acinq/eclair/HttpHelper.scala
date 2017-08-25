package fr.acinq.eclair

import com.ning.http.client.{AsyncCompletionHandler, AsyncHttpClient, AsyncHttpClientConfig, Response}
import grizzled.slf4j.Logging
import org.json4s.DefaultFormats
import org.json4s.JsonAST.{JNothing, JValue}
import org.json4s.jackson.JsonMethods.parse

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

object HttpHelper extends Logging {

  val client = new AsyncHttpClient(new AsyncHttpClientConfig.Builder().setAcceptAnyCertificate(true).build())

  implicit val formats = DefaultFormats

  def get(url: String)(implicit ec: ExecutionContext): Future[JValue] = {
    val promise = Promise[JValue]
    client
      .prepareGet(url)
      .execute(new AsyncCompletionHandler[Unit] {
        override def onCompleted(response: Response): Unit = {
          Try(parse(response.getResponseBody)) match {
            case Success(json) => promise.success(json)
            case Failure(t) => promise.success(JNothing)
          }
        }
      })
    val f = promise.future
    f onFailure {
      case t: Throwable => logger.error(s"GET $url failed: ", t)
    }
    f
  }

}
