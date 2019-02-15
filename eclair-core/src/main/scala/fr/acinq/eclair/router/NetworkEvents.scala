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

package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}

/**
  * Created by PM on 02/02/2017.
  */
trait NetworkEvent

case class NodesDiscovered(ann: Iterable[NodeAnnouncement]) extends NetworkEvent

case class NodeUpdated(ann: NodeAnnouncement) extends NetworkEvent

case class NodeLost(nodeId: PublicKey) extends NetworkEvent

case class SingleChannelDiscovered(ann: ChannelAnnouncement, capacity: Satoshi)

case class ChannelsDiscovered(c: Iterable[SingleChannelDiscovered]) extends NetworkEvent

case class ChannelLost(shortChannelId: ShortChannelId) extends NetworkEvent

case class ChannelUpdatesReceived(ann: Iterable[ChannelUpdate]) extends NetworkEvent

case class SyncProgress(progress: Double) extends NetworkEvent
