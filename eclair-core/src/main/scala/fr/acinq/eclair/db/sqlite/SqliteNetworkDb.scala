/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, Satoshi}
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.router.PublicChannel
import fr.acinq.eclair.wire.LightningMessageCodecs.nodeAnnouncementCodec
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import grizzled.slf4j.Logging
import scodec.Codec
import scodec.bits.ByteVector

import scala.collection.immutable.SortedMap

class SqliteNetworkDb(sqlite: Connection, chainHash: ByteVector32) extends NetworkDb with Logging {

  import SqliteUtils._
  import SqliteUtils.ExtendedResultSet._

  val DB_NAME = "network"
  val CURRENT_VERSION = 2

  import fr.acinq.eclair.wire.CommonCodecs._
  import scodec.codecs._

  // on Android we prune as many fields as possible to save memory
  val channelAnnouncementWitnessCodec =
    ("features" | provide(null.asInstanceOf[ByteVector])) ::
      ("chainHash" | provide(null.asInstanceOf[ByteVector32])) ::
      ("shortChannelId" | shortchannelid) ::
      ("nodeId1" | publicKey) ::
      ("nodeId2" | publicKey) ::
      ("bitcoinKey1" | provide(null.asInstanceOf[PublicKey])) ::
      ("bitcoinKey2" | provide(null.asInstanceOf[PublicKey])) ::
      ("unknownFields" | bytes)

  val channelAnnouncementCodec: Codec[ChannelAnnouncement] = (
    ("nodeSignature1" | provide(null.asInstanceOf[ByteVector64])) ::
      ("nodeSignature2" | provide(null.asInstanceOf[ByteVector64])) ::
      ("bitcoinSignature1" | provide(null.asInstanceOf[ByteVector64])) ::
      ("bitcoinSignature2" | provide(null.asInstanceOf[ByteVector64])) ::
      channelAnnouncementWitnessCodec).as[ChannelAnnouncement]

  val channelUpdateWitnessCodec =
    ("chainHash" | provide(chainHash)) ::
      ("shortChannelId" | shortchannelid) ::
      ("timestamp" | uint32) ::
      (("messageFlags" | byte) >>:~ { messageFlags =>
        ("channelFlags" | byte) ::
          ("cltvExpiryDelta" | cltvExpiryDelta) ::
          ("htlcMinimumMsat" | millisatoshi) ::
          ("feeBaseMsat" | millisatoshi32) ::
          ("feeProportionalMillionths" | uint32) ::
          ("htlcMaximumMsat" | conditional((messageFlags & 1) != 0, millisatoshi)) ::
          ("unknownFields" | bytes)
      })

  val channelUpdateCodec: Codec[ChannelUpdate] = (
    ("signature" | provide(null.asInstanceOf[ByteVector64])) ::
      channelUpdateWitnessCodec).as[ChannelUpdate]

  using(sqlite.createStatement(), inTransaction = true) { statement =>
    getVersion(statement, DB_NAME, CURRENT_VERSION) match {
      case 1 =>
        // channel_update are cheap to retrieve, so let's just wipe them out and they'll get resynced
        // on Android we also wipe the channel db
        statement.execute("PRAGMA foreign_keys = ON")
        logger.warn("migrating network db version 1->2")
        statement.executeUpdate("DROP TABLE channels")
        statement.executeUpdate("DROP TABLE channel_updates")
        statement.execute("PRAGMA foreign_keys = OFF")
        setVersion(statement, DB_NAME, CURRENT_VERSION)
        logger.warn("migration complete")
      case 2 => () // nothing to do
      case unknown => throw new IllegalArgumentException(s"unknown version $unknown for network db")
    }
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS nodes (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS channels (short_channel_id INTEGER NOT NULL PRIMARY KEY, txid TEXT NOT NULL, channel_announcement BLOB NOT NULL, capacity_sat INTEGER NOT NULL, channel_update_1 BLOB NULL, channel_update_2 BLOB NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS pruned (short_channel_id INTEGER NOT NULL PRIMARY KEY)")
  }

  override def addNode(n: NodeAnnouncement): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO nodes VALUES (?, ?)")) { statement =>
      statement.setBytes(1, n.nodeId.value.toArray)
      statement.setBytes(2, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.executeUpdate()
    }
  }

  override def updateNode(n: NodeAnnouncement): Unit = {
    using(sqlite.prepareStatement("UPDATE nodes SET data=? WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeAnnouncementCodec.encode(n).require.toByteArray)
      statement.setBytes(2, n.nodeId.value.toArray)
      statement.executeUpdate()
    }
  }

  override def getNode(nodeId: Crypto.PublicKey): Option[NodeAnnouncement] = {
    using(sqlite.prepareStatement("SELECT data FROM nodes WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      val rs = statement.executeQuery()
      codecSequence(rs, nodeAnnouncementCodec).headOption
    }
  }

  override def removeNode(nodeId: Crypto.PublicKey): Unit = {
    using(sqlite.prepareStatement("DELETE FROM nodes WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeUpdate()
    }
  }

  override def listNodes(): Seq[NodeAnnouncement] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT data FROM nodes")
      codecSequence(rs, nodeAnnouncementCodec)
    }
  }

  override def addChannel(c: ChannelAnnouncement, txid: ByteVector32, capacity: Satoshi): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO channels VALUES (?, ?, ?, ?, NULL, NULL)")) { statement =>
      statement.setLong(1, c.shortChannelId.toLong)
      statement.setString(2, txid.toHex)
      statement.setBytes(3, channelAnnouncementCodec.encode(c).require.toByteArray)
      statement.setLong(4, capacity.toLong)
      statement.executeUpdate()
    }
  }

