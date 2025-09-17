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

package fr.acinq.eclair.wire.internal.channel.version5

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.transactions.DirectedHtlc
import scodec.bits.ByteVector

/**
 * Created by t-bast on 18/06/2025.
 */

private[channel] object ChannelTypes5 {

  /**
   * When multiple commitments are active, htlcs are shared between all of these commitments.
   * There may be up to 2 * 483 = 966 htlcs, and every htlc uses at least 1452 bytes and at most 65536 bytes.
   * The resulting htlc set size is thus between 1,4 MB and 64 MB, which can be pretty large.
   * To avoid writing that htlc set multiple times to disk, we encode it separately.
   */
  case class EncodedCommitments(channelParams: ChannelParams,
                                changes: CommitmentChanges,
                                // The direction we use is from our local point of view.
                                htlcs: Set[DirectedHtlc],
                                active: List[Commitment],
                                inactive: List[Commitment],
                                remoteNextCommitInfo: Either[WaitForRev, PublicKey],
                                remotePerCommitmentSecrets: ShaChain,
                                originChannels: Map[Long, Origin],
                                remoteChannelData_opt: Option[ByteVector]) {
    def toCommitments: Commitments = {
      Commitments(
        channelParams = channelParams,
        changes = changes,
        active = active,
        inactive = inactive,
        remoteNextCommitInfo = remoteNextCommitInfo,
        remotePerCommitmentSecrets = remotePerCommitmentSecrets,
        originChannels = originChannels,
        remoteChannelData_opt = remoteChannelData_opt
      )
    }
  }

  object EncodedCommitments {
    def fromCommitments(commitments: Commitments): EncodedCommitments = {
      // The direction we use is from our local point of view: we use sets, which deduplicates htlcs that are in both
      // local and remote commitments. All active commitments have the same htlc set, but each inactive commitment may
      // have a distinct htlc set.
      val commitmentsSet = commitments.active.head +: commitments.inactive
      val htlcs = commitmentsSet.flatMap(_.localCommit.spec.htlcs).toSet ++
        commitmentsSet.flatMap(_.remoteCommit.spec.htlcs.map(_.opposite)).toSet ++
        commitmentsSet.flatMap(_.nextRemoteCommit_opt.toList.flatMap(_.commit.spec.htlcs.map(_.opposite))).toSet
      EncodedCommitments(
        channelParams = commitments.channelParams,
        changes = commitments.changes,
        htlcs = htlcs,
        active = commitments.active.toList,
        inactive = commitments.inactive.toList,
        remoteNextCommitInfo = commitments.remoteNextCommitInfo,
        remotePerCommitmentSecrets = commitments.remotePerCommitmentSecrets,
        originChannels = commitments.originChannels,
        remoteChannelData_opt = commitments.remoteChannelData_opt
      )
    }
  }

}
