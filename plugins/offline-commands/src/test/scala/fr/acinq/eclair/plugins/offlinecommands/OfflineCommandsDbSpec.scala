/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.plugins.offlinecommands

import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.ClosingFeerates
import fr.acinq.eclair.{TimestampSecond, randomBytes32}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.HexStringSyntax

import java.sql.DriverManager

class OfflineCommandsDbSpec extends AnyFunSuiteLike {

  test("add/update/list close commands") {
    val db = new SqliteOfflineCommandsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
    assert(db.listPendingCloseCommands().isEmpty)

    val feerates = ClosingFeerates(FeeratePerKw(1000 sat), FeeratePerKw(500 sat), FeeratePerKw(1200 sat))
    val channelId1 = randomBytes32()
    db.addCloseCommand(channelId1, ClosingParams(None, None, None))
    val channelId2 = randomBytes32()
    db.addCloseCommand(channelId2, ClosingParams(Some(TimestampSecond(150)), Some(hex"0102030405"), Some(feerates)))

    assert(db.listPendingCloseCommands() == Map(
      channelId1 -> ClosingParams(None, None, None),
      channelId2 -> ClosingParams(Some(TimestampSecond(150)), Some(hex"0102030405"), Some(feerates)),
    ))

    db.addCloseCommand(channelId1, ClosingParams(Some(TimestampSecond(250)), Some(hex"deadbeef"), None))
    assert(db.listPendingCloseCommands() == Map(
      channelId1 -> ClosingParams(Some(TimestampSecond(250)), Some(hex"deadbeef"), None),
      channelId2 -> ClosingParams(Some(TimestampSecond(150)), Some(hex"0102030405"), Some(feerates)),
    ))

    db.updateCloseCommand(channelId1, ClosingStatus.ChannelNotFound)
    assert(db.listPendingCloseCommands() == Map(
      channelId2 -> ClosingParams(Some(TimestampSecond(150)), Some(hex"0102030405"), Some(feerates)),
    ))

    db.updateCloseCommand(channelId2, ClosingStatus.ChannelClosed)
    assert(db.listPendingCloseCommands().isEmpty)
  }

}
