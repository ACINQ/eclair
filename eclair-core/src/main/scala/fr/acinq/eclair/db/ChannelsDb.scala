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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.{DATA_CLOSED, PersistentChannelData}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.{CltvExpiry, Paginated}

trait ChannelsDb {

  def addOrUpdateChannel(data: PersistentChannelData): Unit

  def getChannel(channelId: ByteVector32): Option[PersistentChannelData]

  def updateChannelMeta(channelId: ByteVector32, event: ChannelEvent.EventType): Unit

  /**
   * Remove a channel from our DB.
   *
   * @param channelId ID of the channel that should be removed.
   * @param data_opt  if provided, closing data will be stored in a dedicated table.
   */
  def removeChannel(channelId: ByteVector32, data_opt: Option[DATA_CLOSED]): Unit

  /** Mark revoked HTLC information as obsolete. It will be removed from the DB once [[removeHtlcInfos]] is called. */
  def markHtlcInfosForRemoval(channelId: ByteVector32, beforeCommitIndex: Long): Unit

  /** Remove up to batchSize obsolete revoked HTLC information. */
  def removeHtlcInfos(batchSize: Int): Unit

  def listLocalChannels(): Seq[PersistentChannelData]

  def listClosedChannels(remoteNodeId_opt: Option[PublicKey], paginated_opt: Option[Paginated]): Seq[DATA_CLOSED]

  def addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit

  def listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): Seq[(ByteVector32, CltvExpiry)]

}
