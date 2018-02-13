package fr.acinq.eclair

import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

object DBCompatChecker extends Logging {

  /**
    * Tests if the channels data in the DB are compatible with the current version of eclair; throws an exception if incompatible.
    *
    * @param nodeParams
    */
  def checkDBCompatibility(nodeParams: NodeParams): Unit =
    Try(nodeParams.channelsDb.listChannels()) match {
      case Success(_) => {}
      case Failure(_) => throw IncompatibleDBException
    }

  /**
    * Tests if the network database is readable.
    *
    * @param nodeParams
    */
  def checkNetworkDBCompatibility(nodeParams: NodeParams): Unit =
    Try(nodeParams.networkDb.listChannels(), nodeParams.networkDb.listNodes(), nodeParams.networkDb.listChannelUpdates()) match {
      case Success(_) => {}
      case Failure(_) => throw IncompatibleNetworkDBException
    }
}
