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

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.payment.PaymentLifecycle.{SendPayment,PaymentResult}

/**
  * Created by PM on 29/08/2016.
  */
class PaymentInitiator(sourceNodeId: PublicKey, router: ActorRef, register: ActorRef) extends Actor with ActorLogging {
  override def receive: Receive = run(Map(),Map(),0)

  def run(actors: Map[ActorRef, Long],results: Map[Long,Option[PaymentResult]] , paymentCounterId: Long): Receive = {
    case c: SendPayment =>
      val payFsm = context.actorOf(PaymentLifecycle.props(sourceNodeId, router, register))
      if (c.async) sender ! SendPaymentId(paymentCounterId)
      payFsm forward c
      context.become(run(actors + (payFsm -> paymentCounterId), results +(paymentCounterId -> None), paymentCounterId+1))
    case (state: PaymentResult) =>
      val id=actors(sender)
      log.info("got:"+id+" state:"+state.toString())
      context.become(run(actors,results + (id -> Some(state)),paymentCounterId))
    case SendPaymentId(id) =>
      log.info(s"got: $id state2:"+results.get(id))
      sender ! CheckSendPaymentResult(results.contains(id),results.get(id))
  }

}

object PaymentInitiator {
  def props(sourceNodeId: PublicKey, router: ActorRef, register: ActorRef) = Props(classOf[PaymentInitiator], sourceNodeId, router, register)
}

case class SendPaymentId(id: Long) // A reference for this payment to allow api to callback to get status. As payments can hang.

case class CheckSendPaymentResult(validId: Boolean, result: Option[Option[PaymentResult]])
