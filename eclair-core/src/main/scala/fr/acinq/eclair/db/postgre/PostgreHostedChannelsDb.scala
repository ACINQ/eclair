package fr.acinq.eclair.db.postgre

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.HOSTED_DATA_COMMITMENTS
import fr.acinq.eclair.db.HostedChannelsDb
import fr.acinq.eclair.wire.HostedChannelCodecs.HOSTED_DATA_COMMITMENTS_Codec
import fr.acinq.eclair.db.postgre.PostgreHostedChannelsDbModel._
import scodec.bits.BitVector
import scala.concurrent.duration._
import slick.jdbc.PostgresProfile.api._
import slick.lifted.{Index, Tag}
import slick.jdbc.PostgresProfile
import scala.concurrent.Await
import scala.compat.Platform

object PostgreHostedChannelsDbModel {
  val model = TableQuery[PostgreHostedChannelsDbModel]
  type DbType = (Long, Array[Byte], Long, Int, Long, Long, Long, Long, Array[Byte])
  val awaitSpan: FiniteDuration = 30.seconds
}

class PostgreHostedChannelsDbModel(tag: Tag) extends Table[DbType](tag, "hostedchannels") {
  def id: Rep[Long] = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def channelId: Rep[Array[Byte]] = column[Array[Byte]]("channel_id")
  def shortChannelId: Rep[Long] = column[Long]("short_channel_id")
  def inFlightHtlcs: Rep[Int] = column[Int]("in_flight_htlcs")
  def inFlightIncomingAmount: Rep[Long] = column[Long]("in_flight_incoming_amount")
  def inFlightOutgoingAmount: Rep[Long] = column[Long]("in_flight_outgoing_amount")
  def capacity: Rep[Long] = column[Long]("capacity")
  def createdAt: Rep[Long] = column[Long]("created_at")
  def data: Rep[Array[Byte]] = column[Array[Byte]]("data")

  def channelIdIdx: Index = index("hostedchannels__channel_id__idx", channelId, unique = true)
  def shortChannelIdIdx: Index = index("hostedchannels__short_channel_id__idx", channelId, unique = true)
  def inFlightHtlcsIdx: Index = index("hostedchannels__in_flight_htlcs__idx", inFlightHtlcs, unique = false)
  def * = (id, channelId, shortChannelId, inFlightHtlcs, inFlightIncomingAmount, inFlightOutgoingAmount, capacity, createdAt, data)
}

class PostgreHostedChannelsDb(db: PostgresProfile.backend.Database) extends HostedChannelsDb {
  private val insertCompiled = Compiled(for (hc <- model) yield (hc.channelId, hc.shortChannelId, hc.inFlightHtlcs, hc.inFlightIncomingAmount, hc.inFlightOutgoingAmount, hc.capacity, hc.createdAt, hc.data))

  private val listHotChannelsCompiled = Compiled(for (hc <- model if hc.inFlightHtlcs > 0) yield hc.data)

  private val findUpdatableByIdCompiled = Compiled(findUpdatableById _)

  private val findChannelByIdCompiled = Compiled(findChannelById _)

  private def findUpdatableById(channelId: Rep[Array[Byte]]) = for (hc <- model if hc.channelId === channelId) yield (hc.shortChannelId, hc.inFlightHtlcs, hc.inFlightIncomingAmount, hc.inFlightOutgoingAmount, hc.capacity, hc.data)

  private def findChannelById(channelId: Rep[Array[Byte]]) = for (hc <- model if hc.channelId === channelId) yield hc.data

  def addOrUpdateChannel(state: HOSTED_DATA_COMMITMENTS): Unit = {
    val channelId = state.channelId.toArray
    val data = HOSTED_DATA_COMMITMENTS_Codec.encode(state).require.toByteArray
    val shortChannelId = state.channelUpdate.shortChannelId.toLong
    val capacity = state.lastCrossSignedState.initHostedChannel.channelCapacityMsat.toLong
    val insert = insertCompiled += (channelId, shortChannelId, state.currentAndNextInFlightHtlcs.size, state.localSpec.inFlightIncoming.toLong, state.localSpec.inFlightOutgoing.toLong, capacity, Platform.currentTime / 1000L, data)
    val update = findUpdatableByIdCompiled(channelId).update((shortChannelId, state.currentAndNextInFlightHtlcs.size, state.localSpec.inFlightIncoming.toLong, state.localSpec.inFlightOutgoing.toLong, capacity, data))
    val affectedUpdateRows = Await.result(db.run(update), awaitSpan)
    val affectedInsertRows = if (affectedUpdateRows < 1) Await.result(db.run(insert), awaitSpan) else 0
    require(affectedUpdateRows > 0 || affectedInsertRows > 0)
  }

  def getChannel(channelId: ByteVector32): Option[HOSTED_DATA_COMMITMENTS] = {
    val rawOpt = Await.result(db.run(findChannelByIdCompiled(channelId.toArray).result.headOption), awaitSpan)
    for (data <- rawOpt) yield HOSTED_DATA_COMMITMENTS_Codec.decode(BitVector(data)).require.value
  }

  def listHotChannels(): Seq[HOSTED_DATA_COMMITMENTS] = {
    val rawSeq = Await.result(db.run(listHotChannelsCompiled.result), awaitSpan)
    for (data <- rawSeq) yield HOSTED_DATA_COMMITMENTS_Codec.decode(BitVector(data)).require.value
  }
}