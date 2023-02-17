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
import fr.acinq.eclair.wire.protocol.CommitSig

private[channel] object ChannelTypes3 {

  case class WaitingForRevocation(nextRemoteCommit: RemoteCommit, sent: CommitSig, sentAfterLocalCommitIndex: Long)

  // Before version4, we didn't support multiple active commitments, which were later introduced by dual funding and splicing.
  case class Commitments(channelId: ByteVector32,
                         channelConfig: ChannelConfig,
                         channelFeatures: ChannelFeatures,
                         localParams: LocalParams, remoteParams: RemoteParams,
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
      ChannelParams(channelId, channelConfig, channelFeatures, localParams, remoteParams, channelFlags),
      CommitmentChanges(localChanges, remoteChanges, localNextHtlcId, remoteNextHtlcId),
      Seq(Commitment(localFundingStatus, remoteFundingStatus, localCommit, remoteCommit, remoteNextCommitInfo.left.toOption.map(w => NextRemoteCommit(w.sent, w.nextRemoteCommit)))),
      remoteNextCommitInfo.fold(w => Left(WaitForRev(w.sentAfterLocalCommitIndex)), remotePerCommitmentPoint => Right(remotePerCommitmentPoint)),
      remotePerCommitmentSecrets,
      originChannels
    )
  }

}
