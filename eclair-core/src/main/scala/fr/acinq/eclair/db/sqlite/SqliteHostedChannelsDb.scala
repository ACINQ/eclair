package fr.acinq.eclair.db.sqlite

import fr.acinq.eclair.db.HostedChannelsDb
import grizzled.slf4j.Logging
import java.sql.Connection

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.HostedChannelCodecs.HOSTED_DATA_COMMITMENTS_Codec
import fr.acinq.eclair.channel.HOSTED_DATA_COMMITMENTS
import scodec.bits.BitVector

import scala.collection.immutable.Queue

class SqliteHostedChannelsDb(sqlite: Connection) extends HostedChannelsDb with Logging {

  import SqliteUtils._

  val DB_NAME = "hosted_channels"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS used_short_channel_ids (short_channel_id INTEGER PRIMARY KEY)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_hosted_channels (channel_id BLOB NOT NULL, in_flight_htlcs INTEGER NOT NULL, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS local_hosted_channels_in_flight_htlcs_idx ON local_hosted_channels(in_flight_htlcs)")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS local_hosted_channels_channel_id_idx ON local_hosted_channels(channel_id)")
  }

  override def addOrUpdateChannel(state: HOSTED_DATA_COMMITMENTS): Unit = {
    val data = HOSTED_DATA_COMMITMENTS_Codec.encode(state).require.toByteArray
    using (sqlite.prepareStatement("UPDATE local_hosted_channels SET data=?, in_flight_htlcs=? WHERE channel_id=?")) { update =>
      update.setBytes(1, data)
      update.setLong(2, state.currentAndNextInFlight.size)
      update.setBytes(3, state.channelId.toArray)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO local_hosted_channels VALUES (?, ?, ?)")) { statement =>
          statement.setBytes(1, state.channelId.toArray)
          statement.setLong(2, state.currentAndNextInFlight.size)
          statement.setBytes(3, data)
          statement.executeUpdate()
        }
      }
    }
  }

  override def getChannel(channelId: ByteVector32): Option[HOSTED_DATA_COMMITMENTS] = {
    using(sqlite.prepareStatement("SELECT data FROM local_hosted_channels WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      val rs = statement.executeQuery()
      if (rs.next()) {
        val rawData = BitVector(rs.getBytes("data"))
        val decodedData = HOSTED_DATA_COMMITMENTS_Codec.decode(rawData)
        Some(decodedData.require.value)
      } else {
        None
      }
    }
  }

  override def listHotChannels(): Set[HOSTED_DATA_COMMITMENTS] = {
    using(sqlite.prepareStatement("SELECT data FROM local_hosted_channels WHERE in_flight_htlcs > 0")) { statement =>
      val rs = statement.executeQuery()
      var q: Queue[HOSTED_DATA_COMMITMENTS] = Queue()
      while (rs.next()) {
        val rawData = BitVector(rs.getBytes("data"))
        val decodedData = HOSTED_DATA_COMMITMENTS_Codec.decode(rawData)
        q = q :+ decodedData.require.value
      }
      q.toSet
    }
  }

  override def markShortChannelIdAsUsed(shortChannelId: ShortChannelId): Unit = {
    using(sqlite.prepareStatement("INSERT INTO used_short_channel_ids VALUES (?)")) { statement =>
      statement.setLong(1, shortChannelId.toLong)
      statement.executeUpdate()
    }
  }

  override def getNewShortChannelId: Long = {
    using(sqlite.prepareStatement("SELECT MAX(short_channel_id) as current FROM used_short_channel_ids")) { statement =>
      val rs = statement.executeQuery()
      if (rs.next()) {
        rs.getLong("current") + 1
      } else {
        1
      }
    }
  }
}