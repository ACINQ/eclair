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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases, migrationCheck}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.ChannelsDbSpec.getTimestamp
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.jdbc.JdbcUtils.using
import fr.acinq.eclair.db.pg.PgChannelsDb
import fr.acinq.eclair.db.pg.PgUtils.setVersion
import fr.acinq.eclair.db.sqlite.SqliteChannelsDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.ExtendedResultSet._
import fr.acinq.eclair.json.JsonSerializers
import fr.acinq.eclair.transactions.Transactions.{ClosingTx, InputInfo}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.channelDataCodec
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol.Shutdown
import fr.acinq.eclair.{Alias, BlockHeight, CltvExpiry, MilliSatoshiLong, TestDatabases, randomBytes32, randomKey, randomLong}
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

  test("migrate closed channels to dedicated table") {
    def createCommitments(): Commitments = {
      ChannelCodecsSpec.normal.commitments
        .modify(_.channelParams.channelId).setTo(randomBytes32())
        .modify(_.channelParams.remoteParams.nodeId).setTo(randomKey().publicKey)
    }

    def closingTx(): ClosingTx = {
      val input = InputInfo(OutPoint(randomTxId(), 3), TxOut(300_000 sat, Script.pay2wpkh(randomKey().publicKey)))
      val tx = Transaction(2, Seq(TxIn(input.outPoint, Nil, 0)), Seq(TxOut(120_000 sat, Script.pay2wpkh(randomKey().publicKey)), TxOut(175_000 sat, Script.pay2tr(randomKey().xOnlyPublicKey()))), 0)
      ClosingTx(input, tx, Some(1))
    }

    val paymentHash1 = randomBytes32()
    val paymentHash2 = randomBytes32()
    // The next two channels are closed and should be migrated to the closed_channels table.
    // We haven't yet removed their corresponding htlc_infos, because it is done asynchronously for performance reasons.
    val closed1 = DATA_CLOSING(createCommitments(), BlockHeight(750_000), hex"deadbeef", closingTx() :: Nil, closingTx() :: Nil)
    val closed2 = DATA_NEGOTIATING_SIMPLE(createCommitments(), FeeratePerKw(2500 sat), hex"deadbeef", hex"beefdead", Nil, closingTx() :: Nil)
    val htlcInfos = Map(
      closed1.channelId -> Seq(
        (7, paymentHash1, CltvExpiry(800_000)),
        (7, paymentHash2, CltvExpiry(795_000)),
        (8, paymentHash1, CltvExpiry(800_000)),
      ),
      closed2.channelId -> Seq(
        (13, paymentHash1, CltvExpiry(801_000)),
        (14, paymentHash2, CltvExpiry(801_000)),
      )
    )
    // The following channel is closed, but was never confirmed and thus doesn't need to be migrated to the closed_channels table.
    val closed3 = DATA_WAIT_FOR_DUAL_FUNDING_CONFIRMED(createCommitments(), 0 msat, 0 msat, BlockHeight(775_000), BlockHeight(780_000), DualFundingStatus.WaitingForConfirmations, None)
    // The following channels aren't closed, and must stay in the channels table after the migration.
    val notClosed1 = DATA_CLOSING(createCommitments(), BlockHeight(800_000), hex"deadbeef", closingTx() :: Nil, closingTx() :: Nil)
    val notClosed2 = DATA_SHUTDOWN(createCommitments(), Shutdown(randomBytes32(), hex"deadbeef"), Shutdown(randomBytes32(), hex"beefdead"), CloseStatus.Initiator(Some(ClosingFeerates(FeeratePerKw(1500 sat), FeeratePerKw(1000 sat), FeeratePerKw(2500 sat)))))

    def postCheck(db: ChannelsDb): Unit = {
      // The closed channels have been migrated to a dedicated DB.
      assert(db.listClosedChannels(None, None).map(_.channelId).toSet == Set(closed1.channelId, closed2.channelId))
      // The remaining channels are still active.
      assert(db.listLocalChannels().toSet == Set(notClosed1, notClosed2))
      // The corresponding htlc_infos hasn't been removed.
      assert(db.listHtlcInfos(closed1.channelId, 7).toSet == Set((paymentHash1, CltvExpiry(800_000)), (paymentHash2, CltvExpiry(795_000))))
      assert(db.listHtlcInfos(closed1.channelId, 8).toSet == Set((paymentHash1, CltvExpiry(800_000))))
      assert(db.listHtlcInfos(closed2.channelId, 13).toSet == Set((paymentHash1, CltvExpiry(801_000))))
      assert(db.listHtlcInfos(closed2.channelId, 14).toSet == Set((paymentHash2, CltvExpiry(801_000))))
    }

    forAllDbs {
      case dbs: TestPgDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            // We initialize a v11 database, where closed channels were kept inside the channels table with an is_closed flag.
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS local")
              statement.executeUpdate("CREATE TABLE local.channels (channel_id TEXT NOT NULL PRIMARY KEY, remote_node_id TEXT NOT NULL, data BYTEA NOT NULL, json JSONB NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT FALSE, created_timestamp TIMESTAMP WITH TIME ZONE, last_payment_sent_timestamp TIMESTAMP WITH TIME ZONE, last_payment_received_timestamp TIMESTAMP WITH TIME ZONE, last_connected_timestamp TIMESTAMP WITH TIME ZONE, closed_timestamp TIMESTAMP WITH TIME ZONE)")
              statement.executeUpdate("CREATE TABLE local.htlc_infos (channel_id TEXT NOT NULL, commitment_number BIGINT NOT NULL, payment_hash TEXT NOT NULL, cltv_expiry BIGINT NOT NULL, FOREIGN KEY(channel_id) REFERENCES local.channels(channel_id))")
              statement.executeUpdate("CREATE INDEX htlc_infos_channel_id_idx ON local.htlc_infos(channel_id)")
              statement.executeUpdate("CREATE INDEX htlc_infos_commitment_number_idx ON local.htlc_infos(commitment_number)")
              setVersion(statement, PgChannelsDb.DB_NAME, 11)
            }
            // We insert some channels in our DB and htc info related to those channels.
            Seq(closed1, closed2, closed3).foreach { c =>
              using(connection.prepareStatement("INSERT INTO local.channels (channel_id, remote_node_id, data, json, is_closed) VALUES (?, ?, ?, ?::JSONB, TRUE)")) { statement =>
                statement.setString(1, c.channelId.toHex)
                statement.setString(2, c.remoteNodeId.toHex)
                statement.setBytes(3, channelDataCodec.encode(c).require.toByteArray)
                statement.setString(4, JsonSerializers.serialization.write(c)(JsonSerializers.formats))
                statement.executeUpdate()
              }
            }
            Seq(notClosed1, notClosed2).foreach { c =>
              using(connection.prepareStatement("INSERT INTO local.channels (channel_id, remote_node_id, data, json, is_closed) VALUES (?, ?, ?, ?::JSONB, FALSE)")) { statement =>
                statement.setString(1, c.channelId.toHex)
                statement.setString(2, c.remoteNodeId.toHex)
                statement.setBytes(3, channelDataCodec.encode(c).require.toByteArray)
                statement.setString(4, JsonSerializers.serialization.write(c)(JsonSerializers.formats))
                statement.executeUpdate()
              }
            }
            htlcInfos.foreach { case (channelId, infos) =>
              infos.foreach { case (commitmentNumber, paymentHash, expiry) =>
                using(connection.prepareStatement("INSERT INTO local.htlc_infos VALUES (?, ?, ?, ?)")) { statement =>
                  statement.setString(1, channelId.toHex)
                  statement.setLong(2, commitmentNumber)
                  statement.setString(3, paymentHash.toHex)
                  statement.setLong(4, expiry.toLong)
                  statement.executeUpdate()
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
            // We initialize a v7 database, where closed channels were kept inside the channels table with an is_closed flag.
            using(connection.createStatement()) { statement =>
              statement.execute("PRAGMA foreign_keys = ON")
              statement.executeUpdate("CREATE TABLE local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT 0, created_timestamp INTEGER, last_payment_sent_timestamp INTEGER, last_payment_received_timestamp INTEGER, last_connected_timestamp INTEGER, closed_timestamp INTEGER)")
              statement.executeUpdate("CREATE TABLE htlc_infos (channel_id BLOB NOT NULL, commitment_number INTEGER NOT NULL, payment_hash BLOB NOT NULL, cltv_expiry INTEGER NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
              statement.executeUpdate("CREATE INDEX htlc_infos_channel_id_idx ON htlc_infos(channel_id)")
              statement.executeUpdate("CREATE INDEX htlc_infos_commitment_number_idx ON htlc_infos(commitment_number)")
              setVersion(statement, SqliteChannelsDb.DB_NAME, 7)
            }
            // We insert some channels in our DB and htc info related to those channels.
            Seq(closed1, closed2, closed3).foreach { c =>
              using(connection.prepareStatement("INSERT INTO local_channels (channel_id, data, is_closed) VALUES (?, ?, 1)")) { statement =>
                statement.setBytes(1, c.channelId.toArray)
                statement.setBytes(2, channelDataCodec.encode(c).require.toByteArray)
                statement.executeUpdate()
              }
            }
            Seq(notClosed1, notClosed2).foreach { c =>
              using(connection.prepareStatement("INSERT INTO local_channels (channel_id, data, is_closed) VALUES (?, ?, 0)")) { statement =>
                statement.setBytes(1, c.channelId.toArray)
                statement.setBytes(2, channelDataCodec.encode(c).require.toByteArray)
                statement.executeUpdate()
              }
            }
            htlcInfos.foreach { case (channelId, infos) =>
              infos.foreach { case (commitmentNumber, paymentHash, expiry) =>
                using(connection.prepareStatement("INSERT INTO htlc_infos VALUES (?, ?, ?, ?)")) { statement =>
                  statement.setBytes(1, channelId.toArray)
                  statement.setLong(2, commitmentNumber)
                  statement.setBytes(3, paymentHash.toArray)
                  statement.setLong(4, expiry.toLong)
                  statement.executeUpdate()
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
