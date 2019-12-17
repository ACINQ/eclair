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

import fr.acinq.eclair.db.jdbc.JdbcUtils.using
import fr.acinq.eclair.wire.{NodeAddress, Tor2, Tor3}
import fr.acinq.eclair.{TestConstants, randomKey}
import org.scalatest.{BeforeAndAfter, FunSuite}


class SqlitePeersDbSpec extends FunSuite with BeforeAndAfter {

  import TestConstants.forAllDbs

  after {
    forAllDbs { dbs =>
      using(dbs.connection.createStatement()) { statement =>
        statement.executeUpdate("DROP TABLE IF EXISTS peers")
        statement.executeUpdate("DROP TABLE IF EXISTS versions")
      }
    }
  }

  test("init sqlite 2 times in a row") {
    forAllDbs { dbs =>
      val db1 = dbs.peers()
      val db2 = dbs.peers()
    }
  }

  test("add/remove/list peers") {
    forAllDbs { dbs =>
      val db = dbs.peers()

      val peer_1 = (randomKey.publicKey, NodeAddress.fromParts("127.0.0.1", 42000).get)
      val peer_1_bis = (peer_1._1, NodeAddress.fromParts("127.0.0.1", 1112).get)
      val peer_2 = (randomKey.publicKey, Tor2("z4zif3fy7fe7bpg3", 4231))
      val peer_3 = (randomKey.publicKey, Tor3("mrl2d3ilhctt2vw4qzvmz3etzjvpnc6dczliq5chrxetthgbuczuggyd", 4231))

      assert(db.listPeers().toSet === Set.empty)
      db.addOrUpdatePeer(peer_1._1, peer_1._2)
      db.addOrUpdatePeer(peer_1._1, peer_1._2) // duplicate is ignored
      assert(db.listPeers().size === 1)
      db.addOrUpdatePeer(peer_2._1, peer_2._2)
      db.addOrUpdatePeer(peer_3._1, peer_3._2)
      assert(db.listPeers().toSet === Set(peer_1, peer_2, peer_3))
      db.removePeer(peer_2._1)
      assert(db.listPeers().toSet === Set(peer_1, peer_3))
      db.addOrUpdatePeer(peer_1_bis._1, peer_1_bis._2)
      assert(db.listPeers().toSet === Set(peer_1_bis, peer_3))
    }
  }

}
