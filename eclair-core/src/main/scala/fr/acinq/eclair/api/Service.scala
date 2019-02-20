/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.api

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props, Scheduler}
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.Credentials
import akka.http.scaladsl.server.directives.RouteDirectives.reject
import akka.pattern.ask
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Source}
import akka.util.Timeout
import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi, Satoshi}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Peer.{GetPeerInfo, PeerInfo}
import fr.acinq.eclair.io.{NodeURI, Peer}
import fr.acinq.eclair.payment.PaymentLifecycle._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.{ChannelDesc, RouteRequest, RouteResponse, Router}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAddress, NodeAnnouncement}
import fr.acinq.eclair.{Kit, ShortChannelId, feerateByte2Kw}
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
case class GetInfoResponse(nodeId: PublicKey, alias: String, port: Int, chainHash: BinaryData, blockHeight: Int, publicAddresses: Seq[NodeAddress])
case class AuditResponse(sent: Seq[PaymentSent], received: Seq[PaymentReceived], relayed: Seq[PaymentRelayed])
trait RPCRejection extends Rejection {
  def requestId: String
}
final case class UnknownMethodRejection(requestId: String) extends RPCRejection
final case class UnknownParamsRejection(requestId: String, message: String) extends RPCRejection
final case class RpcValidationRejection(requestId: String, message: String) extends RPCRejection
final case class ExceptionRejection(requestId: String, message: String) extends RPCRejection
// @formatter:on

trait Service extends Logging {

  implicit def ec: ExecutionContext = ExecutionContext.Implicits.global

  def scheduler: Scheduler

  implicit val serialization = jackson.Serialization
  implicit val formats = org.json4s.DefaultFormats + new BinaryDataSerializer + new UInt64Serializer + new MilliSatoshiSerializer + new ShortChannelIdSerializer + new StateSerializer + new ShaChainSerializer + new PublicKeySerializer + new PrivateKeySerializer + new ScalarSerializer + new PointSerializer + new TransactionSerializer + new TransactionWithInputInfoSerializer + new InetSocketAddressSerializer + new OutPointSerializer + new OutPointKeySerializer + new InputInfoSerializer + new ColorSerializer +  new RouteResponseSerializer + new ThrowableSerializer + new FailureMessageSerializer + new NodeAddressSerializer + new DirectionSerializer +new PaymentRequestSerializer
  implicit val timeout = Timeout(60 seconds)
  implicit val shouldWritePretty: ShouldWritePretty = ShouldWritePretty.True

  import Json4sSupport.{marshaller, unmarshaller}

  def password: String

  def appKit: Kit

  val socketHandler: Flow[Message, TextMessage.Strict, NotUsed]

  def userPassAuthenticator(credentials: Credentials): Future[Option[String]] = credentials match {
    case p@Credentials.Provided(id) if p.verify(password) => Future.successful(Some(id))
    case _ => akka.pattern.after(1 second, using = scheduler)(Future.successful(None)) // force a 1 sec pause to deter brute force
  }

