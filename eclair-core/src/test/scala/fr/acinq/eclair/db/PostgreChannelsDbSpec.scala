package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import com.softwaremill.quicklens._
import fr.acinq.eclair.db.postgre.PostgreChannelsDb
import fr.acinq.eclair.{CltvExpiry, TestConstants}
import fr.acinq.eclair.wire.ChannelCodecsSpec
import org.postgresql.util.PSQLException
import org.scalatest.FunSuite
import scodec.bits.ByteVector

class PostgreChannelsDbSpec extends FunSuite {
  test("add/remove/list channels") {
    val db = new PostgreChannelsDb(TestConstants.throwawayPostgreDb())
    val channel1 = ChannelCodecsSpec.normal
    val channel2 = ChannelCodecsSpec.normal.modify(_.commitments.channelId).setTo(ByteVector32.One)

    val commitNumber = 42
    val paymentHash1 = ByteVector32.Zeroes
    val cltvExpiry1 = CltvExpiry(123)
    val paymentHash2 = ByteVector32(ByteVector.fill(32)(1))
    val cltvExpiry2 = CltvExpiry(656)

    intercept[PSQLException](db.addOrUpdateHtlcInfo(channel1.channelId, commitNumber, paymentHash1, cltvExpiry1)) // no related channel

    assert(db.listLocalChannels().toSet === Set.empty)
    db.addOrUpdateChannel(channel1)
    db.addOrUpdateChannel(channel1)
    assert(db.listLocalChannels() === List(channel1))

    db.addOrUpdateChannel(channel2)
    db.addOrUpdateChannel(channel2)
    assert(db.listLocalChannels().toSet === Set(channel1, channel2))

    assert(db.listHtlcInfos(channel1.channelId, commitNumber).toList == Nil)
    db.addOrUpdateHtlcInfo(channel1.channelId, commitNumber, paymentHash1, cltvExpiry1)
    db.addOrUpdateHtlcInfo(channel1.channelId, commitNumber, paymentHash2, cltvExpiry2)
    assert(db.listHtlcInfos(channel1.channelId, commitNumber).toSet == Set((paymentHash1, cltvExpiry1), (paymentHash2, cltvExpiry2)))
    assert(db.listHtlcInfos(channel1.channelId, 43).toList == Nil)

    db.addOrUpdateHtlcInfo(channel2.channelId, commitNumber, paymentHash1, cltvExpiry1)
    db.addOrUpdateHtlcInfo(channel2.channelId, commitNumber, paymentHash2, cltvExpiry2)

    db.removeChannel(channel1.channelId)
    assert(db.listLocalChannels() === List(channel2))
    assert(db.listHtlcInfos(channel1.channelId, commitNumber).toList == Nil)
    assert(db.listHtlcInfos(channel2.channelId, commitNumber).toSet == Set((paymentHash1, cltvExpiry1), (paymentHash2, cltvExpiry2)))

    db.removeChannel(channel2.channelId)
    assert(db.listLocalChannels() === Nil)
    assert(db.listHtlcInfos(channel2.channelId, commitNumber).toList == Nil)
  }
}