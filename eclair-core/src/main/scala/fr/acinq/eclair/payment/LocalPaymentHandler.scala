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
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Channel}
import fr.acinq.eclair.db.Payment
import fr.acinq.eclair.payment.PaymentLifecycle.{CheckPayment, ReceivePayment}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, NodeParams, randomBytes}

import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try

/**
  * Simple payment handler that generates payment requests and fulfills incoming htlcs.
  *
  * Note that unfulfilled payment requests are kept forever if they don't have an expiry!
  *
  * Created by PM on 17/06/2016.
  */
class LocalPaymentHandler(nodeParams: NodeParams) extends Actor with ActorLogging {

  import LocalPaymentHandler._

  implicit val ec: ExecutionContext = context.system.dispatcher
  context.system.scheduler.schedule(10 minutes, 10 minutes)(self ! PurgeExpiredRequests)

  override def receive: Receive = run(Map.empty)

  def run(hash2preimage: Map[BinaryData, PendingPaymentRequest]): Receive = {

    case PurgeExpiredRequests =>
      context.become(run(hash2preimage.filterNot { case (_, pr) => hasExpired(pr) }))

    case ReceivePayment(amount_opt, desc, expirySeconds_opt, extraHops) =>
      Try {
        if (hash2preimage.size > nodeParams.maxPendingPaymentRequests) {
          throw new RuntimeException(s"too many pending payment requests (max=${nodeParams.maxPendingPaymentRequests})")
        }
        val paymentPreimage = randomBytes(32)
        val paymentHash = Crypto.sha256(paymentPreimage)
        val expirySeconds = expirySeconds_opt.getOrElse(nodeParams.paymentRequestExpiry.toSeconds)
        val paymentRequest = PaymentRequest(nodeParams.chainHash, amount_opt, paymentHash, nodeParams.privateKey, desc, fallbackAddress = None, expirySeconds = Some(expirySeconds), extraHops = extraHops)
        log.debug(s"generated payment request=${PaymentRequest.write(paymentRequest)} from amount=$amount_opt")
        sender ! paymentRequest
        context.become(run(hash2preimage + (paymentHash -> PendingPaymentRequest(paymentPreimage, paymentRequest))))
      } recover { case t => sender ! Status.Failure(t) }

    case CheckPayment(paymentHash) =>
      nodeParams.paymentsDb.findByPaymentHash(paymentHash) match {
        case Some(_) => sender ! true
        case _ => sender ! false
      }

    case htlc: UpdateAddHtlc =>
      hash2preimage
        .get(htlc.paymentHash) // we retrieve the request
        .filterNot(hasExpired) // and filter it out if it is expired (it will be purged independently)
      match {
        case Some(PendingPaymentRequest(paymentPreimage, paymentRequest)) =>
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
              nodeParams.paymentsDb.addPayment(Payment(htlc.paymentHash, htlc.amountMsat, Platform.currentTime / 1000))
              sender ! CMD_FULFILL_HTLC(htlc.id, paymentPreimage, commit = true)
              context.system.eventStream.publish(PaymentReceived(MilliSatoshi(htlc.amountMsat), htlc.paymentHash, htlc.channelId))
              context.become(run(hash2preimage - htlc.paymentHash))
          }
        case None =>
          sender ! CMD_FAIL_HTLC(htlc.id, Right(UnknownPaymentHash), commit = true)
      }

    case 'requests =>
      // this is just for testing
      sender ! hash2preimage
  }

}

object LocalPaymentHandler {

  def props(nodeParams: NodeParams): Props = Props(new LocalPaymentHandler(nodeParams))

  case object PurgeExpiredRequests

  case class PendingPaymentRequest(preimage: BinaryData, paymentRequest: PaymentRequest)

  def hasExpired(pr: PendingPaymentRequest): Boolean = pr.paymentRequest.expiry match {
    case Some(expiry) => pr.paymentRequest.timestamp + expiry <= Platform.currentTime / 1000
    case None => false // this request will never expire
  }

}