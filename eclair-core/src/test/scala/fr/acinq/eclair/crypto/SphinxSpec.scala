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
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71da571226458c510bbadd1276f045c21c520a07d35da256ef75b4367962437b0dd10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf038659793a86cae5f52d32f3438527b47a1cfc54285a8afec3a4c9f3323db0c946f5d4cb2ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d15222e60826d5d971f64172d98e0760154400958f00e86697aa1aa9d41bee8119a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b040d6ba38b54ec35f81d7fc67678c3be47274f3c4cc472aff005c3469eb3bc140769ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565ae82cd3f4e3b24c76eaa5616c6111343306ab35c1fe5ca4a77c0e314ed7dba39d6f1e0de791719c241a939cc493bea2bae1c1e932679ea94d29084278513c77b899cc98059d06a27d171b0dbdf6bee13ddc4fc17a0c4d2827d488436b57baa167544138ca2e64a11b43ac8a06cd0c2fba2d4d900ed2d9205305e2d7383cc98dacb078133de5f6fb6bed2ef26ba92cea28aafc3b9948dd9ae5559e8bd6920b8cea462aa445ca6a95e0e7ba52961b181c79e73bd581821df2b10173727a810c92b83b5ba4a0403eb710d2ca10689a35bec6c3a708e9e92f7d78ff3c5d9989574b00c6736f84c199256e76e19e78f0c98a9d580b4a658c84fc8f2096c2fbea8f5f8c59d0fdacb3be2802ef802abbecb3aba4acaac69a0e965abd8981e9896b1f6ef9d60f7a164b371af869fd0e48073742825e9434fc54da837e120266d53302954843538ea7c6c3dbfb4ff3b2fdbe244437f2a153ccf7bdb4c92aa08102d4f3cff2ae5ef86fab4653595e6a5837fa2f3e29f27a9cde5966843fb847a4a61f1e76c281fe8bb2b0a181d096100db5a1a5ce7a910238251a43ca556712eaadea167fb4d7d75825e440f3ecd782036d7574df8bceacb397abefc5f5254d2722215c53ff54af8299aaaad642c6d72a14d27882d9bbd539e1cc7a527526ba89b8c037ad09120e98ab042d3e8652b31ae0e478516bfaf88efca9f3676ffe99d2819dcaeb7610a626695f53117665d267d3f7abebd6bbd6733f645c72c389f03855bdf1e4b8075b516569b118233a0f0971d24b83113c0b096f5216a207ca99a7cddc81c130923fe3d91e7508c9ac5f2e914ff5dccab9e558566fa14efb34ac98d878580814b94b73acbfde9072f30b881f7f0fff42d4045d1ace6322d86a97d164aa84d93a60498065cc7c20e636f5862dc81531a88c60305a2e59a985be327a6902e4bed986dbf4a0b50c217af0ea7fdf9ab37f9ea1a1aaa72f54cf40154ea9b269f1a7c09f9f43245109431a175d50e2db0132337baa0ef97eed0fcf20489da36b79a1172faccc2f7ded7c60e00694282d93359c4682135642bc81f433574aa8ef0c97b4ade7ca372c5ffc23c7eddd839bab4e0f14d6df15c9dbeab176bec8b5701cf054eb3072f6dadc98f88819042bf10c407516ee58bce33fbe3b3d86a54255e577db4598e30a135361528c101683a5fcde7e8ba53f3456254be8f45fe3a56120ae96ea3773631fcb3873aa3abd91bcff00bd38bd43697a2e789e00da6077482e7b1b1a677b5afae4c54e6cbdf7377b694eb7d7a5b913476a5be923322d3de06060fd5e819635232a2cf4f0731da13b8546d1d6d4f8d75b9fce6c2341a71b0ea6f780df54bfdb0dd5cd9855179f602f917265f21f9190c70217774a6fbaaa7d63ad64199f4664813b955cff954949076dcf")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = PaymentPacket.peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = PaymentPacket.peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = PaymentPacket.peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = PaymentPacket.peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) = PaymentPacket.peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == referenceFixedSizePayloads)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == ByteVector32(hex"9b122c79c8aee73ea2cdbc22eca15bbcc9409a4cdd73d2b3fcd4fe26a492d376"))
    assert(packets(1).hmac == ByteVector32(hex"548e58057ab0a0e6c2d8ad8e855d89f9224279a5652895ea14f60bffb81590eb"))
    assert(packets(2).hmac == ByteVector32(hex"0daed5f832ef34ea8d0d2cc0699134287a2739c77152d9edc8fe5ccce7ec838f"))
    assert(packets(3).hmac == ByteVector32(hex"62cc962876e734e089e79eda497077fb411fac5f36afd43329040ecd1e16c6d9"))
    // this means that node #4 is the last node
    assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("create packet with variable-size payloads (reference test vector)") {
    val PacketAndSecrets(onion, sharedSecrets) = PaymentPacket.create(sessionKey, publicKeys, referenceVariableSizePayloads, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71a060daf367132b378b3a3883c0e2c0e026b8900b2b5cdbc784e1a3bb913f88a9c50f7d61ab590531cf08000178a333a347f8b4072ed056f820f77774345e183a342ec4729f3d84accf515e88adddb85ecc08daba68404bae9a8e8d7178977d7094a1ae549f89338c0777551f874159eb42d3a59fb9285ad4e24883f27de23942ec966611e99bee1cee503455be9e8e642cef6cef7b9864130f692283f8a973d47a8f1c1726b6e59969385975c766e35737c8d76388b64f748ee7943ffb0e2ee45c57a1abc40762ae598723d21bd184e2b338f68ebff47219357bd19cd7e01e2337b806ef4d717888e129e59cd3dc31e6201ccb2fd6d7499836f37a993262468bcb3a4dcd03a22818aca49c6b7b9b8e9e870045631d8e039b066ff86e0d1b7291f71cefa7264c70404a8e538b566c17ccc5feab231401e6c08a01bd5edfc1aa8e3e533b96e82d1f91118d508924b923531929aea889fcdf050597c681185f336b1da63b0939aa2b7c50b21b5eb7b6ad66c81fab98a3cdf73f658149e7e9ced4edde5d38c9b8f92e16f6b4ab13d7fca6a0e4ecc9f9de611a90da6e99c39551094c56e3196f282c5dffd9fc4b2fc12f3bca8e6fe47eb45fbdd3be21a8a8d200797eae3c9a0497132f92410d804977408494dff49dd3d8bce248e0b74fd9e6f0f7102c25ddfa02bd9ad9f746abbfa337ef811d5345a9e16b60de1767b209645ba40bd1f9a5f75bc04feca9b27c5554be4fe83fac2cb83aa447a817bb85ae966c68b420063833fada375e2f515965e687a45699632902672c654d1d18d7bcbf55e8fa57f63f2da449f8e1e606e8722df081e5f193fc4179feb99ad22819afdeef211f7c54afdba92aeef0c00b7bc2b65a4813c01f907a8377585708f2d4c940a25328e585714c8ded0a9a4d7a6de1027c1cb7a0198cd3db68b58c0704dfd0cfbe624e9cd18cc0ae5d96697bb476708b9ee0403d211e64e0d5a7683a7a9a140c02f0ff1c6e67a302941b4052bdea8a63e70a3ad62c5b89c698f1fd3c7685cb49705096cad702d02d93bcb1c27a409f4c9bddec001205ca4a2740f19b50900be81c7e847f1a863deea8d35701f1355cad8db57b1d4eb2ab4e29587734785abfb46ddede71928213d7d089dfdeda052827f459f1688cc0935bd47e7bcec27427c8376dcce7e22699567c0d145f8a7db33f6758815f1f15f9f7a9760dec4f34ae095edda4c64e9735bdd029c4e32c2ee31ba47ec5e6bdb97813d52dbd15b4e0b7a2c7f790ae64104d99f38c127f0a093288fa34144adb16b8968d4fa7656fcec99de8503dd46d3b03620a71c7cd085364abd30dccf7fbda25a1cdc102600149c9af1c97aa0372cd2e1909f28ac5c686f432b310e79528c9b8b9e8f314c1e74621ce6308ad2278b81d460892e0d9dd38b7c76d58be6dfd10ae7583ee1e7ef5b3f6f78dc60af0950df1b00cc55b6d178ba2e476bea0eaeef49323b83f05804159e7aef4eed4cc60dd07be76f067dfd0bcfb0b806b69ba921336a20c43c832d0cab8fa3ddeb29e3bf07b0d98a112eb07802756235a49d44a8b82a950d84e95e01971f0e106ccb337f07384e21620e0ad39e16ed9edca123226cf55ac44f449eeb53e38a7f27d101806e4823e4efcc887414240ee6826c4a5cb1c6443ad36ebf905a435c1d9054e54173911b17b5b40f60b3d9fd5f12eac54ca1e20191f5f18544d5fd3d665e9bcef96fb44b76110aa64d9db4c86c9513cbdad546538e8aec521fbe83ceac5e74a15629f1ed0b870a1d0d1e5680b6d6100d1bd3f3b9043bd35b8919c4088f1949b8be89e4701eb870f8ed64fafa446c78df3ea")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = PaymentPacket.peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = PaymentPacket.peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = PaymentPacket.peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = PaymentPacket.peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) = PaymentPacket.peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == referenceVariableSizePayloads)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == ByteVector32(hex"e125e4acea319d02932a96d7dc065940bdf20d94ab8d5f9b0d816be457ee20d2"))
    assert(packets(1).hmac == ByteVector32(hex"f132f8609ca84fc4667dada7d22684c515e7231dabd45e64f92a1277af306c21"))
    assert(packets(2).hmac == ByteVector32(hex"4a630bdc56575e956627d7f191e731fabf110ef0044f8f64f4dcea79c2dcb995"))
    assert(packets(3).hmac == ByteVector32(hex"a2a89cf333e198b68904ce59ddceb9f989ebfa1ad534fa74ee85e41ff303c3a0"))
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
    assert(serializeTrampolineOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619cff34152f3a36e52ca94e74927203a560392b9cc7ce3c45809c6be52166c24a595716880f95f178bf5b30ca63141f74db6e92795c6130877cfdac3d4bd3087ee73c65d627ddd709112a848cc99e303f3706509aa43ba7c8a88cba175fccf9a8f5016ef06d3b935dbb15196d7ce16dc1a7157845566901d7b2197e52cab4ce487014b14816e5805f9fcacb4f8f88b8ff176f1b94f6ce6b00bc43221130c17d20ef629db7c5f7eafaa166578c720619561dd14b3277db557ec7dcdb793771aef0f2f667cfdbeae3ac8d331c5994779dffb31e5fc0dbdedc0c592ca6d21c18e47fe3528d6975c19517d7e2ea8c5391cf17d0fe30c80913ed887234ccb48808f7ef9425bcd815c3b586210979e3bb286ef2851bf9ce04e28c40a203df98fd648d2f1936fd2f1def0e77eecb277229b4b682322371c0a1dbfcd723a991993df8cc1f2696b84b055b40a1792a29f710295a18fbd351b0f3ff34cd13941131b8278ba79303c89117120eea691738a9954908195143b039dbeed98f26a92585f3d15cf742c953799d3272e0545e9b744be9d3b4cd2975656379d61ecccb8665fa26b63609c751bf3bfbacde4fff3508215f8dfd5")

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
