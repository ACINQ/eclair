package fr.acinq.eclair.db.postgre

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.wire.ChannelCodecs.stateDataCodec
import scodec.bits.{BitVector, ByteVector}
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.PostgresProfile
import slick.lifted.{Index, Tag}
import slick.model.ForeignKeyAction.Cascade

object PostgreLocalChannelsDb {
  val model = TableQuery[PostgreLocalChannelsDbModel]
  type DbType = (Long, Array[Byte], Array[Byte], Boolean)
}

class PostgreLocalChannelsDbModel(tag: Tag) extends Table[PostgreLocalChannelsDb.DbType](tag, "localchannels") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def channelId: Rep[Array[Byte]] = column[Array[Byte]]("channel_id", O.Unique)
  def data: Rep[Array[Byte]] = column[Array[Byte]]("data")
  def isClosed: Rep[Boolean] = column[Boolean]("is_closed")
  def * = (id, channelId, data, isClosed)
}

object PostgreHtlcInfosDb {
  val model = TableQuery[PostgreHtlcInfosModel]
  type DbType = (Long, Array[Byte], Long, Array[Byte], Long)
}

class PostgreHtlcInfosModel(tag: Tag) extends Table[PostgreHtlcInfosDb.DbType](tag, "htlcinfos") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def channelId: Rep[Array[Byte]] = column[Array[Byte]]("channel_id")
  def commitmentNumber: Rep[Long] = column[Long]("commitment_number")
  def paymentHash: Rep[Array[Byte]] = column[Array[Byte]]("payment_hash")
  def cltvExpiry: Rep[Long] = column[Long]("cltv_expiry")
  def idx: Index = index("htlcinfos__channel_id__commitment_number__idx", (channelId, commitmentNumber), unique = false)
  def userFk = foreignKey("htlcinfos_localchannels_fk", channelId, PostgreLocalChannelsDb.model)(_.channelId, Cascade, Cascade)
  def * = (id, channelId, commitmentNumber, paymentHash, cltvExpiry)
}

class PostgreChannelsDb(db: PostgresProfile.backend.Database) extends ChannelsDb {
  private val insertHtlcInfoCompiled = Compiled(for (hi <- PostgreHtlcInfosDb.model) yield (hi.channelId, hi.commitmentNumber, hi.paymentHash, hi.cltvExpiry))

  private val findHtlcInfosByChannelIdCompiled = Compiled((channelId: Rep[Array[Byte]]) => for (c <- PostgreHtlcInfosDb.model if c.channelId === channelId) yield c)

  private val findHtlcInfosByChannelIdNumberCompiled = Compiled((channelId: Rep[Array[Byte]], commitmentNumber: Rep[Long]) => for (c <- PostgreHtlcInfosDb.model if c.channelId === channelId && c.commitmentNumber === commitmentNumber) yield c)

  private val insertChannelCompiled = Compiled(for (c <- PostgreLocalChannelsDb.model) yield (c.channelId, c.data, c.isClosed))

  private val findAllNonClosedChannelDataCompiled = Compiled(for (c <- PostgreLocalChannelsDb.model if !c.isClosed) yield c)

  private val findNonClosedChannelDataByIdCompiled = Compiled((channelId: Rep[Array[Byte]]) => for (c <- PostgreLocalChannelsDb.model if c.channelId === channelId && !c.isClosed) yield c.data)

  private val findChannelIsClosedByIdCompiled = Compiled((channelId: Rep[Array[Byte]]) => for (c <- PostgreLocalChannelsDb.model if c.channelId === channelId) yield c.isClosed)

  override def addOrUpdateChannel(state: HasCommitments): Unit = {
    val channelId = state.channelId.toArray
    val data = stateDataCodec.encode(state).require.toByteArray
    if (Blocking.txWrite(findNonClosedChannelDataByIdCompiled(channelId).update(data), db) < 1) {
      require(Blocking.txWrite(insertChannelCompiled += (channelId, data, false), db) > 0, "Could not neither update nor insert")
    }
  }

  // SQLite version also removes related pending_relay but here we don't do that because pending_relay is harmless and will be later removed in Switchboard
  override def removeChannel(channelId: ByteVector32): Unit = {
    val setChannelRemoved = findChannelIsClosedByIdCompiled(channelId.toArray).update(true)
    val removeHtlcInfos = findHtlcInfosByChannelIdCompiled(channelId.toArray).delete
    Blocking.txWrite(DBIO.seq(setChannelRemoved, removeHtlcInfos), db)
  }

  override def listLocalChannels(): Seq[HasCommitments] = for {
    (_, _, data, _) <- Blocking.txRead(findAllNonClosedChannelDataCompiled.result, db)
  } yield stateDataCodec.decode(BitVector(data)).require.value

  override def addOrUpdateHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit =
    Blocking.txWrite(insertHtlcInfoCompiled += (channelId.toArray, commitmentNumber, paymentHash.toArray, cltvExpiry.toLong), db)

  override def listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): Seq[(ByteVector32, CltvExpiry)] = for {
    (_, _, _, paymentHash, cltvExpiry) <- Blocking.txRead(findHtlcInfosByChannelIdNumberCompiled(channelId.toArray, commitmentNumber).result, db)
  } yield (ByteVector32(ByteVector.view(paymentHash)), CltvExpiry(cltvExpiry))
}