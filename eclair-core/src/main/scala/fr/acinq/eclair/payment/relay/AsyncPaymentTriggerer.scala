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
import fr.acinq.eclair.io.PeerReadyNotifier.NotifyWhenPeerReady
import fr.acinq.eclair.io.{PeerReadyNotifier, Switchboard}
import fr.acinq.eclair.payment.relay.AsyncPaymentTriggerer.Command
import fr.acinq.eclair.{BlockHeight, Logs}

import scala.concurrent.duration.Duration

/**
 * This actor waits for an async payment receiver to become ready to receive a payment or for a block timeout to expire.
 * If the receiver of the payment is a connected peer, spawn a PeerReadyNotifier actor.
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
      val pendingPayments1 = pendingPayments.removedAll(expiredPayments)
      if (pendingPayments1.isEmpty) {
        context.stop(notifier)
        None
      } else {
        Some(PeerPayments(notifier, pendingPayments1))
      }
    }

    def trigger(): Unit = pendingPayments.foreach(e => e.replyTo ! AsyncPaymentTriggered)
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
            val notifier = context.spawn(
              Behaviors.supervise(PeerReadyNotifier(remoteNodeId, switchboard, None)).onFailure(SupervisorStrategy.restart),
              s"peer-ready-notifier-$remoteNodeId",
            )
            notifier ! NotifyWhenPeerReady(context.messageAdapter[PeerReadyNotifier.Result](WrappedPeerReadyResult))
            val peer = PeerPayments(notifier, Set(Payment(replyTo, timeout, paymentHash)))
            watching(peers + (remoteNodeId -> peer))
          case Some(peer) =>
            val peer1 = PeerPayments(peer.notifier, peer.pendingPayments + Payment(replyTo, timeout, paymentHash))
            watching(peers + (remoteNodeId -> peer1))
        }
      case WrappedCurrentBlockHeight(CurrentBlockHeight(currentBlockHeight)) =>
        val peers1 = peers.flatMap {
          case (remoteNodeId, peer) => peer.update(currentBlockHeight).map(peer1 => remoteNodeId -> peer1)
        }
        watching(peers1)
      case WrappedPeerReadyResult(PeerReadyNotifier.PeerReady(remoteNodeId, _)) =>
        // notify watcher that destination peer is ready to receive async payments; PeerReadyNotifier will stop itself
        peers.get(remoteNodeId).foreach(_.trigger())
        watching(peers - remoteNodeId)
    }
  }

}
