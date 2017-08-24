package fr.acinq.eclair

import java.io.File

import com.sun.javafx.application.LauncherImpl
import fr.acinq.eclair.gui.{FxApp, FxPreloader}
import grizzled.slf4j.Logging

/**
  * Created by PM on 25/01/2016.
  */
object JavafxBoot extends App with Logging {

  case class CmdLineConfig(datadir: File = new File(System.getProperty("user.home"), ".eclair"), headless: Boolean = false)

  val parser = new scopt.OptionParser[CmdLineConfig]("eclair") {
    head("eclair gui", s"${getClass.getPackage.getImplementationVersion} (commit: ${getClass.getPackage.getSpecificationVersion})")
    help("help").abbr("h").text("display usage text")
    opt[File]("datadir").optional().valueName("<file>").action((x, c) => c.copy(datadir = x)).text("optional data directory, default is ~/.eclair")
    opt[Unit]("headless").optional().action((_, c) => c.copy(headless = true)).text("runs eclair without a gui")
  }

  try {
    parser.parse(args, CmdLineConfig()) match {
      case Some(config) if config.headless =>
        LogSetup.logTo(config.datadir)
        new Setup(config.datadir).bootstrap
      case Some(config) =>
        LogSetup.logTo(config.datadir)
        LauncherImpl.launchApplication(classOf[FxApp], classOf[FxPreloader], Array(config.datadir.getAbsolutePath))
      case None => System.exit(0)
    }
  } catch {
    case t: Throwable =>
      System.err.println(s"fatal error: ${t.getMessage}")
      logger.error(s"fatal error: ${t.getMessage}")
      System.exit(1)
  }
}
