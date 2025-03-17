/*
 * Copyright 2024 ACINQ SAS
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

import fr.acinq.eclair.channel.LocalFundingStatus.ConfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, Shutdown}
import fr.acinq.eclair.{Alias, RealShortChannelId}

private[channel] object ChannelTypes5 {

  // We moved the real scid inside each commitment object when adding DATA_NORMAL_14_Codec.
  case class ShortIds(real_opt: Option[RealShortChannelId], localAlias: Alias, remoteAlias_opt: Option[Alias])

  // We moved the channel_announcement inside each commitment object when adding DATA_NORMAL_14_Codec.
  case class DATA_NORMAL_0e(commitments: Commitments,
                            shortIds: ShortIds,
                            channelAnnouncement: Option[ChannelAnnouncement],
                            channelUpdate: ChannelUpdate,
                            localShutdown: Option[Shutdown],
                            remoteShutdown: Option[Shutdown],
                            closingFeerates: Option[ClosingFeerates],
                            spliceStatus: SpliceStatus) {
    def migrate(): DATA_NORMAL = {
      val commitments1 = commitments.copy(
        active = commitments.active.map(c => setScidIfMatches(c, shortIds)),
        inactive = commitments.inactive.map(c => setScidIfMatches(c, shortIds)),
      )
      val aliases = ShortIdAliases(shortIds.localAlias, shortIds.remoteAlias_opt)
      DATA_NORMAL(commitments1, aliases, channelAnnouncement, channelUpdate, localShutdown, remoteShutdown, closingFeerates, spliceStatus)
    }
  }

  case class DATA_WAIT_FOR_CHANNEL_READY_0b(commitments: Commitments, shortIds: ShortIds) {
    def migrate(): DATA_WAIT_FOR_CHANNEL_READY = {
      val commitments1 = commitments.copy(
        active = commitments.active.map(c => setScidIfMatches(c, shortIds)),
        inactive = commitments.inactive.map(c => setScidIfMatches(c, shortIds)),
      )
      val aliases = ShortIdAliases(shortIds.localAlias, shortIds.remoteAlias_opt)
      DATA_WAIT_FOR_CHANNEL_READY(commitments1, aliases)
    }
  }

  case class DATA_WAIT_FOR_DUAL_FUNDING_READY_0d(commitments: Commitments, shortIds: ShortIds) {
    def migrate(): DATA_WAIT_FOR_DUAL_FUNDING_READY = {
      val commitments1 = commitments.copy(
        active = commitments.active.map(c => setScidIfMatches(c, shortIds)),
        inactive = commitments.inactive.map(c => setScidIfMatches(c, shortIds)),
      )
      val aliases = ShortIdAliases(shortIds.localAlias, shortIds.remoteAlias_opt)
      DATA_WAIT_FOR_DUAL_FUNDING_READY(commitments1, aliases)
    }
  }

  private def setScidIfMatches(c: Commitment, shortIds: ShortIds): Commitment = {
    c.localFundingStatus match {
      // We didn't support splicing on public channels in this version: the scid (if available) is for the initial
      // funding transaction. For private channels we don't care about the real scid, it will be set correctly after
      // the next splice.
      case f: ConfirmedFundingTx if c.fundingTxIndex == 0 =>
        val scid = shortIds.real_opt.getOrElse(f.shortChannelId)
        c.copy(localFundingStatus = f.copy(shortChannelId = scid))
      case _ => c
    }
  }

}
