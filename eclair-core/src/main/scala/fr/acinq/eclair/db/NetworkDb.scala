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

package fr.acinq.eclair.db

import fr.acinq.bitcoin.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.router.Router.PublicChannel
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}

import java.io.Closeable
import scala.collection.immutable.SortedMap

trait NetworkDb extends Closeable {

  def addNode(n: NodeAnnouncement): Unit

  def updateNode(n: NodeAnnouncement): Unit

  def getNode(nodeId: PublicKey): Option[NodeAnnouncement]

  def removeNode(nodeId: PublicKey): Unit

  def listNodes(): Seq[NodeAnnouncement]

  def addChannel(c: ChannelAnnouncement, txid: ByteVector32, capacity: Satoshi): Unit

  def updateChannel(u: ChannelUpdate): Unit

  def removeChannel(shortChannelId: ShortChannelId) = removeChannels(Set(shortChannelId)): Unit

  def removeChannels(shortChannelIds: Iterable[ShortChannelId]): Unit

  def listChannels(): SortedMap[ShortChannelId, PublicChannel]

  def addToPruned(shortChannelIds: Iterable[ShortChannelId]): Unit

  def removeFromPruned(shortChannelId: ShortChannelId): Unit

  def isPruned(shortChannelId: ShortChannelId): Boolean

}
