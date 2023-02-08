/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.crypto

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.TimestampMilli
import fr.acinq.eclair.blockchain.NewBlock
import fr.acinq.eclair.channel.ChannelSignatureReceived
import fr.acinq.eclair.io.PeerConnected
import fr.acinq.eclair.payment.ChannelPaymentRelayed
import fr.acinq.eclair.router.NodeUpdated
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt

/**
 * Created by t-bast on 20/04/2021.
 */

/**
 * This actor gathers entropy from several events and from the runtime, and regularly injects it into our [[WeakRandom]]
 * instance.
 *
 * Note that this isn't a strong entropy pool and shouldn't be trusted on its own but rather used as a safeguard against
 * failures in [[java.security.SecureRandom]].
 */
object WeakEntropyPool {

  // @formatter:off
  sealed trait Command
  private case object FlushEntropy extends Command
  private case class WrappedNewBlock(blockHash: ByteVector32) extends Command
  private case class WrappedPaymentRelayed(paymentHash: ByteVector32, relayedAt: TimestampMilli) extends Command
  private case class WrappedPeerConnected(nodeId: PublicKey) extends Command
  private case class WrappedChannelSignature(wtxid: ByteVector32) extends Command
  private case class WrappedNodeUpdated(sig: ByteVector64) extends Command
  // @formatter:on

  def apply(collector: EntropyCollector): Behavior[Command] = {
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[NewBlock](e => WrappedNewBlock(e.blockHash)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelPaymentRelayed](e => WrappedPaymentRelayed(e.paymentHash, e.timestamp)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[PeerConnected](e => WrappedPeerConnected(e.nodeId)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[NodeUpdated](e => WrappedNodeUpdated(e.ann.signature)))
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelSignatureReceived](e => WrappedChannelSignature(e.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx.wtxid)))
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(FlushEntropy, 30 seconds)
        collecting(collector, None)
      }
    }
  }

  private def collecting(collector: EntropyCollector, entropy_opt: Option[ByteVector32]): Behavior[Command] = {
    Behaviors.receiveMessage {
      case FlushEntropy =>
        entropy_opt match {
          case Some(entropy) =>
            collector.addEntropy(entropy.toArray)
            collecting(collector, None)
          case None =>
            Behaviors.same
        }

      case WrappedNewBlock(blockHash) => collecting(collector, collect(entropy_opt, blockHash ++ ByteVector.fromLong(System.currentTimeMillis())))

      case WrappedPaymentRelayed(paymentHash, relayedAt) => collecting(collector, collect(entropy_opt, paymentHash ++ ByteVector.fromLong(relayedAt.toLong)))

      case WrappedPeerConnected(nodeId) => collecting(collector, collect(entropy_opt, nodeId.value ++ ByteVector.fromLong(System.currentTimeMillis())))

      case WrappedNodeUpdated(sig) => collecting(collector, collect(entropy_opt, sig ++ ByteVector.fromLong(System.currentTimeMillis())))

      case WrappedChannelSignature(wtxid) => collecting(collector, collect(entropy_opt, wtxid ++ ByteVector.fromLong(System.currentTimeMillis())))
    }
  }

  private def collect(entropy_opt: Option[ByteVector32], additional: ByteVector): Option[ByteVector32] = {
    Some(Crypto.sha256(entropy_opt.map(_.bytes).getOrElse(ByteVector.empty) ++ additional))
  }

}
