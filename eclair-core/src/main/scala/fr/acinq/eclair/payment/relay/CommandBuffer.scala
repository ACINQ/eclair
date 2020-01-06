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

package fr.acinq.eclair.payment.relay

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel._

/**
 * We store [[CMD_FULFILL_HTLC]]/[[CMD_FAIL_HTLC]]/[[CMD_FAIL_MALFORMED_HTLC]]
 * in a database because we don't want to lose preimages, or to forget to fail
 * incoming htlcs, which would lead to unwanted channel closings.
 */
class CommandBuffer(nodeParams: NodeParams, register: ActorRef) extends Actor with ActorLogging {

  import CommandBuffer._

  val db = nodeParams.db.pendingRelay

  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])

  override def receive: Receive = {

    case CommandSend(channelId, cmd) =>
      register forward Register.Forward(channelId, cmd)
      // we store the command in a db (note that this happens *after* forwarding the command to the channel, so we don't add latency)
      db.addPendingRelay(channelId, cmd)

    case CommandAck(channelId, htlcId) =>
      log.debug(s"fulfill/fail acked for channelId=$channelId htlcId=$htlcId")
      db.removePendingRelay(channelId, htlcId)

    case ChannelStateChanged(channel, _, _, WAIT_FOR_INIT_INTERNAL | OFFLINE | SYNCING, NORMAL | SHUTDOWN | CLOSING, d: HasCommitments) =>
      db.listPendingRelay(d.channelId) match {
        case Nil => ()
        case cmds =>
          log.info(s"re-sending ${cmds.size} unacked fulfills/fails to channel ${d.channelId}")
          cmds.foreach(channel ! _) // they all have commit = false
          channel ! CMD_SIGN // so we can sign all of them at once
      }

    case _: ChannelStateChanged => () // ignored

  }

}

object CommandBuffer {

  case class CommandSend[T <: Command with HasHtlcId](channelId: ByteVector32, cmd: T)

  case class CommandAck(channelId: ByteVector32, htlcId: Long)

}
