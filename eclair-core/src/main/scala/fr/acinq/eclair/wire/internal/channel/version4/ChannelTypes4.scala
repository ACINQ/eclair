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

package fr.acinq.eclair.wire.internal.channel.version4

import fr.acinq.eclair.channel.LocalFundingStatus.ConfirmedFundingTx
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire.protocol.{ChannelAnnouncement, ChannelUpdate, Shutdown}

private[channel] object ChannelTypes4 {

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
        active = commitments.active.map(c => setAnnouncementAndScidIfMatches(c, shortIds, channelAnnouncement)),
        inactive = commitments.inactive.map(c => setAnnouncementAndScidIfMatches(c, shortIds, channelAnnouncement)),
      )
      DATA_NORMAL(commitments1, shortIds, channelUpdate, localShutdown, remoteShutdown, closingFeerates, spliceStatus)
    }

    private def setAnnouncementAndScidIfMatches(c: Commitment, shortIds: ShortIds, announcement_opt: Option[ChannelAnnouncement]): Commitment = {
      c.localFundingStatus match {
        // We didn't support splicing on public channels in this version: the scid and announcement (if any) are for the
        // initial funding transaction.  
        case f: ConfirmedFundingTx if c.fundingTxIndex == 0 =>
          val scid = shortIds.real.toOption.getOrElse(f.shortChannelId)
          c.copy(localFundingStatus = f.copy(shortChannelId = scid, announcement_opt = announcement_opt))
        case _ => c
      }
    }
  }

  object DATA_NORMAL_0e {
    def from(d: DATA_NORMAL): DATA_NORMAL_0e = DATA_NORMAL_0e(d.commitments, d.shortIds, d.lastAnnouncement_opt, d.channelUpdate, d.localShutdown, d.remoteShutdown, d.closingFeerates, d.spliceStatus)
  }

}
