/*
 * Copyright 2024 ACINQ SAS
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

import akka.actor.Cancellable
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import akka.actor.typed.{ActorRef, Behavior}
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, TxId}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.Monitoring.Metrics
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.wire.protocol.LiquidityAds.PaymentDetails
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Logs, MilliSatoshi, MilliSatoshiLong, NodeParams, TimestampMilli, ToMilliSatoshiConversion}

import scala.concurrent.duration.FiniteDuration

/**
 * Created by t-bast on 19/06/2024.
 */

object OnTheFlyFunding {

  case class Config(proposalTimeout: FiniteDuration) {
    // When funding a transaction using from_future_htlc, we are taking the risk that the remote node doesn't fulfill
    // the corresponding HTLCs. If we detect that our peer fails such HTLCs, we automatically disable from_future_htlc
    // to limit our exposure.
    // Note that this state is flushed when restarting: node operators should explicitly remove the from_future_htlc
    // payment type from their liquidity ads configuration if they want to keep it disabled.
    private val suspectFromFutureHtlcRelays = scala.collection.concurrent.TrieMap.empty[ByteVector32, PublicKey]

    /** We allow using from_future_htlc if we don't have any pending payment that is abusing it. */
    def isFromFutureHtlcAllowed: Boolean = suspectFromFutureHtlcRelays.isEmpty

    /** An on-the-fly payment using from_future_htlc was failed by the remote node: they may be malicious. */
    def fromFutureHtlcFailed(paymentHash: ByteVector32, remoteNodeId: PublicKey): Unit = {
      suspectFromFutureHtlcRelays.addOne(paymentHash, remoteNodeId)
      Metrics.SuspiciousFromFutureHtlcRelays.withoutTags().update(suspectFromFutureHtlcRelays.size)
    }

    /** If a fishy payment is fulfilled, we remove it from the list, which may re-enabled from_future_htlc. */
    def fromFutureHtlcFulfilled(paymentHash: ByteVector32): Unit = {
      suspectFromFutureHtlcRelays.remove(paymentHash).foreach { _ =>
        // We only need to update the metric if an entry was actually removed.
        Metrics.SuspiciousFromFutureHtlcRelays.withoutTags().update(suspectFromFutureHtlcRelays.size)
      }
    }

    /** Remove all suspect payments and re-enable from_future_htlc. */
    def enableFromFutureHtlc(): Unit = {
      val pending = suspectFromFutureHtlcRelays.toList.map(_._1)
      pending.foreach(paymentHash => suspectFromFutureHtlcRelays.remove(paymentHash))
      Metrics.SuspiciousFromFutureHtlcRelays.withoutTags().update(0)
    }
  }

  // @formatter:off
  sealed trait Status
  object Status {
    /** We sent will_add_htlc, but didn't fund a transaction yet. */
    case class Proposed(timer: Cancellable) extends Status
    /** Our peer revealed the preimage to add this payment to their fee credit for a future on-chain transaction. */
    case class AddedToFeeCredit(preimage: ByteVector32) extends Status
    /**
     * We signed a transaction matching the on-the-fly funding proposed. We're waiting for the liquidity to be
     * available (channel ready or splice locked) to relay the HTLCs and complete the payment.
     */
    case class Funded(channelId: ByteVector32, txId: TxId, fundingTxIndex: Long, remainingFees: MilliSatoshi) extends Status
  }
  // @formatter:on

