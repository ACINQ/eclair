package fr.acinq.eclair

import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

object DBCompatChecker extends Logging {

  /**
    * Tests if the DB files are compatible with the current version of eclair; throws an exception if incompatible.
    *
    * @param nodeParams
    */
  def checkDBCompatibility(nodeParams: NodeParams): Unit =
    Try(nodeParams.announcementsDb.values.size + nodeParams.peersDb.values.size + nodeParams.channelsDb.values.size) match {
      case Success(s) =>
      case Failure(f) =>
        logger.error("DB files are not compatible with this version of eclair.")
        throw IncompatibleDBException("incompatible version " + getClass.getPackage.getImplementationVersion)
    }
}

case class IncompatibleDBException(version: String) extends RuntimeException
