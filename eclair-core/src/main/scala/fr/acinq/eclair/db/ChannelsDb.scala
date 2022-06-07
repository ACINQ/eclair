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
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.channel.PersistentChannelData
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent

trait ChannelsDb {

  def addOrUpdateChannel(data: PersistentChannelData): Unit

  def getChannel(channelId: ByteVector32): Option[PersistentChannelData]

  def updateChannelMeta(channelId: ByteVector32, event: ChannelEvent.EventType): Unit

  def removeChannel(channelId: ByteVector32): Unit

  def listLocalChannels(): Seq[PersistentChannelData]

  def addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit

  def listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): Seq[(ByteVector32, CltvExpiry)]
}
