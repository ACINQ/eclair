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

import akka.util.Timeout
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.api.FormParamExtractors._
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.{CltvExpiryDelta, Eclair, MilliSatoshi}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector
import spray.http.CacheDirectives.{public, _}
import spray.http.HttpHeaders._
import spray.http.{HttpMethods, StatusCodes}
import spray.routing.authentication.{BasicAuth, UserPass}
import spray.routing.{ExceptionHandler, MalformedFormFieldRejection, Route}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class ErrorResponse(error: String)

trait Service extends ExtraDirectives with Logging {

  import JsonSupport.json4sMarshaller

  implicit val ec = ExecutionContext.global
  implicit val timeout = Timeout(30 seconds)

  val password: String
  val eclairApi: Eclair

  implicit val apiExceptionHandler = ExceptionHandler {
    case t: IllegalArgumentException =>
      logger.error(s"API call failed with cause=${t.getMessage}", t)
      complete(StatusCodes.BadRequest, ErrorResponse(t.getMessage))
    case t: Throwable =>
      logger.error(s"API call failed with cause=${t.getMessage}", t)
      complete(StatusCodes.InternalServerError, ErrorResponse(t.getMessage))
  }

  val customHeaders = `Access-Control-Allow-Headers`("Content-Type, Authorization") ::
    `Access-Control-Allow-Methods`(HttpMethods.POST :: Nil) ::
    `Cache-Control`(public, `no-store`, `max-age`(0)) :: Nil

  def userPassAuthenticator(userPass: Option[UserPass]): Future[Option[String]] = userPass match {
    case Some(UserPass(user, pass)) if pass == password => Future.successful(Some("user"))
    case _ => Future.successful(None)
  }

