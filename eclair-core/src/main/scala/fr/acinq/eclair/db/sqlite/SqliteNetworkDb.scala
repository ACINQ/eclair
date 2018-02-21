package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi}
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.LightningMessageCodecs.nodeAnnouncementCodec
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}

import scala.collection.immutable.Queue

class SqliteNetworkDb(sqlite: Connection) extends NetworkDb {

  import SqliteUtils._

  using(sqlite.createStatement()) { statement =>
    statement.execute("PRAGMA foreign_keys = ON")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS nodes (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channels (short_channel_id INTEGER NOT NULL PRIMARY KEY, node_id_1 BLOB NOT NULL, node_id_2 BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_updates (short_channel_id INTEGER NOT NULL, node_flag INTEGER NOT NULL, timestamp INTEGER NOT NULL, flags BLOB NOT NULL, cltv_expiry_delta INTEGER NOT NULL, htlc_minimum_msat INTEGER NOT NULL, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL, PRIMARY KEY(short_channel_id, node_flag), FOREIGN KEY(short_channel_id) REFERENCES channels(short_channel_id))")
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

  override def listNodes(): Seq[NodeAnnouncement] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT data FROM nodes")
      codecSequence(rs, nodeAnnouncementCodec)
    }
  }

  override def addChannel(c: ChannelAnnouncement, txid: BinaryData, capacity: Satoshi): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channels VALUES (?, ?, ?)")) { statement =>
      statement.setLong(1, c.shortChannelId)
      statement.setBytes(2, c.nodeId1.value.toBin(false).toArray) // we store uncompressed public keys
      statement.setBytes(3, c.nodeId2.value.toBin(false).toArray)
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

  override def listChannels(): Map[ChannelAnnouncement, (BinaryData, Satoshi)] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT * FROM channels")
      var m: Map[ChannelAnnouncement, (BinaryData, Satoshi)] = Map()
      val emptyTxid = BinaryData("")
      val zeroCapacity = Satoshi(0)
      while (rs.next()) {
        m = m + (ChannelAnnouncement(
          nodeSignature1 = null,
          nodeSignature2 = null,
          bitcoinSignature1 = null,
          bitcoinSignature2 = null,
          features = null,
          chainHash = null,
          shortChannelId = rs.getLong("short_channel_id"),
          nodeId1 = PublicKey(PublicKey(rs.getBytes("node_id_1")).value, compressed = true), // we read as uncompressed, and convert to compressed
          nodeId2 = PublicKey(PublicKey(rs.getBytes("node_id_2")).value, compressed = true),
          bitcoinKey1 = null,
          bitcoinKey2 = null) -> (emptyTxid, zeroCapacity))
      }
      m
    }
  }

  override def addChannelUpdate(u: ChannelUpdate): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channel_updates VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, u.shortChannelId)
      statement.setBoolean(2, Announcements.isNode1(u.flags))
      statement.setLong(3, u.timestamp)
      statement.setBytes(4, u.flags)
      statement.setInt(5, u.cltvExpiryDelta)
      statement.setLong(6, u.htlcMinimumMsat)
      statement.setLong(7, u.feeBaseMsat)
      statement.setLong(8, u.feeProportionalMillionths)
      statement.executeUpdate()
    }
  }

  override def updateChannelUpdate(u: ChannelUpdate): Unit = {
    using(sqlite.prepareStatement("UPDATE channel_updates SET timestamp=?, flags=?, cltv_expiry_delta=?, htlc_minimum_msat=?, fee_base_msat=?, fee_proportional_millionths=? WHERE short_channel_id=? AND node_flag=?")) { statement =>
      statement.setLong(1, u.timestamp)
      statement.setBytes(2, u.flags)
      statement.setInt(3, u.cltvExpiryDelta)
      statement.setLong(4, u.htlcMinimumMsat)
      statement.setLong(5, u.feeBaseMsat)
      statement.setLong(6, u.feeProportionalMillionths)
      statement.setLong(7, u.shortChannelId)
      statement.setBoolean(8, Announcements.isNode1(u.flags))
      statement.executeUpdate()
    }
  }

  override def listChannelUpdates(): Seq[ChannelUpdate] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT * FROM channel_updates")
      var q: Queue[ChannelUpdate] = Queue()
      while (rs.next()) {
        q = q :+ ChannelUpdate(
          signature = null,
          chainHash = null,
          shortChannelId = rs.getLong("short_channel_id"),
          timestamp = rs.getLong("timestamp"),
          flags = rs.getBytes("flags"),
          cltvExpiryDelta = rs.getInt("cltv_expiry_delta"),
          htlcMinimumMsat = rs.getLong("htlc_minimum_msat"),
          feeBaseMsat = rs.getLong("fee_base_msat"),
          feeProportionalMillionths = rs.getLong("fee_proportional_millionths"))
      }
      q
    }
  }

}
