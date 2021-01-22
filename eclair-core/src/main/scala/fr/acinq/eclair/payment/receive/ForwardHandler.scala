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
import akka.event.DiagnosticLoggingAdapter

/**
 * Simple handler that forwards all messages to an actor
 */
class ForwardHandler(actor: ActorRef) extends ReceiveHandler {
  override def handle(implicit ctx: ActorContext, log: DiagnosticLoggingAdapter): Receive = {
    case msg => actor forward msg
  }
}
