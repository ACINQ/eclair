/*
 * Copyright 2019 ACINQ SAS
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

import java.util.UUID

import akka.NotUsed
import akka.actor.{Actor, ActorSystem, Props}
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers.{`Access-Control-Allow-Headers`, `Access-Control-Allow-Methods`, `Cache-Control`}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.Credentials
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Source}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.Timeout
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.api.FormParamExtractors._
import fr.acinq.eclair.blockchain.fee.FeeratePerByte
import fr.acinq.eclair.channel.{ChannelClosed, ChannelCreated, ChannelEvent, ChannelStateChanged, WAIT_FOR_INIT_INTERNAL}
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.payment.{PaymentEvent, PaymentRequest}
import fr.acinq.eclair.router.Router.{PredefinedChannelRoute, PredefinedNodeRoute}
import fr.acinq.eclair.{CltvExpiryDelta, Eclair, MilliSatoshi}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import scala.concurrent.Future
import scala.concurrent.duration._

case class ErrorResponse(error: String)

trait Service extends ExtraDirectives with Logging {

  // important! Must NOT import the unmarshaller as it is too generic...see https://github.com/akka/akka-http/issues/541

  import JsonSupport.{formats, marshaller, serialization}

  def password: String

  val eclairApi: Eclair

  implicit val actorSystem: ActorSystem
  implicit val mat: Materializer

  // timeout for reading request parameters from the underlying stream
  val paramParsingTimeout = 5 seconds

  val apiExceptionHandler = ExceptionHandler {
    case t: IllegalArgumentException =>
      logger.error(s"API call failed with cause=${t.getMessage}", t)
      complete(StatusCodes.BadRequest, ErrorResponse(t.getMessage))
    case t: Throwable =>
      logger.error(s"API call failed with cause=${t.getMessage}", t)
      complete(StatusCodes.InternalServerError, ErrorResponse(t.getMessage))
  }

  // map all the rejections to a JSON error object ErrorResponse
  val apiRejectionHandler = RejectionHandler.default.mapRejectionResponse {
    case res@HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
      res.copy(entity = HttpEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse(ent.data.utf8String))))
  }

  val customHeaders = `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
    `Access-Control-Allow-Methods`(POST) ::
    `Cache-Control`(public, `no-store`, `max-age`(0)) :: Nil

  lazy val makeSocketHandler: Flow[Message, TextMessage.Strict, NotUsed] = {

    // create a flow transforming a queue of string -> string
    val (flowInput, flowOutput) = Source.queue[String](10, OverflowStrategy.dropTail).toMat(BroadcastHub.sink[String])(Keep.both).run()

    // register an actor that feeds the queue on payment related events
    actorSystem.actorOf(Props(new Actor {

      override def preStart: Unit = {
        context.system.eventStream.subscribe(self, classOf[PaymentEvent])
        context.system.eventStream.subscribe(self, classOf[ChannelCreated])
        context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])
        context.system.eventStream.subscribe(self, classOf[ChannelClosed])
      }

      def receive: Receive = {
        case message: PaymentEvent => flowInput.offer(serialization.write(message))
        case message: ChannelCreated => flowInput.offer(serialization.write(message))
        case message: ChannelStateChanged =>
          if (message.previousState != WAIT_FOR_INIT_INTERNAL) {
            flowInput.offer(serialization.write(message))
          }
        case message: ChannelClosed => flowInput.offer(serialization.write(message))
      }

    }))

    Flow[Message]
      .mapConcat(_ => Nil) // Ignore heartbeats and other data from the client
      .merge(flowOutput) // Stream the data we want to the client
      .map(TextMessage.apply)
  }

  val timeoutResponse: HttpRequest => HttpResponse = { _ =>
    HttpResponse(StatusCodes.RequestTimeout).withEntity(ContentTypes.`application/json`, serialization.writePretty(ErrorResponse("request timed out")))
  }

  def userPassAuthenticator(credentials: Credentials): Future[Option[String]] = credentials match {
    case p@Credentials.Provided(id) if p.verify(password) => Future.successful(Some(id))
    case _ => akka.pattern.after(1 second, using = actorSystem.scheduler)(Future.successful(None))(actorSystem.dispatcher) // force a 1 sec pause to deter brute force
  }

  val route: Route = {
    respondWithDefaultHeaders(customHeaders) {
      handleExceptions(apiExceptionHandler) {
        handleRejections(apiRejectionHandler) {
          // forcing the request entity to be fully parsed can have performance issues, see: https://doc.akka.io/docs/akka-http/current/routing-dsl/directives/basic-directives/toStrictEntity.html#description
          toStrictEntity(paramParsingTimeout) {
            formFields("timeoutSeconds".as[Timeout].?) { tm_opt =>
              // this is the akka timeout
              implicit val timeout: Timeout = tm_opt.getOrElse(Timeout(30 seconds))
              // we ensure that http timeout is greater than akka timeout
              withRequestTimeout(timeout.duration + 2.seconds) {
                withRequestTimeoutResponse(timeoutResponse) {
                  authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator) { _ =>
                    post {
                      path("getinfo") {
                        complete(eclairApi.getInfo())
                      } ~
                        path("connect") {
                          formFields("uri".as[NodeURI]) { uri =>
                            complete(eclairApi.connect(Left(uri)))
                          } ~ formFields(nodeIdFormParam, "host".as[String], "port".as[Int].?) { (nodeId, host, port_opt) =>
                            complete(eclairApi.connect(Left(NodeURI(nodeId, HostAndPort.fromParts(host, port_opt.getOrElse(NodeURI.DEFAULT_PORT))))))
                          } ~ formFields(nodeIdFormParam) { nodeId =>
                            complete(eclairApi.connect(Right(nodeId)))
                          }
                        } ~
                        path("disconnect") {
                          formFields(nodeIdFormParam) { nodeId =>
                            complete(eclairApi.disconnect(nodeId))
                          }
                        } ~
                        path("open") {
                          formFields(nodeIdFormParam, "fundingSatoshis".as[Satoshi], "pushMsat".as[MilliSatoshi].?, "fundingFeerateSatByte".as[FeeratePerByte].?, "channelFlags".as[Int].?, "openTimeoutSeconds".as[Timeout].?) {
                            (nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags, openTimeout_opt) =>
                              complete(eclairApi.open(nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags, openTimeout_opt))
                          }
                        } ~
                        path("updaterelayfee") {
                          withChannelsIdentifier { channels =>
                            formFields("feeBaseMsat".as[MilliSatoshi], "feeProportionalMillionths".as[Long]) { (feeBase, feeProportional) =>
                              complete(eclairApi.updateRelayFee(channels, feeBase, feeProportional))
                            }
                          }
                        } ~
                        path("close") {
                          withChannelsIdentifier { channels =>
                            formFields("scriptPubKey".as[ByteVector](binaryDataUnmarshaller).?) { scriptPubKey_opt =>
                              complete(eclairApi.close(channels, scriptPubKey_opt))
                            }
                          }
                        } ~
                        path("forceclose") {
                          withChannelsIdentifier { channels =>
                            complete(eclairApi.forceClose(channels))
                          }
                        } ~
                        path("peers") {
                          complete(eclairApi.peers())
                        } ~
                        path("nodes") {
                          formFields(nodeIdsFormParam.?) { nodeIds_opt =>
                            complete(eclairApi.nodes(nodeIds_opt.map(_.toSet)))
                          }
                        } ~
                        path("channels") {
                          formFields(nodeIdFormParam.?) { toRemoteNodeId_opt =>
                            complete(eclairApi.channelsInfo(toRemoteNodeId_opt))
                          }
                        } ~
                        path("channel") {
                          withChannelIdentifier { channel =>
                            complete(eclairApi.channelInfo(channel))
                          }
                        } ~
                        path("allchannels") {
                          complete(eclairApi.allChannels())
                        } ~
                        path("networkstats") {
                          complete(eclairApi.networkStats())
                        } ~
                        path("allupdates") {
                          formFields(nodeIdFormParam.?) { nodeId_opt =>
                            complete(eclairApi.allUpdates(nodeId_opt))
                          }
                        } ~
                        path("findroute") {
                          formFields(invoiceFormParam, amountMsatFormParam.?) {
                            case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None) => complete(eclairApi.findRoute(nodeId, amount, invoice.routingInfo))
                            case (invoice, Some(overrideAmount)) => complete(eclairApi.findRoute(invoice.nodeId, overrideAmount, invoice.routingInfo))
                            case _ => reject(MalformedFormFieldRejection("invoice", "The invoice must have an amount or you need to specify one using 'amountMsat'"))
                          }
                        } ~
                        path("findroutetonode") {
                          formFields(nodeIdFormParam, amountMsatFormParam) { (nodeId, amount) =>
                            complete(eclairApi.findRoute(nodeId, amount))
                          }
                        } ~
                        path("parseinvoice") {
                          formFields(invoiceFormParam) { invoice =>
                            complete(invoice)
                          }
                        } ~
                        path("payinvoice") {
                          formFields(invoiceFormParam, amountMsatFormParam.?, "maxAttempts".as[Int].?, "feeThresholdSat".as[Satoshi].?, "maxFeePct".as[Double].?, "externalId".?) {
                            case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None, maxAttempts, feeThresholdSat_opt, maxFeePct_opt, externalId_opt) =>
                              complete(eclairApi.send(externalId_opt, nodeId, amount, invoice.paymentHash, Some(invoice), maxAttempts, feeThresholdSat_opt, maxFeePct_opt))
                            case (invoice, Some(overrideAmount), maxAttempts, feeThresholdSat_opt, maxFeePct_opt, externalId_opt) =>
                              complete(eclairApi.send(externalId_opt, invoice.nodeId, overrideAmount, invoice.paymentHash, Some(invoice), maxAttempts, feeThresholdSat_opt, maxFeePct_opt))
                            case _ => reject(MalformedFormFieldRejection("invoice", "The invoice must have an amount or you need to specify one using the field 'amountMsat'"))
                          }
                        } ~
                        path("sendtonode") {
                          formFields(amountMsatFormParam, nodeIdFormParam, paymentHashFormParam.?, "maxAttempts".as[Int].?, "feeThresholdSat".as[Satoshi].?, "maxFeePct".as[Double].?, "externalId".?, "keysend".as[Boolean].?) {
                            case (amountMsat, nodeId, Some(paymentHash), maxAttempts_opt, feeThresholdSat_opt, maxFeePct_opt, externalId_opt, keySend) =>
                              keySend match {
                                case Some(true) => reject(MalformedFormFieldRejection("paymentHash", "You cannot request a KeySend payment and specify a paymentHash"))
                                case _ => complete(eclairApi.send(externalId_opt, nodeId, amountMsat, paymentHash, maxAttempts_opt = maxAttempts_opt, feeThresholdSat_opt = feeThresholdSat_opt, maxFeePct_opt = maxFeePct_opt))
                              }
                            case (amountMsat, nodeId, None, maxAttempts_opt, feeThresholdSat_opt, maxFeePct_opt, externalId_opt, keySend) =>
                              keySend match {
                                case Some(true) => complete(eclairApi.sendWithPreimage(externalId_opt, nodeId, amountMsat, maxAttempts_opt = maxAttempts_opt, feeThresholdSat_opt = feeThresholdSat_opt, maxFeePct_opt = maxFeePct_opt))
                                case _ => reject(MalformedFormFieldRejection("paymentHash", "No payment type specified. Either provide a paymentHash or use --keysend=true"))
                              }
                          }
                        } ~
                        path("sendtoroute") {
                          withRoute { hops =>
                            formFields(amountMsatFormParam, "recipientAmountMsat".as[MilliSatoshi].?, invoiceFormParam, "finalCltvExpiry".as[Int], "externalId".?, "parentId".as[UUID].?, "trampolineSecret".as[ByteVector32].?, "trampolineFeesMsat".as[MilliSatoshi].?, "trampolineCltvExpiry".as[Int].?, "trampolineNodes".as[List[PublicKey]](pubkeyListUnmarshaller).?) {
                              (amountMsat, recipientAmountMsat_opt, invoice, finalCltvExpiry, externalId_opt, parentId_opt, trampolineSecret_opt, trampolineFeesMsat_opt, trampolineCltvExpiry_opt, trampolineNodes_opt) => {
                                val route = hops match {
                                  case Left(shortChannelIds) => PredefinedChannelRoute(invoice.nodeId, shortChannelIds)
                                  case Right(nodeIds) => PredefinedNodeRoute(nodeIds)
                                }
                                complete(eclairApi.sendToRoute(amountMsat, recipientAmountMsat_opt, externalId_opt, parentId_opt, invoice, CltvExpiryDelta(finalCltvExpiry), route, trampolineSecret_opt, trampolineFeesMsat_opt, trampolineCltvExpiry_opt.map(CltvExpiryDelta), trampolineNodes_opt.getOrElse(Nil)))
                              }
                            }
                          }
                        } ~
                        path("sendonchain") {
                          formFields("address".as[String], "amountSatoshis".as[Satoshi], "confirmationTarget".as[Long]) { (address, amount, confirmationTarget) =>
                            complete(eclairApi.sendOnChain(address, amount, confirmationTarget))
                          }
                        } ~
                        path("getsentinfo") {
                          formFields("id".as[UUID]) { id =>
                            complete(eclairApi.sentInfo(Left(id)))
                          } ~ formFields(paymentHashFormParam) { paymentHash =>
                            complete(eclairApi.sentInfo(Right(paymentHash)))
                          }
                        } ~
                        path("createinvoice") {
                          formFields("description".as[String], amountMsatFormParam.?, "expireIn".as[Long].?, "fallbackAddress".as[String].?, "paymentPreimage".as[ByteVector32](sha256HashUnmarshaller).?) { (desc, amountMsat, expire, fallBackAddress, paymentPreimage_opt) =>
                            complete(eclairApi.receive(desc, amountMsat, expire, fallBackAddress, paymentPreimage_opt))
                          }
                        } ~
                        path("getinvoice") {
                          formFields(paymentHashFormParam) { paymentHash =>
                            completeOrNotFound(eclairApi.getInvoice(paymentHash))
                          }
                        } ~
                        path("listinvoices") {
                          formFields(fromFormParam.?, toFormParam.?) { (from_opt, to_opt) =>
                            complete(eclairApi.allInvoices(from_opt, to_opt))
                          }
                        } ~
                        path("listpendinginvoices") {
                          formFields(fromFormParam.?, toFormParam.?) { (from_opt, to_opt) =>
                            complete(eclairApi.pendingInvoices(from_opt, to_opt))
                          }
                        } ~
                        path("getreceivedinfo") {
                          formFields(paymentHashFormParam) { paymentHash =>
                            completeOrNotFound(eclairApi.receivedInfo(paymentHash))
                          } ~ formFields(invoiceFormParam) { invoice =>
                            completeOrNotFound(eclairApi.receivedInfo(invoice.paymentHash))
                          }
                        } ~
                        path("audit") {
                          formFields(fromFormParam.?, toFormParam.?) { (from_opt, to_opt) =>
                            complete(eclairApi.audit(from_opt, to_opt))
                          }
                        } ~
                        path("networkfees") {
                          formFields(fromFormParam.?, toFormParam.?) { (from_opt, to_opt) =>
                            complete(eclairApi.networkFees(from_opt, to_opt))
                          }
                        } ~
                        path("channelstats") {
                          formFields(fromFormParam.?, toFormParam.?) { (from_opt, to_opt) =>
                            complete(eclairApi.channelStats(from_opt, to_opt))
                          }
                        } ~
                        path("usablebalances") {
                          complete(eclairApi.usableBalances())
                        } ~
                        path("onchainbalance") {
                          complete(eclairApi.onChainBalance())
                        } ~
                        path("getnewaddress") {
                          complete(eclairApi.newAddress())
                        } ~
                        path("onchaintransactions") {
                          formFields("count".as[Int].?, "skip".as[Int].?) { (count_opt, skip_opt) =>
                            complete(eclairApi.onChainTransactions(count_opt.getOrElse(10), skip_opt.getOrElse(0)))
                          }
                        } ~
                        path("signmessage") {
                          formFields("msg".as[ByteVector](base64DataUnmarshaller)) { message =>
                            complete(eclairApi.signMessage(message))
                          }
                        } ~
                        path("verifymessage") {
                          formFields("msg".as[ByteVector](base64DataUnmarshaller), "sig".as[ByteVector](binaryDataUnmarshaller)) { (message, signature) =>
                            complete(eclairApi.verifyMessage(message, signature))
                          }
                        }
                    } ~ get {
                      path("ws") {
                        handleWebSocketMessages(makeSocketHandler)
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
