package fr.acinq.eclair.api

import java.net.InetSocketAddress

import akka.actor.ActorRef
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.StatusCodes.{register => _, _}
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers.HttpOriginRange.*
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
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
import fr.acinq.eclair.wire.{ChannelAnnouncement, NodeAnnouncement}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JInt, JString}
import org.json4s.{JValue, jackson}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
  * Created by PM on 25/01/2016.
  */

object JSON_CONST {
  val id: String = "eclair-node"
  val version: String = "1.0"
}
// @formatter:off
case class JsonRPCBody(jsonrpc: String = JSON_CONST.version, id: String = JSON_CONST.id, method: String, params: Seq[JValue])
case class Error(code: Int, message: String)
case class JsonRPCRes(result: AnyRef, error: Option[Error], id: String)
case class Status(node_id: String)
case class GetInfoResponse(nodeId: PublicKey, alias: String, port: Int, chainHash: BinaryData, blockHeight: Int)
case class ChannelInfo(shortChannelId: String, nodeId1: PublicKey , nodeId2: PublicKey)
// @formatter:on

trait Service extends Logging {

  implicit def ec: ExecutionContext = ExecutionContext.Implicits.global

  implicit val serialization = jackson.Serialization
  implicit val formats = org.json4s.DefaultFormats + new BinaryDataSerializer + new StateSerializer + new ShaChainSerializer + new PublicKeySerializer + new PrivateKeySerializer + new ScalarSerializer + new PointSerializer + new TransactionWithInputInfoSerializer + new OutPointKeySerializer
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

