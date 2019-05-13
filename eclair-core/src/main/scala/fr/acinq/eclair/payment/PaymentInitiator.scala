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

import java.util.UUID
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.payment.PaymentLifecycle.GenericSendPayment

/**
  * Created by PM on 29/08/2016.
  */
class PaymentInitiator(nodeParams: NodeParams, router: ActorRef, register: ActorRef) extends Actor with ActorLogging {

  override def receive: Receive = {
    case c: GenericSendPayment =>
      val paymentId = UUID.randomUUID()
      val payFsm = context.actorOf(PaymentLifecycle.props(nodeParams, paymentId, router, register))
      payFsm forward c
      sender ! paymentId
  }

}

object PaymentInitiator {
  def props(nodeParams: NodeParams, router: ActorRef, register: ActorRef) = Props(classOf[PaymentInitiator], nodeParams, router, register)
}
