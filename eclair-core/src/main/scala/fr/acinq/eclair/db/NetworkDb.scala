/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}

trait NetworkDb {

  def addNode(n: NodeAnnouncement)

  def updateNode(n: NodeAnnouncement)

  def removeNode(nodeId: PublicKey)

  def listNodes(): Seq[NodeAnnouncement]

  def addChannel(c: ChannelAnnouncement, txid: BinaryData, capacity: Satoshi)

  /**
    * This method removes 1 channel announcement and 2 channel updates (at both ends of the same channel)
    *
    * @param shortChannelId
    * @return
    */
  def removeChannel(shortChannelId: ShortChannelId)

  def listChannels(): Map[ChannelAnnouncement, (BinaryData, Satoshi)]

  def addChannelUpdate(u: ChannelUpdate)

  def updateChannelUpdate(u: ChannelUpdate)

  def listChannelUpdates(): Seq[ChannelUpdate]

  def addToPruned(shortChannelId: ShortChannelId)

  def removeFromPruned(shortChannelId: ShortChannelId)

  def isPruned(shortChannelId: ShortChannelId): Boolean

  def close(): Unit

}