  override def updateChannel(u: ChannelUpdate): Unit = {
    val column = if (u.isNode1) "channel_update_1" else "channel_update_2"
    using(sqlite.prepareStatement(s"UPDATE channels SET $column=? WHERE short_channel_id=?")) { statement =>
      statement.setBytes(1, channelUpdateCodec.encode(u).require.toByteArray)
      statement.setLong(2, u.shortChannelId.toLong)
      statement.executeUpdate()
    }
  }

  override def listChannels(): SortedMap[ShortChannelId, PublicChannel] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT channel_announcement, txid, capacity_sat, channel_update_1, channel_update_2 FROM channels")
      var m = SortedMap.empty[ShortChannelId, PublicChannel]
      while (rs.next()) {
        val ann = channelAnnouncementCodec.decode(rs.getBitVectorOpt("channel_announcement").get).require.value
        val txId = ByteVector32.fromValidHex(rs.getString("txid"))
        val capacity = rs.getLong("capacity_sat")
        val channel_update_1_opt = rs.getBitVectorOpt("channel_update_1").map(channelUpdateCodec.decode(_).require.value)
        val channel_update_2_opt = rs.getBitVectorOpt("channel_update_2").map(channelUpdateCodec.decode(_).require.value)
        m = m + (ann.shortChannelId -> PublicChannel(ann, txId, Satoshi(capacity), channel_update_1_opt, channel_update_2_opt))
      }
      m
    }
  }

  override def removeChannels(shortChannelIds: Iterable[ShortChannelId]): Unit = {
    using(sqlite.createStatement) { statement =>
    shortChannelIds
      .grouped(1000) // remove channels by batch of 1000
      .foreach {group =>
        val ids = shortChannelIds.map(_.toLong).mkString(",")
        statement.executeUpdate(s"DELETE FROM channels WHERE short_channel_id IN ($ids)")
      }
    }
  }

  override def addToPruned(shortChannelIds: Iterable[ShortChannelId]): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO pruned VALUES (?)"), inTransaction = true) { statement =>
      shortChannelIds.foreach(shortChannelId => {
        statement.setLong(1, shortChannelId.toLong)
        statement.addBatch()
      })
      statement.executeBatch()
    }
  }

  override def removeFromPruned(shortChannelId: ShortChannelId): Unit = {
    using(sqlite.createStatement) { statement =>
      statement.executeUpdate(s"DELETE FROM pruned WHERE short_channel_id=${shortChannelId.toLong}")
    }
  }

  override def isPruned(shortChannelId: ShortChannelId): Boolean = {
    using(sqlite.prepareStatement("SELECT short_channel_id from pruned WHERE short_channel_id=?")) { statement =>
      statement.setLong(1, shortChannelId.toLong)
      val rs = statement.executeQuery()
      rs.next()
    }
  }

  override def close(): Unit = sqlite.close
}
