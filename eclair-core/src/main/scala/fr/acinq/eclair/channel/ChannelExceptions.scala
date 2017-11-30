package fr.acinq.eclair.channel

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.payment.Origin
import fr.acinq.eclair.wire.ChannelUpdate

/**
  * Created by PM on 11/04/2017.
  */

class ChannelException(val channelId: BinaryData, message: String) extends RuntimeException(message)
// @formatter:off
case class DebugTriggeredException             (override val channelId: BinaryData) extends ChannelException(channelId, "debug-mode triggered failure")
case class InvalidChainHash                    (override val channelId: BinaryData, local: BinaryData, remote: BinaryData) extends ChannelException(channelId, s"invalid chain_hash (local=$local remote=$remote)")
case class InvalidFundingAmount                (override val channelId: BinaryData, fundingSatoshis: Long, min: Long, max: Long) extends ChannelException(channelId, s"invalid funding_satoshis=$fundingSatoshis (min=$min max=$max)")
case class InvalidPushAmount                   (override val channelId: BinaryData, pushMsat: Long, max: Long) extends ChannelException(channelId, s"invalid push_msat=$pushMsat (max=$max)")
case class InvalidMaxAcceptedHtlcs             (override val channelId: BinaryData, maxAcceptedHtlcs: Int, max: Int) extends ChannelException(channelId, s"invalid max_accepted_htlcs=$maxAcceptedHtlcs (max=$max)")
case class InvalidDustLimit                    (override val channelId: BinaryData, dustLimitSatoshis: Long, min: Long) extends ChannelException(channelId, s"invalid dust_limit_satoshis=$dustLimitSatoshis (min=$min)")
case class ChannelReserveTooHigh               (override val channelId: BinaryData, channelReserveSatoshis: Long, reserveToFundingRatio: Double, maxReserveToFundingRatio: Double) extends ChannelException(channelId, s"channelReserveSatoshis too high: reserve=$channelReserveSatoshis fundingRatio=$reserveToFundingRatio maxFundingRatio=$maxReserveToFundingRatio")
case class ClosingInProgress                   (override val channelId: BinaryData) extends ChannelException(channelId, "cannot send new htlcs, closing in progress")
case class ClosingAlreadyInProgress            (override val channelId: BinaryData) extends ChannelException(channelId, "closing already in progress")
case class CannotCloseWithUnsignedOutgoingHtlcs(override val channelId: BinaryData) extends ChannelException(channelId, "cannot close when there are unsigned outgoing htlcs")
case class ChannelUnavailable                  (override val channelId: BinaryData) extends ChannelException(channelId, "channel is unavailable (offline or closing)")
case class InvalidFinalScript                  (override val channelId: BinaryData) extends ChannelException(channelId, "invalid final script")
case class HtlcTimedout                        (override val channelId: BinaryData) extends ChannelException(channelId, s"one or more htlcs timed out")
case class FeerateTooDifferent                 (override val channelId: BinaryData, localFeeratePerKw: Long, remoteFeeratePerKw: Long) extends ChannelException(channelId, s"local/remote feerates are too different: remoteFeeratePerKw=$remoteFeeratePerKw localFeeratePerKw=$localFeeratePerKw")
case class InvalidCloseSignature               (override val channelId: BinaryData) extends ChannelException(channelId, "cannot verify their close signature")
case class InvalidCommitmentSignature          (override val channelId: BinaryData) extends ChannelException(channelId, "invalid commitment signature")
case class ForcedLocalCommit                   (override val channelId: BinaryData, reason: String) extends ChannelException(channelId, s"forced local commit: reason")
case class UnexpectedHtlcId                    (override val channelId: BinaryData, expected: Long, actual: Long) extends ChannelException(channelId, s"unexpected htlc id: expected=$expected actual=$actual")
case class InvalidPaymentHash                  (override val channelId: BinaryData) extends ChannelException(channelId, "invalid payment hash")
case class ExpiryTooSmall                      (override val channelId: BinaryData, minimum: Long, actual: Long, blockCount: Long) extends ChannelException(channelId, s"expiry too small: required=$minimum actual=$actual blockCount=$blockCount")
case class ExpiryCannotBeInThePast             (override val channelId: BinaryData, expiry: Long, blockCount: Long) extends ChannelException(channelId, s"expiry can't be in the past: expiry=$expiry blockCount=$blockCount")
case class HtlcValueTooSmall                   (override val channelId: BinaryData, minimum: Long, actual: Long) extends ChannelException(channelId, s"htlc value too small: minimum=$minimum actual=$actual")
case class HtlcValueTooHighInFlight            (override val channelId: BinaryData, maximum: UInt64, actual: UInt64) extends ChannelException(channelId, s"in-flight htlcs hold too much value: maximum=$maximum actual=$actual")
case class TooManyAcceptedHtlcs                (override val channelId: BinaryData, maximum: Long) extends ChannelException(channelId, s"too many accepted htlcs: maximum=$maximum")
case class InsufficientFunds                   (override val channelId: BinaryData, amountMsat: Long, missingSatoshis: Long, reserveSatoshis: Long, feesSatoshis: Long) extends ChannelException(channelId, s"insufficient funds: missingSatoshis=$missingSatoshis reserveSatoshis=$reserveSatoshis fees=$feesSatoshis")
case class InvalidHtlcPreimage                 (override val channelId: BinaryData, id: Long) extends ChannelException(channelId, s"invalid htlc preimage for htlc id=$id")
case class UnknownHtlcId                       (override val channelId: BinaryData, id: Long) extends ChannelException(channelId, s"unknown htlc id=$id")
case class FundeeCannotSendUpdateFee           (override val channelId: BinaryData) extends ChannelException(channelId, s"only the funder should send update_fee messages")
case class CannotAffordFees                    (override val channelId: BinaryData, missingSatoshis: Long, reserveSatoshis: Long, feesSatoshis: Long) extends ChannelException(channelId, s"can't pay the fee: missingSatoshis=$missingSatoshis reserveSatoshis=$reserveSatoshis feesSatoshis=$feesSatoshis")
case class CannotSignWithoutChanges            (override val channelId: BinaryData) extends ChannelException(channelId, "cannot sign when there are no changes")
case class CannotSignBeforeRevocation          (override val channelId: BinaryData) extends ChannelException(channelId, "cannot sign until next revocation hash is received")
case class UnexpectedRevocation                (override val channelId: BinaryData) extends ChannelException(channelId, "received unexpected RevokeAndAck message")
case class InvalidRevocation                   (override val channelId: BinaryData) extends ChannelException(channelId, "invalid revocation")
case class CommitmentSyncError                 (override val channelId: BinaryData) extends ChannelException(channelId, "commitment sync error")
case class RevocationSyncError                 (override val channelId: BinaryData) extends ChannelException(channelId, "revocation sync error")
case class InvalidFailureCode                  (override val channelId: BinaryData) extends ChannelException(channelId, "UpdateFailMalformedHtlc message doesn't have BADONION bit set")
// @formatter:on

case class AddHtlcFailed(t: Throwable, origin: Origin, channelUpdate: Option[ChannelUpdate]) extends RuntimeException(t)
