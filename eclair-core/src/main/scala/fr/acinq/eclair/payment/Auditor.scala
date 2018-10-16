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

import akka.actor.{Actor, ActorLogging, Props}
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.NetworkFeePaid

class Auditor(nodeParams: NodeParams) extends Actor with ActorLogging {

  context.system.eventStream.subscribe(self, classOf[PaymentEvent])
  context.system.eventStream.subscribe(self, classOf[NetworkFeePaid])

  override def receive: Receive = {

    case e: PaymentSent => nodeParams.auditDb.add(e)

    case e: PaymentReceived => nodeParams.auditDb.add(e)

    case e: PaymentRelayed => nodeParams.auditDb.add(e)

    case e: NetworkFeePaid => nodeParams.auditDb.add(e)

    // Does not exactly fit but when added here there is no need for a separate listener
    case e: PaymentSettlingOnChain if !e.isDone => nodeParams.pendingPaymentDb.add(e)

  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled msg=$message")
}

object Auditor {

  def props(nodeParams: NodeParams) = Props(classOf[Auditor], nodeParams)

}
