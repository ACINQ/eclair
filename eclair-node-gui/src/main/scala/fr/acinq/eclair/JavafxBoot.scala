package fr.acinq.eclair

import java.io.File

import com.sun.javafx.application.LauncherImpl
import fr.acinq.eclair.gui.{FxApp, FxPreloader}
import grizzled.slf4j.Logging

/**
  * Created by PM on 25/01/2016.
  */
object JavafxBoot extends App with Logging {
  val datadir = new File(System.getProperty("eclair.datadir", System.getProperty("user.home") + "/.eclair"))

  try {
    val headless = System.getProperty("eclair.headless") != null

    if (headless) {
      new Setup(datadir).bootstrap
    } else {
      LauncherImpl.launchApplication(classOf[FxApp], classOf[FxPreloader], Array(datadir.getAbsolutePath))
    }
  } catch {
    case t: Throwable =>
      System.err.println(s"fatal error: ${t.getMessage}")
      logger.error(s"fatal error: ${t.getMessage}")
      System.exit(1)
  }
}
