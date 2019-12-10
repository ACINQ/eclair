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

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{UInt64, wire}
import org.scalatest.FunSuite
import scodec.bits._

import scala.util.Success

/**
 * Created by fabrice on 10/01/17.
 */
class SphinxSpec extends FunSuite {

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

  test("generate filler with fixed-size payloads (reference test vector)") {
    val (_, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = PaymentPacket.generateFiller("rho", sharedsecrets.dropRight(1), referenceFixedSizePayloads.dropRight(1))
    assert(filler == hex"c6b008cf6414ed6e4c42c291eb505e9f22f5fe7d0ecdd15a833f4d016ac974d33adc6ea3293e20859e87ebfb937ba406abd025d14af692b12e9c9c2adbe307a679779259676211c071e614fdb386d1ff02db223a5b2fae03df68d321c7b29f7c7240edd3fa1b7cb6903f89dc01abf41b2eb0b49b6b8d73bb0774b58204c0d0e96d3cce45ad75406be0bc009e327b3e712a4bd178609c00b41da2daf8a4b0e1319f07a492ab4efb056f0f599f75e6dc7e0d10ce1cf59088ab6e873de377343880f7a24f0e36731a0b72092f8d5bc8cd346762e93b2bf203d00264e4bc136fc142de8f7b69154deb05854ea88e2d7506222c95ba1aab065c8a851391377d3406a35a9af3ac")
  }

  test("generate filler with variable-size payloads") {
    val (_, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = PaymentPacket.generateFiller("rho", sharedsecrets.dropRight(1), referenceVariableSizePayloads.dropRight(1))
    assert(filler == hex"b77d99c935d3f32469844f7e09340a91ded147557bdd0456c369f7e449587c0f5666faab58040146db49024db88553729bce12b860391c29c1779f022ae48a9cb314ca35d73fc91addc92632bcf7ba6fd9f38e6fd30fabcedbd5407b6648073c38331ee7ab0332f41f550c180e1601f8c25809ed75b3a1e78635a2ef1b828e92c9658e76e49f995d72cf9781eec0c838901d0bdde3ac21c13b4979ac9e738a1c4d0b9741d58e777ad1aed01263ad1390d36a18a6b92f4f799dcf75edbb43b7515e8d72cb4f827a9af0e7b9338d07b1a24e0305b5535f5b851b1144bad6238b9d9482b5ba6413f1aafac3cdde5067966ed8b78f7c1c5f916a05f874d5f17a2b7d0ae75d66a5f1bb6ff932570dc5a0cf3ce04eb5d26bc55c2057af1f8326e20a7d6f0ae644f09d00fac80de60f20aceee85be41a074d3e1dda017db79d0070b99f54736396f206ee3777abd4c00a4bb95c871750409261e3b01e59a3793a9c20159aae4988c68397a1443be6370fd9614e46108291e615691729faea58537209fa668a172d066d0efff9bc77c2bd34bd77870ad79effd80140990e36731a0b72092f8d5bc8cd346762e93b2bf203d00264e4bc136fc142de8f7b69154deb05854ea88e2d7506222c95ba1aab065c8a")
  }

  test("peek at per-hop payload length") {
    val testCases = Map(
      34 -> hex"01",
      41 -> hex"08",
      65 -> hex"00",
      285 -> hex"fc",
      288 -> hex"fd00fd",
      65570 -> hex"fdffff"
    )

    for ((expected, payload) <- testCases) {
      assert(peekPayloadLength(payload) === expected)
    }
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
      assert(packet.isLastPacket === expected)
    }
  }

  test("bad onion") {
    val badOnions = Seq[wire.OnionRoutingPacket](
      wire.OnionRoutingPacket(1, ByteVector.fill(33)(0), ByteVector.fill(65)(1), ByteVector32.Zeroes),
      wire.OnionRoutingPacket(0, ByteVector.fill(33)(0), ByteVector.fill(65)(1), ByteVector32.Zeroes),
      wire.OnionRoutingPacket(0, publicKeys.head.value, ByteVector.fill(42)(1), ByteVector32(hex"2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a2a"))
    )

    val expected = Seq[BadOnion](
      InvalidOnionVersion(ByteVector32(hex"2f89b15c6cb0bb256d7a71b66de0d50cd3dd806f77d1cc1a3b0d86a0becd28ce")),
      InvalidOnionKey(ByteVector32(hex"d2602c65fc331d6ae728331ae50e602f35929312ca7a951dc5ce250031b6b999")),
      InvalidOnionHmac(ByteVector32(hex"3c01a86e6bc51b44a2718745fbbbc71a5c5dde5f46a489da17046c9d097bb303"))
    )

    for ((packet, expected) <- badOnions zip expected) {
      val Left(onionErr) = PaymentPacket.peel(privKeys.head, associatedData, packet)
      assert(onionErr === expected)
    }
  }

  test("create packet with fixed-size payloads (reference test vector)") {
    val PacketAndSecrets(onion, sharedSecrets) = PaymentPacket.create(sessionKey, publicKeys, referenceFixedSizePayloads, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71d215fe077311013884fabc09318c23a2435069ff1c40e90d2c150084eb8eb59ed10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf03865979313da96e66517a37b5c485744275a73b8eab6f43e3d56a2a59764eb12617efbe0ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d3a3451e1a7368bb5edac5c665a61f6796b288bc1f12cd3b9fbaf915bc732ee749a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b04040caeaeed42e3eb4adf6146661e3708accfeeb02d174de7effeaa8040417c8b49ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565ac90cc68a2cfd94a8a93dc787d6a074e539903b1d36e8d5d45655aa59208a20f3f600ef6b05d963ab796cdf33748c944795403626cdff6c0f114586f1754fb6d64800beeda3e18c04f109b7a91c9049ba2967316f088a8b45c57ea708768ed1cc99f06886ee3089d6c8e9ea3ce884b8dfffed4d4cdc425bf4a8740d03cfcdc95861300a03f5913bf284daa96b9c81dac1ec47cd72bd1049256f41d3710bb47d6151bc6d2dca3851363b4c499f53cbfd445bbd11e669f6b868b35b2e2cc950b1dcde4517b548066ac7543d9f9ac78393b61d1df632c1e7e048427d36227a51f795049c9b89cfdf25b9982e8d79f0da84caf54f7904e8731af45bcbf558eeefd885e2907ce00a3f2124421a704c1907a966e26215b26d2fa7558406a767c0e7218e1f9ac26b012971eba585be112b4e84abbab93a6d76f626b744c1f86df334dcc5d5277b2ce89e0c9869be3fd952ccfac0d3ab40eecd8dd5356155a8b0b186e650b9bbac561a9b5f309b18d31d3bfdd6dac9169b7e38f82b300e3598eb142fb5dd99612d3cd8e1f720551142f5460736e6da0d1158b238a626afa86076c64851694651d2bf6808de85104ef995ddc159d2efbe67c50a2462c10864400bf01ef387d63dd06b21ddebc4ddfb12b9ff2ad2816bcabf737ed8e89f8749a5179ab26faf5796c80b2364af41dc0384507b160c1e7bd93cc6062d82fb87572b9822f1400222c9517a5f4a89fe8fbe39b54efcca1d40bc8627d6b1f43e621c3297ea5ab9076824d90cfb86e5025aad6160cbff1d62c1e46d86a43dce76e09bc1833def9deeb42b02e96b2e423c3acf305e02d4c2c942fddcba269ffcdaef2b4dd6514b427e3236cee79bc28a26adf83af2137a9c7c6453a8571a65d86146c6a93fea20fe91d64f4e3eda8c1980d68e4d9d22a8dd20d5b5d36abda9a7a32bc0711802bd68a19a5837d6e45cf21ae674e291f9ab6846c9c0440017b47630f67cba07a90bfc98b4acd9bef2f8b379357f0e91c3dd04ee819214cfb4f50e35e9a6428d072c09240bb550b225ad312698a827da5fad2f1ae11a474d02872a3554822ff12045b230276f3ad5bd57e5b1e71705076ce6c234b32543cd86a8bec4a1da37b02ffeceb4ebc98311c2c1938458ce8e390e8b702032efc2d30326ffd9a16bbee7cd198b442e22a5210152c6b08c13972b50f5c4d3fd8e393d752463b9192f66cb4be243737ae167c044e4517b4d5efd6af68ab040477acd144fbd42d60725ed2a3e0d83af620e800c6c55e628bef178d4490bc8fa0e0be00f309183c75a402fa501ec8253530199d74bd360932281c3cc5a5d33906e695c927f49b169604959aec1978a369784bdeaecfb79281996b1fc377841ac73e62f2cc382a1356e06f2762f9cdbcec7c7771037e824b0c1071f4f93d614")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = PaymentPacket.peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = PaymentPacket.peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = PaymentPacket.peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = PaymentPacket.peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) = PaymentPacket.peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == referenceFixedSizePayloads)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == ByteVector32(hex"9350c058fe33b70d9cea125d2d68ba02d81af4609c916d51a575c8db2d5f1de5"))
    assert(packets(1).hmac == ByteVector32(hex"ef3860bc4d742ca9a6c2818d03c83f034d5c22776232a87ca049ec79b63627b9"))
    assert(packets(2).hmac == ByteVector32(hex"22b8aa7917b4e2c87fc5678bab10b450454fbb5e70708c3d49f0670a616085ea"))
    assert(packets(3).hmac == ByteVector32(hex"f4bc4473ec0a55d5f3ededdba4a8e303f9128391a0f1f54d8adacf2221c04e1b"))
    // this means that node #4 is the last node
    assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("create packet with variable-size payloads (reference test vector)") {
    val PacketAndSecrets(onion, sharedSecrets) = PaymentPacket.create(sessionKey, publicKeys, referenceVariableSizePayloads, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71068850554818be051c5fb6e47df0f24ab88623abf089afc95db4f653cfdc4e3fc50f7d61ab590531cf08000178a333a347f8b4072ecac76d4ab27f190828a68222a6bb6c6f23fc671295d5d4fe37b9f3dacaa629d6404bae9a8e8d7178977d7094a1ae549f89338c0777551f874159eb42d3a59fb9285ad4e24883f27de23942ec966611e99bee1cee503455be9e8e642cef6cef7b9864130f692283f8a973d47a8f1c1726b6e59969385975c766e35737c8d76388b64f748ee7943ffb0e2ee45c57a1abc40762ae598723d21bd184e2b338f68ebff47219357bd19cd7e01e2337b806ef4d717888e129e59cd3dc31e6201ccb2fd6d7499836f37a993262468bcb3a4dcd03a22818aca49c6b7b9b8e9e870045631d8e039b066ff86e0d1b7291f71cefa7264c70404a8e538b566c17ccc5feab231401e6c08a01bd5edfc1aa8e3e533b96e82d1f91118d508924b923531929aea889fcdf0568b9af020955bf28f2296f547bec0773d4f76e19351ceda5ad381e6a79b57a5a658149e7e9ced4edde5d38c9b8f92e16f6b4ab13d704721bb12be5941e1b6620be32870100cb1000cf05b7562be8dbfb7fdb4da45b2f3bca8e6fe47eb45fbdd3be21a8a8d200797eae3c9a0497132f92410d804977408494dff49dd3d8bce248e0b74fd9e6f0f7102c25ddfa02bd9ad9f746abbfa337cea1082d8ae639b52346b18c05b8248d9b30df256d432ab365b732a58b04d7d0d6f6dd20b810c52b2457544c629ca0911c35ff1292523f980f13ebd87d1627e9759abf8b17e54955835fa70c3d9217d21dd2a7e33bea072be9170265c9465d5b4c54a1df3c15513332dfb2123c27498af3bfa116a140d28d43661a3088c6f2ae995bd921801d43ee31a353f2e164704b1593136e84815e2d304515ddf45ef3f70b2169718c811fbe697a1c0d97032e83dec858b6672be484244127e23e2ca8fa055e8e34165c3d02219fb588c7a221722e45e2b54f6b9fa4a6013906198cdf72c8d5222155cfc64f1f322f7785d84727fdd2f9b66627cbe8c7ecf5d944ffcb9266ac815ebc4b4301ed6bc1a765bfeade304cac32e5841340ded04f1f9ea4e2fa7ceea533f8b5253a940f842176c672443af37f03a03f7219df1c4062c51e6e79cf859d07eece8cb7583643e698cf76c04cc6f4c01ca9378046e1763818c616c54f640b26a424fbc0b37f6765f57ce1ade6c8c1eef6ab12df9fb6df6125d2d8bb665a4033d1386525eebc5af407155a5bbc9f8719b836f58044a8f5d1256ca627a592c0419af0bbe4886b9f97667c1bb1712f003f9ac8c53e5ceeaa2182260bab8f5ea50951529c4bda2e58ac649bff4447b585e07f8ca3e35379e969dc71f015557fc588eef1282f7da45f57d94f9316898c904217c3a39ceb2e20c4878c6c196ad11aee10b7ed0b047220930aa1d6463d60058dbf1c36f12eb767d5dc09b9c9d1df628766a4de995fa7e85040533087133ba8892b2cd207da65b99e5d95575e4d7d5ba2666de8f9d5582c1f8799e43e25aa1ad7b945bfb97a953e09a9341c49d1346901af9eb30486bd270d750493dfb69b467a24757e5b3a7198f6794549725bccdc69303f6ccaa48f825e5f83d42faba8f32895148c18db0ec3f6411b969674a507d39a4cb723bba876b766bc2219b164a0f32fd9a8c198695343cbc7c48dda1d36ce69efd07a3d7d46275583e50b9fd1a6d95ce3454da8fe754ab58ee4b5ed48a020f514287e900076d389d6266c627f1e0e22a4b511dfa5c4891eac4d8fe7e9f0332cc26af9a5f467b4acea6da597f27fbb58511ffb8a200d68f09aa9e83b401dc012")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = PaymentPacket.peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = PaymentPacket.peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = PaymentPacket.peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = PaymentPacket.peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) = PaymentPacket.peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == referenceVariableSizePayloads)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == ByteVector32(hex"47cd6e0ac53a0830044f18b061146bea23ccbe3470582b95d4d43e0c090de644"))
    assert(packets(1).hmac == ByteVector32(hex"eba36d0ad9a022f810c315b15a599a350b9fe8c06f5f0237132bbf616d4cff9f"))
    assert(packets(2).hmac == ByteVector32(hex"274d625f475dd2d689a883acd3a11df53b547bb786e5cfa791fb5f8b18a434f0"))
    assert(packets(3).hmac == ByteVector32(hex"5a7c27a6f4cdf576f3787e3d81d07b6c13f2b683339ac3772a03e0b9d405483a"))
    assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("create packet with variable-size payloads filling the onion") {
    val PacketAndSecrets(onion, sharedSecrets) = PaymentPacket.create(sessionKey, publicKeys, variableSizePayloadsFull, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866196ef84350c2a76fc232b5d46d421e9615471ab9e0bc887beff8c95fdb878f7b3a7141453e5f8d22b6101810ae541ce499a09b4a9d9f80d1845c8960c85fc6d1a87bf74b2ce49922898e9353fa268086c00ae8b7f718405b72ad3829dbb38c85e02a00427eb4bdbda8fcd42b44708a9efde49cf776b75ebb389bf84d0bfbf58590e510e034572a01e409c309396778760423a8d8754c52e9a01a8f0e271cba5068bab5ee5bd0b5cd98276b0e04d60ba6a0f6bafd75ff41903ab352a1f47586eae3c6c8e437d4308766f71052b46ba2efbd87c0a781e8b3f456300fc7efbefc78ab515338666aed2070e674143c30b520b9cc1782ba8b46454db0d4ce72589cfc2eafb2db452ec98573ad08496483741de5376bfc7357fc6ea629e31236ba6ba7703014959129141a1719788ec83884f2e9151a680e2a96d2bcc67a8a2935aa11acee1f9d04812045b4ae5491220313756b5b9a0a6f867f2a95be1fab14870f04eeab694d9594620632b14ec4b424b495914f3dc587f75cd4582c113bb61e34a0fa7f79f97463be4e3c6fb99516889ed020acee419bb173d38e5ba18a00065e11fd733cf9ae46505dbb4ef70ef2f502601f4f6ee1fdb9d17435e15080e962f24760843f35bac1ac079b694ff7c347c1ed6a87f02b0758fbf00917764716c68ed7d6e6c0e75ccdb6dc7fa59554784b3ad906127ea77a6cdd814662ee7d57a939e28d77b3da47efc072436a3fd7f9c40515af8c4903764301e62b57153a5ca03ff5bb49c7dc8d3b2858100fb4aa5df7a94a271b73a76129445a3ea180d84d19029c003c164db926ed6983e5219028721a294f145e3fcc20915b8a2147efc8b5d508339f64970feee3e2da9b9c9348c1a0a4df7527d0ae3f8ae507a5beb5c73c2016ecf387a3cd8b79df80a8e9412e707cb9c761a0809a84c606a779567f9f0edf685b38c98877e90d02aedd096ed841e50abf2114ce01efbff04788fb280f870eca20c7ec353d5c381903e7d08fc57695fd79c27d43e7bd603a876068d3f1c7f45af99003e5eec7e8d8c91e395320f1fc421ef3552ea033129429383304b760c8f93de342417c3223c2112a623c3514480cdfae8ec15a99abfca71b03a8396f19edc3d5000bcfb77b5544813476b1b521345f4da396db09e783870b97bc2034bd11611db30ed2514438b046f1eb7093eceddfb1e73880786cd7b540a3896eaadd0a0692e4b19439815b5f2ec855ec8ececce889442a64037e956452a3f7b86cb3780b3e316c8dde464bc74a60a85b613f849eb0b29daf81892877bd4be9ba5997fc35544d3c2a00e5e1f45dc925607d952c6a89721bd0b6f6aec03314d667166a5b8b18471403be7018b2479aaef6c7c6c554a50a98b717dff06d50be39fb36dc03e678e0a52fc615be46b223e3bee83fa0c7c47a1f29fb94f1e9eebf6c9ecf8fc79ae847df2effb60d07aba301fc536546ec4899eedb4fec9a9bed79e3a83c4b32757745778e977e485c67c0f12bbc82c0b3bb0f4df0bd13d046fed4446f54cd85bfce55ef781a80e5f63d289d08de001237928c2a4e0c8694d0c1e68cc23f2409f30009019085e831a928e7bc5b00a1f29d25482f7fd0b6dad30e6ef8edc68ddf7db404ea7d11540fc2cee74863d64af4c945457e04b7bea0a5fb8636edadb1e1d6f2630d61062b781c1821f46eddadf269ea1fada829547590081b16bc116e074cae0224a375f2d9ce16e836687c89cd285e3b40f1e59ce2caa3d1d8cf37ee4d5e3abe7ef0afd6ffeb4fd6905677b950894863c828ab8d93519566f69fa3c2129da763bf58d9c4d2837d4d9e13821258f7e7098b34f695a589bd9eb568ba51ee3014b2d3ba1d4cf9ebaed0231ed57ecea7bd918216")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = PaymentPacket.peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = PaymentPacket.peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = PaymentPacket.peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = PaymentPacket.peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) = PaymentPacket.peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == variableSizePayloadsFull)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == ByteVector32(hex"859cd694cf604442547246f4fae144f255e71e30cb366b9775f488cac713f0db"))
    assert(packets(1).hmac == ByteVector32(hex"259982a8af80bd3b8018443997fa5f74c48b488fff62e531be54b887d53fe0ac"))
    assert(packets(2).hmac == ByteVector32(hex"58110c95368305b73ae15d22b884fda0482c60993d3ba4e506e37ff5021efb13"))
    assert(packets(3).hmac == ByteVector32(hex"f45e7099e32b8973f54cbfd1f6c48e7e0b90718ad7b00a88e1e98cebeb6d3916"))
    assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("create packet with single variable-size payload filling the onion") {
    val PacketAndSecrets(onion, _) = PaymentPacket.create(sessionKey, publicKeys.take(1), variableSizeOneHopPayload, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661918f5b235c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a7141453e5f8d22b6351810ae541ce499a09b4a9d9f80d1845c8960c85fc6d1a87bd24b2cc49922898e9353fa268086c00ae8b7f718405b72ad380cdbb38c85e02a00427eb4bdbda8fcd42b44708a9efde49cf753b75ebb389bf84d0bfbf58590e510e034572a01e409c30939e2e4a090ecc89c371820af54e06e4ad5495d4e58718385cca5414552e078fedf284fdc2cc5c070cba21a6a8d4b77525ddbc9a9fca9b2f29aac5783ee8badd709f81c73ff60556cf2ee623af073b5a84799acc1ca46b764f74b97068c7826cc0579794a540d7a55e49eac26a6930340132e946a983240b0cd1b732e305c1042f580c4b26f140fc1cab3ee6f620958e0979f85eddf586c410ce42e93a4d7c803ead45fc47cf4396d284632314d789e73cf3f534126c63fe244069d9e8a7c4f98e7e530fc588e648ef4e641364981b5377542d5e7a4aaab6d35f6df7d3a9d7ca715213599ee02c4dbea4dc78860febe1d29259c64b59b3333ffdaebbaff4e7b31c27a3791f6bf848a58df7c69bb2b1852d2ad357b9919ffdae570b27dc709fba087273d3a4de9e6a6be66db647fb6a8d1a503b3f481befb96745abf5cc4a6bba0f780d5c7759b9e303a2a6b17eb05b6e660f4c474959db183e1cae060e1639227ee0bca03978a238dc4352ed764da7d4f3ed5337f6d0376dff72615beeeeaaeef79ab93e4bcbf18cd8424eb2b6ad7f33d2b4ffd5ea08372e6ed1d984152df17e04c6f73540988d7dd979e020424a163c271151a255966be7edef42167b8facca633649739bab97572b485658cde409e5d4a0f653f1a5911141634e3d2b6079b19347df66f9820755fd517092dae62fb278b0bafcc7ad682f7921b3a455e0c6369988779e26f0458b31bffd7e4e5bfb31944e80f100b2553c3b616e75be18328dc430f6618d55cd7d0962bb916d26ed4b117c46fa29e0a112c02c36020b34a96762db628fa3490828ec2079962ad816ef20ea0bca78fb2b7f7aedd4c47e375e64294d151ff03083730336dea64934003a27730cc1c7dec5049ddba8188123dd191aa71390d43a49fb792a3da7082efa6cced73f00eccea18145fbc84925349f7b552314ab8ed4c491e392aed3b1f03eb79474c294b42e2eba1528da26450aa592cba7ea22e965c54dff0fd6fdfd6b52b9a0f5f762e27fb0e6c3cd326a1ca1c5973de9be881439f702830affeb0c034c18ac8d5c2f135c964bf69de50d6e99bde88e90321ba843d9753c8f83666105d25fafb1a11ea22d62ef6f1fc34ca4e60c35d69773a104d9a44728c08c20b6314327301a2c400a71e1424c12628cf9f4a67990ade8a2203b0edb96c6082d4673b7309cd52c4b32b02951db2f66c6c72bd6c7eac2b50b83830c75cdfc3d6e9c2b592c45ed5fa5f6ec0da85710b7e1562aea363e28665835791dc574d9a70b2e5e2b9973ab590d45b94d244fc4256926c5a55b01cd0aca21fe5f9c907691fb026d0c56788b03ca3f08db0abb9f901098dde2ec4003568bc3ca27475ff86a7cb0aabd9e5136c5de064d16774584b252024109bb02004dba1fabf9e8277de097a0ab0dc8f6e26fcd4a28fb9d27cd4a2f6b13e276ed259a39e1c7e60f3c32c5cc4c4f96bd981edcb5e2c76a517cdc285aa2ca571d1e3d463ecd7614ae227df17af7445305bd7c661cf7dba658b0adcf36b0084b74a5fa408e272f703770ac5351334709112c5d4e4fe987e0c27b670412696f52b33245c229775da550729938268ee4e7a282e4a60b25dbb28ea8877a5069f819e5d1d31d9140bbc627ff3df267d22e5f0e151db066577845d71b7cd4484089f3f59194963c8f02bd7a637")

    val Right(DecryptedPacket(payload, nextPacket, _)) = PaymentPacket.peel(privKeys(0), associatedData, onion)
    assert(payload == variableSizeOneHopPayload.head)
    assert(nextPacket.hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("create trampoline packet") {
    val PacketAndSecrets(onion, sharedSecrets) = TrampolinePacket.create(sessionKey, publicKeys, trampolinePayloads, associatedData)
    assert(serializeTrampolineOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619cff34152f3a36e52ca94e74927203a560392b9cc7ce3c45809c6be52166c24a595716880f95f178bf5b30cef0d5765280d9b6d0c91a22ff4b6ef137eff2921c6de4a07ef54b6ba93e78c67cc99e303f3706509aa43ba7c8a88cba175fccf9a8f5016ef06d3b935dbb15196d7ce16dc1a7157845566901d7b2197e52cab4ce48701ba6653c2a2b2c3bafa910d5e0f92af6bdc71d910aa7b766e0c716cffc9c12f7029db7c5f7eafaa166578c720619561dd14b3277db557ec7dcdb793771aef0f2f667cfdbec42e2a82a7189271c562a5784669387fc32a517d289dee16b46e8f016c32394319517d7e2ea8c5391cf17d0fe30c80913ed887234ccb48808f7ef9425bcd815c3b6e26fe30cb5ddcf6482b371417cef541890e08894ae65cd73fe52a662d21f2c9e77eecb277229b4b682322371c0a1dbfcd723a991993df8cc1f2696b84b055b40a1792a29f710295a18fbd351b0f3ff34cd13941131b8278ba79303c89117120eea69173abb941ee4edacce5400518062f432a44676433c907c05de61dc24830d954c6872e5a5c11afe432b203698edd656d9dad1d2859270ff45b90a96d48f713916b56f5ddeaf3cc77")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = TrampolinePacket.peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = TrampolinePacket.peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = TrampolinePacket.peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = TrampolinePacket.peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, _, sharedSecret4)) = TrampolinePacket.peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == trampolinePayloads)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))
  }

  test("create packet with invalid payload") {
    // In this test vector, the payload length (encoded as a varint in the first bytes) isn't equal to the actual
    // payload length.
    val incorrectVarint = Seq(
      hex"fd2a0101234567",
      hex"000000000000000000000000000000000000000000000000000000000000000000"
    )

    assertThrows[IllegalArgumentException](PaymentPacket.create(sessionKey, publicKeys.take(2), incorrectVarint, associatedData))
  }

  test("decrypt failure message") {
    val sharedSecrets = Seq(
      hex"0101010101010101010101010101010101010101010101010101010101010101",
      hex"0202020202020202020202020202020202020202020202020202020202020202",
      hex"0303030303030303030303030303030303030303030303030303030303030303"
    ).map(ByteVector32(_))

    val expected = DecryptedFailurePacket(publicKeys.head, InvalidOnionKey(ByteVector32.One))

    val packet1 = FailurePacket.create(sharedSecrets.head, expected.failureMessage)
    assert(packet1.length === FailurePacket.PacketLength)

    val Success(decrypted1) = FailurePacket.decrypt(packet1, Seq(0).map(i => (sharedSecrets(i), publicKeys(i))))
    assert(decrypted1 === expected)

    val packet2 = FailurePacket.wrap(packet1, sharedSecrets(1))
    assert(packet2.length === FailurePacket.PacketLength)

    val Success(decrypted2) = FailurePacket.decrypt(packet2, Seq(1, 0).map(i => (sharedSecrets(i), publicKeys(i))))
    assert(decrypted2 === expected)

    val packet3 = FailurePacket.wrap(packet2, sharedSecrets(2))
    assert(packet3.length === FailurePacket.PacketLength)

    val Success(decrypted3) = FailurePacket.decrypt(packet3, Seq(2, 1, 0).map(i => (sharedSecrets(i), publicKeys(i))))
    assert(decrypted3 === expected)
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
    for ((payloads, packetType) <- Seq(
      (referenceFixedSizePayloads, PaymentPacket),
      (referenceVariableSizePayloads, PaymentPacket),
      (variableSizePayloadsFull, PaymentPacket),
      (trampolinePayloads, TrampolinePacket))) {
      // route: origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4

      // origin build the onion packet
      val PacketAndSecrets(packet, sharedSecrets) = packetType.create(sessionKey, publicKeys, payloads, associatedData)

      // each node parses and forwards the packet
      // node #0
      val Right(DecryptedPacket(_, packet1, sharedSecret0)) = packetType.peel(privKeys(0), associatedData, packet)
      // node #1
      val Right(DecryptedPacket(_, packet2, sharedSecret1)) = packetType.peel(privKeys(1), associatedData, packet1)
      // node #2
      val Right(DecryptedPacket(_, packet3, sharedSecret2)) = packetType.peel(privKeys(2), associatedData, packet2)
      // node #3
      val Right(DecryptedPacket(_, packet4, sharedSecret3)) = packetType.peel(privKeys(3), associatedData, packet3)
      // node #4
      val Right(lastPacket@DecryptedPacket(_, _, sharedSecret4)) = packetType.peel(privKeys(4), associatedData, packet4)
      assert(lastPacket.isLastPacket)

      // node #4 want to reply with an error message
      val error = FailurePacket.create(sharedSecret4, TemporaryNodeFailure)
      assert(error === hex"a5e6bd0c74cb347f10cce367f949098f2457d14c046fd8a22cb96efb30b0fdcda8cb9168b50f2fd45edd73c1b0c8b33002df376801ff58aaa94000bf8a86f92620f343baef38a580102395ae3abf9128d1047a0736ff9b83d456740ebbb4aeb3aa9737f18fb4afb4aa074fb26c4d702f42968888550a3bded8c05247e045b866baef0499f079fdaeef6538f31d44deafffdfd3afa2fb4ca9082b8f1c465371a9894dd8c243fb4847e004f5256b3e90e2edde4c9fb3082ddfe4d1e734cacd96ef0706bf63c9984e22dc98851bcccd1c3494351feb458c9c6af41c0044bea3c47552b1d992ae542b17a2d0bba1a096c78d169034ecb55b6e3a7263c26017f033031228833c1daefc0dedb8cf7c3e37c9c37ebfe42f3225c326e8bcfd338804c145b16e34e4")
      // error sent back to 3, 2, 1 and 0
      val error1 = FailurePacket.wrap(error, sharedSecret3)
      assert(error1 === hex"c49a1ce81680f78f5f2000cda36268de34a3f0a0662f55b4e837c83a8773c22aa081bab1616a0011585323930fa5b9fae0c85770a2279ff59ec427ad1bbff9001c0cd1497004bd2a0f68b50704cf6d6a4bf3c8b6a0833399a24b3456961ba00736785112594f65b6b2d44d9f5ea4e49b5e1ec2af978cbe31c67114440ac51a62081df0ed46d4a3df295da0b0fe25c0115019f03f15ec86fabb4c852f83449e812f141a9395b3f70b766ebbd4ec2fae2b6955bd8f32684c15abfe8fd3a6261e52650e8807a92158d9f1463261a925e4bfba44bd20b166d532f0017185c3a6ac7957adefe45559e3072c8dc35abeba835a8cb01a71a15c736911126f27d46a36168ca5ef7dccd4e2886212602b181463e0dd30185c96348f9743a02aca8ec27c0b90dca270")

      val error2 = FailurePacket.wrap(error1, sharedSecret2)
      assert(error2 === hex"a5d3e8634cfe78b2307d87c6d90be6fe7855b4f2cc9b1dfb19e92e4b79103f61ff9ac25f412ddfb7466e74f81b3e545563cdd8f5524dae873de61d7bdfccd496af2584930d2b566b4f8d3881f8c043df92224f38cf094cfc09d92655989531524593ec6d6caec1863bdfaa79229b5020acc034cd6deeea1021c50586947b9b8e6faa83b81fbfa6133c0af5d6b07c017f7158fa94f0d206baf12dda6b68f785b773b360fd0497e16cc402d779c8d48d0fa6315536ef0660f3f4e1865f5b38ea49c7da4fd959de4e83ff3ab686f059a45c65ba2af4a6a79166aa0f496bf04d06987b6d2ea205bdb0d347718b9aeff5b61dfff344993a275b79717cd815b6ad4c0beb568c4ac9c36ff1c315ec1119a1993c4b61e6eaa0375e0aaf738ac691abd3263bf937e3")

      val error3 = FailurePacket.wrap(error2, sharedSecret1)
      assert(error3 === hex"aac3200c4968f56b21f53e5e374e3a2383ad2b1b6501bbcc45abc31e59b26881b7dfadbb56ec8dae8857add94e6702fb4c3a4de22e2e669e1ed926b04447fc73034bb730f4932acd62727b75348a648a1128744657ca6a4e713b9b646c3ca66cac02cdab44dd3439890ef3aaf61708714f7375349b8da541b2548d452d84de7084bb95b3ac2345201d624d31f4d52078aa0fa05a88b4e20202bd2b86ac5b52919ea305a8949de95e935eed0319cf3cf19ebea61d76ba92532497fcdc9411d06bcd4275094d0a4a3c5d3a945e43305a5a9256e333e1f64dbca5fcd4e03a39b9012d197506e06f29339dfee3331995b21615337ae060233d39befea925cc262873e0530408e6990f1cbd233a150ef7b004ff6166c70c68d9f8c853c1abca640b8660db2921")

      val error4 = FailurePacket.wrap(error3, sharedSecret0)
      assert(error4 === hex"9c5add3963fc7f6ed7f148623c84134b5647e1306419dbe2174e523fa9e2fbed3a06a19f899145610741c83ad40b7712aefaddec8c6baf7325d92ea4ca4d1df8bce517f7e54554608bf2bd8071a4f52a7a2f7ffbb1413edad81eeea5785aa9d990f2865dc23b4bc3c301a94eec4eabebca66be5cf638f693ec256aec514620cc28ee4a94bd9565bc4d4962b9d3641d4278fb319ed2b84de5b665f307a2db0f7fbb757366067d88c50f7e829138fde4f78d39b5b5802f1b92a8a820865af5cc79f9f30bc3f461c66af95d13e5e1f0381c184572a91dee1c849048a647a1158cf884064deddbf1b0b88dfe2f791428d0ba0f6fb2f04e14081f69165ae66d9297c118f0907705c9c4954a199bae0bb96fad763d690e7daa6cfda59ba7f2c8d11448b604d12d")

      // origin parses error packet and can see that it comes from node #4
      val Success(DecryptedFailurePacket(pubkey, failure)) = FailurePacket.decrypt(error4, sharedSecrets)
      assert(pubkey === publicKeys(4))
      assert(failure === TemporaryNodeFailure)
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
      assert(wrapped.length === FailurePacket.PacketLength)
    }
  }

  test("intermediate node replies with a failure message (reference test vector)") {
    for ((payloads, packetType) <- Seq(
      (referenceFixedSizePayloads, PaymentPacket),
      (referenceVariableSizePayloads, PaymentPacket),
      (variableSizePayloadsFull, PaymentPacket),
      (trampolinePayloads, TrampolinePacket))) {
      // route: origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4

      // origin build the onion packet
      val PacketAndSecrets(packet, sharedSecrets) = packetType.create(sessionKey, publicKeys, payloads, associatedData)

      // each node parses and forwards the packet
      // node #0
      val Right(DecryptedPacket(_, packet1, sharedSecret0)) = packetType.peel(privKeys(0), associatedData, packet)
      // node #1
      val Right(DecryptedPacket(_, packet2, sharedSecret1)) = packetType.peel(privKeys(1), associatedData, packet1)
      // node #2
      val Right(DecryptedPacket(_, _, sharedSecret2)) = packetType.peel(privKeys(2), associatedData, packet2)

      // node #2 want to reply with an error message
      val error = FailurePacket.create(sharedSecret2, InvalidRealm)

      // error sent back to 1 and 0
      val error1 = FailurePacket.wrap(error, sharedSecret1)
      val error2 = FailurePacket.wrap(error1, sharedSecret0)

      // origin parses error packet and can see that it comes from node #2
      val Success(DecryptedFailurePacket(pubkey, failure)) = FailurePacket.decrypt(error2, sharedSecrets)
      assert(pubkey === publicKeys(2))
      assert(failure === InvalidRealm)
    }
  }
}

object SphinxSpec {

  def serializePaymentOnion(onion: OnionRoutingPacket): ByteVector =
    OnionCodecs.paymentOnionPacketCodec.encode(onion).require.toByteVector

  def serializeTrampolineOnion(onion: OnionRoutingPacket): ByteVector =
    OnionCodecs.trampolineOnionPacketCodec.encode(onion).require.toByteVector

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

  // This test vector uses payloads with a fixed size.
  // origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4
  val referenceFixedSizePayloads = Seq(
    hex"000000000000000000000000000000000000000000000000000000000000000000",
    hex"000101010101010101000000000000000100000001000000000000000000000000",
    hex"000202020202020202000000000000000200000002000000000000000000000000",
    hex"000303030303030303000000000000000300000003000000000000000000000000",
    hex"000404040404040404000000000000000400000004000000000000000000000000"
  )

  // This test vector uses variable-size payloads intertwined with fixed-size payloads.
  // origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4
  val referenceVariableSizePayloads = Seq(
    hex"000000000000000000000000000000000000000000000000000000000000000000",
    hex"140101010101010101000000000000000100000001",
    hex"fd0100000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
    hex"140303030303030303000000000000000300000003",
    hex"000404040404040404000000000000000400000004000000000000000000000000"
  )

  // This test vector uses variable-sized payloads and fills the whole onion packet.
  // origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4
  val variableSizePayloadsFull = Seq(
    hex"8b09000000000000000030000000000000000000000000000000000000000000000000000000000025000000000000000000000000000000000000000000000000250000000000000000000000000000000000000000000000002500000000000000000000000000000000000000000000000025000000000000000000000000000000000000000000000000",
    hex"fd012a08000000000000009000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000",
    hex"620800000000000000900000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
    hex"fc120000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000",
    hex"fd01582200000000000000000000000000000000000000000022000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000"
  )

  // This test vector uses a single variable-sized payload filling the whole onion payload.
  // origin -> recipient
  val variableSizeOneHopPayload = Seq(
    hex"fd04f16500000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
  )

  // This test vector uses trampoline variable-size payloads.
  val trampolinePayloads = Seq(
    hex"2a 02020231 040190 f8210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c",
    hex"35 fa 33 010000000000000000000000040000000000000000000000000ff0000000000000000000000000000000000000000000000000",
    hex"23 f8 21 032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991",
    hex"00 0303030303030303 0000000000000003 00000003 000000000000000000000000",
    hex"23 f8 21 02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"
  )

  val associatedData = ByteVector32(hex"4242424242424242424242424242424242424242424242424242424242424242")
}
