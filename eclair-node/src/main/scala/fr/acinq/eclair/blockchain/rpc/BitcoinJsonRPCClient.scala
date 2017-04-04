package fr.acinq.eclair.blockchain.rpc

import java.io.IOException

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import org.json4s.JsonAST.JValue
import org.json4s.{DefaultFormats, jackson}

import scala.concurrent.{ExecutionContext, Future}

// @formatter:off
case class JsonRPCRequest(jsonrpc: String = "1.0", id: String = "scala-client", method: String, params: Seq[Any])
case class Error(code: Int, message: String)
case class JsonRPCResponse(result: JValue, error: Option[Error], id: String)
case class JsonRPCError(error: Error) extends IOException(s"${error.message} (code: ${error.code})")
// @formatter:on

class BitcoinJsonRPCClient(user: String, password: String, host: String = "127.0.0.1", port: Int = 8332, ssl: Boolean = false)(implicit system: ActorSystem) {

  val scheme = if (ssl) "https" else "http"
  val uri = Uri(s"$scheme://$host:$port")

  implicit val materializer = ActorMaterializer()
  val httpClient = Http(system)
  implicit val serialization = jackson.Serialization
  implicit val formats = DefaultFormats

  def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] =
    for {
      entity <- Marshal(JsonRPCRequest(method = method, params = params)).to[RequestEntity]
      httpRes <- httpClient.singleRequest(HttpRequest(uri = uri, method = HttpMethods.POST).addHeader(Authorization(BasicHttpCredentials(user, password))).withEntity(entity))
      jsonRpcRes <- Unmarshal(httpRes).to[JsonRPCResponse].map {
        case JsonRPCResponse(_, Some(error), _) => throw JsonRPCError(error)
        case o => o
      } recover {
        case t: Throwable if httpRes.status == StatusCodes.Unauthorized => throw new RuntimeException("bitcoind replied with 401/Unauthorized (bad user/password?)", t)
      }
    } yield jsonRpcRes.result

  def invoke(request: Seq[(String, Seq[Any])])(implicit ec: ExecutionContext): Future[Seq[JValue]] =
    for {
      entity <- Marshal(request.map(r => JsonRPCRequest(method = r._1, params = r._2))).to[RequestEntity]
      httpRes <- httpClient.singleRequest(HttpRequest(uri = uri, method = HttpMethods.POST).addHeader(Authorization(BasicHttpCredentials(user, password))).withEntity(entity))
      jsonRpcRes <- Unmarshal(httpRes).to[Seq[JsonRPCResponse]].map {
        //case JsonRPCResponse(_, Some(error), _) => throw JsonRPCError(error)
        case o => o
      } recover {
        case t: Throwable if httpRes.status == StatusCodes.Unauthorized => throw new RuntimeException("bitcoind replied with 401/Unauthorized (bad user/password?)", t)
      }
    } yield jsonRpcRes.map(_.result)

}