/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.plugins.offlinecommands

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.channel.{CMD_CLOSE, ClosingFeerates}
import scodec.bits.ByteVector

object OfflineChannelsCloser {

  // @formatter:off
  sealed trait Command
  case class CloseChannels(replyTo: typed.ActorRef[Response], channelIds: Seq[ByteVector32], scriptPubKey_opt: Option[ByteVector], closingFeerates_opt: Option[ClosingFeerates]) extends Command

  sealed trait ClosingStatus
  case object WaitingForPeer extends ClosingStatus

  sealed trait Response
  case class CloseCommandsRegistered(status: Map[ByteVector32, ClosingStatus]) extends Response
  // @formatter:on

  def apply(nodeParams: NodeParams, register: ActorRef): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new OfflineChannelsCloser(nodeParams, register, context, timers).run(Map.empty)
      }
    }
  }

}

private class OfflineChannelsCloser(nodeParams: NodeParams, register: ActorRef, context: ActorContext[OfflineChannelsCloser.Command], timers: TimerScheduler[OfflineChannelsCloser.Command]) {

  import OfflineChannelsCloser._

  private val log = context.log

  def run(channels: Map[ByteVector32, CMD_CLOSE]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case cmd: CloseChannels =>
        cmd.replyTo ! CloseCommandsRegistered(cmd.channelIds.map(_ -> WaitingForPeer).toMap)
        val channels1 = channels ++ cmd.channelIds.map(_ -> CMD_CLOSE(context.self.toClassic, cmd.scriptPubKey_opt, cmd.closingFeerates_opt))
        run(channels1)
    }
  }

}
