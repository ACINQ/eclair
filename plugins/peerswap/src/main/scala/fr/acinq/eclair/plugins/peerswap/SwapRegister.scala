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

package fr.acinq.eclair.plugins.peerswap

import akka.actor
import akka.actor.typed
import akka.actor.typed.ActorRef.ActorRefOps
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import fr.acinq.bitcoin.scalacompat.Satoshi
import fr.acinq.eclair.blockchain.OnChainWallet
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.io.UnknownMessageReceived
import fr.acinq.eclair.plugins.peerswap.SwapCommands._
import fr.acinq.eclair.plugins.peerswap.SwapRegister.Command
import fr.acinq.eclair.plugins.peerswap.SwapResponses._
import fr.acinq.eclair.plugins.peerswap.db.SwapsDb
import fr.acinq.eclair.plugins.peerswap.wire.protocol.PeerSwapMessageCodecs.peerSwapMessageCodec
import fr.acinq.eclair.plugins.peerswap.wire.protocol.{HasSwapId, SwapInRequest, SwapOutRequest, SwapRequest}
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.unknownMessageCodec
import fr.acinq.eclair.{NodeParams, ShortChannelId, randomBytes32}
import scodec.Attempt

import scala.reflect.ClassTag

object SwapRegister {
  // @formatter:off
  sealed trait Command
  sealed trait ReplyToMessages extends Command {
    def replyTo: ActorRef[Response]
  }

  sealed trait SwapRequested extends ReplyToMessages {
    def replyTo: ActorRef[Response]
    def amount: Satoshi
    def shortChannelId: ShortChannelId
  }

  sealed trait RegisteringMessages extends Command
  case class WrappedUnknownMessageReceived(message: UnknownMessageReceived) extends RegisteringMessages
  case class SwapInRequested(replyTo: ActorRef[Response], amount: Satoshi, shortChannelId: ShortChannelId) extends RegisteringMessages with SwapRequested
  case class SwapOutRequested(replyTo: ActorRef[Response], amount: Satoshi, shortChannelId: ShortChannelId) extends RegisteringMessages with SwapRequested
  case class SwapTerminated(swapId: String) extends RegisteringMessages
  case class ListPendingSwaps(replyTo: ActorRef[Iterable[Status]]) extends RegisteringMessages
  case class CancelSwapRequested(replyTo: ActorRef[Response], swapId: String) extends RegisteringMessages with ReplyToMessages
  // @formatter:on

  def apply(nodeParams: NodeParams, paymentInitiator: actor.ActorRef, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet, keyManager: SwapKeyManager, db: SwapsDb, data: Set[SwapData]): Behavior[Command] = Behaviors.setup { context =>
    new SwapRegister(context, nodeParams, paymentInitiator, watcher, register, wallet, keyManager, db, data).start
  }
}

private class SwapRegister(context: ActorContext[Command], nodeParams: NodeParams, paymentInitiator: actor.ActorRef, watcher: ActorRef[ZmqWatcher.Command], register: actor.ActorRef, wallet: OnChainWallet, keyManager: SwapKeyManager, db: SwapsDb, data: Set[SwapData]) {
  import SwapRegister._

  case class SwapEntry(shortChannelId: String, swap: ActorRef[SwapCommands.SwapCommand])

  private def myReceive[B <: Command : ClassTag](stateName: String)(f: B => Behavior[Command]): Behavior[Command] =
    Behaviors.receiveMessage[Command] {
      case m: B => f(m)
      case m =>
        // m.replyTo ! Unhandled(stateName, m.getClass.getSimpleName)
        context.log.error(s"received unhandled message while in state $stateName of ${m.getClass.getSimpleName}")
        Behaviors.same
    }
  private def watchForUnknownMessage(watch: Boolean)(implicit context: ActorContext[Command]): Unit =
    if (watch) context.system.classicSystem.eventStream.subscribe(unknownMessageReceivedAdapter(context).toClassic, classOf[UnknownMessageReceived])
    else context.system.classicSystem.eventStream.unsubscribe(unknownMessageReceivedAdapter(context).toClassic, classOf[UnknownMessageReceived])
  private def unknownMessageReceivedAdapter(context: ActorContext[Command]): ActorRef[UnknownMessageReceived] = {
    context.messageAdapter[UnknownMessageReceived](WrappedUnknownMessageReceived)
  }

  private def restoreSwap(checkPoint: SwapData): (String, SwapEntry) = {
    val swap = checkPoint.swapRole match {
      case SwapRole.Maker => context.spawn(Behaviors.supervise(SwapMaker(nodeParams, watcher, register, wallet, keyManager, db)).onFailure(typed.SupervisorStrategy.stop), "SwapMaker-" + checkPoint.scid)
      case SwapRole.Taker => context.spawn(Behaviors.supervise(SwapTaker(nodeParams, paymentInitiator, watcher, register, wallet, keyManager, db)).onFailure(typed.SupervisorStrategy.stop), "SwapTaker-" + checkPoint.scid)
    }
    context.watchWith(swap, SwapTerminated(checkPoint.swapId))
    swap ! RestoreSwap(checkPoint)
    checkPoint.swapId -> SwapEntry(checkPoint.scid, swap.unsafeUpcast)
  }

