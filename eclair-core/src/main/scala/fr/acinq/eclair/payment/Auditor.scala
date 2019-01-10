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
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.ChannelLifecycleEvent

class Auditor(nodeParams: NodeParams) extends Actor with ActorLogging {

  val db = nodeParams.auditDb

  context.system.eventStream.subscribe(self, classOf[PaymentEvent])
  context.system.eventStream.subscribe(self, classOf[NetworkFeePaid])
  context.system.eventStream.subscribe(self, classOf[AvailableBalanceChanged])
  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])
  context.system.eventStream.subscribe(self, classOf[ChannelClosed])

  override def receive: Receive = {

    case e: PaymentSent => db.add(e)

    case e: PaymentReceived => db.add(e)

    case e: PaymentRelayed => db.add(e)

    case e: NetworkFeePaid => db.add(e)

    case e: AvailableBalanceChanged => db.add(e)

    case e: ChannelStateChanged =>
      e match {
        case ChannelStateChanged(_, _, remoteNodeId, WAIT_FOR_FUNDING_LOCKED, NORMAL, d: DATA_NORMAL) =>
          db.add(ChannelLifecycleEvent(d.channelId, remoteNodeId, d.commitments.commitInput.txOut.amount.toLong, d.commitments.localParams.isFunder, !d.commitments.announceChannel, "created"))
        case _ => ()
      }

    case e: ChannelClosed =>
      db.add(ChannelLifecycleEvent(e.channelId, e.commitments.remoteParams.nodeId, e.commitments.commitInput.txOut.amount.toLong, e.commitments.localParams.isFunder, !e.commitments.announceChannel, e.closeType))

  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled msg=$message")
}

object Auditor {

  def props(nodeParams: NodeParams) = Props(classOf[Auditor], nodeParams)

}
