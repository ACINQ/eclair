package fr.acinq.eclair.blockchain.bitcoind.rpc

import java.io.IOException

import akka.actor.ActorSystem
import com.ning.http.client._
import org.json4s.{DefaultFormats, DefaultReaders}
import org.json4s.JsonAST.{JInt, JNull, JString, JValue}
import org.json4s.jackson.JsonMethods.parse
import org.json4s.jackson.Serialization._

import scala.concurrent.{ExecutionContext, Future, Promise}

// @formatter:off
case class JsonRPCRequest(jsonrpc: String = "1.0", id: String = "scala-client", method: String, params: Seq[Any])
case class Error(code: Int, message: String)
case class JsonRPCResponse(result: JValue, error: Option[Error], id: String)
case class JsonRPCError(error: Error) extends IOException(s"${error.message} (code: ${error.code})")
// @formatter:on

class BitcoinJsonRPCClient(config: AsyncHttpClientConfig, host: String, port: Int, ssl: Boolean)(implicit system: ActorSystem) {

    def this(user: String, password: String, host: String = "127.0.0.1", port: Int = 8332, ssl: Boolean = false)(implicit system: ActorSystem) = this(
      new AsyncHttpClientConfig.Builder()
        .setRealm(new Realm.RealmBuilder().setPrincipal(user).setPassword(password).setUsePreemptiveAuth(true).setScheme(Realm.AuthScheme.BASIC).build)
        .build,
      host,
      port,
      ssl
    )

  val client: AsyncHttpClient = new AsyncHttpClient(config)

  implicit val formats = DefaultFormats

  def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] = {
    val promise = Promise[JValue]()
    client
      .preparePost((if (ssl) "https" else "http") + s"://$host:$port/")
      .addHeader("Content-Type", "application/json")
      .setBody(write(JsonRPCRequest(method = method, params = params)))
      .execute(new AsyncCompletionHandler[Unit] {
        override def onCompleted(response: Response): Unit =
          try {
            val jvalue = parse(response.getResponseBody)
            val jerror = jvalue \ "error"
            val result = jvalue \ "result"
            if (jerror != JNull) {
              for {
                JInt(code) <- jerror \ "code"
                JString(message) <- jerror \ "message"
              } yield promise.failure(new JsonRPCError(Error(code.toInt, message)))
            } else {
              promise.success(result)
            }
          } catch {
            case t: Throwable => promise.failure(t)
          }

        override def onThrowable(t: Throwable): Unit = promise.failure(t)
      })
    promise.future
  }

  def invoke(request: Seq[(String, Seq[Any])])(implicit ec: ExecutionContext): Future[Seq[JValue]] = ???

}