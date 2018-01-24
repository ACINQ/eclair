package fr.acinq.eclair

import java.io.File

import grizzled.slf4j.Logging

/**
  * Created by PM on 25/01/2016.
  */
object Boot extends App with Logging {

  val datadir = new File(System.getProperty("eclair.datadir", System.getProperty("user.home") + "/.eclair"))

  try {
    import scala.concurrent.ExecutionContext.Implicits.global
    new Setup(datadir).bootstrap onFailure {
      case t: Throwable => onError(t)
    }
  } catch {
    case t: Throwable => onError(t)
  }

  def onError(t: Throwable): Unit = {
    val errorMsg = if (t.getMessage != null) t.getMessage else t.getClass.getSimpleName
    System.err.println(s"fatal error: $errorMsg")
    logger.error(s"fatal error: $errorMsg")
    System.exit(1)
  }
}

