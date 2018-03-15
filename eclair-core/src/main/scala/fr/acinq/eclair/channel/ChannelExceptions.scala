package fr.acinq.eclair.channel

import fr.acinq.bitcoin.{BinaryData, Transaction}
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.payment.{Origin, Relayed}
import fr.acinq.eclair.wire.{ChannelUpdate, UpdateAddHtlc}

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
case class ToSelfDelayTooHigh                  (override val channelId: BinaryData, toSelfDelay: Int, max: Int) extends ChannelException(channelId, s"unreasonable to_self_delay=$toSelfDelay (max=$max)")
case class ChannelReserveTooHigh               (override val channelId: BinaryData, channelReserveSatoshis: Long, reserveToFundingRatio: Double, maxReserveToFundingRatio: Double) extends ChannelException(channelId, s"channelReserveSatoshis too high: reserve=$channelReserveSatoshis fundingRatio=$reserveToFundingRatio maxFundingRatio=$maxReserveToFundingRatio")
case class ChannelFundingError                 (override val channelId: BinaryData) extends ChannelException(channelId, "channel funding error")
case class NoMoreHtlcsClosingInProgress        (override val channelId: BinaryData) extends ChannelException(channelId, "cannot send new htlcs, closing in progress")
case class ClosingAlreadyInProgress            (override val channelId: BinaryData) extends ChannelException(channelId, "closing already in progress")
case class CannotCloseInThisState              (override val channelId: BinaryData, state: State) extends ChannelException(channelId, s"cannot close in state=$state")
case class CannotCloseWithUnsignedOutgoingHtlcs(override val channelId: BinaryData) extends ChannelException(channelId, "cannot close when there are unsigned outgoing htlcs")
case class ChannelUnavailable                  (override val channelId: BinaryData) extends ChannelException(channelId, "channel is unavailable (offline or closing)")
case class InvalidFinalScript                  (override val channelId: BinaryData) extends ChannelException(channelId, "invalid final script")
case class FundingTxTimedout                   (override val channelId: BinaryData) extends ChannelException(channelId, "funding tx timed out")
case class FundingTxSpent                      (override val channelId: BinaryData, spendingTx: Transaction) extends ChannelException(channelId, s"funding tx has been spent by txid=${spendingTx.txid}")
case class HtlcTimedout                        (override val channelId: BinaryData) extends ChannelException(channelId, "one or more htlcs timed out")
case class FeerateTooDifferent                 (override val channelId: BinaryData, localFeeratePerKw: Long, remoteFeeratePerKw: Long) extends ChannelException(channelId, s"local/remote feerates are too different: remoteFeeratePerKw=$remoteFeeratePerKw localFeeratePerKw=$localFeeratePerKw")
case class InvalidCommitmentSignature          (override val channelId: BinaryData, tx: Transaction) extends ChannelException(channelId, s"invalid commitment signature: tx=$tx")
case class InvalidHtlcSignature                (override val channelId: BinaryData, tx: Transaction) extends ChannelException(channelId, s"invalid htlc signature: tx=$tx")
case class InvalidCloseSignature               (override val channelId: BinaryData, tx: Transaction) extends ChannelException(channelId, s"invalid close signature: tx=$tx")
case class InvalidCloseFee                     (override val channelId: BinaryData, feeSatoshi: Long) extends ChannelException(channelId, s"invalid close fee: fee_satoshis=$feeSatoshi")
case class HtlcSigCountMismatch                (override val channelId: BinaryData, expected: Int, actual: Int) extends ChannelException(channelId, s"htlc sig count mismatch: expected=$expected actual: $actual")
case class ForcedLocalCommit                   (override val channelId: BinaryData, reason: String) extends ChannelException(channelId, s"forced local commit: reason")
case class UnexpectedHtlcId                    (override val channelId: BinaryData, expected: Long, actual: Long) extends ChannelException(channelId, s"unexpected htlc id: expected=$expected actual=$actual")
case class InvalidPaymentHash                  (override val channelId: BinaryData) extends ChannelException(channelId, "invalid payment hash")
case class ExpiryTooSmall                      (override val channelId: BinaryData, minimum: Long, actual: Long, blockCount: Long) extends ChannelException(channelId, s"expiry too small: minimum=$minimum actual=$actual blockCount=$blockCount")
case class ExpiryTooBig                        (override val channelId: BinaryData, maximum: Long, actual: Long, blockCount: Long) extends ChannelException(channelId, s"expiry too big: maximum=$maximum actual=$actual blockCount=$blockCount")
case class ExpiryCannotBeInThePast             (override val channelId: BinaryData, expiry: Long, blockCount: Long) extends ChannelException(channelId, s"expiry can't be in the past: expiry=$expiry blockCount=$blockCount")
case class HtlcValueTooSmall                   (override val channelId: BinaryData, minimum: Long, actual: Long) extends ChannelException(channelId, s"htlc value too small: minimum=$minimum actual=$actual")
case class HtlcValueTooHighInFlight            (override val channelId: BinaryData, maximum: UInt64, actual: UInt64) extends ChannelException(channelId, s"in-flight htlcs hold too much value: maximum=$maximum actual=$actual")
case class TooManyAcceptedHtlcs                (override val channelId: BinaryData, maximum: Long) extends ChannelException(channelId, s"too many accepted htlcs: maximum=$maximum")
case class InsufficientFunds                   (override val channelId: BinaryData, amountMsat: Long, missingSatoshis: Long, reserveSatoshis: Long, feesSatoshis: Long) extends ChannelException(channelId, s"insufficient funds: missingSatoshis=$missingSatoshis reserveSatoshis=$reserveSatoshis fees=$feesSatoshis")
case class InvalidHtlcPreimage                 (override val channelId: BinaryData, id: Long) extends ChannelException(channelId, s"invalid htlc preimage for htlc id=$id")
case class UnknownHtlcId                       (override val channelId: BinaryData, id: Long) extends ChannelException(channelId, s"unknown htlc id=$id")
case class CannotExtractSharedSecret           (override val channelId: BinaryData, htlc: UpdateAddHtlc) extends ChannelException(channelId, s"can't extract shared secret: paymentHash=${htlc.paymentHash} onion=${htlc.onionRoutingPacket}")
case class FundeeCannotSendUpdateFee           (override val channelId: BinaryData) extends ChannelException(channelId, s"only the funder should send update_fee messages")
case class CannotAffordFees                    (override val channelId: BinaryData, missingSatoshis: Long, reserveSatoshis: Long, feesSatoshis: Long) extends ChannelException(channelId, s"can't pay the fee: missingSatoshis=$missingSatoshis reserveSatoshis=$reserveSatoshis feesSatoshis=$feesSatoshis")
case class CannotSignWithoutChanges            (override val channelId: BinaryData) extends ChannelException(channelId, "cannot sign when there are no changes")
case class CannotSignBeforeRevocation          (override val channelId: BinaryData) extends ChannelException(channelId, "cannot sign until next revocation hash is received")
case class UnexpectedRevocation                (override val channelId: BinaryData) extends ChannelException(channelId, "received unexpected RevokeAndAck message")
case class InvalidRevocation                   (override val channelId: BinaryData) extends ChannelException(channelId, "invalid revocation")
case class CommitmentSyncError                 (override val channelId: BinaryData) extends ChannelException(channelId, "commitment sync error")
case class RevocationSyncError                 (override val channelId: BinaryData) extends ChannelException(channelId, "revocation sync error")
case class InvalidFailureCode                  (override val channelId: BinaryData) extends ChannelException(channelId, "UpdateFailMalformedHtlc message doesn't have BADONION bit set")
case class PleasePublishYourCommitment         (override val channelId: BinaryData) extends ChannelException(channelId, "please publish your local commitment")
case class AddHtlcFailed                       (override val channelId: BinaryData, paymentHash: BinaryData, t: Throwable, origin: Origin, channelUpdate: Option[ChannelUpdate]) extends ChannelException(channelId, s"cannot add htlc with origin=$origin reason=${t.getMessage}")
// @formatter:on