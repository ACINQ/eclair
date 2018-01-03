package fr.acinq.eclair.api

import akka.actor.ActorRef
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers.HttpOriginRange.*
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import akka.http.scaladsl.server.{ExceptionHandler, Rejection, RejectionHandler, Route, MalformedRequestContentRejection}
import akka.pattern.ask
import akka.util.Timeout
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.Kit
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.{PaymentRequest, PaymentResult, ReceivePayment, SendPayment}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.wire.{ChannelAnnouncement, NodeAnnouncement}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JBool, JInt, JString}
import org.json4s.{JValue, jackson}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// @formatter:off
case class JsonRPCBody(jsonrpc: String = "1.0", id: String = "eclair-node", method: String, params: Seq[JValue])
case class Error(code: Int, message: String)
case class JsonRPCRes(result: AnyRef, error: Option[Error], id: String)
case class Status(node_id: String)
case class GetInfoResponse(nodeId: PublicKey, alias: String, port: Int, chainHash: BinaryData, blockHeight: Int)
case class ChannelInfo(shortChannelId: String, nodeId1: PublicKey, nodeId2: PublicKey)
trait RPCRejection extends Rejection {
  def requestId: String
}
final case class UnknownMethodRejection(requestId: String) extends RPCRejection
final case class UnknownParamsRejection(requestId: String, message: String) extends RPCRejection
final case class NotFoundRejection(requestId: String) extends RPCRejection
final case class ValidationRejection(requestId: String, message: String) extends RPCRejection
// @formatter:on

trait Service extends Logging {

  implicit def ec: ExecutionContext = ExecutionContext.Implicits.global

  implicit val serialization = jackson.Serialization
  implicit val formats = org.json4s.DefaultFormats + new BinaryDataSerializer + new StateSerializer + new ShaChainSerializer + new PublicKeySerializer + new PrivateKeySerializer + new ScalarSerializer + new PointSerializer + new TransactionWithInputInfoSerializer + new InetSocketAddressSerializer + new OutPointKeySerializer
  implicit val timeout = Timeout(30 seconds)
  implicit val shouldWritePretty: ShouldWritePretty = ShouldWritePretty.True

  import Json4sSupport.{marshaller, unmarshaller}

  def appKit: Kit

