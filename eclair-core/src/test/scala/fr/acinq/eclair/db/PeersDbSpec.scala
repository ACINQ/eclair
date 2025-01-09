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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases}
import fr.acinq.eclair._
import fr.acinq.eclair.db.pg.PgPeersDb
import fr.acinq.eclair.db.sqlite.SqlitePeersDb
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.wire.protocol._
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.HexStringSyntax

import java.util.concurrent.Executors
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.Success

class PeersDbSpec extends AnyFunSuite {

  import fr.acinq.eclair.TestDatabases.forAllDbs

  test("init database two times in a row") {
    forAllDbs {
      case sqlite: TestSqliteDatabases =>
        new SqlitePeersDb(sqlite.connection)
        new SqlitePeersDb(sqlite.connection)
      case pg: TestPgDatabases =>
        new PgPeersDb()(pg.datasource, pg.lock)
        new PgPeersDb()(pg.datasource, pg.lock)
    }
  }

  test("add/remove/list peers") {
    forAllDbs { dbs =>
      val db = dbs.peers

      case class TestCase(nodeId: PublicKey, nodeInfo: NodeInfo)

      val peer1a = TestCase(randomKey().publicKey, NodeInfo(Features(Features.ChannelType -> FeatureSupport.Optional), NodeAddress.fromParts("127.0.0.1", 42000).toOption))
      val peer1b = TestCase(peer1a.nodeId, peer1a.nodeInfo.copy(address_opt = NodeAddress.fromParts("127.0.0.1", 1112).toOption))
      val peer1c = TestCase(peer1a.nodeId, peer1b.nodeInfo.copy(features = Features(Features.ChannelType -> FeatureSupport.Mandatory, Features.ShutdownAnySegwit -> FeatureSupport.Optional)))
      val peer2a = TestCase(randomKey().publicKey, NodeInfo(Features.empty, None))
      val peer2b = TestCase(peer2a.nodeId, NodeInfo(Features(Map[InitFeature, FeatureSupport](Features.DataLossProtect -> FeatureSupport.Optional), Set(UnknownFeature(61317))), Some(Tor2("z4zif3fy7fe7bpg3", 4231))))
      val peer3 = TestCase(randomKey().publicKey, NodeInfo(Features.empty, Some(Tor3("mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd", 4231))))

      assert(db.listPeers().isEmpty)
      db.addOrUpdatePeer(peer1a.nodeId, peer1a.nodeInfo)
      assert(db.getPeer(peer1a.nodeId).contains(peer1a.nodeInfo))
      assert(db.getPeer(peer2a.nodeId).isEmpty)
      db.addOrUpdatePeer(peer1a.nodeId, peer1a.nodeInfo) // duplicate is ignored
      assert(db.listPeers().size == 1)
      db.addOrUpdatePeer(peer2a.nodeId, peer2a.nodeInfo)
      db.addOrUpdatePeer(peer3.nodeId, peer3.nodeInfo)
      assert(db.listPeers().map(p => TestCase(p._1, p._2)).toSet == Set(peer1a, peer2a, peer3))
      db.addOrUpdatePeer(peer2b.nodeId, peer2b.nodeInfo)
      assert(db.listPeers().map(p => TestCase(p._1, p._2)).toSet == Set(peer1a, peer2b, peer3))
      db.removePeer(peer2b.nodeId)
      assert(db.listPeers().map(p => TestCase(p._1, p._2)).toSet == Set(peer1a, peer3))
      // The first peer updates its address without changing its features.
      db.addOrUpdatePeer(peer1b.nodeId, peer1b.nodeInfo)
      assert(db.getPeer(peer1b.nodeId).contains(peer1b.nodeInfo))
      assert(db.listPeers().map(p => TestCase(p._1, p._2)).toSet == Set(peer1b, peer3))
      // The first peer updates its features without an address: the previous address should be kept.
      db.addOrUpdatePeer(peer1c.nodeId, peer1c.nodeInfo.copy(address_opt = None))
      assert(db.getPeer(peer1c.nodeId).contains(peer1c.nodeInfo))
      assert(db.listPeers().map(p => TestCase(p._1, p._2)).toSet == Set(peer1c, peer3))
    }
  }