  /** An on-the-fly funding proposal sent to our peer. */
  case class Proposal(htlc: WillAddHtlc, upstream: Upstream.Hot, onionSharedSecrets: Seq[Sphinx.SharedSecret]) {
    /** Maximum fees that can be collected from this HTLC. */
    def maxFees(htlcMinimum: MilliSatoshi): MilliSatoshi = htlc.amount - htlcMinimum

    /** Create commands to fail all upstream HTLCs. */
    def createFailureCommands(failure_opt: Option[FailureReason])(implicit log: LoggingAdapter): Seq[(ByteVector32, CMD_FAIL_HTLC)] = upstream match {
      case _: Upstream.Local => Nil
      case u: Upstream.Hot.Channel =>
        // Note that even in the Bolt12 case, we relay the downstream failure instead of sending back invalid_onion_blinding.
        // That's because we are directly connected to the wallet: the blinded path doesn't contain any other public nodes,
        // so we don't need to protect against probing. This allows us to return a more meaningful failure to the payer.
        val failure = failure_opt.getOrElse(FailureReason.LocalFailure(UnknownNextPeer()))
        val attribution = FailureAttributionData(htlcReceivedAt = u.receivedAt, trampolineReceivedAt_opt = None)
        Seq(u.add.channelId -> CMD_FAIL_HTLC(u.add.id, failure, Some(attribution), commit = true))
      case u: Upstream.Hot.Trampoline =>
        val failure = failure_opt match {
          case Some(f) => f match {
            case f: FailureReason.EncryptedDownstreamFailure =>
              // In the trampoline case, we currently ignore downstream failures: we should add dedicated failures to
              // the BOLTs to better handle those cases.
              Sphinx.FailurePacket.decrypt(f.packet, f.attribution_opt, onionSharedSecrets).failure match {
                case Left(Sphinx.CannotDecryptFailurePacket(_, _)) =>
                  log.warning("couldn't decrypt downstream on-the-fly funding failure")
                case Right(f) =>
                  log.warning("downstream on-the-fly funding failure: {}", f.failureMessage.message)
              }
              FailureReason.LocalFailure(TemporaryNodeFailure())
            case _: FailureReason.LocalFailure => f
          }
          case None => FailureReason.LocalFailure(UnknownNextPeer())
        }
        u.received.map(c => {
          val attribution = FailureAttributionData(htlcReceivedAt = c.receivedAt, trampolineReceivedAt_opt = Some(u.receivedAt))
          c.add.channelId -> CMD_FAIL_HTLC(c.add.id, failure, Some(attribution), commit = true)
        })
    }

    /** Create commands to fulfill all upstream HTLCs. */
    def createFulfillCommands(preimage: ByteVector32): Seq[(ByteVector32, CMD_FULFILL_HTLC)] = upstream match {
      case _: Upstream.Local => Nil
      case u: Upstream.Hot.Channel => Seq(u.add.channelId -> CMD_FULFILL_HTLC(u.add.id, preimage, Some(FulfillAttributionData(htlcReceivedAt = u.receivedAt, trampolineReceivedAt_opt = None, downstreamAttribution_opt = None)), commit = true))
      case u: Upstream.Hot.Trampoline => u.received.map(c => {
        val attribution = FulfillAttributionData(htlcReceivedAt = c.receivedAt, trampolineReceivedAt_opt = Some(u.receivedAt), downstreamAttribution_opt = None)
        c.add.channelId -> CMD_FULFILL_HTLC(c.add.id, preimage, Some(attribution), commit = true)
      })
    }
  }

  /** A set of funding proposals for a given payment. */
  case class Pending(proposed: Seq[Proposal], status: Status) {
    val paymentHash = proposed.head.htlc.paymentHash
    val expiry = proposed.map(_.htlc.expiry).min
    val amountOut = proposed.map(_.htlc.amount).sum

    /** Maximum fees that can be collected from this HTLC set. */
    def maxFees(htlcMinimum: MilliSatoshi): MilliSatoshi = proposed.map(_.maxFees(htlcMinimum)).sum

    /** Create commands to fail all upstream HTLCs. */
    def createFailureCommands(implicit log: LoggingAdapter): Seq[(ByteVector32, CMD_FAIL_HTLC)] = proposed.flatMap(_.createFailureCommands(None))

    /** Create commands to fulfill all upstream HTLCs. */
    def createFulfillCommands(preimage: ByteVector32): Seq[(ByteVector32, CMD_FULFILL_HTLC)] = proposed.flatMap(_.createFulfillCommands(preimage))
  }

