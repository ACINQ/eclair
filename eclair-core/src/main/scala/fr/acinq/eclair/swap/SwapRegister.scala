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

package fr.acinq.eclair.swap

import akka.actor
import akka.actor.typed
import akka.actor.typed.ActorRef.ActorRefOps
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.blockchain.OnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.swap.SwapCommands._
import fr.acinq.eclair.swap.SwapData.SwapData
import fr.acinq.eclair.swap.SwapRegister.Command
import fr.acinq.eclair.swap.SwapResponses.{Response, Status, SwapOpened}
import fr.acinq.eclair.wire.protocol.{HasSwapId, SwapInRequest, SwapOutRequest}
import fr.acinq.eclair.{NodeParams, ShortChannelId, randomBytes32}
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}
import scala.reflect.ClassTag

object SwapRegister {
  // @formatter:off
  sealed trait Command
  sealed trait ReplyToMessages extends Command {
    def replyTo: ActorRef[Response]
  }

  sealed trait RegisteringMessages extends Command
  case class SwapInRequested(replyTo: ActorRef[Response], amount: Satoshi, shortChannelId: ShortChannelId) extends RegisteringMessages with ReplyToMessages
  case class SwapOutRequested(replyTo: ActorRef[Response], amount: Satoshi, shortChannelId: ShortChannelId) extends RegisteringMessages with ReplyToMessages
  case class MessageReceived(message: HasSwapId) extends RegisteringMessages
  case class SwapTerminated(swapInSenderId: SwapId) extends RegisteringMessages
  case class ListPendingSwaps(replyTo: ActorRef[Iterable[Status]]) extends RegisteringMessages
  case class CancelSwapRequested(replyTo: ActorRef[Response], swapId: String) extends RegisteringMessages with ReplyToMessages

  case class SwapId(id: String) {
    def toByteVector32: ByteVector32 = ByteVector32(ByteVector.fromValidHex(id))
  }
  // @formatter:on

  def apply(nodeParams: NodeParams, paymentInitiator: actor.ActorRef, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet, data: Set[SwapData] = Set()): Behavior[Command] = Behaviors.setup { context =>
    new SwapRegister(context, nodeParams, paymentInitiator, watcher, register, wallet, data).initializing
  }
}

private class SwapRegister(context: ActorContext[Command], nodeParams: NodeParams, paymentInitiator: actor.ActorRef, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet, data: Set[SwapData] = Set()) {
  import SwapRegister._

  private def myReceive[B <: Command : ClassTag](stateName: String)(f: B => Behavior[Command]): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case m: B => f(m)
      case m =>
        // m.replyTo ! Unhandled(stateName, m.getClass.getSimpleName)
        context.log.error(s"received unhandled message while in state $stateName of ${m.getClass.getSimpleName}")
        Behaviors.same
    }

  private def initializing: Behavior[Command] = {
    // TODO: restore SwapInReceiver from 'data'
    // TODO: restore 'data' from database
    val swaps = data.map { state =>
      val swap: typed.ActorRef[SwapCommands.SwapCommand] = context.spawn(Behaviors.supervise(SwapInSender(nodeParams, watcher, register, wallet))
        .onFailure(typed.SupervisorStrategy.restart), "SwapInSender-"+state.request.scid)
      context.watchWith(swap, SwapTerminated(SwapId(state.request.swapId)))
      swap ! RestoreSwapInSender(state)
      SwapId(state.request.swapId) -> swap.unsafeUpcast
    }.toMap
    registering(swaps)
  }

  private def registering(swaps: Map[SwapId, ActorRef[Any]]): Behavior[Command] = {
    // TODO: fail requests for swaps on a channel if one already exists for the channel; keep a list of channels with active swaps
    myReceive[RegisteringMessages]("registering") {
      case SwapInRequested(replyTo, amount, shortChannelId) =>
        val swapId = randomBytes32().toHex
        val swap = context.spawn(Behaviors.supervise(SwapInSender(nodeParams, watcher, register, wallet))
          .onFailure(SupervisorStrategy.restart), "Swap-"+shortChannelId.toHex)
        context.watchWith(swap, SwapTerminated(SwapId(swapId)))
        swap ! StartSwapInSender(amount, swapId, shortChannelId)
        replyTo ! SwapOpened(swapId)
        registering(swaps + (SwapId(swapId) -> swap.unsafeUpcast))

      case SwapOutRequested(replyTo, amount, channelId) =>
        val swapId = randomBytes32().toHex
        val swap = context.spawn(Behaviors.supervise(SwapInReceiver(nodeParams, paymentInitiator, watcher, register, wallet))
          .onFailure(SupervisorStrategy.restart), "Swap-" + channelId.toHex)
        context.watchWith(swap, SwapTerminated(SwapId(swapId)))
        swap ! StartSwapOutSender(amount, swapId, channelId)
        replyTo ! SwapOpened(swapId)
        registering(swaps + (SwapId(swapId) -> swap.unsafeUpcast))

      case MessageReceived(request: SwapInRequest) =>
        val swap = context.spawn(Behaviors.supervise(SwapInReceiver(nodeParams, paymentInitiator, watcher, register, wallet))
          .onFailure(SupervisorStrategy.restart), "Swap-"+request.scid)
        context.watchWith(swap, SwapTerminated(SwapId(request.swapId)))
        swap ! StartSwapInReceiver(request)
        registering(swaps + (SwapId(request.swapId) -> swap.unsafeUpcast))

      case MessageReceived(request: SwapOutRequest) =>
        val swap = context.spawn(Behaviors.supervise(SwapInSender(nodeParams, watcher, register, wallet))
          .onFailure(SupervisorStrategy.restart), "Swap-" + request.scid)
        context.watchWith(swap, SwapTerminated(SwapId(request.swapId)))
        swap ! StartSwapOutReceiver(request)
        registering(swaps + (SwapId(request.swapId) -> swap.unsafeUpcast))

      case MessageReceived(msg) => swaps.get(SwapId(msg.swapId)) match {
        case Some(swap) => swap ! SwapMessageReceived(msg)
          Behaviors.same
        case None => context.log.error(s"received unhandled message for swap ${msg.swapId}: $msg")
          Behaviors.same
      }

      case SwapTerminated(swapInSenderId) => registering(swaps - SwapId(swapInSenderId.id))

      case ListPendingSwaps(replyTo: ActorRef[Iterable[Status]]) =>
        // TODO: is this the best way to do this?!
        val statuses: Iterable[Future[Status]] = swaps.values.map(swap => swap.ask(ref => GetStatus(ref))(1000 milliseconds, context.system.scheduler))
        replyTo ! statuses.map(v => Await.result(v, 1000 milliseconds))
        Behaviors.same

      case CancelSwapRequested(replyTo: ActorRef[Response], swapId: String) =>
        swaps.get(SwapId(swapId)) match {
          case Some(swap) => swap ! CancelRequested(replyTo)
            Behaviors.same
          case None => context.log.error(s"could not cancel swap $swapId: does not exist")
            Behaviors.same
        }
    }
  }
}
