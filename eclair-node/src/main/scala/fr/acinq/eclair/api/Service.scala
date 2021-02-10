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

import akka.actor.ActorSystem
import akka.http.scaladsl.server._
import akka.stream.Materializer
import akka.util.Timeout
import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.api.FormParamExtractors._
import fr.acinq.eclair.blockchain.fee.FeeratePerByte
import fr.acinq.eclair.io.NodeURI
import fr.acinq.eclair.payment.PaymentRequest
import fr.acinq.eclair.router.Router.{PredefinedChannelRoute, PredefinedNodeRoute}
import fr.acinq.eclair.{CltvExpiryDelta, Eclair, MilliSatoshi}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import java.util.UUID

case class ErrorResponse(error: String)

trait Service extends EclairDirectives with WebSocket with Logging {

  // important! Must NOT import the unmarshaller as it is too generic...see https://github.com/akka/akka-http/issues/541
  import JsonSupport.{formats, marshaller, serialization}

  def password: String

  val eclairApi: Eclair

  implicit val actorSystem: ActorSystem

  implicit val mat: Materializer



  val getInfo:Route = postRequest("getinfo") { implicit t =>
     complete(eclairApi.getInfo())
  }

  val connect:Route = postRequest("connect") { implicit t =>
    formFields("uri".as[NodeURI]) { uri =>
      complete(eclairApi.connect(Left(uri)))
    } ~ formFields(nodeIdFormParam, "host".as[String], "port".as[Int].?) { (nodeId, host, port_opt) =>
      complete {
        eclairApi.connect(
          Left(NodeURI(nodeId, HostAndPort.fromParts(host, port_opt.getOrElse(NodeURI.DEFAULT_PORT))))
        )
      }
    } ~ formFields(nodeIdFormParam) { nodeId =>
      complete(eclairApi.connect(Right(nodeId)))
    }
  }

  val disconnect:Route = postRequest("disconnect") { implicit t =>
    formFields(nodeIdFormParam) { nodeId =>
      complete(eclairApi.disconnect(nodeId))
    }
  }

  val open:Route = postRequest("open") { implicit t =>
    formFields(nodeIdFormParam, "fundingSatoshis".as[Satoshi], "pushMsat".as[MilliSatoshi].?,
      "fundingFeerateSatByte".as[FeeratePerByte].?, "feeBaseMsat".as[MilliSatoshi].?,
      "feeProportionalMillionths".as[Int].?, "channelFlags".as[Int].?, "openTimeoutSeconds".as[Timeout].?) {
      (nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, feeBase, feeProportional, channelFlags, openTimeout_opt) =>
        if (feeBase.nonEmpty && feeProportional.isEmpty || feeBase.isEmpty && feeProportional.nonEmpty) {
           reject(MalformedFormFieldRejection("feeBaseMsat/feeProportionalMillionths",
             "All relay fees parameters (feeBaseMsat/feeProportionalMillionths) must be specified to override node defaults"
           ))
        } else {
          val initialRelayFees = (feeBase, feeProportional) match {
            case (Some(feeBase), Some(feeProportional)) => Some(feeBase, feeProportional)
            case _ => None
          }
          complete{
            eclairApi.open(
              nodeId, fundingSatoshis, pushMsat, fundingFeerateSatByte, initialRelayFees, channelFlags, openTimeout_opt
            )
          }
        }
    }

  }

  val close:Route = postRequest("close") { implicit t =>
    withChannelsIdentifier { channels =>
      formFields("scriptPubKey".as[ByteVector](binaryDataUnmarshaller).?) { scriptPubKey_opt =>
        complete(eclairApi.close(channels, scriptPubKey_opt))
      }
    }
  }

  val forceClose:Route = postRequest("forceclose") { implicit t =>
    withChannelsIdentifier { channels =>
      complete(eclairApi.forceClose(channels))
    }
  }

  val channels:Route = postRequest("channels") { implicit t =>
    formFields(nodeIdFormParam.?) { toRemoteNodeId_opt =>
      complete(eclairApi.channelsInfo(toRemoteNodeId_opt))
    }
  }

