package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.wire.ChannelCodecs.stateDataCodec

class SqliteChannelsDb(sqlite: Connection) extends ChannelsDb {

  import SqliteUtils._

  using(sqlite.createStatement()) { statement =>
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
  }

  override def addOrUpdateChannel(state: HasCommitments): Unit = {
    val data = stateDataCodec.encode(state).require.toByteArray
    using (sqlite.prepareStatement("UPDATE local_channels SET data=? WHERE channel_id=?")) { update =>
      update.setBytes(1, data)
      update.setBytes(2, state.channelId)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO local_channels VALUES (?, ?)")) { statement =>
          statement.setBytes(1, state.channelId)
          statement.setBytes(2, data)
          statement.executeUpdate()
        }
      }
    }
  }

  override def removeChannel(channelId: BinaryData): Unit = {
    using(sqlite.prepareStatement("DELETE FROM pending_relay WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId)
      statement.executeUpdate()
    }

    using(sqlite.prepareStatement("DELETE FROM local_channels WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId)
      statement.executeUpdate()
    }
  }

  override def listChannels(): Seq[HasCommitments] = {
    using(sqlite.createStatement) { statement =>
      val rs = statement.executeQuery("SELECT data FROM local_channels")
      codecSequence(rs, stateDataCodec)
    }
  }
}
