package fr.acinq.eclair.wire

import java.nio.charset.Charset

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.channel.{LocalParams, RemoteParams}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.{UInt64, randomKey}
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.ChannelCodecs._
import fr.acinq.eclair.wire.LightningMessageCodecs._
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner
import scodec.Codec
import scodec.bits.BitVector
import scodec.codecs._

import scala.util.Random

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class ChannelCodecsSpec extends FunSuite {

  def randomBytes(size: Int): BinaryData = {
    val bin = new Array[Byte](size)
    Random.nextBytes(bin)
    bin
  }

  test("encode/decode localparams") {
    val o = LocalParams(
      nodeId = randomKey.publicKey,
      dustLimitSatoshis = Random.nextInt(Int.MaxValue),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserveSatoshis = Random.nextInt(Int.MaxValue),
      htlcMinimumMsat = Random.nextInt(Int.MaxValue),
      toSelfDelay = Random.nextInt(Short.MaxValue),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      fundingPrivKey = randomKey,
      revocationSecret = randomKey.value,
      paymentKey = randomKey,
      delayedPaymentKey = randomKey.value,
      defaultFinalScriptPubKey = randomBytes(10 + Random.nextInt(200)),
      shaSeed = randomBytes(32),
      isFunder = Random.nextBoolean(),
      globalFeatures = randomBytes(256),
      localFeatures = randomBytes(256))
    val encoded = localParamsCodec.encode(o).require
    val decoded = localParamsCodec.decode(encoded).require
    assert(o === decoded.value)
  }

  test("encode/decode remoteparams") {
    val o = RemoteParams(
      nodeId = randomKey.publicKey,
      dustLimitSatoshis = Random.nextInt(Int.MaxValue),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserveSatoshis = Random.nextInt(Int.MaxValue),
      htlcMinimumMsat = Random.nextInt(Int.MaxValue),
      toSelfDelay = Random.nextInt(Short.MaxValue),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      fundingPubKey = randomKey.publicKey,
      revocationBasepoint = randomKey.publicKey.value,
      paymentBasepoint = randomKey.publicKey.value,
      delayedPaymentBasepoint = randomKey.publicKey.value,
      globalFeatures = randomBytes(256),
      localFeatures = randomBytes(256))
    val encoded = remoteParamsCodec.encode(o).require
    val decoded = remoteParamsCodec.decodeValue(encoded).require
    assert(o === decoded)
  }

  test("encode/decode direction") {
    directionCodec.decodeValue(directionCodec.encode(IN).require).require == IN
    directionCodec.decodeValue(directionCodec.encode(OUT).require).require == OUT
  }

  test("encode/decode htlc") {
    val add = UpdateAddHtlc(
      channelId = randomBytes(32),
      id = Random.nextInt(Int.MaxValue),
      amountMsat = Random.nextInt(Int.MaxValue),
      expiry = Random.nextInt(Int.MaxValue),
      paymentHash = randomBytes(32),
      onionRoutingPacket = randomBytes(Sphinx.PacketLength))
    val htlc1 = Htlc(direction = IN, add = add, previousChannelId = Some(randomBytes(32)))
    val htlc2 = Htlc(direction = OUT, add = add, previousChannelId = None)
    htlcCodec.decodeValue(htlcCodec.encode(htlc1).require).require == htlc1
    htlcCodec.decodeValue(htlcCodec.encode(htlc2).require).require == htlc2
  }

  test("encode/decode commitment spec") {
    val add1 = UpdateAddHtlc(
      channelId = randomBytes(32),
      id = Random.nextInt(Int.MaxValue),
      amountMsat = Random.nextInt(Int.MaxValue),
      expiry = Random.nextInt(Int.MaxValue),
      paymentHash = randomBytes(32),
      onionRoutingPacket = randomBytes(Sphinx.PacketLength))
    val add2 = UpdateAddHtlc(
      channelId = randomBytes(32),
      id = Random.nextInt(Int.MaxValue),
      amountMsat = Random.nextInt(Int.MaxValue),
      expiry = Random.nextInt(Int.MaxValue),
      paymentHash = randomBytes(32),
      onionRoutingPacket = randomBytes(Sphinx.PacketLength))
    val htlc1 = Htlc(direction = IN, add = add1, previousChannelId = Some(randomBytes(32)))
    val htlc2 = Htlc(direction = OUT, add = add2, previousChannelId = None)
    val htlcs = Set(htlc1, htlc2)
    setCodec(htlcCodec).decodeValue(setCodec(htlcCodec).encode(htlcs).require).require == htlcs
    val o = CommitmentSpec(
      htlcs = Set(htlc1, htlc2),
      feeratePerKw = Random.nextInt(Int.MaxValue),
      toLocalMsat = Random.nextInt(Int.MaxValue),
      toRemoteMsat = Random.nextInt(Int.MaxValue)
    )
    val encoded = commitmentSpecCodec.encode(o).require
    val decoded = commitmentSpecCodec.decode(encoded).require
    assert(o === decoded.value)

  }

}
