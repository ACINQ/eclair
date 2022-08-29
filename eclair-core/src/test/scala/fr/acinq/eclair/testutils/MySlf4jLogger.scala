package fr.acinq.eclair.testutils

import akka.actor.{Actor, ExtendedActorSystem}
import akka.dispatch.RequiresMessageQueue
import akka.event.Logging.{Debug, Error, Info, InitializeLogger, LogEvent, LogEventWithCause, LogEventWithMarker, LoggerInitialized, Warning}
import akka.event.slf4j.{SLF4JLogging, Slf4jLogMarker}
import akka.event.{DummyClassForStringSources, LoggerMessageQueueSemantics}
import akka.util.Helpers
import ch.qos.logback.classic.LoggerContext
import org.slf4j.{ILoggerFactory, MDC, Marker, MarkerFactory}

/**
 * Copy of [[akka.event.slf4j.Slf4jLogger]], with a customizable [[ILoggerFactory]] so we can separate logs in tests.
 */

object MyLogger {

  /**
   * @param logger - which logger
   * @return a Logger that corresponds for the given logger name
   */
  def apply(loggerFactory: ILoggerFactory, logger: String): org.slf4j.Logger = loggerFactory.getLogger(logger)

  /**
   * @param logClass  - the class to log for
   * @param logSource - the textual representation of the source of this log stream
   * @return a Logger for the specified parameters
   */
  def apply(loggerFactory: ILoggerFactory, logClass: Class[_], logSource: String): org.slf4j.Logger = logClass match {
    case c if c == classOf[DummyClassForStringSources] => apply(loggerFactory, logSource)
    case _ => loggerFactory.getLogger(logClass.getName)
  }
}

/**
 * SLF4J logger.
 *
 * The thread in which the logging was performed is captured in
 * Mapped Diagnostic Context (MDC) with attribute name "sourceThread".
 */
class MySlf4jLogger extends Actor with SLF4JLogging with RequiresMessageQueue[LoggerMessageQueueSemantics] {

  private val contextName = context.system.settings.config.getString("akka.logging-context")

  val loggerFactory: LoggerContext = MyContextSelector.Singleton.getLoggerContext(contextName)

  val mdcThreadAttributeName = "sourceThread"
  val mdcActorSystemAttributeName = "sourceActorSystem"
  val mdcAkkaSourceAttributeName = "akkaSource"
  val mdcAkkaTimestamp = "akkaTimestamp"
  val mdcAkkaAddressAttributeName = "akkaAddress"

  private def akkaAddress = context.system.asInstanceOf[ExtendedActorSystem].provider.rootPath.address.toString

  def receive = {

    case event@Error(cause, logSource, logClass, message) =>
      withMdc(logSource, event) {
        cause match {
          case Error.NoCause | null =>
            MyLogger(loggerFactory, logClass, logSource).error(markerIfPresent(event), if (message != null) message.toString else null)
          case _ =>
            MyLogger(loggerFactory, logClass, logSource).error(
              markerIfPresent(event),
              if (message != null) message.toString else cause.getLocalizedMessage,
              cause)
        }
      }

    case event@Warning(logSource, logClass, message) =>
      withMdc(logSource, event) {
        event match {
          case e: LogEventWithCause =>
            MyLogger(loggerFactory, logClass, logSource).warn(
              markerIfPresent(event),
              if (message != null) message.toString else e.cause.getLocalizedMessage,
              e.cause)
          case _ =>
            MyLogger(loggerFactory, logClass, logSource).warn(markerIfPresent(event), if (message != null) message.toString else null)
        }
      }

    case event@Info(logSource, logClass, message) =>
      withMdc(logSource, event) {
        MyLogger(loggerFactory, logClass, logSource).info(markerIfPresent(event), "{}", message: Any)
      }

    case event@Debug(logSource, logClass, message) =>
      withMdc(logSource, event) {
        MyLogger(loggerFactory, logClass, logSource).debug(markerIfPresent(event), "{}", message: Any)
      }

    case InitializeLogger(_) =>
      log.info("Slf4jLogger started")
      sender() ! LoggerInitialized
  }

  @inline
  final def withMdc(logSource: String, logEvent: LogEvent)(logStatement: => Unit): Unit = {
    logEvent match {
      case m: LogEventWithMarker if m.marker ne null =>
        val properties = m.marker.properties
        if (properties.nonEmpty) {
          properties.foreach { case (k, v) => MDC.put(k, String.valueOf(v)) }
        }
      case _ =>
    }

    MDC.put(mdcAkkaSourceAttributeName, logSource)
    MDC.put(mdcThreadAttributeName, logEvent.thread.getName)
    MDC.put(mdcAkkaTimestamp, formatTimestamp(logEvent.timestamp))
    MDC.put(mdcActorSystemAttributeName, context.system.name)
    MDC.put(mdcAkkaAddressAttributeName, akkaAddress)
    logEvent.mdc.foreach { case (k, v) => MDC.put(k, String.valueOf(v)) }

    try logStatement
    finally {
      MDC.clear()
    }
  }

  private final def markerIfPresent(event: LogEvent): Marker =
    event match {
      case m: LogEventWithMarker =>
        m.marker match {
          case null => null
          case slf4jMarker: Slf4jLogMarker => slf4jMarker.marker
          case marker => MarkerFactory.getMarker(marker.name)
        }
      case _ => null
    }

  /**
   * Override this method to provide a differently formatted timestamp
   *
   * @param timestamp a "currentTimeMillis"-obtained timestamp
   * @return the given timestamp as a UTC String
   */
  protected def formatTimestamp(timestamp: Long): String =
    Helpers.currentTimeMillisToUTCString(timestamp)

}
