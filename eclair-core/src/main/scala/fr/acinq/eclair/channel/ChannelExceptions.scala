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

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, Transaction}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.wire.protocol.{AnnouncementSignatures, Error, InteractiveTxMessage, UpdateAddHtlc}
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, MilliSatoshi, UInt64}

/**
 * Created by PM on 11/04/2017.
 */

// @formatter:off
sealed trait ChannelOpenError
case class LocalError(t: Throwable) extends ChannelOpenError
case class RemoteError(e: Error) extends ChannelOpenError
// @formatter:on

class ChannelException(val channelId: ByteVector32, message: String) extends RuntimeException(message)

// @formatter:off
case class InvalidChainHash                        (override val channelId: ByteVector32, local: ByteVector32, remote: ByteVector32) extends ChannelException(channelId, s"invalid chainHash (local=$local remote=$remote)")
case class InvalidFundingAmount                    (override val channelId: ByteVector32, fundingAmount: Satoshi, min: Satoshi, max: Satoshi) extends ChannelException(channelId, s"invalid funding_satoshis=$fundingAmount (min=$min max=$max)")
case class InvalidPushAmount                       (override val channelId: ByteVector32, pushAmount: MilliSatoshi, max: MilliSatoshi) extends ChannelException(channelId, s"invalid pushAmount=$pushAmount (max=$max)")
case class InvalidMaxAcceptedHtlcs                 (override val channelId: ByteVector32, maxAcceptedHtlcs: Int, max: Int) extends ChannelException(channelId, s"invalid max_accepted_htlcs=$maxAcceptedHtlcs (max=$max)")
case class InvalidChannelType                      (override val channelId: ByteVector32, ourChannelType: ChannelType, theirChannelType: ChannelType) extends ChannelException(channelId, s"invalid channel_type=$theirChannelType, expected channel_type=$ourChannelType")
case class MissingChannelType                      (override val channelId: ByteVector32) extends ChannelException(channelId, "option_channel_type was negotiated but channel_type is missing")
case class DustLimitTooSmall                       (override val channelId: ByteVector32, dustLimit: Satoshi, min: Satoshi) extends ChannelException(channelId, s"dustLimit=$dustLimit is too small (min=$min)")
case class DustLimitTooLarge                       (override val channelId: ByteVector32, dustLimit: Satoshi, max: Satoshi) extends ChannelException(channelId, s"dustLimit=$dustLimit is too large (max=$max)")
case class DustLimitAboveOurChannelReserve         (override val channelId: ByteVector32, dustLimit: Satoshi, channelReserve: Satoshi) extends ChannelException(channelId, s"dustLimit=$dustLimit is above our channelReserve=$channelReserve")
case class ToSelfDelayTooHigh                      (override val channelId: ByteVector32, toSelfDelay: CltvExpiryDelta, max: CltvExpiryDelta) extends ChannelException(channelId, s"unreasonable to_self_delay=$toSelfDelay (max=$max)")
case class ChannelReserveTooHigh                   (override val channelId: ByteVector32, channelReserve: Satoshi, reserveToFundingRatio: Double, maxReserveToFundingRatio: Double) extends ChannelException(channelId, s"channelReserve too high: reserve=$channelReserve fundingRatio=$reserveToFundingRatio maxFundingRatio=$maxReserveToFundingRatio")
case class ChannelReserveBelowOurDustLimit         (override val channelId: ByteVector32, channelReserve: Satoshi, dustLimit: Satoshi) extends ChannelException(channelId, s"their channelReserve=$channelReserve is below our dustLimit=$dustLimit")
case class ChannelReserveNotMet                    (override val channelId: ByteVector32, toLocal: MilliSatoshi, toRemote: MilliSatoshi, reserve: Satoshi) extends ChannelException(channelId, s"channel reserve is not met toLocal=$toLocal toRemote=$toRemote reserve=$reserve")
case class ChannelFundingError                     (override val channelId: ByteVector32) extends ChannelException(channelId, "channel funding error")
case class InvalidFundingTx                        (override val channelId: ByteVector32) extends ChannelException(channelId, "invalid funding tx")
case class InvalidSerialId                         (override val channelId: ByteVector32, serialId: UInt64) extends ChannelException(channelId, s"invalid serial_id=${serialId.toByteVector.toHex}")
case class DuplicateSerialId                       (override val channelId: ByteVector32, serialId: UInt64) extends ChannelException(channelId, s"duplicate serial_id=${serialId.toByteVector.toHex}")
case class UnknownSerialId                         (override val channelId: ByteVector32, serialId: UInt64) extends ChannelException(channelId, s"unknown serial_id=${serialId.toByteVector.toHex}")
case class DuplicateInput                          (override val channelId: ByteVector32, serialId: UInt64, previousTxId: ByteVector32, previousTxOutput: Long) extends ChannelException(channelId, s"duplicate input $previousTxId:$previousTxOutput (serial_id=${serialId.toByteVector.toHex})")
case class InputOutOfBounds                        (override val channelId: ByteVector32, serialId: UInt64, previousTxId: ByteVector32, previousTxOutput: Long) extends ChannelException(channelId, s"invalid input $previousTxId:$previousTxOutput (serial_id=${serialId.toByteVector.toHex})")
case class NonReplaceableInput                     (override val channelId: ByteVector32, serialId: UInt64, previousTxId: ByteVector32, previousTxOutput: Long, sequence: Long) extends ChannelException(channelId, s"$previousTxId:$previousTxOutput is not replaceable (serial_id=${serialId.toByteVector.toHex}, nSequence=$sequence)")
case class NonSegwitInput                          (override val channelId: ByteVector32, serialId: UInt64, previousTxId: ByteVector32, previousTxOutput: Long) extends ChannelException(channelId, s"$previousTxId:$previousTxOutput is not a native segwit input (serial_id=${serialId.toByteVector.toHex})")
case class PreviousTxMissing                       (override val channelId: ByteVector32, serialId: UInt64) extends ChannelException(channelId, s"previous tx missing from tx_add_input (serial_id=${serialId.toByteVector.toHex})")
case class InvalidSharedInput                      (override val channelId: ByteVector32, serialId: UInt64) extends ChannelException(channelId, s"invalid shared tx_add_input (serial_id=${serialId.toByteVector.toHex})")
case class OutputBelowDust                         (override val channelId: ByteVector32, serialId: UInt64, amount: Satoshi, dustLimit: Satoshi) extends ChannelException(channelId, s"invalid output amount=$amount below dust=$dustLimit (serial_id=${serialId.toByteVector.toHex})")
case class InvalidSharedOutputAmount               (override val channelId: ByteVector32, serialId: UInt64, amount: Satoshi, expected: Satoshi) extends ChannelException(channelId, s"invalid shared output amount=$amount expected=$expected (serial_id=${serialId.toByteVector.toHex})")
case class UnconfirmedInteractiveTxInputs          (override val channelId: ByteVector32) extends ChannelException(channelId, "the completed interactive tx contains unconfirmed inputs")
case class InvalidCompleteInteractiveTx            (override val channelId: ByteVector32) extends ChannelException(channelId, "the completed interactive tx is invalid")
case class TooManyInteractiveTxRounds              (override val channelId: ByteVector32) extends ChannelException(channelId, "too many messages exchanged during interactive tx construction")
case class RbfAttemptAborted                       (override val channelId: ByteVector32) extends ChannelException(channelId, "rbf attempt aborted")
case class DualFundingAborted                      (override val channelId: ByteVector32) extends ChannelException(channelId, "dual funding aborted")
case class UnexpectedInteractiveTxMessage          (override val channelId: ByteVector32, msg: InteractiveTxMessage) extends ChannelException(channelId, s"unexpected interactive-tx message (${msg.getClass.getSimpleName})")
case class UnexpectedCommitSig                     (override val channelId: ByteVector32) extends ChannelException(channelId, "unexpected commitment signatures (commit_sig)")
case class UnexpectedFundingSignatures             (override val channelId: ByteVector32) extends ChannelException(channelId, "unexpected funding signatures (tx_signatures)")
case class InvalidFundingFeerate                   (override val channelId: ByteVector32, targetFeerate: FeeratePerKw, actualFeerate: FeeratePerKw) extends ChannelException(channelId, s"invalid funding feerate: target=$targetFeerate actual=$actualFeerate")
case class InvalidFundingSignature                 (override val channelId: ByteVector32, txId_opt: Option[ByteVector32]) extends ChannelException(channelId, s"invalid funding signature: txId=${txId_opt.map(_.toHex).getOrElse("n/a")}")
case class InvalidRbfFeerate                       (override val channelId: ByteVector32, proposed: FeeratePerKw, expected: FeeratePerKw) extends ChannelException(channelId, s"invalid rbf attempt: the feerate must be at least $expected, you proposed $proposed")
case class InvalidRbfAlreadyInProgress             (override val channelId: ByteVector32) extends ChannelException(channelId, "invalid rbf attempt: the current rbf attempt must be completed or aborted first")
case class InvalidRbfTxAbortNotAcked               (override val channelId: ByteVector32) extends ChannelException(channelId, "invalid rbf attempt: our previous tx_abort has not been acked")
case class InvalidRbfAttemptsExhausted             (override val channelId: ByteVector32, maxAttempts: Int) extends ChannelException(channelId, s"invalid rbf attempt: $maxAttempts/$maxAttempts attempts already published")
case class InvalidRbfAttemptTooSoon                (override val channelId: ByteVector32, previousAttempt: BlockHeight, nextAttempt: BlockHeight) extends ChannelException(channelId, s"invalid rbf attempt: last attempt made at block=$previousAttempt, next attempt available after block=$nextAttempt")
case class InvalidRbfTxConfirmed                   (override val channelId: ByteVector32) extends ChannelException(channelId, "no need to rbf, transaction is already confirmed")
case class InvalidRbfNonInitiator                  (override val channelId: ByteVector32) extends ChannelException(channelId, "cannot initiate rbf: we're not the initiator of this interactive-tx attempt")
case class InvalidRbfZeroConf                      (override val channelId: ByteVector32) extends ChannelException(channelId, "cannot initiate rbf: we're using zero-conf for this interactive-tx attempt")
case class InvalidRbfAttempt                       (override val channelId: ByteVector32) extends ChannelException(channelId, "invalid rbf attempt")
case class NoMoreHtlcsClosingInProgress            (override val channelId: ByteVector32) extends ChannelException(channelId, "cannot send new htlcs, closing in progress")
case class ClosingAlreadyInProgress                (override val channelId: ByteVector32) extends ChannelException(channelId, "closing already in progress")
case class CannotCloseWithUnsignedOutgoingHtlcs    (override val channelId: ByteVector32) extends ChannelException(channelId, "cannot close when there are unsigned outgoing htlcs")
case class CannotCloseWithUnsignedOutgoingUpdateFee(override val channelId: ByteVector32) extends ChannelException(channelId, "cannot close when there is an unsigned fee update")
case class ChannelUnavailable                      (override val channelId: ByteVector32) extends ChannelException(channelId, "channel is unavailable (offline or closing)")
case class InvalidFinalScript                      (override val channelId: ByteVector32) extends ChannelException(channelId, "invalid final script")
case class MissingUpfrontShutdownScript            (override val channelId: ByteVector32) extends ChannelException(channelId, "missing upfront shutdown script")
case class FundingTxTimedout                       (override val channelId: ByteVector32) extends ChannelException(channelId, "funding tx timed out")
case class FundingTxDoubleSpent                    (override val channelId: ByteVector32) extends ChannelException(channelId, "funding tx double spent")
case class FundingTxSpent                          (override val channelId: ByteVector32, spendingTxId: ByteVector32) extends ChannelException(channelId, s"funding tx has been spent by txId=$spendingTxId")
case class HtlcsTimedoutDownstream                 (override val channelId: ByteVector32, htlcs: Set[UpdateAddHtlc]) extends ChannelException(channelId, s"one or more htlcs timed out downstream: ids=${htlcs.take(10).map(_.id).mkString(",")}") // we only display the first 10 ids
case class HtlcsWillTimeoutUpstream                (override val channelId: ByteVector32, htlcs: Set[UpdateAddHtlc]) extends ChannelException(channelId, s"one or more htlcs that should be fulfilled are close to timing out upstream: ids=${htlcs.take(10).map(_.id).mkString}") // we only display the first 10 ids
case class HtlcOverriddenByLocalCommit             (override val channelId: ByteVector32, htlc: UpdateAddHtlc) extends ChannelException(channelId, s"htlc ${htlc.id} was overridden by local commit")
case class FeerateTooSmall                         (override val channelId: ByteVector32, remoteFeeratePerKw: FeeratePerKw) extends ChannelException(channelId, s"remote fee rate is too small: remoteFeeratePerKw=${remoteFeeratePerKw.toLong}")
case class FeerateTooDifferent                     (override val channelId: ByteVector32, localFeeratePerKw: FeeratePerKw, remoteFeeratePerKw: FeeratePerKw) extends ChannelException(channelId, s"local/remote feerates are too different: remoteFeeratePerKw=${remoteFeeratePerKw.toLong} localFeeratePerKw=${localFeeratePerKw.toLong}")
case class InvalidAnnouncementSignatures           (override val channelId: ByteVector32, annSigs: AnnouncementSignatures) extends ChannelException(channelId, s"invalid announcement signatures: $annSigs")
case class InvalidCommitmentSignature              (override val channelId: ByteVector32, txId: ByteVector32) extends ChannelException(channelId, s"invalid commitment signature: txId=$txId")
case class InvalidHtlcSignature                    (override val channelId: ByteVector32, txId: ByteVector32) extends ChannelException(channelId, s"invalid htlc signature: txId=$txId")
case class InvalidCloseSignature                   (override val channelId: ByteVector32, txId: ByteVector32) extends ChannelException(channelId, s"invalid close signature: txId=$txId")
case class InvalidCloseFee                         (override val channelId: ByteVector32, fee: Satoshi) extends ChannelException(channelId, s"invalid close fee: fee_satoshis=$fee")
case class InvalidCloseAmountBelowDust             (override val channelId: ByteVector32, txId: ByteVector32) extends ChannelException(channelId, s"invalid closing tx: some outputs are below dust: txId=$txId")
case class CommitSigCountMismatch                  (override val channelId: ByteVector32, expected: Int, actual: Int) extends ChannelException(channelId, s"commit sig count mismatch: expected=$expected actual=$actual")
case class HtlcSigCountMismatch                    (override val channelId: ByteVector32, expected: Int, actual: Int) extends ChannelException(channelId, s"htlc sig count mismatch: expected=$expected actual: $actual")
case class ForcedLocalCommit                       (override val channelId: ByteVector32) extends ChannelException(channelId, s"forced local commit")
case class UnexpectedHtlcId                        (override val channelId: ByteVector32, expected: Long, actual: Long) extends ChannelException(channelId, s"unexpected htlc id: expected=$expected actual=$actual")
case class ExpiryTooSmall                          (override val channelId: ByteVector32, minimum: CltvExpiry, actual: CltvExpiry, blockHeight: BlockHeight) extends ChannelException(channelId, s"expiry too small: minimum=$minimum actual=$actual blockHeight=$blockHeight")
case class ExpiryTooBig                            (override val channelId: ByteVector32, maximum: CltvExpiry, actual: CltvExpiry, blockHeight: BlockHeight) extends ChannelException(channelId, s"expiry too big: maximum=$maximum actual=$actual blockHeight=$blockHeight")
case class HtlcValueTooSmall                       (override val channelId: ByteVector32, minimum: MilliSatoshi, actual: MilliSatoshi) extends ChannelException(channelId, s"htlc value too small: minimum=$minimum actual=$actual")
case class HtlcValueTooHighInFlight                (override val channelId: ByteVector32, maximum: MilliSatoshi, actual: MilliSatoshi) extends ChannelException(channelId, s"in-flight htlcs hold too much value: maximum=$maximum actual=$actual")
case class TooManyAcceptedHtlcs                    (override val channelId: ByteVector32, maximum: Long) extends ChannelException(channelId, s"too many accepted htlcs: maximum=$maximum")
case class LocalDustHtlcExposureTooHigh            (override val channelId: ByteVector32, maximum: Satoshi, actual: MilliSatoshi) extends ChannelException(channelId, s"dust htlcs hold too much value: maximum=$maximum actual=$actual")
case class RemoteDustHtlcExposureTooHigh           (override val channelId: ByteVector32, maximum: Satoshi, actual: MilliSatoshi) extends ChannelException(channelId, s"dust htlcs hold too much value: maximum=$maximum actual=$actual")
case class InsufficientFunds                       (override val channelId: ByteVector32, amount: MilliSatoshi, missing: Satoshi, reserve: Satoshi, fees: Satoshi) extends ChannelException(channelId, s"insufficient funds: missing=$missing reserve=$reserve fees=$fees")
case class RemoteCannotAffordFeesForNewHtlc        (override val channelId: ByteVector32, amount: MilliSatoshi, missing: Satoshi, reserve: Satoshi, fees: Satoshi) extends ChannelException(channelId, s"remote can't afford increased commit tx fees once new HTLC is added: missing=$missing reserve=$reserve fees=$fees")
case class InvalidHtlcPreimage                     (override val channelId: ByteVector32, id: Long) extends ChannelException(channelId, s"invalid htlc preimage for htlc id=$id")
case class UnknownHtlcId                           (override val channelId: ByteVector32, id: Long) extends ChannelException(channelId, s"unknown htlc id=$id")
case class CannotExtractSharedSecret               (override val channelId: ByteVector32, htlc: UpdateAddHtlc) extends ChannelException(channelId, s"can't extract shared secret: paymentHash=${htlc.paymentHash} onion=${htlc.onionRoutingPacket}")
case class NonInitiatorCannotSendUpdateFee         (override val channelId: ByteVector32) extends ChannelException(channelId, s"only the initiator should send update_fee messages")
case class CannotAffordFirstCommitFees             (override val channelId: ByteVector32, missing: Satoshi, fees: Satoshi) extends ChannelException(channelId, s"can't pay the fee in first commitment: missing=$missing fees=$fees")
case class CannotAffordFees                        (override val channelId: ByteVector32, missing: Satoshi, reserve: Satoshi, fees: Satoshi) extends ChannelException(channelId, s"can't pay the fee: missing=$missing reserve=$reserve fees=$fees")
case class CannotSignWithoutChanges                (override val channelId: ByteVector32) extends ChannelException(channelId, "cannot sign when there are no changes")
case class CannotSignBeforeRevocation              (override val channelId: ByteVector32) extends ChannelException(channelId, "cannot sign until next revocation hash is received")
case class UnexpectedRevocation                    (override val channelId: ByteVector32) extends ChannelException(channelId, "received unexpected RevokeAndAck message")
case class InvalidRevocation                       (override val channelId: ByteVector32) extends ChannelException(channelId, "invalid revocation")
case class InvalidRevokedCommitProof               (override val channelId: ByteVector32, ourLocalCommitmentNumber: Long, theirRemoteCommitmentNumber: Long, invalidPerCommitmentSecret: PrivateKey) extends ChannelException(channelId, s"counterparty claimed that we have a revoked commit but their proof doesn't check out: ourCommitmentNumber=$ourLocalCommitmentNumber theirCommitmentNumber=$theirRemoteCommitmentNumber perCommitmentSecret=$invalidPerCommitmentSecret")
case class InvalidFailureCode                      (override val channelId: ByteVector32) extends ChannelException(channelId, "UpdateFailMalformedHtlc message doesn't have BADONION bit set")
case class PleasePublishYourCommitment             (override val channelId: ByteVector32) extends ChannelException(channelId, "please publish your local commitment")
case class CommandUnavailableInThisState           (override val channelId: ByteVector32, command: String, state: ChannelState) extends ChannelException(channelId, s"cannot execute command=$command in state=$state")
// @formatter:on