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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64}
import fr.acinq.eclair.{channel, randomKey}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fund.InteractiveTxSigningSession
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.transactions.Transactions.{CommitTx, HtlcTx}
import fr.acinq.eclair.wire.internal.channel.version0.ChannelTypes0
import fr.acinq.eclair.wire.protocol.CommitSig

private[channel] object ChannelTypes3 {

  case class WaitingForRevocation(nextRemoteCommit: RemoteCommit, sent: CommitSig, sentAfterLocalCommitIndex: Long)

  case class HtlcTxAndRemoteSig(htlcTx: HtlcTx, remoteSig: ByteVector64)

  case class CommitTxAndRemoteSig(commitTx: CommitTx, remoteSig: ChannelSpendSignature.IndividualSignature)

  // Before version 4, we stored the unsigned commit tx and htlc txs in our local commit.
  // We changed that to only store the remote signatures and re-compute transactions on-the-fly when force-closing.
  case class LocalCommit(index: Long, spec: CommitmentSpec, commitTxAndRemoteSig: CommitTxAndRemoteSig, htlcTxsAndRemoteSigs: List[HtlcTxAndRemoteSig]) {
    def migrate(): channel.LocalCommit = channel.LocalCommit(index, spec, commitTxAndRemoteSig.commitTx.tx.txid, commitTxAndRemoteSig.remoteSig, htlcTxsAndRemoteSigs.map(_.remoteSig))
  }

  case class UnsignedLocalCommit(index: Long, spec: CommitmentSpec, commitTx: CommitTx, htlcTxs: List[HtlcTx]) {
    def migrate(): InteractiveTxSigningSession.UnsignedLocalCommit = InteractiveTxSigningSession.UnsignedLocalCommit(index, spec, commitTx.tx.txid)
  }

  // Before version4, we didn't support multiple active commitments, which were later introduced by dual funding and splicing.
  case class Commitments(channelId: ByteVector32,
                         channelConfig: ChannelConfig,
                         channelFeatures: ChannelFeatures,
                         localParams: LocalParams, remoteParams: ChannelTypes0.RemoteParams,
                         channelFlags: ChannelFlags,
                         localCommit: ChannelTypes3.LocalCommit, remoteCommit: RemoteCommit,
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
      Seq(Commitment(
        fundingTxIndex = 0,
        firstRemoteCommitIndex = 0,
        localFundingPubKey = randomKey().publicKey,
        remoteFundingPubKey = remoteParams.fundingPubKey,
        fundingTxOutpoint = localCommit.commitTxAndRemoteSig.commitTx.input.outPoint,
        fundingAmount = localCommit.commitTxAndRemoteSig.commitTx.input.txOut.amount,
        localFundingStatus, remoteFundingStatus,
        format = channelFeatures.commitmentFormat,
        localCommit.migrate(), remoteCommit, remoteNextCommitInfo.left.toOption.map(w => NextRemoteCommit(w.sent, w.nextRemoteCommit)))),
      inactive = Nil,
      remoteNextCommitInfo.fold(w => Left(WaitForRev(w.sentAfterLocalCommitIndex)), remotePerCommitmentPoint => Right(remotePerCommitmentPoint)),
      remotePerCommitmentSecrets,
      originChannels
    )
  }

}
