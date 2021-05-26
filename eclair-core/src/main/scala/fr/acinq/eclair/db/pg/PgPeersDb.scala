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

package fr.acinq.eclair.db.pg

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.PeersDb
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.wire.protocol._
import scodec.bits.BitVector

import javax.sql.DataSource

class PgPeersDb(implicit ds: DataSource, lock: PgLock) extends PeersDb {

  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import lock._

  val DB_NAME = "peers"
  val CURRENT_VERSION = 1

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>
      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE TABLE peers (node_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL)")
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def addOrUpdatePeer(nodeId: Crypto.PublicKey, nodeaddress: NodeAddress): Unit = withMetrics("peers/add-or-update", DbBackends.Postgres) {
    withLock { pg =>
      val data = CommonCodecs.nodeaddress.encode(nodeaddress).require.toByteArray
      using(pg.prepareStatement(
        """
          | INSERT INTO peers (node_id, data)
          | VALUES (?, ?)
          | ON CONFLICT (node_id)
          | DO UPDATE SET data = EXCLUDED.data ;
          | """.stripMargin)) { statement =>
        statement.setString(1, nodeId.value.toHex)
        statement.setBytes(2, data)
        statement.executeUpdate()
      }
    }
  }

  override def removePeer(nodeId: Crypto.PublicKey): Unit = withMetrics("peers/remove", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("DELETE FROM peers WHERE node_id=?")) { statement =>
        statement.setString(1, nodeId.value.toHex)
        statement.executeUpdate()
      }
    }
  }

  override def getPeer(nodeId: PublicKey): Option[NodeAddress] = withMetrics("peers/get", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT data FROM peers WHERE node_id=?")) { statement =>
        statement.setString(1, nodeId.value.toHex)
        statement.executeQuery()
          .mapCodec(CommonCodecs.nodeaddress)
          .headOption
      }
    }
  }

  override def listPeers(): Map[PublicKey, NodeAddress] = withMetrics("peers/list", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.createStatement()) { statement =>
        statement.executeQuery("SELECT node_id, data FROM peers")
          .map { rs =>
            val nodeid = PublicKey(rs.getByteVectorFromHex("node_id"))
            val nodeaddress = CommonCodecs.nodeaddress.decode(BitVector(rs.getBytes("data"))).require.value
            nodeid -> nodeaddress
          }
          .toMap
      }
    }
  }

  override def close(): Unit = ()
}
