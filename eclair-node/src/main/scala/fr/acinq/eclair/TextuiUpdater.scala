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

package fr.acinq.eclair

import akka.actor.Actor
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.channel._
import fr.acinq.eclair.payment.PaymentEvent
import fr.acinq.eclair.router.NetworkEvent

/**
  * Created by PM on 31/05/2017.
  */
class TextuiUpdater(textui: Textui) extends Actor {
  context.system.eventStream.subscribe(self, classOf[ChannelEvent])
  context.system.eventStream.subscribe(self, classOf[NetworkEvent])
  context.system.eventStream.subscribe(self, classOf[PaymentEvent])

  override def receive: Receive = {
    case ChannelCreated(channel, _, remoteNodeId, _, temporaryChannelId) =>
      textui.addChannel(channel, temporaryChannelId, remoteNodeId, WAIT_FOR_INIT_INTERNAL, Satoshi(0), Satoshi(1))

    case ChannelRestored(channel, _, remoteNodeId, _, channelId, data) =>
      textui.addChannel(channel, channelId, remoteNodeId, OFFLINE, Satoshi(33), Satoshi(100))

    case ChannelStateChanged(channel, _, _, _, state, _) =>
      textui.updateState(channel, state)
  }
}