  // @formatter:off
  sealed trait ValidationResult
  object ValidationResult {
    /** The incoming channel or splice cannot pay the liquidity fees: we must reject it and fail the corresponding upstream HTLCs. */
    case class Reject(cancel: CancelOnTheFlyFunding, paymentHashes: Set[ByteVector32]) extends ValidationResult
    /** We are on-the-fly funding a channel: if we received preimages, we must fulfill the corresponding upstream HTLCs. */
    case class Accept(preimages: Set[ByteVector32], useFeeCredit_opt: Option[MilliSatoshi]) extends ValidationResult
  }
  // @formatter:on

  /** Validate an incoming channel that may use on-the-fly funding. */
  def validateOpen(cfg: Config, open: Either[OpenChannel, OpenDualFundedChannel], pendingOnTheFlyFunding: Map[ByteVector32, Pending], feeCredit: MilliSatoshi): ValidationResult = {
    open match {
      case Left(_) => ValidationResult.Accept(Set.empty, None)
      case Right(open) => open.requestFunding_opt match {
        case Some(requestFunding) => validate(cfg, open.temporaryChannelId, requestFunding, isChannelCreation = true, open.fundingFeerate, open.htlcMinimum, pendingOnTheFlyFunding, feeCredit)
        case None => ValidationResult.Accept(Set.empty, None)
      }
    }
  }

  /** Validate an incoming splice that may use on-the-fly funding. */
  def validateSplice(cfg: Config, splice: SpliceInit, htlcMinimum: MilliSatoshi, pendingOnTheFlyFunding: Map[ByteVector32, Pending], feeCredit: MilliSatoshi): ValidationResult = {
    splice.requestFunding_opt match {
      case Some(requestFunding) => validate(cfg, splice.channelId, requestFunding, isChannelCreation = false, splice.feerate, htlcMinimum, pendingOnTheFlyFunding, feeCredit)
      case None => ValidationResult.Accept(Set.empty, None)
    }
  }

