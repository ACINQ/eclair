package fr.acinq.eclair.api

import java.net.InetSocketAddress

import akka.actor.ActorRef
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.util.Timeout
import akka.http.scaladsl.server.Directives._
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.Boot
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JString
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import akka.pattern.ask
import fr.acinq.eclair.channel.Register.ListChannels
import fr.acinq.eclair.router.{ChannelDesc, CreatePayment}

/**
  * Created by PM on 25/01/2016.
  */

case class JsonRPCBody(jsonrpc: String = "1.0", id: String = "scala-client", method: String, params: Seq[JValue])

case class Error(code: Int, message: String)

case class JsonRPCRes(result: AnyRef, error: Option[Error], id: String)

case class Status(node_id: String)

//TODO : use Json4sSupport ?
trait Service extends Logging {

  implicit def ec: ExecutionContext = ExecutionContext.Implicits.global

  implicit val formats = org.json4s.DefaultFormats + new BinaryDataSerializer + new StateSerializer + new Sha256Serializer + new ShaChainSerializer
  implicit val timeout = Timeout(30 seconds)

  def connect(addr: InetSocketAddress, amount: Long): Unit

  // amount in satoshis
  def register: ActorRef

  def router: ActorRef

  def paymentHandler: ActorRef

  def sendCommand(channel_id: String, cmd: Command): Future[String] = {
    Boot.system.actorSelection(Register.actorPathToChannelId(channel_id)).resolveOne().map(actor => {
      actor ! cmd
      "ok"
    })
  }

  val customHeaders = RawHeader("Access-Control-Allow-Origin", "*") ::
    RawHeader("Access-Control-Allow-Headers", "Content-Type") ::
    RawHeader("Access-Control-Allow-Methods", "PUT, GET, POST, DELETE, OPTIONS") ::
    RawHeader("Cache-control", "public, no-store, max-age=0") ::
    RawHeader("Access-Control-Allow-Headers", "x-requested-with") :: Nil

  val route =
    pathSingleSlash {
      post {
        entity(as[String]) {
          body =>
            val json = parse(body).extract[JsonRPCBody]
            val f_res: Future[AnyRef] = json match {
              case JsonRPCBody(_, _, "connect", JString(host) :: JInt(port) :: JInt(anchor_amount) :: Nil) =>
                connect(new InetSocketAddress(host, port.toInt), anchor_amount.toLong)
                Future.successful("ok")
              case JsonRPCBody(_, _, "info", _) =>
                Future.successful(Status(Globals.Node.id))
              case JsonRPCBody(_, _, "list", _) =>
                (register ? ListChannels).mapTo[Iterable[ActorRef]]
                  .flatMap(l => Future.sequence(l.map(c => c ? CMD_GETINFO)))
              case JsonRPCBody(_, _, "network", _) =>
                (router ? 'network).mapTo[Iterable[ChannelDesc]]
              case JsonRPCBody(_, _, "addhtlc", JInt(amount) :: JString(rhash) :: JString(nodeId) :: Nil) =>
                (router ? CreatePayment(amount.toInt, BinaryData(rhash), BinaryData(nodeId))).mapTo[ActorRef].map(_ => "ok")
              case JsonRPCBody(_, _, "genh", _) =>
                (paymentHandler ? 'genh).mapTo[BinaryData]
              case JsonRPCBody(_, _, "sign", JString(channel) :: Nil) =>
                sendCommand(channel, CMD_SIGN)
              case JsonRPCBody(_, _, "fulfillhtlc", JString(channel) :: JDouble(id) :: JString(r) :: Nil) =>
                sendCommand(channel, CMD_FULFILL_HTLC(id.toLong, BinaryData(r), commit = true))
              case JsonRPCBody(_, _, "close", JString(channel) :: JString(scriptPubKey) :: Nil) =>
                sendCommand(channel, CMD_CLOSE(Some(scriptPubKey)))
              case JsonRPCBody(_, _, "close", JString(channel) :: Nil) =>
                sendCommand(channel, CMD_CLOSE(None))
              case JsonRPCBody(_, _, "help", _) =>
                Future.successful(List(
                  "info: display basic node information",
                  "connect (host, port, anchor_amount): open a channel with another eclair or lightningd instance",
                  "list: list existing channels",
                  "addhtlc (amount, rhash, nodeId): send an htlc",
                  "sign (channel_id): update the commitment transaction",
                  "fulfillhtlc (channel_id, htlc_id, r): fulfill an htlc",
                  "close (channel_id): close a channel",
                  "help: display this message"))
              case _ => Future.failed(new RuntimeException("method not found"))
            }

            onComplete(f_res) {
              case Success(res) => complete(HttpResponse(StatusCodes.OK, headers = customHeaders, entity = HttpEntity(ContentTypes.`application/json`, Serialization.writePretty(
                JsonRPCRes(res, None, json.id)
              ))))
              case Failure(t) => complete(HttpResponse(StatusCodes.InternalServerError, headers = customHeaders, entity = HttpEntity(ContentTypes.`application/json`, Serialization.writePretty(
                JsonRPCRes(null, Some(Error(-1, t.getMessage)), json.id))
              )))
            }
        }
      }
    }
}
