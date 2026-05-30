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

package fr.acinq.eclair.db

import com.softwaremill.quicklens._
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong}
import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.ChannelsDbSpec.getTimestamp
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.jdbc.JdbcUtils.using
import fr.acinq.eclair.db.pg.PgChannelsDb
import fr.acinq.eclair.db.sqlite.SqliteChannelsDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.ExtendedResultSet._
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.{Alias, CltvExpiry, MilliSatoshiLong, TestDatabases, randomBytes32, randomLong}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

import java.sql.Connection
import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

class ChannelsDbSpec extends AnyFunSuite {

  import fr.acinq.eclair.TestDatabases.forAllDbs

  test("init database two times in a row") {
    forAllDbs {
      case sqlite: TestSqliteDatabases =>
        new SqliteChannelsDb(sqlite.connection)
        new SqliteChannelsDb(sqlite.connection)
      case pg: TestPgDatabases =>
        new PgChannelsDb()(pg.datasource, pg.lock)
        new PgChannelsDb()(pg.datasource, pg.lock)
    }
  }

  test("add/remove/list channels") {
    forAllDbs { dbs =>
      val db = dbs.channels

      val channel1 = ChannelCodecsSpec.normal
      val channel2a = ChannelCodecsSpec.normal.modify(_.commitments.channelParams.channelId).setTo(randomBytes32())
      val channel2b = channel2a.modify(_.aliases.remoteAlias_opt).setTo(Some(Alias(randomLong())))

      val commitNumber = 42
      val paymentHash1 = ByteVector32.Zeroes
      val cltvExpiry1 = CltvExpiry(123)
      val paymentHash2 = ByteVector32(ByteVector.fill(32)(1))
      val cltvExpiry2 = CltvExpiry(656)

      assert(db.listLocalChannels().isEmpty)
      db.addOrUpdateChannel(channel1)
      db.addOrUpdateChannel(channel1)
      assert(db.listLocalChannels() == List(channel1))
      db.addOrUpdateChannel(channel2a)
      assert(db.listLocalChannels() == List(channel1, channel2a))
      assert(db.getChannel(channel1.channelId).contains(channel1))
      assert(db.getChannel(channel2a.channelId).contains(channel2a))
      db.addOrUpdateChannel(channel2b)
      assert(db.listLocalChannels() == List(channel1, channel2b))
      assert(db.getChannel(channel2b.channelId).contains(channel2b))

      assert(db.listHtlcInfos(channel1.channelId, commitNumber).isEmpty)
      db.addHtlcInfo(channel1.channelId, commitNumber, paymentHash1, cltvExpiry1)
      db.addHtlcInfo(channel1.channelId, commitNumber, paymentHash2, cltvExpiry2)
      assert(db.listHtlcInfos(channel1.channelId, commitNumber).toSet == Set((paymentHash1, cltvExpiry1), (paymentHash2, cltvExpiry2)))
      assert(db.listHtlcInfos(channel1.channelId, commitNumber + 1).isEmpty)

      assert(db.listClosedChannels(None, None).isEmpty)
      val closed1 = DATA_CLOSED(channel1.channelId, channel1.remoteNodeId, randomTxId(), 3, 2, channel1.channelParams.localParams.fundingKeyPath.toString(), channel1.channelParams.channelFeatures.toString, isChannelOpener = true, "anchor_outputs", announced = true, 100_000 sat, randomTxId(), "local-close", hex"deadbeef", 61_000_500 msat, 40_000_000 msat, 60_000 sat)
      db.removeChannel(channel1.channelId, Some(closed1))
      assert(db.getChannel(channel1.channelId).isEmpty)
      assert(db.listLocalChannels() == List(channel2b))
      assert(db.listClosedChannels(None, None) == List(closed1))
      assert(db.listClosedChannels(Some(channel1.remoteNodeId), None) == List(closed1))
      assert(db.listClosedChannels(Some(PrivateKey(randomBytes32()).publicKey), None).isEmpty)

      // If no closing data is provided, the channel won't be backed-up in the closed_channels table.
      db.removeChannel(channel2b.channelId, None)
      assert(db.getChannel(channel2b.channelId).isEmpty)
      assert(db.listLocalChannels().isEmpty)
      assert(db.listClosedChannels(None, None) == Seq(closed1))
    }
  }

