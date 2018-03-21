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

package fr.acinq.eclair

import akka.actor.{Actor, FSM}
import akka.event.{DiagnosticLoggingAdapter, LoggingAdapter}

/**
  * A version of akka.actor.DiagnosticActorLogging compatible with an FSM
  * See https://groups.google.com/forum/#!topic/akka-user/0CxR8CImr4Q
  */
trait FSMDiagnosticActorLogging[S, D] extends FSM[S, D] {

  import akka.event.Logging._

  val diagLog: DiagnosticLoggingAdapter = akka.event.Logging(this)

  def mdc(currentMessage: Any): MDC = emptyMDC

  override def log: LoggingAdapter = diagLog

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = try {
    diagLog.mdc(mdc(msg))
    super.aroundReceive(receive, msg)
  } finally {
    diagLog.clearMDC()
  }
}
