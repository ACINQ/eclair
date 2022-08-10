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
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshiLong, ShortChannelId, UInt64, randomKey}
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

  test("generate filler with fixed-size payloads (reference test vector)") {
    val (_, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = generateFiller("rho", 1300, sharedsecrets.dropRight(1), referenceFixedSizePaymentPayloads.dropRight(1))
    assert(filler == hex"c6b008cf6414ed6e4c42c291eb505e9f22f5fe7d0ecdd15a833f4d016ac974d33adc6ea3293e20859e87ebfb937ba406abd025d14af692b12e9c9c2adbe307a679779259676211c071e614fdb386d1ff02db223a5b2fae03df68d321c7b29f7c7240edd3fa1b7cb6903f89dc01abf41b2eb0b49b6b8d73bb0774b58204c0d0e96d3cce45ad75406be0bc009e327b3e712a4bd178609c00b41da2daf8a4b0e1319f07a492ab4efb056f0f599f75e6dc7e0d10ce1cf59088ab6e873de377343880f7a24f0e36731a0b72092f8d5bc8cd346762e93b2bf203d00264e4bc136fc142de8f7b69154deb05854ea88e2d7506222c95ba1aab065c8a851391377d3406a35a9af3ac")
  }

  test("generate filler with variable-size payloads") {
    val (_, sharedsecrets) = computeEphemeralPublicKeysAndSharedSecrets(sessionKey, publicKeys)
    val filler = generateFiller("rho", 1300, sharedsecrets.dropRight(1), referenceVariableSizePaymentPayloads.dropRight(1))
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
      assert(peekPayloadLength(payload) == expected)
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

  test("create payment packet with fixed-size payloads (reference test vector)") {
    val Success(PacketAndSecrets(onion, sharedSecrets)) = create(sessionKey, 1300, publicKeys, referenceFixedSizePaymentPayloads, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a71e87f9aab8f6378c6ff744c1f34b393ad28d065b535c1a8668d85d3b34a1b3befd10f7d61ab590531cf08000178a333a347f8b4072e216400406bdf3bf038659793a1f9e7abc789266cc861cabd95818c0fc8efbdfdc14e3f7c2bc7eb8d6a79ef75ce721caad69320c3a469a202f3e468c67eaf7a7cda226d0fd32f7b48084dca885d014698cf05d742557763d9cb743faeae65dcc79dddaecf27fe5942be5380d15e9a1ec866abe044a9ad635778ba61fc0776dc832b39451bd5d35072d2269cf9b040a2a2fba158a0d8085926dc2e44f0c88bf487da56e13ef2d5e676a8589881b4869ed4c7f0218ff8c6c7dd7221d189c65b3b9aaa71a01484b122846c7c7b57e02e679ea8469b70e14fe4f70fee4d87b910cf144be6fe48eef24da475c0b0bcc6565a9f99728426ce2380a9580e2a9442481ceae7679906c30b1a0e21a10f26150e0645ab6edfdab1ce8f8bea7b1dee511c5fd38ac0e702c1c15bb86b52bca1b71e15b96982d262a442024c33ceb7dd8f949063c2e5e613e873250e2f8708bd4e1924abd45f65c2fa5617bfb10ee9e4a42d6b5811acc8029c16274f937dac9e8817c7e579fdb767ffe277f26d413ced06b620ede8362081da21cf67c2ca9d6f15fe5bc05f82f5bb93f8916bad3d63338ca824f3bbc11b57ce94a5fa1bc239533679903d6fec92a8c792fd86e2960188c14f21e399cfd72a50c620e10aefc6249360b463df9a89bf6836f4f26359207b765578e5ed76ae9f31b1cc48324be576e3d8e44d217445dba466f9b6293fdf05448584eb64f61e02903f834518622b7d4732471c6e0e22e22d1f45e31f0509eab39cdea5980a492a1da2aaac55a98a01216cd4bfe7abaa682af0fbff2dfed030ba28f1285df750e4d3477190dd193f8643b61d8ac1c427d590badb1f61a05d480908fbdc7c6f0502dd0c4abb51d725e92f95da2a8facb79881a844e2026911adcc659d1fb20a2fce63787c8bb0d9f6789c4b231c76da81c3f0718eb7156565a081d2be6b4170c0e0bcebddd459f53db2590c974bca0d705c055dee8c629bf854a5d58edc85228499ec6dde80cce4c8910b81b1e9e8b0f43bd39c8d69c3a80672729b7dc952dd9448688b6bd06afc2d2819cda80b66c57b52ccf7ac1a86601410d18d0c732f69de792e0894a9541684ef174de766fd4ce55efea8f53812867be6a391ac865802dbc26d93959df327ec2667c7256aa5a1d3c45a69a6158f285d6c97c3b8eedb09527848500517995a9eae4cd911df531544c77f5a9a2f22313e3eb72ca7a07dba243476bc926992e0d1e58b4a2fc8c7b01e0cad726237933ea319bad7537d39f3ed635d1e6c1d29e97b3d2160a09e30ee2b65ac5bce00996a73c008bcf351cecb97b6833b6d121dcf4644260b2946ea204732ac9954b228f0beaa15071930fd9583dfc466d12b5f0eeeba6dcf23d5ce8ae62ee5796359d97a4a15955c778d868d0ef9991d9f2833b5bb66119c5f8b396fd108baed7906cbb3cc376d13551caed97fece6f42a4c908ee279f1127fda1dd3ee77d8de0a6f3c135fa3f1cffe38591b6738dc97b55f0acc52be9753ce53e64d7e497bb00ca6123758df3b68fad99e35c04389f7514a8e36039f541598a417275e77869989782325a15b5342ac5011ff07af698584b476b35d941a4981eac590a07a092bb50342da5d3341f901aa07964a8d02b623c7b106dd0ae50bfa007a22d46c8772fa55558176602946cb1d11ea5460db7586fb89c6d3bcd3ab6dd20df4a4db63d2e7d52380800ad812b8640887e027e946df96488b47fbc4a4fadaa8beda4abe446fafea5403fae2ef")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) = peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == referenceFixedSizePaymentPayloads)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == ByteVector32(hex"a93aa4f40241cef3e764e24b28570a0db39af82ab5102c3a04e51bec8cca9394"))
    assert(packets(1).hmac == ByteVector32(hex"5d1b11f1efeaa9be32eb1c74b113c0b46f056bb49e2a35a51ceaece6bd31332c"))
    assert(packets(2).hmac == ByteVector32(hex"19ca6357b5552b28e50ae226854eec874bbbf7025cf290a34c06b4eff5d2bac0"))
    assert(packets(3).hmac == ByteVector32(hex"16d4553c6084b369073d259381bb5b02c16bb2c590bbd9e69346cf7ebd563229"))
    // this means that node #4 is the last node
    assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("create payment packet with variable-size payloads (reference test vector)") {
    val Success(PacketAndSecrets(onion, sharedSecrets)) = create(sessionKey, 1300, publicKeys, referenceVariableSizePaymentPayloads, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619e5f14350c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a710f8eaf9ccc768f66bb5dec1f7827f33c43fe2ddd05614c8283aa78e9e7573f87c50f7d61ab590531cf08000178a333a347f8b4072e1cea42da7552402b10765adae3f581408f35ff0a71a34b78b1d8ecae77df96c6404bae9a8e8d7178977d7094a1ae549f89338c0777551f874159eb42d3a59fb9285ad4e24883f27de23942ec966611e99bee1cee503455be9e8e642cef6cef7b9864130f692283f8a973d47a8f1c1726b6e59969385975c766e35737c8d76388b64f748ee7943ffb0e2ee45c57a1abc40762ae598723d21bd184e2b338f68ebff47219357bd19cd7e01e2337b806ef4d717888e129e59cd3dc31e6201ccb2fd6d7499836f37a993262468bcb3a4dcd03a22818aca49c6b7b9b8e9e870045631d8e039b066ff86e0d1b7291f71cefa7264c70404a8e538b566c17ccc5feab231401e6c08a01bd5edfc1aa8e3e533b96e82d1f91118d508924b923531929aea889fcdf057f5995d9731c4bf796fb0e41c885d488dcbc68eb742e27f44310b276edc6f652658149e7e9ced4edde5d38c9b8f92e16f6b4ab13d710ee5c193921909bdd75db331cd9d7581a39fca50814ed8d9d402b86e7f8f6ac2f3bca8e6fe47eb45fbdd3be21a8a8d200797eae3c9a0497132f92410d804977408494dff49dd3d8bce248e0b74fd9e6f0f7102c25ddfa02bd9ad9f746abbfa3379834bc2380d58e9d23237821475a1874484783a15d68f47d3dc339f38d9bf925655d5c946778680fd6d1f062f84128895aff09d35d6c92cca63d3f95a9ee8f2a84f383b4d6a087533e65de12fc8dcaf85777736a2088ff4b22462265028695b37e70963c10df8ef2458756c73007dc3e544340927f9e9f5ea4816a9fd9832c311d122e9512739a6b4714bba590e31caa143ce83cb84b36c738c60c3190ff70cd9ac286a9fd2ab619399b68f1f7447be376ce884b5913c8496d01cbf7a44a60b6e6747513f69dc538f340bc1388e0fde5d0c1db50a4dcb9cc0576e0e2474e4853af9623212578d502757ffb2e0e749695ed70f61c116560d0d4154b64dcf3cbf3c91d89fb6dd004dc19588e3479fcc63c394a4f9e8a3b8b961fce8a532304f1337f1a697a1bb14b94d2953f39b73b6a3125d24f27fcd4f60437881185370bde68a5454d816e7a70d4cea582effab9a4f1b730437e35f7a5c4b769c7b72f0346887c1e63576b2f1e2b3706142586883f8cf3a23595cc8e35a52ad290afd8d2f8bcd5b4c1b891583a4159af7110ecde092079209c6ec46d2bda60b04c519bb8bc6dffb5c87f310814ef2f3003671b3c90ddf5d0173a70504c2280d31f17c061f4bb12a978122c8a2a618bb7d1edcf14f84bf0fa181798b826a254fca8b6d7c81e0beb01bd77f6461be3c8647301d02b04753b0771105986aa0cbc13f7718d64e1b3437e8eef1d319359914a7932548c91570ef3ea741083ca5be5ff43c6d9444d29df06f76ec3dc936e3d180f4b6d0fbc495487c7d44d7c8fe4a70d5ff1461d0d9593f3f898c919c363fa18341ce9dae54f898ccf3fe792136682272941563387263c51b2a2f32363b804672cc158c9230472b554090a661aa81525d11876eefdcc45442249e61e07284592f1606491de5c0324d3af4be035d7ede75b957e879e9770cdde2e1bbc1ef75d45fe555f1ff6ac296a2f648eeee59c7c08260226ea333c285bcf37a9bbfa57ba2ab8083c4be6fc2ebe279537d22da96a07392908cf22b233337a74fe5c603b51712b43c3ee55010ee3d44dd9ba82bba3145ec358f863e04bbfa53799a7a9216718fd5859da2f0deb77b8e315ad6868fdec9400f45a48e6dc8ddbaeb3")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) = peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == referenceVariableSizePaymentPayloads)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == ByteVector32(hex"4ecb91c341543953a34d424b64c36a9cd8b4b04285b0c8de0acab0b6218697fc"))
    assert(packets(1).hmac == ByteVector32(hex"3d8e429a1e8d7bdb2813cd491f17771aa75670d88b299db1954aa015d035408f"))
    assert(packets(2).hmac == ByteVector32(hex"30ad58843d142609ed7ae2b960c8ce0e331f7d45c7d705f67fd3f3978cd7b8f8"))
    assert(packets(3).hmac == ByteVector32(hex"4ee0600ee609f1f3356b85b0af8ead34c2db4ae93e3978d15f983040e8b01acd"))
    assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("create payment packet with variable-size payloads filling the onion") {
    val Success(PacketAndSecrets(onion, sharedSecrets)) = create(sessionKey, 1300, publicKeys, variableSizePaymentPayloadsFull, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f2836866196ef84350c2a76fc232b5d46d421e9615471ab9e0bc887beff8c95fdb878f7b3a7141453e5f8d22b6101810ae541ce499a09b4a9d9f80d1845c8960c85fc6d1a87bf74b2ce49922898e9353fa268086c00ae8b7f718405b72ad3829dbb38c85e02a00427eb4bdbda8fcd42b44708a9efde49cf776b75ebb389bf84d0bfbf58590e510e034572a01e409c309396778760423a8d8754c52e9a01a8f0e271cba5068bab5ee5bd0b5cd98276b0e04d60ba6a0f6bafd75ff41903ab352a1f47586eae3c6c8e437d4308766f71052b46ba2efbd87c0a781e8b3f456300fc7efbefc78ab515338666aed2070e674143c30b520b9cc1782ba8b46454db0d4ce72589cfc2eafb2db452ec98573ad08496483741de5376bfc7357fc6ea629e31236ba6ba7703014959129141a1719788ec83884f2e9151a680e2a96d2bcc67a8a2935aa11acee1f9d04812045b4ae5491220313756b5b9a0a6f867f2a95be1fab14870f04eeab694d9594620632b14ec4b424b495914f3dc587f75cd4582c113bb61e34a0fa7f79f97463be4e3c6fb99516889ed020acee419bb173d38e5ba18a00065e11fd733cf9ae46505dbb4ef70ef2f502601f4f6ee1fdb9d17435e15080e962f24760843f35bac1ac079b694ff7c347c1ed6a87f02b0758fbf00917764716c68ed7d6e6c0e75ccdb6dc7fa59554784b3ad906127ea77a6cdd814662ee7d57a939e28d77b3da47efc072436a3fd7f9c40515af8c4903764301e62b57153a5ca03ff5bb49c7dc8d3b2858100fb4aa5df7a94a271b73a76129445a3ea180d84d19029c003c164db926ed6983e5219028721a294f145e3fcc20915b8a2147efc8b5d508339f64970feee3e2da9b9c9348c1a0a4df7527d0ae3f8ae507a5beb5c73c2016ecf387a3cd8b79df80a8e9412e707cb9c761a0809a84c606a779567f9f0edf685b38c98877e90d02aedd096ed841e50abf2114ce01efbff04788fb280f870eca20c7ec353d5c381903e7d08fc57695fd79c27d43e7bd603a876068d3f1c7f45af99003e5eec7e8d8c91e395320f1fc421ef3552ea033129429383304b760c8f93de342417c3223c2112a623c3514480cdfae8ec15a99abfca71b03a8396f19edc3d5000bcfb77b5544813476b1b521345f4da396db09e783870b97bc2034bd11611db30ed2514438b046f1eb7093eceddfb1e73880786cd7b540a3896eaadd0a0692e4b19439815b5f2ec855ec8ececce889442a64037e956452a3f7b86cb3780b3e316c8dde464bc74a60a85b613f849eb0b29daf81892877bd4be9ba5997fc35544d3c2a00e5e1f45dc925607d952c6a89721bd0b6f6aec03314d667166a5b8b18471403be7018b2479aaef6c7c6c554a50a98b717dff06d50be39fb36dc03e678e0a52fc615be46b223e3bee83fa0c7c47a1f29fb94f1e9eebf6c9ecf8fc79ae847df2effb60d07aba301fc536546ec4899eedb4fec9a9bed79e3a83c4b32757745778e977e485c67c0f12bbc82c0b3bb0f4df0bd13d046fed4446f54cd85bfce55ef781a80e5f63d289d08de001237928c2a4e0c8694d0c1e68cc23f2409f30009019085e831a928e7bc5b00a1f29d25482f7fd0b6dad30e6ef8edc68ddf7db404ea7d11540fc2cee74863d64af4c945457e04b7bea0a5fb8636edadb1e1d6f2630d61062b781c1821f46eddadf269ea1fada829547590081b16bc116e074cae0224a375f2d9ce16e836687c89cd285e3b40f1e59ce2caa3d1d8cf37ee4d5e3abe7ef0afd6ffeb4fd6905677b950894863c828ab8d93519566f69fa3c2129da763bf58d9c4d2837d4d9e13821258f7e7098b34f695a589bd9eb568ba51ee3014b2d3ba1d4cf9ebaed0231ed57ecea7bd918216")

    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = peel(privKeys(1), associatedData, nextPacket0)
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = peel(privKeys(2), associatedData, nextPacket1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = peel(privKeys(3), associatedData, nextPacket2)
    val Right(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) = peel(privKeys(4), associatedData, nextPacket3)
    assert(Seq(payload0, payload1, payload2, payload3, payload4) == variableSizePaymentPayloadsFull)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == ByteVector32(hex"859cd694cf604442547246f4fae144f255e71e30cb366b9775f488cac713f0db"))
    assert(packets(1).hmac == ByteVector32(hex"259982a8af80bd3b8018443997fa5f74c48b488fff62e531be54b887d53fe0ac"))
    assert(packets(2).hmac == ByteVector32(hex"58110c95368305b73ae15d22b884fda0482c60993d3ba4e506e37ff5021efb13"))
    assert(packets(3).hmac == ByteVector32(hex"f45e7099e32b8973f54cbfd1f6c48e7e0b90718ad7b00a88e1e98cebeb6d3916"))
    assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("create payment packet with single variable-size payload filling the onion") {
    val Success(PacketAndSecrets(onion, _)) = create(sessionKey, 1300, publicKeys.take(1), variableSizeOneHopPaymentPayload, associatedData)
    assert(serializePaymentOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f28368661918f5b235c2a76fc232b5e46d421e9615471ab9e0bc887beff8c95fdb878f7b3a7141453e5f8d22b6351810ae541ce499a09b4a9d9f80d1845c8960c85fc6d1a87bd24b2cc49922898e9353fa268086c00ae8b7f718405b72ad380cdbb38c85e02a00427eb4bdbda8fcd42b44708a9efde49cf753b75ebb389bf84d0bfbf58590e510e034572a01e409c30939e2e4a090ecc89c371820af54e06e4ad5495d4e58718385cca5414552e078fedf284fdc2cc5c070cba21a6a8d4b77525ddbc9a9fca9b2f29aac5783ee8badd709f81c73ff60556cf2ee623af073b5a84799acc1ca46b764f74b97068c7826cc0579794a540d7a55e49eac26a6930340132e946a983240b0cd1b732e305c1042f580c4b26f140fc1cab3ee6f620958e0979f85eddf586c410ce42e93a4d7c803ead45fc47cf4396d284632314d789e73cf3f534126c63fe244069d9e8a7c4f98e7e530fc588e648ef4e641364981b5377542d5e7a4aaab6d35f6df7d3a9d7ca715213599ee02c4dbea4dc78860febe1d29259c64b59b3333ffdaebbaff4e7b31c27a3791f6bf848a58df7c69bb2b1852d2ad357b9919ffdae570b27dc709fba087273d3a4de9e6a6be66db647fb6a8d1a503b3f481befb96745abf5cc4a6bba0f780d5c7759b9e303a2a6b17eb05b6e660f4c474959db183e1cae060e1639227ee0bca03978a238dc4352ed764da7d4f3ed5337f6d0376dff72615beeeeaaeef79ab93e4bcbf18cd8424eb2b6ad7f33d2b4ffd5ea08372e6ed1d984152df17e04c6f73540988d7dd979e020424a163c271151a255966be7edef42167b8facca633649739bab97572b485658cde409e5d4a0f653f1a5911141634e3d2b6079b19347df66f9820755fd517092dae62fb278b0bafcc7ad682f7921b3a455e0c6369988779e26f0458b31bffd7e4e5bfb31944e80f100b2553c3b616e75be18328dc430f6618d55cd7d0962bb916d26ed4b117c46fa29e0a112c02c36020b34a96762db628fa3490828ec2079962ad816ef20ea0bca78fb2b7f7aedd4c47e375e64294d151ff03083730336dea64934003a27730cc1c7dec5049ddba8188123dd191aa71390d43a49fb792a3da7082efa6cced73f00eccea18145fbc84925349f7b552314ab8ed4c491e392aed3b1f03eb79474c294b42e2eba1528da26450aa592cba7ea22e965c54dff0fd6fdfd6b52b9a0f5f762e27fb0e6c3cd326a1ca1c5973de9be881439f702830affeb0c034c18ac8d5c2f135c964bf69de50d6e99bde88e90321ba843d9753c8f83666105d25fafb1a11ea22d62ef6f1fc34ca4e60c35d69773a104d9a44728c08c20b6314327301a2c400a71e1424c12628cf9f4a67990ade8a2203b0edb96c6082d4673b7309cd52c4b32b02951db2f66c6c72bd6c7eac2b50b83830c75cdfc3d6e9c2b592c45ed5fa5f6ec0da85710b7e1562aea363e28665835791dc574d9a70b2e5e2b9973ab590d45b94d244fc4256926c5a55b01cd0aca21fe5f9c907691fb026d0c56788b03ca3f08db0abb9f901098dde2ec4003568bc3ca27475ff86a7cb0aabd9e5136c5de064d16774584b252024109bb02004dba1fabf9e8277de097a0ab0dc8f6e26fcd4a28fb9d27cd4a2f6b13e276ed259a39e1c7e60f3c32c5cc4c4f96bd981edcb5e2c76a517cdc285aa2ca571d1e3d463ecd7614ae227df17af7445305bd7c661cf7dba658b0adcf36b0084b74a5fa408e272f703770ac5351334709112c5d4e4fe987e0c27b670412696f52b33245c229775da550729938268ee4e7a282e4a60b25dbb28ea8877a5069f819e5d1d31d9140bbc627ff3df267d22e5f0e151db066577845d71b7cd4484089f3f59194963c8f02bd7a637")

    val Right(DecryptedPacket(payload, nextPacket, _)) = peel(privKeys(0), associatedData, onion)
    assert(payload == variableSizeOneHopPaymentPayload.head)
    assert(nextPacket.hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
  }

  test("create trampoline payment packet") {
    val Success(PacketAndSecrets(onion, sharedSecrets)) = create(sessionKey, 400, publicKeys, trampolinePaymentPayloads, associatedData)
    assert(serializeTrampolineOnion(onion) == hex"0002eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619cff34152f3a36e52ca94e74927203a560392b9cc7ce3c45809c6be52166c24a595716880f95f178bf5b30ca5f01f7d8f9e2d26348fa73a0cf0e01efaeb4a6ff69f0e8ca2cb7f180d97b5becc99e303f3706509aa43ba7c8a88cba175fccf9a8f5016ef06d3b935dbb15196d7ce16dc1a7157845566901d7b2197e52cab4ce487019d8f59df4c61e85b3c678636701ea8bb55b8bdbd8724d8d39ee47087a648501329db7c5f7eafaa166578c720619561dd14b3277db557ec7dcdb793771aef0f2f667cfdbe148be176e089e1ae07192472031bcdaf47ab6334b98e5b6fcd26b3b47982842019517d7e2ea8c5391cf17d0fe30c80913ed887234ccb48808f7ef9425bcd815c3b9604b5119fbc40ae57b5921bb333f5dd9de0b2638d44bc5e1a863715f96589f3e77eecb277229b4b682322371c0a1dbfcd723a991993df8cc1f2696b84b055b40a1792a29f710295a18fbd351b0f3ff34cd13941131b8278ba79303c89117120eea69173fd2cf5e044e97bcd4060d1ab6da116bdb4136f4d37eb832845b64366dfcbe8729df1dda5708c1c89cd880b0f7c82318bcfe8a27f9e857b1dc453eb555c428c412a1056005319")

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
      (referenceFixedSizePaymentPayloads, 1300),
      (referenceVariableSizePaymentPayloads, 1300),
      (variableSizePaymentPayloadsFull, 1300),
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
      (referenceFixedSizePaymentPayloads, 1300),
      (referenceVariableSizePaymentPayloads, 1300),
      (variableSizePaymentPayloadsFull, 1300),
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
    val bobPayload = hex"01200000000000000000000000000000000000000000000000000000000000000000 02080000000000000001 0a080032000000002710 0c0c000b72460000000000000032"
    val carol = PrivateKey(hex"4343434343434343434343434343434343434343434343434343434343434343")
    val carolPayload = hex"02080000000000000002 0821031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f 0a07004b0000009664 0c0c000b72140000000000000032"
    val dave = PrivateKey(hex"4444444444444444444444444444444444444444444444444444444444444444")
    val davePayload = hex"012200000000000000000000000000000000000000000000000000000000000000000000 02080000000000000003 0a06001900000064 0c0c000b71c90000000000000032"
    val eve = PrivateKey(hex"4545454545454545454545454545454545454545454545454545454545454545")
    val evePayload = hex"011c00000000000000000000000000000000000000000000000000000000 0616c9cf92f45ade68345bc20ae672e2012f4af487ed4415 0c0c000b71b00000000000000032"

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
      hex"cd7b00ff9c09ed28102b210ac73aa12d63e90852cebc496c49f57c499a2888b49f2e72b19446f7e60a818aa2938d8c625415b992b8928a7a21edb8f7fcaa0dc85ff12ba8a7c266d0d9b602a88669890ec2400145",
      hex"cc0f16524fd7f8bb0f4e8d40ad71709ef140174c76faa574cac401bb8992fef76c4d004aa485dd599ed1cf2715f570f656a5aaecaf1ee8d59d0fa1d4167b9b259b065e2f2c17df0b782d9f118ec4e1fa2990e425",
      hex"0fa1a72cff3b64a3d6e1e4903cf8c8b0a17144aeb249dcb86561adee1f679ee8db3e561d9e49895fd4bcebf6f58d6f61a6d41a9bf5aa4b0d5343785651208226e401d38c7a2728c421b132f193939eb0d5de7165",
      hex"da1c7e5f7881219884beae6ae68971de73bab4c3055d9865b1afb60722a63c688768042ade22f2c22f5724767d171fd221d3e579e43b3545c72e3ef174a3a95ee07b395d070821dc9b052dc1e2e66e5e0be3704f",
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
      ).map(tlvs => PaymentOnionCodecs.tlvPerHopPayloadCodec.encode(tlvs).require.bytes)

      val nodeIds = Seq(alice, bob).map(_.publicKey) ++ blindedRoute.blindedNodeIds.tail
      val Success(PacketAndSecrets(onion, sharedSecrets)) = create(sessionKey, 1300, nodeIds, payloads, associatedData)
      assert(serializePaymentOnion(onion) == hex"0002531fe6068134503d2723133227c867ac8fa6c83c537e9a44c3c5bdbdcb1fe337dadf610256c6ab518495dce9cdedf9391e21a71dad9f6d478c501d3238245ac0a1b154184ff24d9180f6178d49dded810e0a48e4daaf6916e4ebe5d56ad2013b520af2a3c49316bc590ee83e8c31b1eb11ff766dad27ca993326b1ed582fb451a2ad87fbf6601134c6341c4a2deb6859e25a355bd483bb421e93726e454ebeb63bdb8bc6d2e93b36ba64459135b78423ee0bdada348a45f1004deb738c51d9f5a675dd5370e04062ae3d6f71823a7c004f364929aa6d3068eaad27dbd49cb178bf08f868413d7ebce95c4650277007e0ff5e8164f81b960f29b881c8f896bd63e62518cb4f1de62562c442d2a0880d6b34d4dab0f66805371f9627400aa44e1fba3e09129c3d0c1b8279a4bdccb55d2844a881f0ac89d3582bdfb60a89d3814770fbe5d686b9854d74db38f6f01f10f185dc5f1bcc3607a1d1ca2c9955621df1e74d61956130e462e8b6c1133cf7950b83a60b4a7900c699e2253e0b43148c34a7501809ef6dc33560fd9d9ab1654a83e8266e858d804510ff5f96049f07c8ab8ab07442fdd164acefb6dece8c57eb16b57be0bb79205c9c21b9d9d4928558f01823874971052b40d3560ef8e80d16722b6eb73b7d67b636f0369a23afacecc2376067e60843938dbb305ccb964e820e20a8213d4cd577bcc5a8c874e7aef71153a884ebf78164d80441a0c431610bad060d9335f7ea948d76d3a94c20da4decb01cd916b1527437fb3320d1d585414bb2f8d3758342b33df5dc69daa700eb53a19dc5707e96ccd62730881731f98c74a9bd96b857a69398f84bbfb7274975a6ece60ac5983d2b631c47c3a66410b9b64a0b264e041cc5df2a42e7f1ab8f8ed545984a37385f996ecf585f36a371067c36d74b58f509094340fc2894acd5edf094b279576aa501c6c403aa83899748817ece2f0fe0a84a8a8e8262856ac9d7dd5b5211cc17336ea7532e47a75aca9969ad4f61501d3f5acf0c5e25af82c0318cc7a10182288fa848dc0d8787ae83602f1a109443bbce347dfbcea05353a0f227a00d99e0b9e56d03bfa69bffd7e51a8ec6054d704de32106941a26d56b5ef5aa875445fa98cd84de56203d52d097e4e1ec5220462ccaa6aec6be416bd082164a2f465906acedd1263e46d04a7ee02bd6b45c26bd736c75094ad1f053ec8c239c130da9ef643b8ae139cab06bf88c516133df0b05acdbbc86b9af9702dfa16ce74eb2a26a078a7ff41fbc1b1dfa73eb81ef5d0a81e56aa176f5f04b6b093e9c15f7aa1c4f7c24b6c443476b3579020cc84dadfd3a3acfe45ac0d674bcc3c2bafe56f1d1efaaae26e9ba37c21ac21c672fb5b61877726f3b77ff08974c51b2a9f7c0b3761872ccf9453a7f8c05a810db972abc8578c3dd440bf2f399975be509561116c618b3c743012ba821986e0dd5045310c618ed65fbcc28be6949a4ee4f27c554baae00d1f7826046ee5e7ccd82fa74c6441e7c76aa52a69c49e33942c7ed16682085177fc51901a19409ffd504867ab234c491c3e4bad1c3be868ca23575ad13f1c01d275e7713eeec8eb8479124e1e83da679f1b7154d1104b7cab3e7cb7d1a215fa6f4adaa0ca9f5e179fa5f588a43c92d5c0560d22d2b7d264a7b933540973bf4708d85017375e3a9fcad2d7b91527d8c35569fe021f6031d0cf5665820d9c7d66fc1cc34ec661df9299ec13352dd9e7a5c18cc1dcc2af1e0e211fdf9786e358fcbcd71ebc3e59740517e0c702c951a7551e7e30771d18d70b02272b1cfbe530d5a38f25fc1572aa2215fcf69e65a3a10c10774ab88ec0afd512c02c73072a4907928f19a5a366b6d7a68faccee3ec6326b83ae4b8003049f4a9f119c36f418ac2c6d77cc913c0e43cd72c34a920220c3")

      // Alice can decrypt the onion as usual.
      val Right(DecryptedPacket(onionPayloadAlice, packetForBob, sharedSecretAlice)) = peel(alice, associatedData, onion)

      // Bob is the introduction node.
      // He can decrypt the onion as usual, but the payload doesn't contain a shortChannelId or a nodeId to forward to.
      // However it contains a blinding point and encrypted data, which he can decrypt to discover the next node.
      val Right(DecryptedPacket(onionPayloadBob, packetForCarol, sharedSecretBob)) = peel(bob, associatedData, packetForBob)
      val tlvsBob = PaymentOnionCodecs.tlvPerHopPayloadCodec.decode(onionPayloadBob.bits).require.value
      assert(tlvsBob.get[OnionPaymentPayloadTlv.BlindingPoint].map(_.publicKey).contains(blinding))
      assert(tlvsBob.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
      val Success((recipientTlvsBob, blindingEphemeralKeyForCarol)) = RouteBlindingEncryptedDataCodecs.decode(bob, blinding, tlvsBob.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data, RouteBlindingEncryptedDataCodecs.paymentRelayDataCodec)
      assert(recipientTlvsBob.outgoingChannelId == ShortChannelId(1))
      assert(recipientTlvsBob.amountToForward(110_125 msat) == 100_125.msat)
      assert(recipientTlvsBob.outgoingCltv(CltvExpiry(749150)) == CltvExpiry(749100))
      assert(recipientTlvsBob.records.get[RouteBlindingEncryptedDataTlv.PaymentRelay].contains(RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(50), 0, 10_000 msat)))
      assert(recipientTlvsBob.paymentConstraints == RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750150), 50 msat, Features.empty))

      // Carol is a blinded hop.
      // She receives the blinding key from Bob (e.g. in a tlv field in update_add_htlc) which she can use to derive the
      // private key corresponding to her blinded node ID and decrypt the onion.
      // The payload doesn't contain a shortChannelId or a nodeId to forward to, but the encrypted data does.
      val blindedPrivKeyCarol = RouteBlinding.derivePrivateKey(carol, blindingEphemeralKeyForCarol)
      val Right(DecryptedPacket(onionPayloadCarol, packetForDave, sharedSecretCarol)) = peel(blindedPrivKeyCarol, associatedData, packetForCarol)
      val tlvsCarol = PaymentOnionCodecs.tlvPerHopPayloadCodec.decode(onionPayloadCarol.bits).require.value
      assert(tlvsCarol.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
      val Success((recipientTlvsCarol, blindingEphemeralKeyForDave)) = RouteBlindingEncryptedDataCodecs.decode(carol, blindingEphemeralKeyForCarol, tlvsCarol.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data, RouteBlindingEncryptedDataCodecs.paymentRelayDataCodec)
      assert(recipientTlvsCarol.outgoingChannelId == ShortChannelId(2))
      assert(recipientTlvsCarol.amountToForward(100_125 msat) == 100_010.msat)
      assert(recipientTlvsCarol.outgoingCltv(CltvExpiry(749100)) == CltvExpiry(749025))
      assert(recipientTlvsCarol.records.get[RouteBlindingEncryptedDataTlv.PaymentRelay].contains(RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(75), 150, 100 msat)))
      assert(recipientTlvsCarol.paymentConstraints == RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750100), 50 msat, Features.empty))
      // Carol's payload contains a blinding override.
      val blindingEphemeralKeyForDaveOverride = recipientTlvsCarol.records.get[RouteBlindingEncryptedDataTlv.NextBlinding].map(_.blinding)
      assert(blindingEphemeralKeyForDaveOverride.contains(blindingOverride))
      assert(blindingEphemeralKeyForDave != blindingOverride)

      // Dave is a blinded hop.
      // He receives the blinding key from Carol (e.g. in a tlv field in update_add_htlc) which he can use to derive the
      // private key corresponding to his blinded node ID and decrypt the onion.
      val blindedPrivKeyDave = RouteBlinding.derivePrivateKey(dave, blindingOverride)
      val Right(DecryptedPacket(onionPayloadDave, packetForEve, sharedSecretDave)) = peel(blindedPrivKeyDave, associatedData, packetForDave)
      val tlvsDave = PaymentOnionCodecs.tlvPerHopPayloadCodec.decode(onionPayloadDave.bits).require.value
      assert(tlvsDave.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
      val Success((recipientTlvsDave, blindingEphemeralKeyForEve)) = RouteBlindingEncryptedDataCodecs.decode(dave, blindingOverride, tlvsDave.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data, RouteBlindingEncryptedDataCodecs.paymentRelayDataCodec)
      assert(recipientTlvsDave.outgoingChannelId == ShortChannelId(3))
      assert(recipientTlvsDave.amountToForward(100_010 msat) == 100_000.msat)
      assert(recipientTlvsDave.outgoingCltv(CltvExpiry(749025)) == CltvExpiry(749000))
      assert(recipientTlvsDave.records.get[RouteBlindingEncryptedDataTlv.PaymentRelay].contains(RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(25), 100, 0 msat)))
      assert(recipientTlvsDave.paymentConstraints == RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750025), 50 msat, Features.empty))

      // Eve is the blinded recipient.
      // She receives the blinding key from Dave (e.g. in a tlv field in update_add_htlc) which she can use to derive
      // the private key corresponding to her blinded node ID and decrypt the onion.
      val blindedPrivKeyEve = RouteBlinding.derivePrivateKey(eve, blindingEphemeralKeyForEve)
      val Right(DecryptedPacket(onionPayloadEve, packetForNobody, sharedSecretEve)) = peel(blindedPrivKeyEve, associatedData, packetForEve)
      val tlvsEve = PaymentOnionCodecs.tlvPerHopPayloadCodec.decode(onionPayloadEve.bits).require.value
      assert(tlvsEve.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
      val Success((recipientTlvsEve, _)) = RouteBlindingEncryptedDataCodecs.decode(eve, blindingEphemeralKeyForEve, tlvsEve.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data, RouteBlindingEncryptedDataCodecs.paymentRecipientDataCodec)
      assert(recipientTlvsEve.pathId_opt.contains(hex"c9cf92f45ade68345bc20ae672e2012f4af487ed4415"))
      assert(recipientTlvsEve.paymentConstraints == RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(750000), 50 msat, Features.empty))

      assert(Seq(onionPayloadAlice, onionPayloadBob, onionPayloadCarol, onionPayloadDave, onionPayloadEve) == payloads)
      assert(Seq(sharedSecretAlice, sharedSecretBob, sharedSecretCarol, sharedSecretDave, sharedSecretEve) == sharedSecrets.map(_._1))

      val packets = Seq(packetForBob, packetForCarol, packetForDave, packetForEve, packetForNobody)
      assert(packets(0).hmac == ByteVector32(hex"4bcd0f0d1e3883aeb94e6a5a1a5950943cf1419f4641bec641ca8d628fea25d9"))
      assert(packets(1).hmac == ByteVector32(hex"1827715602080882c82c4d0c0addc109dd61e26930e48d253f71ef89f663af16"))
      assert(packets(2).hmac == ByteVector32(hex"1359f3f6a6e3c243e8ecc28e6487defd7bec9682bb2d29a9256597f7787ad081"))
      assert(packets(3).hmac == ByteVector32(hex"ab5f1926681cf0932022161f90d709bebfe37fb16757290c161ce180f002e7ad"))
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

  // This test vector uses payloads with a fixed size.
  // origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4
  val referenceFixedSizePaymentPayloads = Seq(
    hex"000000000000000000000000000000000000000000000000000000000000000000",
    hex"000101010101010101000000000000000100000001000000000000000000000000",
    hex"000202020202020202000000000000000200000002000000000000000000000000",
    hex"000303030303030303000000000000000300000003000000000000000000000000",
    hex"000404040404040404000000000000000400000004000000000000000000000000"
  )

  // This test vector uses variable-size payloads intertwined with fixed-size payloads.
  // origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4
  val referenceVariableSizePaymentPayloads = Seq(
    hex"000000000000000000000000000000000000000000000000000000000000000000",
    hex"140101010101010101000000000000000100000001",
    hex"fd0100000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9fa0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebfc0c1c2c3c4c5c6c7c8c9cacbcccdcecfd0d1d2d3d4d5d6d7d8d9dadbdcdddedfe0e1e2e3e4e5e6e7e8e9eaebecedeeeff0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
    hex"140303030303030303000000000000000300000003",
    hex"000404040404040404000000000000000400000004000000000000000000000000"
  )

  // This test vector uses variable-sized payloads and fills the whole onion packet.
  // origin -> node #0 -> node #1 -> node #2 -> node #3 -> node #4
  val variableSizePaymentPayloadsFull = Seq(
    hex"8b09000000000000000030000000000000000000000000000000000000000000000000000000000025000000000000000000000000000000000000000000000000250000000000000000000000000000000000000000000000002500000000000000000000000000000000000000000000000025000000000000000000000000000000000000000000000000",
    hex"fd012a08000000000000009000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000200000000000000000000000000000000000000020000000000000000000000000000000000000002000000000000000000000000000000000000000",
    hex"620800000000000000900000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
    hex"fc120000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000240000000000000000000000000000000000000000000000",
    hex"fd01582200000000000000000000000000000000000000000022000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000300000000000000000000000000000000000000000000000000000000000"
  )

  // This test vector uses a single variable-sized payload filling the whole onion payload.
  // origin -> recipient
  val variableSizeOneHopPaymentPayload = Seq(
    hex"fd04f16500000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
  )

  // This test vector uses trampoline variable-size payloads.
  val trampolinePaymentPayloads = Seq(
    hex"2a 02020231 040190 f8210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c",
    hex"35 fa 33 010000000000000000000000040000000000000000000000000ff0000000000000000000000000000000000000000000000000",
    hex"23 f8 21 032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991",
    hex"00 0303030303030303 0000000000000003 00000003 000000000000000000000000",
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
