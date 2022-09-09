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
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshiLong, ShortChannelId, UInt64, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits._

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
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))

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
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))

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
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))
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

    val packet1 = FailurePacket.create(sharedSecrets.head, expected.failureMessage)
    assert(packet1.length == FailurePacket.PacketLength)

    val Success(decrypted1) = FailurePacket.decrypt(packet1, Seq(0).map(i => (sharedSecrets(i), publicKeys(i))))
    assert(decrypted1 == expected)

    val packet2 = FailurePacket.wrap(packet1, sharedSecrets(1))
    assert(packet2.length == FailurePacket.PacketLength)

    val Success(decrypted2) = FailurePacket.decrypt(packet2, Seq(1, 0).map(i => (sharedSecrets(i), publicKeys(i))))
    assert(decrypted2 == expected)

    val packet3 = FailurePacket.wrap(packet2, sharedSecrets(2))
    assert(packet3.length == FailurePacket.PacketLength)

    val Success(decrypted3) = FailurePacket.decrypt(packet3, Seq(2, 1, 0).map(i => (sharedSecrets(i), publicKeys(i))))
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
        FailurePacket.create(sharedSecrets.head, InvalidOnionPayload(UInt64(0), 0)),
        sharedSecrets(1)),
      sharedSecrets(2))

    assert(FailurePacket.decrypt(packet, Seq(0, 2, 1).map(i => (sharedSecrets(i), publicKeys(i)))).isFailure)
  }

  test("last node replies with a failure message (reference test vector)") {
    for ((payloads, packetPayloadLength) <- Seq(
      (referencePaymentPayloads, 1300),
      (paymentPayloadsFull, 1300),
      (trampolinePaymentPayloads, 400))) {
      // route: origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4

      // origin build the onion packet
      val Success(PacketAndSecrets(packet, sharedSecrets)) = create(sessionKey, packetPayloadLength, publicKeys, payloads, associatedData)

      // each node parses and forwards the packet
      // node #0
      val Right(DecryptedPacket(_, packet1, sharedSecret0)) = peel(privKeys(0), associatedData, packet)
      // node #1
      val Right(DecryptedPacket(_, packet2, sharedSecret1)) = peel(privKeys(1), associatedData, packet1)
      // node #2
      val Right(DecryptedPacket(_, packet3, sharedSecret2)) = peel(privKeys(2), associatedData, packet2)
      // node #3
      val Right(DecryptedPacket(_, packet4, sharedSecret3)) = peel(privKeys(3), associatedData, packet3)
      // node #4
      val Right(lastPacket@DecryptedPacket(_, _, sharedSecret4)) = peel(privKeys(4), associatedData, packet4)
      assert(lastPacket.isLastPacket)

      // node #4 want to reply with an error message
      val error = FailurePacket.create(sharedSecret4, TemporaryNodeFailure)
      assert(error == hex"a5e6bd0c74cb347f10cce367f949098f2457d14c046fd8a22cb96efb30b0fdcda8cb9168b50f2fd45edd73c1b0c8b33002df376801ff58aaa94000bf8a86f92620f343baef38a580102395ae3abf9128d1047a0736ff9b83d456740ebbb4aeb3aa9737f18fb4afb4aa074fb26c4d702f42968888550a3bded8c05247e045b866baef0499f079fdaeef6538f31d44deafffdfd3afa2fb4ca9082b8f1c465371a9894dd8c243fb4847e004f5256b3e90e2edde4c9fb3082ddfe4d1e734cacd96ef0706bf63c9984e22dc98851bcccd1c3494351feb458c9c6af41c0044bea3c47552b1d992ae542b17a2d0bba1a096c78d169034ecb55b6e3a7263c26017f033031228833c1daefc0dedb8cf7c3e37c9c37ebfe42f3225c326e8bcfd338804c145b16e34e4")
      // error sent back to 3, 2, 1 and 0
      val error1 = FailurePacket.wrap(error, sharedSecret3)
      assert(error1 == hex"c49a1ce81680f78f5f2000cda36268de34a3f0a0662f55b4e837c83a8773c22aa081bab1616a0011585323930fa5b9fae0c85770a2279ff59ec427ad1bbff9001c0cd1497004bd2a0f68b50704cf6d6a4bf3c8b6a0833399a24b3456961ba00736785112594f65b6b2d44d9f5ea4e49b5e1ec2af978cbe31c67114440ac51a62081df0ed46d4a3df295da0b0fe25c0115019f03f15ec86fabb4c852f83449e812f141a9395b3f70b766ebbd4ec2fae2b6955bd8f32684c15abfe8fd3a6261e52650e8807a92158d9f1463261a925e4bfba44bd20b166d532f0017185c3a6ac7957adefe45559e3072c8dc35abeba835a8cb01a71a15c736911126f27d46a36168ca5ef7dccd4e2886212602b181463e0dd30185c96348f9743a02aca8ec27c0b90dca270")

      val error2 = FailurePacket.wrap(error1, sharedSecret2)
      assert(error2 == hex"a5d3e8634cfe78b2307d87c6d90be6fe7855b4f2cc9b1dfb19e92e4b79103f61ff9ac25f412ddfb7466e74f81b3e545563cdd8f5524dae873de61d7bdfccd496af2584930d2b566b4f8d3881f8c043df92224f38cf094cfc09d92655989531524593ec6d6caec1863bdfaa79229b5020acc034cd6deeea1021c50586947b9b8e6faa83b81fbfa6133c0af5d6b07c017f7158fa94f0d206baf12dda6b68f785b773b360fd0497e16cc402d779c8d48d0fa6315536ef0660f3f4e1865f5b38ea49c7da4fd959de4e83ff3ab686f059a45c65ba2af4a6a79166aa0f496bf04d06987b6d2ea205bdb0d347718b9aeff5b61dfff344993a275b79717cd815b6ad4c0beb568c4ac9c36ff1c315ec1119a1993c4b61e6eaa0375e0aaf738ac691abd3263bf937e3")

      val error3 = FailurePacket.wrap(error2, sharedSecret1)
      assert(error3 == hex"aac3200c4968f56b21f53e5e374e3a2383ad2b1b6501bbcc45abc31e59b26881b7dfadbb56ec8dae8857add94e6702fb4c3a4de22e2e669e1ed926b04447fc73034bb730f4932acd62727b75348a648a1128744657ca6a4e713b9b646c3ca66cac02cdab44dd3439890ef3aaf61708714f7375349b8da541b2548d452d84de7084bb95b3ac2345201d624d31f4d52078aa0fa05a88b4e20202bd2b86ac5b52919ea305a8949de95e935eed0319cf3cf19ebea61d76ba92532497fcdc9411d06bcd4275094d0a4a3c5d3a945e43305a5a9256e333e1f64dbca5fcd4e03a39b9012d197506e06f29339dfee3331995b21615337ae060233d39befea925cc262873e0530408e6990f1cbd233a150ef7b004ff6166c70c68d9f8c853c1abca640b8660db2921")

      val error4 = FailurePacket.wrap(error3, sharedSecret0)
      assert(error4 == hex"9c5add3963fc7f6ed7f148623c84134b5647e1306419dbe2174e523fa9e2fbed3a06a19f899145610741c83ad40b7712aefaddec8c6baf7325d92ea4ca4d1df8bce517f7e54554608bf2bd8071a4f52a7a2f7ffbb1413edad81eeea5785aa9d990f2865dc23b4bc3c301a94eec4eabebca66be5cf638f693ec256aec514620cc28ee4a94bd9565bc4d4962b9d3641d4278fb319ed2b84de5b665f307a2db0f7fbb757366067d88c50f7e829138fde4f78d39b5b5802f1b92a8a820865af5cc79f9f30bc3f461c66af95d13e5e1f0381c184572a91dee1c849048a647a1158cf884064deddbf1b0b88dfe2f791428d0ba0f6fb2f04e14081f69165ae66d9297c118f0907705c9c4954a199bae0bb96fad763d690e7daa6cfda59ba7f2c8d11448b604d12d")

      // origin parses error packet and can see that it comes from node #4
      val Success(DecryptedFailurePacket(pubkey, failure)) = FailurePacket.decrypt(error4, sharedSecrets)
      assert(pubkey == publicKeys(4))
      assert(failure == TemporaryNodeFailure)
    }
  }

  test("intermediate node replies with an invalid onion payload length") {
    // The error will not be recoverable by the sender, but we must still forward it.
    val sharedSecret = ByteVector32(hex"4242424242424242424242424242424242424242424242424242424242424242")
    val errors = Seq(
      ByteVector.fill(FailurePacket.PacketLength - MacLength)(13),
      ByteVector.fill(FailurePacket.PacketLength + MacLength)(13)
    )

    for (error <- errors) {
      val wrapped = FailurePacket.wrap(error, sharedSecret)
      assert(wrapped.length == FailurePacket.PacketLength)
    }
  }

  test("intermediate node replies with a failure message (reference test vector)") {
    for ((payloads, packetPayloadLength) <- Seq(
      (referencePaymentPayloads, 1300),
      (paymentPayloadsFull, 1300),
      (trampolinePaymentPayloads, 400))) {
      // route: origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4

      // origin build the onion packet
      val Success(PacketAndSecrets(packet, sharedSecrets)) = create(sessionKey, packetPayloadLength, publicKeys, payloads, associatedData)

      // each node parses and forwards the packet
      // node #0
      val Right(DecryptedPacket(_, packet1, sharedSecret0)) = peel(privKeys(0), associatedData, packet)
      // node #1
      val Right(DecryptedPacket(_, packet2, sharedSecret1)) = peel(privKeys(1), associatedData, packet1)
      // node #2
      val Right(DecryptedPacket(_, _, sharedSecret2)) = peel(privKeys(2), associatedData, packet2)

      // node #2 want to reply with an error message
      val error = FailurePacket.create(sharedSecret2, InvalidRealm)

      // error sent back to 1 and 0
      val error1 = FailurePacket.wrap(error, sharedSecret1)
      val error2 = FailurePacket.wrap(error1, sharedSecret0)

      // origin parses error packet and can see that it comes from node #2
      val Success(DecryptedFailurePacket(pubkey, failure)) = FailurePacket.decrypt(error2, sharedSecrets)
      assert(pubkey == publicKeys(2))
      assert(failure == InvalidRealm)
    }
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
    val (blindingOverride, blindedRouteEnd) = {
      val sessionKey = PrivateKey(hex"0101010101010101010101010101010101010101010101010101010101010101")
      val blindedRoute = RouteBlinding.create(sessionKey, Seq(dave, eve).map(_.publicKey), Seq(davePayload, evePayload))
      assert(blindedRoute.blindingKey == PublicKey(hex"031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"))
      (blindedRoute.blindingKey, blindedRoute)
    }

    // Bob also wants to use route blinding:
    val (blinding, blindedRouteStart) = {
      val sessionKey = PrivateKey(hex"0202020202020202020202020202020202020202020202020202020202020202")
      val blindedRoute = RouteBlinding.create(sessionKey, Seq(bob, carol).map(_.publicKey), Seq(bobPayload, carolPayload))
      assert(blindedRoute.blindingKey == PublicKey(hex"024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766"))
      (blindedRoute.blindingKey, blindedRoute)
    }

    // We now have a blinded route Bob -> Carol -> Dave -> Eve
    val blindedRoute = BlindedRoute(bob.publicKey, blinding, blindedRouteStart.blindedNodes ++ blindedRouteEnd.blindedNodes)
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

    // Every node in the route is able to decrypt its payload and extract the blinding point for the next node:
    {
      // Bob (the introduction point) can decrypt its encrypted payload and obtain the next ephemeral public key.
      val Success((payload0, ephKey1)) = RouteBlinding.decryptPayload(bob, blindedRoute.blindingKey, blindedRoute.encryptedPayloads(0))
      assert(payload0 == bobPayload)
      assert(ephKey1 == PublicKey(hex"034e09f450a80c3d252b258aba0a61215bf60dda3b0dc78ffb0736ea1259dfd8a0"))

      // Carol can derive the private key used to unwrap the onion and decrypt its encrypted payload.
      assert(RouteBlinding.derivePrivateKey(carol, ephKey1).publicKey == blindedRoute.blindedNodeIds(1))
      val Success((payload1, ephKey2)) = RouteBlinding.decryptPayload(carol, ephKey1, blindedRoute.encryptedPayloads(1))
      assert(payload1 == carolPayload)
      assert(ephKey2 == PublicKey(hex"03af5ccc91851cb294e3a364ce63347709a08cdffa58c672e9a5c587ddd1bbca60"))
      // NB: Carol finds a blinding override and will transmit that instead of ephKey2 to the next node.
      assert(payload1.containsSlice(blindingOverride.value))

      // Dave must be given the blinding override to derive the private key used to unwrap the onion and decrypt its encrypted payload.
      assert(RouteBlinding.decryptPayload(dave, ephKey2, blindedRoute.encryptedPayloads(2)).isFailure)
      assert(RouteBlinding.derivePrivateKey(dave, blindingOverride).publicKey == blindedRoute.blindedNodeIds(2))
      val Success((payload2, ephKey3)) = RouteBlinding.decryptPayload(dave, blindingOverride, blindedRoute.encryptedPayloads(2))
      assert(payload2 == davePayload)
      assert(ephKey3 == PublicKey(hex"03e09038ee76e50f444b19abf0a555e8697e035f62937168b80adf0931b31ce52a"))

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
        // The sender includes the blinding key and the first encrypted recipient data in the introduction node's payload.
        TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.BlindingPoint(blinding), OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads(0))),
        // The sender includes the correct encrypted recipient data in each blinded node's payload.
        TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads(1))),
        TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads(2))),
        TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.AmountToForward(100_000 msat), OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(749000)), OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads(3))),
      ).map(tlvs => PaymentOnionCodecs.perHopPayloadCodec.encode(tlvs).require.bytes)

      val nodeIds = Seq(alice, bob).map(_.publicKey) ++ blindedRoute.blindedNodeIds.tail
      val Success(PacketAndSecrets(onion, sharedSecrets)) = create(sessionKey, 1300, nodeIds, payloads, associatedData)
      assert(serializePaymentOnion(onion) == hex"0002531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe337dadf610256c6ab518495dce9cdedf9391e21a71daddfe667387c384267a4c6453777590fc38e591b4f04a1e96bd1dec4af605d6adda2690de4ebe5d56ad2013b520af2a3c49316bc590ee83e8c31b1eb11ff766dad27ca993326b1ed582fb451a2ad87fbf6601134c6341c4a2deb6850e25a355be68dbb6923dc89444fdd74a0f700433b667bda345926099f5547b07e97ad903e8a01566a78ae177366239e793dac719de805565b6d0a942f42722a79dba29ebf4f9ec40cf579191716aac3a79f78c1d43398fba3f304786435976102a924ba4ba3de6150c829ce01c25428f2f5d05ef023be7d590ecdf6603730db3948f80ca1ed3d85227e64ef77200b9b557f427b6e1073cfa0e63e4485441768b98ab11ba8104a6cee1d7af7bb9d3167503ea010fabcd207b0b37a68b84be55663802d96faee291e8241b5e6c4b38e0c6d17ef6ba7bbe93f02046975bb01b7f766fcfc5a755af11a90cc7eb3505986b56e07a7855534d03b79f0dfbfe645b0d6d4185c038771fd25b800aa26b2ed2e30b1e713659468618a2fea04fcd04732a6fa9e77db73d0efa5253e123d5c2306ddb9ebf7bc897b559cf9870715039c6183082d762b6417f99d0f71ff7c060f6b564ad6827edaffa72eefcc4ce633a8da8d41c19d8f6aebd8878869eb518ccc16dccae6a94c690957598ce0295c1c46af5d7a2f0955b5400526bfd1430f554562614b5d00feff3946427be520cce52bfe9b6a9c2b1da6701c8ca628a69d6d40e20dd69d6e879d7a052d9c16f52c26e3bf745daeb3578c211475f2953e3c42308af89f3fd3c93bb4ba7320b35721bfdf2ad3db94b711fbdccdbe8465d9ff7bc9a293861dcea15bfa4f64993e9a751f571ab24a3219446483968821aa19a8d89ec611d686ff5f8fdc340aa8185ae29b01e60fb5a4c5c4bf8054c711522fc74e1d60976c33d2dfd782bbd555b8d06af6e688b3f541f1275706d045c607eea5926c49ced5bd368914f5ef793c3d6c1ab08dae689f0d71d64ec9c136cd38ac038cfa37846e3df7ce4bf63f44fce412bf3c9b8f21eabc34186a9c660b23fb7f3fa26cc9d830b40b499c613c2569d5e5f10823854471d3ac8bf655b020c37309fbaa0d0af5f14babd9485347ccd891bbd1e3b73e800c500be25073ee8a3844aca1cb9fa06d5579532da09a480cbec171b2ca9f83985d1a8cf60092fedaa88d4ccc711243298beb3d9d46c87542072aebb33d5a5ee671d4974b93c901eb1b5b4eaefc3669a7daa5154dced8cdc1bf49c1ba829bcbdee4e1f2f703c983872a7bff0669c9322c13a7cfb3f7f98b7ddcb47042a4786368a182f9c667d495438b6dee2d2a6ad0f8795ac499c3c3e9d584f6cf8279497fecc51c9203510858d738cec815a13d35d220ea297333068d8b64f4bcb627d127ab1e7732c840da45d35647e9e319bac2e95bb49f070e32772e2a8a6b55ca35d2391de4269cd6c5030203ab14abfca973a032b6ce10e958f1be2399c98ee70da0363c2f9a4e52546d8eef0b63cbab415a9341dbb9099df5e1ba2a83c2be15a96518741eacbe0f5d45e81ed5ddb76438a45cc5bb8d87abba0dd8c9181eff8b1f7c3939f3600883a3139515c53a07429247db278384d727d9b3b327c0f47dd4319d12e24ac2713f8c828217491df60f5b002cc58476a7b857dffb148179ffa5c62060d26dc3a9df11beccf77929e5d752d7351e58dc7f5265946792e7733886240efa0994868aa28a66754dccee99abd37a78558c858ddc9ca52aee32e263dd5165cdaf8ff74dfa9b61506af68b2fc9c0b887d3e49cc534040221f72fe6ec705e3964ad1e6d686840dd821c7a386baecb841369c98f5b493820be03c3b726cba925c72b05ea3d1b")

      // Alice can decrypt the onion as usual.
      val Right(DecryptedPacket(onionPayloadAlice, packetForBob, sharedSecretAlice)) = peel(alice, associatedData, onion)

      // Bob is the introduction node.
      // He can decrypt the onion as usual, but the payload doesn't contain a shortChannelId or a nodeId to forward to.
      // However it contains a blinding point and encrypted data, which he can decrypt to discover the next node.
      val Right(DecryptedPacket(onionPayloadBob, packetForCarol, sharedSecretBob)) = peel(bob, associatedData, packetForBob)
      val tlvsBob = PaymentOnionCodecs.perHopPayloadCodec.decode(onionPayloadBob.bits).require.value
      assert(tlvsBob.get[OnionPaymentPayloadTlv.BlindingPoint].map(_.publicKey).contains(blinding))
      assert(tlvsBob.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)

      val Right(decryptedPayloadBob) = RouteBlindingEncryptedDataCodecs.decode(bob, blinding, tlvsBob.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data)
      val blindingEphemeralKeyForCarol = decryptedPayloadBob.nextBlinding
      val Right(payloadBob) = PaymentOnion.IntermediatePayload.ChannelRelay.Blinded.validate(tlvsBob, decryptedPayloadBob.tlvs, blindingEphemeralKeyForCarol)
      assert(payloadBob.outgoingChannelId == ShortChannelId(1))
      assert(payloadBob.amountToForward(110_125 msat) == 100_125.msat)
      assert(payloadBob.outgoingCltv(CltvExpiry(749150)) == CltvExpiry(749100))
      assert(payloadBob.paymentRelay == RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(50), 0, 10_000 msat))
      assert(payloadBob.paymentConstraints == RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750150), 50 msat))
      assert(payloadBob.allowedFeatures.isEmpty)

      // Carol is a blinded hop.
      // She receives the blinding key from Bob (e.g. in a tlv field in update_add_htlc) which she can use to derive the
      // private key corresponding to her blinded node ID and decrypt the onion.
      // The payload doesn't contain a shortChannelId or a nodeId to forward to, but the encrypted data does.
      val blindedPrivKeyCarol = RouteBlinding.derivePrivateKey(carol, blindingEphemeralKeyForCarol)
      val Right(DecryptedPacket(onionPayloadCarol, packetForDave, sharedSecretCarol)) = peel(blindedPrivKeyCarol, associatedData, packetForCarol)
      val tlvsCarol = PaymentOnionCodecs.perHopPayloadCodec.decode(onionPayloadCarol.bits).require.value
      assert(tlvsCarol.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
      val Right(decryptedPayloadCarol) = RouteBlindingEncryptedDataCodecs.decode(carol, blindingEphemeralKeyForCarol, tlvsCarol.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data)
      val blindingEphemeralKeyForDave = decryptedPayloadCarol.nextBlinding
      val Right(payloadCarol) = PaymentOnion.IntermediatePayload.ChannelRelay.Blinded.validate(tlvsCarol, decryptedPayloadCarol.tlvs, blindingEphemeralKeyForDave)
      assert(payloadCarol.outgoingChannelId == ShortChannelId(2))
      assert(payloadCarol.amountToForward(100_125 msat) == 100_010.msat)
      assert(payloadCarol.outgoingCltv(CltvExpiry(749100)) == CltvExpiry(749025))
      assert(payloadCarol.paymentRelay == RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(75), 150, 100 msat))
      assert(payloadCarol.paymentConstraints == RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750100), 50 msat))
      assert(payloadCarol.allowedFeatures.isEmpty)
      // Carol's payload contains a blinding override.
      val blindingEphemeralKeyForDaveOverride = payloadCarol.blindedRecords.get[RouteBlindingEncryptedDataTlv.NextBlinding].map(_.blinding)
      assert(blindingEphemeralKeyForDaveOverride.contains(blindingOverride))
      assert(blindingEphemeralKeyForDave == blindingOverride)

      // Dave is a blinded hop.
      // He receives the blinding key from Carol (e.g. in a tlv field in update_add_htlc) which he can use to derive the
      // private key corresponding to his blinded node ID and decrypt the onion.
      val blindedPrivKeyDave = RouteBlinding.derivePrivateKey(dave, blindingOverride)
      val Right(DecryptedPacket(onionPayloadDave, packetForEve, sharedSecretDave)) = peel(blindedPrivKeyDave, associatedData, packetForDave)
      val tlvsDave = PaymentOnionCodecs.perHopPayloadCodec.decode(onionPayloadDave.bits).require.value
      assert(tlvsDave.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
      val Right(decryptedPayloadDave) = RouteBlindingEncryptedDataCodecs.decode(dave, blindingOverride, tlvsDave.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data)
      val blindingEphemeralKeyForEve = decryptedPayloadDave.nextBlinding
      val Right(payloadDave) = PaymentOnion.IntermediatePayload.ChannelRelay.Blinded.validate(tlvsDave, decryptedPayloadDave.tlvs, blindingEphemeralKeyForEve)
      assert(payloadDave.outgoingChannelId == ShortChannelId(3))
      assert(payloadDave.amountToForward(100_010 msat) == 100_000.msat)
      assert(payloadDave.outgoingCltv(CltvExpiry(749025)) == CltvExpiry(749000))
      assert(payloadDave.paymentRelay == RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(25), 100, 0 msat))
      assert(payloadDave.paymentConstraints == RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750025), 50 msat))
      assert(payloadDave.allowedFeatures.isEmpty)

      // Eve is the blinded recipient.
      // She receives the blinding key from Dave (e.g. in a tlv field in update_add_htlc) which she can use to derive
      // the private key corresponding to her blinded node ID and decrypt the onion.
      val blindedPrivKeyEve = RouteBlinding.derivePrivateKey(eve, blindingEphemeralKeyForEve)
      val Right(DecryptedPacket(onionPayloadEve, packetForNobody, sharedSecretEve)) = peel(blindedPrivKeyEve, associatedData, packetForEve)
      val tlvsEve = PaymentOnionCodecs.perHopPayloadCodec.decode(onionPayloadEve.bits).require.value
      assert(tlvsEve.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
      val Right(decryptedPayloadEve) = RouteBlindingEncryptedDataCodecs.decode(eve, blindingEphemeralKeyForEve, tlvsEve.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data)
      val Right(payloadEve) = PaymentOnion.FinalPayload.Blinded.validate(tlvsEve, decryptedPayloadEve.tlvs)
      assert(payloadEve.pathId_opt.contains(hex"c9cf92f45ade68345bc20ae672e2012f4af487ed4415"))
      assert(payloadEve.paymentConstraints == RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750000), 50 msat))
      assert(payloadEve.allowedFeatures.isEmpty)

      assert(Seq(onionPayloadAlice, onionPayloadBob, onionPayloadCarol, onionPayloadDave, onionPayloadEve) == payloads)
      assert(Seq(sharedSecretAlice, sharedSecretBob, sharedSecretCarol, sharedSecretDave, sharedSecretEve) == sharedSecrets.map(_._1))

      val packets = Seq(packetForBob, packetForCarol, packetForDave, packetForEve, packetForNobody)
      assert(packets(0).hmac == ByteVector32(hex"0b462fb9321df3f139d2efccdc54471840e5cb50b4f7dae44df9c8c3e5ffabde"))
      assert(packets(1).hmac == ByteVector32(hex"5c7b8d4f3061b3e58194edfb76ac339932c61ff77b024192508c9628a0206bb7"))
      assert(packets(2).hmac == ByteVector32(hex"6a8df602e649e459b456df92327d7cf28132b735d38d3692c7c199e27d298c85"))
      assert(packets(3).hmac == ByteVector32(hex"6db2bc62c58cd931570f8b7eb13b96e40b8a34e13655eb4f4b3a3ec87824403d"))
      assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
    }
  }

  test("invalid blinded route") {
    val encryptedPayloads = RouteBlinding.create(sessionKey, publicKeys, routeBlindingPayloads).encryptedPayloads
    // Invalid node private key:
    val ephKey0 = sessionKey.publicKey
    assert(RouteBlinding.decryptPayload(privKeys(1), ephKey0, encryptedPayloads(0)).isFailure)
    // Invalid unblinding ephemeral key:
    assert(RouteBlinding.decryptPayload(privKeys(0), randomKey().publicKey, encryptedPayloads(0)).isFailure)
    // Invalid encrypted payload:
    assert(RouteBlinding.decryptPayload(privKeys(0), ephKey0, encryptedPayloads(1)).isFailure)
  }

}

object SphinxSpec {

  def serializePaymentOnion(onion: OnionRoutingPacket): ByteVector =
    PaymentOnionCodecs.paymentOnionPacketCodec.encode(onion).require.toByteVector

  def serializeTrampolineOnion(onion: OnionRoutingPacket): ByteVector =
    PaymentOnionCodecs.trampolineOnionPacketCodec.encode(onion).require.toByteVector

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
