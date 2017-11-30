package fr.acinq.eclair.channel

import fr.acinq.bitcoin.{BinaryData, Transaction}
import fr.acinq.eclair.UInt64

/**
  * Created by PM on 11/04/2017.
  */

class ChannelException(channelId: BinaryData, message: String) extends RuntimeException(message)
// @formatter:off
case class DebugTriggeredException             (channelId: BinaryData) extends ChannelException(channelId, "debug-mode triggered failure")
case class InvalidChainHash                    (channelId: BinaryData, local: BinaryData, remote: BinaryData) extends ChannelException(channelId, s"invalid chain_hash (local=$local remote=$remote)")
case class InvalidFundingAmount                (channelId: BinaryData, fundingSatoshis: Long, min: Long, max: Long) extends ChannelException(channelId, s"invalid funding_satoshis=$fundingSatoshis (min=$min max=$max)")
case class InvalidPushAmount                   (channelId: BinaryData, pushMsat: Long, max: Long) extends ChannelException(channelId, s"invalid push_msat=$pushMsat (max=$max)")
case class InvalidMaxAcceptedHtlcs             (channelId: BinaryData, maxAcceptedHtlcs: Int, max: Int) extends ChannelException(channelId, s"invalid max_accepted_htlcs=$maxAcceptedHtlcs (max=$max)")
case class InvalidDustLimit                    (channelId: BinaryData, dustLimitSatoshis: Long, min: Long) extends ChannelException(channelId, s"invalid dust_limit_satoshis=$dustLimitSatoshis (min=$min)")
case class ChannelReserveTooHigh               (channelId: BinaryData, channelReserveSatoshis: Long, reserveToFundingRatio: Double, maxReserveToFundingRatio: Double) extends ChannelException(channelId, s"channelReserveSatoshis too high: reserve=$channelReserveSatoshis fundingRatio=$reserveToFundingRatio maxFundingRatio=$maxReserveToFundingRatio")
case class ClosingInProgress                   (channelId: BinaryData) extends ChannelException(channelId, "cannot send new htlcs, closing in progress")
case class ClosingAlreadyInProgress            (channelId: BinaryData) extends ChannelException(channelId, "closing already in progress")
case class CannotCloseWithUnsignedOutgoingHtlcs(channelId: BinaryData) extends ChannelException(channelId, "cannot close when there are unsigned outgoing htlcs")
case class ChannelUnavailable                  (channelId: BinaryData) extends ChannelException(channelId, "channel is unavailable (offline or closing)")
case class InvalidFinalScript                  (channelId: BinaryData) extends ChannelException(channelId, "invalid final script")
case class HtlcTimedout                        (channelId: BinaryData) extends ChannelException(channelId, s"one or more htlcs timed out")
case class FeerateTooDifferent                 (channelId: BinaryData, localFeeratePerKw: Long, remoteFeeratePerKw: Long) extends ChannelException(channelId, s"local/remote feerates are too different: remoteFeeratePerKw=$remoteFeeratePerKw localFeeratePerKw=$localFeeratePerKw")
case class InvalidCloseSignature               (channelId: BinaryData) extends ChannelException(channelId, "cannot verify their close signature")
case class InvalidCommitmentSignature          (channelId: BinaryData, tx: Transaction) extends ChannelException(channelId, s"invalid commitment signature: tx=${Transaction.write(tx)}")
case class InvalidHtlcSignature                (channelId: BinaryData, tx: Transaction) extends ChannelException(channelId, s"invalid htlc signature: tx=${Transaction.write(tx)}")
case class HtlcSigCountMismatch                (channelId: BinaryData, expected: Int, actual: Int) extends ChannelException(channelId, s"htlc sig count mismatch: expected=$expected actual: $actual")
case class ForcedLocalCommit                   (channelId: BinaryData, reason: String) extends ChannelException(channelId, s"forced local commit: reason")
case class UnexpectedHtlcId                    (channelId: BinaryData, expected: Long, actual: Long) extends ChannelException(channelId, s"unexpected htlc id: expected=$expected actual=$actual")
case class InvalidPaymentHash                  (channelId: BinaryData) extends ChannelException(channelId, "invalid payment hash")
case class ExpiryTooSmall                      (channelId: BinaryData, minimum: Long, actual: Long, blockCount: Long) extends ChannelException(channelId, s"expiry too small: required=$minimum actual=$actual blockCount=$blockCount")
case class ExpiryCannotBeInThePast             (channelId: BinaryData, expiry: Long, blockCount: Long) extends ChannelException(channelId, s"expiry can't be in the past: expiry=$expiry blockCount=$blockCount")
case class HtlcValueTooSmall                   (channelId: BinaryData, minimum: Long, actual: Long) extends ChannelException(channelId, s"htlc value too small: minimum=$minimum actual=$actual")
case class HtlcValueTooHighInFlight            (channelId: BinaryData, maximum: UInt64, actual: UInt64) extends ChannelException(channelId, s"in-flight htlcs hold too much value: maximum=$maximum actual=$actual")
case class TooManyAcceptedHtlcs                (channelId: BinaryData, maximum: Long) extends ChannelException(channelId, s"too many accepted htlcs: maximum=$maximum")
case class InsufficientFunds                   (channelId: BinaryData, amountMsat: Long, missingSatoshis: Long, reserveSatoshis: Long, feesSatoshis: Long) extends ChannelException(channelId, s"insufficient funds: missingSatoshis=$missingSatoshis reserveSatoshis=$reserveSatoshis fees=$feesSatoshis")
case class InvalidHtlcPreimage                 (channelId: BinaryData, id: Long) extends ChannelException(channelId, s"invalid htlc preimage for htlc id=$id")
case class UnknownHtlcId                       (channelId: BinaryData, id: Long) extends ChannelException(channelId, s"unknown htlc id=$id")
case class FundeeCannotSendUpdateFee           (channelId: BinaryData) extends ChannelException(channelId, s"only the funder should send update_fee messages")
case class CannotAffordFees                    (channelId: BinaryData, missingSatoshis: Long, reserveSatoshis: Long, feesSatoshis: Long) extends ChannelException(channelId, s"can't pay the fee: missingSatoshis=$missingSatoshis reserveSatoshis=$reserveSatoshis feesSatoshis=$feesSatoshis")
case class CannotSignWithoutChanges            (channelId: BinaryData) extends ChannelException(channelId, "cannot sign when there are no changes")
case class CannotSignBeforeRevocation          (channelId: BinaryData) extends ChannelException(channelId, "cannot sign until next revocation hash is received")
case class UnexpectedRevocation                (channelId: BinaryData) extends ChannelException(channelId, "received unexpected RevokeAndAck message")
case class InvalidRevocation                   (channelId: BinaryData) extends ChannelException(channelId, "invalid revocation")
case class CommitmentSyncError                 (channelId: BinaryData) extends ChannelException(channelId, "commitment sync error")
case class RevocationSyncError                 (channelId: BinaryData) extends ChannelException(channelId, "revocation sync error")
case class InvalidFailureCode                  (channelId: BinaryData) extends ChannelException(channelId, "UpdateFailMalformedHtlc message doesn't have BADONION bit set")
// @formatter:on