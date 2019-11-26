package fr.acinq.eclair.db

import java.util.UUID

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.channel.{Channel, ChannelVersion, HOSTED_DATA_COMMITMENTS}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId, TestConstants, UInt64, randomBytes32, randomBytes64, randomKey}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IN, OUT}
import fr.acinq.eclair.wire.{Error, InitHostedChannel, LastCrossSignedState, StateOverride, UpdateAddHtlc}
import org.scalatest.FunSuite
import scodec.bits.ByteVector
import fr.acinq.eclair._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.Origin

import scala.util.Random

class PostgreHostedChannelsDbSpec extends FunSuite {

  def bin(len: Int, fill: Byte): ByteVector = ByteVector.fill(len)(fill)

  def bin32(fill: Byte) = ByteVector32(bin(32, fill))

  val add1 = UpdateAddHtlc(
    channelId = randomBytes32,
    id = Random.nextInt(Int.MaxValue),
    amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
    paymentHash = randomBytes32,
    onionRoutingPacket = Sphinx.emptyOnionPacket)
  val add2 = UpdateAddHtlc(
    channelId = randomBytes32,
    id = Random.nextInt(Int.MaxValue),
    amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
    paymentHash = randomBytes32,
    onionRoutingPacket = Sphinx.emptyOnionPacket)

  val init_hosted_channel = InitHostedChannel(UInt64(6), 10 msat, 20, 500000000L msat, 5000, 1000000 sat, 1000000 msat)
  val lcss1 = LastCrossSignedState(bin(47, 0), init_hosted_channel, 10000, 10000 msat, 20000 msat, 10, 20, List(add1, add2), List(add2, add1), randomBytes64, randomBytes64)

  val htlc1 = DirectedHtlc(direction = IN, add = add1)
  val htlc2 = DirectedHtlc(direction = OUT, add = add2)
  val cs = CommitmentSpec(
    htlcs = Set(htlc1, htlc2),
    feeratePerKw = 0L,
    toLocal = MilliSatoshi(Random.nextInt(Int.MaxValue)),
    toRemote = MilliSatoshi(Random.nextInt(Int.MaxValue))
  )

  val channelUpdate = Announcements.makeChannelUpdate(ByteVector32(ByteVector.fill(32)(1)), randomKey, randomKey.publicKey, ShortChannelId(142553), CltvExpiryDelta(42), MilliSatoshi(15), MilliSatoshi(575), 53, Channel.MAX_FUNDING.toMilliSatoshi)

  val error = Error(ByteVector32.Zeroes, ByteVector.fromValidHex("0000"))

  val hdc = HOSTED_DATA_COMMITMENTS(
    remoteNodeId = randomKey.publicKey,
    channelVersion = ChannelVersion.STANDARD,
    lastCrossSignedState = lcss1,
    futureUpdates = List(Right(add1), Left(add2)),
    originChannels = Map(42L -> Origin.Local(UUID.randomUUID, None), 15000L -> Origin.Relayed(ByteVector32(ByteVector.fill(32)(42)), 43, MilliSatoshi(11000000L), MilliSatoshi(10000000L))),
    localSpec = cs,
    channelId = ByteVector32.Zeroes,
    isHost = false,
    channelUpdate = channelUpdate,
    localError = None,
    remoteError = Some(error),
    failedOutgoingHtlcLeftoverIds = Set(12, 67, 79, 119),
    fulfilledOutgoingHtlcLeftoverIds = Set(1, 2, 3),
    overrideProposal = Some(StateOverride(50000L, 500000 msat, 70000, 700000, randomBytes64)))

  test("get / insert / update a hosted commits") {
    val db = TestConstants.inMemoryDb().hostedChannels
    assert(db.getChannel(ByteVector32.Zeroes).isEmpty)
    val newShortChannelId = randomHostedChanShortId
    val hdc1 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = newShortChannelId))

    db.addOrUpdateChannel(hdc1) // insert channel data
    db.addOrUpdateChannel(hdc1) // update, same data
    assert(db.getChannel(ByteVector32.Zeroes).contains(hdc1))

    val hdc2 = hdc1.copy(futureUpdates = Nil)
    db.addOrUpdateChannel(hdc2) // update, new data
    assert(db.getChannel(ByteVector32.Zeroes).contains(hdc2))
  }

  test("list hot channels (with HTLCs in-flight)") {
    val db = TestConstants.inMemoryDb().hostedChannels
    val newShortChannelId1 = randomHostedChanShortId
    val newShortChannelId2 = randomHostedChanShortId
    val newShortChannelId3 = randomHostedChanShortId

    val hdc1 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = newShortChannelId1), channelId = randomBytes32)
    val hdc2 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = newShortChannelId2), channelId = randomBytes32)
    val hdc3 = hdc.copy(channelUpdate = channelUpdate.copy(shortChannelId = newShortChannelId3), channelId = randomBytes32,
      futureUpdates = Nil, localSpec = CommitmentSpec(htlcs = Set.empty, feeratePerKw = 0L, toLocal = MilliSatoshi(Random.nextInt(Int.MaxValue)),
        toRemote = MilliSatoshi(Random.nextInt(Int.MaxValue))))

    db.addOrUpdateChannel(hdc1)
    db.addOrUpdateChannel(hdc2)
    db.addOrUpdateChannel(hdc3)

    assert(db.listHotChannels().toSet === Set(hdc1, hdc2))
  }
}