  def route: Route = {
    respondWithHeaders(customHeaders) {
      handleExceptions(apiExceptionHandler) {
        authenticate(BasicAuth(userPassAuthenticator _, realm = "Access restricted")) { _ =>
          post {
            path("getinfo") {
              complete(eclairApi.getInfo())
            } ~
              path("connect") {
                formFields("uri".as[Option[NodeURI]]) { uri =>
                  complete(eclairApi.connect(Left(uri.get)))
                } ~ formFields(nodeIdFormParam_opt, "host".as[String], "port".as[Int].?) { (nodeId, host, port_opt) =>
                  complete(eclairApi.connect(Left(NodeURI(nodeId.get, HostAndPort.fromParts(host, port_opt.getOrElse(NodeURI.DEFAULT_PORT))))))
                } ~ formFields(nodeIdFormParam_opt) { nodeId =>
                  complete(eclairApi.connect(Right(nodeId.get)))
                }
              } ~
              path("disconnect") {
                formFields(nodeIdFormParam_opt) { nodeId =>
                  complete(eclairApi.disconnect(nodeId.get))
                }
              } ~
              path("open") {
                formFields(nodeIdFormParam_opt, "fundingSatoshis".as[Option[Satoshi]](satoshiUnmarshaller), "pushMsat".as[Option[MilliSatoshi]](millisatoshiUnmarshaller), "fundingFeerateSatByte".as[Option[Long]], "channelFlags".as[Option[Int]]) {
                  (nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, channelFlags) =>
                    complete(eclairApi.open(nodeId.get, fundingSatoshis.get, pushMsat, fundingFeerateSatByte, channelFlags, None))
                }
              } ~
              path("updaterelayfee") {
                withChannelsIdentifier { channels =>
                  formFields("feeBaseMsat".as[Option[MilliSatoshi]](millisatoshiUnmarshaller), "feeProportionalMillionths".as[Option[Long]]) { (feeBase, feeProportional) =>
                    complete(eclairApi.updateRelayFee(channels, feeBase.get, feeProportional.get))
                  }
                }
              } ~
              path("close") {
                withChannelsIdentifier { channels =>
                  formFields("scriptPubKey".as[Option[ByteVector]](binaryDataUnmarshaller)) { scriptPubKey_opt =>
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
                complete(eclairApi.peersInfo())
              } ~
              path("channels") {
                formFields(nodeIdFormParam_opt) { toRemoteNodeId_opt =>
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
              path("networkstats") {
                complete(eclairApi.networkStats())
              } ~
              path("allupdates") {
                formFields(nodeIdFormParam_opt) { nodeId_opt =>
                  complete(eclairApi.allUpdates(nodeId_opt))
                }
              } ~
              path("findroute") {
                formFields(invoiceFormParam_opt, amountMsatFormParam_opt) {
                  case (Some(invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _)), None) => complete(eclairApi.findRoute(nodeId, amount, invoice.routingInfo))
                  case (Some(invoice), Some(overrideAmount)) => complete(eclairApi.findRoute(invoice.nodeId, overrideAmount, invoice.routingInfo))
                  case _ => reject(MalformedFormFieldRejection("invoice", "The invoice must have an amount or you need to specify one using 'amountMsat'"))
                }
              } ~
              path("findroutetonode") {
                formFields(nodeIdFormParam_opt, amountMsatFormParam_opt) { (nodeId, amount) =>
                  complete(eclairApi.findRoute(nodeId.get, amount.get))
                }
              } ~
              path("parseinvoice") {
                formFields(invoiceFormParam_opt) { invoice =>
                  complete(invoice)
                }
              } ~
              path("payinvoice") {
                formFields(invoiceFormParam_opt, amountMsatFormParam_opt, "maxAttempts".as[Int].?, "feeThresholdSat".as[Option[Satoshi]](satoshiUnmarshaller), "maxFeePct".as[Double].?, "externalId".?) {
                  case (Some(invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _)), None, maxAttempts, feeThresholdSat_opt, maxFeePct_opt, externalId_opt) =>
                    complete(eclairApi.send(externalId_opt, nodeId, amount, invoice.paymentHash, Some(invoice), maxAttempts, feeThresholdSat_opt, maxFeePct_opt))
                  case (Some(invoice), Some(overrideAmount), maxAttempts, feeThresholdSat_opt, maxFeePct_opt, externalId_opt) =>
                    complete(eclairApi.send(externalId_opt, invoice.nodeId, overrideAmount, invoice.paymentHash, Some(invoice), maxAttempts, feeThresholdSat_opt, maxFeePct_opt))
                  case _ => reject(MalformedFormFieldRejection("invoice", "The invoice must have an amount or you need to specify one using the field 'amountMsat'"))
                }
              } ~
              path("sendtonode") {
                formFields(amountMsatFormParam_opt, paymentHashFormParam_opt, nodeIdFormParam_opt, "maxAttempts".as[Int].?, "feeThresholdSat".as[Option[Satoshi]](satoshiUnmarshaller), "maxFeePct".as[Double].?, "externalId".?) {
                  (amountMsat, paymentHash, nodeId, maxAttempts_opt, feeThresholdSat_opt, maxFeePct_opt, externalId_opt) =>
                    complete(eclairApi.send(externalId_opt, nodeId.get, amountMsat.get, paymentHash.get, maxAttempts_opt = maxAttempts_opt, feeThresholdSat_opt = feeThresholdSat_opt, maxFeePct_opt = maxFeePct_opt))
                }
              } ~
              path("sendtoroute") {
                formFields(amountMsatFormParam_opt, "recipientAmountMsat".as[Option[MilliSatoshi]](millisatoshiUnmarshaller), invoiceFormParam_opt, "finalCltvExpiry".as[Int], "route".as[Option[List[PublicKey]]](pubkeyListUnmarshaller), "externalId".?, "parentId".as[UUID].?, "trampolineSecret".as[Option[ByteVector32]](sha256HashUnmarshaller), "trampolineFeesMsat".as[Option[MilliSatoshi]](millisatoshiUnmarshaller), "trampolineCltvExpiry".as[Int].?, "trampolineNodes".as[Option[List[PublicKey]]](pubkeyListUnmarshaller)) {
                  (amountMsat, recipientAmountMsat_opt, invoice, finalCltvExpiry, route, externalId_opt, parentId_opt, trampolineSecret_opt, trampolineFeesMsat_opt, trampolineCltvExpiry_opt, trampolineNodes_opt) =>
                    complete(eclairApi.sendToRoute(amountMsat.get, recipientAmountMsat_opt, externalId_opt, parentId_opt, invoice.get, CltvExpiryDelta(finalCltvExpiry), route.get, trampolineSecret_opt, trampolineFeesMsat_opt, trampolineCltvExpiry_opt.map(CltvExpiryDelta), trampolineNodes_opt.getOrElse(Nil)))
                }
              } ~ path("sendonchain") {
                formFields("address".as[String], "amountSatoshis".as[Option[Satoshi]](satoshiUnmarshaller), "confirmationTarget".as[Long]) { (address, amount_opt, confirmationTarget) =>
                  complete(eclairApi.sendOnChain(address, amount_opt.get, confirmationTarget))
                }
              } ~
              path("getsentinfo") {
                formFields("id".as[Option[UUID]]) { id =>
                  complete(eclairApi.sentInfo(Left(id.get)))
                } ~ formFields(paymentHashFormParam_opt) { paymentHash =>
                  complete(eclairApi.sentInfo(Right(paymentHash.get)))
                }
              } ~
              path("createinvoice") {
                formFields("description".as[String], amountMsatFormParam_opt, "expireIn".as[Long].?, "fallbackAddress".as[String].?, "paymentPreimage".as[Option[ByteVector32]](sha256HashUnmarshaller)) { (desc, amountMsat, expire, fallBackAddress, paymentPreimage_opt) =>
                  complete(eclairApi.receive(desc, amountMsat, expire, fallBackAddress, paymentPreimage_opt))
                }
              } ~
              path("getinvoice") {
                formFields(paymentHashFormParam_opt) { paymentHash =>
                  completeOrNotFound(eclairApi.getInvoice(paymentHash.get))
                }
              } ~
              path("listinvoices") {
                formFields(fromFormParam_opt.?, toFormParam_opt.?) { (from_opt, to_opt) =>
                  complete(eclairApi.allInvoices(from_opt, to_opt))
                }
              } ~
              path("listpendinginvoices") {
                formFields(fromFormParam_opt.?, toFormParam_opt.?) { (from_opt, to_opt) =>
                  complete(eclairApi.pendingInvoices(from_opt, to_opt))
                }
              } ~
              path("getreceivedinfo") {
                formFields(paymentHashFormParam_opt) { paymentHash =>
                  completeOrNotFound(eclairApi.receivedInfo(paymentHash.get))
                } ~ formFields(invoiceFormParam_opt) { invoice =>
                  completeOrNotFound(eclairApi.receivedInfo(invoice.get.paymentHash))
                }
              } ~
              path("audit") {
                formFields(fromFormParam_opt.?, toFormParam_opt.?) { (from_opt, to_opt) =>
                  complete(eclairApi.audit(from_opt, to_opt))
                }
              } ~
              path("networkfees") {
                formFields(fromFormParam_opt.?, toFormParam_opt.?) { (from_opt, to_opt) =>
                  complete(eclairApi.networkFees(from_opt, to_opt))
                }
              } ~
              path("channelstats") {
                complete(eclairApi.channelStats())
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
              }
          }
        }
      }
    }
  }
}