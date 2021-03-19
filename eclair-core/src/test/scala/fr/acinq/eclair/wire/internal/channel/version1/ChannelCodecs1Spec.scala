package fr.acinq.eclair.wire.internal.channel.version1

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.{DeterministicWallet, OutPoint, Satoshi, SatoshiLong, Script}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{ChannelVersion, LocalParams, Origin, RemoteParams}
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.CommonCodecs.setCodec
import fr.acinq.eclair.wire.UpdateAddHtlc
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec.normal
import fr.acinq.eclair.wire.internal.channel.version1.ChannelCodecs1.Codecs._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, TestConstants, UInt64, randomBytes, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._
import scodec.{Attempt, Codec, DecodeResult}

import java.util.UUID
import scala.util.Random

class ChannelCodecs1Spec extends AnyFunSuite {

  test("encode/decode key paths (all 0s)") {
    val keyPath = KeyPath(Seq(0L, 0L, 0L, 0L))
    val encoded = keyPathCodec.encode(keyPath).require
    val decoded = keyPathCodec.decode(encoded).require
    assert(keyPath === decoded.value)
  }

  test("encode/decode key paths (all 1s)") {
    val keyPath = KeyPath(Seq(0xffffffffL, 0xffffffffL, 0xffffffffL, 0xffffffffL))
    val encoded = keyPathCodec.encode(keyPath).require
    val decoded = keyPathCodec.decode(encoded).require
    assert(keyPath === decoded.value)
  }

  test("encode/decode channel version") {
    val current02 = hex"0000000102a06ea3081f0f7a8ce31eb4f0822d10d2da120d5a1b1451f0727f51c7372f0f9b"
    val current03 = hex"0000000103d5c030835d6a6248b2d1d4cac60813838011b995a66b6f78dcc9fb8b5c40c3f3"
    val current04 = hex"0000000303d5c030835d6a6248b2d1d4cac60813838011b995a66b6f78dcc9fb8b5c40c3f3"
    val current05 = hex"0000000703d5c030835d6a6248b2d1d4cac60813838011b995a66b6f78dcc9fb8b5c40c3f3"

    assert(channelVersionCodec.decode(current02.bits) === Attempt.successful(DecodeResult(ChannelVersion.STANDARD, current02.drop(4).bits)))
    assert(channelVersionCodec.decode(current03.bits) === Attempt.successful(DecodeResult(ChannelVersion.STANDARD, current03.drop(4).bits)))
    assert(channelVersionCodec.decode(current04.bits) === Attempt.successful(DecodeResult(ChannelVersion.STATIC_REMOTEKEY, current04.drop(4).bits)))
    assert(channelVersionCodec.decode(current05.bits) === Attempt.successful(DecodeResult(ChannelVersion.ANCHOR_OUTPUTS, current05.drop(4).bits)))

    assert(channelVersionCodec.encode(ChannelVersion.STANDARD) === Attempt.successful(hex"00000001".bits))
    assert(channelVersionCodec.encode(ChannelVersion.STATIC_REMOTEKEY) === Attempt.successful(hex"00000003".bits))
    assert(channelVersionCodec.encode(ChannelVersion.ANCHOR_OUTPUTS) === Attempt.successful(hex"00000007".bits))
  }

