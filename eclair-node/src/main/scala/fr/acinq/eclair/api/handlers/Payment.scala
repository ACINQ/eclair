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

package fr.acinq.eclair.api.handlers

import akka.http.scaladsl.server.{MalformedFormFieldRejection, Route}
import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.api.Service
import fr.acinq.eclair.api.directives.EclairDirectives
import fr.acinq.eclair.api.serde.FormParamExtractors.{pubkeyListUnmarshaller, _}
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.router.Router.{PredefinedChannelRoute, PredefinedNodeRoute}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshi, randomBytes32}

import java.util.UUID

trait Payment {
  this: Service with EclairDirectives =>

  import fr.acinq.eclair.api.serde.JsonSupport.{formats, marshaller, serialization}

  val usableBalances: Route = postRequest("usablebalances") { implicit t =>
    complete(eclairApi.usableBalances())
  }

  val payInvoice: Route = postRequest("payinvoice") { implicit t =>
    formFields(invoiceFormParam, amountMsatFormParam.?, "maxAttempts".as[Int].?, "feeThresholdSat".as[Satoshi].?, "maxFeePct".as[Double].?, "externalId".?, "blocking".as[Boolean].?, "pathFindingExperimentName".?) {
      case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None, maxAttempts, feeThresholdSat_opt, maxFeePct_opt, externalId_opt, blocking_opt, pathFindingExperimentName_opt) =>
        blocking_opt match {
          case Some(true) => complete(eclairApi.sendBlocking(externalId_opt, amount, invoice, maxAttempts, feeThresholdSat_opt, maxFeePct_opt, pathFindingExperimentName_opt))
          case _ => complete(eclairApi.send(externalId_opt, amount, invoice, maxAttempts, feeThresholdSat_opt, maxFeePct_opt, pathFindingExperimentName_opt))
        }
      case (invoice, Some(overrideAmount), maxAttempts, feeThresholdSat_opt, maxFeePct_opt, externalId_opt, blocking_opt, pathFindingExperimentName_opt) =>
        blocking_opt match {
          case Some(true) => complete(eclairApi.sendBlocking(externalId_opt, overrideAmount, invoice, maxAttempts, feeThresholdSat_opt, maxFeePct_opt, pathFindingExperimentName_opt))
          case _ => complete(eclairApi.send(externalId_opt, overrideAmount, invoice, maxAttempts, feeThresholdSat_opt, maxFeePct_opt, pathFindingExperimentName_opt))
        }
      case _ => reject(MalformedFormFieldRejection("invoice", "The invoice must have an amount or you need to specify one using the field 'amountMsat'"))
    }
  }

  val sendToRoute: Route = postRequest("sendtoroute") { implicit t =>
    withRoute { hops =>
      formFields(amountMsatFormParam, "recipientAmountMsat".as[MilliSatoshi].?, invoiceFormParam, "finalCltvExpiry".as[Int], "externalId".?, "parentId".as[UUID].?,
        "trampolineSecret".as[ByteVector32].?, "trampolineFeesMsat".as[MilliSatoshi].?, "trampolineCltvExpiry".as[Int].?, "trampolineNodes".as[List[PublicKey]](pubkeyListUnmarshaller).?) {
        (amountMsat, recipientAmountMsat_opt, invoice, finalCltvExpiry, externalId_opt, parentId_opt, trampolineSecret_opt, trampolineFeesMsat_opt, trampolineCltvExpiry_opt, trampolineNodes_opt) => {
          val route = hops match {
            case Left(shortChannelIds) => PredefinedChannelRoute(invoice.nodeId, shortChannelIds)
            case Right(nodeIds) => PredefinedNodeRoute(nodeIds)
          }
          complete(eclairApi.sendToRoute(
            amountMsat, recipientAmountMsat_opt, externalId_opt, parentId_opt, invoice, CltvExpiryDelta(finalCltvExpiry), route, trampolineSecret_opt, trampolineFeesMsat_opt,
            trampolineCltvExpiry_opt.map(CltvExpiryDelta), trampolineNodes_opt.getOrElse(Nil)
          ))
        }
      }
    }
  }

  val sendToNode: Route = postRequest("sendtonode") { implicit t =>
    formFields(amountMsatFormParam, nodeIdFormParam, "maxAttempts".as[Int].?, "feeThresholdSat".as[Satoshi].?, "maxFeePct".as[Double].?, "externalId".?, "pathFindingExperimentName".?) {
      case (amountMsat, nodeId, maxAttempts_opt, feeThresholdSat_opt, maxFeePct_opt, externalId_opt, pathFindingExperimentName_opt) =>
        complete(eclairApi.sendWithPreimage(externalId_opt, nodeId, amountMsat, randomBytes32(), maxAttempts_opt, feeThresholdSat_opt, maxFeePct_opt, pathFindingExperimentName_opt))
    }
  }

  val getSentInfo: Route = postRequest("getsentinfo") { implicit t =>
    formFields("id".as[UUID]) { id =>
      complete(eclairApi.sentInfo(Left(id)))
    } ~ formFields(paymentHashFormParam) { paymentHash =>
      complete(eclairApi.sentInfo(Right(paymentHash)))
    }
  }

  val getReceivedInfo: Route = postRequest("getreceivedinfo") { implicit t =>
    formFields(paymentHashFormParam) { paymentHash =>
      completeOrNotFound(eclairApi.receivedInfo(paymentHash))
    } ~ formFields(invoiceFormParam) { invoice =>
      completeOrNotFound(eclairApi.receivedInfo(invoice.paymentHash))
    }
  }

  val paymentRoutes: Route = usableBalances ~ payInvoice ~ sendToNode ~ sendToRoute ~ getSentInfo ~ getReceivedInfo

}
