package fr.acinq.eclair.wire.hosted

import java.util.UUID

import fr.acinq.eclair._
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.{MilliSatoshi, ShortChannelId, TestConstants, UInt64, randomBytes32, randomBytes64, randomKey}
import fr.acinq.eclair.channel.{Channel, ChannelVersion, HOSTED_DATA_COMMITMENTS, LocalChanges}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.{Local, Origin, Relayed}
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IN, OUT}
import fr.acinq.eclair.wire._
import org.scalatest.FunSuite
import scodec.bits.ByteVector

import scala.util.Random

class HostedChannelCodecsSpec extends FunSuite {

  def bin(len: Int, fill: Byte): ByteVector = ByteVector.fill(len)(fill)

  def bin32(fill: Byte) = ByteVector32(bin(32, fill))

  test("encode/decode hosted commitments") {
    val init_hosted_channel = InitHostedChannel(UInt64(6), 10 msat, 20, 500000000L msat, 5000, 1000000 sat, 1000000 msat)
    val in_flight_htlc = InFlightHtlc(1L, 600000000000L msat, randomBytes32, CltvExpiry(1000L))
    val lcss1 = LastCrossSignedState(bin(47, 0), init_hosted_channel, 10000, 10000 msat, 20000 msat, 10, 20, List(in_flight_htlc, in_flight_htlc), List(in_flight_htlc, in_flight_htlc), randomBytes64)

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
      shortChannelId = ShortChannelId(42),
      lastCrossSignedState = lcss1,
      allLocalUpdates = 100L,
      allRemoteUpdates = 101L,
      localChanges = LocalChanges(List(add1, add2), List(add1, add2), Nil),
      remoteUpdates = List(add1, add2),
      localSpec = cs,
      originChannels = Map(42L -> Local(UUID.randomUUID, None), 15000L -> Relayed(ByteVector32(ByteVector.fill(32)(42)), 43, MilliSatoshi(11000000L), MilliSatoshi(10000000L))),
      channelId = ByteVector32.Zeroes,
      updateOpt = Some(channelUpdate),
      localError = None,
      remoteError = Some(error))

    val binary = HostedChannelCodecs.HOSTED_DATA_COMMITMENTS_Codec.encode(hdc).require
    val check = HostedChannelCodecs.HOSTED_DATA_COMMITMENTS_Codec.decodeValue(binary).require
    assert(hdc.localSpec === check.localSpec)
    assert(hdc === check)
  }
}
