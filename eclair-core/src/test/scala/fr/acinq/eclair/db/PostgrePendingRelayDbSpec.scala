package fr.acinq.eclair.db

import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FAIL_MALFORMED_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.db.postgre.PostgrePendingRelayDb
import fr.acinq.eclair.wire.FailureMessageCodecs
import fr.acinq.eclair.{TestConstants, randomBytes32}
import org.scalatest.FunSuite

class PostgrePendingRelayDbSpec extends FunSuite {
  test("add/remove/list messages") {
    val db = new PostgrePendingRelayDb(TestConstants.throwawayPostgreDb())

    val channelId1 = randomBytes32
    val channelId2 = randomBytes32
    val msg0 = CMD_FULFILL_HTLC(0, randomBytes32)
    val msg1 = CMD_FULFILL_HTLC(1, randomBytes32)
    val msg2 = CMD_FAIL_HTLC(2, Left(randomBytes32))
    val msg3 = CMD_FAIL_HTLC(3, Left(randomBytes32))
    val msg4 = CMD_FAIL_MALFORMED_HTLC(4, randomBytes32, FailureMessageCodecs.BADONION)

    assert(db.listPendingRelay(channelId1).toSet === Set.empty)
    db.addPendingRelay(channelId1, msg0)
    db.addPendingRelay(channelId1, msg0) // duplicate
    db.addPendingRelay(channelId1, msg1)
    db.addPendingRelay(channelId1, msg2)
    db.addPendingRelay(channelId1, msg3)
    db.addPendingRelay(channelId1, msg4)
    db.addPendingRelay(channelId2, msg0) // same messages but for different channel
    db.addPendingRelay(channelId2, msg1)
    assert(db.listPendingRelay(channelId1).toSet === Set(msg0, msg1, msg2, msg3, msg4))
    assert(db.listPendingRelay(channelId2).toSet === Set(msg0, msg1))
    assert(db.listPendingRelay === Set((channelId1, msg0), (channelId1, msg1), (channelId1, msg2), (channelId1, msg3), (channelId1, msg4), (channelId2, msg0), (channelId2, msg1)))
    db.removePendingRelay(channelId1, msg1.id)
    assert(db.listPendingRelay === Set((channelId1, msg0), (channelId1, msg2), (channelId1, msg3), (channelId1, msg4), (channelId2, msg0), (channelId2, msg1)))
  }
}
