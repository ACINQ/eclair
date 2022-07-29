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

import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.TimestampSecond
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.ClosingFeerates
import scodec.bits.ByteVector

import java.sql.{Connection, ResultSet}

// @formatter:off
sealed trait ClosingStatus
object ClosingStatus {
  case object Pending extends ClosingStatus
  case object ChannelNotFound extends ClosingStatus
  case object ChannelClosed extends ClosingStatus
}
// @formatter:on

case class ClosingParams(forceCloseAfter_opt: Option[TimestampSecond], scriptPubKey_opt: Option[ByteVector], closingFeerates_opt: Option[ClosingFeerates])

trait OfflineCloseCommandsDb {
  // @formatter:off
  def addCloseCommand(channelId: ByteVector32, closingParams: ClosingParams): Unit
  def updateCloseCommand(channelId: ByteVector32, status: ClosingStatus): Unit
  def listPendingCloseCommands(): Map[ByteVector32, ClosingParams]
  // @formatter:on
}

trait OfflineCommandsDb extends OfflineCloseCommandsDb

object SqliteOfflineCommandsDb {
  val CURRENT_VERSION = 1
  val DB_NAME = "offline_commands"
}

class SqliteOfflineCommandsDb(sqlite: Connection) extends OfflineCommandsDb {

  import SqliteOfflineCommandsDb._
  import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
  import fr.acinq.eclair.db.sqlite.SqliteUtils._

  using(sqlite.createStatement(), inTransaction = true) { statement =>
    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE close_commands (channel_id TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL, force_close_timestamp INTEGER, script_pubkey TEXT, preferred_feerate_sat_kw INTEGER, min_feerate_sat_kw INTEGER, max_feerate_sat_kw INTEGER, created_timestamp INTEGER NOT NULL, closed_timestamp INTEGER)")
        statement.executeUpdate("CREATE INDEX status_idx ON close_commands(status)")
        statement.executeUpdate("CREATE INDEX created_timestamp_idx ON close_commands(created_timestamp)")
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addCloseCommand(channelId: ByteVector32, closingParams: ClosingParams): Unit = {
    using(sqlite.prepareStatement("UPDATE close_commands SET status=?, force_close_timestamp=?, script_pubkey=?, preferred_feerate_sat_kw=?, min_feerate_sat_kw=?, max_feerate_sat_kw=?, created_timestamp=? WHERE channel_id=?")) { update =>
      update.setString(1, ClosingStatus.Pending.toString)
      update.setObject(2, closingParams.forceCloseAfter_opt.map(_.toLong).orNull)
      update.setString(3, closingParams.scriptPubKey_opt.map(_.toHex).orNull)
      update.setObject(4, closingParams.closingFeerates_opt.map(_.preferred.toLong).orNull)
      update.setObject(5, closingParams.closingFeerates_opt.map(_.min.toLong).orNull)
      update.setObject(6, closingParams.closingFeerates_opt.map(_.max.toLong).orNull)
      update.setLong(7, TimestampSecond.now().toLong)
      update.setString(8, channelId.toHex)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO close_commands (channel_id, status, force_close_timestamp, script_pubkey, preferred_feerate_sat_kw, min_feerate_sat_kw, max_feerate_sat_kw, created_timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, channelId.toHex)
          statement.setString(2, ClosingStatus.Pending.toString)
          statement.setObject(3, closingParams.forceCloseAfter_opt.map(_.toLong).orNull)
          statement.setString(4, closingParams.scriptPubKey_opt.map(_.toHex).orNull)
          statement.setObject(5, closingParams.closingFeerates_opt.map(_.preferred.toLong).orNull)
          statement.setObject(6, closingParams.closingFeerates_opt.map(_.min.toLong).orNull)
          statement.setObject(7, closingParams.closingFeerates_opt.map(_.max.toLong).orNull)
          statement.setLong(8, TimestampSecond.now().toLong)
          statement.executeUpdate()
        }
      }
    }
  }

  override def updateCloseCommand(channelId: ByteVector32, status: ClosingStatus): Unit = {
    using(sqlite.prepareStatement("UPDATE close_commands SET status=?, closed_timestamp=? WHERE channel_id=?")) { update =>
      update.setString(1, status.toString)
      update.setLong(2, TimestampSecond.now().toLong)
      update.setString(3, channelId.toHex)
      update.executeUpdate()
    }
  }

  override def listPendingCloseCommands(): Map[ByteVector32, ClosingParams] = {
    using(sqlite.prepareStatement("SELECT * FROM close_commands WHERE status = ? ORDER BY created_timestamp")) { statement =>
      statement.setString(1, ClosingStatus.Pending.toString)
      statement.executeQuery().map(parseCloseCommand).toMap
    }
  }

  private def parseCloseCommand(rs: ResultSet): (ByteVector32, ClosingParams) = {
    val channelId = ByteVector32.fromValidHex(rs.getString("channel_id"))
    val closingParams = ClosingParams(
      rs.getLongNullable("force_close_timestamp").map(TimestampSecond(_)),
      rs.getStringNullable("script_pubkey").map(ByteVector.fromValidHex(_)),
      rs.getLongNullable("preferred_feerate_sat_kw").map(preferred => ClosingFeerates(
        FeeratePerKw(Satoshi(preferred)),
        FeeratePerKw(Satoshi(rs.getLong("min_feerate_sat_kw"))),
        FeeratePerKw(Satoshi(rs.getLong("max_feerate_sat_kw"))),
      ))
    )
    (channelId, closingParams)
  }

}
