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
    Try(nodeParams.networkDb.listChannels() ++ nodeParams.networkDb.listNodes() ++ nodeParams.peersDb.listPeers() ++ nodeParams.channelsDb.listChannels()) match {
      case Success(_) => {}
      case Failure(_) => throw IncompatibleDBException
    }
}

case object IncompatibleDBException extends RuntimeException("DB files are not compatible with this version of eclair.")
