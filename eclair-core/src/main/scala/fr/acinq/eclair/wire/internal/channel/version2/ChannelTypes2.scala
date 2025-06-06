/*
 * Copyright 2025 ACINQ SAS
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

package fr.acinq.eclair.wire.internal.channel.version2

import fr.acinq.bitcoin.scalacompat.{OutPoint, Transaction}
import fr.acinq.eclair.channel
import fr.acinq.eclair.channel.{IncomingHtlcId, OutgoingHtlcId}
import fr.acinq.eclair.transactions.Transactions._

private[channel] object ChannelTypes2 {

  case class LocalCommitPublished(commitTx: Transaction, claimMainDelayedOutputTx: Option[ClaimLocalDelayedOutputTx], htlcTxs: Map[OutPoint, Option[HtlcTx]], claimHtlcDelayedTxs: List[HtlcDelayedTx], claimAnchorTxs: List[ClaimAnchorOutputTx], irrevocablySpent: Map[OutPoint, Transaction]) {
    def migrate(): channel.LocalCommitPublished = channel.LocalCommitPublished(
      commitTx = commitTx,
      localOutput_opt = claimMainDelayedOutputTx.map(_.input.outPoint),
      anchorOutput_opt = claimAnchorTxs.headOption.map(_.input.outPoint),
      htlcs = htlcTxs.map {
        case (outpoint, Some(htlcTx: HtlcSuccessTx)) => outpoint -> IncomingHtlcId(htlcTx.htlcId)
        case (outpoint, Some(htlcTx: HtlcTimeoutTx)) => outpoint -> OutgoingHtlcId(htlcTx.htlcId)
        // This case only happens for a received HTLC for which we don't yet have the preimage.
        // We cannot easily find the htlcId, so we just set it to a high value that won't match existing HTLCs.
        // This is fine because it is only used to unwatch HTLC outpoints that were failed downstream, which is just
        // an optimization to go to CLOSED more quickly.
        case (outpoint, None) => outpoint -> IncomingHtlcId(0x00ffffffffffffffL)
      },
      htlcDelayedOutputs = claimHtlcDelayedTxs.map(_.input.outPoint).toSet,
      irrevocablySpent = irrevocablySpent
    )
  }

  case class RemoteCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[ClaimRemoteCommitMainOutputTx], claimHtlcTxs: Map[OutPoint, Option[ClaimHtlcTx]], claimAnchorTxs: List[ClaimAnchorOutputTx], irrevocablySpent: Map[OutPoint, Transaction]) {
    def migrate(): channel.RemoteCommitPublished = channel.RemoteCommitPublished(
      commitTx = commitTx,
      localOutput_opt = claimMainOutputTx.map(_.input.outPoint),
      anchorOutput_opt = claimAnchorTxs.headOption.map(_.input.outPoint),
      htlcs = claimHtlcTxs.map {
        case (outpoint, Some(htlcTx: ClaimHtlcSuccessTx)) => outpoint -> OutgoingHtlcId(htlcTx.htlcId)
        case (outpoint, Some(htlcTx: ClaimHtlcTimeoutTx)) => outpoint -> IncomingHtlcId(htlcTx.htlcId)
        // Similarly to LocalCommitPublished above, it is fine to ignore this case.
        case (outpoint, None) => outpoint -> OutgoingHtlcId(0x00ffffffffffffffL)
      },
      irrevocablySpent = irrevocablySpent
    )
  }

  case class RevokedCommitPublished(commitTx: Transaction, claimMainOutputTx: Option[ClaimRemoteCommitMainOutputTx], mainPenaltyTx: Option[MainPenaltyTx], htlcPenaltyTxs: List[HtlcPenaltyTx], claimHtlcDelayedPenaltyTxs: List[ClaimHtlcDelayedOutputPenaltyTx], irrevocablySpent: Map[OutPoint, Transaction]) {
    def migrate(): channel.RevokedCommitPublished = channel.RevokedCommitPublished(
      commitTx = commitTx,
      localOutput_opt = claimMainOutputTx.map(_.input.outPoint),
      remoteOutput_opt = mainPenaltyTx.map(_.input.outPoint),
      htlcOutputs = htlcPenaltyTxs.map(_.input.outPoint).toSet,
      htlcDelayedOutputs = claimHtlcDelayedPenaltyTxs.map(_.input.outPoint).toSet,
      irrevocablySpent = irrevocablySpent
    )
  }

}
