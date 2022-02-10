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
import fr.acinq.eclair.db.Monitoring.Metrics
import fr.acinq.eclair.db.PaymentsDb
import fr.acinq.eclair.payment.receive.InvoicePurger.{Command, PurgeCompleted, PurgeResult, TickPurge, ec}
import fr.acinq.eclair.{KamonExt, TimestampMilli, TimestampMilliLong}

import java.util.concurrent.Executors
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

/**
 * This actor will purge expired invoices from the database it was initialized with at a scheduled interval.
 */
object InvoicePurger {

  sealed trait Command
  private case object TickPurge extends Command
  private case class PurgeResult(result: Try[Done]) extends Command

  sealed trait PurgeEvent
  // this notification is sent when we have completed our invoice purge process
  case object PurgeCompleted extends PurgeEvent

  // the purge task will run in this thread pool
  private val ec = ExecutionContext.fromExecutor(Executors.newSingleThreadExecutor())

  def apply(paymentsDb: PaymentsDb, interval: FiniteDuration): Behavior[Command] =
    Behaviors.setup { context =>
      // wait for purge events sent at `interval`
      Behaviors.withTimers { timers =>
        timers.startTimerAtFixedRate(TickPurge, interval)
        new InvoicePurger(paymentsDb, context).waiting(willPurgeAtNextTick = true)
      }
    }
}

class InvoicePurger private(paymentsDb: PaymentsDb,
                            context: ActorContext[Command]) {

  // purge at each tick unless already currently purging
  def waiting(willPurgeAtNextTick: Boolean): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case TickPurge => if (willPurgeAtNextTick) {
        context.log.debug("purging expired invoices")
        context.pipeToSelf(doPurge())(PurgeResult)
        purging(willPurgeAtNextTick = false)
      } else {
        Behaviors.same
      }
    }

  def purging(willPurgeAtNextTick: Boolean): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case PurgeResult(res) =>
        res match {
          case Success(Done) => context.log.debug("invoice purge succeeded")
          case Failure(cause) => context.log.warn(s"invoice purge failed: $cause")
        }
        waiting(willPurgeAtNextTick)
    }

  private def doPurge(): Future[Done] = Future {
    val now = TimestampMilli.now()
    KamonExt.time(Metrics.InvoicePurgeDuration.withoutTags()) {
      val expiredPayments = paymentsDb.listExpiredIncomingPayments(0 unixms, now)
      expiredPayments.foreach(p => paymentsDb.removeIncomingPayment(p.invoice.paymentHash))

      // publish a notification that we have purged expired invoices
      context.system.eventStream ! EventStream.Publish(PurgeCompleted)
      Metrics.InvoicePurgeCompleted.withoutTags().increment()
    }

    Done
  }(ec)

}