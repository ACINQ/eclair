package fr.acinq.eclair.api

import java.net.InetSocketAddress

import akka.actor.{ActorSystem, Props, Actor}
import com.google.common.util.concurrent.Service.State
import fr.acinq.eclair.io.Client
import fr.acinq.flipcoin.wallet.BitcoinJsonRPCBody
import grizzled.slf4j.Logging
import org.bitcoinj.params.TestNet3Params
import org.json4s.JsonAST.{JString, JDouble, JBool, JObject}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import spray.http.{ContentTypes, HttpEntity, StatusCodes, HttpResponse}
import spray.routing.HttpService
import spray.routing.authentication.BasicAuth

import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Failure, Success}

/**
  * Created by PM on 25/01/2016.
  */

// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
abstract class ServiceActor extends Actor with Service {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  // this actor only runs our route, but you could add
  // other things here, like request stream processing,
  // timeout handling or alternative handler registration
  def receive = runRoute(route)
}

case class JsonRPCBody(jsonrpc: String = "1.0", id: String = "scala-client", method: String, params: Seq[JValue])

trait Service extends HttpService with Logging {

  implicit def ec: ExecutionContext = ExecutionContext.Implicits.global

  implicit val formats = org.json4s.DefaultFormats

  implicit val system: ActorSystem

  val route =
    path(RestPath) { path =>
      post {
        entity(as[String]) {
          body =>
            val json = parse(body).extract[JsonRPCBody]
            val f_res: Future[JValue] = json match {
              case JsonRPCBody(_, _, "connect", JString(host) :: JInt(port) :: JInt(anchor_amount) :: Nil) =>
                val client = system.actorOf(Props(classOf[Client], new InetSocketAddress(host, port.toInt)), s"client-$host:$port")
              case _ => Future.failed(new RuntimeException("method not found"))
            }
            onComplete(f_res) {
              case Success(res) => complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, pretty(
                JObject(("result", res) ::("error", JNull) ::("id", JString(json.id)) :: Nil)
              ))))
              case Failure(t) => complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`application/json`, pretty(
                JObject(("result", JNull) ::("error", JObject(("code", JInt(-1)) ::("message", JString(t.getMessage)) :: Nil)) ::("id", JString(json.id)) :: Nil)
              ))))
            }
        }
      }
    }
}
