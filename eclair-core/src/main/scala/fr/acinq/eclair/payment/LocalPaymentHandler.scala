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

package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, Props, Status}
import fr.acinq.bitcoin.{Crypto, MilliSatoshi}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Channel}
import fr.acinq.eclair.db.IncomingPayment
import fr.acinq.eclair.payment.PaymentLifecycle.ReceivePayment
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, NodeParams, randomBytes32}
import concurrent.duration._
import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
  * Simple payment handler that generates payment requests and fulfills incoming htlcs.
  *
  * Note that unfulfilled payment requests are kept forever if they don't have an expiry!
  *
  * Created by PM on 17/06/2016.
  */
class LocalPaymentHandler(nodeParams: NodeParams) extends Actor with ActorLogging {

  implicit val ec: ExecutionContext = context.system.dispatcher
  val paymentDb = nodeParams.db.payments

  override def receive: Receive = {

    case ReceivePayment(amount_opt, desc, expirySeconds_opt, extraHops, fallbackAddress_opt) =>
      Try {
        val paymentPreimage = randomBytes32
        val paymentHash = Crypto.sha256(paymentPreimage)
        val expirySeconds = expirySeconds_opt.getOrElse(nodeParams.paymentRequestExpiry.toSeconds)
        val paymentRequest = PaymentRequest(nodeParams.chainHash, amount_opt, paymentHash, nodeParams.privateKey, desc, fallbackAddress_opt, expirySeconds = Some(expirySeconds), extraHops = extraHops)
        log.debug(s"generated payment request={} from amount={}", PaymentRequest.write(paymentRequest), amount_opt)
        paymentDb.addPaymentRequest(paymentRequest, paymentPreimage)
        paymentRequest
      } match {
        case Success(paymentRequest) => sender ! paymentRequest
        case Failure(exception) => sender ! Status.Failure(exception)
      }

    case htlc: UpdateAddHtlc =>
      paymentDb.getPendingPaymentRequestAndPreimage(htlc.paymentHash) match {
        case Some((paymentPreimage, paymentRequest)) =>
          val minFinalExpiry = Globals.blockCount.get() + paymentRequest.minFinalCltvExpiry.getOrElse(Channel.MIN_CLTV_EXPIRY)
          // The htlc amount must be equal or greater than the requested amount. A slight overpaying is permitted, however
          // it must not be greater than two times the requested amount.
          // see https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#failure-messages
          paymentRequest.amount match {
            case _ if htlc.cltvExpiry < minFinalExpiry =>
              sender ! CMD_FAIL_HTLC(htlc.id, Right(FinalExpiryTooSoon), commit = true)
            case Some(amount) if MilliSatoshi(htlc.amountMsat) < amount =>
              log.warning(s"received payment with amount too small for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat}")
              sender ! CMD_FAIL_HTLC(htlc.id, Right(IncorrectPaymentAmount), commit = true)
            case Some(amount) if MilliSatoshi(htlc.amountMsat) > amount * 2 =>
              log.warning(s"received payment with amount too large for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat}")
              sender ! CMD_FAIL_HTLC(htlc.id, Right(IncorrectPaymentAmount), commit = true)
            case _ =>
              log.info(s"received payment for paymentHash=${htlc.paymentHash} amountMsat=${htlc.amountMsat}")
              // amount is correct or was not specified in the payment request
              nodeParams.db.payments.addIncomingPayment(IncomingPayment(htlc.paymentHash, htlc.amountMsat, Platform.currentTime))
              sender ! CMD_FULFILL_HTLC(htlc.id, paymentPreimage, commit = true)
              context.system.eventStream.publish(PaymentReceived(MilliSatoshi(htlc.amountMsat), htlc.paymentHash, htlc.channelId))
          }
        case None =>
          sender ! CMD_FAIL_HTLC(htlc.id, Right(UnknownPaymentHash), commit = true)
      }
  }

}

object LocalPaymentHandler {

  def props(nodeParams: NodeParams): Props = Props(new LocalPaymentHandler(nodeParams))

}