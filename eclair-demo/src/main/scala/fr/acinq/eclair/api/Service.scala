package fr.acinq.eclair.api

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem, Props, Actor}
import akka.util.Timeout
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import fr.acinq.eclair.channel.CMD_SEND_HTLC_FULFILL
import fr.acinq.eclair.{Boot, GetChannels, CreateChannel}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JString, JDouble, JBool, JObject}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import spray.http.{ContentTypes, HttpEntity, StatusCodes, HttpResponse}
import spray.routing.HttpService

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import akka.pattern.ask

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
  implicit val timeout = Timeout(30 seconds)

  def register: ActorRef

  val route =
    path(RestPath) { path =>
      post {
        entity(as[String]) {
          body =>
            val json = parse(body).extract[JsonRPCBody]
            val f_res: Future[JValue] = json match {
              case JsonRPCBody(_, _, "connect", JString(host) :: JInt(port) :: JInt(anchor_amount) :: Nil) =>
                register ! CreateChannel(new InetSocketAddress(host, port.toInt))
                Future.successful(JNothing)
              case JsonRPCBody(_, _, "list", _) =>
                (register ? GetChannels).mapTo[Iterable[ActorRef]].map(x => JArray(x.toList.map(a => JString(a.toString()))))
              case JsonRPCBody(_, _, "fulfillhtlc", JString(channel) :: JString(r) :: Nil) =>
                Boot.system.actorSelection(channel).resolveOne().map(actor => {
                  actor ! CMD_SEND_HTLC_FULFILL(BinaryData(r))
                  JString("ok")
                })
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
