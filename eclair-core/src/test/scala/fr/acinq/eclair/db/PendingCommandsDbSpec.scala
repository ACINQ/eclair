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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FAIL_MALFORMED_HTLC, CMD_FULFILL_HTLC, HtlcSettlementCommand}
import fr.acinq.eclair.db.pg.PgPendingCommandsDb
import fr.acinq.eclair.db.sqlite.SqlitePendingCommandsDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{setVersion, using}
import fr.acinq.eclair.randomBytes32
import fr.acinq.eclair.wire.internal.CommandCodecs.cmdCodec
import fr.acinq.eclair.wire.protocol.{FailureMessageCodecs, UnknownNextPeer}
import org.scalatest.funsuite.AnyFunSuite

import scala.util.Random

class PendingCommandsDbSpec extends AnyFunSuite {

  import PendingCommandsDbSpec._
  import fr.acinq.eclair.TestDatabases.{forAllDbs, migrationCheck}

  test("init database two times in a row") {
    forAllDbs {
      case sqlite: TestSqliteDatabases =>
        new SqlitePendingCommandsDb(sqlite.connection)
        new SqlitePendingCommandsDb(sqlite.connection)
      case pg: TestPgDatabases =>
        new PgPendingCommandsDb()(pg.datasource, pg.lock)
        new PgPendingCommandsDb()(pg.datasource, pg.lock)
    }
  }

  test("add/remove/list messages") {
    forAllDbs { dbs =>
      val db = dbs.pendingCommands

      val channelId1 = randomBytes32()
      val channelId2 = randomBytes32()
      val msg0 = CMD_FULFILL_HTLC(0, randomBytes32())
      val msg1 = CMD_FULFILL_HTLC(1, randomBytes32())
      val msg2 = CMD_FAIL_HTLC(2, Left(randomBytes32()))
      val msg3 = CMD_FAIL_HTLC(3, Left(randomBytes32()))
      val msg4 = CMD_FAIL_MALFORMED_HTLC(4, randomBytes32(), FailureMessageCodecs.BADONION)

      assert(db.listSettlementCommands(channelId1).toSet == Set.empty)
      db.addSettlementCommand(channelId1, msg0)
      db.addSettlementCommand(channelId1, msg0) // duplicate
      db.addSettlementCommand(channelId1, msg1)
      db.addSettlementCommand(channelId1, msg2)
      db.addSettlementCommand(channelId1, msg3)
      db.addSettlementCommand(channelId1, msg4)
      db.addSettlementCommand(channelId2, msg0) // same messages but for different channel
      db.addSettlementCommand(channelId2, msg1)
      assert(db.listSettlementCommands(channelId1).toSet == Set(msg0, msg1, msg2, msg3, msg4))
      assert(db.listSettlementCommands(channelId2).toSet == Set(msg0, msg1))
      assert(db.listSettlementCommands().toSet == Set((channelId1, msg0), (channelId1, msg1), (channelId1, msg2), (channelId1, msg3), (channelId1, msg4), (channelId2, msg0), (channelId2, msg1)))
      db.removeSettlementCommand(channelId1, msg1.id)
      assert(db.listSettlementCommands().toSet == Set((channelId1, msg0), (channelId1, msg2), (channelId1, msg3), (channelId1, msg4), (channelId2, msg0), (channelId2, msg1)))
    }
  }

  test("migrate database v1 -> current") {
    forAllDbs {
      case dbs: TestPgDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE TABLE pending_relay (channel_id TEXT NOT NULL, htlc_id BIGINT NOT NULL, data BYTEA NOT NULL, PRIMARY KEY(channel_id, htlc_id))")
              setVersion(statement, "pending_relay", 1)
            }
            testCases.foreach { testCase =>
              using(connection.prepareStatement("INSERT INTO pending_relay VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) { statement =>
                statement.setString(1, testCase.channelId.toHex)
                statement.setLong(2, testCase.cmd.id)
                statement.setBytes(3, cmdCodec.encode(testCase.cmd).require.toByteArray)
                statement.executeUpdate()
              }
            }
          },
          dbName = PgPendingCommandsDb.DB_NAME,
          targetVersion = PgPendingCommandsDb.CURRENT_VERSION,
          postCheck = _ =>
            assert(dbs.pendingCommands.listSettlementCommands().toSet == testCases.map(tc => tc.channelId -> tc.cmd))
        )
      case dbs: TestSqliteDatabases =>
        migrationCheck(
          dbs = dbs,
          initializeTables = connection => {
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE TABLE pending_relay (channel_id BLOB NOT NULL, htlc_id INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(channel_id, htlc_id))")
              setVersion(statement, "pending_relay", 1)
            }
            testCases.foreach { testCase =>
              using(connection.prepareStatement("INSERT OR IGNORE INTO pending_relay VALUES (?, ?, ?)")) { statement =>
                statement.setBytes(1, testCase.channelId.toArray)
                statement.setLong(2, testCase.cmd.id)
                statement.setBytes(3, cmdCodec.encode(testCase.cmd).require.toByteArray)
                statement.executeUpdate()
              }
            }
          },
          dbName = SqlitePendingCommandsDb.DB_NAME,
          targetVersion = SqlitePendingCommandsDb.CURRENT_VERSION,
          postCheck = _ =>
            assert(dbs.pendingCommands.listSettlementCommands().toSet == testCases.map(tc => tc.channelId -> tc.cmd))
        )
    }
  }

}

object PendingCommandsDbSpec {

  case class TestCase(channelId: ByteVector32,
                      cmd: HtlcSettlementCommand)

  val testCases: Set[TestCase] = (0 until 100).flatMap { _ =>
    val channelId = randomBytes32()
    val cmds = (0 until Random.nextInt(5)).map { _ =>
      Random.nextInt(2) match {
        case 0 => CMD_FULFILL_HTLC(Random.nextLong(100_000), randomBytes32())
        case 1 => CMD_FAIL_HTLC(Random.nextLong(100_000), Right(UnknownNextPeer()))
      }
    }
    cmds.map(cmd => TestCase(channelId, cmd))
  }.toSet
}
