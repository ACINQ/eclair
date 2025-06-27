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

package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.crypto.Sphinx.FailurePacket
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.{BlindedRoute, BlindedRouteDetails}
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, EncodedNodeId, MilliSatoshiLong, ShortChannelId, UInt64, randomBytes, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

import scala.concurrent.duration.DurationInt
import scala.util.Success

/**
 * Created by fabrice on 10/01/17.
 */
class SphinxSpec extends AnyFunSuite {

  import Sphinx._
  import SphinxSpec._

  /*
  hop_shared_secret[0] = 0x53eb63ea8a3fec3b3cd433b85cd62a4b145e1dda09391b348c4e1cd36a03ea66
  hop_blinding_factor[0] = 0x2ec2e5da605776054187180343287683aa6a51b4b1c04d6dd49c45d8cffb3c36
  hop_ephemeral_pubkey[0] = 0x02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619

  hop_shared_secret[1] = 0xa6519e98832a0b179f62123b3567c106db99ee37bef036e783263602f3488fae
  hop_blinding_factor[1] = 0xbf66c28bc22e598cfd574a1931a2bafbca09163df2261e6d0056b2610dab938f
  hop_ephemeral_pubkey[1] = 0x028f9438bfbf7feac2e108d677e3a82da596be706cc1cf342b75c7b7e22bf4e6e2

  hop_shared_secret[2] = 0x3a6b412548762f0dbccce5c7ae7bb8147d1caf9b5471c34120b30bc9c04891cc
  hop_blinding_factor[2] = 0xa1f2dadd184eb1627049673f18c6325814384facdee5bfd935d9cb031a1698a5
  hop_ephemeral_pubkey[2] = 0x03bfd8225241ea71cd0843db7709f4c222f62ff2d4516fd38b39914ab6b83e0da0

  hop_shared_secret[3] = 0x21e13c2d7cfe7e18836df50872466117a295783ab8aab0e7ecc8c725503ad02d
  hop_blinding_factor[3] = 0x7cfe0b699f35525029ae0fa437c69d0f20f7ed4e3916133f9cacbb13c82ff262
  hop_ephemeral_pubkey[3] = 0x031dde6926381289671300239ea8e57ffaf9bebd05b9a5b95beaf07af05cd43595

  hop_shared_secret[4] = 0xb5756b9b542727dbafc6765a49488b023a725d631af688fc031217e90770c328
  hop_blinding_factor[4] = 0xc96e00dddaf57e7edcd4fb5954be5b65b09f17cb6d20651b4e90315be5779205
  hop_ephemeral_pubkey[4] = 0x03a214ebd875aab6ddfd77f22c5e7311d7f77f17a169e599f157bbcdae8bf071f4
  */
  test("generate ephemeral keys and secrets (reference test vector)") {
    val (ephkeys, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    assert(ephkeys(0) == PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))
    assert(sharedsecrets(0) == ByteVector32(hex"53eb63ea8a3fec3b3cd433b85cd62a4b145e1dda09391b348c4e1cd36a03ea66"))
    assert(ephkeys(1) == PublicKey(hex"028f9438bfbf7feac2e108d677e3a82da596be706cc1cf342b75c7b7e22bf4e6e2"))
    assert(sharedsecrets(1) == ByteVector32(hex"a6519e98832a0b179f62123b3567c106db99ee37bef036e783263602f3488fae"))
    assert(ephkeys(2) == PublicKey(hex"03bfd8225241ea71cd0843db7709f4c222f62ff2d4516fd38b39914ab6b83e0da0"))
    assert(sharedsecrets(2) == ByteVector32(hex"3a6b412548762f0dbccce5c7ae7bb8147d1caf9b5471c34120b30bc9c04891cc"))
    assert(ephkeys(3) == PublicKey(hex"031dde6926381289671300239ea8e57ffaf9bebd05b9a5b95beaf07af05cd43595"))
    assert(sharedsecrets(3) == ByteVector32(hex"21e13c2d7cfe7e18836df50872466117a295783ab8aab0e7ecc8c725503ad02d"))
    assert(ephkeys(4) == PublicKey(hex"03a214ebd875aab6ddfd77f22c5e7311d7f77f17a169e599f157bbcdae8bf071f4"))
    assert(sharedsecrets(4) == ByteVector32(hex"b5756b9b542727dbafc6765a49488b023a725d631af688fc031217e90770c328"))
  }

