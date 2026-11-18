/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair

import akka.actor.typed.ActorRef
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, Satoshi, TxId, TxOut}
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, Commitments, Origin, Upstream}
import fr.acinq.eclair.io.OpenChannelInterceptor.OpenChannelNonInitiator
import fr.acinq.eclair.payment.relay.PostRestartHtlcCleaner.IncomingHtlc
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.wire.protocol.{Error, LiquidityAds}

import java.util.UUID
import scala.concurrent.Future

/** Custom plugin parameters. */
trait PluginParams {
  /** Plugin's friendly name. */
  def name: String
}

/** Parameters for a plugin that adds support for an experimental or unofficial Bolt9 feature. */
trait CustomFeaturePlugin extends PluginParams {
  /** A set of LightningMessage tags that the plugin wants to listen to. */
  def messageTags: Set[Int]

  /** Feature bit that the plugin wants to advertise through Init message. */
  def feature: Feature

  /**
   * Whether this feature is optional or mandatory for remote peers. We recommend using [[FeatureSupport.Optional]]
   * otherwise connection will fail with nodes that don't support this feature.
   */
  def support: FeatureSupport
}

/** Parameters for a plugin that defines custom commitment transactions (or non-standard HTLCs). */
trait CustomCommitmentsPlugin extends PluginParams {
  /**
   * If we do nothing after a restart, incoming HTLCs that were committed upstream but not relayed will eventually
   * expire. If your plugin defines non-standard HTLCs, and they need to be automatically failed, they should be
   * returned by this method.
   */
  def getIncomingHtlcs(nodeParams: NodeParams, log: LoggingAdapter): Seq[IncomingHtlc]

  /**
   * Outgoing HTLC sets that are still pending may either succeed or fail: we need to watch them to properly forward the
   * result upstream to preserve channels. If you have non-standard HTLCs that may be in this situation, they should be
   * returned by this method.
   */
  def getHtlcsRelayedOut(htlcsIn: Seq[IncomingHtlc], nodeParams: NodeParams, log: LoggingAdapter): Map[Origin.Cold, Set[(ByteVector32, Long)]]
}

/**
 * Plugins implementing this trait will be called to validate the remote inputs and outputs used in interactive-tx.
 * This can be used for example to reject interactive transactions that send to specific addresses before signing them.
 */
trait ValidateInteractiveTxPlugin extends PluginParams {
  /**
   * This function will be called for every interactive-tx, before signing it. The plugin should return:
   *  - [[Future.successful(())]] to accept the transaction
   *  - [[Future.failed(...)]] to reject it: the error message will be sent to the remote node, so make sure you don't
   *    include information that should stay private.
   *
   * Note that eclair will run standard validation on its own: you don't need for example to verify that inputs exist
   * and aren't already spent. This function should only be used for custom, non-standard validation that node operators
   * want to apply.
   */
  def validateSharedTx(remoteNodeId: PublicKey, remoteInputs: Map[OutPoint, TxOut], remoteOutputs: Seq[TxOut]): Future[Unit]
}

/**
 * Plugins implementing this trait will be called to validate outgoing HTLCs before we add them to a channel. This
 * applies to payments that we send and payments that we relay (channel relay or trampoline), with one exception: HTLCs
 * that pay the fees of an on-the-fly funding are never sent to plugins, because we have already added liquidity for
 * them and our peer must use them to pay us the funding fee.
 *
 * This can be used for example to prevent outgoing payments from draining a channel's balance below a given amount.
 */
trait ValidateOutgoingHtlcPlugin extends PluginParams {
  /**
   * This function will be called by the channel actor for every outgoing HTLC, after eclair's own validation succeeded
   * and before the HTLC is sent to our peer. The plugin should return:
   *  - Right(()) to accept the HTLC
   *  - Left(reason) to reject it: the reason is only used in local logs and errors, it is never sent to our peer
   *
   * A rejected HTLC is handled like any other local error: eclair tries other channels (or other routes, for payments
   * that we send and trampoline payments that we relay) when possible, otherwise the payment fails. When relaying, the
   * error returned upstream depends on the relay type and on the other attempts that failed: for example, a channel
   * relay that is rejected on the channel requested by the sender fails with temporary_channel_failure.
   *
   * If this function throws anything, including fatal errors such as a [[LinkageError]], or returns null, the HTLC is
   * rejected and the error is logged.
   *
   * Plugins are called in order and we stop at the first one that rejects the HTLC: accepting an HTLC doesn't mean that
   * it will be added, since a later plugin may still reject it. Plugins that need to know the outcome (e.g. to keep
   * track of the HTLCs they accepted) should listen to the [[fr.acinq.eclair.channel.OutgoingHtlcAdded]] and
   * [[fr.acinq.eclair.channel.OutgoingHtlcNotAdded]] events, and match them using the channel ID, payment hash, amount
   * and expiry of the HTLC.
   *
   * This function is called synchronously by every channel actor concurrently, so it must be fast, thread-safe, and
   * must never block (no database or network calls).
   */
  def validateOutgoingHtlc(htlc: ValidateOutgoingHtlcPlugin.OutgoingHtlcInfo): Either[String, Unit]
}

object ValidateOutgoingHtlcPlugin {
  /** Origin of an outgoing HTLC that is validated by a [[ValidateOutgoingHtlcPlugin]]. */
  sealed trait OutgoingHtlcOrigin

