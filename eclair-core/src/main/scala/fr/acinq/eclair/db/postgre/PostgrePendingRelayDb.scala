package fr.acinq.eclair.db.postgre

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.HasHtlcIdCommand
import fr.acinq.eclair.db.PendingRelayDb
import fr.acinq.eclair.wire.CommandCodecs.cmdCodec
import fr.acinq.eclair.db.postgre.PostgrePendingRelayDb._
import org.postgresql.util.PSQLException
import scodec.bits.{BitVector, ByteVector}
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.PostgresProfile
import slick.lifted.{Index, Tag}

object PostgrePendingRelayDb {
  val model = TableQuery[PostgrePendingRelayDbModel]
  type DbType = (Long, Array[Byte], Long, Array[Byte])
}

class PostgrePendingRelayDbModel(tag: Tag) extends Table[DbType](tag, "pendingrelay") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def channelId: Rep[Array[Byte]] = column[Array[Byte]]("channel_id")
  def htlcId: Rep[Long] = column[Long]("htlc_id")
  def data: Rep[Array[Byte]] = column[Array[Byte]]("data")
  def idx: Index = index("pendingrelay__channel_id__htlc_id__idx", (channelId, htlcId), unique = true)
  def * = (id, channelId, htlcId, data)
}

class PostgrePendingRelayDb(db: PostgresProfile.backend.Database) extends PendingRelayDb {
  private val insertCompiled = Compiled(for (pr <- model) yield (pr.channelId, pr.htlcId, pr.data))

  private val findAllCompiled = Compiled(for (pr <- model) yield (pr.channelId, pr.data))

  private val findByChannelIdHtlcIdCompiled = Compiled(findByChannelIdHtlcId _)

  private val findByChannelIdCompiled = Compiled(findByChannelId _)

  private def findByChannelIdHtlcId(channelId: Rep[Array[Byte]], htlcId: Rep[Long]) = for (pr <- model if pr.channelId === channelId && pr.htlcId === htlcId) yield pr

  private def findByChannelId(channelId: Rep[Array[Byte]]) = for (pr <- model if pr.channelId === channelId) yield pr.data

  override def addPendingRelay(channelId: ByteVector32, cmd: HasHtlcIdCommand): Unit = try {
    val insert = insertCompiled += (channelId.toArray, cmd.id, cmdCodec.encode(cmd).require.toByteArray)
    Blocking.txWrite(insert, db)
  } catch {
    case e: PSQLException if "23505" == e.getSQLState => // do nothing on duplicate key
    case e: Throwable => throw e // propagate further
  }

  override def removePendingRelay(channelId: ByteVector32, htlcId: Long): Unit =
    Blocking.txWrite(findByChannelIdHtlcIdCompiled(channelId.toArray, htlcId).delete, db)

  override def listPendingRelay(channelId: ByteVector32): Seq[HasHtlcIdCommand] = for {
    data <- Blocking.txRead(findByChannelIdCompiled(channelId.toArray).result, db)
  } yield cmdCodec.decode(BitVector(data)).require.value

  override def listPendingRelay(): Set[(ByteVector32, HasHtlcIdCommand)] = for {
    (channelId, data) <- Blocking.txRead(findAllCompiled.result, db).toSet
  } yield (ByteVector32(ByteVector(channelId)), cmdCodec.decode(BitVector(data)).require.value)
}