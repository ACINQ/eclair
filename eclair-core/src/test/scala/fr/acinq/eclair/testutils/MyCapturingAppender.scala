package fr.acinq.eclair.testutils

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase

/**
 * Heavily inspired by [[akka.actor.testkit.typed.internal.CapturingAppender]]
 */
class MyCapturingAppender extends AppenderBase[ILoggingEvent] {

  private var buffer: Vector[ILoggingEvent] = Vector.empty

  // invocations are synchronized via doAppend in AppenderBase
  override def append(event: ILoggingEvent): Unit = {
    event.prepareForDeferredProcessing()
    buffer :+= event
  }

  /**
   * Flush buffered logging events to the output appenders
   * Also clears the buffer..
   */
  def flush(): Unit = synchronized {
    val deferredTestLogger = this.getContext.asInstanceOf[LoggerContext].getLogger("MyCapturingAppenderDelegate")
    for (event <- buffer) {
      deferredTestLogger.callAppenders(event)
    }
    clear()
  }

  /**
   * Discards the buffered logging events without output.
   */
  def clear(): Unit = synchronized {
    buffer = Vector.empty
  }

}
