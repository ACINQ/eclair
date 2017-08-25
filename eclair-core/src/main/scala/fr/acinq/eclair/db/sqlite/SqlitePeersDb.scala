package fr.acinq.eclair.db.sqlite

import java.net.InetSocketAddress
import java.sql.Connection

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.PeersDb
import fr.acinq.eclair.wire.LightningMessageCodecs.socketaddress
import scodec.bits.BitVector

class SqlitePeersDb(sqlite: Connection) extends PeersDb {

  {
    val statement = sqlite.createStatement
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS peers (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
  }

  override def addOrUpdatePeer(nodeId: Crypto.PublicKey, address: InetSocketAddress): Unit = {
    val data = socketaddress.encode(address).require.toByteArray
    val update = sqlite.prepareStatement("UPDATE peers SET data=? WHERE node_id=?")
    update.setBytes(1, data)
    update.setBytes(2, nodeId.toBin)
    if (update.executeUpdate() == 0) {
      val statement = sqlite.prepareStatement("INSERT INTO peers VALUES (?, ?)")
      statement.setBytes(1, nodeId.toBin)
      statement.setBytes(2, data)
      statement.executeUpdate()
    }
  }

  override def removePeer(nodeId: Crypto.PublicKey): Unit = {
    val statement = sqlite.prepareStatement("DELETE FROM peers WHERE node_id=?")
    statement.setBytes(1, nodeId.toBin)
    statement.executeUpdate()
  }

  override def listPeers(): Iterator[(PublicKey, InetSocketAddress)] = {
    val rs = sqlite.createStatement.executeQuery("SELECT node_id, data FROM peers")
    new Iterator[(PublicKey, InetSocketAddress)] {
      override def hasNext: Boolean = rs.next()

      override def next() = (PublicKey(rs.getBytes("node_id")), socketaddress.decode(BitVector(rs.getBytes("data"))).require.value)
    }
  }
}
