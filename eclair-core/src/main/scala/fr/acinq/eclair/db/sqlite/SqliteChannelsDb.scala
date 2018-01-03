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
    statement.close()
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
      statement.close()
    }
    update.close()
  }

  override def removeChannel(channelId: BinaryData): Unit = {
    val statement1 = sqlite.prepareStatement("DELETE FROM preimages WHERE channel_id=?")
    statement1.setBytes(1, channelId)
    statement1.executeUpdate()
    statement1.close()

    val statement2 = sqlite.prepareStatement("DELETE FROM local_channels WHERE channel_id=?")
    statement2.setBytes(1, channelId)
    statement2.executeUpdate()
    statement2.close()
  }

  override def listChannels(): List[HasCommitments] = {
    val statement = sqlite.createStatement
    try {
      val rs = statement.executeQuery("SELECT data FROM local_channels")
      codecList(rs, stateDataCodec)
    } finally {
      statement.close()
    }

  }
}
