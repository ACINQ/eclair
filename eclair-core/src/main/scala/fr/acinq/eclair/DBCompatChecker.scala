package fr.acinq.eclair

import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

object DBCompatChecker extends Logging {

  /**
    * Tests if the DB files are compatible with the current version of eclair. Returns true if compatible, false otherwise.
    *
    * @param nodeParams
    */
  def isDBCompatible(nodeParams: NodeParams): Boolean = Try(nodeParams.announcementsDb.values.size + nodeParams.peersDb.values.size + nodeParams.channelsDb.values.size) match {
    case Success(s) => true
    case Failure(f) =>
      logger.info("DB files are not compatible with this version of eclair")
      false
  }
}
