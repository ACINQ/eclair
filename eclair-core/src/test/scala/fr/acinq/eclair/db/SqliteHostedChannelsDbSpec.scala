package fr.acinq.eclair.db

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.{Channel, ChannelVersion, HOSTED_DATA_COMMITMENTS, LocalChanges}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId, TestConstants, UInt64, randomBytes32, randomBytes64, randomKey}
import fr.acinq.eclair.db.sqlite.SqliteHostedChannelsDb
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.ChannelCodecs.originCodec
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IN, OUT}
import fr.acinq.eclair.wire.{Error, InitHostedChannel, LastCrossSignedState, TlvStream, UpdateAddHtlc, UpdateAddSecretTlv}
import org.scalatest.FunSuite
import scodec.bits.ByteVector
import fr.acinq.eclair._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.Relayed

import scala.util.Random

class SqliteHostedChannelsDbSpec extends FunSuite {

  def bin(len: Int, fill: Byte): ByteVector = ByteVector.fill(len)(fill)

  def bin32(fill: Byte) = ByteVector32(bin(32, fill))

  val add1 = UpdateAddHtlc(
    channelId = randomBytes32,
    id = Random.nextInt(Int.MaxValue),
    amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
    paymentHash = randomBytes32,
    onionRoutingPacket = Sphinx.emptyOnionPacket,
    tlvStream = TlvStream(UpdateAddSecretTlv.Secret(originCodec.encode(Relayed(ByteVector32(ByteVector.fill(32)(42)), 43, 11000000 msat, 10000000 msat)).require.toByteVector) :: Nil))
  val add2 = UpdateAddHtlc(
    channelId = randomBytes32,
    id = Random.nextInt(Int.MaxValue),
    amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
    paymentHash = randomBytes32,
    onionRoutingPacket = Sphinx.emptyOnionPacket)

  val init_hosted_channel = InitHostedChannel(UInt64(6), 10 msat, 20, 500000000L msat, 5000, 1000000 sat, 1000000 msat)
  val lcss1 = LastCrossSignedState(bin(47, 0), init_hosted_channel, 10000, 10000 msat, 20000 msat, 10, 20, List(add1, add2), List(add2, add1), randomBytes64)

  val htlc1 = DirectedHtlc(direction = IN, add = add1)
  val htlc2 = DirectedHtlc(direction = OUT, add = add2)
  val htlcs = Set(htlc1, htlc2)
  val cs = CommitmentSpec(
    htlcs = Set(htlc1, htlc2),
    feeratePerKw = 0L,
    toLocal = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    toRemote = MilliSatoshi(Random.nextInt(Int.MaxValue))
  )

  val channelUpdate = Announcements.makeChannelUpdate(ByteVector32(ByteVector.fill(32)(1)), randomKey, randomKey.publicKey, ShortChannelId(142553), CltvExpiryDelta(42), MilliSatoshi(15), MilliSatoshi(575), 53, Channel.MAX_FUNDING.toMilliSatoshi)

  val error = Error(ByteVector32.Zeroes, ByteVector.fromValidHex("0000"))

  val hdc = HOSTED_DATA_COMMITMENTS(channelVersion = ChannelVersion.STANDARD,
    lastCrossSignedState = lcss1,
    allLocalUpdates = 100L,
    allRemoteUpdates = 101L,
    localChanges = LocalChanges(List(add1, add2), List(add1, add2), Nil),
    remoteUpdates = List(add1, add2),
    localSpec = cs,
    channelId = ByteVector32.Zeroes,
    isHost = false,
    channelUpdateOpt = Some(channelUpdate),
    localError = None,
    remoteError = Some(error))

  test("init sqlite 2 times in a row") {
    val sqlite = TestConstants.sqliteInMemory()
    new SqliteHostedChannelsDb(sqlite)
    new SqliteHostedChannelsDb(sqlite)
  }

  test("same getNewShortChannelId 2 times in a row") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteHostedChannelsDb(sqlite)
    assert(db.getNewShortChannelId == 1)
    assert(db.getNewShortChannelId == 1)
  }

  test("get / insert / update a hosted commits") {
    val sqlite = TestConstants.sqliteInMemory()
    val db = new SqliteHostedChannelsDb(sqlite)
    assert(db.getChannel(ByteVector32.Zeroes).isEmpty)
    val newShortChannelId = ShortChannelId(db.getNewShortChannelId)
    val hdc1 = hdc.copy(channelUpdateOpt = Some(channelUpdate.copy(shortChannelId = newShortChannelId)))

    db.addUsedShortChannelId(newShortChannelId) // mark this short id as used
    db.addOrUpdateChannel(hdc1) // insert channel data
    db.addOrUpdateChannel(hdc1) // update, same data
    assert(db.getChannel(ByteVector32.Zeroes).contains(hdc1))

    val hdc2 = hdc1.copy(allLocalUpdates = 200L)
    db.addOrUpdateChannel(hdc2) // update, new data
    assert(db.getChannel(ByteVector32.Zeroes).contains(hdc2))
    assert(db.getNewShortChannelId == newShortChannelId.toLong + 1)
  }
}