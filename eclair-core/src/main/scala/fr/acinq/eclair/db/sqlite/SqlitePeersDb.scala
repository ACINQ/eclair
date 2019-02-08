/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.PeersDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{getVersion, using}
import fr.acinq.eclair.wire._
import scodec.bits.BitVector

class SqlitePeersDb(sqlite: Connection) extends PeersDb {

  val DB_NAME = "peers"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS peers (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
  }

  override def addOrUpdatePeer(nodeId: Crypto.PublicKey, nodeaddress: NodeAddress): Unit = {
    val data = LightningMessageCodecs.nodeaddress.encode(nodeaddress).require.toByteArray
    using(sqlite.prepareStatement("UPDATE peers SET data=? WHERE node_id=?")) { update =>
      update.setBytes(1, data)
      update.setBytes(2, nodeId.toBin)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO peers VALUES (?, ?)")) { statement =>
          statement.setBytes(1, nodeId.toBin)
          statement.setBytes(2, data)
          statement.executeUpdate()
        }
      }
    }
  }

  override def removePeer(nodeId: Crypto.PublicKey): Unit = {
    using(sqlite.prepareStatement("DELETE FROM peers WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.toBin)
      statement.executeUpdate()
    }
  }

  override def listPeers(): Map[PublicKey, NodeAddress] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT node_id, data FROM peers")
      var m: Map[PublicKey, NodeAddress] = Map()
      while (rs.next()) {
        val nodeid = PublicKey(rs.getBytes("node_id"))
        val nodeaddress = LightningMessageCodecs.nodeaddress.decode(BitVector(rs.getBytes("data"))).require.value
        m += (nodeid -> nodeaddress)
      }
      m
    }
  }

  override def close(): Unit = sqlite.close()
}
