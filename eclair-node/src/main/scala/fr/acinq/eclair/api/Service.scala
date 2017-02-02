package fr.acinq.eclair.api

import akka.actor.ActorRef
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers.HttpOriginRange.*
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.util.Timeout
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair._
import fr.acinq.eclair.channel.Register.{ListChannels, SendCommand}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.CreatePayment
import fr.acinq.eclair.router.ChannelDesc
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JDouble, JInt, JString}
import org.json4s.{JValue, jackson}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Created by PM on 25/01/2016.
  */

// @formatter:off
case class JsonRPCBody(jsonrpc: String = "1.0", id: String = "scala-client", method: String, params: Seq[JValue])
case class Error(code: Int, message: String)
case class JsonRPCRes(result: AnyRef, error: Option[Error], id: String)
case class Status(node_id: String)
// @formatter:on

trait Service extends Logging {

  implicit def ec: ExecutionContext = ExecutionContext.Implicits.global

  implicit val serialization = jackson.Serialization
  implicit val formats = org.json4s.DefaultFormats + new BinaryDataSerializer + new StateSerializer + new ShaChainSerializer
  implicit val timeout = Timeout(30 seconds)
  implicit val shouldWritePretty: ShouldWritePretty = ShouldWritePretty.True

  import Json4sSupport.{json4sMarshaller, json4sUnmarshaller}

  def connect(host: String, port: Int, pubkey: BinaryData, fundingSatoshis: Satoshi, pushMsat: MilliSatoshi): Unit

  def register: ActorRef

  def router: ActorRef

  def paymentInitiator: ActorRef

  def paymentHandler: ActorRef

  val customHeaders = `Access-Control-Allow-Origin`(*) ::
    `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
    `Access-Control-Allow-Methods`(PUT, GET, POST, DELETE, OPTIONS) ::
    `Cache-Control`(public, `no-store`, `max-age`(0)) ::
    `Access-Control-Allow-Headers`("x-requested-with") :: Nil

  val route =
    respondWithDefaultHeaders(customHeaders) {
      pathSingleSlash {
        post {
          entity(as[JsonRPCBody]) {
            req =>
              val f_res: Future[AnyRef] = req match {
                case JsonRPCBody(_, _, "connect", JString(host) :: JInt(port) :: JString(pubkey) :: JInt(anchor_amount) :: Nil) =>
                  connect(host, port.toInt, BinaryData(pubkey), Satoshi(anchor_amount.toLong), MilliSatoshi(0))
                  Future.successful("ok")
                case JsonRPCBody(_, _, "info", _) =>
                  Future.successful(Status(Globals.Node.id))
                case JsonRPCBody(_, _, "list", _) =>
                  (register ? ListChannels).mapTo[Iterable[ActorRef]]
                    .flatMap(l => Future.sequence(l.map(c => c ? CMD_GETINFO)))
                case JsonRPCBody(_, _, "network", _) =>
                  (router ? 'channels).mapTo[Iterable[ChannelDesc]]
                case JsonRPCBody(_, _, "addhtlc", JInt(amount) :: JString(rhash) :: JString(nodeId) :: Nil) =>
                  (paymentInitiator ? CreatePayment(amount.toLong, BinaryData(rhash), BinaryData(nodeId))).mapTo[ChannelEvent]
                case JsonRPCBody(_, _, "genh", _) =>
                  (paymentHandler ? 'genh).mapTo[BinaryData]
                case JsonRPCBody(_, _, "sign", JInt(channel) :: Nil) =>
                  (register ? SendCommand(channel.toLong, CMD_SIGN)).mapTo[ActorRef].map(_ => "ok")
                case JsonRPCBody(_, _, "fulfillhtlc", JString(channel) :: JDouble(id) :: JString(r) :: Nil) =>
                  (register ? SendCommand(channel.toLong, CMD_FULFILL_HTLC(id.toLong, BinaryData(r), commit = true))).mapTo[ActorRef].map(_ => "ok")
                case JsonRPCBody(_, _, "close", JString(channel) :: JString(scriptPubKey) :: Nil) =>
                  (register ? SendCommand(channel.toLong, CMD_CLOSE(Some(scriptPubKey)))).mapTo[ActorRef].map(_ => "ok")
                case JsonRPCBody(_, _, "close", JString(channel) :: Nil) =>
                  (register ? SendCommand(channel.toLong, CMD_CLOSE(None))).mapTo[ActorRef].map(_ => "ok")
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
                case Success(res) => complete(JsonRPCRes(res, None, req.id))
                case Failure(t) => complete(StatusCodes.InternalServerError, JsonRPCRes(null, Some(Error(-1, t.getMessage)), req.id))
              }
          }
        }
      }
    }
}
