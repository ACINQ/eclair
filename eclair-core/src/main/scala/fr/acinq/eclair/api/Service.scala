package fr.acinq.eclair.api

import java.net.InetSocketAddress

import akka.actor.{ActorRef, ActorSystem}
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
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.Kit
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Switchboard.{NewChannel, NewConnection}
import fr.acinq.eclair.payment.{PaymentRequest, PaymentResult, ReceivePayment, SendPayment}
import fr.acinq.eclair.wire.NodeAnnouncement
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JInt, JString}
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
case class GetInfoResponse(nodeId: PublicKey, alias: String, port: Int, chainHash: String, blockHeight: Int)
// @formatter:on

trait Service extends Logging {

  implicit def ec: ExecutionContext = ExecutionContext.Implicits.global

  implicit val serialization = jackson.Serialization
  implicit val formats = org.json4s.DefaultFormats + new BinaryDataSerializer + new StateSerializer + new ShaChainSerializer + new PublicKeySerializer + new PrivateKeySerializer + new ScalarSerializer + new PointSerializer + new TransactionWithInputInfoSerializer
  implicit val timeout = Timeout(30 seconds)
  implicit val shouldWritePretty: ShouldWritePretty = ShouldWritePretty.True

  import Json4sSupport.{marshaller, unmarshaller}

  def appKit: Kit

  def getInfoResponse: Future[GetInfoResponse]

  val customHeaders = `Access-Control-Allow-Origin`(*) ::
    `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
    `Access-Control-Allow-Methods`(PUT, GET, POST, DELETE, OPTIONS) ::
    `Cache-Control`(public, `no-store`, `max-age`(0)) ::
    `Access-Control-Allow-Headers`("x-requested-with") :: Nil

  def getChannel(channelId: String): Future[ActorRef] =
    for {
      channels <- (appKit.register ? 'channels).mapTo[Map[BinaryData, ActorRef]]
    } yield channels.get(BinaryData(channelId)).getOrElse(throw new RuntimeException("unknown channel"))

  val route =
    respondWithDefaultHeaders(customHeaders) {
      pathSingleSlash {
        post {
          entity(as[JsonRPCBody]) {
            req =>
              val kit = appKit
              import kit._
              val f_res: Future[AnyRef] = req match {
                case JsonRPCBody(_, _, "getinfo", _) => getInfoResponse
                case JsonRPCBody(_, _, "connect", JString(host) :: JInt(port) :: JString(nodeId) :: Nil) =>
                  (switchboard ? NewConnection(PublicKey(nodeId), new InetSocketAddress(host, port.toInt), None)).mapTo[String]
                case JsonRPCBody(_, _, "open", JString(nodeId) :: JString(host) :: JInt(port) :: JInt(fundingSatoshi) :: JInt(pushMsat) :: options) =>
                  val channelFlags = options match {
                    case JInt(value) :: Nil => Some(value.toByte)
                    case _ => None // TODO: too lax?
                  }
                  (switchboard ? NewConnection(PublicKey(nodeId), new InetSocketAddress(host, port.toInt), Some(NewChannel(Satoshi(fundingSatoshi.toLong), MilliSatoshi(pushMsat.toLong), channelFlags)))).mapTo[String]
                case JsonRPCBody(_, _, "peers", _) =>
                  (switchboard ? 'peers).mapTo[Map[PublicKey, ActorRef]].map(_.map(_._1.toBin))
                case JsonRPCBody(_, _, "channels", _) =>
                  (register ? 'channels).mapTo[Map[Long, ActorRef]].map(_.keys)
                case JsonRPCBody(_, _, "channel", JString(channelId) :: Nil) =>
                  getChannel(channelId).flatMap(_ ? CMD_GETINFO).mapTo[RES_GETINFO]
                case JsonRPCBody(_, _, "network", _) =>
                  (router ? 'nodes).mapTo[Iterable[NodeAnnouncement]].map(_.map(_.nodeId))
                case JsonRPCBody(_,_, "receive", JInt(amountMsat) :: JString(description) :: Nil) =>
                  (paymentHandler ? ReceivePayment(new MilliSatoshi(amountMsat.toLong), description)).mapTo[PaymentRequest].map(PaymentRequest.write(_))
                case JsonRPCBody(_, _, "send", JInt(amountMsat) :: JString(paymentHash) :: JString(nodeId) :: Nil) =>
                  (paymentInitiator ? SendPayment(amountMsat.toLong, paymentHash, PublicKey(nodeId))).mapTo[PaymentResult]
                case JsonRPCBody(_, _, "send", JString(paymentRequest) :: Nil) =>
                  for {
                    req <- Future(PaymentRequest.read(paymentRequest))
                    res <- (paymentInitiator ? SendPayment(req.amount.getOrElse(throw new RuntimeException("request without amounts are not supported")).amount, req.paymentHash, req.nodeId)).mapTo[PaymentResult]
                  } yield res
                case JsonRPCBody(_, _, "close", JString(channelId) :: JString(scriptPubKey) :: Nil) =>
                  getChannel(channelId).flatMap(_ ? CMD_CLOSE(scriptPubKey = Some(scriptPubKey))).mapTo[String]
                case JsonRPCBody(_, _, "close", JString(channelId) :: Nil) =>
                  getChannel(channelId).flatMap(_ ? CMD_CLOSE(scriptPubKey = None)).mapTo[String]
                case JsonRPCBody(_, _, "help", _) =>
                  Future.successful(List(
                    "connect (host, port, nodeId): connect to another lightning node through a secure connection",
                    "open (nodeId, host, port, fundingSatoshi, pushMsat, channelFlags = 0x01): open a channel with another lightning node",
                    "peers: list existing local peers",
                    "channels: list existing local channels",
                    "channel (channelId): retrieve detailed information about a given channel",
                    "network: list all the nodes announced in network",
                    "receive (amountMsat, description): generate a payment request for a given amount",
                    "send (amountMsat, paymentHash, nodeId): send a payment to a lightning node",
                    "send (paymentRequest): send a payment to a lightning node using a BOLT11 payment request",
                    "close (channelId): close a channel",
                    "close (channelId, scriptPubKey): close a channel and send the funds to the given scriptPubKey",
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
