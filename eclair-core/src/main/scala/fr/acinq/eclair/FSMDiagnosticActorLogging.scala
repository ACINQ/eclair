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
