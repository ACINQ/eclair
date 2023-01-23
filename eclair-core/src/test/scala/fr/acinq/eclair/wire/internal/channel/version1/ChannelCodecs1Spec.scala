package fr.acinq.eclair.wire.internal.channel.version1

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.scalacompat.{OutPoint, Satoshi, SatoshiLong}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{Origin, RemoteParams}
import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc, IncomingHtlc, OutgoingHtlc}
import fr.acinq.eclair.wire.internal.channel.version0.ChannelTypes0.ChannelVersion
import fr.acinq.eclair.wire.internal.channel.version1.ChannelCodecs1.Codecs._
import fr.acinq.eclair.wire.protocol.UpdateAddHtlc
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshi, MilliSatoshiLong, TestConstants, UInt64, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._
import scodec.{Attempt, DecodeResult}

import java.util.UUID
import scala.util.Random

class ChannelCodecs1Spec extends AnyFunSuite {

  test("encode/decode key paths (all 0s)") {
    val keyPath = KeyPath(Seq(0L, 0L, 0L, 0L))
    val encoded = keyPathCodec.encode(keyPath).require
    val decoded = keyPathCodec.decode(encoded).require
    assert(keyPath == decoded.value)
  }

  test("encode/decode key paths (all 1s)") {
    val keyPath = KeyPath(Seq(0xffffffffL, 0xffffffffL, 0xffffffffL, 0xffffffffL))
    val encoded = keyPathCodec.encode(keyPath).require
    val decoded = keyPathCodec.decode(encoded).require
    assert(keyPath == decoded.value)
  }

  test("encode/decode channel version") {
    val current02 = hex"0000000102a06ea3081f0f7a8ce31eb4f0822d10d2da120d5a1b1451f0727f51c7372f0f9b"
    val current03 = hex"0000000103d5c030835d6a6248b2d1d4cac60813838011b995a66b6f78dcc9fb8b5c40c3f3"
    val current04 = hex"0000000303d5c030835d6a6248b2d1d4cac60813838011b995a66b6f78dcc9fb8b5c40c3f3"
    val current05 = hex"0000000703d5c030835d6a6248b2d1d4cac60813838011b995a66b6f78dcc9fb8b5c40c3f3"

    assert(channelVersionCodec.decode(current02.bits) == Attempt.successful(DecodeResult(ChannelVersion.STANDARD, current02.drop(4).bits)))
    assert(channelVersionCodec.decode(current03.bits) == Attempt.successful(DecodeResult(ChannelVersion.STANDARD, current03.drop(4).bits)))
    assert(channelVersionCodec.decode(current04.bits) == Attempt.successful(DecodeResult(ChannelVersion.STATIC_REMOTEKEY, current04.drop(4).bits)))
    assert(channelVersionCodec.decode(current05.bits) == Attempt.successful(DecodeResult(ChannelVersion.ANCHOR_OUTPUTS, current05.drop(4).bits)))

    assert(channelVersionCodec.encode(ChannelVersion.STANDARD) == Attempt.successful(hex"00000001".bits))
    assert(channelVersionCodec.encode(ChannelVersion.STATIC_REMOTEKEY) == Attempt.successful(hex"00000003".bits))
    assert(channelVersionCodec.encode(ChannelVersion.ANCHOR_OUTPUTS) == Attempt.successful(hex"00000007".bits))
  }

