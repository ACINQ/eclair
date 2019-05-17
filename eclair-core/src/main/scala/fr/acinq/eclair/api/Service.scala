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
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.util.Timeout
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.api.FormParamExtractors._
import fr.acinq.eclair.api.JsonSupport.CustomTypeHints
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.payment.PaymentLifecycle.PaymentFailed
import fr.acinq.eclair.payment.{PaymentReceived, PaymentRequest, _}
import fr.acinq.eclair.{Eclair, ShortChannelId}
import grizzled.slf4j.Logging
import org.json4s.jackson.Serialization
import scodec.bits.ByteVector
import scala.concurrent.Future
import scala.concurrent.duration._

case class ErrorResponse(error: String)

trait Service extends ExtraDirectives with Logging {

  // important! Must NOT import the unmarshaller as it is too generic...see https://github.com/akka/akka-http/issues/541
  import JsonSupport.{formats, marshaller, serialization}

  // used to send typed messages over the websocket
  val formatsWithTypeHint = formats.withTypeHintFieldName("type") +
    CustomTypeHints(Map(
      classOf[PaymentSent] -> "payment-sent",
      classOf[PaymentRelayed] -> "payment-relayed",
      classOf[PaymentReceived] -> "payment-received",
      classOf[PaymentSettlingOnChain] -> "payment-settling-onchain",
      classOf[PaymentFailed] -> "payment-failed"
    ))

  def password: String

  val eclairApi: Eclair

  implicit val actorSystem: ActorSystem
  implicit val mat: ActorMaterializer

  val apiExceptionHandler = ExceptionHandler {
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
        context.system.eventStream.subscribe(self, classOf[PaymentFailed])
        context.system.eventStream.subscribe(self, classOf[PaymentEvent])
      }

      def receive: Receive = {
        case message: PaymentFailed => flowInput.offer(Serialization.write(message)(formatsWithTypeHint))
        case message: PaymentEvent => flowInput.offer(Serialization.write(message)(formatsWithTypeHint))
      }

    }))

    Flow[Message]
      .mapConcat(_ => Nil) // Ignore heartbeats and other data from the client
      .merge(flowOutput) // Stream the data we want to the client
      .map(TextMessage.apply)
  }

  val timeoutResponse: HttpRequest => HttpResponse = { r =>
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
          formFields("timeoutSeconds".as[Timeout].?) { tm_opt =>
            // this is the akka timeout
            implicit val timeout = tm_opt.getOrElse(Timeout(30 seconds))
            // we ensure that http timeout is greater than akka timeout
            withRequestTimeout(timeout.duration + 2.seconds) {
              withRequestTimeoutResponse(timeoutResponse) {
                authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator) { _ =>
                  post {
                    path("getinfo") {
                      complete(eclairApi.getInfoResponse())
                    } ~
                      path("connect") {
                        formFields("uri".as[String]) { uri =>
                          complete(eclairApi.connect(uri))
                        } ~ formFields(nodeIdFormParam, "host".as[String], "port".as[Int].?) { (nodeId, host, port_opt) =>
                          complete(eclairApi.connect(s"$nodeId@$host:${port_opt.getOrElse(NodeURI.DEFAULT_PORT)}"))
                        }
                      } ~
                      path("open") {
                        formFields(nodeIdFormParam, "fundingSatoshis".as[Long], "pushMsat".as[Long].?, "fundingFeerateSatByte".as[Long].?, "channelFlags".as[Int].?, "openTimeoutSeconds".as[Timeout].?) {
                          (nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags, openTimeout_opt) =>
                            complete(eclairApi.open(nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags, openTimeout_opt))
                        }
                      } ~
                      path("updaterelayfee") {
                        withChannelIdentifier { channelIdentifier =>
                          formFields("feeBaseMsat".as[Long], "feeProportionalMillionths".as[Long]) { (feeBase, feeProportional) =>
                            complete(eclairApi.updateRelayFee(channelIdentifier, feeBase, feeProportional))
                          }
                        }
                      } ~
                      path("close") {
                        withChannelIdentifier { channelIdentifier =>
                          formFields("scriptPubKey".as[ByteVector](binaryDataUnmarshaller).?) { scriptPubKey_opt =>
                            complete(eclairApi.close(channelIdentifier, scriptPubKey_opt))
                          }
                        }
                      } ~
                      path("forceclose") {
                        withChannelIdentifier { channelIdentifier =>
                          complete(eclairApi.forceClose(channelIdentifier))
                        }
                      } ~
                      path("peers") {
                        complete(eclairApi.peersInfo())
                      } ~
                      path("channels") {
                        formFields(nodeIdFormParam.?) { toRemoteNodeId_opt =>
                          complete(eclairApi.channelsInfo(toRemoteNodeId_opt))
                        }
                      } ~
                      path("channel") {
                        withChannelIdentifier { channelIdentifier =>
                          complete(eclairApi.channelInfo(channelIdentifier))
                        }
                      } ~
                      path("allnodes") {
                        complete(eclairApi.allNodes())
                      } ~
                      path("allchannels") {
                        complete(eclairApi.allChannels())
                      } ~
                      path("allupdates") {
                        formFields(nodeIdFormParam.?) { nodeId_opt =>
                          complete(eclairApi.allUpdates(nodeId_opt))
                        }
                      } ~
                      path("findroute") {
                        formFields(invoiceFormParam, amountMsatFormParam.?) {
                          case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None) => complete(eclairApi.findRoute(nodeId, amount.toLong, invoice.routingInfo))
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
                        formFields(invoiceFormParam, amountMsatFormParam.?, "maxAttempts".as[Int].?) {
                          case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None, maxAttempts) =>
                            complete(eclairApi.send(nodeId, amount.toLong, invoice.paymentHash, invoice.routingInfo, invoice.minFinalCltvExpiry, maxAttempts))
                          case (invoice, Some(overrideAmount), maxAttempts) =>
                            complete(eclairApi.send(invoice.nodeId, overrideAmount, invoice.paymentHash, invoice.routingInfo, invoice.minFinalCltvExpiry, maxAttempts))
                          case _ => reject(MalformedFormFieldRejection("invoice", "The invoice must have an amount or you need to specify one using the field 'amountMsat'"))
                        }
                      } ~
                      path("sendtonode") {
                        formFields(amountMsatFormParam, paymentHashFormParam, nodeIdFormParam, "maxAttempts".as[Int].?) { (amountMsat, paymentHash, nodeId, maxAttempts) =>
                          complete(eclairApi.send(nodeId, amountMsat, paymentHash, maxAttempts = maxAttempts))
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
                        complete(eclairApi.channelStats())
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
