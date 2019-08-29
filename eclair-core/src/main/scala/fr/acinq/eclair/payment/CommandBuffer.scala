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

package fr.acinq.eclair.payment

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel._
import com.softwaremill.quicklens._
import scala.collection.mutable

/**
  * We store [[CMD_FULFILL_HTLC]]/[[CMD_FAIL_HTLC]]/[[CMD_FAIL_MALFORMED_HTLC]]
  * in a database because we don't want to lose preimages, or to forget to fail
  * incoming htlcs, which would lead to unwanted channel closings.
  */
class CommandBuffer(nodeParams: NodeParams, register: ActorRef) extends Actor with ActorLogging {

  import CommandBuffer._
  import nodeParams.db._

  context.system.eventStream.subscribe(self, classOf[ChannelStateChanged])

  override def receive: Receive = {
    val pendingRelays = new mutable.HashMap[ByteVector32, mutable.Set[HasHtlcIdCommand]] with mutable.MultiMap[ByteVector32, HasHtlcIdCommand]
    for ((chanId, cmd) <- pendingRelay.listPendingRelay()) pendingRelays.addBinding(chanId, cmd)
    main(pendingRelays)
  }

  def main(pendingRelays: mutable.HashMap[ByteVector32, mutable.Set[HasHtlcIdCommand]] with mutable.MultiMap[ByteVector32, HasHtlcIdCommand]): Receive = {

    case CommandSend(channelId, cmd) =>
      // save command in db
      register ! Register.Forward(channelId, cmd)
      // we also store the preimage in a db (note that this happens *after* forwarding the fulfill to the channel, so we don't add latency)
      pendingRelay.addPendingRelay(channelId, cmd)
      context become main(pendingRelays.addBinding(channelId, cmd.modify(_.commit).setTo(false)))

    case CommandAck(channelId, htlcId) =>
      //delete from db
      log.debug(s"fulfill/fail acked for channelId=$channelId htlcId=$htlcId")
      pendingRelay.removePendingRelay(channelId, htlcId)
      pendingRelays.get(channelId).flatMap(_.find(_.id == htlcId)).foreach { cmd =>
        context become main(pendingRelays.removeBinding(channelId, cmd))
      }

    case ChannelStateChanged(channel, _, _, WAIT_FOR_INIT_INTERNAL | OFFLINE | SYNCING, NORMAL | SHUTDOWN | CLOSING, d: HasCommitments) =>
      import d.channelId
      // if channel is in a state where it can have pending htlcs, we send them the fulfills/fails we know of
      pendingRelays.get(channelId) match {
        case None => ()
        case Some(cmds) =>
          log.info(s"re-sending ${cmds.size} unacked fulfills/fails to channel $channelId")
          cmds.foreach(channel ! _) // they all have commit = false
          // better to sign once instead of after each fulfill
          channel ! CMD_SIGN
      }

    case ChannelStateChanged(_, _, _, _, CLOSED, d: HasCommitments) =>
      context become main(pendingRelays -= d.channelId)
  }
}

object CommandBuffer {

  case class CommandSend(channelId: ByteVector32, cmd: HasHtlcIdCommand)

  case class CommandAck(channelId: ByteVector32, htlcId: Long)

}
