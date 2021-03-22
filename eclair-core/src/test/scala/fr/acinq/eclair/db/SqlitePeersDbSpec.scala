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

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.wire.protocol.{NodeAddress, Tor2, Tor3}
import fr.acinq.eclair.{TestConstants, randomKey}
import org.scalatest.funsuite.AnyFunSuite


class SqlitePeersDbSpec extends AnyFunSuite {

  import TestConstants.forAllDbs

  test("init sqlite 2 times in a row") {
    forAllDbs { dbs =>
      val db1 = dbs.peers()
      val db2 = dbs.peers()
    }
  }

  test("add/remove/list peers") {
    forAllDbs { dbs =>
      val db = dbs.peers()

      case class TestCase(nodeId: PublicKey, nodeAddress: NodeAddress)

      val peer_1 = TestCase(randomKey.publicKey, NodeAddress.fromParts("127.0.0.1", 42000).get)
      val peer_1_bis = TestCase(peer_1.nodeId, NodeAddress.fromParts("127.0.0.1", 1112).get)
      val peer_2 = TestCase(randomKey.publicKey, Tor2("z4zif3fy7fe7bpg3", 4231))
      val peer_3 = TestCase(randomKey.publicKey, Tor3("mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd", 4231))

      assert(db.listPeers().toSet === Set.empty)
      db.addOrUpdatePeer(peer_1.nodeId, peer_1.nodeAddress)
      assert(db.getPeer(peer_1.nodeId) === Some(peer_1.nodeAddress))
      assert(db.getPeer(peer_2.nodeId) === None)
      db.addOrUpdatePeer(peer_1.nodeId, peer_1.nodeAddress) // duplicate is ignored
      assert(db.listPeers().size === 1)
      db.addOrUpdatePeer(peer_2.nodeId, peer_2.nodeAddress)
      db.addOrUpdatePeer(peer_3.nodeId, peer_3.nodeAddress)
      assert(db.listPeers().map(p => TestCase(p._1, p._2)).toSet === Set(peer_1, peer_2, peer_3))
      db.removePeer(peer_2.nodeId)
      assert(db.listPeers().map(p => TestCase(p._1, p._2)).toSet === Set(peer_1, peer_3))
      db.addOrUpdatePeer(peer_1_bis.nodeId, peer_1_bis.nodeAddress)
      assert(db.getPeer(peer_1.nodeId) === Some(peer_1_bis.nodeAddress))
      assert(db.listPeers().map(p => TestCase(p._1, p._2)).toSet === Set(peer_1_bis, peer_3))
    }
  }

}
