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

import fr.acinq.bitcoin.scalacompat.Crypto
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.PeersDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{getVersion, setVersion, using}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Features, InitFeature, MilliSatoshi, TimestampSecond}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import java.sql.{Connection, Statement}

object SqlitePeersDb {
  val DB_NAME = "peers"
  val CURRENT_VERSION = 4
}

class SqlitePeersDb(val sqlite: Connection) extends PeersDb with Logging {

  import SqlitePeersDb._
  import SqliteUtils.ExtendedResultSet._

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    def migration12(statement: Statement): Unit = {
      statement.executeUpdate("CREATE TABLE relay_fees (node_id BLOB NOT NULL PRIMARY KEY, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL)")
    }

    def migration23(statement: Statement): Unit = {
      statement.executeUpdate("CREATE TABLE peer_storage (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL, last_updated_at INTEGER NOT NULL, removed_peer_at INTEGER)")
      statement.executeUpdate("CREATE INDEX removed_peer_at_idx ON peer_storage(removed_peer_at)")
    }

    def migration34(statement: Statement): Unit = {
      statement.executeUpdate("CREATE TABLE peers_v4 (node_id BLOB NOT NULL PRIMARY KEY, node_address BLOB, node_features BLOB)")
      statement.executeUpdate("INSERT INTO peers_v4 (node_id, node_address, node_features) SELECT node_id, data, NULL FROM peers")
      statement.executeUpdate("DROP TABLE peers")
      statement.executeUpdate("ALTER TABLE peers_v4 RENAME TO peers")
    }

    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE peers (node_id BLOB NOT NULL PRIMARY KEY, node_address BLOB, node_features BLOB)")
        statement.executeUpdate("CREATE TABLE relay_fees (node_id BLOB NOT NULL PRIMARY KEY, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE peer_storage (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL, last_updated_at INTEGER NOT NULL, removed_peer_at INTEGER)")

