/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.channel

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{ByteVector32, Transaction}
import fr.acinq.eclair.{ShortChannelId, UInt64}
import fr.acinq.eclair.payment.Origin
import fr.acinq.eclair.wire.{ChannelUpdate, UpdateAddHtlc}

/**
  * Created by PM on 11/04/2017.
  */

class ChannelException(val channelId: ByteVector32, message: String) extends RuntimeException(message)
// @formatter:off
case class DebugTriggeredException             (override val channelId: ByteVector32) extends ChannelException(channelId, "debug-mode triggered failure")
case class InvalidChainHash                    (override val channelId: ByteVector32, local: ByteVector32, remote: ByteVector32) extends ChannelException(channelId, s"invalid chainHash (local=$local remote=$remote)")
case class InvalidFundingAmount                (override val channelId: ByteVector32, fundingSatoshis: Long, min: Long, max: Long) extends ChannelException(channelId, s"invalid funding_satoshis=$fundingSatoshis (min=$min max=$max)")
case class InvalidPushAmount                   (override val channelId: ByteVector32, pushMsat: Long, max: Long) extends ChannelException(channelId, s"invalid pushMsat=$pushMsat (max=$max)")
case class InvalidMaxAcceptedHtlcs             (override val channelId: ByteVector32, maxAcceptedHtlcs: Int, max: Int) extends ChannelException(channelId, s"invalid max_accepted_htlcs=$maxAcceptedHtlcs (max=$max)")
case class DustLimitTooSmall                   (override val channelId: ByteVector32, dustLimitSatoshis: Long, min: Long) extends ChannelException(channelId, s"dustLimitSatoshis=$dustLimitSatoshis is too small (min=$min)")
case class DustLimitTooLarge                   (override val channelId: ByteVector32, dustLimitSatoshis: Long, max: Long) extends ChannelException(channelId, s"dustLimitSatoshis=$dustLimitSatoshis is too large (max=$max)")
case class DustLimitAboveOurChannelReserve     (override val channelId: ByteVector32, dustLimitSatoshis: Long, channelReserveSatoshis: Long) extends ChannelException(channelId, s"dustLimitSatoshis dustLimitSatoshis=$dustLimitSatoshis is above our channelReserveSatoshis=$channelReserveSatoshis")
case class ToSelfDelayTooHigh                  (override val channelId: ByteVector32, toSelfDelay: Int, max: Int) extends ChannelException(channelId, s"unreasonable to_self_delay=$toSelfDelay (max=$max)")
case class ChannelReserveTooHigh               (override val channelId: ByteVector32, channelReserveSatoshis: Long, reserveToFundingRatio: Double, maxReserveToFundingRatio: Double) extends ChannelException(channelId, s"channelReserveSatoshis too high: reserve=$channelReserveSatoshis fundingRatio=$reserveToFundingRatio maxFundingRatio=$maxReserveToFundingRatio")
case class ChannelReserveBelowOurDustLimit     (override val channelId: ByteVector32, channelReserveSatoshis: Long, dustLimitSatoshis: Long) extends ChannelException(channelId, s"their channelReserveSatoshis=$channelReserveSatoshis is below our dustLimitSatoshis=$dustLimitSatoshis")
case class ChannelReserveNotMet                (override val channelId: ByteVector32, toLocalMsat: Long, toRemoteMsat: Long, reserveSatoshis: Long) extends ChannelException(channelId, s"channel reserve is not met toLocalMsat=$toLocalMsat toRemoteMsat=$toRemoteMsat reserveSat=$reserveSatoshis")
case class ChannelFundingError                 (override val channelId: ByteVector32) extends ChannelException(channelId, "channel funding error")
case class NoMoreHtlcsClosingInProgress        (override val channelId: ByteVector32) extends ChannelException(channelId, "cannot send new htlcs, closing in progress")
case class ClosingAlreadyInProgress            (override val channelId: ByteVector32) extends ChannelException(channelId, "closing already in progress")
case class CannotCloseWithUnsignedOutgoingHtlcs(override val channelId: ByteVector32) extends ChannelException(channelId, "cannot close when there are unsigned outgoing htlcs")
case class ChannelUnavailable                  (override val channelId: ByteVector32) extends ChannelException(channelId, "channel is unavailable (offline or closing)")
case class InvalidFinalScript                  (override val channelId: ByteVector32) extends ChannelException(channelId, "invalid final script")
case class FundingTxTimedout                   (override val channelId: ByteVector32) extends ChannelException(channelId, "funding tx timed out")
case class FundingTxSpent                      (override val channelId: ByteVector32, spendingTx: Transaction) extends ChannelException(channelId, s"funding tx has been spent by txid=${spendingTx.txid}")
case class HtlcTimedout                        (override val channelId: ByteVector32, htlcs: Set[UpdateAddHtlc]) extends ChannelException(channelId, s"one or more htlcs timed out: ids=${htlcs.take(10).map(_.id).mkString}") // we only display the first 10 ids
case class HtlcOverridenByLocalCommit          (override val channelId: ByteVector32) extends ChannelException(channelId, "htlc was overriden by local commit")
case class FeerateTooSmall                     (override val channelId: ByteVector32, remoteFeeratePerKw: Long) extends ChannelException(channelId, s"remote fee rate is too small: remoteFeeratePerKw=$remoteFeeratePerKw")
case class FeerateTooDifferent                 (override val channelId: ByteVector32, localFeeratePerKw: Long, remoteFeeratePerKw: Long) extends ChannelException(channelId, s"local/remote feerates are too different: remoteFeeratePerKw=$remoteFeeratePerKw localFeeratePerKw=$localFeeratePerKw")
case class InvalidCommitmentSignature          (override val channelId: ByteVector32, tx: Transaction) extends ChannelException(channelId, s"invalid commitment signature: tx=$tx")
case class InvalidHtlcSignature                (override val channelId: ByteVector32, tx: Transaction) extends ChannelException(channelId, s"invalid htlc signature: tx=$tx")
case class InvalidCloseSignature               (override val channelId: ByteVector32, tx: Transaction) extends ChannelException(channelId, s"invalid close signature: tx=$tx")
case class InvalidCloseFee                     (override val channelId: ByteVector32, feeSatoshi: Long) extends ChannelException(channelId, s"invalid close fee: fee_satoshis=$feeSatoshi")
case class HtlcSigCountMismatch                (override val channelId: ByteVector32, expected: Int, actual: Int) extends ChannelException(channelId, s"htlc sig count mismatch: expected=$expected actual: $actual")
case class ForcedLocalCommit                   (override val channelId: ByteVector32) extends ChannelException(channelId, s"forced local commit")
case class UnexpectedHtlcId                    (override val channelId: ByteVector32, expected: Long, actual: Long) extends ChannelException(channelId, s"unexpected htlc id: expected=$expected actual=$actual")
case class ExpiryTooSmall                      (override val channelId: ByteVector32, minimum: Long, actual: Long, blockCount: Long) extends ChannelException(channelId, s"expiry too small: minimum=$minimum actual=$actual blockCount=$blockCount")
case class ExpiryTooBig                        (override val channelId: ByteVector32, maximum: Long, actual: Long, blockCount: Long) extends ChannelException(channelId, s"expiry too big: maximum=$maximum actual=$actual blockCount=$blockCount")
case class HtlcValueTooSmall                   (override val channelId: ByteVector32, minimum: Long, actual: Long) extends ChannelException(channelId, s"htlc value too small: minimum=$minimum actual=$actual")
case class HtlcValueTooHighInFlight            (override val channelId: ByteVector32, maximum: UInt64, actual: UInt64) extends ChannelException(channelId, s"in-flight htlcs hold too much value: maximum=$maximum actual=$actual")
case class TooManyAcceptedHtlcs                (override val channelId: ByteVector32, maximum: Long) extends ChannelException(channelId, s"too many accepted htlcs: maximum=$maximum")
case class InsufficientFunds                   (override val channelId: ByteVector32, amountMsat: Long, missingSatoshis: Long, reserveSatoshis: Long, feesSatoshis: Long) extends ChannelException(channelId, s"insufficient funds: missingSatoshis=$missingSatoshis reserveSatoshis=$reserveSatoshis fees=$feesSatoshis")
case class InvalidHtlcPreimage                 (override val channelId: ByteVector32, id: Long) extends ChannelException(channelId, s"invalid htlc preimage for htlc id=$id")
case class UnknownHtlcId                       (override val channelId: ByteVector32, id: Long) extends ChannelException(channelId, s"unknown htlc id=$id")
case class CannotExtractSharedSecret           (override val channelId: ByteVector32, htlc: UpdateAddHtlc) extends ChannelException(channelId, s"can't extract shared secret: paymentHash=${htlc.paymentHash} onion=${htlc.onionRoutingPacket}")
case class FundeeCannotSendUpdateFee           (override val channelId: ByteVector32) extends ChannelException(channelId, s"only the funder should send update_fee messages")
case class CannotAffordFees                    (override val channelId: ByteVector32, missingSatoshis: Long, reserveSatoshis: Long, feesSatoshis: Long) extends ChannelException(channelId, s"can't pay the fee: missingSatoshis=$missingSatoshis reserveSatoshis=$reserveSatoshis feesSatoshis=$feesSatoshis")
case class CannotSignWithoutChanges            (override val channelId: ByteVector32) extends ChannelException(channelId, "cannot sign when there are no changes")
case class CannotSignBeforeRevocation          (override val channelId: ByteVector32) extends ChannelException(channelId, "cannot sign until next revocation hash is received")
case class UnexpectedRevocation                (override val channelId: ByteVector32) extends ChannelException(channelId, "received unexpected RevokeAndAck message")
case class InvalidRevocation                   (override val channelId: ByteVector32) extends ChannelException(channelId, "invalid revocation")
case class InvalidRevokedCommitProof           (override val channelId: ByteVector32, ourCommitmentNumber: Long, theirCommitmentNumber: Long, perCommitmentSecret: PrivateKey) extends ChannelException(channelId, s"counterparty claimed that we have a revoked commit but their proof doesn't check out: ourCommitmentNumber=$ourCommitmentNumber theirCommitmentNumber=$theirCommitmentNumber perCommitmentSecret=$perCommitmentSecret")
case class CommitmentSyncError                 (override val channelId: ByteVector32) extends ChannelException(channelId, "commitment sync error")
case class RevocationSyncError                 (override val channelId: ByteVector32) extends ChannelException(channelId, "revocation sync error")
case class InvalidFailureCode                  (override val channelId: ByteVector32) extends ChannelException(channelId, "UpdateFailMalformedHtlc message doesn't have BADONION bit set")
case class PleasePublishYourCommitment         (override val channelId: ByteVector32) extends ChannelException(channelId, "please publish your local commitment")
case class AddHtlcFailed                       (override val channelId: ByteVector32, paymentHash: ByteVector32, t: Throwable, origin: Origin, channelUpdate: Option[ChannelUpdate], originalCommand: Option[CMD_ADD_HTLC]) extends ChannelException(channelId, s"cannot add htlc with origin=$origin reason=${t.getMessage}")
case class CommandUnavailableInThisState       (override val channelId: ByteVector32, command: String, state: State) extends ChannelException(channelId, s"cannot execute command=$command in state=$state")
// @formatter:on