  test("encode/decode localparams") {
    def roundtrip(localParams: LocalParams, codec: Codec[LocalParams]) = {
      val encoded = codec.encode(localParams).require
      val decoded = codec.decode(encoded).require
      assert(localParams === decoded.value)
    }

    val o = LocalParams(
      nodeId = randomKey.publicKey,
      fundingKeyPath = DeterministicWallet.KeyPath(Seq(42L)),
      dustLimit = Satoshi(Random.nextInt(Int.MaxValue)),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserve = Satoshi(Random.nextInt(Int.MaxValue)),
      htlcMinimum = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      toSelfDelay = CltvExpiryDelta(Random.nextInt(Short.MaxValue)),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(randomBytes32).publicKey)),
      walletStaticPaymentBasepoint = None,
      isFunder = Random.nextBoolean(),
      features = Features(randomBytes(256)))
    val o1 = o.copy(walletStaticPaymentBasepoint = Some(PrivateKey(randomBytes32).publicKey))

    roundtrip(o, localParamsCodec(ChannelVersion.ZEROES))
    roundtrip(o1, localParamsCodec(ChannelVersion.STATIC_REMOTEKEY))
    roundtrip(o, localParamsCodec(ChannelVersion.ANCHOR_OUTPUTS))
  }


  test("encode/decode remoteparams") {
    val o = RemoteParams(
      nodeId = randomKey.publicKey,
      dustLimit = Satoshi(Random.nextInt(Int.MaxValue)),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      channelReserve = Satoshi(Random.nextInt(Int.MaxValue)),
      htlcMinimum = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      toSelfDelay = CltvExpiryDelta(Random.nextInt(Short.MaxValue)),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      fundingPubKey = randomKey.publicKey,
      revocationBasepoint = randomKey.publicKey,
      paymentBasepoint = randomKey.publicKey,
      delayedPaymentBasepoint = randomKey.publicKey,
      htlcBasepoint = randomKey.publicKey,
      features = TestConstants.Alice.nodeParams.features)
    val encoded = remoteParamsCodec.encode(o).require
    val decoded = remoteParamsCodec.decodeValue(encoded).require
    assert(o === decoded)

    // Backwards-compatibility: decode remoteparams with global features.
    val withGlobalFeatures = hex"03c70c3b813815a8b79f41622b6f2c343fa24d94fb35fa7110bbb3d4d59cd9612e0000000059844cbc000000001b1524ea000000001503cbac000000006b75d3272e38777e029fa4e94066163024177311de7ba1befec2e48b473c387bbcee1484bf276a54460215e3dfb8e6f262222c5f343f5e38c5c9a43d2594c7f06dd7ac1a4326c665dd050347aba4d56d7007a7dcf03594423dccba9ed700d11e665d261594e1154203df31020d457ee336ba6eeb328d00f1b8bd8bfefb8a4dcd5af6db4c438b7ec5106c7edc0380df17e1beb0f238e51a39122ac4c6fb57f3c4f5b7bc9432f991b1ef4a8af3570002020000018a"
    val withGlobalFeaturesDecoded = remoteParamsCodec.decode(withGlobalFeatures.bits).require.value
    assert(withGlobalFeaturesDecoded.features.toByteVector === hex"028a")
  }

  test("encode/decode htlc") {
    val add = UpdateAddHtlc(
      channelId = randomBytes32,
      id = Random.nextInt(Int.MaxValue),
      amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
      paymentHash = randomBytes32,
      onionRoutingPacket = TestConstants.emptyOnionPacket)
    val htlc1 = IncomingHtlc(add)
    val htlc2 = OutgoingHtlc(add)
    assert(htlcCodec.decodeValue(htlcCodec.encode(htlc1).require).require === htlc1)
    assert(htlcCodec.decodeValue(htlcCodec.encode(htlc2).require).require === htlc2)
  }

  test("encode/decode commitment spec") {
    val add1 = UpdateAddHtlc(
      channelId = randomBytes32,
      id = Random.nextInt(Int.MaxValue),
      amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
      paymentHash = randomBytes32,
      onionRoutingPacket = TestConstants.emptyOnionPacket)
    val add2 = UpdateAddHtlc(
      channelId = randomBytes32,
      id = Random.nextInt(Int.MaxValue),
      amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
      paymentHash = randomBytes32,
      onionRoutingPacket = TestConstants.emptyOnionPacket)
    val htlc1 = IncomingHtlc(add1)
    val htlc2 = OutgoingHtlc(add2)
    val htlcs = Set[DirectedHtlc](htlc1, htlc2)
    assert(setCodec(htlcCodec).decodeValue(setCodec(htlcCodec).encode(htlcs).require).require === htlcs)
    val o = CommitmentSpec(
      htlcs = Set(htlc1, htlc2),
      feeratePerKw = FeeratePerKw(Random.nextInt(Int.MaxValue).sat),
      toLocal = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      toRemote = MilliSatoshi(Random.nextInt(Int.MaxValue))
    )
    val encoded = commitmentSpecCodec.encode(o).require
    val decoded = commitmentSpecCodec.decode(encoded).require
    assert(o === decoded.value)
  }

  test("encode/decode origin") {
    val replyTo = TestProbe("replyTo")(ActorSystem("system")).ref

    val localHot = Origin.LocalHot(replyTo, UUID.randomUUID())
    val localCold = Origin.LocalCold(localHot.id)
    assert(originCodec.decodeValue(originCodec.encode(localHot).require).require === localCold)
    assert(originCodec.decodeValue(originCodec.encode(localCold).require).require === localCold)

    val add = UpdateAddHtlc(randomBytes32, 4324, 11000000 msat, randomBytes32, CltvExpiry(400000), TestConstants.emptyOnionPacket)
    val relayedHot = Origin.ChannelRelayedHot(replyTo, add, 11000000 msat)
    val relayedCold = Origin.ChannelRelayedCold(add.channelId, add.id, add.amountMsat, relayedHot.amountOut)
    assert(originCodec.decodeValue(originCodec.encode(relayedHot).require).require === relayedCold)
    assert(originCodec.decodeValue(originCodec.encode(relayedCold).require).require === relayedCold)

    val adds = Seq(
      UpdateAddHtlc(randomBytes32, 1L, 1000 msat, randomBytes32, CltvExpiry(400000), TestConstants.emptyOnionPacket),
      UpdateAddHtlc(randomBytes32, 1L, 2000 msat, randomBytes32, CltvExpiry(400000), TestConstants.emptyOnionPacket),
      UpdateAddHtlc(randomBytes32, 2L, 3000 msat, randomBytes32, CltvExpiry(400000), TestConstants.emptyOnionPacket),
    )
    val trampolineRelayedHot = Origin.TrampolineRelayedHot(replyTo, adds)
    val trampolineRelayedCold = Origin.TrampolineRelayedCold(trampolineRelayedHot.htlcs)
    assert(originCodec.decodeValue(originCodec.encode(trampolineRelayedHot).require).require === trampolineRelayedCold)
    assert(originCodec.decodeValue(originCodec.encode(trampolineRelayedCold).require).require === trampolineRelayedCold)
  }

  test("encode/decode map of origins") {
    val map = Map(
      1L -> Origin.LocalCold(UUID.randomUUID()),
      42L -> Origin.ChannelRelayedCold(randomBytes32, 4324, 12000000 msat, 11000000 msat),
      43L -> Origin.TrampolineRelayedCold((randomBytes32, 17L) :: (randomBytes32, 21L) :: (randomBytes32, 21L) :: Nil),
      130L -> Origin.ChannelRelayedCold(randomBytes32, -45, 13000000 msat, 12000000 msat),
      140L -> Origin.TrampolineRelayedCold((randomBytes32, 0L) :: Nil),
      1000L -> Origin.ChannelRelayedCold(randomBytes32, 10, 14000000 msat, 13000000 msat),
      -32L -> Origin.ChannelRelayedCold(randomBytes32, 54, 15000000 msat, 14000000 msat),
      -54L -> Origin.TrampolineRelayedCold((randomBytes32, 1L) :: (randomBytes32, 2L) :: Nil),
      -4L -> Origin.LocalCold(UUID.randomUUID()))
    assert(originsMapCodec.decodeValue(originsMapCodec.encode(map).require).require === map)
  }

  test("encode/decode map of spending txes") {
    val map = Map(
      OutPoint(randomBytes32, 42) -> randomBytes32,
      OutPoint(randomBytes32, 14502) -> randomBytes32,
      OutPoint(randomBytes32, 0) -> randomBytes32,
      OutPoint(randomBytes32, 454513) -> randomBytes32
    )
    assert(spentMapCodec.decodeValue(spentMapCodec.encode(map).require).require === map)
  }

  test("basic serialization test (NORMAL)") {
    val data = normal
    val bin = DATA_NORMAL_Codec.encode(data).require
    val check = DATA_NORMAL_Codec.decodeValue(bin).require
    assert(data.commitments.localCommit.spec === check.commitments.localCommit.spec)
    assert(data === check)
  }

}
