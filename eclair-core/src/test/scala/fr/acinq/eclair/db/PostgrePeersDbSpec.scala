package fr.acinq.eclair.db

import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.db.postgre.PostgrePeersDb
import fr.acinq.eclair.wire.{NodeAddress, Tor2, Tor3}
import fr.acinq.eclair.randomKey
import org.scalatest.FunSuite

class PostgrePeersDbSpec extends FunSuite {
  test("add/remove/list peers") {
    val db = new PostgrePeersDb(TestConstants.throwawayPostgreDb())

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