  test("remove htlc infos") {
    forAllDbs { dbs =>
      val db = dbs.channels

      val channel1 = ChannelCodecsSpec.normal
      val channel2 = ChannelCodecsSpec.normal.modify(_.commitments.channelParams.channelId).setTo(randomBytes32())
      db.addOrUpdateChannel(channel1)
      db.addOrUpdateChannel(channel2)

      val commitNumberSplice1 = 50
      val commitNumberSplice2 = 75

      // The first channel has one splice transaction and is then closed.
      db.addHtlcInfo(channel1.channelId, 49, randomBytes32(), CltvExpiry(561))
      db.addHtlcInfo(channel1.channelId, 50, randomBytes32(), CltvExpiry(561))
      db.addHtlcInfo(channel1.channelId, 50, randomBytes32(), CltvExpiry(561))
      db.markHtlcInfosForRemoval(channel1.channelId, commitNumberSplice1)
      db.addHtlcInfo(channel1.channelId, 51, randomBytes32(), CltvExpiry(561))
      db.addHtlcInfo(channel1.channelId, 52, randomBytes32(), CltvExpiry(561))
      db.removeChannel(channel1.channelId, None)

      // The second channel has two splice transactions.
      db.addHtlcInfo(channel2.channelId, 48, randomBytes32(), CltvExpiry(561))
      db.addHtlcInfo(channel2.channelId, 48, randomBytes32(), CltvExpiry(561))
      db.addHtlcInfo(channel2.channelId, 49, randomBytes32(), CltvExpiry(561))
      db.addHtlcInfo(channel2.channelId, 50, randomBytes32(), CltvExpiry(561))
      db.markHtlcInfosForRemoval(channel2.channelId, commitNumberSplice1)
      db.addHtlcInfo(channel2.channelId, 74, randomBytes32(), CltvExpiry(561))
      db.addHtlcInfo(channel2.channelId, 75, randomBytes32(), CltvExpiry(561))
      db.addHtlcInfo(channel2.channelId, 76, randomBytes32(), CltvExpiry(561))
      db.markHtlcInfosForRemoval(channel2.channelId, commitNumberSplice2)

      // We asynchronously clean-up the HTLC data from the DB in small batches.
      val obsoleteHtlcInfo = Seq(
        (channel1.channelId, 49),
        (channel1.channelId, 50),
        (channel1.channelId, 51),
        (channel1.channelId, 52),
        (channel2.channelId, 48),
        (channel2.channelId, 49),
        (channel2.channelId, 50),
        (channel2.channelId, 74),
      )
      db.removeHtlcInfos(10) // This should remove all the data for one of the two channels in one batch
      assert(obsoleteHtlcInfo.flatMap { case (channelId, commitNumber) => db.listHtlcInfos(channelId, commitNumber) }.size == 5)
      db.removeHtlcInfos(3) // This should remove only part of the data for the remaining channel
      assert(obsoleteHtlcInfo.flatMap { case (channelId, commitNumber) => db.listHtlcInfos(channelId, commitNumber) }.size == 2)
      db.removeHtlcInfos(3) // This should remove the rest of the data for the remaining channel
      obsoleteHtlcInfo.foreach { case (channelId, commitNumber) => db.listHtlcInfos(channelId, commitNumber).isEmpty }

      // The remaining HTLC data shouldn't be removed.
      assert(db.listHtlcInfos(channel2.channelId, 75).nonEmpty)
      assert(db.listHtlcInfos(channel2.channelId, 76).nonEmpty)
      db.removeHtlcInfos(10) // no-op
      assert(db.listHtlcInfos(channel2.channelId, 75).nonEmpty)
      assert(db.listHtlcInfos(channel2.channelId, 76).nonEmpty)
    }
  }

