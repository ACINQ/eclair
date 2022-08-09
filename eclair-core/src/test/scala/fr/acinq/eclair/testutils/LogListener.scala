package fr.acinq.eclair.testutils

import akka.actor.{Actor, ActorSystem}
import akka.event.Logging.{LogEvent, StdOutLogger}
import fr.acinq.eclair.testutils.LogListener.{ListenTo, PrintLogs}

import scala.collection.mutable.ListBuffer

/** ListBuffer = constant time append */
class LogListener(logEvents: ListBuffer[LogEvent]) extends Actor with StdOutLogger {

  override def receive: Receive = {
    case ListenTo(actorSystem) => actorSystem.eventStream.subscribe(self, classOf[LogEvent])
    case e: LogEvent =>
      if (!e.logClass.getCanonicalName.startsWith("akka")) {
        logEvents += e
      }
    case PrintLogs => logEvents.foreach(print)
  }
}

object LogListener {
  // @formatter:off
  case class ListenTo(actorSystem: ActorSystem)
  case object PrintLogs
  // @formatter:on
}
