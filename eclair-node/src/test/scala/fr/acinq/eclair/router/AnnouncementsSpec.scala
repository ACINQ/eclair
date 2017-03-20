package fr.acinq.eclair.router

import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair._
import fr.acinq.eclair.router.Announcements._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.compat.Platform

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class AnnouncementsSpec extends FunSuite {

  test("create valid signed channel announcement") {
    val (node_a, node_b, bitcoin_a, bitcoin_b) = (randomKey, randomKey, randomKey, randomKey)
    val (node_a_sig, bitcoin_a_sig) = signChannelAnnouncement(42, node_a, node_b.publicKey, bitcoin_a, bitcoin_b.publicKey, "")
    val (node_b_sig, bitcoin_b_sig) = signChannelAnnouncement(42, node_b, node_a.publicKey, bitcoin_b, bitcoin_a.publicKey, "")
    val ann = makeChannelAnnouncement(42, node_a.publicKey, node_b.publicKey, bitcoin_a.publicKey, bitcoin_b.publicKey, node_a_sig, node_b_sig, bitcoin_a_sig, bitcoin_b_sig)
    assert(checkSigs(ann))
    assert(checkSigs(ann.copy(nodeId1 = randomKey.publicKey)) === false)
  }

  test("create valid signed node announcement") {
    val ann = makeNodeAnnouncement(Alice.nodeParams.privateKey, Alice.nodeParams.alias, Alice.nodeParams.color, Alice.nodeParams.address :: Nil, Platform.currentTime / 1000)
    assert(checkSig(ann))
    assert(checkSig(ann.copy(timestamp = 153)) === false)
  }

  test("create valid signed channel update announcement") {
    val ann = makeChannelUpdate(Alice.nodeParams.privateKey, randomKey.publicKey, 45561, Alice.nodeParams.expiryDeltaBlocks, Alice.nodeParams.htlcMinimumMsat, Alice.nodeParams.feeBaseMsat, Alice.nodeParams.feeProportionalMillionth, Platform.currentTime / 1000)
    assert(checkSig(ann, Alice.nodeParams.privateKey.publicKey))
    assert(checkSig(ann, randomKey.publicKey) === false)
  }

}
