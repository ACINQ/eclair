package fr.acinq.eclair.db.postgre

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.PeersDb
import scodec.bits.{BitVector, ByteVector}
import fr.acinq.eclair.db.postgre.PostgrePeersDb._
import fr.acinq.eclair.wire.{CommonCodecs, NodeAddress}
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.PostgresProfile
import slick.lifted.Tag

object PostgrePeersDb {
  val model = TableQuery[PostgrePeersDbModel]
  type DbType = (Long, Array[Byte], Array[Byte])
}

class PostgrePeersDbModel(tag: Tag) extends Table[DbType](tag, "peers") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def nodeId: Rep[Array[Byte]] = column[Array[Byte]]("node_id", O.Unique)
  def data: Rep[Array[Byte]] = column[Array[Byte]]("data")
  def * = (id, nodeId, data)
}

class PostgrePeersDb(db: PostgresProfile.backend.Database) extends PeersDb {
  private val insertCompiled = Compiled(for (p <- model) yield (p.nodeId, p.data))

  private val findAllCompiled = Compiled(for (p <- model) yield p)

  private val findByNodeIdCompiled = Compiled((nodeId: Rep[Array[Byte]]) => for (p <- model if p.nodeId === nodeId) yield p.data)

  override def addOrUpdatePeer(nodeId: PublicKey, nodeaddress: NodeAddress): Unit = {
    val peerNodeId = nodeId.value.toArray
    val data = CommonCodecs.nodeaddress.encode(nodeaddress).require.toByteArray
    if (Blocking.txWrite(findByNodeIdCompiled(peerNodeId).update(data), db) < 1) {
      require(Blocking.txWrite(insertCompiled += (peerNodeId, data), db) > 0, "Could not neither update nor insert")
    }
  }

  def removePeer(nodeId: PublicKey): Unit = Blocking.txWrite(findByNodeIdCompiled(nodeId.value.toArray).delete, db)

  def listPeers(): Map[PublicKey, NodeAddress] = {
    val results = for {
      (_, nodeId, data) <- Blocking.txRead(findAllCompiled.result, db)
      remoteNodeId = PublicKey(ByteVector.view(nodeId))
      nodeaddress = CommonCodecs.nodeaddress.decode(BitVector.view(data)).require.value
    } yield (remoteNodeId, nodeaddress)
    results.toMap
  }
}