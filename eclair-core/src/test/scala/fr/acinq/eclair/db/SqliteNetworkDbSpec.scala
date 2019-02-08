/*
 * Copyright 2018 ACINQ SAS
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

import java.net.{InetAddress, InetSocketAddress}
import java.sql.DriverManager

import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.{Block, Crypto, Satoshi}
import fr.acinq.eclair.db.sqlite.SqliteNetworkDb
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.{Color, NodeAddress, Tor2}
import fr.acinq.eclair.{ShortChannelId, randomKey}
import org.scalatest.FunSuite
import org.sqlite.SQLiteException


class SqliteNetworkDbSpec extends FunSuite {

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  test("init sqlite 2 times in a row") {
    val sqlite = inmem
    val db1 = new SqliteNetworkDb(sqlite)
    val db2 = new SqliteNetworkDb(sqlite)
  }

  test("add/remove/list nodes") {
    val sqlite = inmem
    val db = new SqliteNetworkDb(sqlite)

    val node_1 = Announcements.makeNodeAnnouncement(randomKey, "node-alice", Color(100.toByte, 200.toByte, 300.toByte), NodeAddress.fromParts("192.168.1.42", 42000).get :: Nil)
    val node_2 = Announcements.makeNodeAnnouncement(randomKey, "node-bob", Color(100.toByte, 200.toByte, 300.toByte), NodeAddress.fromParts("192.168.1.42", 42000).get :: Nil)
    val node_3 = Announcements.makeNodeAnnouncement(randomKey, "node-charlie", Color(100.toByte, 200.toByte, 300.toByte), NodeAddress.fromParts("192.168.1.42", 42000).get :: Nil)
    val node_4 = Announcements.makeNodeAnnouncement(randomKey, "node-charlie", Color(100.toByte, 200.toByte, 300.toByte), Tor2("aaaqeayeaudaocaj", 42000) :: Nil)

    assert(db.listNodes().toSet === Set.empty)
    db.addNode(node_1)
    db.addNode(node_1) // duplicate is ignored
    assert(db.listNodes().size === 1)
    db.addNode(node_2)
    db.addNode(node_3)
    db.addNode(node_4)
    assert(db.listNodes().toSet === Set(node_1, node_2, node_3, node_4))
    db.removeNode(node_2.nodeId)
    assert(db.listNodes().toSet === Set(node_1, node_3, node_4))
    db.updateNode(node_1)

    assert(node_4.addresses == List(Tor2("aaaqeayeaudaocaj", 42000)))
  }

  test("add/remove/list channels and channel_updates") {
    val sqlite = inmem
    val db = new SqliteNetworkDb(sqlite)

    def sig = Crypto.encodeSignature(Crypto.sign(randomKey.toBin, randomKey)) :+ 1.toByte

    val channel_1 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(42), randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, sig, sig)
    val channel_2 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(43), randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, sig, sig)
    val channel_3 = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, ShortChannelId(44), randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, randomKey.publicKey, sig, sig, sig, sig)

    val txid_1 = randomKey.toBin
    val txid_2 = randomKey.toBin
    val txid_3 = randomKey.toBin
    val capacity = Satoshi(10000)

    assert(db.listChannels().toSet === Set.empty)
    db.addChannel(channel_1, txid_1, capacity)
    db.addChannel(channel_1, txid_1, capacity) // duplicate is ignored
    assert(db.listChannels().size === 1)
    db.addChannel(channel_2, txid_2, capacity)
    db.addChannel(channel_3, txid_3, capacity)
    assert(db.listChannels().toSet === Set((channel_1, (txid_1, capacity)), (channel_2, (txid_2, capacity)), (channel_3, (txid_3, capacity))))
    db.removeChannel(channel_2.shortChannelId)
    assert(db.listChannels().toSet === Set((channel_1, (txid_1, capacity)), (channel_3, (txid_3, capacity))))

    val channel_update_1 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, randomKey, randomKey.publicKey, ShortChannelId(42), 5, 7000000, 50000, 100, 500000000L, true)
    val channel_update_2 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, randomKey, randomKey.publicKey, ShortChannelId(43), 5, 7000000, 50000, 100, 500000000L, true)
    val channel_update_3 = Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, randomKey, randomKey.publicKey, ShortChannelId(44), 5, 7000000, 50000, 100, 500000000L, true)

    assert(db.listChannelUpdates().toSet === Set.empty)
    db.addChannelUpdate(channel_update_1)
    db.addChannelUpdate(channel_update_1) // duplicate is ignored
    assert(db.listChannelUpdates().size === 1)
    intercept[SQLiteException](db.addChannelUpdate(channel_update_2))
    db.addChannelUpdate(channel_update_3)
    db.removeChannel(channel_3.shortChannelId)
    assert(db.listChannels().toSet === Set((channel_1, (txid_1, capacity))))
    assert(db.listChannelUpdates().toSet === Set(channel_update_1))
    db.updateChannelUpdate(channel_update_1)
  }

  test("add/remove/test pruned channels") {
    val sqlite = inmem
    val db = new SqliteNetworkDb(sqlite)

    db.addToPruned(ShortChannelId(1))
    db.addToPruned(ShortChannelId(5))

    assert(db.isPruned(ShortChannelId(1)))
    assert(!db.isPruned(ShortChannelId(3)))
    assert(db.isPruned(ShortChannelId(1)))

    db.removeFromPruned(ShortChannelId(5))
    assert(!db.isPruned(ShortChannelId(5)))
  }

}
