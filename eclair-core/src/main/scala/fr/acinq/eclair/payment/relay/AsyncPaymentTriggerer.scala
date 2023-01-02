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
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.io.PeerReadyNotifier.{NotifyWhenPeerReady, PeerUnavailable}
import fr.acinq.eclair.io.{PeerReadyNotifier, Switchboard}
import fr.acinq.eclair.payment.relay.AsyncPaymentTriggerer.Command
import fr.acinq.eclair.{BlockHeight, Logs}

/**
 * This actor waits for an async payment receiver to become ready to receive a payment or for a block timeout to expire.
 * If the receiver of the payment is a connected peer, spawn a PeerReadyNotifier actor.
 */
object AsyncPaymentTriggerer {
  // @formatter:off
  sealed trait Command
  case class Start(switchboard: ActorRef[Switchboard.GetPeerInfo]) extends Command
  case class Watch(replyTo: ActorRef[Result], remoteNodeId: PublicKey, paymentHash: ByteVector32, timeout: BlockHeight) extends Command
  case class Cancel(paymentHash: ByteVector32) extends Command
  private[relay] case class NotifierStopped(remoteNodeId: PublicKey) extends Command
  private case class WrappedPeerReadyResult(result: PeerReadyNotifier.Result) extends Command
  private case class WrappedCurrentBlockHeight(currentBlockHeight: CurrentBlockHeight) extends Command

  sealed trait Result
  case object AsyncPaymentTriggered extends Result
  case object AsyncPaymentTimeout extends Result
  case object AsyncPaymentCanceled extends Result
  // @formatter:on

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    Behaviors.withMdc(Logs.mdc(category_opt = Some(LogCategory.PAYMENT))) {
      Behaviors.receiveMessagePartial {
        case Start(switchboard) => new AsyncPaymentTriggerer(switchboard, context).start()
      }
    }
  }
}

private class AsyncPaymentTriggerer(switchboard: ActorRef[Switchboard.GetPeerInfo], context: ActorContext[Command]) {

  import AsyncPaymentTriggerer._

  case class Payment(replyTo: ActorRef[Result], timeout: BlockHeight, paymentHash: ByteVector32) {
    def expired(currentBlockHeight: BlockHeight): Boolean = timeout <= currentBlockHeight
  }

  case class PeerPayments(notifier: ActorRef[PeerReadyNotifier.Command], pendingPayments: Set[Payment]) {
    def update(currentBlockHeight: BlockHeight): Option[PeerPayments] = {
      val expiredPayments = pendingPayments.filter(_.expired(currentBlockHeight))
      expiredPayments.foreach(e => e.replyTo ! AsyncPaymentTimeout)
      updatePaymentsOrStop(pendingPayments.removedAll(expiredPayments))
    }

    def cancel(paymentHash: ByteVector32): Option[PeerPayments] = {
      val canceledPayments = pendingPayments.filter(_.paymentHash == paymentHash)
      canceledPayments.foreach(_.replyTo ! AsyncPaymentCanceled)
      updatePaymentsOrStop(pendingPayments.removedAll(canceledPayments))
    }

    private def updatePaymentsOrStop(pendingPayments: Set[Payment]): Option[PeerPayments] = {
      if (pendingPayments.isEmpty) {
        context.stop(notifier)
        None
      } else {
        Some(PeerPayments(notifier, pendingPayments))
      }
    }

    def trigger(): Unit = pendingPayments.foreach(e => e.replyTo ! AsyncPaymentTriggered)
    def cancel(): Unit = pendingPayments.foreach(e => e.replyTo ! AsyncPaymentCanceled)
  }

  def start(): Behavior[Command] = {
    context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[CurrentBlockHeight](WrappedCurrentBlockHeight))
    watching(Map.empty)
  }

  private def watching(peers: Map[PublicKey, PeerPayments]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case Watch(replyTo, remoteNodeId, paymentHash, timeout) =>
        peers.get(remoteNodeId) match {
          case None =>
            val notifier = context.spawnAnonymous(Behaviors.supervise(PeerReadyNotifier(remoteNodeId, switchboard, timeout_opt = None)).onFailure(SupervisorStrategy.stop))
            context.watchWith(notifier, NotifierStopped(remoteNodeId))
            notifier ! NotifyWhenPeerReady(context.messageAdapter[PeerReadyNotifier.Result](WrappedPeerReadyResult))
            val peer = PeerPayments(notifier, Set(Payment(replyTo, timeout, paymentHash)))
            watching(peers + (remoteNodeId -> peer))
          case Some(peer) =>
            val peer1 = PeerPayments(peer.notifier, peer.pendingPayments + Payment(replyTo, timeout, paymentHash))
            watching(peers + (remoteNodeId -> peer1))
        }
      case Cancel(paymentHash) =>
        val peers1 = peers.flatMap {
          case (remoteNodeId, peer) => peer.cancel(paymentHash).map(peer1 => remoteNodeId -> peer1)
        }
        watching(peers1)
      case WrappedCurrentBlockHeight(CurrentBlockHeight(currentBlockHeight)) =>
        val peers1 = peers.flatMap {
          case (remoteNodeId, peer) => peer.update(currentBlockHeight).map(peer1 => remoteNodeId -> peer1)
        }
        watching(peers1)
      case WrappedPeerReadyResult(result) => result match {
        case PeerReadyNotifier.PeerReady(remoteNodeId, _) =>
          // notify watcher that destination peer is ready to receive async payments; PeerReadyNotifier will stop itself
          peers.get(remoteNodeId).foreach(_.trigger())
          watching(peers - remoteNodeId)
        case PeerUnavailable(_) =>
          // only use PeerReadyNotifier to signal when the peer connects, not for timeouts
          Behaviors.same
      }
      case NotifierStopped(remoteNodeId) =>
        peers.get(remoteNodeId) match {
          case None => Behaviors.same
          case Some(peer) =>
            context.log.error(s"PeerReadyNotifier stopped unexpectedly while watching node $remoteNodeId.")
            peer.cancel()
            watching(peers - remoteNodeId)
        }
    }
  }

}
