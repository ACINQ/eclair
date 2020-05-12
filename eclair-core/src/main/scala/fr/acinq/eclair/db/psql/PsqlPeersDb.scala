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

package fr.acinq.eclair.db.psql

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.PeersDb
import fr.acinq.eclair.db.psql.PsqlUtils.DatabaseLock
import fr.acinq.eclair.wire._
import javax.sql.DataSource
import scodec.bits.BitVector

class PsqlPeersDb(implicit ds: DataSource, lock: DatabaseLock) extends PeersDb {

  import PsqlUtils.ExtendedResultSet._
  import PsqlUtils._
  import lock._

  val DB_NAME = "peers"
  val CURRENT_VERSION = 1

  inTransaction { psql =>
    using(psql.createStatement()) { statement =>
      require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION, s"incompatible version of $DB_NAME DB found") // there is only one version currently deployed
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS peers (node_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL)")
    }
  }

  override def addOrUpdatePeer(nodeId: Crypto.PublicKey, nodeaddress: NodeAddress): Unit = {
    withLock { psql =>
      val data = CommonCodecs.nodeaddress.encode(nodeaddress).require.toByteArray
      using(psql.prepareStatement("UPDATE peers SET data=? WHERE node_id=?")) { update =>
        update.setBytes(1, data)
        update.setString(2, nodeId.value.toHex)
        if (update.executeUpdate() == 0) {
          using(psql.prepareStatement("INSERT INTO peers VALUES (?, ?)")) { statement =>
            statement.setString(1, nodeId.value.toHex)
            statement.setBytes(2, data)
            statement.executeUpdate()
          }
        }
      }
    }
  }

  override def removePeer(nodeId: Crypto.PublicKey): Unit = {
    withLock { psql =>
      using(psql.prepareStatement("DELETE FROM peers WHERE node_id=?")) { statement =>
        statement.setString(1, nodeId.value.toHex)
        statement.executeUpdate()
      }
    }
  }

  override def getPeer(nodeId: PublicKey): Option[NodeAddress] = {
    withLock { psql =>
      using(psql.prepareStatement("SELECT data FROM peers WHERE node_id=?")) { statement =>
        statement.setString(1, nodeId.value.toHex)
        val rs = statement.executeQuery()
        codecSequence(rs, CommonCodecs.nodeaddress).headOption
      }
    }
  }

  override def listPeers(): Map[PublicKey, NodeAddress] = {
    withLock { psql =>
      using(psql.createStatement()) { statement =>
        val rs = statement.executeQuery("SELECT node_id, data FROM peers")
        var m: Map[PublicKey, NodeAddress] = Map()
        while (rs.next()) {
          val nodeid = PublicKey(rs.getByteVectorFromHex("node_id"))
          val nodeaddress = CommonCodecs.nodeaddress.decode(BitVector(rs.getBytes("data"))).require.value
          m += (nodeid -> nodeaddress)
        }
        m
      }
    }
  }

  override def close(): Unit = ()
}