  val channel:Route = postRequest("channel") { implicit t =>
    withChannelIdentifier { channel =>
      complete(eclairApi.channelInfo(channel))
    }
  }

  val allChannels:Route = postRequest("allchannels") { implicit t =>
    complete(eclairApi.allChannels())
  }

  val peers: Route = postRequest("peers") { implicit t =>
    complete(eclairApi.peers())
  }

  val updateRelayFee: Route = postRequest("updaterelayfee") { implicit t =>
    withChannelsIdentifier { channels =>
      formFields("feeBaseMsat".as[MilliSatoshi], "feeProportionalMillionths".as[Long]) { (feeBase, feeProportional) =>
        complete(eclairApi.updateRelayFee(channels, feeBase, feeProportional))
      }
    }
  }

  val nodes: Route = postRequest("nodes") { implicit t =>
    formFields(nodeIdsFormParam.?) { nodeIds_opt =>
      complete(eclairApi.nodes(nodeIds_opt.map(_.toSet)))
    }
  }

  val networkStats: Route = postRequest("networkstats") { implicit t =>
    complete(eclairApi.networkStats())
  }

  val allUpdates: Route = postRequest("allupdates") { implicit t =>
    formFields(nodeIdFormParam.?) { nodeId_opt =>
      complete(eclairApi.allUpdates(nodeId_opt))
    }
  }

