package fr.acinq.eclair.db.sqlite

import java.sql.{Connection, ResultSet}

import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.LightningMessageCodecs.{channelAnnouncementCodec, channelUpdateCodec, nodeAnnouncementCodec}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import scodec.Codec
import scodec.bits.BitVector

class SqliteNetworkDb(sqlite: Connection) extends NetworkDb {

  import SqliteUtils._

  {
    val statement = sqlite.createStatement
    statement.execute("PRAGMA foreign_keys = ON")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS nodes (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channels (short_channel_id INTEGER NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_updates (short_channel_id INTEGER NOT NULL, node_flag INTEGER NOT NULL, data BLOB NOT NULL, FOREIGN KEY(short_channel_id) REFERENCES channels(short_channel_id))")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_updates_idx ON channel_updates(short_channel_id)")
  }

  override def addNode(n: NodeAnnouncement): Unit = {
    val statement = sqlite.prepareStatement("INSERT OR IGNORE INTO nodes VALUES (?, ?)")
    statement.setBytes(1, n.nodeId.toBin)
    statement.setBytes(2, nodeAnnouncementCodec.encode(n).require.toByteArray)
    statement.executeUpdate()
  }

  override def updateNode(n: NodeAnnouncement): Unit = {
    val statement = sqlite.prepareStatement("UPDATE nodes SET data=? WHERE node_id=?")
    statement.setBytes(1, nodeAnnouncementCodec.encode(n).require.toByteArray)
    statement.setBytes(2, n.nodeId.toBin)
    statement.executeUpdate()
  }

  override def removeNode(nodeId: Crypto.PublicKey): Unit = {
    val statement = sqlite.prepareStatement("DELETE FROM nodes WHERE node_id=?")
    statement.setBytes(1, nodeId.toBin)
    statement.executeUpdate()
  }

  override def listNodes(): Iterator[NodeAnnouncement] = {
    val rs = sqlite.createStatement.executeQuery("SELECT data FROM nodes")
    codecIterator(rs, nodeAnnouncementCodec)
  }

  override def addChannel(c: ChannelAnnouncement): Unit = {
    val statement = sqlite.prepareStatement("INSERT INTO channels VALUES (?, ?)")
    statement.setLong(1, c.shortChannelId)
    statement.setBytes(2, channelAnnouncementCodec.encode(c).require.toByteArray)
    statement.executeUpdate()
  }

  override def removeChannel(shortChannelId: Long): Unit = {
    val statement = sqlite.createStatement
    statement.execute("BEGIN TRANSACTION")
    statement.executeUpdate(s"DELETE FROM channel_updates WHERE short_channel_id=$shortChannelId")
    statement.executeUpdate(s"DELETE FROM channels WHERE short_channel_id=$shortChannelId")
    statement.execute("COMMIT TRANSACTION")
  }

  override def listChannels(): Iterator[ChannelAnnouncement] = {
    val rs = sqlite.createStatement.executeQuery("SELECT data FROM channels")
    codecIterator(rs, channelAnnouncementCodec)
  }

  override def addChannelUpdate(u: ChannelUpdate): Unit = {
    val statement = sqlite.prepareStatement("INSERT INTO channel_updates VALUES (?, ?, ?)")
    statement.setLong(1, u.shortChannelId)
    statement.setBoolean(2, Announcements.isNode1(u.flags))
    statement.setBytes(3, channelUpdateCodec.encode(u).require.toByteArray)
    statement.executeUpdate()
  }

  override def updateChannelUpdate(u: ChannelUpdate): Unit = {
    val statement = sqlite.prepareStatement("UPDATE channel_updates SET data=? WHERE short_channel_id=? AND node_flag=?")
    statement.setBytes(1, channelUpdateCodec.encode(u).require.toByteArray)
    statement.setLong(2, u.shortChannelId)
    statement.setBoolean(3, Announcements.isNode1(u.flags))
    statement.executeUpdate()
  }

  override def listChannelUpdates(): Iterator[ChannelUpdate] = {
    val rs = sqlite.createStatement.executeQuery("SELECT data FROM channel_updates")
    codecIterator(rs, channelUpdateCodec)
  }

}
