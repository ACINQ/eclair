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
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases, migrationCheck}
import fr.acinq.eclair.channel.RealScidStatus
import fr.acinq.eclair.db.ChannelsDbSpec.{getPgTimestamp, getTimestamp, testCases}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.jdbc.JdbcUtils.using
import fr.acinq.eclair.db.pg.PgUtils.{getVersion, setVersion}
import fr.acinq.eclair.db.pg.{PgChannelsDb, PgUtils}
import fr.acinq.eclair.db.sqlite.SqliteChannelsDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.ExtendedResultSet._
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.channelDataCodec
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.{CltvExpiry, RealShortChannelId, TestDatabases, randomBytes32}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.ByteVector

import java.sql.{Connection, SQLException}
import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.Random

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
      dbs.pendingCommands // needed by db.removeChannel

      val channel1 = ChannelCodecsSpec.normal
      val channel2a = ChannelCodecsSpec.normal.modify(_.commitments.params.channelId).setTo(randomBytes32())
      val channel2b = channel2a.modify(_.shortIds.real).setTo(RealScidStatus.Final(RealShortChannelId(189371)))

      val commitNumber = 42
      val paymentHash1 = ByteVector32.Zeroes
      val cltvExpiry1 = CltvExpiry(123)
      val paymentHash2 = ByteVector32(ByteVector.fill(32)(1))
      val cltvExpiry2 = CltvExpiry(656)

      intercept[SQLException](db.addHtlcInfo(channel1.channelId, commitNumber, paymentHash1, cltvExpiry1)) // no related channel

      assert(db.listLocalChannels().toSet == Set.empty)
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

      assert(db.listHtlcInfos(channel1.channelId, commitNumber).toList == Nil)
      db.addHtlcInfo(channel1.channelId, commitNumber, paymentHash1, cltvExpiry1)
      db.addHtlcInfo(channel1.channelId, commitNumber, paymentHash2, cltvExpiry2)
      assert(db.listHtlcInfos(channel1.channelId, commitNumber).toList.toSet == Set((paymentHash1, cltvExpiry1), (paymentHash2, cltvExpiry2)))
      assert(db.listHtlcInfos(channel1.channelId, 43).toList == Nil)

      db.removeChannel(channel1.channelId)
      assert(db.getChannel(channel1.channelId).isEmpty)
      assert(db.listLocalChannels() == List(channel2b))
      assert(db.listHtlcInfos(channel1.channelId, commitNumber).toList == Nil)
      db.removeChannel(channel2b.channelId)
      assert(db.getChannel(channel2b.channelId).isEmpty)
      assert(db.listLocalChannels() == Nil)
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
        Future(db.addOrUpdateChannel(channel.modify(_.commitments.params.channelId).setTo(channelId)))
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
      val channel2 = channel1.modify(_.commitments.params.channelId).setTo(randomBytes32())

      // first we add channels
      db.addOrUpdateChannel(channel1)
      db.addOrUpdateChannel(channel2)

      // make sure initially all metadata are empty
      assert(getTimestamp(dbs, channel1.channelId, "created_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel1.channelId, "last_payment_sent_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel1.channelId, "last_payment_received_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel1.channelId, "last_connected_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel1.channelId, "closed_timestamp").isEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.Created)
      assert(getTimestamp(dbs, channel1.channelId, "created_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.PaymentSent)
      assert(getTimestamp(dbs, channel1.channelId, "last_payment_sent_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.PaymentReceived)
      assert(getTimestamp(dbs, channel1.channelId, "last_payment_received_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.Connected)
      assert(getTimestamp(dbs, channel1.channelId, "last_connected_timestamp").nonEmpty)

      db.updateChannelMeta(channel1.channelId, ChannelEvent.EventType.Closed(null))
      assert(getTimestamp(dbs, channel1.channelId, "closed_timestamp").nonEmpty)

      // make sure all metadata are still empty for channel 2
      assert(getTimestamp(dbs, channel2.channelId, "created_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel2.channelId, "last_payment_sent_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel2.channelId, "last_payment_received_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel2.channelId, "last_connected_timestamp").isEmpty)
      assert(getTimestamp(dbs, channel2.channelId, "closed_timestamp").isEmpty)
    }
  }

  test("migrate sqlite channel database v1 -> current") {
    forAllDbs {
      case _: TestPgDatabases => // no migration
      case dbs: TestSqliteDatabases =>
        val sqlite = dbs.connection

        // create a v1 channels database
        using(sqlite.createStatement()) { statement =>
          statement.execute("PRAGMA foreign_keys = ON")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
          statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_infos (channel_id BLOB NOT NULL, commitment_number BLOB NOT NULL, payment_hash BLOB NOT NULL, cltv_expiry INTEGER NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
          statement.executeUpdate("CREATE INDEX IF NOT EXISTS htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
          setVersion(statement, "channels", 1)
        }

        // insert data
        for (testCase <- testCases) {
          using(sqlite.prepareStatement("INSERT INTO local_channels VALUES (?, ?)")) { statement =>
            statement.setBytes(1, testCase.channelId.toArray)
            statement.setBytes(2, testCase.data.toArray)
            statement.executeUpdate()
          }
          for (commitmentNumber <- testCase.commitmentNumbers) {
            using(sqlite.prepareStatement("INSERT INTO htlc_infos (channel_id, commitment_number, payment_hash, cltv_expiry) VALUES (?, ?, ?, ?)")) { statement =>
              statement.setBytes(1, testCase.channelId.toArray)
              statement.setLong(2, commitmentNumber)
              statement.setBytes(3, randomBytes32().toArray)
              statement.setLong(4, 500000 + Random.nextInt(500000))
              statement.executeUpdate()
            }
          }
        }

        // check that db migration works
        val targetVersion = SqliteChannelsDb.CURRENT_VERSION
        val db = new SqliteChannelsDb(sqlite)
        using(sqlite.createStatement()) { statement =>
          assert(getVersion(statement, "channels").contains(targetVersion))
        }
        assert(db.listLocalChannels().size == testCases.size)
        for (testCase <- testCases) {
          db.updateChannelMeta(testCase.channelId, ChannelEvent.EventType.Created) // this call must not fail
          for (commitmentNumber <- testCase.commitmentNumbers) {
            assert(db.listHtlcInfos(testCase.channelId, commitmentNumber).size == testCase.commitmentNumbers.count(_ == commitmentNumber))
          }
        }
    }
  }

  test("migrate channel database v2 -> current") {
    def postCheck(channelsDb: ChannelsDb): Unit = {
      assert(channelsDb.listLocalChannels().size == testCases.filterNot(_.isClosed).size)
      for (testCase <- testCases.filterNot(_.isClosed)) {
        channelsDb.updateChannelMeta(testCase.channelId, ChannelEvent.EventType.Created) // this call must not fail
        for (commitmentNumber <- testCase.commitmentNumbers) {
          assert(channelsDb.listHtlcInfos(testCase.channelId, commitmentNumber).size == testCase.commitmentNumbers.count(_ == commitmentNumber))
        }
      }
    }

    forAllDbs {
      case dbs: TestPgDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            // initialize a v2 database
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT FALSE)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_infos (channel_id TEXT NOT NULL, commitment_number TEXT NOT NULL, payment_hash TEXT NOT NULL, cltv_expiry BIGINT NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
              setVersion(statement, "channels", 2)
            }
            // insert data
            testCases.foreach { testCase =>
              using(connection.prepareStatement("INSERT INTO local_channels (channel_id, data, is_closed) VALUES (?, ?, ?)")) { statement =>
                statement.setString(1, testCase.channelId.toHex)
                statement.setBytes(2, testCase.data.toArray)
                statement.setBoolean(3, testCase.isClosed)
                statement.executeUpdate()
                for (commitmentNumber <- testCase.commitmentNumbers) {
                  using(connection.prepareStatement("INSERT INTO htlc_infos (channel_id, commitment_number, payment_hash, cltv_expiry) VALUES (?, ?, ?, ?)")) { statement =>
                    statement.setString(1, testCase.channelId.toHex)
                    statement.setLong(2, commitmentNumber)
                    statement.setString(3, randomBytes32().toHex)
                    statement.setLong(4, 500000 + Random.nextInt(500000))
                    statement.executeUpdate()
                  }
                }
              }
            }
          },
          dbName = PgChannelsDb.DB_NAME,
          targetVersion = PgChannelsDb.CURRENT_VERSION,
          postCheck = _ => postCheck(dbs.channels)
        )
      case dbs: TestSqliteDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            // create a v2 channels database
            using(connection.createStatement()) { statement =>
              statement.execute("PRAGMA foreign_keys = ON")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT 0)")
              statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_infos (channel_id BLOB NOT NULL, commitment_number BLOB NOT NULL, payment_hash BLOB NOT NULL, cltv_expiry INTEGER NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
              statement.executeUpdate("CREATE INDEX IF NOT EXISTS htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
              setVersion(statement, "channels", 2)
            }
            // insert data
            testCases.foreach { testCase =>
              using(connection.prepareStatement("INSERT INTO local_channels (channel_id, data, is_closed) VALUES (?, ?, ?)")) { statement =>
                statement.setBytes(1, testCase.channelId.toArray)
                statement.setBytes(2, testCase.data.toArray)
                statement.setBoolean(3, testCase.isClosed)
                statement.executeUpdate()
                for (commitmentNumber <- testCase.commitmentNumbers) {
                  using(connection.prepareStatement("INSERT INTO htlc_infos (channel_id, commitment_number, payment_hash, cltv_expiry) VALUES (?, ?, ?, ?)")) { statement =>
                    statement.setBytes(1, testCase.channelId.toArray)
                    statement.setLong(2, commitmentNumber)
                    statement.setBytes(3, randomBytes32().toArray)
                    statement.setLong(4, 500000 + Random.nextInt(500000))
                    statement.executeUpdate()
                  }
                }
              }
            }
          },
          dbName = SqliteChannelsDb.DB_NAME,
          targetVersion = SqliteChannelsDb.CURRENT_VERSION,
          postCheck = _ => postCheck(dbs.channels)
        )
    }
  }

  test("migrate pg channel database v3 -> current") {
    val dbs = TestPgDatabases()

    migrationCheck(
      dbs = dbs,
      initializeTables = connection => {
        using(connection.createStatement()) { statement =>
          // initialize a v3 database
          statement.executeUpdate("CREATE TABLE local_channels (channel_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT FALSE, created_timestamp BIGINT, last_payment_sent_timestamp BIGINT, last_payment_received_timestamp BIGINT, last_connected_timestamp BIGINT, closed_timestamp BIGINT)")
          statement.executeUpdate("CREATE TABLE htlc_infos (channel_id TEXT NOT NULL, commitment_number TEXT NOT NULL, payment_hash TEXT NOT NULL, cltv_expiry BIGINT NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
          statement.executeUpdate("CREATE INDEX htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
          PgUtils.setVersion(statement, "channels", 3)
        }
        // insert data
        testCases.foreach { testCase =>
          using(connection.prepareStatement("INSERT INTO local_channels (channel_id, data, is_closed, created_timestamp, last_payment_sent_timestamp, last_payment_received_timestamp, last_connected_timestamp, closed_timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
            statement.setString(1, testCase.channelId.toHex)
            statement.setBytes(2, testCase.data.toArray)
            statement.setBoolean(3, testCase.isClosed)
            statement.setObject(4, testCase.createdTimestamp.orNull)
            statement.setObject(5, testCase.lastPaymentSentTimestamp.orNull)
            statement.setObject(6, testCase.lastPaymentReceivedTimestamp.orNull)
            statement.setObject(7, testCase.lastConnectedTimestamp.orNull)
            statement.setObject(8, testCase.closedTimestamp.orNull)
            statement.executeUpdate()
          }
        }
      },
      dbName = PgChannelsDb.DB_NAME,
      targetVersion = PgChannelsDb.CURRENT_VERSION,
      postCheck = connection => {
        assert(dbs.channels.listLocalChannels().size == testCases.filterNot(_.isClosed).size)
        testCases.foreach { testCase =>
          assert(getPgTimestamp(connection, testCase.channelId, "created_timestamp") == testCase.createdTimestamp)
          assert(getPgTimestamp(connection, testCase.channelId, "last_payment_sent_timestamp") == testCase.lastPaymentSentTimestamp)
          assert(getPgTimestamp(connection, testCase.channelId, "last_payment_received_timestamp") == testCase.lastPaymentReceivedTimestamp)
          assert(getPgTimestamp(connection, testCase.channelId, "last_connected_timestamp") == testCase.lastConnectedTimestamp)
          assert(getPgTimestamp(connection, testCase.channelId, "closed_timestamp") == testCase.closedTimestamp)
        }
      }
    )
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

  case class TestCase(channelId: ByteVector32,
                      data: ByteVector,
                      isClosed: Boolean,
                      createdTimestamp: Option[Long],
                      lastPaymentSentTimestamp: Option[Long],
                      lastPaymentReceivedTimestamp: Option[Long],
                      lastConnectedTimestamp: Option[Long],
                      closedTimestamp: Option[Long],
                      commitmentNumbers: Seq[Int]
                     )

  val testCases: Seq[TestCase] = for (_ <- 0 until 10) yield {
    val channelId = randomBytes32()
    val data = channelDataCodec.encode(ChannelCodecsSpec.normal.modify(_.commitments.params.channelId).setTo(channelId)).require.bytes
    TestCase(
      channelId = channelId,
      data = data,
      isClosed = Random.nextBoolean(),
      createdTimestamp = if (Random.nextBoolean()) Some(Random.nextInt(Int.MaxValue)) else None,
      lastPaymentSentTimestamp = if (Random.nextBoolean()) Some(Random.nextInt(Int.MaxValue)) else None,
      lastPaymentReceivedTimestamp = if (Random.nextBoolean()) Some(Random.nextInt(Int.MaxValue)) else None,
      lastConnectedTimestamp = if (Random.nextBoolean()) Some(Random.nextInt(Int.MaxValue)) else None,
      closedTimestamp = if (Random.nextBoolean()) Some(Random.nextInt(Int.MaxValue)) else None,
      commitmentNumbers = for (_ <- 0 until Random.nextInt(10)) yield Random.nextInt(5) // there will be repetitions, on purpose
    )
  }

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
