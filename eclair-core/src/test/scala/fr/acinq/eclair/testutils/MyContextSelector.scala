package fr.acinq.eclair.testutils

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.selector.ContextSelector
import ch.qos.logback.classic.util.ContextInitializer

import java.util
import scala.jdk.CollectionConverters.SeqHasAsJava

/**
 * We use a dedicated [[LoggerContext]] for each test, so the logging output do not get mixed up when we run tests in
 * parallel. It allows us to only display the output of the test that failed.
 *
 * See https://logback.qos.ch/manual/contextSelector.html.
 */
class MyContextSelector extends ContextSelector {

  val default = "default"
  var contexts: Map[String, LoggerContext] = Map.empty

  def this(context: LoggerContext) = {
    this()
    synchronized {
      contexts = contexts + (default -> context)
    }
  }

  override def getLoggerContext: LoggerContext = getLoggerContext(default)

  override def getLoggerContext(name: String): LoggerContext = {
    synchronized {
      val context = contexts.getOrElse(name, {
        val context = new LoggerContext()
        context.setName(name)
        new ContextInitializer(context).autoConfig()
        context
      })
      contexts = contexts + (name -> context)
      context
    }
  }

  override def getDefaultLoggerContext: LoggerContext = getLoggerContext(default)

  override def detachLoggerContext(name: String): LoggerContext = {
    synchronized {
      val context = contexts.getOrElse(name, null)
      contexts = contexts - name
      context
    }
  }

  override def getContextNames: util.List[String] = contexts.keys.toList.asJava
}

object MyContextSelector {
  /**
   * The standard logback way would be to resolve the ContextSelector by setting:
   * {{{-Dlogback.ContextSelector=fr.acinq.eclair.testutils.MyContextSelector}}}
   * But relying on system properties is not convenient because intellij ignore
   * values set in pom.xml, so we must set them manually when running tests within
   * the IDE.
   */
  val Singleton: MyContextSelector = new MyContextSelector()
}