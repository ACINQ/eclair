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

import akka.http.scaladsl.server._
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, MilliSatoshi, Satoshi}
import fr.acinq.eclair.{Eclair, Kit, ShortChannelId}
import FormParamExtractors._
import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.CacheDirectives.{`max-age`, `no-store`, public}
import akka.http.scaladsl.model.headers.{`Access-Control-Allow-Headers`, `Access-Control-Allow-Methods`, `Cache-Control`}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.directives.{Credentials, LoggingMagnet}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import akka.stream.scaladsl.{BroadcastHub, Flow, Keep, Source}
import fr.acinq.eclair.api.JsonSupport.CustomTypeHints
import fr.acinq.eclair.channel.{ChannelCreated, ChannelFundingPublished, ChannelFundingRolledBack, ChannelIdAssigned}
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.payment.PaymentLifecycle.PaymentFailed
import fr.acinq.eclair.payment._
import grizzled.slf4j.Logging
import org.json4s.{ShortTypeHints, TypeHints}
import org.json4s.jackson.Serialization
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

case class ErrorResponse(error: String)

trait Service extends Directives with Logging {

  // important! Must NOT import the unmarshaller as it is too generic...see https://github.com/akka/akka-http/issues/541
  import JsonSupport.marshaller
  import JsonSupport.formats
  import JsonSupport.serialization
  // used to send typed messages over the websocket
  val formatsWithTypeHint = formats.withTypeHintFieldName("type") +
    CustomTypeHints(Map(
      classOf[ChannelCreated] -> "channel-created",
      classOf[ChannelIdAssigned] -> "channel-id-assigned",
      classOf[ChannelFundingRolledBack] -> "channel-funding-rolled-back",
      classOf[ChannelFundingPublished] -> "channel-funding-published",
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

  // a named and typed URL parameter used across several routes, 32-bytes hex-encoded
  val channelId = "channelId".as[ByteVector32](sha256HashUnmarshaller)
  val nodeId = "nodeId".as[PublicKey]
  val shortChannelId = "shortChannelId".as[ShortChannelId](shortChannelIdUnmarshaller)

  val apiExceptionHandler = ExceptionHandler {
    case t: Throwable =>
      logger.error(s"API call failed with cause=${t.getMessage}", t)
      complete(StatusCodes.InternalServerError, ErrorResponse(t.getMessage))
  }

  // map all the rejections to a JSON error object ErrorResponse
  val apiRejectionHandler = RejectionHandler.default.mapRejectionResponse {
    case res @ HttpResponse(_, _, ent: HttpEntity.Strict, _) =>
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
        context.system.eventStream.subscribe(self, classOf[ChannelCreated])
        context.system.eventStream.subscribe(self, classOf[ChannelIdAssigned])
        context.system.eventStream.subscribe(self, classOf[ChannelFundingRolledBack])
        context.system.eventStream.subscribe(self, classOf[ChannelFundingPublished])
        context.system.eventStream.subscribe(self, classOf[PaymentFailed])
        context.system.eventStream.subscribe(self, classOf[PaymentEvent])
      }

      def receive: Receive = {
        case message: ChannelCreated => flowInput.offer(Serialization.write(message)(formatsWithTypeHint))
        case message: ChannelIdAssigned => flowInput.offer(Serialization.write(message)(formatsWithTypeHint))
        case message: ChannelFundingRolledBack => flowInput.offer(Serialization.write(message)(formatsWithTypeHint))
        case message: ChannelFundingPublished => flowInput.offer(Serialization.write(message)(formatsWithTypeHint))
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
        handleRejections(apiRejectionHandler){
          withRequestTimeoutResponse(timeoutResponse) {
            authenticateBasicAsync(realm = "Access restricted", userPassAuthenticator) { _ =>
              post {
                path("getinfo") {
                  complete(eclairApi.getInfoResponse())
                } ~
                  path("connect") {
                    formFields("uri".as[String]) { uri =>
                      complete(eclairApi.connect(uri))
                    } ~ formFields(nodeId, "host".as[String], "port".as[Int].?) { (nodeId, host, port_opt) =>
                      complete(eclairApi.connect(s"$nodeId@$host:${port_opt.getOrElse(NodeURI.DEFAULT_PORT)}"))
                    }
                  } ~
                  path("open") {
                    formFields(nodeId, "fundingSatoshis".as[Long], "pushMsat".as[Long].?, "fundingFeerateSatByte".as[Long].?, "channelFlags".as[Int].?) {
                      (nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags) =>
                        complete(eclairApi.open(nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags))
                    }
                  } ~
                  path("close") {
                    formFields(channelId, "scriptPubKey".as[ByteVector](binaryDataUnmarshaller).?) { (channelId, scriptPubKey_opt) =>
                      complete(eclairApi.close(Left(channelId), scriptPubKey_opt))
                    } ~ formFields(shortChannelId, "scriptPubKey".as[ByteVector](binaryDataUnmarshaller).?) { (shortChannelId, scriptPubKey_opt) =>
                      complete(eclairApi.close(Right(shortChannelId), scriptPubKey_opt))
                    }
                  } ~
                  path("forceclose") {
                    formFields(channelId) { channelId =>
                      complete(eclairApi.forceClose(Left(channelId)))
                    } ~ formFields(shortChannelId) { shortChannelId =>
                      complete(eclairApi.forceClose(Right(shortChannelId)))
                    }
                  } ~
                  path("updaterelayfee") {
                    formFields(channelId, "feeBaseMsat".as[Long], "feeProportionalMillionths".as[Long]) { (channelId, feeBase, feeProportional) =>
                      complete(eclairApi.updateRelayFee(channelId.toString, feeBase, feeProportional))
                    }
                  } ~
                  path("peers") {
                    complete(eclairApi.peersInfo())
                  } ~
                  path("channels") {
                    formFields(nodeId.?) { toRemoteNodeId_opt =>
                      complete(eclairApi.channelsInfo(toRemoteNodeId_opt))
                    }
                  } ~
                  path("channel") {
                    formFields(channelId) { channelId =>
                      complete(eclairApi.channelInfo(channelId))
                    }
                  } ~
                  path("allnodes") {
                    complete(eclairApi.allnodes())
                  } ~
                  path("allchannels") {
                    complete(eclairApi.allchannels())
                  } ~
                  path("allupdates") {
                    formFields(nodeId.?) { nodeId_opt =>
                      complete(eclairApi.allupdates(nodeId_opt))
                    }
                  } ~
                  path("receive") {
                    formFields("description".as[String], "amountMsat".as[Long].?, "expireIn".as[Long].?) { (desc, amountMsat, expire) =>
                      complete(eclairApi.receive(desc, amountMsat, expire))
                    }
                  } ~
                  path("parseinvoice") {
                    formFields("invoice".as[PaymentRequest]) { invoice =>
                      complete(invoice)
                    }
                  } ~
                  path("findroute") {
                    formFields("invoice".as[PaymentRequest], "amountMsat".as[Long].?) {
                      case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None) => complete(eclairApi.findRoute(nodeId, amount.toLong, invoice.routingInfo))
                      case (invoice, Some(overrideAmount)) => complete(eclairApi.findRoute(invoice.nodeId, overrideAmount, invoice.routingInfo))
                      case _ => reject(MalformedFormFieldRejection("invoice", "The invoice must have an amount or you need to specify one using 'amountMsat'"))
                    }
                  } ~ path("findroutetonode") {
                  formFields(nodeId, "amountMsat".as[Long]) { (nodeId, amount) =>
                    complete(eclairApi.findRoute(nodeId, amount))
                  }
                } ~
                  path("send") {
                    formFields("invoice".as[PaymentRequest], "amountMsat".as[Long].?) {
                      case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None) =>
                        complete(eclairApi.send(nodeId, amount.toLong, invoice.paymentHash, invoice.routingInfo, invoice.minFinalCltvExpiry))
                      case (invoice, Some(overrideAmount)) =>
                        complete(eclairApi.send(invoice.nodeId, overrideAmount, invoice.paymentHash, invoice.routingInfo, invoice.minFinalCltvExpiry))
                      case _ => reject(MalformedFormFieldRejection("invoice", "The invoice must have an amount or you need to specify one using the field 'amountMsat'"))
                    }
                  } ~
                  path("sendtonode") {
                    formFields("amountMsat".as[Long], "paymentHash".as[ByteVector32](sha256HashUnmarshaller), "nodeId".as[PublicKey]) { (amountMsat, paymentHash, nodeId) =>
                      complete(eclairApi.send(nodeId, amountMsat, paymentHash))
                    }
                  } ~
                  path("checkpayment") {
                    formFields("paymentHash".as[ByteVector32](sha256HashUnmarshaller)) { paymentHash =>
                      complete(eclairApi.checkpayment(paymentHash))
                    } ~ formFields("invoice".as[PaymentRequest]) { invoice =>
                      complete(eclairApi.checkpayment(invoice.paymentHash))
                    }
                  } ~
                  path("audit") {
                    formFields("from".as[Long].?, "to".as[Long].?) { (from, to) =>
                      complete(eclairApi.audit(from, to))
                    }
                  } ~
                  path("networkfees") {
                    formFields("from".as[Long].?, "to".as[Long].?) { (from, to) =>
                      complete(eclairApi.networkFees(from, to))
                    }
                  } ~
                  path("channelstats") {
                    complete(eclairApi.channelStats())
                  } ~
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