package fr.acinq.eclair.db.sqlite

import java.sql.Connection
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.Timer
import fr.acinq.bitcoin.{BinaryData, Satoshi}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.NetworkDb
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.ShortChannelId
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}

class TimedSqliteNetworkDb(sqlite: Connection,metrics: MetricRegistry) extends NetworkDb with TimeUnit {
  val db = new SqliteNetworkDb(sqlite)
  
  val t1=metrics.timer("db.networkdb.addNode")
  val t2=metrics.timer("db.networkdb.updateNode")
  val t3=metrics.timer("db.networkdb.removeNode")
  val t4=metrics.timer("db.networkdb.listNodes")
  val t5=metrics.timer("db.networkdb.addChannel")
  val t6=metrics.timer("db.networkdb.removeChannel")
  val t7=metrics.timer("db.networkdb.listChannels")
  val t8=metrics.timer("db.networkdb.addChannelUpdate")
  val t9=metrics.timer("db.networkdb.updateChannelUpdate")
  val t10=metrics.timer("db.networkdb.listChannelUpdates")
  val t11=metrics.timer("db.networkdb.addToPruned")
  val t12=metrics.timer("db.networkdb.removeFromPruned")
  val t13=metrics.timer("db.networkdb.isPruned")
  val t14=metrics.timer("db.networkdb.close")

  
  def addNode(n: NodeAnnouncement) = timeUnit(t1,db.addNode(n)) 
 
  def updateNode(n: NodeAnnouncement) = timeUnit(t2,db.updateNode(n)) 

  def removeNode(nodeId: PublicKey)= timeUnit(t3,db.removeNode(nodeId))

  def listNodes(): Seq[NodeAnnouncement] = {
    val context = t4.time()
    try {
        db.listNodes()
    } finally {
        context.stop();
    }
  }

  def addChannel(c: ChannelAnnouncement, txid: BinaryData, capacity: Satoshi)= timeUnit(t5,db.addChannel(c,txid,capacity))


  def removeChannel(shortChannelId: ShortChannelId)= timeUnit(t6,db.removeChannel(shortChannelId))

  def listChannels(): Map[ChannelAnnouncement, (BinaryData, Satoshi)] = {
    val context = t7.time()
    try {
        db.listChannels()
    } finally {
        context.stop();
    }
  }

  def addChannelUpdate(u: ChannelUpdate)= timeUnit(t8,db.addChannelUpdate(u))

  def updateChannelUpdate(u: ChannelUpdate)= timeUnit(t9,db.updateChannelUpdate(u))

  def listChannelUpdates(): Seq[ChannelUpdate] = {
    val context = t10.time()
    try {
        db.listChannelUpdates()
    } finally {
        context.stop();
    }
  }

  def addToPruned(shortChannelId: ShortChannelId)= timeUnit(t11,db.addToPruned(shortChannelId))

  def removeFromPruned(shortChannelId: ShortChannelId)= timeUnit(t12,db.removeFromPruned(shortChannelId))

  def isPruned(shortChannelId: ShortChannelId): Boolean = {
    val context = t13.time()
    try {
        db.isPruned(shortChannelId)
    } finally {
        context.stop();
    }
  }
  
  def close(): Unit = timeUnit(t14,db.close())
 
}