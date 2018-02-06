package fr.acinq.eclair.blockchain.bitcoind.rpc

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, BasicHttpCredentials}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import de.heikoseeberger.akkahttpjson4s.Json4sSupport._
import org.json4s.JsonAST.JValue
import org.json4s.{DefaultFormats, jackson}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

class BasicBitcoinJsonRPCClient(user: String, password: String, host: String = "127.0.0.1", port: Int = 8332, ssl: Boolean = false)(implicit system: ActorSystem) extends BitcoinJsonRPCClient {

  val scheme = if (ssl) "https" else "http"
  val uri = Uri(s"$scheme://$host:$port")
  implicit val serialization = jackson.Serialization
  implicit val formats = DefaultFormats
  val log = Logging(system, classOf[BasicBitcoinJsonRPCClient])

  implicit val materializer = ActorMaterializer()
  val httpClientFlow = Http().cachedHostConnectionPool[Promise[HttpResponse]](host, port)

  val queueSize = 256
  val queue = Source.queue[(HttpRequest, Promise[HttpResponse])](queueSize, OverflowStrategy.dropNew)
    .via(httpClientFlow)
    .toMat(Sink.foreach({
      case ((Success(resp), p)) => p.success(resp)
      case ((Failure(e), p)) => p.failure(e)
    }))(Keep.left)
    .run()

  def queueRequest(request: HttpRequest): Future[HttpResponse] = {
    val responsePromise = Promise[HttpResponse]()
    queue.offer(request -> responsePromise).flatMap {
      case QueueOfferResult.Enqueued => responsePromise.future
      case QueueOfferResult.Dropped => Future.failed(new RuntimeException("Queue overflowed. Try again later."))
      case QueueOfferResult.Failure(ex) => Future.failed(ex)
      case QueueOfferResult.QueueClosed => Future.failed(new RuntimeException("Queue was closed (pool shut down) while running the request. Try again later."))
    }
  }

  override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] =
    invoke(Seq(JsonRPCRequest(method = method, params = params))).map(l => jsonResponse2Exception(l.head).result)

  def jsonResponse2Exception(jsonRPCResponse: JsonRPCResponse): JsonRPCResponse = jsonRPCResponse match {
    case JsonRPCResponse(_, Some(error), _) => throw JsonRPCError(error)
    case o => o
  }

  def invoke(requests: Seq[JsonRPCRequest])(implicit ec: ExecutionContext): Future[Seq[JsonRPCResponse]] =
    for {
      entity <- Marshal(requests).to[RequestEntity]
      _ = log.debug("sending rpc request with body={}", entity)
      httpRes <- queueRequest(HttpRequest(uri = "/", method = HttpMethods.POST).addHeader(Authorization(BasicHttpCredentials(user, password))).withEntity(entity))
      jsonRpcRes <- Unmarshal(httpRes).to[Seq[JsonRPCResponse]].recover {
        case t: Throwable if httpRes.status == StatusCodes.Unauthorized => throw new RuntimeException("bitcoind replied with 401/Unauthorized (bad user/password?)", t)
      }
    } yield jsonRpcRes

}