package fr.acinq.eclair

import java.io.File

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.core.FileAppender
import org.slf4j.LoggerFactory

/**
  * Created by PM on 01/06/2017.
  */
object LogSetup {
  def logTo(datadir: File) = {
    val lc = LoggerFactory.getILoggerFactory().asInstanceOf[LoggerContext]
    val ple = new PatternLayoutEncoder()
    ple.setPattern("%d %-5level %logger{36} %X{akkaSource} - %msg%ex{24}%n")
    ple.setContext(lc)
    ple.start()
    val fileAppender = new FileAppender[ILoggingEvent]()
    fileAppender.setFile(new File(datadir, "eclair.log").getPath)
    fileAppender.setEncoder(ple)
    fileAppender.setContext(lc)
    fileAppender.start()
    val logger = LoggerFactory.getLogger("ROOT").asInstanceOf[Logger]
    logger.addAppender(fileAppender)
  }
}
