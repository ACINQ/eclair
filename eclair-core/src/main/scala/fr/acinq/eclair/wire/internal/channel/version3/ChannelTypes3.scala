/*
 * Copyright 2023 ACINQ SAS
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

package fr.acinq.eclair.wire.internal.channel.version3

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.DirectedHtlc
import fr.acinq.eclair.transactions.Transactions.{ClaimHtlcSuccessTx, ClaimHtlcTimeoutTx, HtlcSuccessTx, HtlcTimeoutTx}
import fr.acinq.eclair.wire.internal.channel.version0.ChannelTypes0
import fr.acinq.eclair.wire.protocol.CommitSig

private[channel] object ChannelTypes3 {

  case class WaitingForRevocation(nextRemoteCommit: RemoteCommit, sent: CommitSig, sentAfterLocalCommitIndex: Long)

  // Before version4, we didn't support multiple active commitments, which were later introduced by dual funding and splicing.
  case class Commitments(channelId: ByteVector32,
                         channelConfig: ChannelConfig,
                         channelFeatures: ChannelFeatures,
                         localParams: LocalParams, remoteParams: ChannelTypes0.RemoteParams,
                         channelFlags: ChannelFlags,
                         localCommit: LocalCommit, remoteCommit: RemoteCommit,
                         localChanges: LocalChanges, remoteChanges: RemoteChanges,
                         localNextHtlcId: Long, remoteNextHtlcId: Long,
                         originChannels: Map[Long, Origin],
                         remoteNextCommitInfo: Either[WaitingForRevocation, PublicKey],
                         localFundingStatus: LocalFundingStatus,
                         remoteFundingStatus: RemoteFundingStatus,
                         remotePerCommitmentSecrets: ShaChain) {
    def migrate(): channel.Commitments = channel.Commitments(
      ChannelParams(channelId, channelConfig, channelFeatures, localParams, remoteParams.migrate(), channelFlags),
      CommitmentChanges(localChanges, remoteChanges, localNextHtlcId, remoteNextHtlcId),
      Seq(Commitment(fundingTxIndex = 0, firstRemoteCommitIndex = 0, remoteFundingPubKey = remoteParams.fundingPubKey, localFundingStatus, remoteFundingStatus, localCommit, remoteCommit, remoteNextCommitInfo.left.toOption.map(w => NextRemoteCommit(w.sent, w.nextRemoteCommit)))),
      inactive = Nil,
      remoteNextCommitInfo.fold(w => Left(WaitForRev(w.sentAfterLocalCommitIndex)), remotePerCommitmentPoint => Right(remotePerCommitmentPoint)),
      remotePerCommitmentSecrets,
      originChannels
    )
  }

  // Between version4 and version5 (in https://github.com/ACINQ/eclair/pull/3074), we added more fields to our
  // TransactionWithInputInfo instances. We fill those missing fields in case nodes upgrade while a channel is closing.
  def fillForceCloseTxData(closing: DATA_CLOSING): DATA_CLOSING = {
    closing.copy(
      localCommitPublished = closing.localCommitPublished.map(lcp => {
        // It is *our* commitment transaction: it uses the to_self_delay that *they* set.
        val toLocalDelay = closing.commitments.params.remoteParams.toSelfDelay
        val incomingHtlcs = closing.commitments.latest.localCommit.spec.htlcs.collect(DirectedHtlc.incoming).map(add => add.id -> add).toMap
        val outgoingHtlcs = closing.commitments.latest.localCommit.spec.htlcs.collect(DirectedHtlc.outgoing).map(add => add.id -> add).toMap
        lcp.copy(
          claimMainDelayedOutputTx = lcp.claimMainDelayedOutputTx.map(_.copy(toLocalDelay = toLocalDelay)),
          htlcTxs = lcp.htlcTxs.map {
            case (outpoint, Some(htlcTx: HtlcSuccessTx)) =>
              val htlcTx1 = incomingHtlcs.get(htlcTx.htlcId) match {
                case Some(htlc) => htlcTx.copy(paymentHash = htlc.paymentHash, htlcExpiry = htlc.cltvExpiry)
                case None => htlcTx
              }
              (outpoint, Some(htlcTx1))
            case (outpoint, Some(htlcTx: HtlcTimeoutTx)) =>
              val htlcTx1 = outgoingHtlcs.get(htlcTx.htlcId) match {
                case Some(htlc) => htlcTx.copy(paymentHash = htlc.paymentHash, htlcExpiry = htlc.cltvExpiry)
                case None => htlcTx
              }
              (outpoint, Some(htlcTx1))
            case (outpoint, None) => (outpoint, None)
          },
          claimHtlcDelayedTxs = lcp.claimHtlcDelayedTxs.map(_.copy(toLocalDelay = toLocalDelay)),
        )
      }),
      remoteCommitPublished = closing.remoteCommitPublished.map(rcp => {
        val incomingHtlcs = closing.commitments.latest.remoteCommit.spec.htlcs.collect(DirectedHtlc.incoming).map(add => add.id -> add).toMap
        val outgoingHtlcs = closing.commitments.latest.remoteCommit.spec.htlcs.collect(DirectedHtlc.outgoing).map(add => add.id -> add).toMap
        rcp.copy(
          claimHtlcTxs = rcp.claimHtlcTxs.map {
            case (outpoint, Some(claimHtlcTx: ClaimHtlcSuccessTx)) =>
              val claimHtlcTx1 = outgoingHtlcs.get(claimHtlcTx.htlcId) match {
                case Some(htlc) => claimHtlcTx.copy(paymentHash = htlc.paymentHash, htlcExpiry = htlc.cltvExpiry)
                case None => claimHtlcTx
              }
              (outpoint, Some(claimHtlcTx1))
            case (outpoint, Some(claimHtlcTx: ClaimHtlcTimeoutTx)) =>
              val claimHtlcTx1 = incomingHtlcs.get(claimHtlcTx.htlcId) match {
                case Some(htlc) => claimHtlcTx.copy(paymentHash = htlc.paymentHash, htlcExpiry = htlc.cltvExpiry)
                case None => claimHtlcTx
              }
              (outpoint, Some(claimHtlcTx1))
            case (outpoint, None) => (outpoint, None)
          }
        )
      }),
      nextRemoteCommitPublished = closing.nextRemoteCommitPublished.map(rcp => {
        val incomingHtlcs = closing.commitments.latest.nextRemoteCommit_opt.get.commit.spec.htlcs.collect(DirectedHtlc.incoming).map(add => add.id -> add).toMap
        val outgoingHtlcs = closing.commitments.latest.nextRemoteCommit_opt.get.commit.spec.htlcs.collect(DirectedHtlc.outgoing).map(add => add.id -> add).toMap
        rcp.copy(
          claimHtlcTxs = rcp.claimHtlcTxs.map {
            case (outpoint, Some(claimHtlcTx: ClaimHtlcSuccessTx)) =>
              val claimHtlcTx1 = outgoingHtlcs.get(claimHtlcTx.htlcId) match {
                case Some(htlc) => claimHtlcTx.copy(paymentHash = htlc.paymentHash, htlcExpiry = htlc.cltvExpiry)
                case None => claimHtlcTx
              }
              (outpoint, Some(claimHtlcTx1))
            case (outpoint, Some(claimHtlcTx: ClaimHtlcTimeoutTx)) =>
              val claimHtlcTx1 = incomingHtlcs.get(claimHtlcTx.htlcId) match {
                case Some(htlc) => claimHtlcTx.copy(paymentHash = htlc.paymentHash, htlcExpiry = htlc.cltvExpiry)
                case None => claimHtlcTx
              }
              (outpoint, Some(claimHtlcTx1))
            case (outpoint, None) => (outpoint, None)
          }
        )
      }),
      revokedCommitPublished = closing.revokedCommitPublished.map(rvk => {
        // It is *their* commitment transaction: it uses the to_self_delay that *we* set.
        val toRemoteDelay = closing.commitments.params.localParams.toSelfDelay
        rvk.copy(
          mainPenaltyTx = rvk.mainPenaltyTx.map(_.copy(toRemoteDelay = toRemoteDelay)),
          // Ideally, we should fill the payment_hash and htlc_expiry for HTLC-penalty txs. Unfortunately we cannot
          // easily do that: we'd need access to the past HTLCs DB and we'd need to recompute the revocation secret.
          // In practice, it is fine if we don't migrate those transactions because:
          //  - we already have a previously signed version of them that pays a high feerate and will likely confirm
          //  - if they don't confirm and our peer is able to get their HTLC tx confirmed, we will react and use a
          //    penalty transaction to claim the output of their HTLC tx
          claimHtlcDelayedPenaltyTxs = rvk.claimHtlcDelayedPenaltyTxs.map(_.copy(toRemoteDelay = toRemoteDelay)),
        )
      })
    )
  }

}