  private def validate(cfg: Config,
                       channelId: ByteVector32,
                       requestFunding: LiquidityAds.RequestFunding,
                       isChannelCreation: Boolean,
                       feerate: FeeratePerKw,
                       htlcMinimum: MilliSatoshi,
                       pendingOnTheFlyFunding: Map[ByteVector32, Pending],
                       feeCredit: MilliSatoshi): ValidationResult = {
    val paymentHashes = requestFunding.paymentDetails match {
      case PaymentDetails.FromChannelBalance => Nil
      case PaymentDetails.FromChannelBalanceForFutureHtlc(paymentHashes) => paymentHashes
      case PaymentDetails.FromFutureHtlc(paymentHashes) => paymentHashes
      case PaymentDetails.FromFutureHtlcWithPreimage(preimages) => preimages.map(preimage => Crypto.sha256(preimage))
    }
    val pending = paymentHashes.flatMap(paymentHash => pendingOnTheFlyFunding.get(paymentHash)).filter(_.status.isInstanceOf[OnTheFlyFunding.Status.Proposed])
    val totalPaymentAmount = pending.flatMap(_.proposed.map(_.htlc.amount)).sum
    // We will deduce fees from HTLCs: we check that the amount is large enough to cover the fees.
    val availableAmountForFees = pending.map(_.maxFees(htlcMinimum)).sum
    val (feesOwed, useFeeCredit_opt) = if (feeCredit > 0.msat) {
      // We prioritize using our peer's fee credit if they have some available.
      val fees = requestFunding.fees(feerate, isChannelCreation).total.toMilliSatoshi
      val useFeeCredit = feeCredit.min(fees)
      (fees - useFeeCredit, Some(useFeeCredit))
    } else {
      (requestFunding.fees(feerate, isChannelCreation).total.toMilliSatoshi, None)
    }
    val cancelAmountTooLow = CancelOnTheFlyFunding(channelId, paymentHashes, s"requested amount is too low to relay HTLCs: ${requestFunding.requestedAmount} < $totalPaymentAmount")
    val cancelFeesTooLow = CancelOnTheFlyFunding(channelId, paymentHashes, s"htlc amount is too low to pay liquidity fees: $availableAmountForFees < $feesOwed")
    val cancelDisabled = CancelOnTheFlyFunding(channelId, paymentHashes, "payments paid with future HTLCs are currently disabled")
    requestFunding.paymentDetails match {
      case PaymentDetails.FromChannelBalance => ValidationResult.Accept(Set.empty, None)
      case _ if requestFunding.requestedAmount.toMilliSatoshi < totalPaymentAmount => ValidationResult.Reject(cancelAmountTooLow, paymentHashes.toSet)
      case _: PaymentDetails.FromChannelBalanceForFutureHtlc => ValidationResult.Accept(Set.empty, useFeeCredit_opt)
      case _: PaymentDetails.FromFutureHtlc if !cfg.isFromFutureHtlcAllowed => ValidationResult.Reject(cancelDisabled, paymentHashes.toSet)
      case _: PaymentDetails.FromFutureHtlc if availableAmountForFees < feesOwed => ValidationResult.Reject(cancelFeesTooLow, paymentHashes.toSet)
      case _: PaymentDetails.FromFutureHtlc => ValidationResult.Accept(Set.empty, useFeeCredit_opt)
      case _: PaymentDetails.FromFutureHtlcWithPreimage if availableAmountForFees < feesOwed => ValidationResult.Reject(cancelFeesTooLow, paymentHashes.toSet)
      case p: PaymentDetails.FromFutureHtlcWithPreimage => ValidationResult.Accept(p.preimages.toSet, useFeeCredit_opt)
    }
  }

  /**
   * This actor relays HTLCs that were proposed with [[WillAddHtlc]] once funding is complete.
   * It verifies that this payment was not previously relayed, to protect against over-paying and paying multiple times.
   */
  object PaymentRelayer {
    // @formatter:off
    sealed trait Command
    case class TryRelay(replyTo: ActorRef[RelayResult], channel: ActorRef[fr.acinq.eclair.channel.Command], proposed: Seq[Proposal], status: Status.Funded) extends Command
    private case class WrappedChannelInfo(state: ChannelState, data: ChannelData) extends Command
    private case class WrappedCommandResponse(response: CommandResponse[CMD_ADD_HTLC]) extends Command
    private case class WrappedHtlcSettled(result: RES_ADD_SETTLED[Origin.Hot, HtlcResult]) extends Command

    sealed trait RelayResult
    case class RelaySuccess(channelId: ByteVector32, paymentHash: ByteVector32, preimage: ByteVector32, fees: MilliSatoshi) extends RelayResult
    case class RelayFailed(paymentHash: ByteVector32, failure: RelayFailure) extends RelayResult

    sealed trait RelayFailure
    case object ExpiryTooClose extends RelayFailure { override def toString: String = "htlcs are too close to expiry to be relayed" }
    case class ChannelNotAvailable(state: ChannelState) extends RelayFailure { override def toString: String = s"channel is not ready for payments (state=${state.toString})" }
    case class CannotAddToChannel(t: Throwable) extends RelayFailure { override def toString: String = s"could not relay on-the-fly HTLC: ${t.getMessage}" }
    case class RemoteFailure(failure: HtlcResult.Fail) extends RelayFailure { override def toString: String = s"relayed on-the-fly HTLC was failed: ${failure.getClass.getSimpleName}" }
    // @formatter:on