  test("concurrent peer updates") {
    forAllDbs { dbs =>
      val db = dbs.peers
      implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8))
      val Success(peerAddress) = NodeAddress.fromParts("127.0.0.1", 42000)
      val futures = for (_ <- 0 until 10000) yield {
        Future(db.addOrUpdatePeer(randomKey().publicKey, NodeInfo(Features.empty, Some(peerAddress))))
      }
      val res = Future.sequence(futures)
      Await.result(res, 60 seconds)
    }
  }

  test("add and update relay fees") {
    forAllDbs { dbs =>
      val db = dbs.peers

      val a = randomKey().publicKey
      val b = randomKey().publicKey

      assert(db.getRelayFees(a).isEmpty)
      assert(db.getRelayFees(b).isEmpty)
      db.addOrUpdateRelayFees(a, RelayFees(1 msat, 123))
      assert(db.getRelayFees(a).contains(RelayFees(1 msat, 123)))
      assert(db.getRelayFees(b).isEmpty)
      db.addOrUpdateRelayFees(a, RelayFees(2 msat, 456))
      assert(db.getRelayFees(a).contains(RelayFees(2 msat, 456)))
      assert(db.getRelayFees(b).isEmpty)
      db.addOrUpdateRelayFees(b, RelayFees(3 msat, 789))
      assert(db.getRelayFees(a).contains(RelayFees(2 msat, 456)))
      assert(db.getRelayFees(b).contains(RelayFees(3 msat, 789)))
    }
  }

  test("add/update/remove peer storage") {
    forAllDbs { dbs =>
      val db = dbs.peers

      val a = randomKey().publicKey
      val b = randomKey().publicKey

      assert(db.getStorage(a).isEmpty)
      assert(db.getStorage(b).isEmpty)
      db.updateStorage(a, hex"012345")
      assert(db.getStorage(a).contains(hex"012345"))
      assert(db.getStorage(b).isEmpty)
      db.updateStorage(a, hex"6789")
      assert(db.getStorage(a).contains(hex"6789"))
      assert(db.getStorage(b).isEmpty)
      db.updateStorage(b, hex"abcd")
      assert(db.getStorage(a).contains(hex"6789"))
      assert(db.getStorage(b).contains(hex"abcd"))

      // Actively used storage shouldn't be removed.
      db.removePeerStorage(TimestampSecond.now() + 1.hour)
      assert(db.getStorage(a).contains(hex"6789"))
      assert(db.getStorage(b).contains(hex"abcd"))

      // After removing the peer, peer storage can be removed.
      db.removePeer(a)
      assert(db.getStorage(a).contains(hex"6789"))
      db.removePeerStorage(TimestampSecond.now() - 1.hour)
      assert(db.getStorage(a).contains(hex"6789"))
      db.removePeerStorage(TimestampSecond.now() + 1.hour)
      assert(db.getStorage(a).isEmpty)
      assert(db.getStorage(b).contains(hex"abcd"))
    }
  }

  test("migrate peers database (sqlite -> v4, postgres -> v5)") {
    import fr.acinq.eclair.TestDatabases.migrationCheck
    import fr.acinq.eclair.db.jdbc.JdbcUtils.{setVersion, using}

    val peerId1 = randomKey().publicKey
    val peerId2 = randomKey().publicKey
    val peerInfos = Map(
      peerId1 -> NodeInfo(Features.empty, Some(NodeAddress.fromParts("127.0.0.1", 9735).get)),
      peerId2 -> NodeInfo(Features.empty, Some(Tor3("mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd", 9735)))
    )

    forAllDbs {
      case dbs: TestSqliteDatabases =>
        migrationCheck(
          dbs,
          initializeTables = connection => {
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE TABLE peers (node_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
              setVersion(statement, SqlitePeersDb.DB_NAME, 3)
            }
            // Add existing peers.
            peerInfos.foreach {
              case (nodeId, nodeInfo) => using(connection.prepareStatement("INSERT INTO peers VALUES (?, ?)")) { statement =>
                statement.setBytes(1, nodeId.value.toArray)
                statement.setBytes(2, CommonCodecs.nodeaddress.encode(nodeInfo.address_opt.get).require.toByteArray)
                statement.executeUpdate()
              }
            }
          },
          dbName = SqlitePeersDb.DB_NAME,
          targetVersion = 4,
          postCheck = connection => {
            val postMigrationDb = new SqlitePeersDb(connection)
            assert(postMigrationDb.listPeers() == peerInfos)
            val updatedPeerInfo1 = peerInfos(peerId1).copy(features = Features(Features.DataLossProtect -> FeatureSupport.Mandatory))
            postMigrationDb.addOrUpdatePeer(peerId1, updatedPeerInfo1.copy(address_opt = None))
            assert(postMigrationDb.getPeer(peerId1).contains(updatedPeerInfo1))
          }
        )
      case dbs: TestPgDatabases =>
        migrationCheck(
          dbs,
          initializeTables = connection => {
            using(connection.createStatement()) { statement =>
              statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS local")
              statement.executeUpdate("CREATE TABLE local.peers (node_id TEXT NOT NULL PRIMARY KEY, data BYTEA NOT NULL)")
              setVersion(statement, SqlitePeersDb.DB_NAME, 4)
            }
            // Add existing peers.
            peerInfos.foreach {
              case (nodeId, nodeInfo) => using(connection.prepareStatement("INSERT INTO local.peers VALUES (?, ?)")) { statement =>
                statement.setString(1, nodeId.value.toHex)
                statement.setBytes(2, CommonCodecs.nodeaddress.encode(nodeInfo.address_opt.get).require.toByteArray)
                statement.executeUpdate()
              }
            }
          },
          dbName = PgPeersDb.DB_NAME,
          targetVersion = 5,
          postCheck = _ => {
            val postMigrationDb = dbs.peers
            assert(postMigrationDb.listPeers() == peerInfos)
            val updatedPeerInfo1 = peerInfos(peerId1).copy(features = Features(Features.ChannelType -> FeatureSupport.Optional))
            postMigrationDb.addOrUpdatePeer(peerId1, updatedPeerInfo1.copy(address_opt = None))
            assert(postMigrationDb.getPeer(peerId1).contains(updatedPeerInfo1))
          }
        )
    }
  }

}
