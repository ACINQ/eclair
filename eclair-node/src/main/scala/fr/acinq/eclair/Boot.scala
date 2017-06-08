package fr.acinq.eclair

import java.io.File

import grizzled.slf4j.Logging

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {

  case class CmdLineConfig(datadir: File = new File(System.getProperty("user.home"), ".eclair"))

  val parser = new scopt.OptionParser[CmdLineConfig]("eclair") {
    head("eclair", s"${getClass.getPackage.getImplementationVersion} (commit: ${getClass.getPackage.getSpecificationVersion})")
    help("help").abbr("h").text("display usage text")
    opt[File]("datadir").optional().valueName("<file>").action((x, c) => c.copy(datadir = x)).text("optional data directory, default is ~/.eclair")
  }

  try {
    parser.parse(args, CmdLineConfig()) match {
      case Some(config) =>
        LogSetup.logTo(config.datadir)
        new Setup(config.datadir).boostrap
      case None => System.exit(0)
    }
  } catch {
    case t: Throwable =>
      System.err.println(s"fatal error: ${t.getMessage}")
      logger.error(s"fatal error: ${t.getMessage}")
      System.exit(1)
  }
}

