package fr.acinq.eclair.db.sqlite

import java.sql.Connection
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.Timer
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.HasCommitments

class TimedSqliteChannelsDb(sqlite: Connection,metrics: MetricRegistry) extends ChannelsDb with TimeUnit {
  val db = new SqliteChannelsDb(sqlite)
  
  val t1=metrics.timer("db.channeldb.addOrUpdateChannel")
  val t2=metrics.timer("db.channeldb.removeChannel")
  val t3=metrics.timer("db.channeldb.listChannels")
  val t4=metrics.timer("db.channeldb.addOrUpdateHtlcInfo")
  val t5=metrics.timer("db.channeldb.listHtlcInfos")
  val t6=metrics.timer("db.channeldb.close")
  
  def addOrUpdateChannel(state: HasCommitments) = timeUnit(t1,db.addOrUpdateChannel(state: HasCommitments)) 
 
  def removeChannel(channelId: BinaryData)=timeUnit(t2,db.removeChannel(channelId))

  def listChannels(): Seq[HasCommitments] = {
    val context = t2.time()
    try {
        db.listChannels()
    } finally {
        context.stop();
    }
  }

  def addOrUpdateHtlcInfo(channelId: BinaryData, commitmentNumber: Long, paymentHash: BinaryData, cltvExpiry: Long)
    = timeUnit(t4,db.addOrUpdateHtlcInfo(channelId, commitmentNumber, paymentHash, cltvExpiry))

  def listHtlcInfos(channelId: BinaryData, commitmentNumber: Long): Seq[(BinaryData, Long)] = {
    val context = t2.time()
    try {
        db.listHtlcInfos(channelId, commitmentNumber)
    } finally {
        context.stop();
    }
  }

  def close(): Unit = timeUnit(t6,db.close())
 
}