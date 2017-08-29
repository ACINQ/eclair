package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.wire.ChannelCodecs.stateDataCodec

class SqliteChannelsDb(sqlite: Connection) extends ChannelsDb {

  import SqliteUtils._

  {
    val statement = sqlite.createStatement
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
  }

  override def addOrUpdateChannel(state: HasCommitments): Unit = {
    val data = stateDataCodec.encode(state).require.toByteArray
    val update = sqlite.prepareStatement("UPDATE local_channels SET data=? WHERE channel_id=?")
    update.setBytes(1, data)
    update.setBytes(2, state.channelId)
    if (update.executeUpdate() == 0) {
      val statement = sqlite.prepareStatement("INSERT INTO local_channels VALUES (?, ?)")
      statement.setBytes(1, state.channelId)
      statement.setBytes(2, data)
      statement.executeUpdate()
    }
  }

  override def removeChannel(channelId: BinaryData): Unit = {
    val statement = sqlite.prepareStatement("DELETE FROM local_channels WHERE channel_id=?")
    statement.setBytes(1, channelId)
    statement.executeUpdate()
  }

  override def listChannels(): Iterator[HasCommitments] = {
    val rs = sqlite.createStatement.executeQuery("SELECT data FROM local_channels")
    codecIterator(rs, stateDataCodec)
  }
}