        statement.executeUpdate("CREATE INDEX removed_peer_at_idx ON peer_storage(removed_peer_at)")
      case Some(v@(1 | 2 | 3)) =>
        logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
        if (v < 2) {
          migration12(statement)
        }
        if (v < 3) {
          migration23(statement)
        }
        if (v < 4) {
          migration34(statement)
        }
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addOrUpdatePeer(nodeId: Crypto.PublicKey, address: NodeAddress, features: Features[InitFeature]): Unit = withMetrics("peers/add-or-update", DbBackends.Sqlite) {
    val encodedFeatures = CommonCodecs.initFeaturesCodec.encode(features).require.toByteArray
    val encodedAddress = CommonCodecs.nodeaddress.encode(address).require.toByteArray
    using(sqlite.prepareStatement("UPDATE peers SET node_address=?, node_features=? WHERE node_id=?")) { update =>
      update.setBytes(1, encodedAddress)
      update.setBytes(2, encodedFeatures)
      update.setBytes(3, nodeId.value.toArray)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO peers VALUES (?, ?, ?)")) { statement =>
          statement.setBytes(1, nodeId.value.toArray)
          statement.setBytes(2, encodedAddress)
          statement.setBytes(3, encodedFeatures)
          statement.executeUpdate()
        }
      }
    }
  }

  override def addOrUpdatePeerFeatures(nodeId: Crypto.PublicKey, features: Features[InitFeature]): Unit = withMetrics("peers/add-or-update", DbBackends.Sqlite) {
    val encodedFeatures = CommonCodecs.initFeaturesCodec.encode(features).require.toByteArray
    using(sqlite.prepareStatement("UPDATE peers SET node_features=? WHERE node_id=?")) { update =>
      update.setBytes(1, encodedFeatures)
      update.setBytes(2, nodeId.value.toArray)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO peers VALUES (?, NULL, ?)")) { statement =>
          statement.setBytes(1, nodeId.value.toArray)
          statement.setBytes(2, encodedFeatures)
          statement.executeUpdate()
        }
      }
    }
  }

  override def removePeer(nodeId: Crypto.PublicKey): Unit = withMetrics("peers/remove", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("DELETE FROM peers WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeUpdate()
    }
    using(sqlite.prepareStatement("UPDATE peer_storage SET removed_peer_at = ? WHERE node_id = ?")) { statement =>
      statement.setLong(1, TimestampSecond.now().toLong)
      statement.setBytes(2, nodeId.value.toArray)
      statement.executeUpdate()
    }
  }

  override def removePeerStorage(peerRemovedBefore: TimestampSecond): Unit = withMetrics("peers/remove-storage", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("DELETE FROM peer_storage WHERE removed_peer_at < ?")) { statement =>
      statement.setLong(1, peerRemovedBefore.toLong)
      statement.executeUpdate()
    }
  }

  override def getPeer(nodeId: PublicKey): Option[NodeInfo] = withMetrics("peers/get", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT node_address, node_features FROM peers WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeQuery().map { rs =>
        val nodeAddress_opt = rs.getBitVectorOpt("node_address").map(CommonCodecs.nodeaddress.decode(_).require.value)
        val nodeFeatures_opt = rs.getBitVectorOpt("node_features").map(CommonCodecs.initFeaturesCodec.decode(_).require.value)
        NodeInfo(nodeFeatures_opt.getOrElse(Features.empty), nodeAddress_opt)
      }.headOption
    }
  }

  override def listPeers(): Map[PublicKey, NodeInfo] = withMetrics("peers/list", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT node_id, node_address, node_features FROM peers")) { statement =>
      statement.executeQuery().map { rs =>
        val nodeId = PublicKey(rs.getByteVector("node_id"))
        val nodeAddress_opt = rs.getBitVectorOpt("node_address").map(CommonCodecs.nodeaddress.decode(_).require.value)
        val nodeFeatures_opt = rs.getBitVectorOpt("node_features").map(CommonCodecs.initFeaturesCodec.decode(_).require.value)
        nodeId -> NodeInfo(nodeFeatures_opt.getOrElse(Features.empty), nodeAddress_opt)
      }.toMap
    }
  }

  override def addOrUpdateRelayFees(nodeId: Crypto.PublicKey, fees: RelayFees): Unit = withMetrics("peers/add-or-update-relay-fees", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE relay_fees SET fee_base_msat=?, fee_proportional_millionths=? WHERE node_id=?")) { update =>
      update.setLong(1, fees.feeBase.toLong)
      update.setLong(2, fees.feeProportionalMillionths)
      update.setBytes(3, nodeId.value.toArray)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO relay_fees VALUES (?, ?, ?)")) { statement =>
          statement.setBytes(1, nodeId.value.toArray)
          statement.setLong(2, fees.feeBase.toLong)
          statement.setLong(3, fees.feeProportionalMillionths)
          statement.executeUpdate()
        }
      }
    }
  }

  override def getRelayFees(nodeId: PublicKey): Option[RelayFees] = withMetrics("peers/get-relay-fees", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT fee_base_msat, fee_proportional_millionths FROM relay_fees WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeQuery()
        .headOption
        .map(rs =>
          RelayFees(MilliSatoshi(rs.getLong("fee_base_msat")), rs.getLong("fee_proportional_millionths"))
        )
    }
  }

  override def updateStorage(nodeId: PublicKey, data: ByteVector): Unit = withMetrics("peers/update-storage", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE peer_storage SET data = ?, last_updated_at = ?, removed_peer_at = NULL WHERE node_id = ?")) { update =>
      update.setBytes(1, data.toArray)
      update.setLong(2, TimestampSecond.now().toLong)
      update.setBytes(3, nodeId.value.toArray)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO peer_storage VALUES (?, ?, ?, NULL)")) { statement =>
          statement.setBytes(1, nodeId.value.toArray)
          statement.setBytes(2, data.toArray)
          statement.setLong(3, TimestampSecond.now().toLong)
          statement.executeUpdate()
        }
      }
    }
  }

  override def getStorage(nodeId: PublicKey): Option[ByteVector] = withMetrics("peers/get-storage", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT data FROM peer_storage WHERE node_id = ?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeQuery()
        .headOption
        .map(rs => ByteVector(rs.getBytes("data")))
    }
  }
}
