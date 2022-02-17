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

import akka.Done
import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fr.acinq.eclair.db.IncomingPaymentsDb
import fr.acinq.eclair.payment.receive.InvoicePurger.{Command, PurgeCompleted, PurgeResult, TickPurge}
import fr.acinq.eclair.{TimestampMilli, TimestampMilliLong}

import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}

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

  private case class PurgeResult(result: Try[Done]) extends Command

  // this notification is sent when we have completed our invoice purge process
  case object PurgeCompleted extends PurgeEvent

  private case object TickPurge extends Command
}

class InvoicePurger private(paymentsDb: IncomingPaymentsDb, context: ActorContext[Command]) {

  // purge at each tick unless currently purging
  def waiting(): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case TickPurge =>
        doPurge()
        purging()
    }

  // ignore ticks and wait for for purge to complete
  def purging(): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case PurgeResult(res) =>
        res match {
          case Success(Done) => context.log.debug("invoice purge succeeded")
          case Failure(cause) => context.log.warn(s"invoice purge failed: $cause")
        }
        waiting()
    }

  private def doPurge(): Unit = {
    val now = TimestampMilli.now()
    val expiredPayments = paymentsDb.listExpiredIncomingPayments(0 unixms, now)
    // purge expired payments
    expiredPayments.foreach(p => paymentsDb.removeIncomingPayment(p.invoice.paymentHash))

    // publish a notification when we have purged expired invoices
    if (expiredPayments.nonEmpty) {
      context.system.eventStream ! EventStream.Publish(PurgeCompleted)
    }

    // purge complete
    context.self ! PurgeResult(Success(Done))
  }
}