  val findRoute: Route = postRequest("findroute") { implicit t =>
    formFields(invoiceFormParam, amountMsatFormParam.?) {
      case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None) =>
        complete(eclairApi.findRoute(nodeId, amount, invoice.routingInfo))
      case (invoice, Some(overrideAmount)) =>
        complete(eclairApi.findRoute(invoice.nodeId, overrideAmount, invoice.routingInfo))
      case _ => reject(MalformedFormFieldRejection(
        "invoice", "The invoice must have an amount or you need to specify one using 'amountMsat'"
      ))
    }
  }

  val findRouteToNode: Route = postRequest("findroutetonode") { implicit t =>
    formFields(nodeIdFormParam, amountMsatFormParam) { (nodeId, amount) =>
      complete(eclairApi.findRoute(nodeId, amount))
    }
  }

  val parseInvoice: Route = postRequest("parseinvoice") { implicit t =>
    formFields(invoiceFormParam) { invoice =>
      complete(invoice)
    }
  }

  val payInvoice: Route = postRequest("payinvoice") { implicit t =>
    formFields(invoiceFormParam, amountMsatFormParam.?, "maxAttempts".as[Int].?, "feeThresholdSat".as[Satoshi].?,
      "maxFeePct".as[Double].?, "externalId".?) {
      case (invoice@PaymentRequest(_, Some(amount), _, nodeId, _, _), None, maxAttempts, feeThresholdSat_opt, maxFeePct_opt, externalId_opt) =>
        complete(eclairApi.send(
          externalId_opt, nodeId, amount, invoice.paymentHash, Some(invoice),
          maxAttempts, feeThresholdSat_opt, maxFeePct_opt
        ))
      case (invoice, Some(overrideAmount), maxAttempts, feeThresholdSat_opt, maxFeePct_opt, externalId_opt) =>
        complete(eclairApi.send(
          externalId_opt, invoice.nodeId, overrideAmount, invoice.paymentHash,
          Some(invoice), maxAttempts, feeThresholdSat_opt, maxFeePct_opt
        ))
      case _ => reject(MalformedFormFieldRejection(
        "invoice", "The invoice must have an amount or you need to specify one using the field 'amountMsat'"
      ))
    }
  }

  val sendToNode: Route = postRequest("sendtonode") { implicit t =>
    formFields(amountMsatFormParam, nodeIdFormParam, paymentHashFormParam.?, "maxAttempts".as[Int].?,
      "feeThresholdSat".as[Satoshi].?, "maxFeePct".as[Double].?, "externalId".?, "keysend".as[Boolean].?) {
      case (amountMsat, nodeId, Some(paymentHash), maxAttempts_opt, feeThresholdSat_opt, maxFeePct_opt, externalId_opt, keySend) =>
        keySend match {
          case Some(true) => reject(MalformedFormFieldRejection(
            "paymentHash", "You cannot request a KeySend payment and specify a paymentHash"
          ))
          case _ => complete(eclairApi.send(
            externalId_opt, nodeId, amountMsat, paymentHash,
            maxAttempts_opt = maxAttempts_opt,
            feeThresholdSat_opt = feeThresholdSat_opt,
            maxFeePct_opt = maxFeePct_opt
          ))
        }
      case (amountMsat, nodeId, None, maxAttempts_opt, feeThresholdSat_opt, maxFeePct_opt, externalId_opt, keySend) =>
        keySend match {
          case Some(true) => complete(eclairApi.sendWithPreimage(
            externalId_opt, nodeId, amountMsat,
            maxAttempts_opt = maxAttempts_opt,
            feeThresholdSat_opt = feeThresholdSat_opt,
            maxFeePct_opt = maxFeePct_opt)
          )
          case _ => reject(MalformedFormFieldRejection(
            "paymentHash", "No payment type specified. Either provide a paymentHash or use --keysend=true"
          ))
        }
    }
  }

  val sendToRoute: Route = postRequest("sendtoroute") { implicit t =>
    withRoute { hops =>
      formFields(amountMsatFormParam, "recipientAmountMsat".as[MilliSatoshi].?, invoiceFormParam,
        "finalCltvExpiry".as[Int], "externalId".?, "parentId".as[UUID].?, "trampolineSecret".as[ByteVector32].?,
        "trampolineFeesMsat".as[MilliSatoshi].?, "trampolineCltvExpiry".as[Int].?,
        "trampolineNodes".as[List[PublicKey]](pubkeyListUnmarshaller).?) {
        (amountMsat, recipientAmountMsat_opt, invoice, finalCltvExpiry, externalId_opt, parentId_opt,
         trampolineSecret_opt, trampolineFeesMsat_opt, trampolineCltvExpiry_opt, trampolineNodes_opt) => {
          val route = hops match {
            case Left(shortChannelIds) => PredefinedChannelRoute(invoice.nodeId, shortChannelIds)
            case Right(nodeIds) => PredefinedNodeRoute(nodeIds)
          }
          complete(eclairApi.sendToRoute(
            amountMsat, recipientAmountMsat_opt, externalId_opt, parentId_opt, invoice,
            CltvExpiryDelta(finalCltvExpiry), route, trampolineSecret_opt, trampolineFeesMsat_opt,
            trampolineCltvExpiry_opt.map(CltvExpiryDelta), trampolineNodes_opt.getOrElse(Nil)
          ))
        }
      }
    }
  }

  val sendOnChain: Route = postRequest("sendonchain") { implicit t =>
    formFields("address".as[String], "amountSatoshis".as[Satoshi], "confirmationTarget".as[Long]) {
      (address, amount, confirmationTarget) =>
      complete(eclairApi.sendOnChain(address, amount, confirmationTarget))
    }
  }

  val getSentInfo: Route = postRequest("getsentinfo") { implicit t =>
    formFields("id".as[UUID]) { id =>
      complete(eclairApi.sentInfo(Left(id)))
    } ~ formFields(paymentHashFormParam) { paymentHash =>
      complete(eclairApi.sentInfo(Right(paymentHash)))
    }
  }

  val createInvoice: Route = postRequest("createinvoice") { implicit t =>
    formFields("description".as[String], amountMsatFormParam.?, "expireIn".as[Long].?, "fallbackAddress".as[String].?,
      "paymentPreimage".as[ByteVector32](sha256HashUnmarshaller).?) {
      (desc, amountMsat, expire, fallBackAddress, paymentPreimage_opt) =>
        complete(eclairApi.receive(desc, amountMsat, expire, fallBackAddress, paymentPreimage_opt))
    }
  }

  val getInvoice: Route = postRequest("getinvoice") { implicit t =>
    formFields(paymentHashFormParam) { paymentHash =>
      completeOrNotFound(eclairApi.getInvoice(paymentHash))
    }
  }

  val listInvoices: Route = postRequest("listinvoices") { implicit t =>
    formFields(fromFormParam.?, toFormParam.?) { (from_opt, to_opt) =>
      complete(eclairApi.allInvoices(from_opt, to_opt))
    }
  }

  val listPendingInvoices: Route = postRequest("listpendinginvoices") { implicit t =>
    formFields(fromFormParam.?, toFormParam.?) { (from_opt, to_opt) =>
      complete(eclairApi.pendingInvoices(from_opt, to_opt))
    }
  }

  val getReceivedInfo: Route = postRequest("getreceivedinfo") { implicit t =>
    formFields(paymentHashFormParam) { paymentHash =>
      completeOrNotFound(eclairApi.receivedInfo(paymentHash))
    } ~ formFields(invoiceFormParam) { invoice =>
      completeOrNotFound(eclairApi.receivedInfo(invoice.paymentHash))
    }
  }

  val audit: Route = postRequest("audit") { implicit t =>
    formFields(fromFormParam.?, toFormParam.?) { (from_opt, to_opt) =>
      complete(eclairApi.audit(from_opt, to_opt))
    }
  }

  val networkFees: Route = postRequest("networkfees") { implicit t =>
    formFields(fromFormParam.?, toFormParam.?) { (from_opt, to_opt) =>
      complete(eclairApi.networkFees(from_opt, to_opt))
    }
  }

  val channelStats: Route = postRequest("channelstats") { implicit t =>
    formFields(fromFormParam.?, toFormParam.?) { (from_opt, to_opt) =>
      complete(eclairApi.channelStats(from_opt, to_opt))
    }
  }

  val usableBalances: Route = postRequest("usablebalances") { implicit t =>
    complete(eclairApi.usableBalances())
  }

  val onChainBalance: Route = postRequest("onchainbalance") { implicit t =>
    complete(eclairApi.onChainBalance())
  }

  val getNewAddress: Route = postRequest("getnewaddress") { implicit t =>
    complete(eclairApi.newAddress())
  }

  val onChainTransactions: Route = postRequest("onchaintransactions") { implicit t =>
    formFields("count".as[Int].?, "skip".as[Int].?) { (count_opt, skip_opt) =>
      complete(eclairApi.onChainTransactions(count_opt.getOrElse(10), skip_opt.getOrElse(0)))
    }
  }

  val signMessage: Route = postRequest("signmessage") { implicit t =>
    formFields("msg".as[ByteVector](base64DataUnmarshaller)) { message =>
      complete(eclairApi.signMessage(message))
    }
  }

  val verifyMessage: Route = postRequest("verifymessage") { implicit t =>
    formFields("msg".as[ByteVector](base64DataUnmarshaller), "sig".as[ByteVector](binaryDataUnmarshaller)) { (message, signature) =>
      complete(eclairApi.verifyMessage(message, signature))
    }
  }

  serialization

  val route: Route = securedPublicHandler {
    getInfo ~
      connect ~ disconnect ~
      open ~ close ~ forceClose ~
      channels ~ channel ~ allChannels ~ channelStats ~
      peers ~ nodes ~ networkStats ~ allUpdates ~
      findRoute ~ findRouteToNode ~
      parseInvoice ~ payInvoice ~ createInvoice ~ getInvoice ~ listInvoices ~ listPendingInvoices ~
      sendToNode ~ sendToRoute ~ sendOnChain ~
      getSentInfo ~ getReceivedInfo ~
      updateRelayFee ~ networkFees ~
      usableBalances ~ onChainBalance ~ onChainTransactions ~
      signMessage ~ verifyMessage ~ getNewAddress ~
      audit ~ webSocket
  }
}
