/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.wire.internal.channel.version2

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.DeterministicWallet.KeyPath
import fr.acinq.bitcoin.{DeterministicWallet, OutPoint, Satoshi, SatoshiLong, Script, Transaction}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.transactions._
import fr.acinq.eclair.wire.CommonCodecs.setCodec
import fr.acinq.eclair.wire.UpdateAddHtlc
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs._
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.internal.channel.version2.ChannelCodecs2._
import fr.acinq.eclair.wire.internal.channel.version2.ChannelCodecs2.Codecs._
import fr.acinq.eclair.{TestConstants, UInt64, randomBytes32, randomKey, _}
import org.json4s.jackson.Serialization
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._
import scodec.{Attempt, Codec, DecodeResult}

import java.util.UUID
import scala.concurrent.duration._
import scala.io.Source
import scala.util.Random

/**
 * Created by PM on 31/05/2016.
 */

class ChannelCodecs2Spec extends AnyFunSuite {

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

  test("encode/decode map of spending txs") {
    val map = Map(
      OutPoint(randomBytes32, 42) -> Transaction.read("020000000001018154ecccf11a5fb56c39654c4deb4d2296f83c69268280b94d021370c94e219701000000000000000001d0070000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0500483045022100d5275b3619953cb0c3b5aa577f04bc512380e60fa551762ce3d7a1bb7401cff9022037237ab0dac3fe100cde094e82e2bed9ba0ed1bb40154b48e56aa70f259e608b01483045022100c89172099507ff50f4c925e6c5150e871fb6e83dd73ff9fbb72f6ce829a9633f02203a63821d9162e99f9be712a68f9e589483994feae2661e4546cd5b6cec007be501008576a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c820120876475527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae67a914b43e1b38138a41b37f7cd9a1d274bc63e3a9b5d188ac6868f6010000"),
      OutPoint(randomBytes32, 14502) -> Transaction.read("02000000000101bef67e4e2fb9ddeeb3461973cd4c62abb35050b1add772995b820b584a488489000000000038b02b80094a010000000000002200202b1b5854183c12d3316565972c4668929d314d81c5dcdbb21cb45fe8a9a8114f4a01000000000000220020e9e86e4823faa62e222ebc858a226636856158f07e69898da3b0d1af0ddb3994e80300000000000022002010f88bf09e56f14fb4543fd26e47b0db50ea5de9cf3fc46434792471082621aed0070000000000002200203e68115ae0b15b8de75b6c6bc9af5ac9f01391544e0870dae443a1e8fe7837ead007000000000000220020fe0598d74fee2205cc3672e6e6647706b4f3099713b4661b62482c3addd04a5eb80b000000000000220020f96d0334feb64a4f40eb272031d07afcb038db56aa57446d60308c9f8ccadef9a00f000000000000220020ce6e751274836ff59622a0d1e07f8831d80bd6730bd48581398bfadd2bb8da9ac0c62d0000000000220020f3394e1e619b0eca1f91be2fb5ab4dfc59ba5b84ebe014ad1d43a564d012994a4c9e6a00000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0400483045022100cf8f902751006923e4062f5dbf55f8475bef08f4b5ac060d219bbff6c1a4431b02206006c515754ffc1f4f263004f61082e1fe4241449629da9096b0679e7e30972201473044022076a51aed1bd085487a7023f2ca8a87544a60a5b7277805b614b6ff7d36f1a44c02207ffac246b6572f3b4c9a7867ffa97c203500eebbf14659df78cfa0fadea22a6401475221023da092f6980e58d2c037173180e9a465476026ee50f96695963e8efe436f54eb21030e9f7b623d2ccc7c9bd44d66d5ce21ce504c0acf6385a132cec6d3c39fa711c152ae3e195220"),
      OutPoint(randomBytes32, 0) -> Transaction.read("02000000000101b8cefef62ea66f5178b9361b2371be0759cbc8c689bcfa7a8e6746d497ec221a040000000001000000010a060000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e050047304402203c3a699fb80a38112aafd73d6e3a9b7d40bc2c3ed8b7fbc182a20f43b215172202204e71821b984d1af52c4b8e2cd4c572578c12a965866130c2345f61e4c2d3fef48347304402205bcfa92f83c69289a412b0b6dd4f2a0fe0b0fc2d45bd74706e963257a09ea24902203783e47883e60b86240e877fcbf33d50b1742f65bc93b3162d1be26583b367ee012001010101010101010101010101010101010101010101010101010101010101018d76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a9144b6b2e5444c2639cc0fb7bcea5afba3f3cdce23988527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f501b175ac6851b2756800000000"),
      OutPoint(randomBytes32, 454513) -> Transaction.read("02000000000101ab84ff284f162cfbfef241f853b47d4368d171f9e2a1445160cd591c4c7d882b00000000000000000001e8030000000000002200204adb4e2f00643db396dd120d4e7dc17625f5f2c11a40d857accc862d6b7dd80e0500483045022100d9e29616b8f3959f1d3d7f7ce893ffedcdc407717d0de8e37d808c91d3a7c50d022078c3033f6d00095c8720a4bc943c1b45727818c082e4e3ddbc6d3116435b624b014730440220636de5682ef0c5b61f124ec74e8aa2461a69777521d6998295dcea36bc3338110220165285594b23c50b28b82df200234566628a27bcd17f7f14404bd865354eb3ce012000000000000000000000000000000000000000000000000000000000000000008a76a91414011f7254d96b819c76986c277d115efce6f7b58763ac67210394854aa6eab5b2a8122cc726e9dded053a2184d88256816826d6231c068d4a5b7c8201208763a914b8bcb07f6344b42ab04250c86a6e8b75d3fdbbc688527c21030d417a46946384f88d5f3337267c5e579765875dc4daca813e21734b140639e752ae677502f401b175ac686800000000")
    )
    assert(spentMapCodec.decodeValue(spentMapCodec.encode(map).require).require === map)
  }

  test("basic serialization test (NORMAL)") {
    val data = ChannelCodecsSpec.normal
    val bin = DATA_NORMAL_Codec.encode(data).require
    val check = DATA_NORMAL_Codec.decodeValue(bin).require
    assert(data.commitments.localCommit.spec === check.commitments.localCommit.spec)
    assert(data === check)
  }

}


