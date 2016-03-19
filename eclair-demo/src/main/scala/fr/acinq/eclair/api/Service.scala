package fr.acinq.eclair.api

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorRef}
import akka.util.Timeout
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.RegisterActor.GetChannels
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.{Boot, channel}
import grizzled.slf4j.Logging
import lightning.locktime
import lightning.locktime.Locktime.Seconds
import org.json4s.JsonAST.{JBool, JDouble, JObject, JString}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization
import spray.http.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import spray.routing.HttpService

import scala.concurrent.{ExecutionContext, Future}
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
case class Error(code: Int, message: String)
case class JsonRPCRes(result: AnyRef, error: Option[Error], id: String)

//TODO : use Json4sSupport ?
trait Service extends HttpService with Logging {

  implicit def ec: ExecutionContext = ExecutionContext.Implicits.global

  implicit val formats = org.json4s.DefaultFormats + new BinaryDataSerializer + new StateSerializer + new Sha256Serializer
  implicit val timeout = Timeout(30 seconds)

  def connect(addr: InetSocketAddress, amount: Long): Unit // amount in satoshis
  def register: ActorRef

  def sendCommand(channel: String, cmd: Command): Future[String] = {
    Boot.system.actorSelection(s"/user/register/handler-$channel/channel").resolveOne().map(actor => {
      actor ! cmd
      "ok"
    })
  }

  val route =
    path(RestPath) { path =>
      post {
        entity(as[String]) {
          body =>
            val json = parse(body).extract[JsonRPCBody]
            val f_res: Future[AnyRef] = json match {
              case JsonRPCBody(_, _, "connect", JString(host) :: JInt(port) :: JInt(anchor_amount) :: Nil) =>
                connect(new InetSocketAddress(host, port.toInt), anchor_amount.toLong)
                Future.successful("")
              case JsonRPCBody(_, _, "list", _) =>
                (register ? GetChannels).mapTo[Iterable[RES_GETINFO]]
              case JsonRPCBody(_, _, "addhtlc", JString(channel) :: JInt(amount) :: JString(rhash) :: JInt(expiry) :: Nil) =>
                sendCommand(channel, CMD_SEND_HTLC_UPDATE(amount.toInt, BinaryData(rhash), locktime(Seconds(expiry.toInt))))
              case JsonRPCBody(_, _, "addhtlc_r", JInt(amount) :: JString(rhash) :: JInt(expiry) :: tail) =>
                val nodeIds = tail.toSeq.map {
                  case JString(nodeId) => nodeId
                }
                Boot.system.actorSelection(s"*/register/handler-*/channel/${nodeIds.head}-*")
                  .resolveOne(2 seconds)
                    .map { channel =>
                      channel ! CMD_SEND_HTLC_UPDATE(amount.toInt, BinaryData(rhash), locktime(Seconds(expiry.toInt)), nodeIds.drop(1))
                      channel.toString()
                    }
              case JsonRPCBody(_, _, "fulfillhtlc", JString(channel) :: JString(r) :: Nil) =>
                sendCommand(channel, CMD_SEND_HTLC_FULFILL(BinaryData(r)))
              case JsonRPCBody(_, _, "close", JString(channel) :: Nil) =>
                sendCommand(channel, CMD_CLOSE(Globals.closing_fee))
              case JsonRPCBody(_, _, "help", _) =>
                Future.successful(List(
                  "connect (host, port, anchor_amount): opens a channel with another eclair or lightningd instance",
                  "list: lists existing channels",
                  "addhtlc (channel_id, amount, rhash, locktime): sends an htlc",
                  "fulfillhtlc (channel_id, r): fulfills an htlc",
                  "close (channel_id): closes a channel",
                  "help: displays this message"))
              case _ => Future.failed(new RuntimeException("method not found"))
            }

            onComplete(f_res) {
              case Success(res) => complete(HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, Serialization.writePretty(
                JsonRPCRes(res, None, json.id)
              ))))
              case Failure(t) => complete(HttpResponse(StatusCodes.InternalServerError, entity = HttpEntity(ContentTypes.`application/json`, Serialization.writePretty(
                JsonRPCRes(null, Some(Error(-1, t.getMessage)), json.id))
              )))
            }
        }
      }
    }
}