  test("generate filler") {
    val (_, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = generateFiller("rho", 1300, sharedsecrets.dropRight(1), referencePaymentPayloads.dropRight(1))
    assert(filler == hex"51c30cc8f20da0153ca3839b850bcbc8fefc7fd84802f3e78cb35a660e747b57aa5b0de555cbcf1e6f044a718cc34219b96597f3684eee7a0232e1754f638006cb15a14788217abdf1bdd67910dc1ca74a05dcce8b5ad841b0f939fca8935f6a3ff660e0efb409f1a24ce4aa16fc7dc074cd84422c10cc4dd4fc150dd6d1e4f50b36ce10fef29248dd0cec85c72eb3e4b2f4a7c03b5c9e0c9dd12976553ede3d0e295f842187b33ff743e6d685075e98e1bcab8a46bff0102ca8b2098ae91798d370b01ca7076d3d626952a03663fe8dc700d1358263b73ba30e36731a0b72092f8d5bc8cd346762e93b2bf203d00264e4bc136fc142de8f7b69154deb05854ea88e2d7506222c95ba1aab06")
  }

  test("peek at per-hop payload length") {
    val testCases = Map(
      34 -> hex"01",
      41 -> hex"08",
      285 -> hex"fc",
      288 -> hex"fd00fd",
      65570 -> hex"fdffff"
    )

    for ((expected, payload) <- testCases) {
      assert(peekPayloadLength(payload) == expected)
    }

    // The legacy fixed-size payload is not supported.
    assertThrows[IllegalArgumentException](peekPayloadLength(hex"00"))
  }

  test("is last packet") {
    val testCases = Seq(
      // Bolt 1.0 payloads use the next packet's hmac to signal termination.
      (true, DecryptedPacket(hex"00", OnionRoutingPacket(0, publicKeys.head.value, ByteVector.empty, ByteVector32.Zeroes), ByteVector32.One)),
      (false, DecryptedPacket(hex"00", OnionRoutingPacket(0, publicKeys.head.value, ByteVector.empty, ByteVector32.One), ByteVector32.One)),
      // Bolt 1.1 payloads currently also use the next packet's hmac to signal termination.
      (true, DecryptedPacket(hex"0101", OnionRoutingPacket(0, publicKeys.head.value, ByteVector.empty, ByteVector32.Zeroes), ByteVector32.One)),
      (false, DecryptedPacket(hex"0101", OnionRoutingPacket(0, publicKeys.head.value, ByteVector.empty, ByteVector32.One), ByteVector32.One)),
      (false, DecryptedPacket(hex"0100", OnionRoutingPacket(0, publicKeys.head.value, ByteVector.empty, ByteVector32.One), ByteVector32.One)),
      (false, DecryptedPacket(hex"0101", OnionRoutingPacket(0, publicKeys.head.value, ByteVector.empty, ByteVector32.One), ByteVector32.One))
    )

    for ((expected, packet) <- testCases) {
      assert(packet.isLastPacket == expected)
    }
  }

  test("bad onion") {
    val badOnions = Seq[protocol.OnionRoutingPacket](
      protocol.OnionRoutingPacket(1, ByteVector.fill(33)(0), ByteVector.fill(65)(1), ByteVector32.Zeroes),
      protocol.OnionRoutingPacket(0, ByteVector.fill(33)(0), ByteVector.fill(65)(1), ByteVector32.Zeroes),
      protocol.OnionRoutingPacket(0, publicKeys.head.value, ByteVector.fill(42)(1), ByteVector32(hex"2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a"))
    )

    val expected = Seq[BadOnion](
      InvalidOnionVersion(ByteVector32(hex"2f89b15c6cb0bb256d7a71b66de0d50cd3dd806f77d1cc1a3b0d86a0becd28ce")),
      InvalidOnionKey(ByteVector32(hex"d2602c65fc331d6ae728331ae50e602f35929312ca7a951dc5ce250031b6b999")),
      InvalidOnionHmac(ByteVector32(hex"3c01a86e6bc51b44a2718745fbbbc71a5c5dde5f46a489da17046c9d097bb303"))
    )

    for ((packet, expected) <- badOnions zip expected) {
      val Left(onionErr) = peel(privKeys.head, associatedData, packet)
      assert(onionErr == expected)
    }
  }

  test("create payment packet (reference test vector)") {
    val Success(PacketAndSecrets(onion, sharedSecrets)) = create(sessionKey, 1300, publicKeys, referencePaymentPayloads, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619f7f3416a5aa36dc7eeb3ec6d421e9615471ab870a33ac07fa5d5a51df0a8823aabe3fea3f90d387529d4f72837f9e687230371ccd8d263072206dbed0234f6505e21e282abd8c0e4f5b9ff8042800bbab065036eadd0149b37f27dde664725a49866e052e809d2b0198ab9610faa656bbf4ec516763a59f8f42c171b179166ba38958d4f51b39b3e98706e2d14a2dafd6a5df808093abfca5aeaaca16eded5db7d21fb0294dd1a163edf0fb445d5c8d7d688d6dd9c541762bf5a5123bf9939d957fe648416e88f1b0928bfa034982b22548e1a4d922690eecf546275afb233acf4323974680779f1a964cfe687456035cc0fba8a5428430b390f0057b6d1fe9a8875bfa89693eeb838ce59f09d207a503ee6f6299c92d6361bc335fcbf9b5cd44747aadce2ce6069cfdc3d671daef9f8ae590cf93d957c9e873e9a1bc62d9640dc8fc39c14902d49a1c80239b6c5b7fd91d05878cbf5ffc7db2569f47c43d6c0d27c438abff276e87364deb8858a37e5a62c446af95d8b786eaf0b5fcf78d98b41496794f8dcaac4eef34b2acfb94c7e8c32a9e9866a8fa0b6f2a06f00a1ccde569f97eec05c803ba7500acc96691d8898d73d8e6a47b8f43c3d5de74458d20eda61474c426359677001fbd75a74d7d5db6cb4feb83122f133206203e4e2d293f838bf8c8b3a29acb321315100b87e80e0edb272ee80fda944e3fb6084ed4d7f7c7d21c69d9da43d31a90b70693f9b0cc3eac74c11ab8ff655905688916cfa4ef0bd04135f2e50b7c689a21d04e8e981e74c6058188b9b1f9dfc3eec6838e9ffbcf22ce738d8a177c19318dffef090cee67e12de1a3e2a39f61247547ba5257489cbc11d7d91ed34617fcc42f7a9da2e3cf31a94a210a1018143173913c38f60e62b24bf0d7518f38b5bab3e6a1f8aeb35e31d6442c8abb5178efc892d2e787d79c6ad9e2fc271792983fa9955ac4d1d84a36c024071bc6e431b625519d556af38185601f70e29035ea6a09c8b676c9d88cf7e05e0f17098b584c4168735940263f940033a220f40be4c85344128b14beb9e75696db37014107801a59b13e89cd9d2258c169d523be6d31552c44c82ff4bb18ec9f099f3bf0e5b1bb2ba9a87d7e26f98d294927b600b5529c47e04d98956677cbcee8fa2b60f49776d8b8c367465b7c626da53700684fb6c918ead0eab8360e4f60edd25b4f43816a75ecf70f909301825b512469f8389d79402311d8aecb7b3ef8599e79485a4388d87744d899f7c47ee644361e17040a7958c8911be6f463ab6a9b2afacd688ec55ef517b38f1339efc54487232798bb25522ff4572ff68567fe830f92f7b8113efce3e98c3fffbaedce4fd8b50e41da97c0c08e423a72689cc68e68f752a5e3a9003e64e35c957ca2e1c48bb6f64b05f56b70b575ad2f278d57850a7ad568c24a4d32a3d74b29f03dc125488bc7c637da582357f40b0a52d16b3b40bb2c2315d03360bc24209e20972c200566bcf3bbe5c5b0aedd83132a8a4d5b4242ba370b6d67d9b67eb01052d132c7866b9cb502e44796d9d356e4e3cb47cc527322cd24976fe7c9257a2864151a38e568ef7a79f10d6ef27cc04ce382347a2488b1f404fdbf407fe1ca1c9d0d5649e34800e25e18951c98cae9f43555eef65fee1ea8f15828807366c3b612cd5753bf9fb8fced08855f742cddd6f765f74254f03186683d646e6f09ac2805586c7cf11998357cafc5df3f285329366f475130c928b2dceba4aa383758e7a9d20705c4bb9db619e2992f608a1ba65db254bb389468741d0502e2588aeb54390ac600c19af5c8e61383fc1bebe0029e4474051e4ef908828db9cca13277ef65db3fd47ccc2179126aaefb627719f421e20")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) = peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == referencePaymentPayloads)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_.secret))

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == ByteVector32(hex"901fb2bb905d1cfac67727f900daa2bb9da6801ac31ccce78663e5021e83983b"))
    assert(packets(1).hmac == ByteVector32(hex"2c4763d8ef214ced399c9e9ef52ca1b59abdfeb95f9035825fa3b750dfebdfd6"))
    assert(packets(2).hmac == ByteVector32(hex"e9a00fc5e742ca4b512e0a69f7eea60163b1f1aaaaf743aa8639766a6a2e6428"))
    assert(packets(3).hmac == ByteVector32(hex"c0a88e11af86d0ad229e02960e4ae3f7c9d708e0bbd06f49397a6fecb842c0f8"))
    assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("create payment packet with payloads filling the onion") {
    val Success(PacketAndSecrets(onion, sharedSecrets)) = create(sessionKey, 1300, publicKeys, paymentPayloadsFull, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866196ef84350c2a76fc232b5d46d421e9615471ab9e0bc887beff8c95fdb878f7b3a7141453e5f8d22b6101810ae541ce499a09b4a9d9f80d1845c8960c85fc6d1a87bf74b2ce49922898e9353fa268086c00ae8b7f718405b72ad3829dbb38c85e02a00427eb4bdbda8fcd42b44708a9efde49cf776b75ebb389bf84d0bfbf58590e510e034572a01e409c309396778760423a8d8754c52e9a01a8f0e271cba5068bab5ee5bd0b5cd98276b0e04d60ba6a0f6bafd75ff41903ab352a1f47586eae3c6c8e437d4308766f71052b46ba2efbd87c0a781e8b3f456300fc7efbefc78ab515338666aed2070e674143c30b520b9cc1782ba8b46454db0d4ce72589cfc2eafb2db452ec98573ad08496483741de5376bfc7357fc6ea629e31236ba6ba7703014959129141a1719788ec83884f2e9151a680e2a96d2bcc67a8a2935aa11acee1f9d04812045b4ae5491220313756b5b9a0a6f867f2a95be1fab14870f04eeab694d9594620632b14ec4b424b495914f3dc587f75cd4582c113bb61e34a0fa7f79f97463be4e3c6fb99516889ed020acee419bb173d38e5ba18a00065e11fd733cf9ae46505dbb4ef70ef2f502601f4f6ee1fdb9d17435e15080e962f24760843f35bac1ac079b694ff7c347c1ed6a87f02b0758fbf00917764716c68ed7d6e6c0e75ccdb6dc7fa59554784b3ad906127ea77a6cdd814662ee7d57a939e28d77b3da47efc072436a3fd7f9c40515af8c4903764301e62b57153a5ca03ff5bb49c7dc8d3b2858100fb4aa5df7a94a271b73a76129445a3ea180d84d19029c003c164db926ed6983e5219028721a294f145e3fcc20915b8a2147efc8b5d508339f64970feee3e2da9b9c9348c1a0a4df7527d0ae3f8ae507a5beb5c73c2016ecf387a3cd8b79df80a8e9412e707cb9c761a0809a84c606a779567f9f0edf685b38c98877e90d02aedd096ed841e50abf2114ce01efbff04788fb280f870eca20c7ec353d5c381903e7d08fc57695fd79c27d43e7bd603a876068d3f1c7f45af99003e5eec7e8d8c91e395320f1fc421ef3552ea033129429383304b760c8f93de342417c3223c2112a623c3514480cdfae8ec15a99abfca71b03a8396f19edc3d5000bcfb77b5544813476b1b521345f4da396db09e783870b97bc2034bd11611db30ed2514438b046f1eb7093eceddfb1e73880786cd7b540a3896eaadd0a0692e4b19439815b5f2ec855ec8ececce889442a64037e956452a3f7b86cb3780b3e316c8dde464bc74a60a85b613f849eb0b29daf81892877bd4be9ba5997fc35544d3c2a00e5e1f45dc925607d952c6a89721bd0b6f6aec03314d667166a5b8b18471403be7018b2479aaef6c7c6c554a50a98b717dff06d50be39fb36dc03e678e0a52fc615be46b223e3bee83fa0c7c47a1f29fb94f1e9eebf6c9ecf8fc79ae847df2effb60d07aba301fc536546ec4899eedb4fec9a9bed79e3a83c4b32757745778e977e485c67c0f12bbc82c0b3bb0f4df0bd13d046fed4446f54cd85bfce55ef781a80e5f63d289d08de001237928c2a4e0c8694d0c1e68cc23f2409f30009019085e831a928e7bc5b00a1f29d25482f7fd0b6dad30e6ef8edc68ddf7db404ea7d11540fc2cee74863d64af4c945457e04b7bea0a5fb8636edadb1e1d6f2630d61062b781c1821f46eddadf269ea1fada829547590081b16bc116e074cae0224a375f2d9ce16e836687c89cd285e3b40f1e59ce2caa3d1d8cf37ee4d5e3abe7ef0afd6ffeb4fd6905677b950894863c828ab8d93519566f69fa3c2129da763bf58d9c4d2837d4d9e13821258f7e7098b34f695a589bd9eb568ba51ee3014b2d3ba1d4cf9ebaed0231ed57ecea7bd918216")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) = peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == paymentPayloadsFull)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_.secret))

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == ByteVector32(hex"859cd694cf604442547246f4fae144f255e71e30cb366b9775f488cac713f0db"))
    assert(packets(1).hmac == ByteVector32(hex"259982a8af80bd3b8018443997fa5f74c48b488fff62e531be54b887d53fe0ac"))
    assert(packets(2).hmac == ByteVector32(hex"58110c95368305b73ae15d22b884fda0482c60993d3ba4e506e37ff5021efb13"))
    assert(packets(3).hmac == ByteVector32(hex"f45e7099e32b8973f54cbfd1f6c48e7e0b90718ad7b00a88e1e98cebeb6d3916"))
    assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("create payment packet with single payload filling the onion") {
    val Success(PacketAndSecrets(onion, _)) = create(sessionKey, 1300, publicKeys.take(1), oneHopPaymentPayload, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661918f5b235c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a7141453e5f8d22b6351810ae541ce499a09b4a9d9f80d1845c8960c85fc6d1a87bd24b2cc49922898e9353fa268086c00ae8b7f718405b72ad380cdbb38c85e02a00427eb4bdbda8fcd42b44708a9efde49cf753b75ebb389bf84d0bfbf58590e510e034572a01e409c30939e2e4a090ecc89c371820af54e06e4ad5495d4e58718385cca5414552e078fedf284fdc2cc5c070cba21a6a8d4b77525ddbc9a9fca9b2f29aac5783ee8badd709f81c73ff60556cf2ee623af073b5a84799acc1ca46b764f74b97068c7826cc0579794a540d7a55e49eac26a6930340132e946a983240b0cd1b732e305c1042f580c4b26f140fc1cab3ee6f620958e0979f85eddf586c410ce42e93a4d7c803ead45fc47cf4396d284632314d789e73cf3f534126c63fe244069d9e8a7c4f98e7e530fc588e648ef4e641364981b5377542d5e7a4aaab6d35f6df7d3a9d7ca715213599ee02c4dbea4dc78860febe1d29259c64b59b3333ffdaebbaff4e7b31c27a3791f6bf848a58df7c69bb2b1852d2ad357b9919ffdae570b27dc709fba087273d3a4de9e6a6be66db647fb6a8d1a503b3f481befb96745abf5cc4a6bba0f780d5c7759b9e303a2a6b17eb05b6e660f4c474959db183e1cae060e1639227ee0bca03978a238dc4352ed764da7d4f3ed5337f6d0376dff72615beeeeaaeef79ab93e4bcbf18cd8424eb2b6ad7f33d2b4ffd5ea08372e6ed1d984152df17e04c6f73540988d7dd979e020424a163c271151a255966be7edef42167b8facca633649739bab97572b485658cde409e5d4a0f653f1a5911141634e3d2b6079b19347df66f9820755fd517092dae62fb278b0bafcc7ad682f7921b3a455e0c6369988779e26f0458b31bffd7e4e5bfb31944e80f100b2553c3b616e75be18328dc430f6618d55cd7d0962bb916d26ed4b117c46fa29e0a112c02c36020b34a96762db628fa3490828ec2079962ad816ef20ea0bca78fb2b7f7aedd4c47e375e64294d151ff03083730336dea64934003a27730cc1c7dec5049ddba8188123dd191aa71390d43a49fb792a3da7082efa6cced73f00eccea18145fbc84925349f7b552314ab8ed4c491e392aed3b1f03eb79474c294b42e2eba1528da26450aa592cba7ea22e965c54dff0fd6fdfd6b52b9a0f5f762e27fb0e6c3cd326a1ca1c5973de9be881439f702830affeb0c034c18ac8d5c2f135c964bf69de50d6e99bde88e90321ba843d9753c8f83666105d25fafb1a11ea22d62ef6f1fc34ca4e60c35d69773a104d9a44728c08c20b6314327301a2c400a71e1424c12628cf9f4a67990ade8a2203b0edb96c6082d4673b7309cd52c4b32b02951db2f66c6c72bd6c7eac2b50b83830c75cdfc3d6e9c2b592c45ed5fa5f6ec0da85710b7e1562aea363e28665835791dc574d9a70b2e5e2b9973ab590d45b94d244fc4256926c5a55b01cd0aca21fe5f9c907691fb026d0c56788b03ca3f08db0abb9f901098dde2ec4003568bc3ca27475ff86a7cb0aabd9e5136c5de064d16774584b252024109bb02004dba1fabf9e8277de097a0ab0dc8f6e26fcd4a28fb9d27cd4a2f6b13e276ed259a39e1c7e60f3c32c5cc4c4f96bd981edcb5e2c76a517cdc285aa2ca571d1e3d463ecd7614ae227df17af7445305bd7c661cf7dba658b0adcf36b0084b74a5fa408e272f703770ac5351334709112c5d4e4fe987e0c27b670412696f52b33245c229775da550729938268ee4e7a282e4a60b25dbb28ea8877a5069f819e5d1d31d9140bbc627ff3df267d22e5f0e151db066577845d71b7cd4484089f3f59194963c8f02bd7a637")

    val Right(DecryptedPacket(payload, nextPacket, _)) = peel(privKeys(0), associatedData, onion)
    assert(payload == oneHopPaymentPayload.head)
    assert(nextPacket.hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("reject payment packet with fixed-size payloads (legacy reference test vector)") {
    val pubkey = hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"
    val payload = hex"e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71e87f9aab8f6378c6ff744c1f34b393ad28d065b535c1a8668d85d3b34a1b3befd10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf038659793a1f9e7abc789266cc861cabd95818c0fc8efbdfdc14e3f7c2bc7eb8d6a79ef75ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d014698cf05d742557763d9cb743faeae65dcc79dddaecf27fe5942be5380d15e9a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b040a2a2fba158a0d8085926dc2e44f0c88bf487da56e13ef2d5e676a8589881b4869ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565a9f99728426ce2380a9580e2a9442481ceae7679906c30b1a0e21a10f26150e0645ab6edfdab1ce8f8bea7b1dee511c5fd38ac0e702c1c15bb86b52bca1b71e15b96982d262a442024c33ceb7dd8f949063c2e5e613e873250e2f8708bd4e1924abd45f65c2fa5617bfb10ee9e4a42d6b5811acc8029c16274f937dac9e8817c7e579fdb767ffe277f26d413ced06b620ede8362081da21cf67c2ca9d6f15fe5bc05f82f5bb93f8916bad3d63338ca824f3bbc11b57ce94a5fa1bc239533679903d6fec92a8c792fd86e2960188c14f21e399cfd72a50c620e10aefc6249360b463df9a89bf6836f4f26359207b765578e5ed76ae9f31b1cc48324be576e3d8e44d217445dba466f9b6293fdf05448584eb64f61e02903f834518622b7d4732471c6e0e22e22d1f45e31f0509eab39cdea5980a492a1da2aaac55a98a01216cd4bfe7abaa682af0fbff2dfed030ba28f1285df750e4d3477190dd193f8643b61d8ac1c427d590badb1f61a05d480908fbdc7c6f0502dd0c4abb51d725e92f95da2a8facb79881a844e2026911adcc659d1fb20a2fce63787c8bb0d9f6789c4b231c76da81c3f0718eb7156565a081d2be6b4170c0e0bcebddd459f53db2590c974bca0d705c055dee8c629bf854a5d58edc85228499ec6dde80cce4c8910b81b1e9e8b0f43bd39c8d69c3a80672729b7dc952dd9448688b6bd06afc2d2819cda80b66c57b52ccf7ac1a86601410d18d0c732f69de792e0894a9541684ef174de766fd4ce55efea8f53812867be6a391ac865802dbc26d93959df327ec2667c7256aa5a1d3c45a69a6158f285d6c97c3b8eedb09527848500517995a9eae4cd911df531544c77f5a9a2f22313e3eb72ca7a07dba243476bc926992e0d1e58b4a2fc8c7b01e0cad726237933ea319bad7537d39f3ed635d1e6c1d29e97b3d2160a09e30ee2b65ac5bce00996a73c008bcf351cecb97b6833b6d121dcf4644260b2946ea204732ac9954b228f0beaa15071930fd9583dfc466d12b5f0eeeba6dcf23d5ce8ae62ee5796359d97a4a15955c778d868d0ef9991d9f2833b5bb66119c5f8b396fd108baed7906cbb3cc376d13551caed97fece6f42a4c908ee279f1127fda1dd3ee77d8de0a6f3c135fa3f1cffe38591b6738dc97b55f0acc52be9753ce53e64d7e497bb00ca6123758df3b68fad99e35c04389f7514a8e36039f541598a417275e77869989782325a15b5342ac5011ff07af698584b476b35d941a4981eac590a07a092bb50342da5d3341f901aa07964a8d02b623c7b106dd0ae50bfa007a22d46c8772fa55558176602946cb1d11ea5460db7586fb89c6d3bcd3ab6dd20df4a4db63d2e7d52380800ad812"
    val hmac = ByteVector32(hex"b8640887e027e946df96488b47fbc4a4fadaa8beda4abe446fafea5403fae2ef")
    val onion = OnionRoutingPacket(0, pubkey, payload, hmac)
    assert(peel(privKeys(0), associatedData, onion).isLeft)
  }

  test("create trampoline payment packet") {
    val Success(PacketAndSecrets(onion, sharedSecrets)) = create(sessionKey, 400, publicKeys, trampolinePaymentPayloads, associatedData)
    assert(serializeTrampolineOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619cff34152f3a36e52ca94e74927203a560392b9cc7ce3c45809c6be52166c24a595716880f95f178bf5b30c8ca262744d656e4012980ec037cc7b35c9f43eb265ecc97974a598ff045cee0ecc99e303f3706509aa43ba7c8a88cba175fccf9a8f5016ef06d3b935dbb15196d7ce16dc1a7157845566901d7b2197e52cab4ce48701a2aa5a5249b5aed3b5b40bfefa9c40ab669d55e8a6b1058f02941bf119a7a69129db7c5f7eafaa166578c720619561dd14b3277db557ec7dcdb793771aef0f2f667cfdbe7e5b6eb3bd48bb0fbb30acc853fcdd7218ed9b6189816a7f41c5e0695f0471425951787e2ea8c5391cda7b0fe30c80913ef585234ce442808f7ef9425bcd815c3ba9114a3d48735c6283a24743b94ce93cdc9a27670398d1ee83e68dbdd71c9f39f1d635804a45faa69cfbbcb20a6d82b677ddd5b6cede1f2518dbc20f044f591fb6ea042838e7ff8514af58fc7c201ddbc6ca7c01c480f511870823384ca70e54da6006a8cb254cd68f5ab289b89c6ba512c064515c356ede847c376176339f2c9921ecc29325e613593aa2ba4ad37970adee4b3ef8427cad4cf32a37ab1dbe0e539aef146ad675cdfd96")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, _, sharedSecret4)) = peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == trampolinePaymentPayloads)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_.secret))
  }

  test("create packet with invalid payload") {
    // In this test vector, the payload length (encoded as a varint in the first bytes) isn't equal to the actual
    // payload length.
    val incorrectVarint = Seq(
      hex"fd2a0101234567",
      hex"000000000000000000000000000000000000000000000000000000000000000000"
    )
    assert(create(sessionKey, 1300, publicKeys.take(2), incorrectVarint, associatedData).isFailure)
  }

  test("create packet with payloads too big") {
    val payloadsTooBig = Seq(
      hex"c0010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101",
      hex"c0020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202020202",
    )
    assert(create(sessionKey, 400, publicKeys.take(2), payloadsTooBig, associatedData).isFailure)
  }

  test("decrypt failure message") {
    val sharedSecrets = Seq(
      hex"0101010101010101010101010101010101010101010101010101010101010101",
      hex"0202020202020202020202020202020202020202020202020202020202020202",
      hex"0303030303030303030303030303030303030303030303030303030303030303"
    ).map(ByteVector32(_))

    val expected = DecryptedFailurePacket(publicKeys.head, InvalidOnionKey(ByteVector32.One))

    val packet1 = createAndWrap(sharedSecrets.head, expected.failureMessage)
    assert(packet1.length == 292)

    val Right(decrypted1) = FailurePacket.decrypt(packet1, None, Seq(0).map(i => SharedSecret(sharedSecrets(i), publicKeys(i)))).failure
    assert(decrypted1 == expected)

    val packet2 = FailurePacket.wrap(packet1, sharedSecrets(1))
    assert(packet2.length == 292)

    val Right(decrypted2) = FailurePacket.decrypt(packet2, None, Seq(1, 0).map(i => SharedSecret(sharedSecrets(i), publicKeys(i)))).failure
    assert(decrypted2 == expected)

    val packet3 = FailurePacket.wrap(packet2, sharedSecrets(2))
    assert(packet3.length == 292)

    val Right(decrypted3) = FailurePacket.decrypt(packet3, None, Seq(2, 1, 0).map(i => SharedSecret(sharedSecrets(i), publicKeys(i)))).failure
    assert(decrypted3 == expected)
  }

  test("decrypt invalid failure message") {
    val sharedSecrets = Seq(
      hex"0101010101010101010101010101010101010101010101010101010101010101",
      hex"0202020202020202020202020202020202020202020202020202020202020202",
      hex"0303030303030303030303030303030303030303030303030303030303030303"
    ).map(ByteVector32(_))

    val packet = FailurePacket.wrap(
      FailurePacket.wrap(
        createAndWrap(sharedSecrets.head, InvalidOnionPayload(UInt64(0), 0)),
        sharedSecrets(1)),
      sharedSecrets(2))

    assert(FailurePacket.decrypt(packet, None, Seq(0, 2, 1).map(i => SharedSecret(sharedSecrets(i), publicKeys(i)))).failure.isLeft)
  }

  test("last node replies with a short failure message (old reference test vector)") {
    for ((payloads, packetPayloadLength) <- Seq((referencePaymentPayloads, 1300), (paymentPayloadsFull, 1300), (trampolinePaymentPayloads, 400))) {
      // origin build the onion packet
      val Success(PacketAndSecrets(packet, sharedSecrets)) = create(sessionKey, packetPayloadLength, publicKeys, payloads, associatedData)
      // each node parses and forwards the packet
      val Right(DecryptedPacket(_, packet1, sharedSecret0)) = peel(privKeys(0), associatedData, packet)
      val Right(DecryptedPacket(_, packet2, sharedSecret1)) = peel(privKeys(1), associatedData, packet1)
      val Right(DecryptedPacket(_, packet3, sharedSecret2)) = peel(privKeys(2), associatedData, packet2)
      val Right(DecryptedPacket(_, packet4, sharedSecret3)) = peel(privKeys(3), associatedData, packet3)
      val Right(lastPacket@DecryptedPacket(_, _, sharedSecret4)) = peel(privKeys(4), associatedData, packet4)
      assert(lastPacket.isLastPacket)

      // node #4 want to reply with an error message
      val error = createAndWrap(sharedSecret4, TemporaryNodeFailure())
      assert(error == hex"a5e6bd0c74cb347f10cce367f949098f2457d14c046fd8a22cb96efb30b0fdcda8cb9168b50f2fd45edd73c1b0c8b33002df376801ff58aaa94000bf8a86f92620f343baef38a580102395ae3abf9128d1047a0736ff9b83d456740ebbb4aeb3aa9737f18fb4afb4aa074fb26c4d702f42968888550a3bded8c05247e045b866baef0499f079fdaeef6538f31d44deafffdfd3afa2fb4ca9082b8f1c465371a9894dd8c243fb4847e004f5256b3e90e2edde4c9fb3082ddfe4d1e734cacd96ef0706bf63c9984e22dc98851bcccd1c3494351feb458c9c6af41c0044bea3c47552b1d992ae542b17a2d0bba1a096c78d169034ecb55b6e3a7263c26017f033031228833c1daefc0dedb8cf7c3e37c9c37ebfe42f3225c326e8bcfd338804c145b16e34e4")
      val error1 = FailurePacket.wrap(error, sharedSecret3)
      assert(error1 == hex"c49a1ce81680f78f5f2000cda36268de34a3f0a0662f55b4e837c83a8773c22aa081bab1616a0011585323930fa5b9fae0c85770a2279ff59ec427ad1bbff9001c0cd1497004bd2a0f68b50704cf6d6a4bf3c8b6a0833399a24b3456961ba00736785112594f65b6b2d44d9f5ea4e49b5e1ec2af978cbe31c67114440ac51a62081df0ed46d4a3df295da0b0fe25c0115019f03f15ec86fabb4c852f83449e812f141a9395b3f70b766ebbd4ec2fae2b6955bd8f32684c15abfe8fd3a6261e52650e8807a92158d9f1463261a925e4bfba44bd20b166d532f0017185c3a6ac7957adefe45559e3072c8dc35abeba835a8cb01a71a15c736911126f27d46a36168ca5ef7dccd4e2886212602b181463e0dd30185c96348f9743a02aca8ec27c0b90dca270")
      val error2 = FailurePacket.wrap(error1, sharedSecret2)
      assert(error2 == hex"a5d3e8634cfe78b2307d87c6d90be6fe7855b4f2cc9b1dfb19e92e4b79103f61ff9ac25f412ddfb7466e74f81b3e545563cdd8f5524dae873de61d7bdfccd496af2584930d2b566b4f8d3881f8c043df92224f38cf094cfc09d92655989531524593ec6d6caec1863bdfaa79229b5020acc034cd6deeea1021c50586947b9b8e6faa83b81fbfa6133c0af5d6b07c017f7158fa94f0d206baf12dda6b68f785b773b360fd0497e16cc402d779c8d48d0fa6315536ef0660f3f4e1865f5b38ea49c7da4fd959de4e83ff3ab686f059a45c65ba2af4a6a79166aa0f496bf04d06987b6d2ea205bdb0d347718b9aeff5b61dfff344993a275b79717cd815b6ad4c0beb568c4ac9c36ff1c315ec1119a1993c4b61e6eaa0375e0aaf738ac691abd3263bf937e3")
      val error3 = FailurePacket.wrap(error2, sharedSecret1)
      assert(error3 == hex"aac3200c4968f56b21f53e5e374e3a2383ad2b1b6501bbcc45abc31e59b26881b7dfadbb56ec8dae8857add94e6702fb4c3a4de22e2e669e1ed926b04447fc73034bb730f4932acd62727b75348a648a1128744657ca6a4e713b9b646c3ca66cac02cdab44dd3439890ef3aaf61708714f7375349b8da541b2548d452d84de7084bb95b3ac2345201d624d31f4d52078aa0fa05a88b4e20202bd2b86ac5b52919ea305a8949de95e935eed0319cf3cf19ebea61d76ba92532497fcdc9411d06bcd4275094d0a4a3c5d3a945e43305a5a9256e333e1f64dbca5fcd4e03a39b9012d197506e06f29339dfee3331995b21615337ae060233d39befea925cc262873e0530408e6990f1cbd233a150ef7b004ff6166c70c68d9f8c853c1abca640b8660db2921")
      val error4 = FailurePacket.wrap(error3, sharedSecret0)
      assert(error4 == hex"9c5add3963fc7f6ed7f148623c84134b5647e1306419dbe2174e523fa9e2fbed3a06a19f899145610741c83ad40b7712aefaddec8c6baf7325d92ea4ca4d1df8bce517f7e54554608bf2bd8071a4f52a7a2f7ffbb1413edad81eeea5785aa9d990f2865dc23b4bc3c301a94eec4eabebca66be5cf638f693ec256aec514620cc28ee4a94bd9565bc4d4962b9d3641d4278fb319ed2b84de5b665f307a2db0f7fbb757366067d88c50f7e829138fde4f78d39b5b5802f1b92a8a820865af5cc79f9f30bc3f461c66af95d13e5e1f0381c184572a91dee1c849048a647a1158cf884064deddbf1b0b88dfe2f791428d0ba0f6fb2f04e14081f69165ae66d9297c118f0907705c9c4954a199bae0bb96fad763d690e7daa6cfda59ba7f2c8d11448b604d12d")

      // origin parses error packet and can see that it comes from node #4
      val Right(DecryptedFailurePacket(pubkey, failure)) = FailurePacket.decrypt(error4, None, sharedSecrets).failure
      assert(pubkey == publicKeys(4))
      assert(failure == TemporaryNodeFailure())
    }
  }

  test("last node replies with a long failure message (reference test vector)") {
    for ((payloads, packetPayloadLength) <- Seq((referencePaymentPayloads, 1300), (paymentPayloadsFull, 1300))) {
      // origin build the onion packet
      val Success(PacketAndSecrets(packet, sharedSecrets)) = create(sessionKey, packetPayloadLength, publicKeys, payloads, associatedData)
      // each node parses and forwards the packet
      val Right(DecryptedPacket(_, packet1, sharedSecret0)) = peel(privKeys(0), associatedData, packet)
      val Right(DecryptedPacket(_, packet2, sharedSecret1)) = peel(privKeys(1), associatedData, packet1)
      val Right(DecryptedPacket(_, packet3, sharedSecret2)) = peel(privKeys(2), associatedData, packet2)
      val Right(DecryptedPacket(_, packet4, sharedSecret3)) = peel(privKeys(3), associatedData, packet3)
      val Right(lastPacket@DecryptedPacket(_, _, sharedSecret4)) = peel(privKeys(4), associatedData, packet4)
      assert(lastPacket.isLastPacket)

      // node #4 want to reply with an error message
      val failure = IncorrectOrUnknownPaymentDetails(100 msat, BlockHeight(800000), TlvStream(Set.empty[FailureMessageTlv], Set(GenericTlv(UInt64(34001), ByteVector.fill(300)(128)))))
      val failurePacket = createCustomLengthFailurePacket(failure, sharedSecret4, 1024)
      val error = Sphinx.FailurePacket.wrap(failurePacket, sharedSecret4)
      assert(error == hex"146e94a9086dbbed6a0ab6932d00c118a7195dbf69b7d7a12b0e6956fc54b5e0a989f165b5f12fd45edd73a5b0c48630ff5be69500d3d82a29c0803f0a0679a6a073c33a6fb8250090a3152eba3f11a85184fa87b67f1b0354d6f48e3b342e332a17b7710f342f342a87cf32eccdf0afc2160808d58abb5e5840d2c760c538e63a6f841970f97d2e6fe5b8739dc45e2f7f5f532f227bcc2988ab0f9cc6d3f12909cd5842c37bc8c7608475a5ebbe10626d5ecc1f3388ad5f645167b44a4d166f87863fe34918cea25c18059b4c4d9cb414b59f6bc50c1cea749c80c43e2344f5d23159122ed4ab9722503b212016470d9610b46c35dbeebaf2e342e09770b38392a803bc9d2e7c8d6d384ffcbeb74943fe3f64afb2a543a6683c7db3088441c531eeb4647518cb41992f8954f1269fb969630944928c2d2b45593731b5da0c4e70d04a0a57afe4af42e99912fbb4f8883a5ecb9cb29b883cb6bfa0f4db2279ff8c6d2b56a232f55ba28fe7dfa70a9ab0433a085388f25cce8d53de6a2fbd7546377d6ede9027ad173ba1f95767461a3689ef405ab608a21086165c64b02c1782b04a6dba2361a7784603069124e12f2f6dcb1ec7612a4fbf94c0e14631a2bef6190c3d5f35e0c4b32aa85201f449d830fd8f782ec758b0910428e3ec3ca1dba3b6c7d89f69e1ee1b9df3dfbbf6d361e1463886b38d52e8f43b73a3bd48c6f36f5897f514b93364a31d49d1d506340b1315883d425cb36f4ea553430d538fd6f3596d4afc518db2f317dd051abc0d4bfb0a7870c3db70f19fe78d6604bbf088fcb4613f54e67b038277fedcd9680eb97bdffc3be1ab2cbcbafd625b8a7ac34d8c190f98d3064ecd3b95b8895157c6a37f31ef4de094b2cb9dbf8ff1f419ba0ecacb1bb13df0253b826bec2ccca1e745dd3b3e7cc6277ce284d649e7b8285727735ff4ef6cca6c18e2714f4e2a1ac67b25213d3bb49763b3b94e7ebf72507b71fb2fe0329666477ee7cb7ebd6b88ad5add8b217188b1ca0fa13de1ec09cc674346875105be6e0e0d6c8928eb0df23c39a639e04e4aedf535c4e093f08b2c905a14f25c0c0fe47a5a1535ab9eae0d9d67bdd79de13a08d59ee05385c7ea4af1ad3248e61dd22f8990e9e99897d653dd7b1b1433a6d464ea9f74e377f2d8ce99ba7dbc753297644234d25ecb5bd528e2e2082824681299ac30c05354baaa9c3967d86d7c07736f87fc0f63e5036d47235d7ae12178ced3ae36ee5919c093a02579e4fc9edad2c446c656c790704bfc8e2c491a42500aa1d75c8d4921ce29b753f883e17c79b09ea324f1f32ddf1f3284cd70e847b09d90f6718c42e5c94484cc9cbb0df659d255630a3f5a27e7d5dd14fa6b974d1719aa98f01a20fb4b7b1c77b42d57fab3c724339d459ee4a1c6b5d3bd4e08624c786a257872acc9ad3ff62222f2265a658d9f2a007229a5293b67ec91c84c4b4407c228434bad8a815ca9b256c776bd2c9f")
      val attribution = Attribution.create(None, Some(failurePacket), 100 millisecond, sharedSecret4)
      assert(attribution == hex"d77d0711b5f71d1d1be56bd88b3bb7ebc1792bb739ea7ebc1bc3b031b8bc2df3a50e25aeb99f47d7f7ab39e24187d3f4df9c4333463b053832ee9ac07274a5261b8b2a01fc09ce9ea7cd04d7b585dfb8cf5958e3f3f2a4365d1ec0df1d83c6a6221b5b7d1ff30156a2289a1d3ee559e7c7256bda444bb8e046f860e00b3a59a85e1e1a43de215fd5e6bf646a5deab97b1912c934e31b1cfd344764d6ca7e14ea7b3f2a951aba907c964c0f5d19a44e6d1d7279637321fa598adde927b3087d238f8b426ecde500d318617cdb7a56e6ce3520fc95be41a549973764e4dc483853ecc313947709f1b5199cb077d46e701fa633e11d3e13b03e9212c115ca6fa004b2f3dd912814693b705a561a06da54cdf603677a3abecdc22c7358c2de3cef771b366a568150aeecc86ad1990bb0f4e2865933b03ea0df87901bff467908273dc6cea31cbab0e2b8d398d10b001058c259ed221b7b55762f4c7e49c8c11a45a107b7a2c605c26dc5b0b10d719b1c844670102b2b6a36c43fe4753a78a483fc39166ae28420f112d50c10ee64ca69569a2f690712905236b7c2cb7ac8954f02922d2d918c56d42649261593c47b14b324a65038c3c5be8d3c403ce0c8f19299b1664bf077d7cf1636c4fb9685a8e58b7029fd0939fa07925a60bed339b23f973293598f595e75c8f9d455d7cebe4b5e23357c8bd47d66d6628b39427e37e0aecbabf46c11be6771f7136e108a143ae9bafba0fc47a51b6c7deef4cba54bae906398ee3162a41f2191ca386b628bde7e1dd63d1611aa01a95c456df337c763cb8c3a81a6013aa633739d8cd554c688102211725e6adad165adc1bcd429d020c51b4b25d2117e8bb27eb0cc7020f9070d4ad19ac31a76ebdf5f9246646aeadbfb9a3f1d75bd8237961e786302516a1a781780e8b73f58dc06f307e58bd0eb1d8f5c9111f01312974c1dc777a6a2d3834d8a2a40014e9818d0685cb3919f6b3b788ddc640b0ff9b1854d7098c7dd6f35196e902b26709640bc87935a3914869a807e8339281e9cedaaca99474c3e7bdd35050bb998ab4546f9900904e0e39135e861ff7862049269701081ebce32e4cca992c6967ff0fd239e38233eaf614af31e186635e9439ec5884d798f9174da6ff569d68ed5c092b78bd3f880f5e88a7a8ab36789e1b57b035fb6c32a6358f51f83e4e5f46220bcad072943df8bd9541a61b7dae8f30fa3dd5fb39b1fd9a0b8e802552b78d4ec306ecee15bfe6da14b29ba6d19ce5be4dd478bca74a52429cd5309d404655c3dec85c252")
      val error1 = FailurePacket.wrap(error, sharedSecret3)
      assert(error1 == hex"7512354d6a26781d25e65539772ba049b7ed7c530bf75ab7ef80cf974b978a07a1c3dabc61940011585323f70fa98cfa1d4c868da30b1f751e44a72d9b3f79809c8c51c9f0843daa8fe83587844fedeacb7348362003b31922cbb4d6169b2087b6f8d192d9cfe5363254cd1fde24641bde9e422f170c3eb146f194c48a459ae2889d706dc654235fa9dd20307ea54091d09970bf956c067a3bcc05af03c41e01af949a131533778bf6ee3b546caf2eabe9d53d0fb2e8cc952b7e0f5326a69ed2e58e088729a1d85971c6b2e129a5643f3ac43da031e655b27081f10543262cf9d72d6f64d5d96387ac0d43da3e3a03da0c309af121dcf3e99192efa754eab6960c256ffd4c546208e292e0ab9894e3605db098dc16b40f17c320aa4a0e42fc8b105c22f08c9bc6537182c24e32062c6cd6d7ec7062a0c2c2ecdae1588c82185cdc61d874ee916a7873ac54cddf929354f307e870011704a0e9fbc5c7802d6140134028aca0e78a7e2f3d9e5c7e49e20c3a56b624bfea51196ec9e88e4e56be38ff56031369f45f1e03be826d44a182f270c153ee0d9f8cf9f1f4132f33974e37c7887d5b857365c873cb218cbf20d4be3abdb2a2011b14add0a5672e01e5845421cf6dd6faca1f2f443757aae575c53ab797c2227ecdab03882bbbf4599318cefafa72fa0c9a0f5a51d13c9d0e5d25bfcfb0154ed25895260a9df8743ac188714a3f16960e6e2ff663c08bffda41743d50960ea2f28cda0bc3bd4a180e297b5b41c700b674cb31d99c7f2a1445e121e772984abff2bbe3f42d757ceeda3d03fb1ffe710aecabda21d738b1f4620e757e57b123dbc3c4aa5d9617dfa72f4a12d788ca596af14bea583f502f16fdc13a5e739afb0715424af2767049f6b9aa107f69c5da0e85f6d8c5e46507e14616d5d0b797c3dea8b74a1b12d4e47ba7f57f09d515f6c7314543f78b5e85329d50c5f96ee2f55bbe0df742b4003b24ccbd4598a64413ee4807dc7f2a9c0b92424e4ae1b418a3cdf02ea4da5c3b12139348aa7022cc8272a3a1714ee3e4ae111cffd1bdfd62c503c80bdf27b2feaea0d5ab8fe00f9cec66e570b00fd24b4a2ed9a5f6384f148a4d6325110a41ca5659ebc5b98721d298a52819b6fb150f273383f1c5754d320be428941922da790e17f482989c365c078f7f3ae100965e1b38c052041165295157e1a7c5b7a57671b842d4d85a7d971323ad1f45e17a16c4656d889fc75c12fc3d8033f598306196e29571e414281c5da19c12605f48347ad5b4648e371757cbe1c40adb93052af1d6110cfbf611af5c8fc682b7e2ade3bfca8b5c7717d19fc9f97964ba6025aebbc91a6671e259949dcf40984342118de1f6b514a7786bd4f6598ffbe1604cef476b2a4cb1343db608aca09d1d38fc23e98ee9c65e7f6023a8d1e61fd4f34f753454bd8e858c8ad6be6403edc599c220e03ca917db765980ac781e758179cd93983e9c1e769e4241d47c")
      val attribution1 = Attribution.create(Some(attribution), Some(error), 200 milliseconds, sharedSecret3)
      assert(attribution1 == hex"1571e10db7f8aa9f8e7e99caaf9c892e106c817df1d8e3b7b0e39d1c48f631e473e17e205489dd7b3c634cac3be0825cbf01418cd46e83c24b8d9c207742db9a0f0e5bcd888086498159f08080ba7bf36dee297079eb841391ccd3096da76461e314863b6412efe0ffe228d51c6097db10d3edb2e50ea679820613bfe9db11ba02920ab4c1f2a79890d997f1fc022f3ab78f0029cc6de0c90be74d55f4a99bf77a50e20f8d076fe61776190a61d2f41c408871c0279309cba3b60fcdc7efc4a0e90b47cb4a418fc78f362ecc7f15ebbce9f854c09c7be300ebc1a40a69d4c7cb7a19779b6905e82bec221a709c1dab8cbdcde7b527aca3f54bde651aa9f3f2178829cee3f1c0b9292758a40cc63bd998fcd0d3ed4bdcaf1023267b8f8e44130a63ad15f76145936552381eabb6d684c0a3af6ba8efcf207cebaea5b7acdbb63f8e7221102409d10c23f0514dc9f4d0efb2264161a193a999a23e992632710580a0d320f676d367b9190721194514457761af05207cdab2b6328b1b3767eacb36a7ef4f7bd2e16762d13df188e0898b7410f62459458712a44bf594ae662fd89eb300abb6952ff8ad40164f2bcd7f86db5c7650b654b79046de55d51aa8061ce35f867a3e8f5bf98ad920be827101c64fb871d86e53a4b3c0455bfac5784168218aa72cbee86d9c750a9fa63c363a8b43d7bf4b2762516706a306f0aa3be1ec788b5e13f8b24837e53ac414f211e11c7a093cd9653dfa5fba4e377c79adfa5e841e2ddb6afc054fc715c05ddc6c8fc3e1ee3406e1ffceb2df77dc2f02652614d1bfcfaddebaa53ba919c7051034e2c7b7cfaabdf89f26e7f8e3f956d205dfab747ad0cb505b85b54a68439621b25832cbc2898919d0cd7c0a64cfd235388982dd4dd68240cb668f57e1d2619a656ed326f8c92357ee0d9acead3c20008bc5f04ca8059b55d77861c6d04dfc57cfba57315075acbe1451c96cf28e1e328e142890248d18f53b5d3513ce574dea7156cf596fdb3d909095ec287651f9cf1bcdc791c5938a5dd9b47e84c004d24ab3ae74492c7e8dcc1da15f65324be2672947ec82074cac8ce2b925bc555facbbf1b55d63ea6fbea6a785c97d4caf2e1dad9551b7f66c31caae5ebc7c0047e892f201308fcf452c588be0e63d89152113d87bf0dbd01603b4cdc7f0b724b0714a9851887a01f709408882e18230fe810b9fafa58a666654576d8eba3005f07221f55a6193815a672e5db56204053bc4286fa3db38250396309fd28011b5708a26a2d76c4a333b69b6bfd272fb")
      val error2 = FailurePacket.wrap(error1, sharedSecret2)
      assert(error2 == hex"145bc1c63058f7204abbd2320d422e69fb1b3801a14312f81e5e29e6b5f4774cfed8a25241d3dfb7466e749c1b3261559e49090853612e07bd669dfb5f4c54162fa504138dabd6ebcf0db8017840c35f12a2cfb84f89cc7c8959a6d51815b1d2c5136cedec2e4106bb5f2af9a21bd0a02c40b44ded6e6a90a145850614fb1b0eef2a03389f3f2693bc8a755630fc81fff1d87a147052863a71ad5aebe8770537f333e07d841761ec448257f948540d8f26b1d5b66f86e073746106dfdbb86ac9475acf59d95ece037fba360670d924dce53aaa74262711e62a8fc9eb70cd8618fbedae22853d3053c7f10b1a6f75369d7f73c419baa7dbf9f1fc5895362dcc8b6bd60cca4943ef7143956c91992119bccbe1666a20b7de8a2ff30a46112b53a6bb79b763903ecbd1f1f74952fb1d8eb0950c504df31fe702679c23b463f82a921a2c931500ab08e686cffb2d87258d254fb17843959cccd265a57ba26c740f0f231bb76df932b50c12c10be90174b37d454a3f8b284c849e86578a6182c4a7b2e47dd57d44730a1be9fec4ad07287a397e28dce4fda57e9cdfdb2eb5afdf0d38ef19d982341d18d07a556bb16c1416f480a396f278373b8fd9897023a4ac506e65cf4c306377730f9c8ca63cf47565240b59c4861e52f1dab84d938e96fb31820064d534aca05fd3d2600834fe4caea98f2a748eb8f200af77bd9fbf46141952b9ddda66ef0ebea17ea1e7bb5bce65b6e71554c56dd0d4e14f4cf74c77a150776bf31e7419756c71e7421dc22efe9cf01de9e19fc8808d5b525431b944400db121a77994518d6025711cb25a18774068bba7faaa16d8f65c91bec8768848333156dcb4a08dfbbd9fef392da3e4de13d4d74e83a7d6e46cfe530ee7a6f711e2caf8ad5461ba8177b2ef0a518baf9058ff9156e6aa7b08d938bd8d1485a787809d7b4c8aed97be880708470cd2b2cdf8e2f13428cc4b04ef1f2acbc9562f3693b948d0aa94b0e6113cafa684f8e4a67dc431dfb835726874bef1de36f273f52ee694ec46b0700f77f8538067642a552968e866a72a3f2031ad116663ac17b172b446c5bc705b84777363a9a3fdc6443c07b2f4ef58858122168d4ebbaee920cefc312e1cea870ed6e15eec046ab2073bbf08b0a3366f55cfc6ad4681a12ab0946534e7b6f90ea8992d530ec3daa6b523b3cf03101c60cadd914f30dec932c1ef4341b5a8efac3c921e203574cfe0f1f83433fddb8ccfd273f7c3cab7bc27efe3bb61fdccd5146f1185364b9b621e7fb2b74b51f5ee6be72ab6ff46a6359dc2c855e61469724c1dbeb273df9d2e1c1fb74891239c0019dc12d5c7535f7238f963b761d7102b585372cf021b64c4fc85bfb3161e59d2e298bba44cfd34d6859d9dba9dc6271e5047d525468c814f2ae438474b0a977273036da1a2292f88fcfb89574a6bdca1185b40f8aa54026d5926725f99ef028da1be892e3586361efe15f4a148ff1bc9")
      val attribution2 = Attribution.create(Some(attribution1), Some(error1), 300 milliseconds, sharedSecret2)
      assert(attribution2 == hex"34e34397b8621ec2f2b54dbe6c14073e267324cd60b152bce76aec8729a6ddefb61bc263be4b57bd592aae604a32bea69afe6ef4a6b573c26b17d69381ec1fc9b5aa769d148f2f1f8b5377a73840bb6dc641f68e356323d766fff0aaca5039fe7fc27038195844951a97d5a5b26698a4ca1e9cd4bca1fcca0aac5fee91b18977d2ad0e399ba159733fc98f6e96898ebc39bf0028c9c81619233bab6fad0328aa183a635fac20437fa6e00e899b2527c3697a8ab7342e42d55a679b176ab76671fcd480a9894cb897fa6af0a45b917a162bed6c491972403185df7235502f7ada65769d1bfb12d29f10e25b0d3cc08bbf6de8481ac5c04df32b4533b4f764c2aefb7333202645a629fb16e4a208e9045dc36830759c852b31dd613d8b2b10bbead1ed4eb60c85e8a4517deba5ab53e39867c83c26802beee2ee545bdd713208751added5fc0eb2bc89a5aa2decb18ee37dac39f22a33b60cc1a369d24de9f3d2d8b63c039e248806de4e36a47c7a0aed30edd30c3d62debdf1ad82bf7aedd7edec413850d91c261e12beec7ad1586a9ad25b2db62c58ca17119d61dcc4f3e5c4520c42a8e384a45d8659b338b3a08f9e123a1d3781f5fc97564ccff2c1d97f06fa0150cfa1e20eacabefb0c339ec109336d207cc63d9170752fc58314c43e6d4a528fd0975afa85f3aa186ff1b6b8cb12c97ed4ace295b0ef5f075f0217665b8bb180246b87982d10f43c9866b22878106f5214e99188781180478b07764a5e12876ddcb709e0a0a8dd42cf004c695c6fc1669a6fd0e4a1ca54b024d0d80eac492a9e5036501f36fb25b72a054189294955830e43c18e55668337c8c6733abb09fc2d4ade18d5a853a2b82f7b4d77151a64985004f1d9218f2945b63c56fdebd1e96a2a7e49fa70acb4c39873947b83c191c10e9a8f40f60f3ad5a2be47145c22ea59ed3f5f4e61cb069e875fb67142d281d784bf925cc286eacc2c43e94d08da4924b83e58dbf2e43fa625bdd620eba6d9ce960ff17d14ed1f2dbee7d08eceb540fdc75ff06dabc767267658fad8ce99e2a3236e46d2deedcb51c3c6f81589357edebac9772a70b3d910d83cd1b9ce6534a011e9fa557b891a23b5d88afcc0d9856c6dabeab25eea55e9a248182229e4927f268fe5431672fcce52f434ca3d27d1a2136bae5770bb36920df12fbc01d0e8165610efa04794f414c1417f1d4059435c5385bfe2de83ce0e238d6fd2dbd3c0487c69843298577bfa480fe2a16ab2a0e4bc712cd8b5a14871cda61c993b6835303d9043d7689a")
      val error3 = FailurePacket.wrap(error2, sharedSecret1)
      assert(error3 == hex"1b4b09a935ce7af95b336baae307f2b400e3a7e808d9b4cf421cc4b3955620acb69dcdb656128dae8857adbd4e6b37fbb1be9c1f2f02e61e9e59a630c4c77cf383cb37b07413aa4de2f2fbf5b40ae40a91a8f4c6d74aeacef1bb1be4ecbc26ec2c824d2bc45db4b9098e732a769788f1cff3f5b41b0d25c132d40dc5ad045ef0043b15332ca3c5a09de2cdb17455a0f82a8f20da08346282823dab062cdbd2111e238528141d69de13de6d83994fbc711e3e269df63a12d3a4177c5c149150eb4dc2f589cd8acabcddba14dec3b0dada12d663b36176cd3c257c5460bab93981ad99f58660efa9b31d7e63b39915329695b3fa60e0a3bdb93e7e29a54ca6a8f360d3848866198f9c3da3ba958e7730847fe1e6478ce8597848d3412b4ae48b06e05ba9a104e648f6eaf183226b5f63ed2e68f77f7e38711b393766a6fab7921b03eba82b5d7cb78e34dc961948d6161eadd7cf5d95d9c56df2ff5faa6ccf85eacdc9ff2fc3abafe41c365a5bd14fd486d6b5e2f24199319e7813e02e798877ffe31a70ae2398d9e31b9e3727e6c1a3c0d995c67d37bb6e72e9660aaaa9232670f382add2edd468927e3303b6142672546997fe105583e7c5a3c4c2b599731308b5416e6c9a3f3ba55b181ad0439d3535356108b059f2cb8742eed7a58d4eba9fe79eaa77c34b12aff1abdaea93197aabd0e74cb271269ca464b3b06aef1d6573df5e1224179616036b368677f26479376681b772d3760e871d99efd34cca5cd6beca95190d967da820b21e5bec60082ea46d776b0517488c84f26d12873912d1f68fafd67bcf4c298e43cfa754959780682a2db0f75f95f0598c0d04fd014c50e4beb86a9e37d95f2bba7e5065ae052dc306555bca203d104c44a538b438c9762de299e1c4ad30d5b4a6460a76484661fc907682af202cd69b9a4473813b2fdc1142f1403a49b7e69a650b7cde9ff133997dcc6d43f049ecac5fce097a21e2bce49c810346426585e3a5a18569b4cddd5ff6bdec66d0b69fcbc5ab3b137b34cc8aefb8b850a764df0e685c81c326611d901c392a519866e132bbb73234f6a358ba284fbafb21aa3605cacbaf9d0c901390a98b7a7dac9d4f0b405f7291c88b2ff45874241c90ac6c5fc895a440453c344d3a365cb929f9c91b9e39cb98b142444aae03a6ae8284c77eb04b0a163813d4c21883df3c0f398f47bf127b5525f222107a2d8fe55289f0cfd3f4bbad6c5387b0594ef8a966afc9e804ccaf75fe39f35c6446f7ee076d433f2f8a44dba1515acc78e589fa8c71b0a006fe14feebd51d0e0aa4e51110d16759eee86192eee90b34432130f387e0ccd2ee71023f1f641cddb571c690107e08f592039fe36d81336a421e89378f351e633932a2f5f697d25b620ffb8e84bb6478e9bd229bf3b164b48d754ae97bd23f319e3c56b3bcdaaeb3bd7fc02ec02066b324cb72a09b6b43dec1097f49d69d3c138ce6f1a6402898baf7568c")
      val attribution3 = Attribution.create(Some(attribution2), Some(error2), 400 milliseconds, sharedSecret1)
      assert(attribution3 == hex"74a4ea61339463642a2182758871b2ea724f31f531aa98d80f1c3043febca41d5ee52e8b1e127e61719a0d078db8909748d57839e58424b91f063c4fbc8a221bef261140e66a9b596ca6d420a973ad54fef30646ae53ccf0855b61f291a81e0ec6dc0f6bf69f0ca0e5889b7e23f577ba67d2a7d6a2aa91264ab9b20630ed52f8ed56cc10a869807cd1a4c2cd802d8433fee5685d6a04edb0bff248a480b93b01904bed3bb31705d1ecb7332004290cc0cd9cc2f7907cf9db28eec02985301668f53fbc28c3e095c8f3a6cd8cab28e5e442fd9ba608b8b12e098731bbfda755393bd403c62289093b40390b2bae337fc87d2606ca028311d73a9ffbdffef56020c735ada30f54e577c6a9ec515ae2739290609503404b118d7494499ecf0457d75015bb60a16288a4959d74cf5ac5d8d6c113de39f748a418d2a7083b90c9c0a09a49149fd1f2d2cde4412e5aa2421eca6fd4f6fe6b2c362ff37d1a0608c931c7ca3b8fefcfd4c44ef9c38357a0767b14f83cb49bd1989fb3f8e2ab202ac98bd8439790764a40bf309ea2205c1632610956495720030a25dc7118e0c868fdfa78c3e9ecce58215579a0581b3bafdb7dbbe53be9e904567fdc0ce1236aab5d22f1ebc18997e3ea83d362d891e04c5785fd5238326f767bce499209f8db211a50e1402160486e98e7235cf397dbb9ae19fd9b79ef589c821c6f99f28be33452405a003b33f4540fe0a41dfcc286f4d7cc10b70552ba7850869abadcd4bb7f256823face853633d6e2a999ac9fcd259c71d08e266db5d744e1909a62c0db673745ad9585949d108ab96640d2bc27fb4acac7fa8b170a30055a5ede90e004df9a44bdc29aeb4a6bec1e85dde1de6aaf01c6a5d12405d0bec22f49026cb23264f8c04b8401d3c2ab6f2e109948b6193b3bec27adfe19fb8afb8a92364d6fc5b219e8737d583e7ff3a4bcb75d53edda3bf3f52896ac36d8a877ad9f296ea6c045603fc62ac4ae41272bde85ef7c3b3fd3538aacfd5b025fefbe277c2906821ecb20e6f75ea479fa3280f9100fb0089203455c56b6bc775e5c2f0f58c63edd63fa3eec0b40da4b276d0d41da2ec0ead865a98d12bc694e23d8eaadd2b4d0ee88e9570c88fb878930f492e036d27998d593e47763927ff7eb80b188864a3846dd2238f7f95f4090ed399ae95deaeb37abca1cf37c397cc12189affb42dca46b4ff6988eb8c060691d155302d448f50ff70a794d97c0408f8cee9385d6a71fa412e36edcb22dbf433db9db4779f27b682ee17fc05e70c8e794b9f7f6d1")
      val error4 = FailurePacket.wrap(error3, sharedSecret0)
      assert(error4 == hex"2dd2f49c1f5af0fcad371d96e8cddbdcd5096dc309c1d4e110f955926506b3c03b44c192896f45610741c85ed4074212537e0c118d472ff3a559ae244acd9d783c65977765c5d4e00b723d00f12475aafaafff7b31c1be5a589e6e25f8da2959107206dd42bbcb43438129ce6cce2b6b4ae63edc76b876136ca5ea6cd1c6a04ca86eca143d15e53ccdc9e23953e49dc2f87bb11e5238cd6536e57387225b8fff3bf5f3e686fd08458ffe0211b87d64770db9353500af9b122828a006da754cf979738b4374e146ea79dd93656170b89c98c5f2299d6e9c0410c826c721950c780486cd6d5b7130380d7eaff994a8503a8fef3270ce94889fe996da66ed121741987010f785494415ca991b2e8b39ef2df6bde98efd2aec7d251b2772485194c8368451ad49c2354f9d30d95367bde316fec6cbdddc7dc0d25e99d3075e13d3de0822669861dafcd29de74eac48b64411987285491f98d78584d0c2a163b7221ea796f9e8671b2bb91e38ef5e18aaf32c6c02f2fb690358872a1ed28166172631a82c2568d23238017188ebbd48944a147f6cdb3690d5f88e51371cb70adf1fa02afe4ed8b581afc8bcc5104922843a55d52acde09bc9d2b71a663e178788280f3c3eae127d21b0b95777976b3eb17be40a702c244d0e5f833ff49dae6403ff44b131e66df8b88e33ab0a58e379f2c34bf5113c66b9ea8241fc7aa2b1fa53cf4ed3cdd91d407730c66fb039ef3a36d4050dde37d34e80bcfe02a48a6b14ae28227b1627b5ad07608a7763a531f2ffc96dff850e8c583461831b19feffc783bc1beab6301f647e9617d14c92c4b1d63f5147ccda56a35df8ca4806b8884c4aa3c3cc6a174fdc2232404822569c01aba686c1df5eecc059ba97e9688c8b16b70f0d24eacfdba15db1c71f72af1b2af85bd168f0b0800483f115eeccd9b02adf03bdd4a88eab03e43ce342877af2b61f9d3d85497cd1c6b96674f3d4f07f635bb26add1e36835e321d70263b1c04234e222124dad30ffb9f2a138e3ef453442df1af7e566890aedee568093aa922dd62db188aa8361c55503f8e2c2e6ba93de744b55c15260f15ec8e69bb01048ca1fa7bbbd26975bde80930a5b95054688a0ea73af0353cc84b997626a987cc06a517e18f91e02908829d4f4efc011b9867bd9bfe04c5f94e4b9261d30cc39982eb7b250f12aee2a4cce0484ff34eebba89bc6e35bd48d3968e4ca2d77527212017e202141900152f2fd8af0ac3aa456aae13276a13b9b9492a9a636e18244654b3245f07b20eb76b8e1cea8c55e5427f08a63a16b0a633af67c8e48ef8e53519041c9138176eb14b8782c6c2ee76146b8490b97978ee73cd0104e12f483be5a4af414404618e9f6633c55dda6f22252cb793d3d16fae4f0e1431434e7acc8fa2c009d4f6e345ade172313d558a4e61b4377e31b8ed4e28f7cd13a7fe3f72a409bc3bdabfe0ba47a6d861e21f64d2fac706dab18b3e546df4")
      val attribution4 = Attribution.create(Some(attribution3), Some(error3), 500 milliseconds, sharedSecret0)
      assert(attribution4 == hex"84986c936d26bfd3bb2d34d3ec62cfdb63e0032fdb3d9d75f3e5d456f73dffa7e35aab1db4f1bd3b98ff585caf004f656c51037a3f4e810d275f3f6aea0c8e3a125ebee5f374b6440bcb9bb2955ebf706f42be9999a62ed49c7a81fc73c0b4a16419fd6d334532f40bf179dd19afec21bd8519d5e6ebc3802501ef373bc378eee1f14a6fc5fab5b697c91ce31d5922199d1b0ad5ee12176aacafc7c81d54bc5b8fb7e63f3bfd40a3b6e21f985340cbd1c124c7f85f0369d1aa86ebc66def417107a7861131c8bcd73e8946f4fb54bfac87a2dc15bd7af642f32ae583646141e8875ef81ec9083d7e32d5f135131eab7a43803360434100ff67087762bbe3d6afe2034f5746b8c50e0c3c20dd62a4c174c38b1df7365dccebc7f24f19406649fbf48981448abe5c858bbd4bef6eb983ae7a23e9309fb33b5e7c0522554e88ca04b1d65fc190947dead8c0ccd32932976537d869b5ca53ed4945bccafab2a014ea4cbdc6b0250b25be66ba0afff2ff19c0058c68344fd1b9c472567147525b13b1bc27563e61310110935cf89fda0e34d0575e2389d57bdf2869398ca2965f64a6f04e1d1c2edf2082b97054264a47824dd1a9691c27902b39d57ae4a94dd6481954a9bd1b5cff4ab29ca221fa2bf9b28a362c9661206f896fc7cec563fb80aa5eaccb26c09fa4ef7a981e63028a9c4dac12f82ccb5bea090d56bbb1a4c431e315d9a169299224a8dbd099fb67ea61dfc604edf8a18ee742550b636836bb552dabb28820221bf8546331f32b0c143c1c89310c4fa2e1e0e895ce1a1eb0f43278fdb528131a3e32bfffe0c6de9006418f5309cba773ca38b6ad8507cc59445ccc0257506ebc16a4c01d4cd97e03fcf7a2049fea0db28447858f73b8e9fe98b391b136c9dc510288630a1f0af93b26a8891b857bfe4b818af99a1e011e6dbaa53982d29cf74ae7dffef45545279f19931708ed3eede5e82280eab908e8eb80abff3f1f023ab66869297b40da8496861dc455ac3abe1efa8a6f9e2c4eda48025d43a486a3f26f269743eaa30d6f0e1f48db6287751358a41f5b07aee0f098862e3493731fe2697acce734f004907c6f11eef189424fee52cd30ad708707eaf2e441f52bcf3d0c5440c1742458653c0c8a27b5ade784d9e09c8b47f1671901a29360e7e5e94946b9c75752a1a8d599d2a3e14ac81b84d42115cd688c8383a64fc6e7e1dc5568bb4837358ebe63207a4067af66b2027ad2ce8fb7ae3a452d40723a51fdf9f9c9913e8029a222cf81d12ad41e58860d75deb6de30ad")

      // origin parses error packet and can see that it comes from node #4
      val HtlcFailure(holdTimes, Right(DecryptedFailurePacket(pubkey, parsedFailure))) = FailurePacket.decrypt(error4, Some(attribution4), sharedSecrets)
      assert(holdTimes == Seq(HoldTime(500 millisecond, publicKeys(0)), HoldTime(400 milliseconds, publicKeys(1)), HoldTime(300 milliseconds, publicKeys(2)), HoldTime(200 milliseconds, publicKeys(3)), HoldTime(100 milliseconds, publicKeys(4))))
      assert(pubkey == publicKeys(4))
      assert(parsedFailure == failure)
    }
  }

  test("fulfilled HTLC with attribution data (reference test vector)") {
    for ((payloads, packetPayloadLength) <- Seq((referencePaymentPayloads, 1300), (paymentPayloadsFull, 1300))) {
      // origin build the onion packet
      val Success(PacketAndSecrets(packet, sharedSecrets)) = create(sessionKey, packetPayloadLength, publicKeys, payloads, associatedData)
      // each node parses and forwards the packet
      val Right(DecryptedPacket(_, packet1, sharedSecret0)) = peel(privKeys(0), associatedData, packet)
      val Right(DecryptedPacket(_, packet2, sharedSecret1)) = peel(privKeys(1), associatedData, packet1)
      val Right(DecryptedPacket(_, packet3, sharedSecret2)) = peel(privKeys(2), associatedData, packet2)
      val Right(DecryptedPacket(_, packet4, sharedSecret3)) = peel(privKeys(3), associatedData, packet3)
      val Right(lastPacket@DecryptedPacket(_, _, sharedSecret4)) = peel(privKeys(4), associatedData, packet4)
      assert(lastPacket.isLastPacket)

      val attribution = Attribution.create(None, None, 100 millisecond, sharedSecret4)
      assert(attribution == hex"d77d0711b5f71d1d1be56bd88b3bb7ebc1792bb739ea7ebc1bc3b031b8bc2df3a50e25aeb99f47d7f7ab39e24187d3f4df9c4333463b053832ee9ac07274a5261b8b2a01fc09ce9ea7cd04d7b585dfb83299fb6570d71f793c1fcac0ef498766952c8c6840efa02a567d558a3cf6822b12476324b9b9efa03e5f8f26f81fa93daac46cbf00c98e69b6747cf69caaa2a71b025bd18830c4c54cd08f598cfde6197b3f2a951aba907c964c0f5d19a44e6d1d7279637321fa598adde927b3087d238f8b426ecde500d318617cdb7a56e6ce3520fc95be41a549973764e4dc483853ecc313947709f1b5199cb077d46e701fa633e11d3e13b03e9212c115ca6fa004b2f3dd912814693b705a561a06da54cdf603677a3abecdc22c7358c2de3cef771b366a568150aeecc86ad1990bb0f4e2865933b03ea0df87901bff467908273dc6cea31cbab0e2b8d398d10b001058c259ed221b7b55762f4c7e49c8c11a45a107b7a2c605c26dc5b0b10d719b1c844670102b2b6a36c43fe4753a78a483fc39166ae28420f112d50c10ee64ca69569a2f690712905236b7c2cb7ac8954f02922d2d918c56d42649261593c47b14b324a65038c3c5be8d3c403ce0c8f19299b1664bf077d7cf1636c4fb9685a8e58b7029fd0939fa07925a60bed339b23f973293598f595e75c8f9d455d7cebe4b5e23357c8bd47d66d6628b39427e37e0aecbabf46c11be6771f7136e108a143ae9bafba0fc47a51b6c7deef4cba54bae906398ee3162a41f2191ca386b628bde7e1dd63d1611aa01a95c456df337c763cb8c3a81a6013aa633739d8cd554c688102211725e6adad165adc1bcd429d020c51b4b25d2117e8bb27eb0cc7020f9070d4ad19ac31a76ebdf5f9246646aeadbfb9a3f1d75bd8237961e786302516a1a781780e8b73f58dc06f307e58bd0eb1d8f5c9111f01312974c1dc777a6a2d3834d8a2a40014e9818d0685cb3919f6b3b788ddc640b0ff9b1854d7098c7dd6f35196e902b26709640bc87935a3914869a807e8339281e9cedaaca99474c3e7bdd35050bb998ab4546f9900904e0e39135e861ff7862049269701081ebce32e4cca992c6967ff0fd239e38233eaf614af31e186635e9439ec5884d798f9174da6ff569d68ed5c092b78bd3f880f5e88a7a8ab36789e1b57b035fb6c32a6358f51f83e4e5f46220bcad072943df8bd9541a61b7dae8f30fa3dd5fb39b1fd9a0b8e802552b78d4ec306ecee15bfe6da14b29ba6d19ce5be4dd478bca74a52429cd5309d404655c3dec85c252")
      val attribution1 = Attribution.create(Some(attribution), None, 200 milliseconds, sharedSecret3)
      assert(attribution1 == hex"1571e10db7f8aa9f8e7e99caaf9c892e106c817df1d8e3b7b0e39d1c48f631e473e17e205489dd7b3c634cac3be0825cbf01418cd46e83c24b8d9c207742db9a0f0e5bcd888086498159f08080ba7bf3ea029c0b493227c4e75a90f70340d9e21f00979fc7e4fb2078477c1a457ba242ed54b313e590b13a2a13bfeed753dab133c78059f460075b2594b4c31c50f31076f8f1a0f7ad0530d0fadaf2d86e505ff9755940ec0665f9e5bc58cad6e523091f94d0bcd3c6c65ca1a5d401128dcc5e14f9108b32e660017c13de598bcf9d403710857cccb0fb9c2a81bfd66bc4552e1132afa3119203a4aaa1e8839c1dab8cbdcde7b527aca3f54bde651aa9f3f2178829cee3f1c0b9292758a40cc63bd998fcd0d3ed4bdcaf1023267b8f8e44130a63ad15f76145936552381eabb6d684c0a3af6ba8efcf207cebaea5b7acdbb63f8e7221102409d10c23f0514dc9f4d0efb2264161a193a999a23e992632710580a0d320f676d367b9190721194514457761af05207cdab2b6328b1b3767eacb36a7ef4f7bd2e16762d13df188e0898b7410f62459458712a44bf594ae662fd89eb300abb6952ff8ad40164f2bcd7f86db5c7650b654b79046de55d51aa8061ce35f867a3e8f5bf98ad920be827101c64fb871d86e53a4b3c0455bfac5784168218aa72cbee86d9c750a9fa63c363a8b43d7bf4b2762516706a306f0aa3be1ec788b5e13f8b24837e53ac414f211e11c7a093cd9653dfa5fba4e377c79adfa5e841e2ddb6afc054fc715c05ddc6c8fc3e1ee3406e1ffceb2df77dc2f02652614d1bfcfaddebaa53ba919c7051034e2c7b7cfaabdf89f26e7f8e3f956d205dfab747ad0cb505b85b54a68439621b25832cbc2898919d0cd7c0a64cfd235388982dd4dd68240cb668f57e1d2619a656ed326f8c92357ee0d9acead3c20008bc5f04ca8059b55d77861c6d04dfc57cfba57315075acbe1451c96cf28e1e328e142890248d18f53b5d3513ce574dea7156cf596fdb3d909095ec287651f9cf1bcdc791c5938a5dd9b47e84c004d24ab3ae74492c7e8dcc1da15f65324be2672947ec82074cac8ce2b925bc555facbbf1b55d63ea6fbea6a785c97d4caf2e1dad9551b7f66c31caae5ebc7c0047e892f201308fcf452c588be0e63d89152113d87bf0dbd01603b4cdc7f0b724b0714a9851887a01f709408882e18230fe810b9fafa58a666654576d8eba3005f07221f55a6193815a672e5db56204053bc4286fa3db38250396309fd28011b5708a26a2d76c4a333b69b6bfd272fb")
      val attribution2 = Attribution.create(Some(attribution1), None, 300 milliseconds, sharedSecret2)
      assert(attribution2 == hex"34e34397b8621ec2f2b54dbe6c14073e267324cd60b152bce76aec8729a6ddefb61bc263be4b57bd592aae604a32bea69afe6ef4a6b573c26b17d69381ec1fc9b5aa769d148f2f1f8b5377a73840bb6dffc324ded0d1c00dc0c99e3dbc13273b2f89510af6410b525dd8836208abbbaae12753ae2276fa0ca49950374f94e187bf65cefcdd9dd9142074edc4bd0052d0eb027cb1ab6182497f9a10f9fe800b3228e3c088dab60081c807b30a67313667ca8c9e77b38b161a037cae8e973038d0fc4a97ea215914c6c4e23baf6ac4f0fb1e7fcc8aac3f6303658dae1f91588b535eb678e2200f45383c2590a55dc181a09f2209da72f79ae6745992c803310d39f960e8ecf327aed706e4b3e2704eeb9b304dc0e0685f5dcd0389ec377bdba37610ad556a0e957a413a56339dd3c40817214bced5802beee2ee545bdd713208751added5fc0eb2bc89a5aa2decb18ee37dac39f22a33b60cc1a369d24de9f3d2d8b63c039e248806de4e36a47c7a0aed30edd30c3d62debdf1ad82bf7aedd7edec413850d91c261e12beec7ad1586a9ad25b2db62c58ca17119d61dcc4f3e5c4520c42a8e384a45d8659b338b3a08f9e123a1d3781f5fc97564ccff2c1d97f06fa0150cfa1e20eacabefb0c339ec109336d207cc63d9170752fc58314c43e6d4a528fd0975afa85f3aa186ff1b6b8cb12c97ed4ace295b0ef5f075f0217665b8bb180246b87982d10f43c9866b22878106f5214e99188781180478b07764a5e12876ddcb709e0a0a8dd42cf004c695c6fc1669a6fd0e4a1ca54b024d0d80eac492a9e5036501f36fb25b72a054189294955830e43c18e55668337c8c6733abb09fc2d4ade18d5a853a2b82f7b4d77151a64985004f1d9218f2945b63c56fdebd1e96a2a7e49fa70acb4c39873947b83c191c10e9a8f40f60f3ad5a2be47145c22ea59ed3f5f4e61cb069e875fb67142d281d784bf925cc286eacc2c43e94d08da4924b83e58dbf2e43fa625bdd620eba6d9ce960ff17d14ed1f2dbee7d08eceb540fdc75ff06dabc767267658fad8ce99e2a3236e46d2deedcb51c3c6f81589357edebac9772a70b3d910d83cd1b9ce6534a011e9fa557b891a23b5d88afcc0d9856c6dabeab25eea55e9a248182229e4927f268fe5431672fcce52f434ca3d27d1a2136bae5770bb36920df12fbc01d0e8165610efa04794f414c1417f1d4059435c5385bfe2de83ce0e238d6fd2dbd3c0487c69843298577bfa480fe2a16ab2a0e4bc712cd8b5a14871cda61c993b6835303d9043d7689a")
      val attribution3 = Attribution.create(Some(attribution2), None, 400 milliseconds, sharedSecret1)
      assert(attribution3 == hex"74a4ea61339463642a2182758871b2ea724f31f531aa98d80f1c3043febca41d5ee52e8b1e127e61719a0d078db8909748d57839e58424b91f063c4fbc8a221bef261140e66a9b596ca6d420a973ad5431adfa8280a7355462fe50d4cac15cdfbd7a535c4b72a0b6d7d8a64cff3f719ff9b8be28036826342dc3bf3781efc70063d1e6fc79dff86334ae0564a5ab87bd61f8446465ef6713f8c4ef9d0200ebb375f90ee115216b469af42de554622df222858d30d733af1c9223e327ae09d9126be8baee6dd59a112d83a57cc6e0252104c11bc11705d384220eedd72f1a29a0597d97967e28b2ad13ba28b3d8a53c3613c1bb49fe9700739969ef1f795034ef9e2e983af2d3bbd6c637fb12f2f7dfc3aee85e08711e9b604106e95d7a4974e5b047674a6015792dae5d913681d84f71edd415910582e5d86590df2ecfd561dc6e1cdb08d3e10901312326a45fb0498a177319389809c6ba07a76cfad621e07b9af097730e94df92fbd311b2cb5da32c80ab5f14971b6d40f8e2ab202ac98bd8439790764a40bf309ea2205c1632610956495720030a25dc7118e0c868fdfa78c3e9ecce58215579a0581b3bafdb7dbbe53be9e904567fdc0ce1236aab5d22f1ebc18997e3ea83d362d891e04c5785fd5238326f767bce499209f8db211a50e1402160486e98e7235cf397dbb9ae19fd9b79ef589c821c6f99f28be33452405a003b33f4540fe0a41dfcc286f4d7cc10b70552ba7850869abadcd4bb7f256823face853633d6e2a999ac9fcd259c71d08e266db5d744e1909a62c0db673745ad9585949d108ab96640d2bc27fb4acac7fa8b170a30055a5ede90e004df9a44bdc29aeb4a6bec1e85dde1de6aaf01c6a5d12405d0bec22f49026cb23264f8c04b8401d3c2ab6f2e109948b6193b3bec27adfe19fb8afb8a92364d6fc5b219e8737d583e7ff3a4bcb75d53edda3bf3f52896ac36d8a877ad9f296ea6c045603fc62ac4ae41272bde85ef7c3b3fd3538aacfd5b025fefbe277c2906821ecb20e6f75ea479fa3280f9100fb0089203455c56b6bc775e5c2f0f58c63edd63fa3eec0b40da4b276d0d41da2ec0ead865a98d12bc694e23d8eaadd2b4d0ee88e9570c88fb878930f492e036d27998d593e47763927ff7eb80b188864a3846dd2238f7f95f4090ed399ae95deaeb37abca1cf37c397cc12189affb42dca46b4ff6988eb8c060691d155302d448f50ff70a794d97c0408f8cee9385d6a71fa412e36edcb22dbf433db9db4779f27b682ee17fc05e70c8e794b9f7f6d1")
      val attribution4 = Attribution.create(Some(attribution3), None, 500 milliseconds, sharedSecret0)
      assert(attribution4 == hex"84986c936d26bfd3bb2d34d3ec62cfdb63e0032fdb3d9d75f3e5d456f73dffa7e35aab1db4f1bd3b98ff585caf004f656c51037a3f4e810d275f3f6aea0c8e3a125ebee5f374b6440bcb9bb2955ebf70c06d64090f9f6cf098200305f7f4305ba9e1350a0c3f7dab4ccf35b8399b9650d8e363bf83d3a0a09706433f0adae6562eb338b21ea6f21329b3775905e59187c325c9cbf589f5da5e915d9e5ad1d21aa1431f9bdc587185ed8b5d4928e697e67cc96bee6d5354e3764cede3f385588fa665310356b2b1e68f8bd30c75d395405614a40a587031ebd6ace60dfb7c6dd188b572bd8e3e9a47b06c2187b528c5ed35c32da5130a21cd881138a5fcac806858ce6c596d810a7492eb261bcc91cead1dae75075b950c2e81cecf7e5fdb2b51df005d285803201ce914dfbf3218383829a0caa8f15486dd801133f1ed7edec436730b0ec98f48732547927229ac80269fcdc5e4f4db264274e940178732b429f9f0e582c559f994a7cdfb76c93ffc39de91ff936316726cc561a6520d47b2cd487299a96322dadc463ef06127fc63902ff9cc4f265e2fbd9de3fa5e48b7b51aa0850580ef9f3b5ebb60c6c3216c5a75a93e82936113d9cad57ae4a94dd6481954a9bd1b5cff4ab29ca221fa2bf9b28a362c9661206f896fc7cec563fb80aa5eaccb26c09fa4ef7a981e63028a9c4dac12f82ccb5bea090d56bbb1a4c431e315d9a169299224a8dbd099fb67ea61dfc604edf8a18ee742550b636836bb552dabb28820221bf8546331f32b0c143c1c89310c4fa2e1e0e895ce1a1eb0f43278fdb528131a3e32bfffe0c6de9006418f5309cba773ca38b6ad8507cc59445ccc0257506ebc16a4c01d4cd97e03fcf7a2049fea0db28447858f73b8e9fe98b391b136c9dc510288630a1f0af93b26a8891b857bfe4b818af99a1e011e6dbaa53982d29cf74ae7dffef45545279f19931708ed3eede5e82280eab908e8eb80abff3f1f023ab66869297b40da8496861dc455ac3abe1efa8a6f9e2c4eda48025d43a486a3f26f269743eaa30d6f0e1f48db6287751358a41f5b07aee0f098862e3493731fe2697acce734f004907c6f11eef189424fee52cd30ad708707eaf2e441f52bcf3d0c5440c1742458653c0c8a27b5ade784d9e09c8b47f1671901a29360e7e5e94946b9c75752a1a8d599d2a3e14ac81b84d42115cd688c8383a64fc6e7e1dc5568bb4837358ebe63207a4067af66b2027ad2ce8fb7ae3a452d40723a51fdf9f9c9913e8029a222cf81d12ad41e58860d75deb6de30ad")

      val Attribution.UnwrappedAttribution(holdTimes, Some(_)) = Attribution.unwrap(attribution4, sharedSecrets)
      assert(holdTimes == Seq(HoldTime(500 millisecond, publicKeys(0)), HoldTime(400 milliseconds, publicKeys(1)), HoldTime(300 milliseconds, publicKeys(2)), HoldTime(200 milliseconds, publicKeys(3)), HoldTime(100 milliseconds, publicKeys(4))))
    }
  }

  test("only some nodes in the route support attributable failures") {
    for ((payloads, packetPayloadLength) <- Seq((referencePaymentPayloads, 1300), (paymentPayloadsFull, 1300))) {
      // origin build the onion packet
      val Success(PacketAndSecrets(packet, sharedSecrets)) = create(sessionKey, packetPayloadLength, publicKeys, payloads, associatedData)
      // each node parses and forwards the packet
      val Right(DecryptedPacket(_, packet1, sharedSecret0)) = peel(privKeys(0), associatedData, packet)
      val Right(DecryptedPacket(_, packet2, sharedSecret1)) = peel(privKeys(1), associatedData, packet1)
      val Right(DecryptedPacket(_, packet3, sharedSecret2)) = peel(privKeys(2), associatedData, packet2)
      val Right(DecryptedPacket(_, packet4, sharedSecret3)) = peel(privKeys(3), associatedData, packet3)
      val Right(lastPacket@DecryptedPacket(_, _, sharedSecret4)) = peel(privKeys(4), associatedData, packet4)
      assert(lastPacket.isLastPacket)

      // node #4 want to reply with an error message
      val failure = IncorrectOrUnknownPaymentDetails(100 msat, BlockHeight(800000), TlvStream(Set.empty[FailureMessageTlv]))
      val failurePacket = createCustomLengthFailurePacket(failure, sharedSecret4, 1024)
      val error = Sphinx.FailurePacket.wrap(failurePacket, sharedSecret4)
      val error1 = FailurePacket.wrap(error, sharedSecret3)
      // node #4 does not support attributable failures, nodes #0 to #3 support attributable failures
      val attribution1 = Attribution.create(None, Some(error), 200 milliseconds, sharedSecret3)
      val error2 = FailurePacket.wrap(error1, sharedSecret2)
      val attribution2 = Attribution.create(Some(attribution1), Some(error1), 300 milliseconds, sharedSecret2)
      val error3 = FailurePacket.wrap(error2, sharedSecret1)
      val attribution3 = Attribution.create(Some(attribution2), Some(error2), 400 milliseconds, sharedSecret1)
      val error4 = FailurePacket.wrap(error3, sharedSecret0)
      val attribution4 = Attribution.create(Some(attribution3), Some(error3), 500 milliseconds, sharedSecret0)

      // origin parses error packet and can see that it comes from node #4
      val HtlcFailure(holdTimes, Right(DecryptedFailurePacket(pubkey, parsedFailure))) = FailurePacket.decrypt(error4, Some(attribution4), sharedSecrets)
      // We're missing attribution data from node #4 but we get hold times until node #3
      assert(holdTimes == Seq(HoldTime(500 millisecond, publicKeys(0)), HoldTime(400 milliseconds, publicKeys(1)), HoldTime(300 milliseconds, publicKeys(2)), HoldTime(200 milliseconds, publicKeys(3))))
      assert(pubkey == publicKeys(4))
      assert(parsedFailure == failure)
    }
  }

  test("failing node tries to hide its identity") {
    for ((payloads, packetPayloadLength) <- Seq((referencePaymentPayloads, 1300), (paymentPayloadsFull, 1300))) {
      // origin build the onion packet
      val Success(PacketAndSecrets(packet, sharedSecrets)) = create(sessionKey, packetPayloadLength, publicKeys, payloads, associatedData)
      // each node parses and forwards the packet
      val Right(DecryptedPacket(_, packet1, sharedSecret0)) = peel(privKeys(0), associatedData, packet)
      val Right(DecryptedPacket(_, packet2, sharedSecret1)) = peel(privKeys(1), associatedData, packet1)
      val Right(DecryptedPacket(_, packet3, sharedSecret2)) = peel(privKeys(2), associatedData, packet2)
      val Right(DecryptedPacket(_, packet4, sharedSecret3)) = peel(privKeys(3), associatedData, packet3)
      val Right(lastPacket@DecryptedPacket(_, _, sharedSecret4)) = peel(privKeys(4), associatedData, packet4)
      assert(lastPacket.isLastPacket)

      // node #4 fails but instead of returning a failure message it returns random data
      val error = Sphinx.FailurePacket.wrap(randomBytes(1024), sharedSecret4)
      val error1 = FailurePacket.wrap(error, sharedSecret3)
      val attribution1 = Attribution.create(None, Some(error), 200 milliseconds, sharedSecret3)
      val error2 = FailurePacket.wrap(error1, sharedSecret2)
      val attribution2 = Attribution.create(Some(attribution1), Some(error1), 300 milliseconds, sharedSecret2)
      val error3 = FailurePacket.wrap(error2, sharedSecret1)
      val attribution3 = Attribution.create(Some(attribution2), Some(error2), 400 milliseconds, sharedSecret1)
      val error4 = FailurePacket.wrap(error3, sharedSecret0)
      val attribution4 = Attribution.create(Some(attribution3), Some(error3), 500 milliseconds, sharedSecret0)

      // origin can't parse the failure packet but the hold times tell us that nodes #0 to #2 are honest
      val HtlcFailure(holdTimes, Left(CannotDecryptFailurePacket(_, _))) = FailurePacket.decrypt(error4, Some(attribution4), sharedSecrets)
      assert(holdTimes == Seq(HoldTime(500 millisecond, publicKeys(0)), HoldTime(400 milliseconds, publicKeys(1)), HoldTime(300 milliseconds, publicKeys(2)), HoldTime(200 milliseconds, publicKeys(3))))
    }
  }

  test("last node replies with a failure message (arbitrary length)") {
    val Success(PacketAndSecrets(packet, sharedSecrets)) = create(sessionKey, 1300, publicKeys, referencePaymentPayloads, associatedData)
    val Right(DecryptedPacket(_, packet1, sharedSecret0)) = peel(privKeys(0), associatedData, packet)
    val Right(DecryptedPacket(_, packet2, sharedSecret1)) = peel(privKeys(1), associatedData, packet1)
    val Right(DecryptedPacket(_, packet3, sharedSecret2)) = peel(privKeys(2), associatedData, packet2)
    val Right(DecryptedPacket(_, packet4, sharedSecret3)) = peel(privKeys(3), associatedData, packet3)
    val Right(lastPacket@DecryptedPacket(_, _, sharedSecret4)) = peel(privKeys(4), associatedData, packet4)
    assert(lastPacket.isLastPacket)

    // node #4 want to reply with an error message using a custom length
    val error = Sphinx.FailurePacket.wrap(createCustomLengthFailurePacket(TemporaryNodeFailure(), sharedSecret4, 1024), sharedSecret4)
    assert(error == hex"4ca0784803691f89f7558ff4560ba55aa6b94486e5c5cf1d0922750ad01e185ba8cb9168b60f2fd45edd73c1b0c8b33002df376801ff58aaa94000bf8a86f92620f343baef38a580102395ae3abf9128d1047a0736ff9b83d456740ebbb4aeb3aa9737f18fb4afb4aa074fb26c4d702f42968888550a3bded8c05247e045b866baef0499f079fdaeef6538f31d44deafffdfd3afa2fb4ca9082b8f1c465371a9894dd8c243fb4847e004f5256b3e90e2edde4c9fb3082ddfe4d1e734cacd96ef0706bf63c9984e22dc98851bcccd1c3494351feb458c9c6af41c0044bea3c47552b1d992ae542b17a2d0bba1a096c78d169034ecb55b6e3a7263c26017f033031228833c1daefc0dedb8cf7c3e37c9c37ebfe42f3225c326e8bcfd338804c145b16e34e4f5984bc119af09d471a61f39e9e389c4120cadabc5d9b7b1355a8ccef050ca8ad72f642fc26919927b347808bade4b1c321b08bc363f20745ba2f97f0ced2996a232f55ba28fe7dfa70a9ab0433a085388f25cce8d53de6a2fbd7546377d6ede9027ad173ba1f95767461a3689ef405ab608a21086165c64b02c1782b04a6dba2361a7784603069124e12f2f6dcb1ec7612a4fbf94c0e14631a2bef6190c3d5f35e0c4b32aa85201f449d830fd8f782ec758b0910428e3ec3ca1dba3b6c7d89f69e1ee1b9df3dfbbf6d361e1463886b38d52e8f43b73a3bd48c6f36f5897f514b93364a31d49d1d506340b1315883d425cb36f4ea553430d538fd6f3596d4afc518db2f317dd051abc0d4bfb0a7870c3db70f19fe78d6604bbf088fcb4613f54e67b038277fedcd9680eb97bdffc3be1ab2cbcbafd625b8a7ac34d8c190f98d3064ecd3b95b8895157c6a37f31ef4de094b2cb9dbf8ff1f419ba0ecacb1bb13df0253b826bec2ccca1e745dd3b3e7cc6277ce284d649e7b8285727735ff4ef6cca6c18e2714f4e2a1ac67b25213d3bb49763b3b94e7ebf72507b71fb2fe0329666477ee7cb7ebd6b88ad5add8b217188b1ca0fa13de1ec09cc674346875105be6e0e0d6c8928eb0df23c39a639e04e4aedf535c4e093f08b2c905a14f25c0c0fe47a5a1535ab9eae0d9d67bdd79de13a08d59ee05385c7ea4af1ad3248e61dd22f8990e9e99897d653dd7b1b1433a6d464ea9f74e377f2d8ce99ba7dbc753297644234d25ecb5bd528e2e2082824681299ac30c05354baaa9c3967d86d7c07736f87fc0f63e5036d47235d7ae12178ced3ae36ee5919c093a02579e4fc9edad2c446c656c790704bfc8e2c491a42500aa1d75c8d4921ce29b753f883e17c79b09ea324f1f32ddf1f3284cd70e847b09d90f6718c42e5c94484cc9cbb0df659d255630a3f5a27e7d5dd14fa6b974d1719aa98f01a20fb4b7b1c77b42d57fab3c724339d459ee4a1c6b5d3bd4e08624c786a257872acc9ad3ff62222f2265a658d9f2a007229a5293b67ec91c84c4b4407c228434bad8a815ca9b256c776bd2c9f")
    val error1 = FailurePacket.wrap(error, sharedSecret3)
    assert(error1 == hex"2ddcd9ac6122dc79b8b96c5e0c20c40bb64d656a8785420bcdacd3cb67dd27bca081bab1626a0011585323930fa5b9fae0c85770a2279ff59ec427ad1bbff9001c0cd1497004bd2a0f68b50704cf6d6a4bf3c8b6a0833399a24b3456961ba00736785112594f65b6b2d44d9f5ea4e49b5e1ec2af978cbe31c67114440ac51a62081df0ed46d4a3df295da0b0fe25c0115019f03f15ec86fabb4c852f83449e812f141a9395b3f70b766ebbd4ec2fae2b6955bd8f32684c15abfe8fd3a6261e52650e8807a92158d9f1463261a925e4bfba44bd20b166d532f0017185c3a6ac7957adefe45559e3072c8dc35abeba835a8cb01a71a15c736911126f27d46a36168ca5ef7dccd4e2886212602b181463e0dd30185c96348f9743a02aca8ec27c0b90dca2700c1b46d3f10242ceb286acec56576cf0e22042426c5a61d80c0298dc5ce158f46e11eaf8f32cd44d5f1213d4738768f081978420697b454700ade1c093c02a6ca0e78a7e2f3d9e5c7e49e20c3a56b624bfea51196ec9e88e4e56be38ff56031369f45f1e03be826d44a182f270c153ee0d9f8cf9f1f4132f33974e37c7887d5b857365c873cb218cbf20d4be3abdb2a2011b14add0a5672e01e5845421cf6dd6faca1f2f443757aae575c53ab797c2227ecdab03882bbbf4599318cefafa72fa0c9a0f5a51d13c9d0e5d25bfcfb0154ed25895260a9df8743ac188714a3f16960e6e2ff663c08bffda41743d50960ea2f28cda0bc3bd4a180e297b5b41c700b674cb31d99c7f2a1445e121e772984abff2bbe3f42d757ceeda3d03fb1ffe710aecabda21d738b1f4620e757e57b123dbc3c4aa5d9617dfa72f4a12d788ca596af14bea583f502f16fdc13a5e739afb0715424af2767049f6b9aa107f69c5da0e85f6d8c5e46507e14616d5d0b797c3dea8b74a1b12d4e47ba7f57f09d515f6c7314543f78b5e85329d50c5f96ee2f55bbe0df742b4003b24ccbd4598a64413ee4807dc7f2a9c0b92424e4ae1b418a3cdf02ea4da5c3b12139348aa7022cc8272a3a1714ee3e4ae111cffd1bdfd62c503c80bdf27b2feaea0d5ab8fe00f9cec66e570b00fd24b4a2ed9a5f6384f148a4d6325110a41ca5659ebc5b98721d298a52819b6fb150f273383f1c5754d320be428941922da790e17f482989c365c078f7f3ae100965e1b38c052041165295157e1a7c5b7a57671b842d4d85a7d971323ad1f45e17a16c4656d889fc75c12fc3d8033f598306196e29571e414281c5da19c12605f48347ad5b4648e371757cbe1c40adb93052af1d6110cfbf611af5c8fc682b7e2ade3bfca8b5c7717d19fc9f97964ba6025aebbc91a6671e259949dcf40984342118de1f6b514a7786bd4f6598ffbe1604cef476b2a4cb1343db608aca09d1d38fc23e98ee9c65e7f6023a8d1e61fd4f34f753454bd8e858c8ad6be6403edc599c220e03ca917db765980ac781e758179cd93983e9c1e769e4241d47c")
    val error2 = FailurePacket.wrap(error1, sharedSecret2)
    assert(error2 == hex"4c952d273b5c5344d7e4eb5576494a2bfabb21382d310a443c7235ba99bedaf7ff9ac25f422ddfb7466e74f81b3e545563cdd8f5524dae873de61d7bdfccd496af2584930d2b566b4f8d3881f8c043df92224f38cf094cfc09d92655989531524593ec6d6caec1863bdfaa79229b5020acc034cd6deeea1021c50586947b9b8e6faa83b81fbfa6133c0af5d6b07c017f7158fa94f0d206baf12dda6b68f785b773b360fd0497e16cc402d779c8d48d0fa6315536ef0660f3f4e1865f5b38ea49c7da4fd959de4e83ff3ab686f059a45c65ba2af4a6a79166aa0f496bf04d06987b6d2ea205bdb0d347718b9aeff5b61dfff344993a275b79717cd815b6ad4c0beb568c4ac9c36ff1c315ec1119a1993c4b61e6eaa0375e0aaf738ac691abd3263bf937e310be4b517177c9d27b9d0e30158cd0cd739f6782e71ca334e378aa129aac1395802b8866064f7bad07a50da5cf31f8c3151c4c52e525fb22ecf48f8fa39bb5adf932b50c12c10be90174b37d454a3f8b284c849e86578a6182c4a7b2e47dd57d44730a1be9fec4ad07287a397e28dce4fda57e9cdfdb2eb5afdf0d38ef19d982341d18d07a556bb16c1416f480a396f278373b8fd9897023a4ac506e65cf4c306377730f9c8ca63cf47565240b59c4861e52f1dab84d938e96fb31820064d534aca05fd3d2600834fe4caea98f2a748eb8f200af77bd9fbf46141952b9ddda66ef0ebea17ea1e7bb5bce65b6e71554c56dd0d4e14f4cf74c77a150776bf31e7419756c71e7421dc22efe9cf01de9e19fc8808d5b525431b944400db121a77994518d6025711cb25a18774068bba7faaa16d8f65c91bec8768848333156dcb4a08dfbbd9fef392da3e4de13d4d74e83a7d6e46cfe530ee7a6f711e2caf8ad5461ba8177b2ef0a518baf9058ff9156e6aa7b08d938bd8d1485a787809d7b4c8aed97be880708470cd2b2cdf8e2f13428cc4b04ef1f2acbc9562f3693b948d0aa94b0e6113cafa684f8e4a67dc431dfb835726874bef1de36f273f52ee694ec46b0700f77f8538067642a552968e866a72a3f2031ad116663ac17b172b446c5bc705b84777363a9a3fdc6443c07b2f4ef58858122168d4ebbaee920cefc312e1cea870ed6e15eec046ab2073bbf08b0a3366f55cfc6ad4681a12ab0946534e7b6f90ea8992d530ec3daa6b523b3cf03101c60cadd914f30dec932c1ef4341b5a8efac3c921e203574cfe0f1f83433fddb8ccfd273f7c3cab7bc27efe3bb61fdccd5146f1185364b9b621e7fb2b74b51f5ee6be72ab6ff46a6359dc2c855e61469724c1dbeb273df9d2e1c1fb74891239c0019dc12d5c7535f7238f963b761d7102b585372cf021b64c4fc85bfb3161e59d2e298bba44cfd34d6859d9dba9dc6271e5047d525468c814f2ae438474b0a977273036da1a2292f88fcfb89574a6bdca1185b40f8aa54026d5926725f99ef028da1be892e3586361efe15f4a148ff1bc9")
    val error3 = FailurePacket.wrap(error2, sharedSecret1)
    assert(error3 == hex"4385e5483ecade9dc66c52cd980c96f60143bed184abac736030d8efb91c8d17b7dfadbb55ec8dae8857add94e6702fb4c3a4de22e2e669e1ed926b04447fc73034bb730f4932acd62727b75348a648a1128744657ca6a4e713b9b646c3ca66cac02cdab44dd3439890ef3aaf61708714f7375349b8da541b2548d452d84de7084bb95b3ac2345201d624d31f4d52078aa0fa05a88b4e20202bd2b86ac5b52919ea305a8949de95e935eed0319cf3cf19ebea61d76ba92532497fcdc9411d06bcd4275094d0a4a3c5d3a945e43305a5a9256e333e1f64dbca5fcd4e03a39b9012d197506e06f29339dfee3331995b21615337ae060233d39befea925cc262873e0530408e6990f1cbd233a150ef7b004ff6166c70c68d9f8c853c1abca640b8660db29218466c8766a7103a2ebdfe36daee877fffeb8f19bb9b7e6267a37129b836b28abddfc370eb45c1699c856969e2d574fdd155945ed727fdf2aec4f056a4d49fdefc3abafe41c365a5bd14fd486d6b5e2f24199319e7813e02e798877ffe31a70ae2398d9e31b9e3727e6c1a3c0d995c67d37bb6e72e9660aaaa9232670f382add2edd468927e3303b6142672546997fe105583e7c5a3c4c2b599731308b5416e6c9a3f3ba55b181ad0439d3535356108b059f2cb8742eed7a58d4eba9fe79eaa77c34b12aff1abdaea93197aabd0e74cb271269ca464b3b06aef1d6573df5e1224179616036b368677f26479376681b772d3760e871d99efd34cca5cd6beca95190d967da820b21e5bec60082ea46d776b0517488c84f26d12873912d1f68fafd67bcf4c298e43cfa754959780682a2db0f75f95f0598c0d04fd014c50e4beb86a9e37d95f2bba7e5065ae052dc306555bca203d104c44a538b438c9762de299e1c4ad30d5b4a6460a76484661fc907682af202cd69b9a4473813b2fdc1142f1403a49b7e69a650b7cde9ff133997dcc6d43f049ecac5fce097a21e2bce49c810346426585e3a5a18569b4cddd5ff6bdec66d0b69fcbc5ab3b137b34cc8aefb8b850a764df0e685c81c326611d901c392a519866e132bbb73234f6a358ba284fbafb21aa3605cacbaf9d0c901390a98b7a7dac9d4f0b405f7291c88b2ff45874241c90ac6c5fc895a440453c344d3a365cb929f9c91b9e39cb98b142444aae03a6ae8284c77eb04b0a163813d4c21883df3c0f398f47bf127b5525f222107a2d8fe55289f0cfd3f4bbad6c5387b0594ef8a966afc9e804ccaf75fe39f35c6446f7ee076d433f2f8a44dba1515acc78e589fa8c71b0a006fe14feebd51d0e0aa4e51110d16759eee86192eee90b34432130f387e0ccd2ee71023f1f641cddb571c690107e08f592039fe36d81336a421e89378f351e633932a2f5f697d25b620ffb8e84bb6478e9bd229bf3b164b48d754ae97bd23f319e3c56b3bcdaaeb3bd7fc02ec02066b324cb72a09b6b43dec1097f49d69d3c138ce6f1a6402898baf7568c")
    val error4 = FailurePacket.wrap(error3, sharedSecret0)
    assert(error4 == hex"751c187d145e5498306824f193c6bf9ed4a974fa85b3cc5d32d549ce494c1e7b3a06a19f8a9145610741c83ad40b7712aefaddec8c6baf7325d92ea4ca4d1df8bce517f7e54554608bf2bd8071a4f52a7a2f7ffbb1413edad81eeea5785aa9d990f2865dc23b4bc3c301a94eec4eabebca66be5cf638f693ec256aec514620cc28ee4a94bd9565bc4d4962b9d3641d4278fb319ed2b84de5b665f307a2db0f7fbb757366067d88c50f7e829138fde4f78d39b5b5802f1b92a8a820865af5cc79f9f30bc3f461c66af95d13e5e1f0381c184572a91dee1c849048a647a1158cf884064deddbf1b0b88dfe2f791428d0ba0f6fb2f04e14081f69165ae66d9297c118f0907705c9c4954a199bae0bb96fad763d690e7daa6cfda59ba7f2c8d11448b604d12dc942b5cf1db059d3e73d63967e464b5d5cfd4052de195387de93535e88a2e618e15a7c521d67ce2cc836c49118f205c99f18570504504221e337a29e2716fb28671b2bb91e38ef5e18aaf32c6c02f2fb690358872a1ed28166172631a82c2568d23238017188ebbd48944a147f6cdb3690d5f88e51371cb70adf1fa02afe4ed8b581afc8bcc5104922843a55d52acde09bc9d2b71a663e178788280f3c3eae127d21b0b95777976b3eb17be40a702c244d0e5f833ff49dae6403ff44b131e66df8b88e33ab0a58e379f2c34bf5113c66b9ea8241fc7aa2b1fa53cf4ed3cdd91d407730c66fb039ef3a36d4050dde37d34e80bcfe02a48a6b14ae28227b1627b5ad07608a7763a531f2ffc96dff850e8c583461831b19feffc783bc1beab6301f647e9617d14c92c4b1d63f5147ccda56a35df8ca4806b8884c4aa3c3cc6a174fdc2232404822569c01aba686c1df5eecc059ba97e9688c8b16b70f0d24eacfdba15db1c71f72af1b2af85bd168f0b0800483f115eeccd9b02adf03bdd4a88eab03e43ce342877af2b61f9d3d85497cd1c6b96674f3d4f07f635bb26add1e36835e321d70263b1c04234e222124dad30ffb9f2a138e3ef453442df1af7e566890aedee568093aa922dd62db188aa8361c55503f8e2c2e6ba93de744b55c15260f15ec8e69bb01048ca1fa7bbbd26975bde80930a5b95054688a0ea73af0353cc84b997626a987cc06a517e18f91e02908829d4f4efc011b9867bd9bfe04c5f94e4b9261d30cc39982eb7b250f12aee2a4cce0484ff34eebba89bc6e35bd48d3968e4ca2d77527212017e202141900152f2fd8af0ac3aa456aae13276a13b9b9492a9a636e18244654b3245f07b20eb76b8e1cea8c55e5427f08a63a16b0a633af67c8e48ef8e53519041c9138176eb14b8782c6c2ee76146b8490b97978ee73cd0104e12f483be5a4af414404618e9f6633c55dda6f22252cb793d3d16fae4f0e1431434e7acc8fa2c009d4f6e345ade172313d558a4e61b4377e31b8ed4e28f7cd13a7fe3f72a409bc3bdabfe0ba47a6d861e21f64d2fac706dab18b3e546df4")

    // origin parses error packet and can see that it comes from node #4
    val Right(DecryptedFailurePacket(pubkey, failure)) = FailurePacket.decrypt(error4, None, sharedSecrets).failure
    assert(pubkey == publicKeys(4))
    assert(failure == TemporaryNodeFailure())
  }

  test("intermediate node replies with a failure message (reference test vector)") {
    for ((payloads, packetPayloadLength) <- Seq((referencePaymentPayloads, 1300), (paymentPayloadsFull, 1300), (trampolinePaymentPayloads, 400))) {
      // origin build the onion packet
      val Success(PacketAndSecrets(packet, sharedSecrets)) = create(sessionKey, packetPayloadLength, publicKeys, payloads, associatedData)
      // each node parses and forwards the packet
      val Right(DecryptedPacket(_, packet1, sharedSecret0)) = peel(privKeys(0), associatedData, packet)
      val Right(DecryptedPacket(_, packet2, sharedSecret1)) = peel(privKeys(1), associatedData, packet1)
      val Right(DecryptedPacket(_, _, sharedSecret2)) = peel(privKeys(2), associatedData, packet2)

      // node #2 want to reply with an error message
      val error = createAndWrap(sharedSecret2, InvalidRealm())
      val error1 = FailurePacket.wrap(error, sharedSecret1)
      val error2 = FailurePacket.wrap(error1, sharedSecret0)

      // origin parses error packet and can see that it comes from node #2
      val Right(DecryptedFailurePacket(pubkey, failure)) = FailurePacket.decrypt(error2, None, sharedSecrets).failure
      assert(pubkey == publicKeys(2))
      assert(failure == InvalidRealm())
    }
  }

  test("intermediate node replies with a failure message (arbitrary length)") {
    val Success(PacketAndSecrets(packet, sharedSecrets)) = create(sessionKey, 1300, publicKeys, referencePaymentPayloads, associatedData)
    val Right(DecryptedPacket(_, packet1, sharedSecret0)) = peel(privKeys(0), associatedData, packet)
    val Right(DecryptedPacket(_, packet2, sharedSecret1)) = peel(privKeys(1), associatedData, packet1)
    val Right(DecryptedPacket(_, _, sharedSecret2)) = peel(privKeys(2), associatedData, packet2)

    // node #2 want to reply with an error message
    val error = Sphinx.FailurePacket.wrap(createCustomLengthFailurePacket(InvalidRealm(), sharedSecret2, 1024), sharedSecret2)
    assert(error == hex"f1ca7d3b281a71af53d4a0f83f22b618aae9f9c11b1f3302b13615c66d9aefcc5f1938ef23b9dfa61e3d576b149bedaf83058f85f06a3172a3223ad6c4732d96b32955da7d2feb4140e58d86fc0f2eb5d9d1878e6f8a7f65ab9212030e8e915573ebbd7f35e1a430890be7e67c3fb4bbf2def662fa625421e7b411c29ebe81ec67b77355596b05cc155755664e59c16e21410aabe53e80404a615f44ebb31b365ca77a6e91241667b26c6cad24fb2324cf64e8b9dd6e2ce65f1f098cfd1ef41ba2d4c7def0ff165a0e7c84e7597c40e3dffe97d417c144545a0e38ee33ebaae12cc0c14650e453d46bfc48c0514f354773435ee89b7b2810606eb73262c77a1d67f3633705178d79a1078c3a01b5fadc9651feb63603d19decd3a00c1f69af2dab2595931ca50d8280758b1cc91ba2dc43dbbc3d91bf25c08b46c2ecef7a32cec64d4b61ee3a629ef563afe058b71e71bcb69033948bc8728c5ebe65ec596e4f305b9fc159d53f723dfc95b57f3d51717f1c89af97a6d587e89e62efcc92198a1b2bd66e2d875505ea4046c04389f8cb0ee98f0af03af2652e2f3d9a9c48430f2891a4d9b16e7d18099e4a3dd334c24aba1e2450792c2f22092c170da549d43a440021e699bd6c20d8bbf1961100a01ebcce06a4609f5ad93066287acf68294cfa9ea7cea03a508983b134a9f0118b16409a61c06aaa95897d2067cb7cd59123f3e2ccf0e16091571d616c44818f118bb7835a679f5c0eea8cf1bd5479882b2c2a341ec26dbe5da87b3d37d66b1fbd176f71ab203a3b6eaf7f214d579e7d0e4a3e59089ebd26ba04a62403ae7a793516ec16d971d51c5c0107a917d1a70221e6de16edca7cb057c7d06902b5191f298aa4d478a0c3a6260c257eae504ebbf2b591688e6f3f77af770b6f566ae9868d2f26c12574d3bf9323af59f0fe0072ff94ae597c2aa6fbcbf0831989e02f9d3d1b9fd6dd97f509185d9ecbf272e38bd621ee94b97af8e1cd43853a8f6aa6e8372585c71bf88246d064ade524e1e0bd8496b620c4c2d3ae06b6b064c97536aaf8d515046229f72bee8aa398cd0cc21afd5449595016bef4c77cb1e2e9d31fe1ca3ffde06515e6a4331ccc84edf702e5777b10fc844faf17601a4be3235931f6feca4582a8d247c1d6e4773f8fb6de320cf902bbb1767192782dc550d8e266e727a2aa2a414b816d1826ea46af71701537193c22bbcc0123d7ff5a23b0aa8d7967f36fef27b14fe1866ff3ab215eb29e07af49e19174887d71da7e7fe1b7aa1b3c805c063e0fafedf125fa6c57e38cce33a3f7bb35fd8a9f0950de3c22e49743c05f40bc55f960b8a8b5e2fde4bb229f125538438de418cb318d13968532499118cb7dcaaf8b6d635ac4001273bdafd12c8ea0702fb2f0dac81dbaaf68c1c32266382b293fa3951cb952ed5c1bdc41750cdbc0bd62c51bb685616874e251f031a929c06faef5bfcb0857f815ae20620b823f0abecfb5")
    val error1 = FailurePacket.wrap(error, sharedSecret1)
    assert(error1 == hex"fedab5542d8cfc76425c1960d1676ac551116628b2859535ed74f8934d38b82c175c570b34788dbfd0048e4a41c2bb01acf21a928c09f96b801d011d5ff805731f476679849797e76d1ace72304509e05adbbcf0f74959d7d370af32fa27066b9a7a9cb91d92518f3bdabe35a8b3ecea116db79b0c011b70742599012741c4128ca6655eeaf7e6ff343fed810af0e069fa1650659d5864f8b9f1aea92f1fcc10b1b71f3b012e1e55e53056d7f5e092daf7eb1b9244d2de468f69730f3237ce39a84cfd0ee42b12e5ac7ca63fea15bee528125e135090988e55fda565f99f15787ab49ae2b536ca34b1732069a72f314c99836091c17f4e50afecc602184c1e656cf6eb752a4ded94df315a3e16e3d3e422517e9b9a5c566f8bf3eb6144a6778df0078b51887d8ea59b73416c59594f81f8bf1b0f1c98b3d9d5ed87fe76358a47df8a705fb3edddf64770c2d49744854a5ed0272d94cec1cd1b049a6dece2e4aade89d783634c259a330bc407af06368aece354d6fe73608716da08a037dec9c71c4c73bd4a6c86fd1820b54aa2602132a95495933a24e28b189219859ab46847340ad08968a70d5a0df8223aab06a6ea532a4cb25498f3687361a59b9896975c948e03ba60f5248a1f2f4d7aa6e8f00f82f6ca92273f6084cac56c51d4dda2511d64d88dcfd11df5a07ae6779d445f141f5759fca37e09826e2e481ed5dced02956104b219f839f508f60d8828250d0a3617b9d021fad48cde24a5cb42e3278dff0d95af795d4c71bccd344fa98129c9d6f53dd4f7acab78a98711fc5d04112ae971dedc97649608597b7e53369be2fe3f9b0e6b349b3fadcf9bd2a3d24b5e876c74e1006f7c330714ea5146986f3f73b09cae5cdf6277e23a34ecce0d92d909442743ef415be81050c341eb305e93b14b07b55c079766cd894ea00826ce50d5c45707870b0cf411113b8e4e43cf34caf79f3936fdfdbeae185ff52db69ca72442d892ed0e45b9fac939aa172bfa873cebee1e2196fe124597feb92880339ebca8233acaf3061591ed8cf290dfd9b0a06d7efc299993b9c680451992e15d2cb8b5b4a3dc1e511a39d781818144a9662bdfbd01371e898c454a8a092b7a0d32a8d58aec8134891a974ac7b297c3b4f94100083db891bde0ebc1e737dc6c33dad87cf20429d1b865c7e8ee5032d66a17c5a731d288dda8fc38e2c963c317f12a786ded3eac484dcc11b5c530dec0e4cc40ec4bb2c529555a51d8655a4de08fdde774781b5672150d1c771bf0916fc5df6ddb2f2e683e86aa23a52c0fc2efe72eeb1fa5f86ad7926685f40d57ab19b29e1ce5dce8c98ae35aacf740cacb257915fe8421ec09d0883d4ae41fe2695679264b0196e8d0b874d47e2fd675c9dfba26e666d407572e19a65c84ca54ac7235ef1bd4aedd9b0f6406cc7eeb08020e325f22396bc1a42d2de5ff71042b4e098cb0358741a50757a31c45de1f7ecf3a5e5e06f8b682f0")
    val error2 = FailurePacket.wrap(error1, sharedSecret0)
    assert(error2 == hex"c843486107187673b4586f5cdaad43ad84fbac03b39df51bbf9169b2bd682b409a855b2feb0545705f12eba9dbaecee84e328a9c2e4c3086bb1d0909d1f2e4f8a0e9c6be9541e94a849a0887756b984031dcb74d11c20d437a55daf3ee4109dea68ad74f9b742e7571d5e4d1b2ea4f7094787cf361b448a22a547ea85b833aae20f3ba79fb41c6636414c2092d41dd5328e2c1a1c754cb1f0d297628219f91fe946169f593ce7fce79103945d4d24adce46c083ab24757870356af55fcd3d22b9cfd83c45d409eb3081b218448d5dca3a201cf89ac88c9b66049d7c262b32081d3aba2098ea853bfa173ec23aa9253e083dfa881ef487b76780435c1b9f8a1d794557f0ac91d261d280bfb8513ad0c4dab0d7152eb9ee36ae63b8d384613684326d8735dc559f31cecb21b1d55bbcf7a281127adbedd0210b243325fd291cb82d443beec8f4b96aaee4b1a619724d7456b756d391e8fd3256d2b0766e39a435eb4d6d144c7fca1c73105710266e31120565444dfd6e9099e44d73a0f28419809577a267bbbc6671f723669d00c35c8e60fad88d89d4a7477a0c30f9839485197ed76338330f2ca00cf0e31c59da4eeebef977f429ad2c61acac35939866dac5b1df1c3c487ebaf961340c0c1dbc4bedebde7ee0633c3f480b7df265a3d90e78a4bcb9497f4228169fadb647e77afe6f43aa129286bb21767f6e75ac5c092473f99f2cf8b4e191f300c70b210e077a0385d483971bc0c66f5c119c0731a8753793ad12703d9cc5153eb1c8f25b71ee88a8d1d4433aa8f8277366c82111dbebfe0f548411588d54c3606742330d3d84a2f107df98d60995297de11672f6300b11444a04e252d69d8187772798afc6a9cd8b245a5ebd51bf0659f18c57daf1d1f724d2f15d524ab6902fb17a8fa6cee8e01df67735eac34bb0efc183dcb8d2a7cb401bd786c32a17f14c9d9ffc02b4f58c4ebab898a78b4913647d4cb5bafe6f7f27b5a256d1635c10f0ca71796610068c090c270c20bb18ec9d205e640d7655bdf5c9aeae20d7f9426eade0733c19d0aa577caf31f9d5be0a99ed0c509e84ccb555389ca69f09c3e66694a4ea2785f8d839d7dfff08b2c21aff89a023161cb1ebdd1e7a46d6380c0ddbc88eb3526e624fadcd222ecaa09566c2678158f933f03623299fec134a880d39a9d82ba2b29211e7787b3f32d478df856389a02cb68b66fc0dfc0b52353e7360f31e5457a6a9dd34512e912afeb5a92f3cbd3883b62c37e3ba5e4e8b688033150103c810740d130a5597c8a4a16311f50cfb3a919aac1e0a1096f20a14a536c55068ad38f40e62fc6f178b2fee67ca2cbd8afa29ef6c89b217aee02419ca26d59b604521a55e37c0a5a693fbc3ebcba23cd62479ddf62e5521847a2b4ac5e7686ef662c29cf8a8983660530942ee9a6c53b55e08af0b43467989693cefe6267fd524435152c01c9b93aebdec6146366a94162f99ac4c7157c15b988")

    // origin parses error packet and can see that it comes from node #2
    val Right(DecryptedFailurePacket(pubkey, failure)) = FailurePacket.decrypt(error2, None, sharedSecrets).failure
    assert(pubkey == publicKeys(2))
    assert(failure == InvalidRealm())
  }

  test("create blinded route (reference test vector)") {
    val alice = PrivateKey(hex"4141414141414141414141414141414141414141414141414141414141414141")
    val bob = PrivateKey(hex"4242424242424242424242424242424242424242424242424242424242424242")
    val bobPayload = hex"01200000000000000000000000000000000000000000000000000000000000000000 02080000000000000001 0a080032000000002710 0c05000b724632 0e00"
    val carol = PrivateKey(hex"4343434343434343434343434343434343434343434343434343434343434343")
    val carolPayload = hex"02080000000000000002 0821031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f 0a07004b0000009664 0c05000b721432 0e00"
    val dave = PrivateKey(hex"4444444444444444444444444444444444444444444444444444444444444444")
    val davePayload = hex"012200000000000000000000000000000000000000000000000000000000000000000000 02080000000000000003 0a06001900000064 0c05000b71c932 0e00"
    val eve = PrivateKey(hex"4545454545454545454545454545454545454545454545454545454545454545")
    val evePayload = hex"011c00000000000000000000000000000000000000000000000000000000 0616c9cf92f45ade68345bc20ae672e2012f4af487ed4415 0c05000b71b032 0e00"

    // Eve creates a blinded route to herself through Dave:
    val (pathKeyOverride, blindedRouteEnd, lastPathKey) = {
      val sessionKey = PrivateKey(hex"0101010101010101010101010101010101010101010101010101010101010101")
      val BlindedRouteDetails(blindedRoute, lastPathKey) = RouteBlinding.create(sessionKey, Seq(dave, eve).map(_.publicKey), Seq(davePayload, evePayload))
      assert(blindedRoute.firstPathKey == PublicKey(hex"031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"))
      assert(lastPathKey == PublicKey(hex"03e09038ee76e50f444b19abf0a555e8697e035f62937168b80adf0931b31ce52a"))
      (blindedRoute.firstPathKey, blindedRoute, lastPathKey)
    }

    // Bob also wants to use route blinding:
    val (pathKey, blindedRouteStart) = {
      val sessionKey = PrivateKey(hex"0202020202020202020202020202020202020202020202020202020202020202")
      val blindedRoute = RouteBlinding.create(sessionKey, Seq(bob, carol).map(_.publicKey), Seq(bobPayload, carolPayload)).route
      assert(blindedRoute.firstPathKey == PublicKey(hex"024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766"))
      (blindedRoute.firstPathKey, blindedRoute)
    }

    // We now have a blinded route Bob -> Carol -> Dave -> Eve
    val blindedRoute = BlindedRoute(EncodedNodeId(bob.publicKey), pathKey, blindedRouteStart.blindedHops ++ blindedRouteEnd.blindedHops)
    assert(blindedRoute.blindedNodeIds == Seq(
      PublicKey(hex"03da173ad2aee2f701f17e59fbd16cb708906d69838a5f088e8123fb36e89a2c25"),
      PublicKey(hex"02e466727716f044290abf91a14a6d90e87487da160c2a3cbd0d465d7a78eb83a7"),
      PublicKey(hex"036861b366f284f0a11738ffbf7eda46241a8977592878fe3175ae1d1e4754eccf"),
      PublicKey(hex"021982a48086cb8984427d3727fe35a03d396b234f0701f5249daa12e8105c8dae"),
    ))
    assert(blindedRoute.encryptedPayloads == Seq(
      hex"cd7b00ff9c09ed28102b210ac73aa12d63e90852cebc496c49f57c499a2888b49f2e72b19446f7e60a818aa2938d8c625415b992b8928a7321edb8f7cea40de362bed082ad51acc6156dca5532fb68",
      hex"cc0f16524fd7f8bb0f4e8d40ad71709ef140174c76faa574cac401bb8992fef76c4d004aa485dd599ed1cf2715f570f656a5aaecaf1ee8dc9d0fa1d424759be1932a8f29fac08bc2d2a1ed7159f28b",
      hex"0fa1a72cff3b64a3d6e1e4903cf8c8b0a17144aeb249dcb86561adee1f679ee8db3e561d9e49895fd4bcebf6f58d6f61a6d41a9bf5aa4b0453437856632e8255c351873143ddf2bb2b0832b091e1b4",
      hex"da1c7e5f7881219884beae6ae68971de73bab4c3055d9865b1afb60722a63c688768042ade22f2c22f5724767d171fd221d3e579e43b354cc72e3ef146ada91a892d95fc48662f5b158add0af457da",
    ))
    // After generating the blinded route, Eve is able to derive the private key corresponding to her blinded identity.
    assert(RouteBlinding.derivePrivateKey(eve, lastPathKey).publicKey == blindedRoute.blindedNodeIds.last)

    // Every node in the route is able to decrypt its payload and extract the path key for the next node:
    {
      // Bob (the introduction point) can decrypt its encrypted payload and obtain the next ephemeral public key.
      val Success((payload0, ephKey1)) = RouteBlinding.decryptPayload(bob, blindedRoute.firstPathKey, blindedRoute.encryptedPayloads(0))
      assert(payload0 == bobPayload)
      assert(ephKey1 == PublicKey(hex"034e09f450a80c3d252b258aba0a61215bf60dda3b0dc78ffb0736ea1259dfd8a0"))

      // Carol can derive the private key used to unwrap the onion and decrypt its encrypted payload.
      assert(RouteBlinding.derivePrivateKey(carol, ephKey1).publicKey == blindedRoute.blindedNodeIds(1))
      val Success((payload1, ephKey2)) = RouteBlinding.decryptPayload(carol, ephKey1, blindedRoute.encryptedPayloads(1))
      assert(payload1 == carolPayload)
      assert(ephKey2 == PublicKey(hex"03af5ccc91851cb294e3a364ce63347709a08cdffa58c672e9a5c587ddd1bbca60"))
      // NB: Carol finds a path key override and will transmit that instead of ephKey2 to the next node.
      assert(payload1.containsSlice(pathKeyOverride.value))

      // Dave must be given the path key override to derive the private key used to unwrap the onion and decrypt its encrypted payload.
      assert(RouteBlinding.decryptPayload(dave, ephKey2, blindedRoute.encryptedPayloads(2)).isFailure)
      assert(RouteBlinding.derivePrivateKey(dave, pathKeyOverride).publicKey == blindedRoute.blindedNodeIds(2))
      val Success((payload2, ephKey3)) = RouteBlinding.decryptPayload(dave, pathKeyOverride, blindedRoute.encryptedPayloads(2))
      assert(payload2 == davePayload)
      assert(ephKey3 == PublicKey(hex"03e09038ee76e50f444b19abf0a555e8697e035f62937168b80adf0931b31ce52a"))
      assert(ephKey3 == lastPathKey)

      // Eve can derive the private key used to unwrap the onion and decrypt its encrypted payload.
      assert(RouteBlinding.derivePrivateKey(eve, ephKey3).publicKey == blindedRoute.blindedNodeIds(3))
      val Success((payload4, ephKey5)) = RouteBlinding.decryptPayload(eve, ephKey3, blindedRoute.encryptedPayloads(3))
      assert(payload4 == evePayload)
      assert(ephKey5 == PublicKey(hex"038fc6859a402b96ce4998c537c823d6ab94d1598fca02c788ba5dd79fbae83589"))
    }
    // That blinded route may be used for payments, where encrypted payloads are included in the onion:
    {
      val sessionKey = PrivateKey(hex"0303030303030303030303030303030303030303030303030303030303030303")
      val payloads = Seq(
        // The first hop (Alice) receives a normal onion payload.
        TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.AmountToForward(110_125 msat), OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(749150)), OnionPaymentPayloadTlv.OutgoingChannelId(ShortChannelId(10))),
        // The sender includes the path key and the first encrypted recipient data in the introduction node's payload.
        TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.PathKey(pathKey), OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads(0))),
        // The sender includes the correct encrypted recipient data in each blinded node's payload.
        TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads(1))),
        TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads(2))),
        TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.AmountToForward(100_000 msat), OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(749000)), OnionPaymentPayloadTlv.TotalAmount(150_000 msat), OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads(3))),
      ).map(tlvs => PaymentOnionCodecs.perHopPayloadCodec.encode(tlvs).require.bytes)
      assert(payloads == Seq(
        hex"14020301ae2d04030b6e5e0608000000000000000a",
        hex"740a4fcd7b00ff9c09ed28102b210ac73aa12d63e90852cebc496c49f57c499a2888b49f2e72b19446f7e60a818aa2938d8c625415b992b8928a7321edb8f7cea40de362bed082ad51acc6156dca5532fb680c21024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766",
        hex"510a4fcc0f16524fd7f8bb0f4e8d40ad71709ef140174c76faa574cac401bb8992fef76c4d004aa485dd599ed1cf2715f570f656a5aaecaf1ee8dc9d0fa1d424759be1932a8f29fac08bc2d2a1ed7159f28b",
        hex"510a4f0fa1a72cff3b64a3d6e1e4903cf8c8b0a17144aeb249dcb86561adee1f679ee8db3e561d9e49895fd4bcebf6f58d6f61a6d41a9bf5aa4b0453437856632e8255c351873143ddf2bb2b0832b091e1b4",
        hex"6002030186a004030b6dc80a4fda1c7e5f7881219884beae6ae68971de73bab4c3055d9865b1afb60722a63c688768042ade22f2c22f5724767d171fd221d3e579e43b354cc72e3ef146ada91a892d95fc48662f5b158add0af457da12030249f0",
      ))

      val nodeIds = Seq(alice, bob).map(_.publicKey) ++ blindedRoute.blindedNodeIds.tail
      val Success(PacketAndSecrets(onion, sharedSecrets)) = create(sessionKey, 1300, nodeIds, payloads, associatedData)
      assert(serializePaymentOnion(onion) == hex"0002531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe337dadf610256c6ab518495dce9cdedf9391e21a71dada75be905267ba82f326c0513dda706908cfee834996700f881b2aed106585d61a2690de4ebe5d56ad2013b520af2a3c49316bc590ee83e8c31b1eb11ff766dad27ca993326b1ed582fb451a2ad87fbf6601134c6341c4a2deb6850e25a355be68dbb6923dc89444fdd74a0f700433b667bda345926099f5547b07e97ad903e8a01566a78ae177366239e793dac719de805565b6d0a1d290e273f705cfc56873f8b5e28225f7ded7a1d4ceffae63f91e477be8c917c786435976102a924ba4ba3de6150c829ce01c25428f2f5d05ef023be7d590ecdf6603730db3948f80ca1ed3d85227e64ef77200b9b557f427b6e1073cfa0e63e4485441768b98ab11ba8104a6cee1d7af7bb5ee9c05cf9cf4718901e92e09dfe5cb3af336a953072391c1e91fc2f4b92e124b38e0c6d17ef6ba7bbe93f02046975bb01b7f766fcfc5a755af11a90cc7eb3505986b56e07a7855534d03b79f0dfbfe645b0d6d4185c038771fd25b800aa26b2ed2e30b1e713659468618a2fea04fcd0473284598f76b11b0d159d343bc9711d3bea8d561547bcc8fff12317c0e7b1ee75bcb8082d762b6417f99d0f71ff7c060f6b564ad6827edaffa72eefcc4ce633a8da8d41c19d8f6aebd8878869eb518ccc16dccae6a94c690957598ce0295c1c46af5d7a2f0955b5400526bfd1430f554562614b5d00feff3946427be520dee629b76b6a9c2b1da6701c8ca628a69d6d40e20dd69d6e879d7a052d9c16f544b49738c7ff3cdd0613e9ed00ead7707702d1a6a0b88de1927a50c36beb78f4ff81e3dd97b706307596eebb363d418a891e1cb4589ce86ce81cdc0e1473d7a7dd5f6bb6e147c1f7c46fa879b4512c25704da6cdbb3c123a72e3585dc07b3e5cbe7fecf3a08426eee8c70ddc46ebf98b0bcb14a08c469cb5cfb6702acc0befd17640fa60244eca491280a95fbbc5833d26e4be70fcf798b55e06eb9fcb156942dcf108236f32a5a6c605687ba4f037eddbb1834dcbcd5293a0b66c621346ca5d893d239c26619b24c71f25cecc275e1ab24436ac01c80c0006fab2d95e82e3a0c3ea02d08ec5b24eb39205c49f4b549dcab7a88962336c4624716902f4e08f2b23cfd324f18405d66e9da3627ac34a6873ba2238386313af20d5a13bbd507fdc73015a17e3bd38fae1145f7f70d7cb8c5e1cdf9cf06d1246592a25d56ec2ae44cd7f75aa7f5f4a2b2ee49a41a26be4fab3f3f2ceb7b08510c5e2b7255326e4c417325b333cafe96dde1314a15dd6779a7d5a8a40622260041e936247eec8ec39ca29a1e18161db37497bdd4447a7d5ef3b8d22a2acd7f486b152bb66d3a15afc41dc9245a8d75e1d33704d4471e417ccc8d31645fdd647a2c191692675cf97664951d6ce98237d78b0962ad1433b5a3e49ddddbf57a391b14dcce00b4d7efe5cbb1e78f30d5ef53d66c381a45e275d2dcf6be559acb3c42494a9a2156eb8dcf03dd92b2ebaa697ea628fa0f75f125e4a7daa10f8dcf56ebaf7814557708c75580fad2bbb33e66ad7a4788a7aaac792aaae76138d7ff09df6a1a1920ddcf22e5e7007b15171b51ff81799355232ce39f7d5ceeaf704255d790041d6390a69f42816cba641ec81faa3d7c0fdec59dfe4ca41f31a692eaffc66b083995d86c575aea4514a3e09e8b3a1fa4d1591a2505f253ad0b6bfd9d87f063d2be414d3a427c0506a88ac5bdbef9b50d73bce876f85c196dca435e210e1d6713695b529ddda3350fb5065a6a8288abd265380917bac8ebbc7d5ced564587471dddf90c22ce6dbadea7e7a6723438d4cf6ac6dae27d033a8cadd77ab262e8defb33445ddb2056ec364c7629c33745e2338")

      // Alice can decrypt the onion as usual.
      val Right(DecryptedPacket(onionPayloadAlice, packetForBob, sharedSecretAlice)) = peel(alice, associatedData, onion)

      // Bob is the introduction node.
      // He can decrypt the onion as usual, but the payload doesn't contain a shortChannelId or a nodeId to forward to.
      // However it contains a path key and encrypted data, which he can decrypt to discover the next node.
      val Right(DecryptedPacket(onionPayloadBob, packetForCarol, sharedSecretBob)) = peel(bob, associatedData, packetForBob)
      val tlvsBob = PaymentOnionCodecs.perHopPayloadCodec.decode(onionPayloadBob.bits).require.value
      assert(tlvsBob.get[OnionPaymentPayloadTlv.PathKey].map(_.publicKey).contains(pathKey))
      assert(tlvsBob.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)

      val Right(decryptedPayloadBob) = RouteBlindingEncryptedDataCodecs.decode(bob, pathKey, tlvsBob.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data)
      val pathKeyForCarol = decryptedPayloadBob.nextPathKey
      val Right(payloadBob) = PaymentOnion.IntermediatePayload.ChannelRelay.Blinded.validate(tlvsBob, decryptedPayloadBob.tlvs, pathKeyForCarol)
      assert(payloadBob.outgoing.contains(ShortChannelId(1)))
      assert(payloadBob.amountToForward(110_125 msat) == 100_125.msat)
      assert(payloadBob.outgoingCltv(CltvExpiry(749150)) == CltvExpiry(749100))
      assert(payloadBob.paymentRelayData.paymentRelay == RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(50), 0, 10_000 msat))
      assert(payloadBob.paymentRelayData.paymentConstraints == RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750150), 50 msat))
      assert(payloadBob.paymentRelayData.allowedFeatures.isEmpty)

      // Carol is a blinded hop.
      // She receives the path key from Bob (e.g. in a tlv field in update_add_htlc) which she can use to derive the
      // private key corresponding to her blinded node ID and decrypt the onion.
      // The payload doesn't contain a shortChannelId or a nodeId to forward to, but the encrypted data does.
      val blindedPrivKeyCarol = RouteBlinding.derivePrivateKey(carol, pathKeyForCarol)
      val Right(DecryptedPacket(onionPayloadCarol, packetForDave, sharedSecretCarol)) = peel(blindedPrivKeyCarol, associatedData, packetForCarol)
      val tlvsCarol = PaymentOnionCodecs.perHopPayloadCodec.decode(onionPayloadCarol.bits).require.value
      assert(tlvsCarol.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
      val Right(decryptedPayloadCarol) = RouteBlindingEncryptedDataCodecs.decode(carol, pathKeyForCarol, tlvsCarol.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data)
      val pathKeyForDave = decryptedPayloadCarol.nextPathKey
      val Right(payloadCarol) = PaymentOnion.IntermediatePayload.ChannelRelay.Blinded.validate(tlvsCarol, decryptedPayloadCarol.tlvs, pathKeyForDave)
      assert(payloadCarol.outgoing.contains(ShortChannelId(2)))
      assert(payloadCarol.amountToForward(100_125 msat) == 100_010.msat)
      assert(payloadCarol.outgoingCltv(CltvExpiry(749100)) == CltvExpiry(749025))
      assert(payloadCarol.paymentRelayData.paymentRelay == RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(75), 150, 100 msat))
      assert(payloadCarol.paymentRelayData.paymentConstraints == RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750100), 50 msat))
      assert(payloadCarol.paymentRelayData.allowedFeatures.isEmpty)
      // Carol's payload contains a path key override.
      val pathKeyForDaveOverride = payloadCarol.paymentRelayData.records.get[RouteBlindingEncryptedDataTlv.NextPathKey].map(_.pathKey)
      assert(pathKeyForDaveOverride.contains(pathKeyOverride))
      assert(pathKeyForDave == pathKeyOverride)

      // Dave is a blinded hop.
      // He receives the path key from Carol (e.g. in a tlv field in update_add_htlc) which he can use to derive the
      // private key corresponding to his blinded node ID and decrypt the onion.
      val blindedPrivKeyDave = RouteBlinding.derivePrivateKey(dave, pathKeyOverride)
      val Right(DecryptedPacket(onionPayloadDave, packetForEve, sharedSecretDave)) = peel(blindedPrivKeyDave, associatedData, packetForDave)
      val tlvsDave = PaymentOnionCodecs.perHopPayloadCodec.decode(onionPayloadDave.bits).require.value
      assert(tlvsDave.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
      val Right(decryptedPayloadDave) = RouteBlindingEncryptedDataCodecs.decode(dave, pathKeyOverride, tlvsDave.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data)
      val pathKeyForEve = decryptedPayloadDave.nextPathKey
      val Right(payloadDave) = PaymentOnion.IntermediatePayload.ChannelRelay.Blinded.validate(tlvsDave, decryptedPayloadDave.tlvs, pathKeyForEve)
      assert(payloadDave.outgoing.contains(ShortChannelId(3)))
      assert(payloadDave.amountToForward(100_010 msat) == 100_000.msat)
      assert(payloadDave.outgoingCltv(CltvExpiry(749025)) == CltvExpiry(749000))
      assert(payloadDave.paymentRelayData.paymentRelay == RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(25), 100, 0 msat))
      assert(payloadDave.paymentRelayData.paymentConstraints == RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750025), 50 msat))
      assert(payloadDave.paymentRelayData.allowedFeatures.isEmpty)

      // Eve is the blinded recipient.
      // She receives the path key from Dave (e.g. in a tlv field in update_add_htlc) which she can use to derive
      // the private key corresponding to her blinded node ID and decrypt the onion.
      val blindedPrivKeyEve = RouteBlinding.derivePrivateKey(eve, pathKeyForEve)
      val Right(DecryptedPacket(onionPayloadEve, packetForNobody, sharedSecretEve)) = peel(blindedPrivKeyEve, associatedData, packetForEve)
      val tlvsEve = PaymentOnionCodecs.perHopPayloadCodec.decode(onionPayloadEve.bits).require.value
      assert(tlvsEve.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
      val Right(decryptedPayloadEve) = RouteBlindingEncryptedDataCodecs.decode(eve, pathKeyForEve, tlvsEve.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data)
      val Right(payloadEve) = PaymentOnion.FinalPayload.Blinded.validate(tlvsEve, decryptedPayloadEve.tlvs)
      assert(payloadEve.pathId == hex"c9cf92f45ade68345bc20ae672e2012f4af487ed4415")
      assert(payloadEve.paymentConstraints_opt.contains(RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750000), 50 msat)))
      assert(payloadEve.allowedFeatures.isEmpty)

      assert(Seq(onionPayloadAlice, onionPayloadBob, onionPayloadCarol, onionPayloadDave, onionPayloadEve) == payloads)
      assert(Seq(sharedSecretAlice, sharedSecretBob, sharedSecretCarol, sharedSecretDave, sharedSecretEve) == sharedSecrets.map(_.secret))

      val packets = Seq(packetForBob, packetForCarol, packetForDave, packetForEve, packetForNobody)
      assert(packets(0).hmac == ByteVector32(hex"73fba184685e19b9af78afe876aa4e4b4242382b293133771d95a2bd83fa9c62"))
      assert(packets(1).hmac == ByteVector32(hex"d57dc11a256834bb49ac9deeec88e4bf563c7340f44a240caec941c7e50f09cf"))
      assert(packets(2).hmac == ByteVector32(hex"a955510e2126b3bb989a4ac21cf948f965e48bc363d2997437797b4f770e8b65"))
      assert(packets(3).hmac == ByteVector32(hex"4f11ad63afe404cb6f1e8ea5fd7a8e085b65ca5136146febf4d47928dcc9a9e0"))
      assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
    }
  }

  test("invalid blinded route") {
    val path = RouteBlinding.create(sessionKey, publicKeys, routeBlindingPayloads).route
    assert(RouteBlinding.decryptPayload(privKeys(0), path.firstPathKey, path.encryptedPayloads(0)).isSuccess)
    // Invalid node private key:
    assert(RouteBlinding.decryptPayload(privKeys(1), path.firstPathKey, path.encryptedPayloads(0)).isFailure)
    // Invalid path key:
    assert(RouteBlinding.decryptPayload(privKeys(0), randomKey().publicKey, path.encryptedPayloads(0)).isFailure)
    // Invalid encrypted payload:
    assert(RouteBlinding.decryptPayload(privKeys(0), path.firstPathKey, path.encryptedPayloads(1)).isFailure)
  }

}