  object OutgoingHtlcOrigin {
    /** A payment that we are sending. */
    case class Local(paymentId: UUID) extends OutgoingHtlcOrigin

    /** A payment that we are relaying, that we received from one of our channels. */
    case class ChannelRelay(incomingNodeId: PublicKey, incomingChannelId: ByteVector32, amountIn: MilliSatoshi) extends OutgoingHtlcOrigin

    /** A trampoline payment that we are relaying, that we received from one or more of our channels. */
    case class TrampolineRelay(incomingNodeIds: Set[PublicKey], amountIn: MilliSatoshi) extends OutgoingHtlcOrigin
  }

  /**
   * Outgoing HTLC that we are about to send, provided to [[ValidateOutgoingHtlcPlugin]]. This only contains stable types,
   * so that plugins don't depend on internal channel data that changes frequently between eclair versions.
   *
   * @param remoteNodeId                 node to which the HTLC will be sent.
   * @param channelId                    channel in which the HTLC will be sent.
   * @param paymentHash                  payment hash of the HTLC.
   * @param amount                       amount of the HTLC.
   * @param cltvExpiry                   expiry of the HTLC.
   * @param origin                       whether we're sending or relaying this payment.
   * @param commitments                  capacity and balance of each of the channel's active commitments, the latest
   *                                     first. There is a single one most of the time, but there are several while a
   *                                     splice (or an RBF of the funding transaction) is unconfirmed. Any of them may
   *                                     become the channel's final commitment, so plugins that check the balance should
   *                                     check every one of them: the smallest capacity and the smallest balance may come
   *                                     from different commitments.
   * @param availableBalanceForSendAfter amount that we will still be able to send in the channel after this HTLC (after
   *                                     deducting the channel reserve and fees), in the most restrictive commitment.
   */
  case class OutgoingHtlcInfo(remoteNodeId: PublicKey,
                              channelId: ByteVector32,
                              paymentHash: ByteVector32,
                              amount: MilliSatoshi,
                              cltvExpiry: CltvExpiry,
                              origin: OutgoingHtlcOrigin,
                              commitments: Seq[CommitmentBalance],
                              availableBalanceForSendAfter: MilliSatoshi)

  /**
   * @param fundingTxId       funding transaction of this commitment.
   * @param capacity          channel capacity in this commitment.
   * @param localBalanceAfter our balance in this commitment once the new HTLC and all other pending outgoing HTLCs are
   *                          fulfilled: pending incoming HTLCs aren't included and commit tx fees aren't deducted. This
   *                          is the balance that eclair uses to check whether we can afford an HTLC, before deducting the
   *                          channel reserve and fees.
   */
  case class CommitmentBalance(fundingTxId: TxId, capacity: Satoshi, localBalanceAfter: MilliSatoshi)

  object OutgoingHtlcInfo {
    /**
     * @param cmd         command that creates the outgoing HTLC.
     * @param commitments channel commitments that already include the new outgoing HTLC.
     */
    private[eclair] def fromCommand(cmd: CMD_ADD_HTLC, commitments: Commitments): OutgoingHtlcInfo = {
      val origin = cmd.origin.upstream match {
        case u: Upstream.Local => OutgoingHtlcOrigin.Local(u.id)
        case u: Upstream.Hot.Channel => OutgoingHtlcOrigin.ChannelRelay(u.receivedFrom, u.add.channelId, u.amountIn)
        case u: Upstream.Hot.Trampoline => OutgoingHtlcOrigin.TrampolineRelay(u.received.map(_.receivedFrom).toSet, u.amountIn)
      }
      val balances = commitments.active.map { c =>
        // This is the balance that we use to validate new outgoing HTLCs (see Commitment.canSendAdd).
        val remoteCommit = c.nextRemoteCommit_opt.getOrElse(c.remoteCommit)
        val localBalanceAfter = CommitmentSpec.reduce(remoteCommit.spec, commitments.changes.remoteChanges.acked, commitments.changes.localChanges.proposed).toRemote
        CommitmentBalance(c.fundingTxId, c.capacity, localBalanceAfter)
      }
      OutgoingHtlcInfo(commitments.remoteNodeId, commitments.channelId, cmd.paymentHash, cmd.amount, cmd.cltvExpiry, origin, balances, commitments.availableBalanceForSend)
    }
  }
}

// @formatter:off
trait InterceptOpenChannelCommand
case class InterceptOpenChannelReceived(replyTo: ActorRef[InterceptOpenChannelResponse], openChannelNonInitiator: OpenChannelNonInitiator) extends InterceptOpenChannelCommand {
  val remoteFundingAmount: Satoshi = openChannelNonInitiator.open.fold(_.fundingSatoshis, _.fundingAmount)
  val temporaryChannelId: ByteVector32 = openChannelNonInitiator.open.fold(_.temporaryChannelId, _.temporaryChannelId)
}

sealed trait InterceptOpenChannelResponse
case class AcceptOpenChannel(temporaryChannelId: ByteVector32, addFunding_opt: Option[LiquidityAds.AddFunding]) extends InterceptOpenChannelResponse
case class RejectOpenChannel(temporaryChannelId: ByteVector32, error: Error) extends InterceptOpenChannelResponse
// @formatter:on

trait InterceptOpenChannelPlugin extends PluginParams {
  def openChannelInterceptor: ActorRef[InterceptOpenChannelCommand]
}