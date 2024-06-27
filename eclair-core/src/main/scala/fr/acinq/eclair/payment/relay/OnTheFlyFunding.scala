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
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, TxId}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.wire.protocol.LiquidityAds.PaymentDetails
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Logs, MilliSatoshi, NodeParams, TimestampMilli, ToMilliSatoshiConversion}
import scodec.bits.ByteVector

import scala.concurrent.duration.FiniteDuration

/**
 * Created by t-bast on 19/06/2024.
 */

object OnTheFlyFunding {

  case class Config(proposalTimeout: FiniteDuration)

  // @formatter:off
  sealed trait Status
  object Status {
    /** We sent will_add_htlc, but didn't fund a transaction yet. */
    case class Proposed(timer: Cancellable) extends Status
    /**
     * We signed a transaction matching the on-the-fly funding proposed. We're waiting for the liquidity to be
     * available (channel ready or splice locked) to relay the HTLCs and complete the payment.
     */
    case class Funded(channelId: ByteVector32, txId: TxId, fundingTxIndex: Long, remainingFees: MilliSatoshi) extends Status
  }
  // @formatter:on

  /** An on-the-fly funding proposal sent to our peer. */
  case class Proposal(htlc: WillAddHtlc, upstream: Upstream.Hot) {
    /** Maximum fees that can be collected from this HTLC. */
    def maxFees(htlcMinimum: MilliSatoshi): MilliSatoshi = htlc.amount - htlcMinimum

    /** Create commands to fail all upstream HTLCs. */
    def createFailureCommands(failure_opt: Option[Either[ByteVector, FailureMessage]]): Seq[(ByteVector32, CMD_FAIL_HTLC)] = upstream match {
      case _: Upstream.Local => Nil
      case u: Upstream.Hot.Channel =>
        val failure = htlc.blinding_opt match {
          case Some(_) => Right(InvalidOnionBlinding(Sphinx.hash(u.add.onionRoutingPacket)))
          case None => failure_opt.getOrElse(Right(UnknownNextPeer()))
        }
        Seq(u.add.channelId -> CMD_FAIL_HTLC(u.add.id, failure, commit = true))
      case u: Upstream.Hot.Trampoline =>
        // In the trampoline case, we currently ignore downstream failures: we should add dedicated failures to the
        // BOLTs to better handle those cases.
        val failure = failure_opt match {
          case Some(f) => f.getOrElse(TemporaryNodeFailure())
          case None => UnknownNextPeer()
        }
        u.received.map(_.add).map(add => add.channelId -> CMD_FAIL_HTLC(add.id, Right(failure), commit = true))
    }

    /** Create commands to fulfill all upstream HTLCs. */
    def createFulfillCommands(preimage: ByteVector32): Seq[(ByteVector32, CMD_FULFILL_HTLC)] = upstream match {
      case _: Upstream.Local => Nil
      case u: Upstream.Hot.Channel => Seq(u.add.channelId -> CMD_FULFILL_HTLC(u.add.id, preimage, commit = true))
      case u: Upstream.Hot.Trampoline => u.received.map(_.add).map(add => add.channelId -> CMD_FULFILL_HTLC(add.id, preimage, commit = true))
    }
  }

  /** A set of funding proposals for a given payment. */
  case class Pending(proposed: Seq[Proposal], status: Status) {
    val paymentHash = proposed.head.htlc.paymentHash
    val expiry = proposed.map(_.htlc.expiry).min

    /** Maximum fees that can be collected from this HTLC set. */
    def maxFees(htlcMinimum: MilliSatoshi): MilliSatoshi = proposed.map(_.maxFees(htlcMinimum)).sum

    /** Create commands to fail all upstream HTLCs. */
    def createFailureCommands(): Seq[(ByteVector32, CMD_FAIL_HTLC)] = proposed.flatMap(_.createFailureCommands(None))

    /** Create commands to fulfill all upstream HTLCs. */
    def createFulfillCommands(preimage: ByteVector32): Seq[(ByteVector32, CMD_FULFILL_HTLC)] = proposed.flatMap(_.createFulfillCommands(preimage))
  }

