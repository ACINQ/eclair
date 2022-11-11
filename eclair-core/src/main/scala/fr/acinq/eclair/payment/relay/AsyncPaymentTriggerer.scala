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

package fr.acinq.eclair.payment.relay

import akka.actor.typed.ActorRef.ActorRefOps
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.BlockHeight
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.io.PeerReadyNotifier.NotifyWhenPeerReady
import fr.acinq.eclair.io.{PeerReadyNotifier, Switchboard}
import fr.acinq.eclair.payment.relay.AsyncPaymentTriggerer.Command

import scala.concurrent.duration.Duration

/**
 * This actor waits for an async payment receiver to become ready to receive a payment or for a block timeout to expire.
 * If the receiver of the payment is a connected peer, spawn a PeerReadyNotifier actor.
 * TODO: If the receiver is not a connected peer, wait for a `ReceiverReady` onion message containing the specified paymentHash.
 */

object AsyncPaymentTriggerer {
  // @formatter:off
  sealed trait Command
  case class Start(switchboard: ActorRef[Switchboard.GetPeerInfo]) extends Command
  case class Watch(replyTo: ActorRef[Result], remoteNodeId: PublicKey, paymentHash: ByteVector32, timeout: BlockHeight) extends Command
  private case class WrappedPeerReadyResult(result: PeerReadyNotifier.Result) extends Command
  private case class WrappedCurrentBlockHeight(currentBlockHeight: CurrentBlockHeight) extends Command

  sealed trait Result
  case object AsyncPaymentTriggered extends Result
  case object AsyncPaymentTimeout extends Result
  // @formatter:on

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    new AsyncPaymentTriggerer(context).initializing()
  }
}

private class AsyncPaymentTriggerer(context: ActorContext[Command]) {
  import AsyncPaymentTriggerer._

  case class Watcher(replyTo: ActorRef[Result], timeout: BlockHeight, paymentHash: ByteVector32) {
    def expired(currentBlockHeight: BlockHeight): Boolean = timeout <= currentBlockHeight
  }
  case class AsyncPaymentTrigger(notifier: ActorRef[PeerReadyNotifier.Command], watchers: Set[Watcher]) {
    def update(currentBlockHeight: BlockHeight): Option[AsyncPaymentTrigger] = {
      // notify watchers that timeout occurred before offline peer reconnected
      val expiredWatchers = watchers.filter(_.expired(currentBlockHeight))
      expiredWatchers.foreach(e => e.replyTo ! AsyncPaymentTimeout)
      // remove timed out watchers from set
      val updatedWatchers: Set[Watcher] = watchers.removedAll(expiredWatchers)
      if (updatedWatchers.isEmpty) {
        // stop notifier for offline peer when all watchers time out
        context.stop(notifier)
        None
      } else {
        Some(AsyncPaymentTrigger(notifier, updatedWatchers))
      }
    }
    def trigger(): Unit = watchers.foreach(e => e.replyTo ! AsyncPaymentTriggered)
  }

  private def initializing(): Behavior[Command] = {
    Behaviors.receiveMessage[Command] {
      case Start(switchboard) => watching(switchboard, Map())
      case m => context.log.error(s"received unhandled message ${m.getClass.getSimpleName} before Start received.")
        Behaviors.same
    }
  }

  private def watching(switchboard: ActorRef[Switchboard.GetPeerInfo], triggers: Map[PublicKey, AsyncPaymentTrigger]): Behavior[Command] = {
    val peerReadyResultAdapter = context.messageAdapter[PeerReadyNotifier.Result](WrappedPeerReadyResult)
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockHeight](WrappedCurrentBlockHeight))

    Behaviors.receiveMessage[Command] {
      case Watch(replyTo, remoteNodeId, paymentHash, timeout) =>
        triggers.get(remoteNodeId) match {
          case None =>
            // add a new trigger
            val notifier = context.spawn(Behaviors.supervise(PeerReadyNotifier(remoteNodeId, switchboard, None))
              .onFailure(SupervisorStrategy.restart), s"peer-ready-notifier-$remoteNodeId-$timeout")
            notifier ! NotifyWhenPeerReady(peerReadyResultAdapter)
            val newTrigger = AsyncPaymentTrigger(notifier, Set(Watcher(replyTo, timeout, paymentHash)))
            watching(switchboard, triggers + (remoteNodeId -> newTrigger))
          case Some(trigger) =>
            // add a new watcher to an existing trigger
            val updatedTrigger = AsyncPaymentTrigger(trigger.notifier, trigger.watchers + Watcher(replyTo, timeout, paymentHash))
            watching(switchboard, triggers + (remoteNodeId -> updatedTrigger))
        }
      case WrappedCurrentBlockHeight(CurrentBlockHeight(currentBlockHeight)) =>
        // update watchers, and remove triggers with no more active watchers
        val newTriggers = triggers.collect(m => m._2.update(currentBlockHeight) match {
          case Some(t) => m._1 -> t
        })
        watching(switchboard, newTriggers)
      case WrappedPeerReadyResult(PeerReadyNotifier.PeerReady(remoteNodeId, _)) =>
        // notify watcher that destination peer is ready to receive async payments; PeerReadyNotifier will stop itself
        triggers(remoteNodeId).trigger()
        watching(switchboard, triggers - remoteNodeId)
      case m => context.log.error(s"received unhandled message ${m.getClass.getSimpleName} after Start received.")
        Behaviors.same
    }
  }
}
