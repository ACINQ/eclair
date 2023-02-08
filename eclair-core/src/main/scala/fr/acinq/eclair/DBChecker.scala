/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair

import fr.acinq.eclair.channel.PersistentChannelData
import grizzled.slf4j.Logging

import scala.util.{Failure, Success, Try}

object DBChecker extends Logging {

  /**
   * Tests if the channels data in the DB is valid (and throws an exception if not):
   * - it is compatible with the current version of eclair
   * - channel keys can be re-generated from the channel seed
   */
  def checkChannelsDB(nodeParams: NodeParams): Seq[PersistentChannelData] = {
    Try(nodeParams.db.channels.listLocalChannels()) match {
      case Success(channels) =>
        channels.foreach(data => if (!data.commitments.validateSeed(nodeParams.channelKeyManager)) throw InvalidChannelSeedException(data.channelId))
        channels
      case Failure(_) => throw IncompatibleDBException
    }
  }

  /**
   * Tests if the network database is readable.
   */
  def checkNetworkDB(nodeParams: NodeParams): Unit =
    Try(nodeParams.db.network.listChannels(), nodeParams.db.network.listNodes()) match {
      case Success(_) => ()
      case Failure(_) => throw IncompatibleNetworkDBException
    }
}
