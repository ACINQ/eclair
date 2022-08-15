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
import fr.acinq.eclair.db.pg.PgPeersDb
import fr.acinq.eclair.db.sqlite.SqlitePeersDb
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair._
import fr.acinq.eclair.wire.protocol.{NodeAddress, Tor2, Tor3}
import org.scalatest.funsuite.AnyFunSuite

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

      case class TestCase(nodeId: PublicKey, nodeAddress: NodeAddress)

      val peer_1 = TestCase(randomKey().publicKey, NodeAddress.fromParts("127.0.0.1", 42000).get)
      val peer_1_bis = TestCase(peer_1.nodeId, NodeAddress.fromParts("127.0.0.1", 1112).get)
      val peer_2 = TestCase(randomKey().publicKey, Tor2("z4zif3fy7fe7bpg3", 4231))
      val peer_3 = TestCase(randomKey().publicKey, Tor3("mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd", 4231))

      assert(db.listPeers().toSet == Set.empty)
      db.addOrUpdatePeer(peer_1.nodeId, peer_1.nodeAddress)
      assert(db.getPeer(peer_1.nodeId) == Some(peer_1.nodeAddress))
      assert(db.getPeer(peer_2.nodeId) == None)
      db.addOrUpdatePeer(peer_1.nodeId, peer_1.nodeAddress) // duplicate is ignored
      assert(db.listPeers().size == 1)
      db.addOrUpdatePeer(peer_2.nodeId, peer_2.nodeAddress)
      db.addOrUpdatePeer(peer_3.nodeId, peer_3.nodeAddress)
      assert(db.listPeers().map(p => TestCase(p._1, p._2)).toSet == Set(peer_1, peer_2, peer_3))
      db.removePeer(peer_2.nodeId)
      assert(db.listPeers().map(p => TestCase(p._1, p._2)).toSet == Set(peer_1, peer_3))
      db.addOrUpdatePeer(peer_1_bis.nodeId, peer_1_bis.nodeAddress)
      assert(db.getPeer(peer_1.nodeId) == Some(peer_1_bis.nodeAddress))
      assert(db.listPeers().map(p => TestCase(p._1, p._2)).toSet == Set(peer_1_bis, peer_3))
    }
  }

  test("concurrent peer updates") {
    forAllDbs { dbs =>
      val db = dbs.peers
      implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(8))
      val Success(peerAddress) = NodeAddress.fromParts("127.0.0.1", 42000)
      val futures = for (_ <- 0 until 10000) yield {
        Future(db.addOrUpdatePeer(randomKey().publicKey, peerAddress))
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

      assert(db.getRelayFees(a) == None)
      assert(db.getRelayFees(b) == None)
      db.addOrUpdateRelayFees(a, RelayFees(1 msat, 123))
      assert(db.getRelayFees(a) == Some(RelayFees(1 msat, 123)))
      assert(db.getRelayFees(b) == None)
      db.addOrUpdateRelayFees(a, RelayFees(2 msat, 456))
      assert(db.getRelayFees(a) == Some(RelayFees(2 msat, 456)))
      assert(db.getRelayFees(b) == None)
      db.addOrUpdateRelayFees(b, RelayFees(3 msat, 789))
      assert(db.getRelayFees(a) == Some(RelayFees(2 msat, 456)))
      assert(db.getRelayFees(b) == Some(RelayFees(3 msat, 789)))
    }
  }

}
