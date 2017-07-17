package fr.acinq.eclair.channel

/**
  * Created by PM on 11/04/2017.
  */

class ChannelException(message: String) extends RuntimeException(message)

case object DebugTriggeredException extends ChannelException("debug-mode triggered failure")
case object ClosingInProgress extends ChannelException("cannot send new htlcs, closing in progress")
case object ClosingAlreadyInProgress extends ChannelException("closing already in progress")
case object CannotCloseWithPendingChanges extends ChannelException("cannot close when there are pending changes")
case object ChannelUnavailable extends ChannelException("channel is unavailable (offline or closing)")
case object InvalidFinalScript extends ChannelException("invalid final script")
case object HtlcTimedout extends ChannelException(s"one or more htlcs timed out")
case class FeerateTooDifferent(localFeeratePerKw: Long, remoteFeeratePerKw: Long) extends ChannelException(s"local/remote feerates are too different: remoteFeeratePerKw=$remoteFeeratePerKw localFeeratePerKw=$localFeeratePerKw")
case object InvalidCloseSignature extends ChannelException("cannot verify their close signature")
case object InvalidCommitmentSignature extends ChannelException("invalid commitment signature")
case class ForcedLocalCommit(reason: String) extends ChannelException(s"forced local commit: reason")
case class UnexpectedHtlcId(expected: Long, actual: Long) extends ChannelException(s"unexpected htlc id: expected=$expected actual=$actual")
case object InvalidPaymentHash extends ChannelException("invalid payment hash")
case class ExpiryTooSmall(minimum: Long, actual: Long, blockCount: Long) extends ChannelException(s"expiry too small: required=$minimum actual=$actual blockCount=$blockCount")
case class ExpiryCannotBeInThePast(expiry: Long, blockCount: Long) extends ChannelException(s"expiry can't be in the past: expiry=$expiry blockCount=$blockCount")
case class HtlcValueTooSmall(minimum: Long, actual: Long) extends ChannelException(s"htlc value too small: mininmum=$minimum actual=$actual")
case class HtlcValueTooHighInFlight(maximum: Long, actual: Long) extends ChannelException(s"in-flight htlcs hold too much value: maximum=$maximum actual=$actual")
case class TooManyAcceptedHtlcs(maximum: Long) extends ChannelException(s"too many accepted htlcs: maximum=$maximum")
case class InsufficientFunds(amountMsat: Long, missingSatoshis: Long, reserveSatoshis: Long, feesSatoshis: Long) extends ChannelException(s"insufficient funds: missingSatoshis=$missingSatoshis reserveSatoshis=$reserveSatoshis fees=$feesSatoshis")
case class InvalidHtlcPreimage(id: Long) extends ChannelException(s"invalid htlc preimage for htlc id=$id")
case class UnknownHtlcId(id: Long) extends ChannelException(s"unknown htlc id=$id")
case object FundeeCannotSendUpdateFee extends ChannelException(s"only the funder should send update_fee messages")
case class CannotAffordFees(missingSatoshis: Long, reserveSatoshis: Long, feesSatoshis: Long) extends ChannelException(s"can't pay the fee: missingSatoshis=$missingSatoshis reserveSatoshis=$reserveSatoshis feesSatoshis=$feesSatoshis")
case object CannotSignWithoutChanges extends ChannelException("cannot sign when there are no changes")
case object CannotSignBeforeRevocation extends ChannelException("cannot sign until next revocation hash is received")
case object UnexpectedRevocation extends ChannelException("received unexpected RevokeAndAck message")
case object InvalidRevocation extends ChannelException("invalid revocation")
case object CommitmentSyncError extends ChannelException("commitment sync error")
case object RevocationSyncError extends ChannelException("revocation sync error")