  test("concurrent channel updates") {
    forAllDbs { dbs =>
      val db = dbs.channels
      implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8))
      val channel = ChannelCodecsSpec.normal
      val channelIds = (0 until 10).map(_ => randomBytes32()).toList
      val futures = for (i <- 0 until 10000) yield {
        val channelId = channelIds(i % channelIds.size)
        Future(db.addOrUpdateChannel(channel.modify(_.commitments.channelParams.channelId).setTo(channelId)))
        Future(db.updateChannelMeta(channelId, ChannelEvent.EventType.PaymentSent))
      }
      val res = Future.sequence(futures)
      Await.result(res, 5 minutes)
    }
  }

  test("channel metadata") {
    forAllDbs { dbs =>
      val db = dbs.channels

      val channel1 = ChannelCodecsSpec.normal
      val channel2 = channel1.modify(_.commitments.channelParams.channelId).setTo(randomBytes32())

      // first we add channels
      db.addOrUpdateChannel(channel1)
      db.addOrUpdateChannel(channel2)

      assert(getTimestamp(dbs, channel1.channelId, "created_timestamp").nonEmpty)
      assert(getTimestamp(dbs, channel1.channelId, "last_payment_sent_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel1.channelId, "last_payment_received_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel1.channelId, "last_connected_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.Created)
      assert(getTimestamp(dbs, channel1.channelId, "created_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.PaymentSent)
      assert(getTimestamp(dbs, channel1.channelId, "last_payment_sent_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.PaymentReceived)
      assert(getTimestamp(dbs, channel1.channelId, "last_payment_received_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.Connected)
      assert(getTimestamp(dbs, channel1.channelId, "last_connected_timestamp").nonEmpty)

      db.removeChannel(channel1.channelId, None)

      assert(getTimestamp(dbs, channel2.channelId, "created_timestamp").nonEmpty)
      assert(getTimestamp(dbs, channel2.channelId, "last_payment_sent_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel2.channelId, "last_payment_received_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel2.channelId, "last_connected_timestamp").nonEmpty)
    }
  }

  test("json column reset (postgres)") {
    val dbs = TestPgDatabases()
    val db = dbs.channels
    val channel = ChannelCodecsSpec.normal
    db.addOrUpdateChannel(channel)
    dbs.connection.execSQLUpdate("UPDATE local.channels SET json='{}'")
    db.asInstanceOf[PgChannelsDb].resetJsonColumns(dbs.connection)
    assert({
      val res = dbs.connection.execSQLQuery("SELECT * FROM local.channels")
      res.next()
      res.getString("json").length > 100
    })
  }
}

object ChannelsDbSpec {

  def getTimestamp(dbs: TestDatabases, channelId: ByteVector32, columnName: String): Option[Long] = {
    dbs match {
      case _: TestPgDatabases => getPgTimestamp(dbs.connection, channelId, columnName)
      case _: TestSqliteDatabases => getSqliteTimestamp(dbs.connection, channelId, columnName)
    }
  }

  def getSqliteTimestamp(connection: Connection, channelId: ByteVector32, columnName: String): Option[Long] = {
    using(connection.prepareStatement(s"SELECT $columnName FROM local_channels WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      val rs = statement.executeQuery()
      rs.next()
      rs.getLongNullable(columnName)
    }
  }

  def getPgTimestamp(connection: Connection, channelId: ByteVector32, columnName: String): Option[Long] = {
    using(connection.prepareStatement(s"SELECT $columnName FROM local.channels WHERE channel_id=?")) { statement =>
      statement.setString(1, channelId.toHex)
      val rs = statement.executeQuery()
      rs.next()
      rs.getTimestampNullable(columnName).map(_.getTime)
    }
  }
}
