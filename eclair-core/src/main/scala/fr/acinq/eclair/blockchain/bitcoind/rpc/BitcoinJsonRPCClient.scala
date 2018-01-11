package fr.acinq.eclair.blockchain.bitcoind.rpc

import java.io.IOException

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Keep, Sink, Source}
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import org.json4s.JsonAST.JValue
import org.json4s.{DefaultFormats, jackson}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

// @formatter:off
case class JsonRPCRequest(jsonrpc: String = "1.0", id: String = "scala-client", method: String, params: Seq[Any])
case class Error(code: Int, message: String)
case class JsonRPCResponse(result: JValue, error: Option[Error], id: String)
case class JsonRPCError(error: Error) extends IOException(s"${error.message} (code: ${error.code})")
// @formatter:on

class BitcoinJsonRPCClient(user: String, password: String, host: String = "127.0.0.1", port: Int = 8332, ssl: Boolean = false)(implicit system: ActorSystem) {

  val scheme = if (ssl) "https" else "http"
  val uri = Uri(s"$scheme://$host:$port")
  implicit val serialization = jackson.Serialization
  implicit val formats = DefaultFormats
  val log = Logging(system, classOf[BitcoinJsonRPCClient])

  implicit val materializer = ActorMaterializer()
  val httpClientFlow = Http().cachedHostConnectionPool[Promise[HttpResponse]](host, port)

  val queueSize = 32768
  val queue = Source.queue[(HttpRequest, Promise[HttpResponse])](queueSize, OverflowStrategy.dropNew)
      .via(httpClientFlow)
      .toMat(Sink.foreach({
        case ((Success(resp), p)) => p.success(resp)
        case ((Failure(e), p))    => p.failure(e)
      }))(Keep.left)
      .run()

  def queueRequest(request: HttpRequest): Future[HttpResponse] = {
    val responsePromise = Promise[HttpResponse]()
    queue.offer(request -> responsePromise).flatMap {
      case QueueOfferResult.Enqueued    => responsePromise.future
      case QueueOfferResult.Dropped     => Future.failed(new RuntimeException("Queue overflowed. Try again later."))
      case QueueOfferResult.Failure(ex) => Future.failed(ex)
      case QueueOfferResult.QueueClosed => Future.failed(new RuntimeException("Queue was closed (pool shut down) while running the request. Try again later."))
    }
  }

  def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] =
    for {
      entity <- Marshal(JsonRPCRequest(method = method, params = params)).to[RequestEntity]
      _ = log.debug("sending rpc request with body={}", entity)
      httpRes <- queueRequest(HttpRequest(uri = "/", method = HttpMethods.POST).addHeader(Authorization(BasicHttpCredentials(user, password))).withEntity(entity))
      jsonRpcRes <- Unmarshal(httpRes).to[JsonRPCResponse].map {
        case JsonRPCResponse(_, Some(error), _) =>
          if (method == "listunspent" && error.code == -32601) {
            throw JsonRPCError(new Error(error.code, s"Bitcoind must have wallet mode enabled.\n\n${error.message}"))
          } else {
            throw JsonRPCError(error)
          }
        case o => o
      } recover {
        case t: Throwable if httpRes.status == StatusCodes.Unauthorized => throw new RuntimeException("bitcoind replied with 401/Unauthorized (bad user/password?)", t)
      }
    } yield jsonRpcRes.result

  def invoke(request: Seq[(String, Seq[Any])])(implicit ec: ExecutionContext): Future[Seq[JValue]] =
    for {
      entity <- Marshal(request.map(r => JsonRPCRequest(method = r._1, params = r._2))).to[RequestEntity]
      _ = log.debug("sending rpc request with body={}", entity)
      httpRes <- queueRequest(HttpRequest(uri = "/", method = HttpMethods.POST).addHeader(Authorization(BasicHttpCredentials(user, password))).withEntity(entity))
      jsonRpcRes <- Unmarshal(httpRes).to[Seq[JsonRPCResponse]].map {
        //case JsonRPCResponse(_, Some(error), _) => throw JsonRPCError(error)
        case o => o
      } recover {
        case t: Throwable if httpRes.status == StatusCodes.Unauthorized => throw new RuntimeException("bitcoind replied with 401/Unauthorized (bad user/password?)", t)
      }
    } yield jsonRpcRes.map(_.result)

}
