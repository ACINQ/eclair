package fr.acinq.eclair

import java.io.File

import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger, LoggerContext}
import ch.qos.logback.core.FileAppender
import com.sun.javafx.application.LauncherImpl
import fr.acinq.eclair.gui.{FxApp, FxPreloader}
import grizzled.slf4j.Logging
import org.slf4j.LoggerFactory

case class CmdLineConfig(datadir: File = new File(System.getProperty("user.home"), ".eclair"), headless: Boolean = false)

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {

  val parser = new scopt.OptionParser[CmdLineConfig]("eclair") {
    head("eclair", s"${getClass.getPackage.getImplementationVersion} (commit: ${getClass.getPackage.getSpecificationVersion})")
    help("help").abbr("h").text("display usage text")
    opt[File]("datadir").optional().valueName("<file>").action((x, c) => c.copy(datadir = x)).text("optional data directory, default is ~/.eclair")
    opt[Unit]("headless").optional().action((_, c) => c.copy(headless = true)).text("runs eclair without a gui")
  }
  parser.parse(args, CmdLineConfig()) match {
    case Some(config) if config.headless => try {
      val s = new Setup(config.datadir.getAbsolutePath)
      s.boostrap
    } catch {
      case t: Throwable =>
        System.err.println(s"fatal error: ${t.getMessage}")
        logger.error(s"fatal error: ${t.getMessage}")
        System.exit(1)
    }
    case Some(config) => LauncherImpl.launchApplication(classOf[FxApp], classOf[FxPreloader], Array(config.datadir.getAbsolutePath))
    case None => System.exit(0)
  }
}

object LogSetup {
  def logTo(datadir: String) = {
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