  // @formatter:off
  sealed trait ValidationResult
  object ValidationResult {
    /** The incoming channel or splice cannot pay the liquidity fees: we must reject it and fail the corresponding upstream HTLCs. */
    case class Reject(cancel: CancelOnTheFlyFunding, paymentHashes: Set[ByteVector32]) extends ValidationResult
    /** We are on-the-fly funding a channel: if we received preimages, we must fulfill the corresponding upstream HTLCs. */
    case class Accept(preimages: Set[ByteVector32]) extends ValidationResult
  }
  // @formatter:on

  /** Validate an incoming channel that may use on-the-fly funding. */
  def validateOpen(open: Either[OpenChannel, OpenDualFundedChannel], pendingOnTheFlyFunding: Map[ByteVector32, Pending]): ValidationResult = {
    open match {
      case Left(_) => ValidationResult.Accept(Set.empty)
      case Right(open) => open.requestFunding_opt match {
        case Some(requestFunding) => validate(open.temporaryChannelId, requestFunding, isChannelCreation = true, open.fundingFeerate, open.htlcMinimum, pendingOnTheFlyFunding)
        case None => ValidationResult.Accept(Set.empty)
      }
    }
  }

  /** Validate an incoming splice that may use on-the-fly funding. */
  def validateSplice(splice: SpliceInit, htlcMinimum: MilliSatoshi, pendingOnTheFlyFunding: Map[ByteVector32, Pending]): ValidationResult = {
    splice.requestFunding_opt match {
      case Some(requestFunding) => validate(splice.channelId, requestFunding, isChannelCreation = false, splice.feerate, htlcMinimum, pendingOnTheFlyFunding)
      case None => ValidationResult.Accept(Set.empty)
    }
  }

