package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.Command
import fr.acinq.eclair.db.PendingRelayDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{codecSequence, using}
import fr.acinq.eclair.wire.CommandCodecs.cmdCodec

class SqlitePendingRelayDb(sqlite: Connection) extends PendingRelayDb {

  using(sqlite.createStatement()) { statement =>
    // note: should we use a foreign key to local_channels table here?
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS pending_relay (channel_id BLOB NOT NULL, htlc_id INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(channel_id, htlc_id))")
  }

  override def addPendingRelay(channelId: BinaryData, htlcId: Long, cmd: Command): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO pending_relay VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, channelId)
      statement.setLong(2, htlcId)
      statement.setBytes(3, cmdCodec.encode(cmd).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def removePendingRelay(channelId: BinaryData, htlcId: Long): Unit = {
    using(sqlite.prepareStatement("DELETE FROM pending_relay WHERE channel_id=? AND htlc_id=?")) { statement =>
      statement.setBytes(1, channelId)
      statement.setLong(2, htlcId)
      statement.executeUpdate()
    }
  }

  override def listPendingRelay(channelId: BinaryData): Seq[Command] = {
    using(sqlite.prepareStatement("SELECT htlc_id, data FROM pending_relay WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId)
      val rs = statement.executeQuery()
      codecSequence(rs, cmdCodec)
    }
  }

}
