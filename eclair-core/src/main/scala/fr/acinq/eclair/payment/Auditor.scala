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
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.{AuditDb, ChannelLifecycleEvent}

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class Auditor(nodeParams: NodeParams) extends Actor with ActorLogging {

  val db = nodeParams.auditDb

  context.system.eventStream.subscribe(self, classOf[PaymentEvent])
  context.system.eventStream.subscribe(self, classOf[NetworkFeePaid])
  context.system.eventStream.subscribe(self, classOf[AvailableBalanceChanged])
  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])
  context.system.eventStream.subscribe(self, classOf[ChannelClosed])

  val balanceEventThrottler = context.actorOf(Props(new BalanceEventThrottler(db)))

  override def receive: Receive = {

    case e: PaymentSent => db.add(e)

    case e: PaymentReceived => db.add(e)

    case e: PaymentRelayed => db.add(e)

    case e: NetworkFeePaid => db.add(e)

    case e: AvailableBalanceChanged => balanceEventThrottler ! e

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

/**
  * We don't want to log every tiny payment, and we don't want to log probing events.
  */
class BalanceEventThrottler(db: AuditDb) extends Actor with ActorLogging {

  import ExecutionContext.Implicits.global

  val delay = 30 seconds

  case class BalanceUpdate(first: AvailableBalanceChanged, last: AvailableBalanceChanged)

  case class ProcessEvent(channelId: BinaryData)

  override def receive: Receive = run(Map.empty)

  def run(pending: Map[BinaryData, BalanceUpdate]): Receive = {

    case e: AvailableBalanceChanged =>
      pending.get(e.channelId) match {
        case None =>
          // we delay the processing of the event in order to smooth variations
          log.info(s"will log balance event in $delay for channelId=${e.channelId}")
          context.system.scheduler.scheduleOnce(delay, self, ProcessEvent(e.channelId))
          context.become(run(pending + (e.channelId -> (BalanceUpdate(e, e)))))
        case Some(BalanceUpdate(first, _)) =>
          // we already are about to log a balance event, let's update the data we have
          log.info(s"updating balance data for channelId=${e.channelId}")
          context.become(run(pending + (e.channelId -> (BalanceUpdate(first, e)))))
      }

    case ProcessEvent(channelId) =>
      pending.get(channelId) match {
        case Some(BalanceUpdate(first, last)) =>
          if (first.commitments.remoteCommit.spec.toRemoteMsat == last.localBalanceMsat) {
            // we don't log anything if the balance didn't change (e.g. it was a probe payment)
            log.info(s"ignoring balance event for channelId=$channelId (changed was discarded)")
          } else {
            log.info(s"processing balance event for channelId=$channelId balance=${first.localBalanceMsat}->${last.localBalanceMsat}")
            // we log the last event, which contains the most up to date balance
            db.add(last)
            context.become(run(pending - channelId))
          }
        case None => () // wtf?
      }

  }

}

object Auditor {

  def props(nodeParams: NodeParams) = Props(classOf[Auditor], nodeParams)

}
