package fr.acinq.eclair.db.sqlite

import fr.acinq.eclair.db.HostedChannelsDb
import grizzled.slf4j.Logging
import java.sql.Connection

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.HostedChannelCodecs.HOSTED_DATA_COMMITMENTS_Codec
import fr.acinq.eclair.channel.HOSTED_DATA_COMMITMENTS
import scodec.bits.BitVector

class SqliteHostedChannelsDb(sqlite: Connection) extends HostedChannelsDb with Logging {

  import SqliteUtils._

  val DB_NAME = "hosted_channels"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_hosted_channels (short_channel_id INTEGER PRIMARY KEY, channel_id BLOB NOT NULL, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS local_hosted_channels_channel_id_idx ON local_hosted_channels(channel_id)")
  }

  override def addOrUpdateChannel(state: HOSTED_DATA_COMMITMENTS): Unit = {
    val data = HOSTED_DATA_COMMITMENTS_Codec.encode(state).require.toByteArray
    using (sqlite.prepareStatement("UPDATE local_hosted_channels SET data=? WHERE channel_id=?")) { update =>
      update.setBytes(1, data)
      update.setBytes(2, state.channelId.toArray)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO local_hosted_channels VALUES (?, ?, ?)")) { statement =>
          statement.setLong(1, state.shortChannelId.toLong)
          statement.setBytes(2, state.channelId.toArray)
          statement.setBytes(3, data)
          statement.executeUpdate()
        }
      }
    }
  }

  override def getChannelById(channelId: ByteVector32): Option[HOSTED_DATA_COMMITMENTS] = {
    using(sqlite.prepareStatement("SELECT data FROM local_hosted_channels WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(HOSTED_DATA_COMMITMENTS_Codec.decode(BitVector(rs.getBytes("data"))).require.value)
      } else {
        None
      }
    }
  }

  def getChannelByShortId(shortChannelId: ShortChannelId): Option[HOSTED_DATA_COMMITMENTS] = {
    using(sqlite.prepareStatement("SELECT data FROM local_hosted_channels WHERE short_channel_id=?")) { statement =>
      statement.setLong(1, shortChannelId.toLong)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(HOSTED_DATA_COMMITMENTS_Codec.decode(BitVector(rs.getBytes("data"))).require.value)
      } else {
        None
      }
    }
  }

  override def getNewShortChannelId: Long = {
    using(sqlite.prepareStatement("SELECT MAX(short_channel_id) as current FROM local_hosted_channels")) { statement =>
      val rs = statement.executeQuery()
      if (rs.next()) {
        rs.getLong("current") + 1
      } else {
        1
      }
    }
  }
}