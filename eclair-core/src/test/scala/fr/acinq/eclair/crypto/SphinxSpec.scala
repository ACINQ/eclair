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
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, ShortChannelId, UInt64, randomKey}
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
    val sessionKey = PrivateKey(hex"0101010101010101010101010101010101010101010101010101010101010101")
    val blindedRoute = RouteBlinding.create(sessionKey, publicKeys, routeBlindingPayloads)
    assert(blindedRoute.introductionNode.publicKey == publicKeys(0))
    assert(blindedRoute.introductionNodeId == publicKeys(0))
    assert(blindedRoute.introductionNode.blindedPublicKey == PublicKey(hex"02ec68ed555f5d18b12fe0e2208563c3566032967cf11dc29b20c345449f9a50a2"))
    assert(blindedRoute.introductionNode.blindingEphemeralKey == PublicKey(hex"031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"))
    assert(blindedRoute.introductionNode.encryptedPayload == hex"af4fbf67bd52520bdfab598ddfea294cc257f3a2a2e8339e4a9a5f580ea5f90c0e175d34435bf9cccb1b6834cd2c04f94b52eb1dc1")
    assert(blindedRoute.blindedNodeIds == Seq(
      PublicKey(hex"02ec68ed555f5d18b12fe0e2208563c3566032967cf11dc29b20c345449f9a50a2"),
      PublicKey(hex"022b09d77fb3374ee3ed9d2153e15e9962944ad1690327cbb0a9acb7d90f168763"),
      PublicKey(hex"03d9f889364dc5a173460a2a6cc565b4ca78931792115dd6ef82c0e18ced837372"),
      PublicKey(hex"03bfddd2253b42fe12edd37f9071a3883830ed61a4bc347eeac63421629cf032b5"),
      PublicKey(hex"03a8588bc4a0a2f0d2fb8d5c0f8d062fb4d78bfba24a85d0ddeb4fd35dd3b34110"),
    ))
    assert(blindedRoute.subsequentNodes.map(_.blindedPublicKey) == Seq(
      PublicKey(hex"022b09d77fb3374ee3ed9d2153e15e9962944ad1690327cbb0a9acb7d90f168763"),
      PublicKey(hex"03d9f889364dc5a173460a2a6cc565b4ca78931792115dd6ef82c0e18ced837372"),
      PublicKey(hex"03bfddd2253b42fe12edd37f9071a3883830ed61a4bc347eeac63421629cf032b5"),
      PublicKey(hex"03a8588bc4a0a2f0d2fb8d5c0f8d062fb4d78bfba24a85d0ddeb4fd35dd3b34110"),
    ))
    assert(blindedRoute.encryptedPayloads == blindedRoute.introductionNode.encryptedPayload +: blindedRoute.subsequentNodes.map(_.encryptedPayload))
    assert(blindedRoute.subsequentNodes.map(_.encryptedPayload) == Seq(
      hex"14779694e8dfde2a54fc43e8b9a371f437edda7ed5a3e0833e7773979352a602e43e07e36580ba2b6c1ed45b4379c934f7c052dc3c23a17c6cb9fd8ed2a1",
      hex"89d5d5d448f15208452b1c61f9067f6ce26f01ec5de5871cf35b89d04edc2e06b38a444b59e46922aceef708e50068e73fa237ee46c7eac8b21d7f454a26",
      hex"52a65a884542d180e76fec6ec1dbda1d09d1c5c087b28a286bba75cf27e30d3dd56247382464f272fd4c5319f7899a8ecfa8d8b515b1d5455e6f91776076",
      hex"6d63852689b304bd9570db125907c56052af4acd681d9d0882728c6f399ace90392b27031c7d98189e5655b3f15cf79b873d8b5d64bbbd6908c66b3490589503",
    ))

    // The introduction point can decrypt its encrypted payload and obtain the next ephemeral public key.
    val Success((payload0, ephKey1)) = RouteBlinding.decryptPayload(privKeys(0), blindedRoute.introductionNode.blindingEphemeralKey, blindedRoute.encryptedPayloads(0))
    assert(payload0 == routeBlindingPayloads(0))
    assert(ephKey1 == PublicKey(hex"035cb4c003d58e16cc9207270b3596c2be3309eca64c36b208c946bbb599bfcad0"))

    // The next node can derive the private key used to unwrap the onion and decrypt its encrypted payload.
    assert(RouteBlinding.derivePrivateKey(privKeys(1), ephKey1).publicKey == blindedRoute.blindedNodeIds(1))
    val Success((payload1, ephKey2)) = RouteBlinding.decryptPayload(privKeys(1), ephKey1, blindedRoute.encryptedPayloads(1))
    assert(payload1 == routeBlindingPayloads(1))
    assert(ephKey2 == PublicKey(hex"02e105bc01a7af07074a1b0b1d9a112a1d89c6cd87cc4e2b6ba3a824731d9508bd"))

    // The next node can derive the private key used to unwrap the onion and decrypt its encrypted payload.
    assert(RouteBlinding.derivePrivateKey(privKeys(2), ephKey2).publicKey == blindedRoute.blindedNodeIds(2))
    val Success((payload2, ephKey3)) = RouteBlinding.decryptPayload(privKeys(2), ephKey2, blindedRoute.encryptedPayloads(2))
    assert(payload2 == routeBlindingPayloads(2))
    assert(ephKey3 == PublicKey(hex"0349164db5398925ef234002e62d2834da115b8eafc73436fab98ed12266e797cc"))

    // The next node can derive the private key used to unwrap the onion and decrypt its encrypted payload.
    assert(RouteBlinding.derivePrivateKey(privKeys(3), ephKey3).publicKey == blindedRoute.blindedNodeIds(3))
    val Success((payload3, ephKey4)) = RouteBlinding.decryptPayload(privKeys(3), ephKey3, blindedRoute.encryptedPayloads(3))
    assert(payload3 == routeBlindingPayloads(3))
    assert(ephKey4 == PublicKey(hex"020a6d1951916adcac22125063f62c35b3686f36e5db2f77073f3d35b19c7a118a"))

    // The last node can derive the private key used to unwrap the onion and decrypt its encrypted payload.
    assert(RouteBlinding.derivePrivateKey(privKeys(4), ephKey4).publicKey == blindedRoute.blindedNodeIds(4))
    val Success((payload4, _)) = RouteBlinding.decryptPayload(privKeys(4), ephKey4, blindedRoute.encryptedPayloads(4))
    assert(payload4 == routeBlindingPayloads(4))
  }

  test("concatenate blinded routes (reference test vector)") {
    // The recipient creates a blinded route to himself.
    val (blindingOverride, blindedRouteEnd, payloadsEnd) = {
      val sessionKey = PrivateKey(hex"0101010101010101010101010101010101010101010101010101010101010101")
      val payloads = Seq(
        hex"0421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991",
        hex"042102edabbd16b41c8371b92ef2f04c1185b4f03b6dcd52ba9b78d9d7c89c8f221145",
        hex"010f000000000000000000000000000000 061000112233445566778899aabbccddeeff"
      )
      val blindedRoute = RouteBlinding.create(sessionKey, publicKeys.drop(2), payloads)
      assert(blindedRoute.blindingKey == PublicKey(hex"031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"))
      (blindedRoute.blindingKey, blindedRoute, payloads)
    }
    // The sender also wants to use route blinding to reach the introduction point.
    val (blindedRouteStart, payloadsStart) = {
      val sessionKey = PrivateKey(hex"0202020202020202020202020202020202020202020202020202020202020202")
      val payloads = Seq(
        hex"0121000000000000000000000000000000000000000000000000000000000000000000 04210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c",
        // NB: this payload contains the blinding key override.
        hex"0421027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007 0821031b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f"
      )
      (RouteBlinding.create(sessionKey, publicKeys.take(2), payloads), payloads)
    }
    val blindedRoute = BlindedRoute(publicKeys(0), blindedRouteStart.blindingKey, blindedRouteStart.blindedNodes ++ blindedRouteEnd.blindedNodes)
    assert(blindedRoute.blindingKey == PublicKey(hex"024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d0766"))
    assert(blindedRoute.blindedNodeIds == Seq(
      PublicKey(hex"0303176d13958a8a59d59517a6223e12cf291ba5f65c8011efcdca0a52c3850abc"),
      PublicKey(hex"03adbdd3c0fb69641e96de2d5ac923ffc0910d3ed4dfe2314609fae61a71df4da2"),
      PublicKey(hex"021026e6369e42b7f6d723c0c56a3e0b4d67111f07685bd03e9fa6d93ac6bb6dbe"),
      PublicKey(hex"02ba3db3fe7f1ed28c4d82f28cf358373cbf3241a16aba265b1b6fb26f094c0c7f"),
      PublicKey(hex"0379d4ca14cb19e2f7bcb217d36267e3d03b027bc4228923967f5b2e32cbb763c1"),
    ))
    assert(blindedRoute.encryptedPayloads == Seq(
      hex"31da0d438752ed0f19ccd970a386ead7155fd187becd4e1770d561dffdb03d3568dac746dde98725f146582cb040207e8b6c070e28d707564a4dd9fb53f9274ad69d09add393b509a2fa42df5055d7c8aeda5881d5aa",
      hex"d9dfa92f898dc8e37b73c944aa4205f225337b2edde67623e775c79e2bcf395dc205004aa07fdc65712afa5c2687aff9bb3d5e6af7c89cc94f23f962a27844ce7629773f9413ebcf131dbc35818410df207f29b013b0",
      hex"30015dcdcbce70bdcd0125be8ccd541b101d95bcb049ccfc737f91c98cc139cb6f16354ec5a38e77eca769c2245ac4467524d6",
      hex"11e49a0e5f4f8a73b30551bd20448abeb297339b6983ab30d4a227a858311656cbf2444aeff66bd4c8f320ce00ce4ddfed7ca3",
      hex"fe7e62b65ac8e1c2a319ba53a5519b3f8073416971ae3e722ebc008f38999d590d70d40557e44557c0d32b891bd967119c1f78",
    ))

    // The introduction point can decrypt its encrypted payload and obtain the next ephemeral public key.
    val Success((payload0, ephKey1)) = RouteBlinding.decryptPayload(privKeys(0), blindedRoute.blindingKey, blindedRoute.encryptedPayloads(0))
    assert(payload0 == payloadsStart(0))
    assert(ephKey1 == PublicKey(hex"02be4b436dbc6cfa43d7d5652bc630ffdaf0dac93e6682db7950828506055ad1a7"))

    // The next node can derive the private key used to unwrap the onion and decrypt its encrypted payload.
    assert(RouteBlinding.derivePrivateKey(privKeys(1), ephKey1).publicKey == blindedRoute.blindedNodeIds(1))
    val Success((payload1, ephKey2)) = RouteBlinding.decryptPayload(privKeys(1), ephKey1, blindedRoute.encryptedPayloads(1))
    assert(payload1 == payloadsStart(1))
    assert(ephKey2 == PublicKey(hex"03fb82254d740754efddc3318674f4e26cefcb8dec42a3910c08c64d19f25e50b7"))
    // NB: this node finds a blinding override and will transmit that instead of ephKey2 to the next node.
    assert(payload1.containsSlice(blindingOverride.value))

    // The next node must be given the blinding override to derive the private key used to unwrap the onion and decrypt its encrypted payload.
    assert(RouteBlinding.decryptPayload(privKeys(2), ephKey2, blindedRoute.encryptedPayloads(2)).isFailure)
    assert(RouteBlinding.derivePrivateKey(privKeys(2), blindingOverride).publicKey == blindedRoute.blindedNodeIds(2))
    val Success((payload2, ephKey3)) = RouteBlinding.decryptPayload(privKeys(2), blindingOverride, blindedRoute.encryptedPayloads(2))
    assert(payload2 == payloadsEnd(0))
    assert(ephKey3 == PublicKey(hex"03932f4ab7605e8c046b5677becd4d61fdfdc8b9d10f1e9c3080ced0d64fd76931"))

    // The next node can derive the private key used to unwrap the onion and decrypt its encrypted payload.
    assert(RouteBlinding.derivePrivateKey(privKeys(3), ephKey3).publicKey == blindedRoute.blindedNodeIds(3))
    val Success((payload3, ephKey4)) = RouteBlinding.decryptPayload(privKeys(3), ephKey3, blindedRoute.encryptedPayloads(3))
    assert(payload3 == payloadsEnd(1))
    assert(ephKey4 == PublicKey(hex"037bceb365470d24f8204c622e1b7959c6beeb774c634640de6c8401079159fc58"))

    // The last node can derive the private key used to unwrap the onion and decrypt its encrypted payload.
    assert(RouteBlinding.derivePrivateKey(privKeys(4), ephKey4).publicKey == blindedRoute.blindedNodeIds(4))
    val Success((payload4, ephKey5)) = RouteBlinding.decryptPayload(privKeys(4), ephKey4, blindedRoute.encryptedPayloads(4))
    assert(payload4 == payloadsEnd(2))
    assert(ephKey5 == PublicKey(hex"0339ddfa85a2155fb27e94742885fad85696e54920aa148cb86e00bcb8ee346bd4"))
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

  test("create payment packet to blinded route (reference test vector)") {
    // The recipient creates a blinded route containing 3 hops.
    val (blindedRoute, blindingEphemeralKey0) = {
      val recipientSessionKey = PrivateKey(hex"0101010101010101010101010101010101010101010101010101010101010101")
      (RouteBlinding.create(recipientSessionKey, publicKeys.drop(2), routeBlindingPayloads.drop(2)), recipientSessionKey.publicKey)
    }

    // The sender obtains this information (e.g. from a Bolt11 invoice) and prepends two normal hops to reach the introduction node.
    val nodeIds = publicKeys.take(2) ++ Seq(blindedRoute.introductionNodeId) ++ blindedRoute.subsequentNodes.map(_.blindedPublicKey)
    assert(blindedRoute.encryptedPayloads == Seq(
      hex"352a5ee1c0b289eee9a158bbb74e701a64c19997505c1f9f67c9370e7b14dc4b0b13a45745bf1c6105c0a5d775ae6524685a1d5c9de9f40a062eef82fb2a",
      hex"14cf98e3f4f29cc7af8620040cbe7af2a312876b5b6c6c6a6e5b5f718d5a8b30e5ef0130b743d630f8000aea438646bac953b4604745a70a56ef49be6b08",
      hex"f95120f4188aa380e15bf811e713d97dc237132b22ce4f7439983545e37164d792dc276be18e23e217eec00a3380051f7d585df984f36bc1d87f82073afac623",
    ))
    val payloads = Seq(
      // The sender sends normal onion payloads to the first two hops.
      TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.AmountToForward(MilliSatoshi(500)), OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(1000)), OnionPaymentPayloadTlv.OutgoingChannelId(ShortChannelId(10))),
      TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.AmountToForward(MilliSatoshi(450)), OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(900)), OnionPaymentPayloadTlv.OutgoingChannelId(ShortChannelId(15))),
    ).map(tlvs => PaymentOnionCodecs.tlvPerHopPayloadCodec.encode(tlvs).require.bytes) ++ Seq(
      // The sender includes the blinding key and the first encrypted recipient data in the introduction node's payload.
      TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.BlindingPoint(blindingEphemeralKey0), OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads(0))),
      // The sender includes the correct encrypted recipient data in each blinded node's payload.
      TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads(1))),
      TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.EncryptedRecipientData(blindedRoute.encryptedPayloads(2))),
    ).map(tlvs => PaymentOnionCodecs.tlvPerHopPayloadCodec.encode(tlvs).require.bytes)

    val senderSessionKey = PrivateKey(hex"0202020202020202020202020202020202020202020202020202020202020202")
    val Success(PacketAndSecrets(onion, sharedSecrets)) = create(senderSessionKey, 1300, nodeIds, payloads, associatedData)
    assert(serializePaymentOnion(onion) == hex"00024d4b6cd1361032ca9bd2aeb9d900aa4d45d9ead80ac9423374c451a7254d07666a78b866fda75eb6a991f9815c47624f46324b6f18f0a42879ffc3a36340d17d83fcb67df5d9a3c6a4ee5fca292b738f3bdfbf77aa103ebb2a5f054f3d9271e7bf2f0cbe7c4dd637093a380e313332c41a50875ac29bcb815d39c60b02217430acd952f656c923db95af5aab89026866053fa49e8e81f3d7a4d1006f8885ee0e86cdaa862a6fe96256bb256fb14880c327c1d5a84378d060879c63a9277ace5dcf1a29b5f212f5e42ed69d91294a547a5d6972139c6a3aa9255c25b061f29462edeb6d91c61cbe04de3ee7e5338218826e4b1a196703516dc9975ac91165acca35b2aefd9778287928f99d59d3c8fa1e3cf045998b1b7eded5b1e45e9f785e8898c3d8a0e3c7f1dea96b05b98e9d45fa2fa3e9d8beb15a6c9e9c87fd5c76d08f7f6a45fd325a2f6801a02f6eedd0d55a03b38f15b87deac8f9e832853facb6f31ef44570e94d6adbb1b8da787aadffa6994cedc8eb4b04ba95a26e3ad3c32defbbb3dac455c1d70cc01f77ee7df26456430a7b2a46d7fb67f94502a7562abaad746d3df6649d738b7b34892f03b9dcb21a6be67584dd1e925bae77876a690a49c0ff809396a7d5fc0c144b85c47dd30db690b83d3bef252e9102568f39f115efe79dec527a72bb26234a2cc7c8da233cea6506c0610b57e3110d97dd63ef8912e052f2ccc46faa083951684aee03c2b4381ba13b31c41dc1b0eb5a6baa923966a4bcf8a34e9a7cc1d1e93914ed7c101402fe2b137eadea2794ba09008d20c639b596d42282623dc0039274831be79e4d2891b10debc4bba90f29b4bd5d80cb7b007bc0397b8b66eb188142e89179bdeb6a581f075726ae37ad37d690bfc8734d97c9ae52b08a891cd6dc0a6bcdb08271eaa29a14672c04e8a6c2167ac479361bb248ddd14b30f286b43210afb375ac0a4b8ec314b0f88968906a952dca5cc46d7720d8d75249a6909d3cb3afcbcdb846297e4f0b269a49450e0b73094e820868269afc3c5be5216051dfcc91b3fa7ae223a5ee2e1c36bcb9357a6b9c5a121eded3bd993f2c4e8990bccce1337369f77a5f951b4d30aa136cc9307097deeb48bdd1790c86c0ecd936229cc6770c989dc06aa4ea0a881b6ca605184b341522e444da1aaee54e23e28600b8a675257a30ff0c397cfb0d84cba24731a2170db2e702b1a78bb5fb710073c063cb677bf9814cabc12e11cd9074f891a383e5ad4e370359a675ac24a5cb395d7605cc75a106f0aa57a2a21ee5dfa0c6d44e222f6ab02aa13ea2114c7d1aad76d6447099376bbb61a0f02836385b30a5be9f2b8800cdd3c0e535aac1482d40fc895f202b7363df276b8efadddf6667e9d6961daabc20a48039fe7232890e5b5d28eff2c90eda7839aabaefd34c1ea7e482a4670c610fab72b4741768cb8b4aeb76c2fc3b5cdd74ae31ac05bf9de893037bd76f4645dd2fda23e1f1c26dd1ee721ab24723f426f44cdacecb2c3d22200a8e98b02e0f60271ceb00783b20c5b5e2a44774e88390706885dd3074f5a845b35cae73eb87413977e3e5a075ecd68336f4a93128ef9f4f782ec3e05b45e8ec09ff8f79db4a06de628438d3e15374f069eccc15b61482101c8331811a722b20a8abc4d91135180e0dbaa8dc7938c5b52e2cdf831bbb5fd981e51850715528f86a87e2e3e322ae2efe98e891f379587c4e5fbe9cd6bae707bf0f6d17778fe4f73e53c6b12cadc84c48ae3b16dd3b8c398d29f7a13794b18ffc09af1021bd33b339a6a37847d44b655715bd69767a3e47c49410f947ba84304dbf84707e5928253458be403eb0fa3760b9e8f52f0beb79c4cf5548bc1e3eb12a0c07e0f0bb232bef80d7a03912e9e4221e03977cdd2634e4547cbbc8e83febd5fb3c45")

    // The first two hops can decrypt the onion as usual.
    val Right(DecryptedPacket(payload0, nextPacket0, sharedSecret0)) = peel(privKeys(0), associatedData, onion)
    val Right(DecryptedPacket(payload1, nextPacket1, sharedSecret1)) = peel(privKeys(1), associatedData, nextPacket0)

    // The third hop is the introduction node.
    // It can decrypt the onion as usual, but the payload doesn't contain a shortChannelId or a nodeId to forward to.
    // However it contains a blinding point and encrypted data, which it can decrypt to discover the next node.
    val Right(DecryptedPacket(payload2, nextPacket2, sharedSecret2)) = peel(privKeys(2), associatedData, nextPacket1)
    val tlvs2 = PaymentOnionCodecs.tlvPerHopPayloadCodec.decode(payload2.bits).require.value
    assert(tlvs2.get[OnionPaymentPayloadTlv.BlindingPoint].map(_.publicKey) == Some(blindingEphemeralKey0))
    assert(tlvs2.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
    val Success((recipientData2, blindingEphemeralKey1)) = RouteBlindingEncryptedDataCodecs.decode(privKeys(2), blindingEphemeralKey0, tlvs2.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data, RouteBlindingEncryptedDataCodecs.paymentRelayDataCodec)
    assert(recipientData2.outgoingChannelId == ShortChannelId(1105))

    // The fourth hop is a blinded hop.
    // It receives the blinding key from the previous node (e.g. in a tlv field in update_add_htlc) which it can use to
    // derive the private key corresponding to its blinded node ID and decrypt the onion.
    // The payload doesn't contain a shortChannelId or a nodeId to forward to, but the encrypted data does.
    val blindedPrivKey3 = RouteBlinding.derivePrivateKey(privKeys(3), blindingEphemeralKey1)
    val Right(DecryptedPacket(payload3, nextPacket3, sharedSecret3)) = peel(blindedPrivKey3, associatedData, nextPacket2)
    val tlvs3 = PaymentOnionCodecs.tlvPerHopPayloadCodec.decode(payload3.bits).require.value
    assert(tlvs3.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
    val Success((recipientData3, blindingEphemeralKey2)) = RouteBlindingEncryptedDataCodecs.decode(privKeys(3), blindingEphemeralKey1, tlvs3.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data, RouteBlindingEncryptedDataCodecs.paymentRelayDataCodec)
    assert(recipientData3.outgoingChannelId == ShortChannelId(2434))

    // The fifth hop is the blinded recipient.
    // It receives the blinding key from the previous node (e.g. in a tlv field in update_add_htlc) which it can use to
    // derive the private key corresponding to its blinded node ID and decrypt the onion.
    val blindedPrivKey4 = RouteBlinding.derivePrivateKey(privKeys(4), blindingEphemeralKey2)
    val Right(DecryptedPacket(payload4, nextPacket4, sharedSecret4)) = peel(blindedPrivKey4, associatedData, nextPacket3)
    val tlvs4 = PaymentOnionCodecs.tlvPerHopPayloadCodec.decode(payload4.bits).require.value
    assert(tlvs4.get[OnionPaymentPayloadTlv.EncryptedRecipientData].nonEmpty)
    val Success((recipientData4, _)) = RouteBlindingEncryptedDataCodecs.decode(privKeys(4), blindingEphemeralKey2, tlvs4.get[OnionPaymentPayloadTlv.EncryptedRecipientData].get.data, RouteBlindingEncryptedDataCodecs.paymentRecipientDataCodec)
    assert(recipientData4.pathId_opt == associatedData.map(_.bytes))

    assert(Seq(payload0, payload1, payload2, payload3, payload4) == payloads)
    assert(Seq(sharedSecret0, sharedSecret1, sharedSecret2, sharedSecret3, sharedSecret4) == sharedSecrets.map(_._1))

    val packets = Seq(nextPacket0, nextPacket1, nextPacket2, nextPacket3, nextPacket4)
    assert(packets(0).hmac == ByteVector32(hex"1f28db49abf4668055904cd45a324988d011ca797c6c55e62b1154cc1f6d31ad"))
    assert(packets(1).hmac == ByteVector32(hex"c5878c3fb9cf1c4e524c5679687b314e5180fdc3133bc5e0f6c26fd59141e990"))
    assert(packets(2).hmac == ByteVector32(hex"2c15f0b86190ae242df9141eabb04926b09125f9170cb6606c2e14cd5c6a46d8"))
    assert(packets(3).hmac == ByteVector32(hex"b0a89249eaae4186401e5fa2602851a8659ed6ebcb6852a1b550088b9e41406c"))
    assert(packets(4).hmac == ByteVector32(hex"0000000000000000000000000000000000000000000000000000000000000000"))
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