  val myExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      extractRequest { request =>
        logger.info(s"API call failed with cause=${t.getMessage}")
        complete(InternalServerError, JsonRPCRes(null, Some(Error(-1, t.getMessage)), JSON_CONST.id))
      }
  }

  val myRejectionHandler: RejectionHandler = RejectionHandler.newBuilder()
    .handleNotFound {
      complete(NotFound, JsonRPCRes(null, Some(Error(-1, "not found")), JSON_CONST.id))
    }
    .handle {
      case r ⇒
        logger.error(s"API call failed with cause=$r")
        complete((BadRequest, JsonRPCRes(null, Some(Error(-1, r.toString)), JSON_CONST.id)))
    }
    .result()

  /**
    * Sends a request to a channel and expects a response
    * @param channelIdentifier can be a shortChannelId (8-byte hex encoded) or a channelId (32-byte hex encoded)
    * @param request
    * @return
    */
  def sendToChannel(channelIdentifier: String, request: Any): Future[Any] =
    for {
      fwdReq <- Future(Register.ForwardShortId(java.lang.Long.parseLong(channelIdentifier, 16), request))
          .recoverWith { case _ => Future(Register.Forward(BinaryData(channelIdentifier), request)) }
          .recoverWith { case _ => Future.failed(new RuntimeException(s"invalid channel identifier '$channelIdentifier'")) }
      res <- appKit.register ? fwdReq
    } yield res

  val route =
    respondWithDefaultHeaders(customHeaders) {
      handleExceptions(myExceptionHandler) {
        handleRejections(myRejectionHandler) {
          pathSingleSlash {
            post {
              entity(as[JsonRPCBody]) {
                req =>
                  val kit = appKit
                  import kit._
                  val f_res: Future[AnyRef] = req match {
                    case JsonRPCBody(_, _, "getinfo", _) => getInfoResponse
                    case JsonRPCBody(_, _, "connect", JString(nodeId) :: JString(host) :: JInt(port) :: Nil) =>
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
                    case JsonRPCBody(_, _, "channelsto", JString(remoteNodeId) :: Nil) =>
                      val remotePubKey = Try(PublicKey(remoteNodeId)).getOrElse(throw new RuntimeException(s"invalid remote node id '$remoteNodeId'"))
                      (register ? 'channelsTo).mapTo[Map[BinaryData, PublicKey]].map(_.filter(_._2 == remotePubKey).keys)
                    case JsonRPCBody(_, _, "channel", JString(identifier) :: Nil) =>
                      sendToChannel(identifier, CMD_GETINFO).mapTo[RES_GETINFO]
                    case JsonRPCBody(_, _, "allnodes", _) =>
                      (router ? 'nodes).mapTo[Iterable[NodeAnnouncement]].map(_.map(_.nodeId))
                    case JsonRPCBody(_, _, "allchannels", _) =>
                      (router ? 'channels).mapTo[Iterable[ChannelAnnouncement]].map(_.map(c => ChannelInfo(c.shortChannelId.toHexString, c.nodeId1, c.nodeId2)))
                    case JsonRPCBody(_, _, "receive", JString(description) :: Nil) =>
                      (paymentHandler ? ReceivePayment(None, description)).mapTo[PaymentRequest].map(PaymentRequest.write)
                    case JsonRPCBody(_, _, "receive", JInt(amountMsat) :: JString(description) :: Nil) =>
                      (paymentHandler ? ReceivePayment(Some(MilliSatoshi(amountMsat.toLong)), description)).mapTo[PaymentRequest].map(PaymentRequest.write)
                    case JsonRPCBody(_, _, "send", JInt(amountMsat) :: JString(paymentHash) :: JString(nodeId) :: Nil) =>
                      (paymentInitiator ? SendPayment(amountMsat.toLong, paymentHash, PublicKey(nodeId))).mapTo[PaymentResult]
                    case JsonRPCBody(_, _, "send", JString(paymentRequest) :: rest) =>
                      for {
                        req <- Future(PaymentRequest.read(paymentRequest))
                        amount = (req.amount, rest) match {
                          case (Some(_), JInt(amt) :: Nil) => amt.toLong // overriding payment request amount with the one provided
                          case (Some(amt), _) => amt.amount
                          case (None, JInt(amt) :: Nil) => amt.toLong // amount wasn't specified in request, using custom one
                          case (None, _) => throw new RuntimeException("you need to manually specify an amount for this payment request")
                        }
                        sendPayment = req.minFinalCltvExpiry match {
                          case None => SendPayment(amount, req.paymentHash, req.nodeId)
                          case Some(value) => SendPayment(amount, req.paymentHash, req.nodeId, value)
                        }
                        res <- (paymentInitiator ? sendPayment).mapTo[PaymentResult]
                      } yield res
                    case JsonRPCBody(_, _, "close", JString(identifier) :: JString(scriptPubKey) :: Nil) =>
                      sendToChannel(identifier, CMD_CLOSE(scriptPubKey = Some(scriptPubKey))).mapTo[String]
                    case JsonRPCBody(_, _, "close", JString(identifier) :: Nil) =>
                      sendToChannel(identifier, CMD_CLOSE(scriptPubKey = None)).mapTo[String]
                    case JsonRPCBody(_, _, "allpayments", _) => Future.successful(nodeParams.paymentsDb.listPayments())
                    case JsonRPCBody(_, _, "payment", JString(identifier) :: Nil) =>
                      Future.successful(Try(PaymentRequest.read(identifier)) match {
                        case Success(pr) => nodeParams.paymentsDb.findByPaymentHash(pr.paymentHash)
                        case Failure(f) => Try(BinaryData(identifier)) match {
                          case Success(payment_hash) => nodeParams.paymentsDb.findByPaymentHash(payment_hash)
                          case Failure(_) => throw new IllegalArgumentException("payment identifier must be a Payment Request or a Payment Hash")
                        }
                      })
                    case JsonRPCBody(_, _, "help", _) =>
                      Future.successful(List(
                        "connect (nodeId, host, port): connect to another lightning node through a secure connection",
                        "open (nodeId, host, port, fundingSatoshi, pushMsat, channelFlags = 0x01): open a channel with another lightning node",
                        "peers: list existing local peers",
                        "channels: list existing local channels",
                        "channelsto (nodeId): list existing local channels to a particular nodeId",
                        "channel (channelId): retrieve detailed information about a given channel",
                        "allnodes: list all known nodes",
                        "allchannels: list all known channels",
                        "receive (amountMsat, description): generate a payment request for a given amount",
                        "send (amountMsat, paymentHash, nodeId): send a payment to a lightning node",
                        "send (paymentRequest): send a payment to a lightning node using a BOLT11 payment request",
                        "send (paymentRequest, amountMsat): send a payment to a lightning node using a BOLT11 payment request and a custom amount",
                        "close (channelId): close a channel",
                        "close (channelId, scriptPubKey): close a channel and send the funds to the given scriptPubKey",
                        "allpayments: list all received payments",
                        "payment (paymentHash or paymentRequest): returns the payment if it has been received",
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
    }
}
