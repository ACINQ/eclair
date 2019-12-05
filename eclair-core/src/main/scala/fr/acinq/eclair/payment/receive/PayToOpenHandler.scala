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

package fr.acinq.eclair.payment.receive

import akka.actor.Actor.Receive
import akka.actor.{ActorContext, ActorRef}
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.{NodeParams, _}
import fr.acinq.eclair.db.{IncomingPayment, PaymentsDb}
import fr.acinq.eclair.io.PayToOpenRequestEvent
import fr.acinq.eclair.payment.PaymentReceived
import fr.acinq.eclair.payment.receive.PayToOpenHandler._
import fr.acinq.eclair.wire.{PayToOpenRequest, PayToOpenResponse}

import scala.concurrent.Promise
import scala.concurrent.duration._

class PayToOpenHandler(nodeParams: NodeParams, commandBuffer: ActorRef) extends MultiPartHandler(nodeParams, nodeParams.db.payments, commandBuffer) {

  // NB: this is safe because this handler will be called from within an actor
  private var pendingPayToOpenReqs: Map[ByteVector32, List[PayToOpenRequest]] = Map.empty

  override def handle(implicit ctx: ActorContext, log: LoggingAdapter): Receive = {
    case payToOpenRequest: PayToOpenRequest => nodeParams.db.payments.getIncomingPayment(payToOpenRequest.paymentHash) match {
      case Some(record@IncomingPayment(paymentRequest, _, _, _)) if paymentRequest.features.allowMultiPart && paymentRequest.amount.isDefined && payToOpenRequest.amountMsat < paymentRequest.amount.get =>
        // this is a chunk for a multipart payment, do we already have the rest?
        log.info(s"received chunk for a multipart payment payToOpenRequest=$payToOpenRequest")
        val chunks = payToOpenRequest +: pendingPayToOpenReqs.getOrElse(payToOpenRequest.paymentHash, Nil)
        val totalAmount = chunks.map(_.amountMsat).sum
        if (totalAmount >= paymentRequest.amount.get) {
          log.info(s"we have all the chunks for paymentHash=${paymentRequest.paymentHash} chunks = ${chunks.size}")
          // we have everything, let's proceed
          handlePayToOpen(nodeParams.db.payments, Some(record), chunks)
          pendingPayToOpenReqs = pendingPayToOpenReqs - paymentRequest.paymentHash
        } else {
          // we are missing some chunks, let's wait
          pendingPayToOpenReqs = pendingPayToOpenReqs + (payToOpenRequest.paymentHash -> chunks)
        }
      case record_opt =>
        // in all other cases we use the default handler
        handlePayToOpen(nodeParams.db.payments, record_opt, List(payToOpenRequest))
    }
  }

}

object PayToOpenHandler {

  def handlePayToOpen(paymentsDb: PaymentsDb, record_opt: Option[IncomingPayment], payToOpenRequests: List[PayToOpenRequest])(implicit ctx: ActorContext, log: LoggingAdapter) = {
    import ctx.dispatcher
    val transport = ctx.sender
    val chainHash = payToOpenRequests.head.chainHash
    val paymentHash = payToOpenRequests.head.paymentHash
    val fundingSatoshis = payToOpenRequests.map(_.fundingSatoshis).sum
    val totalAmountMsat = payToOpenRequests.map(_.amountMsat).sum
    val feeSatoshis = payToOpenRequests.map(_.feeSatoshis).sum
    val payToOpenResponseDenied = PayToOpenResponse(
      chainHash = chainHash,
      paymentHash = paymentHash,
      paymentPreimage = ByteVector32.Zeroes) // preimage all-zero means we say no to the pay-to-open request
    record_opt match {
      case Some(IncomingPayment(paymentRequest, paymentPreimage, _, _)) =>
        paymentRequest.amount match {
          case _ if paymentRequest.isExpired =>
            log.warning(s"received payment for an expired payment request paymentHash=${paymentHash}")
            transport ! payToOpenResponseDenied
          case Some(amount) if totalAmountMsat < amount =>
            log.warning(s"received payment with amount too small for paymentHash=${paymentHash} amount=${totalAmountMsat}")
            transport ! payToOpenResponseDenied
          case Some(amount) if totalAmountMsat > amount * 2 =>
            log.warning(s"received payment with amount too large for paymentHash=${paymentHash} amount=${totalAmountMsat}")
            transport ! payToOpenResponseDenied
          case _ =>
            // amount is correct or was not specified in the payment request
            log.info(s"received pay-to-open payment for paymentHash=${paymentHash} amount=${totalAmountMsat}")
            val decision = Promise[Boolean]()
            if (feeSatoshis == 0.sat) {
              // we always say ok when fee is zero, without asking the user
              decision.success(true)
            } else {
              val summarizedPayToOpenRequest = PayToOpenRequest(chainHash, fundingSatoshis, totalAmountMsat, feeSatoshis, paymentHash)
              ctx.system.eventStream.publish(PayToOpenRequestEvent(ctx.self, summarizedPayToOpenRequest, decision))
              ctx.system.scheduler.scheduleOnce(60 seconds)(decision.tryFailure(new RuntimeException("pay-to-open timed out")))
            }
            decision
              .future
              .recover { case _: Throwable => false }
              .foreach { result =>
                val payToOpenResponse = if (result) { // user said yes
                  log.info(s"user said ok to pay-to-open request for paymentHash=${paymentHash} amount=${totalAmountMsat}")
                  paymentsDb.receiveIncomingPayment(paymentHash, totalAmountMsat - feeSatoshis)
                  ctx.system.eventStream.publish(PaymentReceived(paymentHash, PaymentReceived.PartialPayment(totalAmountMsat - feeSatoshis, ByteVector32.Zeroes) :: Nil))
                  payToOpenResponseDenied.copy(paymentPreimage = paymentPreimage)
                } else { // user said no
                  payToOpenResponseDenied
                }
                transport ! payToOpenResponse
              }
        }
      case None =>
        transport ! payToOpenResponseDenied
    }
  }

}
