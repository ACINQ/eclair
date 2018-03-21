package fr.acinq.eclair.db.sqlite

import java.net.InetSocketAddress
import java.sql.Connection

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.PeersDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{getVersion, using}
import fr.acinq.eclair.wire.LightningMessageCodecs.socketaddress
import scodec.bits.BitVector

class SqlitePeersDb(sqlite: Connection) extends PeersDb {

  val DB_NAME = "peers"
  val CURRENT_VERSION = 0

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS peers (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
  }

  override def addOrUpdatePeer(nodeId: Crypto.PublicKey, address: InetSocketAddress): Unit = {
    val data = socketaddress.encode(address).require.toByteArray
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

  override def listPeers(): Map[PublicKey, InetSocketAddress] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT node_id, data FROM peers")
      var m: Map[PublicKey, InetSocketAddress] = Map()
      while (rs.next()) {
        m += (PublicKey(rs.getBytes("node_id")) -> socketaddress.decode(BitVector(rs.getBytes("data"))).require.value)
      }
      m
    }
  }
}