object SphinxSpec {

  def serializePaymentOnion(onion: OnionRoutingPacket): ByteVector =
    PaymentOnionCodecs.paymentOnionPacketCodec.encode(onion).require.toByteVector

  def serializeTrampolineOnion(onion: OnionRoutingPacket): ByteVector =
    OnionRoutingCodecs.onionRoutingPacketCodec(onion.payload.length.toInt).encode(onion).require.toByteVector

  def createCustomLengthFailurePacket(failure: FailureMessage, sharedSecret: ByteVector32, length: Int): ByteVector = {
    val um = Sphinx.generateKey("um", sharedSecret)
    val packet = FailureMessageCodecs.failureOnionCodec(Hmac256(um), length).encode(failure).require.toByteVector
    packet
  }

  def createAndWrap(sharedSecret: ByteVector32, failure: FailureMessage): ByteVector = {
    FailurePacket.wrap(FailurePacket.create(sharedSecret, failure), sharedSecret)
  }

  val privKeys = Seq(
    PrivateKey(hex"4141414141414141414141414141414141414141414141414141414141414141"),
    PrivateKey(hex"4242424242424242424242424242424242424242424242424242424242424242"),
    PrivateKey(hex"4343434343434343434343434343434343434343434343434343434343434343"),
    PrivateKey(hex"4444444444444444444444444444444444444444444444444444444444444444"),
    PrivateKey(hex"4545454545454545454545454545454545454545454545454545454545454545")
  )
  val publicKeys = privKeys.map(_.publicKey)
  assert(publicKeys == Seq(
    PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"),
    PublicKey(hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c"),
    PublicKey(hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007"),
    PublicKey(hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991"),
    PublicKey(hex"02edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145")
  ))

  val sessionKey: PrivateKey = PrivateKey(hex"4141414141414141414141414141414141414141414141414141414141414141")

  // origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4
  val referencePaymentPayloads = Seq(
    hex"12 02023a98 040205dc 06080000000000000001",
    hex"52 020236b0 04020578 06080000000000000002 fd02013c0102030405060708090a0b0c0d0e0f0102030405060708090a0b0c0d0e0f0102030405060708090a0b0c0d0e0f0102030405060708090a0b0c0d0e0f",
    hex"12 020230d4 040204e2 06080000000000000003",
    hex"12 02022710 040203e8 06080000000000000004",
    hex"fd0110 02022710 040203e8 082224a33562c54507a9334e79f0dc4f17d407e6d7c61f0e2f3d0d38599502f617042710 fd012de02a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a",
  )

  // This test vector uses multiple payloads to fill the whole onion packet.
  // origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4
  val paymentPayloadsFull = Seq(
    hex"8b09000000000000000030000000000000000000000000000000000000000000000000000000000025000000000000000000000000000000000000000000000000250000000000000000000000000000000000000000000000002500000000000000000000000000000000000000000000000025000000000000000000000000000000000000000000000000",
    hex"fd012a08000000000000009000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000",
    hex"620800000000000000900000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
    hex"fc120000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000",
    hex"fd01582200000000000000000000000000000000000000000022000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000"
  )

  // This test vector uses a single payload filling the whole onion payload.
  // origin -> recipient
  val oneHopPaymentPayload = Seq(
    hex"fd04f16500000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
  )

  // This test vector uses trampoline variable-size payloads.
  val trampolinePaymentPayloads = Seq(
    hex"2a 02020231 040190 f8210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c",
    hex"35 fa 33 010000000000000000000000040000000000000000000000000ff0000000000000000000000000000000000000000000000000",
    hex"23 f8 21 032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991",
    hex"40 0306030303030303 2b06000000000003 2d020003 2f0a00000000000000000000 311e000000000000000000000000000000000000000000000000000000000000",
    hex"23 f8 21 02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"
  )

  // This test vector uses route blinding payloads (encrypted_data).
  val routeBlindingPayloads = Seq(
    hex"0208000000000000002a 0a06009000000000 0c0c000badf80000000000002328 3903123456",
    hex"01020000 02080000000000000231 0a0800900000000103e8 0c0c000badd80000000000001f40 3b00 fdffff0206c1",
    hex"010a00000000000000000000 02080000000000000451 0a08012c0000006403e8 0c0c000bad740000000000001b58",
    hex"010a00000000000000000000 02080000000000000982 0a080062000002a301e9 0c0c000bad420000000000001770",
    hex"06204242424242424242424242424242424242424242424242424242424242424242 0c0c000bac480000000000001388",
  )

  val associatedData = Some(ByteVector32(hex"4242424242424242424242424242424242424242424242424242424242424242"))
}
