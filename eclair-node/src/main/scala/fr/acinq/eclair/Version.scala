package fr.acinq.eclair

import scala.util.{Failure, Success, Try}

/**
  * Contains the application's version
  */
object Version {

  /**
    * Git commit id found in the packaged manifest file. This id is set when the package is built (see pom.xml)
    * If the commit id attribute could be found or if no manifest file was found, returns "Unknown"
    */
  val commit = Try {
    Option(Thread.currentThread.getContextClassLoader.getResourceAsStream("META-INF/MANIFEST.MF")) match {
      case Some(res) =>
        val manifest = new java.util.jar.Manifest
        manifest.read(res)
        Option(manifest.getMainAttributes.getValue("Commit-Id")).getOrElse("Unknown")
      case None =>
    }
  } match {
    case Success(s) => s
    case Failure(t) => "Unknown"
  }
  /**
    * Version of the maven project found in the packaged manifest file.
    * If no meaningful version could be built or if no manifest file was found, returns "Unknown"
    */
  val version = Option(getClass.getPackage.getImplementationVersion).getOrElse("Unknown")
}