  val customHeaders = `Access-Control-Allow-Origin`(*) ::
    `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
    `Access-Control-Allow-Methods`(PUT, GET, POST, DELETE, OPTIONS) ::
    `Cache-Control`(public, `no-store`, `max-age`(0)) ::
    `Access-Control-Allow-Headers`("x-requested-with") :: Nil

  val myExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      extractRequest { request =>
        logger.info(s"API call failed with cause=${t.getMessage}")
        complete(StatusCodes.InternalServerError, JsonRPCRes(null, Some(Error(StatusCodes.InternalServerError.intValue, t.getMessage)), "-1"))
      }
  }

  def completeRpcFuture(requestId: String, future: Future[AnyRef]): Route = onComplete(future) {
    case Success(s) => completeRpc(requestId, s)
    case Failure(_) => reject
  }
  def completeRpc(requestId: String, result: AnyRef): Route = complete(JsonRPCRes(result, None, requestId))

  val myRejectionHandler: RejectionHandler = RejectionHandler.newBuilder()
    .handleNotFound {
      complete(StatusCodes.NotFound, JsonRPCRes(null, Some(Error(StatusCodes.NotFound.intValue, "not found")), "-1"))
    }
    .handle {
      case v: ValidationRejection ⇒ complete(StatusCodes.BadRequest, JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, v.message)), v.requestId))
      case nf: NotFoundRejection ⇒ complete(StatusCodes.NotFound, JsonRPCRes(null, Some(Error(StatusCodes.NotFound.intValue, "not found")), nf.requestId))
      case ukm: UnknownMethodRejection ⇒ complete(StatusCodes.BadRequest, JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, "method not found")), ukm.requestId))
      case p: UnknownParamsRejection ⇒ complete(StatusCodes.BadRequest,
        JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, s"invalid parameters for this method, should be: ${p.message}")), p.requestId))
      case m: MalformedRequestContentRejection ⇒ complete(StatusCodes.BadRequest,
        JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, s"malformed parameters for this method: ${m.message}")), "-1"))
      case r ⇒ logger.error(s"API call failed with cause=$r")
        complete(StatusCodes.BadRequest, JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, r.toString)), "-1"))
    }
    .result()

  val route: Route =
    respondWithDefaultHeaders(customHeaders) {
      handleExceptions(myExceptionHandler) {
        handleRejections(myRejectionHandler) {
          pathSingleSlash {
            post {
              entity(as[JsonRPCBody]) {
                req =>
                  val kit = appKit
                  import kit._

                  req.method match {
                    // utility methods
                    case "getinfo"      => completeRpcFuture(req.id, getInfoResponse)
                    case "help"         => completeRpc(req.id, help)

                    // channel lifecycle methods
                    case "connect"      => req.params match {
                      case JString(pubkey) :: JString(host) :: JInt(port) :: Nil =>
                        completeRpcFuture(req.id, (switchboard ? Peer.Connect(NodeURI.parse(s"$pubkey@$host:$port"))).mapTo[String])
                      case JString(uri) :: Nil =>
                        completeRpcFuture(req.id, (switchboard ? Peer.Connect(NodeURI.parse(uri))).mapTo[String])
                      case _ => reject(UnknownParamsRejection(req.id, "[nodeId@host:port] or [nodeId, host, port]"))
                    }
                    case "open"         => req.params match {
                      case JString(nodeId) :: JInt(fundingSatoshi) :: JInt(pushMsat) :: JInt(flags) :: Nil =>
                        completeRpcFuture(req.id, (switchboard ? Peer.OpenChannel(PublicKey(nodeId), Satoshi(fundingSatoshi.toLong), MilliSatoshi(pushMsat.toLong), channelFlags = Some(flags.toByte))).mapTo[String])
                      case JString(nodeId) :: JInt(fundingSatoshi) :: JInt(pushMsat) :: Nil =>
                        completeRpcFuture(req.id, (switchboard ? Peer.OpenChannel(PublicKey(nodeId), Satoshi(fundingSatoshi.toLong), MilliSatoshi(pushMsat.toLong), channelFlags = None)).mapTo[String])
                      case _ => reject(UnknownParamsRejection(req.id, "[nodeId, fundingSatoshi, pushMsat] or [nodeId, fundingSatoshi, pushMsat, newChannel]"))
                    }
                    case "close"        => req.params match {
                      case JString(identifier) :: Nil => completeRpc(req.id, sendToChannel(identifier, CMD_CLOSE(scriptPubKey = None)).mapTo[String])
                      case JString(identifier) :: JString(scriptPubKey) :: Nil => completeRpc(req.id, sendToChannel(identifier, CMD_CLOSE(scriptPubKey = Some(scriptPubKey))).mapTo[String])
                      case _ => reject(UnknownParamsRejection(req.id, "[channelId] or [channelId, scriptPubKey]"))
                    }

                    // local network methods
                    case "peers"        => completeRpcFuture(req.id, for {
                      peers <- (switchboard ? 'peers).mapTo[Map[PublicKey, ActorRef]]
                      peerinfos <- Future.sequence(peers.values.map(peer => (peer ? GetPeerInfo).mapTo[PeerInfo]))
                    } yield peerinfos)
                    case "channels"     => req.params match {
                      case Nil => completeRpcFuture(req.id, (register ? 'channels).mapTo[Map[Long, ActorRef]].map(_.keys))
                      case JString(remoteNodeId) :: Nil => Try(PublicKey(remoteNodeId)) match {
                          case Success(pk) => completeRpcFuture(req.id, (register ? 'channelsTo).mapTo[Map[BinaryData, PublicKey]].map(_.filter(_._2 == pk).keys))
                          case Failure(f) => reject(ValidationRejection(req.id, s"invalid remote node id '$remoteNodeId'"))
                        }
                      case _ => reject(UnknownParamsRejection(req.id, "no arguments or [remoteNodeId]"))
                    }
                    case "channel"      => req.params match {
                      case JString(identifier) :: Nil => completeRpcFuture(req.id, sendToChannel(identifier, CMD_GETINFO).mapTo[RES_GETINFO])
                      case _ => reject(UnknownParamsRejection(req.id, "[channelId]"))
                    }

                    // global network methods
                    case "allnodes"     => completeRpcFuture(req.id, (router ? 'nodes).mapTo[Iterable[NodeAnnouncement]].map(_.map(_.nodeId)))
                    case "allchannels"  => completeRpcFuture(req.id, (router ? 'channels).mapTo[Iterable[ChannelAnnouncement]].map(_.map(c => ChannelInfo(c.shortChannelId.toHexString, c.nodeId1, c.nodeId2))))

                    // payment methods
                    case "receive"      => req.params match {
                      // only the payment description is given: user may want to generate a donation payment request
                      case JString(description) :: Nil =>
                        completeRpcFuture(req.id, (paymentHandler ? ReceivePayment(None, description)).mapTo[PaymentRequest].map(PaymentRequest.write))
                      // the amount is now given with the description
                      case JInt(amountMsat) :: JString(description) :: Nil =>
                        completeRpcFuture(req.id, (paymentHandler ? ReceivePayment(Some(MilliSatoshi(amountMsat.toLong)), description)).mapTo[PaymentRequest].map(PaymentRequest.write))
                      case _ => reject(UnknownParamsRejection(req.id, "[description] or [amount, description]"))
                    }
                    case "send"         => req.params match {
                      // user manually sets the payment information
                      case JInt(amountMsat) :: JString(paymentHash) :: JString(nodeId) :: Nil =>
                        (Try(BinaryData(paymentHash)), Try(PublicKey(nodeId))) match {
                          case (Success(ph), Success(pk)) => completeRpcFuture(req.id, (paymentInitiator ? SendPayment(amountMsat.toLong, ph, pk)).mapTo[PaymentResult])
                          case (Failure(_), _) => reject(ValidationRejection(req.id, s"invalid payment hash '$paymentHash'"))
                          case _ => reject(ValidationRejection(req.id, s"invalid node id '$nodeId'"))
                        }
                      // user gives a Lightning payment request
                      case JString(paymentRequest) :: rest => Try(PaymentRequest.read(paymentRequest)) match {
                        case Success(pr) =>
                          // setting the payment amount
                          val amount_msat: Long = (pr.amount, rest) match {
                            // optional amount always overrides the amount in the payment request
                            case (_, JInt(amount_msat_override) :: Nil) => amount_msat_override.toLong
                            case (Some(amount_msat_pr), _) => amount_msat_pr.amount
                            case _ => throw new RuntimeException("you must manually specify an amount for this payment request")
                          }
                          logger.debug(s"api call for sending payment with amount_msat=$amount_msat")
                          // optional cltv expiry
                          val sendPayment = pr.minFinalCltvExpiry match {
                            case None => SendPayment(amount_msat, pr.paymentHash, pr.nodeId)
                            case Some(minFinalCltvExpiry) => SendPayment(amount_msat, pr.paymentHash, pr.nodeId, assistedRoutes = Nil, minFinalCltvExpiry)
                          }
                          completeRpcFuture(req.id, (paymentInitiator ? sendPayment).mapTo[PaymentResult])
                        case _ => reject(ValidationRejection(req.id, s"payment request is not valid"))
                      }
                      case _ => reject(UnknownParamsRejection(req.id, "[amountMsat, paymentHash, nodeId or [paymentRequest] or [paymentRequest, amountMsat]"))
                    }

                    // check received payments
                    case "checkpayment" => req.params match {
                      case JString(identifier) :: Nil => completeRpcFuture(req.id, for {
                        paymentHash <- Try(PaymentRequest.read(identifier)) match {
                          case Success(pr) => Future.successful(pr.paymentHash)
                          case _ => Try(BinaryData(identifier)) match {
                            case Success(s) => Future.successful(s)
                            case _ => Future.failed(new IllegalArgumentException("payment identifier must be a payment request or a payment hash"))
                          }
                        }
                        found <- (paymentHandler ? CheckPayment(paymentHash)).map(found => new JBool(found.asInstanceOf[Boolean]))
                      } yield found)
                      case _ => reject(UnknownParamsRejection(req.id, "[paymentHash] or [paymentRequest]"))
                    }

                    // method name was not found
                    case _ => reject(UnknownMethodRejection(req.id))
                  }
              }
            }
          }
        }
      }
    }

  def getInfoResponse: Future[GetInfoResponse]

  def help = List(
    "connect (uri): open a secure connection to a lightning node",
    "connect (nodeId, host, port): open a secure connection to a lightning node",
    "open (nodeId, fundingSatoshi, pushMsat): open a channel with another lightning node",
    "open (nodeId, fundingSatoshi, pushMsat, channelFlags): open a channel with another lightning node",
    "peers: list existing local peers",
    "channels: list existing local channels",
    "channels (nodeId): list existing local channels to a particular nodeId",
    "channel (channelId): retrieve detailed information about a given channel",
    "allnodes: list all known nodes",
    "allchannels: list all known channels",
    "receive (amountMsat, description): generate a payment request for a given amount",
    "send (amountMsat, paymentHash, nodeId): send a payment to a lightning node",
    "send (paymentRequest): send a payment to a lightning node using a BOLT11 payment request",
    "send (paymentRequest, amountMsat): send a payment to a lightning node using a BOLT11 payment request and a custom amount",
    "close (channelId): close a channel",
    "close (channelId, scriptPubKey): close a channel and send the funds to the given scriptPubKey",
    "checkpayment (paymentHash): returns true if the payment has been received, false otherwise",
    "checkpayment (paymentRequest): returns true if the payment has been received, false otherwise",
    "help: display this message")

  /**
    * Sends a request to a channel and expects a response
    *
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
}
