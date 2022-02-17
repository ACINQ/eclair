/*
 * Copyright 2022 ACINQ SAS
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

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fr.acinq.eclair.db.IncomingPaymentsDb
import fr.acinq.eclair.payment.receive.InvoicePurger.{Command, PurgeCompleted, TickPurge}
import fr.acinq.eclair.{TimestampMilli, TimestampMilliLong}

import scala.concurrent.duration.FiniteDuration

/**
 * This actor will purge expired invoices from the database it was initialized with at a scheduled interval.
 */
object InvoicePurger {

  def apply(paymentsDb: IncomingPaymentsDb, interval: FiniteDuration): Behavior[Command] =
    Behaviors.setup { context =>
      // wait for purge events sent at `interval`
      Behaviors.withTimers { timers =>
        timers.startTimerAtFixedRate(TickPurge, interval)
        new InvoicePurger(paymentsDb, context).waiting()
      }
    }

  sealed trait Command

  sealed trait PurgeEvent

  // this notification is sent when we have completed our invoice purge process
  case object PurgeCompleted extends PurgeEvent

  private case object TickPurge extends Command
}

class InvoicePurger private(paymentsDb: IncomingPaymentsDb, context: ActorContext[Command]) {

  // purge at each tick unless currently purging
  def waiting(): Behavior[Command] =
    Behaviors.receiveMessage {
      case TickPurge =>
        val now = TimestampMilli.now()
        val expiredPayments = paymentsDb.listExpiredIncomingPayments(0 unixms, now)
        // purge expired payments
        expiredPayments.foreach(p => paymentsDb.removeIncomingPayment(p.invoice.paymentHash))

        // publish a notification when we have purged expired invoices
        if (expiredPayments.nonEmpty) {
          context.system.eventStream ! EventStream.Publish(PurgeCompleted)
        }
        waiting()
    }
}