  private def validate(channelId: ByteVector32,
                       requestFunding: LiquidityAds.RequestFunding,
                       isChannelCreation: Boolean,
                       feerate: FeeratePerKw,
                       htlcMinimum: MilliSatoshi,
                       pendingOnTheFlyFunding: Map[ByteVector32, Pending]): ValidationResult = {
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
    val fees = requestFunding.fees(feerate, isChannelCreation)
    val cancelAmountTooLow = CancelOnTheFlyFunding(channelId, paymentHashes, s"requested amount is too low to relay HTLCs: ${requestFunding.requestedAmount} < $totalPaymentAmount")
    val cancelFeesTooLow = CancelOnTheFlyFunding(channelId, paymentHashes, s"htlc amount is too low to pay liquidity fees: $availableAmountForFees < ${fees.total}")
    requestFunding.paymentDetails match {
      case PaymentDetails.FromChannelBalance => ValidationResult.Accept(Set.empty)
      case _ if requestFunding.requestedAmount.toMilliSatoshi < totalPaymentAmount => ValidationResult.Reject(cancelAmountTooLow, paymentHashes.toSet)
      case _: PaymentDetails.FromChannelBalanceForFutureHtlc => ValidationResult.Accept(Set.empty)
      case _: PaymentDetails.FromFutureHtlc if availableAmountForFees < fees.total => ValidationResult.Reject(cancelFeesTooLow, paymentHashes.toSet)
      case _: PaymentDetails.FromFutureHtlc => ValidationResult.Accept(Set.empty)
      case _: PaymentDetails.FromFutureHtlcWithPreimage if availableAmountForFees < fees.total => ValidationResult.Reject(cancelFeesTooLow, paymentHashes.toSet)
      case p: PaymentDetails.FromFutureHtlcWithPreimage => ValidationResult.Accept(p.preimages.toSet)
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
      val htlcMinimum = data.commitments.params.remoteParams.htlcMinimum
      val cmdAdapter = context.messageAdapter[CommandResponse[CMD_ADD_HTLC]](WrappedCommandResponse)
      val htlcSettledAdapter = context.messageAdapter[RES_ADD_SETTLED[Origin.Hot, HtlcResult]](WrappedHtlcSettled)
      cmd.proposed.foldLeft(cmd.status.remainingFees) {
        case (remainingFees, p) =>
          // We always set the funding_fee field, even if the fee for this specific HTLC is 0.
          // This lets us detect that this HTLC is an on-the-fly funded HTLC.
          val htlcFees = LiquidityAds.FundingFee(remainingFees.min(p.maxFees(htlcMinimum)), cmd.status.txId)
          val origin = Origin.Hot(htlcSettledAdapter.toClassic, p.upstream)
          // We only sign at the end of the whole batch.
          val commit = p.htlc.id == cmd.proposed.last.htlc.id
          val add = CMD_ADD_HTLC(cmdAdapter.toClassic, p.htlc.amount - htlcFees.amount, paymentHash, p.htlc.expiry, p.htlc.finalPacket, p.htlc.blinding_opt, 1.0, Some(htlcFees), origin, commit)
          cmd.channel ! add
          remainingFees - htlcFees.amount
      }
      waitForResult(cmd.proposed.size)
    }

    private def waitForResult(remaining: Int): Behavior[Command] = {
      Behaviors.receiveMessagePartial {
        case WrappedCommandResponse(response) => response match {
          case _: CommandSuccess[_] => Behaviors.same
          case failure: CommandFailure[_, _] =>
            cmd.replyTo ! RelayFailed(paymentHash, CannotAddToChannel(failure.t))
            stopping(remaining - 1)
        }
        case WrappedHtlcSettled(settled) =>
          settled.result match {
            case fulfill: HtlcResult.Fulfill => cmd.replyTo ! RelaySuccess(channelId, paymentHash, fulfill.paymentPreimage, cmd.status.remainingFees)
            case failure: HtlcResult.Fail => cmd.replyTo ! RelayFailed(paymentHash, RemoteFailure(failure))
          }
          stopping(remaining - 1)
      }
    }

    private def stopping(remaining: Int): Behavior[Command] = {
      if (remaining == 0) {
        Behaviors.stopped
      } else {
        Behaviors.receiveMessagePartial {
          case WrappedCommandResponse(response) =>
            response match {
              case _: CommandSuccess[_] => Behaviors.same
              case _: CommandFailure[_, _] => stopping(remaining - 1)
            }
          case WrappedHtlcSettled(settled) =>
            settled.result match {
              case fulfill: HtlcResult.Fulfill => cmd.replyTo ! RelaySuccess(channelId, paymentHash, fulfill.paymentPreimage, cmd.status.remainingFees)
              case _: HtlcResult.Fail => ()
            }
            stopping(remaining - 1)
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
    private val upstreamChannel: Codec[Upstream.Hot.Channel] = (lengthDelimited(updateAddHtlcCodec) :: uint64overflow.as[TimestampMilli] :: publicKey).as[Upstream.Hot.Channel]
    private val upstreamTrampoline: Codec[Upstream.Hot.Trampoline] = listOfN(uint16, upstreamChannel).as[Upstream.Hot.Trampoline]

    val upstream: Codec[Upstream.Hot] = discriminated[Upstream.Hot].by(uint16)
      .typecase(0x00, upstreamLocal)
      .typecase(0x01, upstreamChannel)
      .typecase(0x02, upstreamTrampoline)

    val proposal: Codec[Proposal] = (("willAddHtlc" | lengthDelimited(willAddHtlcCodec)) :: ("upstream" | upstream)).as[Proposal]

    val proposals: Codec[Seq[Proposal]] = listOfN(uint16, proposal).xmap(_.toSeq, _.toList)

  }

}