  test("decode local params") {
    // we use data encoded with v1 codecs (before upfrontShutdownScript_opt was made optional) and check it can still be decoded and that upfrontShutdownScript_opt is always defined
    val std = hex"0312f3b6afc20f21b77d8404dc9a4159d60b181b44354945b654a08f86868434bf00010000002a000000004916f98200000000795517c4000000001df2678e0000000052ccc3c658d63bd20016001498a16518484aa1f90e924b6d2443393f477bad9000000100e99636b8c1b912ea3ead7d98c8329a7bfa5fd1d39f8c49ae5899fcce188b94f1a51284e1cec6c359e81ba93a368764af5c1633e959a77bec2549669c6a9140b3bd1948d0ff13d297199f6d72a9972476cf92686f1fb2e24e49f9716a5f07dcf698c36824f8b01ba5bc62e9651ff836b742e4582ad44d129baafc3d9db053e202d40828be32a59f177da042e9e6a9b23aa737df386c6028b5aeb41444a1fe719e6f2e71eedac180fb3fdcadc28834f286adba403baa3e9c241acd2451cf82d84bd3c0da8a178de9150b6d94eae100e949d2e83de961841b453838ecd1f7e69382779be1c0369c0cbbe34a73190903bc2e2fb1d6fbc144e6b299109a8e26481896"
    val staticRemoteKey = hex"0312f3b6afc20f21b77d8404dc9a4159d60b181b44354945b654a08f86868434bf00010000002a000000004916f98200000000795517c4000000001df2678e0000000052ccc3c658d63bd20016001498a16518484aa1f90e924b6d2443393f477bad90021b8b033c7bbb4473ca4e5554fa3c549f258061eb5d0612a2c9cfbc253b11a98c00000100e99636b8c1b912ea3ead7d98c8329a7bfa5fd1d39f8c49ae5899fcce188b94f1a51284e1cec6c359e81ba93a368764af5c1633e959a77bec2549669c6a9140b3bd1948d0ff13d297199f6d72a9972476cf92686f1fb2e24e49f9716a5f07dcf698c36824f8b01ba5bc62e9651ff836b742e4582ad44d129baafc3d9db053e202d40828be32a59f177da042e9e6a9b23aa737df386c6028b5aeb41444a1fe719e6f2e71eedac180fb3fdcadc28834f286adba403baa3e9c241acd2451cf82d84bd3c0da8a178de9150b6d94eae100e949d2e83de961841b453838ecd1f7e69382779be1c0369c0cbbe34a73190903bc2e2fb1d6fbc144e6b299109a8e26481896"

    require(localParamsCodec(ChannelVersion.ZEROES).decode(std.toBitVector).require.value.upfrontShutdownScript_opt.isDefined)
    require(localParamsCodec(ChannelVersion.ANCHOR_OUTPUTS).decode(std.toBitVector).require.value.upfrontShutdownScript_opt.isDefined)
    require(localParamsCodec(ChannelVersion.STATIC_REMOTEKEY).decode(staticRemoteKey.toBitVector).require.value.upfrontShutdownScript_opt.isDefined)
  }

  test("encode/decode remote params") {
    val o = RemoteParams(
      nodeId = randomKey().publicKey,
      dustLimit = Satoshi(Random.nextInt(Int.MaxValue)),
      maxHtlcValueInFlightMsat = UInt64(Random.nextInt(Int.MaxValue)),
      requestedChannelReserve_opt = Some(Satoshi(Random.nextInt(Int.MaxValue))),
      htlcMinimum = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      toSelfDelay = CltvExpiryDelta(Random.nextInt(Short.MaxValue)),
      maxAcceptedHtlcs = Random.nextInt(Short.MaxValue),
      fundingPubKey = randomKey().publicKey,
      revocationBasepoint = randomKey().publicKey,
      paymentBasepoint = randomKey().publicKey,
      delayedPaymentBasepoint = randomKey().publicKey,
      htlcBasepoint = randomKey().publicKey,
      initFeatures = TestConstants.Alice.nodeParams.features.initFeatures(),
      upfrontShutdownScript_opt = None)
    val encoded = remoteParamsCodec.encode(o).require
    val decoded = remoteParamsCodec.decodeValue(encoded).require
    assert(o == decoded)

    // Backwards-compatibility: decode remote params with global features.
    val withGlobalFeatures = hex"03c70c3b813815a8b79f41622b6f2c343fa24d94fb35fa7110bbb3d4d59cd9612e0000000059844cbc000000001b1524ea000000001503cbac000000006b75d3272e38777e029fa4e94066163024177311de7ba1befec2e48b473c387bbcee1484bf276a54460215e3dfb8e6f262222c5f343f5e38c5c9a43d2594c7f06dd7ac1a4326c665dd050347aba4d56d7007a7dcf03594423dccba9ed700d11e665d261594e1154203df31020d457ee336ba6eeb328d00f1b8bd8bfefb8a4dcd5af6db4c438b7ec5106c7edc0380df17e1beb0f238e51a39122ac4c6fb57f3c4f5b7bc9432f991b1ef4a8af3570002020000018a"
    val withGlobalFeaturesDecoded = remoteParamsCodec.decode(withGlobalFeatures.bits).require.value
    assert(withGlobalFeaturesDecoded.initFeatures.toByteVector == hex"028a")
  }

  test("encode/decode htlc") {
    val add = UpdateAddHtlc(
      channelId = randomBytes32(),
      id = Random.nextInt(Int.MaxValue),
      amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
      paymentHash = randomBytes32(),
      onionRoutingPacket = TestConstants.emptyOnionPacket,
      blinding_opt = None)
    val htlc1 = IncomingHtlc(add)
    val htlc2 = OutgoingHtlc(add)
    assert(htlcCodec.decodeValue(htlcCodec.encode(htlc1).require).require == htlc1)
    assert(htlcCodec.decodeValue(htlcCodec.encode(htlc2).require).require == htlc2)
  }