    def apply(nodeParams: NodeParams, remoteNodeId: PublicKey, channelId: ByteVector32, paymentHash: ByteVector32): Behavior[Command] =
      Behaviors.setup { context =>
        Behaviors.withMdc(Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), remoteNodeId_opt = Some(remoteNodeId), channelId_opt = Some(channelId), paymentHash_opt = Some(paymentHash))) {
          Behaviors.receiveMessagePartial {
            case cmd: TryRelay => new PaymentRelayer(nodeParams, channelId, paymentHash, cmd, context).start()
          }
        }
      }
  }

  class PaymentRelayer private(nodeParams: NodeParams, channelId: ByteVector32, paymentHash: ByteVector32, cmd: PaymentRelayer.TryRelay, context: ActorContext[PaymentRelayer.Command]) {

    import PaymentRelayer._

    def start(): Behavior[Command] = {
      if (cmd.proposed.exists(_.htlc.expiry.blockHeight <= nodeParams.currentBlockHeight + 12)) {
        // The funding proposal expires soon: we shouldn't relay HTLCs to avoid risking a force-close.
        cmd.replyTo ! RelayFailed(paymentHash, ExpiryTooClose)
        Behaviors.stopped
      } else {
        checkChannelState()
      }
    }

    private def checkChannelState(): Behavior[Command] = {
      cmd.channel ! CMD_GET_CHANNEL_INFO(context.messageAdapter[RES_GET_CHANNEL_INFO](r => WrappedChannelInfo(r.state, r.data)))
      Behaviors.receiveMessagePartial {
        case WrappedChannelInfo(_, data: DATA_NORMAL) if paymentAlreadyRelayed(paymentHash, data) =>
          context.log.warn("payment is already being relayed, waiting for it to be settled")
          Behaviors.stopped
        case WrappedChannelInfo(_, data: DATA_NORMAL) =>
          nodeParams.db.liquidity.getOnTheFlyFundingPreimage(paymentHash) match {
            case Some(preimage) =>
              // We have already received the preimage for that payment, but we probably restarted before removing the
              // on-the-fly funding proposal from our DB. We must not relay the payment again, otherwise we will pay
              // the next node twice.
              cmd.replyTo ! RelaySuccess(channelId, paymentHash, preimage, cmd.status.remainingFees)
              Behaviors.stopped
            case None => relay(data)
          }
        case WrappedChannelInfo(state, _) =>
          cmd.replyTo ! RelayFailed(paymentHash, ChannelNotAvailable(state))
          Behaviors.stopped
      }
    }

    private def paymentAlreadyRelayed(paymentHash: ByteVector32, data: DATA_NORMAL): Boolean = {
      data.commitments.changes.localChanges.all.exists {
        case add: UpdateAddHtlc => add.paymentHash == paymentHash && add.fundingFee_opt.nonEmpty
        case _ => false
      }
    }

    private def relay(data: DATA_NORMAL): Behavior[Command] = {
      context.log.debug("relaying {} on-the-fly HTLCs that have been funded", cmd.proposed.size)
      val htlcMinimum = data.commitments.latest.remoteCommitParams.htlcMinimum
      val cmdAdapter = context.messageAdapter[CommandResponse[CMD_ADD_HTLC]](WrappedCommandResponse)
      val htlcSettledAdapter = context.messageAdapter[RES_ADD_SETTLED[Origin.Hot, HtlcResult]](WrappedHtlcSettled)
      cmd.proposed.foldLeft(cmd.status.remainingFees) {
        case (remainingFees, p) =>
          // We always set the funding_fee field, even if the fee for this specific HTLC is 0.
          // This lets us detect that this HTLC is an on-the-fly funded HTLC.
          val htlcFees = LiquidityAds.FundingFee(remainingFees.min(p.maxFees(htlcMinimum)), cmd.status.txId)
          val origin = Origin.Hot(htlcSettledAdapter.toClassic, p.upstream)
          val add = CMD_ADD_HTLC(cmdAdapter.toClassic, p.htlc.amount - htlcFees.amount, paymentHash, p.htlc.expiry, p.htlc.finalPacket, p.htlc.pathKey_opt, Reputation.Score.max, Some(htlcFees), origin, commit = true)
          cmd.channel ! add
          remainingFees - htlcFees.amount
      }
      Behaviors.withStash(cmd.proposed.size) { stash =>
        waitForCommandResult(stash, cmd.proposed.size, htlcSent = 0)
      }
    }

    private def waitForCommandResult(stash: StashBuffer[Command], remaining: Int, htlcSent: Int): Behavior[Command] = {
      if (remaining == 0) {
        stash.unstashAll(waitForSettlement(htlcSent))
      } else {
        Behaviors.receiveMessagePartial {
          case WrappedCommandResponse(response) => response match {
            case _: CommandSuccess[_] =>
              waitForCommandResult(stash, remaining - 1, htlcSent + 1)
            case failure: CommandFailure[_, _] =>
              cmd.replyTo ! RelayFailed(paymentHash, CannotAddToChannel(failure.t))
              waitForCommandResult(stash, remaining - 1, htlcSent)
          }
          case msg: WrappedHtlcSettled =>
            stash.stash(msg)
            Behaviors.same
        }
      }
    }

    private def waitForSettlement(remaining: Int): Behavior[Command] = {
      if (remaining == 0) {
        Behaviors.stopped
      } else {
        Behaviors.receiveMessagePartial {
          case WrappedHtlcSettled(settled) =>
            settled.result match {
              case fulfill: HtlcResult.Fulfill => cmd.replyTo ! RelaySuccess(channelId, paymentHash, fulfill.paymentPreimage, cmd.status.remainingFees)
              case fail: HtlcResult.Fail => cmd.replyTo ! RelayFailed(paymentHash, RemoteFailure(fail))
            }
            waitForSettlement(remaining - 1)
        }
      }
    }

  }

  object Codecs {

    import fr.acinq.eclair.wire.protocol.CommonCodecs._
    import fr.acinq.eclair.wire.protocol.LightningMessageCodecs._
    import scodec.Codec
    import scodec.codecs._

    private val upstreamLocal: Codec[Upstream.Local] = uuid.as[Upstream.Local]
    private val upstreamChannel: Codec[Upstream.Hot.Channel] = (lengthDelimited(updateAddHtlcCodec) :: uint64overflow.as[TimestampMilli] :: publicKey :: double).as[Upstream.Hot.Channel]
    private val upstreamTrampoline: Codec[Upstream.Hot.Trampoline] = listOfN(uint16, upstreamChannel).as[Upstream.Hot.Trampoline]
    private val legacyUpstreamChannel: Codec[Upstream.Hot.Channel] = (lengthDelimited(updateAddHtlcCodec) :: uint64overflow.as[TimestampMilli] :: publicKey :: provide(0.0)).as[Upstream.Hot.Channel]
    private val legacyUpstreamTrampoline: Codec[Upstream.Hot.Trampoline] = listOfN(uint16, legacyUpstreamChannel).as[Upstream.Hot.Trampoline]

    val upstream: Codec[Upstream.Hot] = discriminated[Upstream.Hot].by(uint16)
      .typecase(0x00, upstreamLocal)
      .typecase(0x03, upstreamChannel)
      .typecase(0x04, upstreamTrampoline)
      .typecase(0x01, legacyUpstreamChannel)
      .typecase(0x02, legacyUpstreamTrampoline)

    val proposal: Codec[Proposal] = (
      ("willAddHtlc" | lengthDelimited(willAddHtlcCodec)) ::
        ("upstream" | upstream) ::
        // We don't need to persist the onion shared secrets: we only persist on-the-fly funding proposals once they
        // have been funded, at which point we will ignore downstream failures.
        ("onionSharedSecrets" | provide(Seq.empty[Sphinx.SharedSecret]))
      ).as[Proposal]

    val proposals: Codec[Seq[Proposal]] = listOfN(uint16, proposal).xmap(_.toSeq, _.toList)

  }

}
