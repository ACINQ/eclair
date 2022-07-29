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
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.channel._
import fr.acinq.eclair.{NodeParams, TimestampSecond}
import scodec.bits.ByteVector

import scala.concurrent.duration.FiniteDuration

object OfflineChannelsCloser {

  // @formatter:off
  sealed trait Command
  case class CloseChannels(replyTo: typed.ActorRef[CloseCommandsRegistered], channelIds: Seq[ByteVector32], forceCloseAfter_opt: Option[FiniteDuration], scriptPubKey_opt: Option[ByteVector], closingFeerates_opt: Option[ClosingFeerates]) extends Command
  case class GetPendingCommands(replyTo: typed.ActorRef[PendingCommands]) extends Command
  private case class ForceCloseChannel(channelId: ByteVector32) extends Command
  private case class WrappedChannelStateChanged(channelId: ByteVector32, state: ChannelState) extends Command
  private case class WrappedCommandResponse(channelId: ByteVector32, response: CommandResponse[CloseCommand]) extends Command
  private case class UnknownChannel(channelId: ByteVector32) extends Command

  case class CloseCommandsRegistered(status: Map[ByteVector32, ClosingStatus])
  case class PendingCommands(channels: Map[ByteVector32, ClosingParams])
  // @formatter:on

  def apply(nodeParams: NodeParams, db: OfflineCommandsDb, register: ActorRef): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelStateChanged](e => WrappedChannelStateChanged(e.channelId, e.currentState)))
        val previous = db.listPendingCloseCommands()
        new OfflineChannelsCloser(nodeParams, db, register, context, timers).start(previous)
      }
    }
  }

}

private class OfflineChannelsCloser(nodeParams: NodeParams, db: OfflineCommandsDb, register: ActorRef, context: ActorContext[OfflineChannelsCloser.Command], timers: TimerScheduler[OfflineChannelsCloser.Command]) {

  import OfflineChannelsCloser._

  private val log = context.log

  def start(previous: Map[ByteVector32, ClosingParams]): Behavior[Command] = {
    val now = TimestampSecond.now()
    previous.foreach {
      case (channelId, closingParams) =>
        closingParams.forceCloseAfter_opt match {
          case Some(forceCloseAfter) if forceCloseAfter <= now =>
            context.self ! ForceCloseChannel(channelId)
          case Some(forceCloseAfter) =>
            sendCloseCommand(channelId, closingParams)
            timers.startSingleTimer(ForceCloseChannel(channelId), forceCloseAfter - now)
          case None =>
            sendCloseCommand(channelId, closingParams)
        }
    }
    run(previous)
  }

  private def run(pending: Map[ByteVector32, ClosingParams]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case cmd: CloseChannels =>
        val closingParams = ClosingParams(cmd.forceCloseAfter_opt.map(delay => TimestampSecond.now() + delay), cmd.scriptPubKey_opt, cmd.closingFeerates_opt)
        cmd.channelIds.foreach(channelId => {
          db.addCloseCommand(channelId, closingParams)
          sendCloseCommand(channelId, closingParams)
          cmd.forceCloseAfter_opt.foreach(delay => timers.startSingleTimer(ForceCloseChannel(channelId), delay))
        })
        val pending1 = pending ++ cmd.channelIds.map(_ -> closingParams)
        cmd.replyTo ! CloseCommandsRegistered(cmd.channelIds.map(_ -> ClosingStatus.Pending).toMap)
        run(pending1)
      case ForceCloseChannel(channelId) =>
        log.info(s"channel $channelId couldn't be cooperatively closed: initiating force-close")
        sendForceCloseCommand(channelId)
        Behaviors.same
      case WrappedChannelStateChanged(channelId, state) =>
        pending.get(channelId) match {
          case Some(closingParams) => state match {
            case NORMAL =>
              log.info(s"channel $channelId is back online: initiating mutual close")
              sendCloseCommand(channelId, closingParams)
              Behaviors.same
            case CLOSED =>
              log.info(s"channel $channelId has been closed")
              db.updateCloseCommand(channelId, ClosingStatus.ChannelClosed)
              run(pending - channelId)
            case _ => Behaviors.same
          }
          case None => Behaviors.same
        }
      case WrappedCommandResponse(channelId, response) =>
        response match {
          case _: CommandSuccess[_] => log.debug(s"close command received by channel $channelId")
          case failure: CommandFailure[_, _] => log.debug(s"close command rejected by channel $channelId: ${failure.t.getMessage}")
        }
        Behaviors.same
      case UnknownChannel(channelId) =>
        log.warn(s"cannot close unknown channel $channelId")
        db.updateCloseCommand(channelId, ClosingStatus.ChannelNotFound)
        run(pending - channelId)
      case GetPendingCommands(replyTo) =>
        replyTo ! PendingCommands(pending)
        Behaviors.same
    }
  }

  private def sendCloseCommand(channelId: ByteVector32, closingParams: ClosingParams): Unit = {
    val close = CMD_CLOSE(context.messageAdapter[CommandResponse[CMD_CLOSE]](r => WrappedCommandResponse(channelId, r)).toClassic, closingParams.scriptPubKey_opt, closingParams.closingFeerates_opt)
    register ! Register.Forward(context.messageAdapter[Register.ForwardFailure[CMD_CLOSE]](r => UnknownChannel(r.fwd.channelId)), channelId, close)
  }

  private def sendForceCloseCommand(channelId: ByteVector32): Unit = {
    val forceClose = CMD_FORCECLOSE(context.messageAdapter[CommandResponse[CMD_CLOSE]](r => WrappedCommandResponse(channelId, r)).toClassic)
    register ! Register.Forward(context.messageAdapter[Register.ForwardFailure[CMD_FORCECLOSE]](r => UnknownChannel(r.fwd.channelId)), channelId, forceClose)
  }

}
