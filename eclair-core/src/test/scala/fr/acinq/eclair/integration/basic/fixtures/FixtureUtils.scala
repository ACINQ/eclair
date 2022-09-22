package fr.acinq.eclair.integration.basic.fixtures

import com.typesafe.config.{Config, ConfigFactory}

object FixtureUtils {

  def actorSystemConfig(testName: String): Config =
    ConfigFactory
      .parseString {
        s"""|  akka {
            |    logging-context = "$testName"
            |    loggers = ["fr.acinq.eclair.testutils.MySlf4jLogger"]
            |    // akka-typed always uses slf4j and by default will remove custom loggers
            |    use-slf4j = false
            |    actor.debug.event-stream = off
            |    }""".stripMargin
      }.withFallback(ConfigFactory.load())
      .resolve()

}