  private def start: Behavior[Command] = {
    val swaps = data.map {
      restoreSwap
    }.toMap
    registering(swaps)
  }

  private def registering(swaps: Map[String, SwapEntry]): Behavior[Command] = {
    watchForUnknownMessage(watch = true)(context)
    myReceive[RegisteringMessages]("registering") {
      case swapRequested: SwapRequested if swaps.exists( p => p._2.shortChannelId == swapRequested.shortChannelId.toCoordinatesString ) =>
        // ignore swap requests for channels with ongoing swaps
        swapRequested.replyTo ! SwapExistsForChannel("", swapRequested.shortChannelId.toCoordinatesString)
        Behaviors.same
      case SwapInRequested(replyTo, amount, shortChannelId) =>
        val swapId = randomBytes32().toHex
        val swap = context.spawn(Behaviors.supervise(SwapMaker(nodeParams, watcher, register, wallet, keyManager, db)).onFailure(SupervisorStrategy.stop), "Swap-" + shortChannelId.toString)
        context.watchWith(swap, SwapTerminated(swapId))
        swap ! StartSwapInSender(amount, swapId, shortChannelId)
        replyTo ! SwapOpened(swapId)
        registering(swaps + (swapId -> SwapEntry(shortChannelId.toCoordinatesString, swap)))
      case SwapOutRequested(replyTo, amount, shortChannelId) =>
        val swapId = randomBytes32().toHex
        val swap = context.spawn(Behaviors.supervise(SwapTaker(nodeParams, paymentInitiator, watcher, register, wallet, keyManager, db)).onFailure(SupervisorStrategy.stop), "Swap-" + shortChannelId.toString)
        context.watchWith(swap, SwapTerminated(swapId))
        swap ! StartSwapOutSender(amount, swapId, shortChannelId)
        replyTo ! SwapOpened(swapId)
        registering(swaps + (swapId -> SwapEntry(shortChannelId.toCoordinatesString, swap)))
      case ListPendingSwaps(replyTo: ActorRef[Iterable[Status]]) =>
        val aggregator = context.spawn(StatusAggregator(swaps.size, replyTo), s"status-aggregator")
        swaps.values.foreach(e => e.swap ! GetStatus(aggregator))
        Behaviors.same
      case CancelSwapRequested(replyTo: ActorRef[Response], swapId: String) =>
        swaps.get(swapId) match {
          case Some(e) => e.swap ! CancelRequested(replyTo)
          case None => replyTo ! SwapNotFound(swapId)
        }
        Behaviors.same
      case SwapTerminated(swapId) =>
        db.restore().collectFirst({
          case checkPoint if checkPoint.swapId == swapId =>
            context.log.error(s"Swap $swapId stopped prematurely after saving a checkpoint, but before recording a result.")
            restoreSwap(checkPoint)
        }) match {
          case None => registering (swaps - swapId)
          case Some (restoredSwap) => registering (swaps + restoredSwap)
        }
      case WrappedUnknownMessageReceived(unknownMessageReceived) =>
        if (PeerSwapPlugin.peerSwapTags.contains(unknownMessageReceived.message.tag)) {
          peerSwapMessageCodec.decode(unknownMessageCodec.encode(unknownMessageReceived.message).require) match {
            case Attempt.Successful(decodedMessage) => decodedMessage.value match {
              case swapRequest: SwapRequest if swaps.exists(s => s._2.shortChannelId == swapRequest.scid) =>
                context.log.info(s"ignoring swap request for a channel with an active swap: $swapRequest")
                Behaviors.same
              case request: SwapInRequest =>
                val swap = context.spawn(Behaviors.supervise(SwapTaker(nodeParams, paymentInitiator, watcher, register, wallet, keyManager, db)).onFailure(SupervisorStrategy.restart), "Swap-" + request.scid)
                context.watchWith(swap, SwapTerminated(request.swapId))
                swap ! StartSwapInReceiver(request)
                registering(swaps + (request.swapId -> SwapEntry(request.scid, swap)))
              case request: SwapOutRequest =>
                val swap = context.spawn(Behaviors.supervise(SwapMaker(nodeParams, watcher, register, wallet, keyManager, db)).onFailure(SupervisorStrategy.restart), "Swap-" + request.scid)
                context.watchWith(swap, SwapTerminated(request.swapId))
                swap ! StartSwapOutReceiver(request)
                registering(swaps + (request.swapId -> SwapEntry(request.scid, swap)))
              case msg: HasSwapId => swaps.get(msg.swapId) match {
                // handle all other swap messages
                case Some(e) => e.swap ! SwapMessageReceived(msg)
                  Behaviors.same
                case None => context.log.error(s"received unhandled swap message: $msg")
                  Behaviors.same
              }
            }
            case _ => context.log.error(s"could not decode unknown message received: $unknownMessageReceived")
              Behaviors.same
          }
        } else {
          // unknown message received without a peerswap message tag
          Behaviors.same
        }
    }
  }
}