  val customHeaders = `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
    `Access-Control-Allow-Methods`(POST) ::
    `Cache-Control`(public, `no-store`, `max-age`(0)) :: Nil

  val myExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      extractRequest { _ =>
        logger.error(s"API call failed with cause=${t.getMessage}")
        complete(StatusCodes.InternalServerError, JsonRPCRes(null, Some(Error(StatusCodes.InternalServerError.intValue, t.getMessage)), "-1"))
      }
  }

  def completeRpcFuture(requestId: String, future: Future[AnyRef]): Route = onComplete(future) {
    case Success(s) => completeRpc(requestId, s)
    case Failure(t) => reject(ExceptionRejection(requestId, t.getLocalizedMessage))
  }

  def completeRpc(requestId: String, result: AnyRef): Route = complete(JsonRPCRes(result, None, requestId))

  val myRejectionHandler: RejectionHandler = RejectionHandler.newBuilder()
    .handleNotFound {
      complete(StatusCodes.NotFound, JsonRPCRes(null, Some(Error(StatusCodes.NotFound.intValue, "not found")), "-1"))
    }
    .handle {
      case _: AuthenticationFailedRejection ⇒ complete(StatusCodes.Unauthorized, JsonRPCRes(null, Some(Error(StatusCodes.Unauthorized.intValue, "Access restricted")), "-1"))
      case v: RpcValidationRejection ⇒ complete(StatusCodes.BadRequest, JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, v.message)), v.requestId))
      case ukm: UnknownMethodRejection ⇒ complete(StatusCodes.BadRequest, JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, "method not found")), ukm.requestId))
      case p: UnknownParamsRejection ⇒ complete(StatusCodes.BadRequest,
        JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, s"invalid parameters for this method, should be: ${p.message}")), p.requestId))
      case m: MalformedRequestContentRejection ⇒ complete(StatusCodes.BadRequest,
        JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, s"malformed parameters for this method: ${m.message}")), "-1"))
      case e: ExceptionRejection ⇒ complete(StatusCodes.BadRequest,
        JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, s"command failed: ${e.message}")), e.requestId))
      case r ⇒ logger.error(s"API call failed with cause=$r")
        complete(StatusCodes.BadRequest, JsonRPCRes(null, Some(Error(StatusCodes.BadRequest.intValue, r.toString)), "-1"))
    }
    .result()

  val route: Route =
    respondWithDefaultHeaders(customHeaders) {
      withRequestTimeoutResponse(r => HttpResponse(StatusCodes.RequestTimeout).withEntity(ContentTypes.`application/json`, """{ "result": null, "error": { "code": 408, "message": "request timed out"} } """)) {
        handleExceptions(myExceptionHandler) {
          handleRejections(myRejectionHandler) {
            authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator) { _ =>
              pathSingleSlash {
                post {
                  entity(as[JsonRPCBody]) {
                    req =>
                      val kit = appKit
                      import kit._

                      req.method match {
                        // utility methods
                        case "getinfo" => completeRpcFuture(req.id, getInfoResponse)
                        case "help" => completeRpc(req.id, help)

                        // channel lifecycle methods
                        case "connect" => req.params match {
                          case JString(pubkey) :: JString(host) :: JInt(port) :: Nil =>
                            completeRpcFuture(req.id, (switchboard ? Peer.Connect(NodeURI.parse(s"$pubkey@$host:$port"))).mapTo[String])
                          case JString(uri) :: Nil =>
                            completeRpcFuture(req.id, (switchboard ? Peer.Connect(NodeURI.parse(uri))).mapTo[String])
                          case _ => reject(UnknownParamsRejection(req.id, "[nodeId@host:port] or [nodeId, host, port]"))
                        }
                        case "open" => req.params match {
                          case JString(nodeId) :: JInt(fundingSatoshis) :: Nil =>
                            completeRpcFuture(req.id, (switchboard ? Peer.OpenChannel(PublicKey(nodeId), Satoshi(fundingSatoshis.toLong), MilliSatoshi(0), fundingTxFeeratePerKw_opt = None, channelFlags = None)).mapTo[String])
                          case JString(nodeId) :: JInt(fundingSatoshis) :: JInt(pushMsat) :: Nil =>
                            completeRpcFuture(req.id, (switchboard ? Peer.OpenChannel(PublicKey(nodeId), Satoshi(fundingSatoshis.toLong), MilliSatoshi(pushMsat.toLong), channelFlags = None, fundingTxFeeratePerKw_opt = None)).mapTo[String])
                          case JString(nodeId) :: JInt(fundingSatoshis) :: JInt(pushMsat) :: JInt(fundingFeerateSatPerByte) :: Nil =>
                            completeRpcFuture(req.id, (switchboard ? Peer.OpenChannel(PublicKey(nodeId), Satoshi(fundingSatoshis.toLong), MilliSatoshi(pushMsat.toLong), fundingTxFeeratePerKw_opt = Some(feerateByte2Kw(fundingFeerateSatPerByte.toLong)), channelFlags = None)).mapTo[String])
                          case JString(nodeId) :: JInt(fundingSatoshis) :: JInt(pushMsat) :: JInt(fundingFeerateSatPerByte) :: JInt(flags) :: Nil =>
                            completeRpcFuture(req.id, (switchboard ? Peer.OpenChannel(PublicKey(nodeId), Satoshi(fundingSatoshis.toLong), MilliSatoshi(pushMsat.toLong), fundingTxFeeratePerKw_opt = Some(feerateByte2Kw(fundingFeerateSatPerByte.toLong)), channelFlags = Some(flags.toByte))).mapTo[String])
                          case _ => reject(UnknownParamsRejection(req.id, s"[nodeId, fundingSatoshis], [nodeId, fundingSatoshis, pushMsat], [nodeId, fundingSatoshis, pushMsat, feerateSatPerByte] or [nodeId, fundingSatoshis, pushMsat, feerateSatPerByte, flag]"))
                        }
                        case "close" => req.params match {
                          case JString(identifier) :: Nil => completeRpcFuture(req.id, sendToChannel(identifier, CMD_CLOSE(scriptPubKey = None)).mapTo[String])
                          case JString(identifier) :: JString(scriptPubKey) :: Nil => completeRpcFuture(req.id, sendToChannel(identifier, CMD_CLOSE(scriptPubKey = Some(scriptPubKey))).mapTo[String])
                          case _ => reject(UnknownParamsRejection(req.id, "[channelId] or [channelId, scriptPubKey]"))
                        }
                        case "forceclose" => req.params match {
                          case JString(identifier) :: Nil => completeRpcFuture(req.id, sendToChannel(identifier, CMD_FORCECLOSE).mapTo[String])
                          case _ => reject(UnknownParamsRejection(req.id, "[channelId]"))
                        }
                        case "updaterelayfee" => req.params match {
                          case JString(identifier) :: JInt(feeBaseMsat) :: JInt(feeProportionalMillionths) :: Nil =>
                            completeRpcFuture(req.id, sendToChannel(identifier, CMD_UPDATE_RELAY_FEE(feeBaseMsat.toLong, feeProportionalMillionths.toLong)).mapTo[String])
                          case JString(identifier) :: JString(feeBaseMsat) :: JString(feeProportionalMillionths) :: Nil =>
                            completeRpcFuture(req.id, sendToChannel(identifier, CMD_UPDATE_RELAY_FEE(feeBaseMsat.toLong, feeProportionalMillionths.toLong)).mapTo[String])
                          case _ => reject(UnknownParamsRejection(req.id, "[channelId] [feeBaseMsat] [feeProportionalMillionths]"))
                        }
                        // local network methods
                        case "peers" => completeRpcFuture(req.id, for {
                          peers <- (switchboard ? 'peers).mapTo[Iterable[ActorRef]]
                          peerinfos <- Future.sequence(peers.map(peer => (peer ? GetPeerInfo).mapTo[PeerInfo]))
                        } yield peerinfos)
                        case "channels" => req.params match {
                          case Nil =>
                            val f = for {
                              channels_id <- (register ? 'channels).mapTo[Map[BinaryData, ActorRef]].map(_.keys)
                              channels <- Future.sequence(channels_id.map(channel_id => sendToChannel(channel_id.toString(), CMD_GETINFO).mapTo[RES_GETINFO]))
                            } yield channels
                            completeRpcFuture(req.id, f)
                          case JString(remoteNodeId) :: Nil => Try(PublicKey(remoteNodeId)) match {
                            case Success(pk) =>
                              val f = for {
                                channels_id <- (register ? 'channelsTo).mapTo[Map[BinaryData, PublicKey]].map(_.filter(_._2 == pk).keys)
                                channels <- Future.sequence(channels_id.map(channel_id => sendToChannel(channel_id.toString(), CMD_GETINFO).mapTo[RES_GETINFO]))
                              } yield channels
                              completeRpcFuture(req.id, f)
                            case Failure(_) => reject(RpcValidationRejection(req.id, s"invalid remote node id '$remoteNodeId'"))
                          }
                          case _ => reject(UnknownParamsRejection(req.id, "no arguments or [remoteNodeId]"))
                        }
                        case "channel" => req.params match {
                          case JString(identifier) :: Nil => completeRpcFuture(req.id, sendToChannel(identifier, CMD_GETINFO).mapTo[RES_GETINFO])
                          case _ => reject(UnknownParamsRejection(req.id, "[channelId]"))
                        }

                        // global network methods
                        case "allnodes" => completeRpcFuture(req.id, (router ? 'nodes).mapTo[Iterable[NodeAnnouncement]])
                        case "allchannels" => completeRpcFuture(req.id, (router ? 'channels).mapTo[Iterable[ChannelAnnouncement]].map(_.map(c => ChannelDesc(c.shortChannelId, c.nodeId1, c.nodeId2))))
                        case "allupdates" => req.params match {
                          case JString(nodeId) :: Nil => Try(PublicKey(nodeId)) match {
                            case Success(pk) => completeRpcFuture(req.id, (router ? 'updatesMap).mapTo[Map[ChannelDesc, ChannelUpdate]].map(_.filter(e => e._1.a == pk || e._1.b == pk).values))
                            case Failure(_) => reject(RpcValidationRejection(req.id, s"invalid remote node id '$nodeId'"))
                          }
                          case _ => completeRpcFuture(req.id, (router ? 'updates).mapTo[Iterable[ChannelUpdate]])
                        }

                      // payment methods
                      case "receive" => req.params match {
                        // only the payment description is given: user may want to generate a donation payment request
                        case JString(description) :: Nil =>
                          completeRpcFuture(req.id, (paymentHandler ? ReceivePayment(None, description)).mapTo[PaymentRequest].map(PaymentRequest.write))
                        // the amount is now given with the description
                        case JInt(amountMsat) :: JString(description) :: Nil =>
                          completeRpcFuture(req.id, (paymentHandler ? ReceivePayment(Some(MilliSatoshi(amountMsat.toLong)), description)).mapTo[PaymentRequest].map(PaymentRequest.write))
                        case JInt(amountMsat) :: JString(description) :: JInt(expirySeconds) :: Nil =>
                          completeRpcFuture(req.id, (paymentHandler ? ReceivePayment(Some(MilliSatoshi(amountMsat.toLong)), description, Some(expirySeconds.toLong))).mapTo[PaymentRequest].map(PaymentRequest.write))
                        case _ => reject(UnknownParamsRejection(req.id, "[description] or [amount, description] or [amount, description, expiryDuration]"))
                      }

                      // checkinvoice deprecated.
                      case "parseinvoice" | "checkinvoice" => req.params match {
                        case JString(paymentRequest) :: Nil => Try(PaymentRequest.read(paymentRequest)) match {
                          case Success(pr) => completeRpc(req.id,pr)
                          case Failure(t) => reject(RpcValidationRejection(req.id, s"invalid payment request ${t.getMessage}"))
                        }
                        case _ => reject(UnknownParamsRejection(req.id, "[payment_request]"))
                      }

                      case "findroute" => req.params match {
                        case JString(nodeId) :: JInt(amountMsat) :: Nil if nodeId.length() == 66 => Try(PublicKey(nodeId)) match {
                          case Success(pk) => completeRpcFuture(req.id, (router ? RouteRequest(appKit.nodeParams.nodeId, pk, amountMsat.toLong)).mapTo[RouteResponse])
                          case Failure(_) => reject(RpcValidationRejection(req.id, s"invalid nodeId hash '$nodeId'"))
                        }
                        case JString(paymentRequest) :: Nil => Try(PaymentRequest.read(paymentRequest)) match {
                          case Success(PaymentRequest(_, Some(amountMsat), _, nodeId , _, _)) => completeRpcFuture(req.id, (router ? RouteRequest(appKit.nodeParams.nodeId, nodeId, amountMsat.toLong)).mapTo[RouteResponse])
                          case Success(_) => reject(RpcValidationRejection(req.id, s"payment request is missing amount, please specify it"))
                          case Failure(t) => reject(RpcValidationRejection(req.id, s"invalid payment request ${t.getLocalizedMessage}"))
                        }
                        case JString(paymentRequest) :: JInt(amountMsat) :: Nil => Try(PaymentRequest.read(paymentRequest)) match {
                          case Success(PaymentRequest(_, None, _, nodeId , _, _)) => completeRpcFuture(req.id, (router ? RouteRequest(appKit.nodeParams.nodeId, nodeId, amountMsat.toLong)).mapTo[RouteResponse])
                          case Success(_) => reject(RpcValidationRejection(req.id, s"amount was specified both in payment request and api call"))
                          case Failure(t) => reject(RpcValidationRejection(req.id, s"invalid payment request ${t.getLocalizedMessage}"))
                        }
                        case _ => reject(UnknownParamsRejection(req.id, "[payment_request] or [payment_request, amountMsat] or [nodeId, amountMsat]"))
                      }

                      case "send" => req.params match {
                        // user manually sets the payment information
                        case JInt(amountMsat) :: JString(paymentHash) :: JString(nodeId) :: Nil =>
                          (Try(BinaryData(paymentHash)), Try(PublicKey(nodeId))) match {
                            case (Success(ph), Success(pk)) => completeRpcFuture(req.id, (paymentInitiator ?
                              SendPayment(amountMsat.toLong, ph, pk)).mapTo[PaymentResult].map {
                              case s: PaymentSucceeded => s
                              case f: PaymentFailed => f.copy(failures = PaymentLifecycle.transformForUser(f.failures))
                            })
                            case (Failure(_), _) => reject(RpcValidationRejection(req.id, s"invalid payment hash '$paymentHash'"))
                            case _ => reject(RpcValidationRejection(req.id, s"invalid node id '$nodeId'"))
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
                            completeRpcFuture(req.id, (paymentInitiator ? sendPayment).mapTo[PaymentResult].map {
                              case s: PaymentSucceeded => s
                              case f: PaymentFailed => f.copy(failures = PaymentLifecycle.transformForUser(f.failures))
                            })
                          case _ => reject(RpcValidationRejection(req.id, s"payment request is not valid"))
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

                        // retrieve audit events
                        case "audit" =>
                          val (from, to) = req.params match {
                            case JInt(from) :: JInt(to) :: Nil => (from.toLong, to.toLong)
                            case _ => (0L, Long.MaxValue)
                          }
                          completeRpcFuture(req.id, Future(AuditResponse(
                            sent = nodeParams.auditDb.listSent(from, to),
                            received = nodeParams.auditDb.listReceived(from, to),
                            relayed = nodeParams.auditDb.listRelayed(from, to))
                          ))

                        case "networkfees" =>
                          val (from, to) = req.params match {
                            case JInt(from) :: JInt(to) :: Nil => (from.toLong, to.toLong)
                            case _ => (0L, Long.MaxValue)
                          }
                          completeRpcFuture(req.id, Future(nodeParams.auditDb.listNetworkFees(from, to)))

                        // retrieve fee stats
                        case "channelstats" => completeRpcFuture(req.id, Future(nodeParams.auditDb.stats))


                        // method name was not found
                        case _ => reject(UnknownMethodRejection(req.id))
                      }
                  }
                }
              }
            } ~ path("ws") {
              handleWebSocketMessages(socketHandler)
            }
          }
        }
      }
    }

  def getInfoResponse: Future[GetInfoResponse]

  def makeSocketHandler(system: ActorSystem)(implicit materializer: ActorMaterializer): Flow[Message, TextMessage.Strict, NotUsed] = {

    // create a flow transforming a queue of string -> string
    val (flowInput, flowOutput) = Source.queue[String](10, OverflowStrategy.dropTail).toMat(BroadcastHub.sink[String])(Keep.both).run()

    // register an actor that feeds the queue when a payment is received
    system.actorOf(Props(new Actor {
      override def preStart: Unit = context.system.eventStream.subscribe(self, classOf[PaymentReceived])
      def receive: Receive = { case received: PaymentReceived => flowInput.offer(received.paymentHash.toString) }
    }))

    Flow[Message]
      .mapConcat(_ => Nil) // Ignore heartbeats and other data from the client
      .merge(flowOutput) // Stream the data we want to the client
      .map(TextMessage.apply)
  }

  def help = List(
    "connect (uri): open a secure connection to a lightning node",
    "connect (nodeId, host, port): open a secure connection to a lightning node",
    "open (nodeId, fundingSatoshis, pushMsat = 0, feerateSatPerByte = ?, channelFlags = 0x01): open a channel with another lightning node, by default push = 0, feerate for the funding tx targets 6 blocks, and channel is announced",
    "updaterelayfee (channelId, feeBaseMsat, feeProportionalMillionths): update relay fee for payments going through this channel",
    "peers: list existing local peers",
    "channels: list existing local channels",
    "channels (nodeId): list existing local channels to a particular nodeId",
    "channel (channelId): retrieve detailed information about a given channel",
    "channelstats: retrieves statistics about channel usage (fees, number and average amount of payments)",
    "allnodes: list all known nodes",
    "allchannels: list all known channels",
    "allupdates: list all channels updates",
    "allupdates (nodeId): list all channels updates for this nodeId",
    "receive (amountMsat, description): generate a payment request for a given amount",
    "receive (amountMsat, description, expirySeconds): generate a payment request for a given amount with a description and a number of seconds till it expires",
    "parseinvoice (paymentRequest): returns node, amount and payment hash in a payment request",
    "findroute (paymentRequest): returns nodes and channels of the route if there is any",
    "findroute (paymentRequest, amountMsat): returns nodes and channels of the route if there is any",
    "findroute (nodeId, amountMsat): returns nodes and channels of the route if there is any",
    "send (amountMsat, paymentHash, nodeId): send a payment to a lightning node",
    "send (paymentRequest): send a payment to a lightning node using a BOLT11 payment request",
    "send (paymentRequest, amountMsat): send a payment to a lightning node using a BOLT11 payment request and a custom amount",
    "close (channelId): close a channel",
    "close (channelId, scriptPubKey): close a channel and send the funds to the given scriptPubKey",
    "forceclose (channelId): force-close a channel by publishing the local commitment tx (careful: this is more expensive than a regular close and will incur a delay before funds are spendable)",
    "checkpayment (paymentHash): returns true if the payment has been received, false otherwise",
    "checkpayment (paymentRequest): returns true if the payment has been received, false otherwise",
    "audit: list all send/received/relayed payments",
    "audit (from, to): list send/received/relayed payments in that interval (from <= timestamp < to)",
    "networkfees: list all network fees paid to the miners, by transaction",
    "networkfees (from, to): list network fees paid to the miners, by transaction, in that interval (from <= timestamp < to)",
    "getinfo: returns info about the blockchain and this node",
    "help: display this message")

  /**
    * Sends a request to a channel and expects a response
    *
    * @param channelIdentifier can be a shortChannelId (BOLT encoded) or a channelId (32-byte hex encoded)
    * @param request
    * @return
    */
  def sendToChannel(channelIdentifier: String, request: Any): Future[Any] =
    for {
      fwdReq <- Future(Register.ForwardShortId(ShortChannelId(channelIdentifier), request))
        .recoverWith { case _ => Future(Register.Forward(BinaryData(channelIdentifier), request)) }
        .recoverWith { case _ => Future.failed(new RuntimeException(s"invalid channel identifier '$channelIdentifier'")) }
      res <- appKit.register ? fwdReq
    } yield res
}
