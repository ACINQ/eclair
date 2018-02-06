package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.LightningMessageCodecs.{channelAnnouncementCodec, channelUpdateCodec, nodeAnnouncementCodec}
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import scodec.bits.BitVector

class SqliteNetworkDb(sqlite: Connection) extends NetworkDb {

  import SqliteUtils._

  using(sqlite.createStatement()) { statement =>
    statement.execute("PRAGMA foreign_keys = ON")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS nodes (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channels (short_channel_id INTEGER NOT NULL PRIMARY KEY, txid STRING NOT NULL, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_updates (short_channel_id INTEGER NOT NULL, node_flag INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(short_channel_id, node_flag), FOREIGN KEY(short_channel_id) REFERENCES channels(short_channel_id))")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_updates_idx ON channel_updates(short_channel_id)")
  }

  override def addNode(n: NodeAnnouncement): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO nodes VALUES (?, ?)")) { statement =>
      statement.setBytes(1, n.nodeId.toBin)
      statement.setBytes(2, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def updateNode(n: NodeAnnouncement): Unit = {
    using(sqlite.prepareStatement("UPDATE nodes SET data=? WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.setBytes(2, n.nodeId.toBin)
      statement.executeUpdate()
    }
  }

  override def removeNode(nodeId: Crypto.PublicKey): Unit = {
    using(sqlite.prepareStatement("DELETE FROM nodes WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.toBin)
      statement.executeUpdate()
    }
  }

  override def listNodes(): List[NodeAnnouncement] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT data FROM nodes")
      codecList(rs, nodeAnnouncementCodec)
    }
  }

  override def addChannel(c: ChannelAnnouncement, txid: BinaryData): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channels VALUES (?, ?, ?)")) { statement =>
      statement.setLong(1, c.shortChannelId)
      statement.setString(2, txid.toString())
      statement.setBytes(3, channelAnnouncementCodec.encode(c).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def removeChannel(shortChannelId: Long): Unit = {
    using(sqlite.createStatement) { statement =>
      statement.execute("BEGIN TRANSACTION")
      statement.executeUpdate(s"DELETE FROM channel_updates WHERE short_channel_id=$shortChannelId")
      statement.executeUpdate(s"DELETE FROM channels WHERE short_channel_id=$shortChannelId")
      statement.execute("COMMIT TRANSACTION")
    }
  }

  override def listChannels(): Map[ChannelAnnouncement, BinaryData] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT data, txid FROM channels")
      var l: Map[ChannelAnnouncement, BinaryData] = Map()
      while (rs.next()) {
        l = l + (channelAnnouncementCodec.decode(BitVector(rs.getBytes("data"))).require.value -> BinaryData(rs.getString("txid")))
      }
      l
    }
  }

  override def addChannelUpdate(u: ChannelUpdate): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channel_updates VALUES (?, ?, ?)")) { statement =>
      statement.setLong(1, u.shortChannelId)
      statement.setBoolean(2, Announcements.isNode1(u.flags))
      statement.setBytes(3, channelUpdateCodec.encode(u).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def updateChannelUpdate(u: ChannelUpdate): Unit = {
    using(sqlite.prepareStatement("UPDATE channel_updates SET data=? WHERE short_channel_id=? AND node_flag=?")) { statement =>
      statement.setBytes(1, channelUpdateCodec.encode(u).require.toByteArray)
      statement.setLong(2, u.shortChannelId)
      statement.setBoolean(3, Announcements.isNode1(u.flags))
      statement.executeUpdate()
    }
  }

  override def listChannelUpdates(): List[ChannelUpdate] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT data FROM channel_updates")
      codecList(rs, channelUpdateCodec)
    }
  }

}