  test("encode/decode commitment spec") {
    val add1 = UpdateAddHtlc(
      channelId = randomBytes32(),
      id = Random.nextInt(Int.MaxValue),
      amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
      paymentHash = randomBytes32(),
      onionRoutingPacket = TestConstants.emptyOnionPacket,
      blinding_opt = None)
    val add2 = UpdateAddHtlc(
      channelId = randomBytes32(),
      id = Random.nextInt(Int.MaxValue),
      amountMsat = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      cltvExpiry = CltvExpiry(Random.nextInt(Int.MaxValue)),
      paymentHash = randomBytes32(),
      onionRoutingPacket = TestConstants.emptyOnionPacket,
      blinding_opt = None)
    val htlc1 = IncomingHtlc(add1)
    val htlc2 = OutgoingHtlc(add2)
    val htlcs = Set[DirectedHtlc](htlc1, htlc2)
    assert(setCodec(htlcCodec).decodeValue(setCodec(htlcCodec).encode(htlcs).require).require == htlcs)
    val o = CommitmentSpec(
      htlcs = Set(htlc1, htlc2),
      commitTxFeerate = FeeratePerKw(Random.nextInt(Int.MaxValue).sat),
      toLocal = MilliSatoshi(Random.nextInt(Int.MaxValue)),
      toRemote = MilliSatoshi(Random.nextInt(Int.MaxValue))
    )
    val encoded = commitmentSpecCodec.encode(o).require
    val decoded = commitmentSpecCodec.decode(encoded).require
    assert(o == decoded.value)
  }

  test("encode/decode origin") {
    val replyTo = TestProbe("replyTo")(ActorSystem("system")).ref

    val localHot = Origin.LocalHot(replyTo, UUID.randomUUID())
    val localCold = Origin.LocalCold(localHot.id)
    assert(originCodec.decodeValue(originCodec.encode(localHot).require).require == localCold)
    assert(originCodec.decodeValue(originCodec.encode(localCold).require).require == localCold)

    val add = UpdateAddHtlc(randomBytes32(), 4324, 11000000 msat, randomBytes32(), CltvExpiry(400000), TestConstants.emptyOnionPacket, None)
    val relayedHot = Origin.ChannelRelayedHot(replyTo, add, 11000000 msat)
    val relayedCold = Origin.ChannelRelayedCold(add.channelId, add.id, add.amountMsat, relayedHot.amountOut)
    assert(originCodec.decodeValue(originCodec.encode(relayedHot).require).require == relayedCold)
    assert(originCodec.decodeValue(originCodec.encode(relayedCold).require).require == relayedCold)

    val adds = Seq(
      UpdateAddHtlc(randomBytes32(), 1L, 1000 msat, randomBytes32(), CltvExpiry(400000), TestConstants.emptyOnionPacket, None),
      UpdateAddHtlc(randomBytes32(), 1L, 2000 msat, randomBytes32(), CltvExpiry(400000), TestConstants.emptyOnionPacket, None),
      UpdateAddHtlc(randomBytes32(), 2L, 3000 msat, randomBytes32(), CltvExpiry(400000), TestConstants.emptyOnionPacket, None),
    )
    val trampolineRelayedHot = Origin.TrampolineRelayedHot(replyTo, adds)
    val trampolineRelayedCold = Origin.TrampolineRelayedCold(trampolineRelayedHot.htlcs)
    assert(originCodec.decodeValue(originCodec.encode(trampolineRelayedHot).require).require == trampolineRelayedCold)
    assert(originCodec.decodeValue(originCodec.encode(trampolineRelayedCold).require).require == trampolineRelayedCold)
  }

  test("encode/decode map of origins") {
    val map = Map(
      1L -> Origin.LocalCold(UUID.randomUUID()),
      42L -> Origin.ChannelRelayedCold(randomBytes32(), 4324, 12000000 msat, 11000000 msat),
      43L -> Origin.TrampolineRelayedCold((randomBytes32(), 17L) :: (randomBytes32(), 21L) :: (randomBytes32(), 21L) :: Nil),
      130L -> Origin.ChannelRelayedCold(randomBytes32(), -45, 13000000 msat, 12000000 msat),
      140L -> Origin.TrampolineRelayedCold((randomBytes32(), 0L) :: Nil),
      1000L -> Origin.ChannelRelayedCold(randomBytes32(), 10, 14000000 msat, 13000000 msat),
      -32L -> Origin.ChannelRelayedCold(randomBytes32(), 54, 15000000 msat, 14000000 msat),
      -54L -> Origin.TrampolineRelayedCold((randomBytes32(), 1L) :: (randomBytes32(), 2L) :: Nil),
      -4L -> Origin.LocalCold(UUID.randomUUID()))
    assert(originsMapCodec.decodeValue(originsMapCodec.encode(map).require).require == map)
  }

  test("encode/decode map of spending txes") {
    val map = Map(
      OutPoint(randomBytes32(), 42) -> randomBytes32(),
      OutPoint(randomBytes32(), 14502) -> randomBytes32(),
      OutPoint(randomBytes32(), 0) -> randomBytes32(),
      OutPoint(randomBytes32(), 454513) -> randomBytes32()
    )
    assert(spentMapCodec.decodeValue(spentMapCodec.encode(map).require).require == map)
  }

}
