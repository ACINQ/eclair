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
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.PeersDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{getVersion, setVersion, using}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.wire.protocol._
import grizzled.slf4j.Logging
import scodec.bits.BitVector

import java.sql.{Connection, Statement}

object SqlitePeersDb {
  val DB_NAME = "peers"
  val CURRENT_VERSION = 2
}

class SqlitePeersDb(val sqlite: Connection) extends PeersDb with Logging {

  import SqlitePeersDb._
  import SqliteUtils.ExtendedResultSet._

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    def migration12(statement: Statement): Unit = {
      statement.executeUpdate("CREATE TABLE relay_fees (node_id BLOB NOT NULL PRIMARY KEY, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL)")
    }

    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE peers (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
        statement.executeUpdate("CREATE TABLE relay_fees (node_id BLOB NOT NULL PRIMARY KEY, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL)")
      case Some(v@1) =>
        logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
        migration12(statement)
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addOrUpdatePeer(nodeId: Crypto.PublicKey, nodeaddress: NodeAddress): Unit = withMetrics("peers/add-or-update", DbBackends.Sqlite) {
    val data = CommonCodecs.nodeaddress.encode(nodeaddress).require.toByteArray
    using(sqlite.prepareStatement("UPDATE peers SET data=? WHERE node_id=?")) { update =>
      update.setBytes(1, data)
      update.setBytes(2, nodeId.value.toArray)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO peers VALUES (?, ?)")) { statement =>
          statement.setBytes(1, nodeId.value.toArray)
          statement.setBytes(2, data)
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
  }

  override def getPeer(nodeId: PublicKey): Option[NodeAddress] = withMetrics("peers/get", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT data FROM peers WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeQuery()
        .mapCodec(CommonCodecs.nodeaddress)
        .headOption
    }
  }

  override def listPeers(): Map[PublicKey, NodeAddress] = withMetrics("peers/list", DbBackends.Sqlite) {
    using(sqlite.createStatement()) { statement =>
      statement.executeQuery("SELECT node_id, data FROM peers")
        .map { rs =>
          val nodeid = PublicKey(rs.getByteVector("node_id"))
          val nodeaddress = CommonCodecs.nodeaddress.decode(BitVector(rs.getBytes("data"))).require.value
          nodeid -> nodeaddress
        }
        .toMap
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
}
