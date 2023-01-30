/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.message

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.PacketAndSecrets
import fr.acinq.eclair.message.OnionMessages._
import fr.acinq.eclair.wire.protocol.MessageOnionCodecs.perHopPayloadCodec
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.EncryptedData
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv._
import fr.acinq.eclair.wire.protocol.{OnionMessage, RouteBlindingEncryptedDataCodecs, RouteBlindingEncryptedDataTlv, TlvStream}
import fr.acinq.eclair.{randomBytes, randomKey}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.util.Success

/**
 * Created by thomash on 23/09/2021.
 */

class OnionMessagesSpec extends AnyFunSuite {

  test("single-hop onion message without path_id") {
    val nodeKey = randomKey()
    val sessionKey = randomKey()
    val blindingSecret = randomKey()
    val destination = randomKey()
    val Right((nextNodeId, message)) = buildMessage(nodeKey, sessionKey, blindingSecret, Nil, Recipient(destination.publicKey, None), TlvStream.empty)
    assert(nextNodeId == destination.publicKey)

    process(destination, message) match {
      case ReceiveMessage(finalPayload) => assert(finalPayload.pathId_opt.isEmpty)
      case x => fail(x.toString)
    }
  }

  test("multi-hop onion message (reference test vector)") {
    val alice = PrivateKey(hex"414141414141414141414141414141414141414141414141414141414141414101")
    assert(alice.publicKey == PublicKey(hex"02eec7245d6b7d2ccb30380bfbe2a3648cd7a942653f5aa340edcea1f283686619"))
    val bob = PrivateKey(hex"424242424242424242424242424242424242424242424242424242424242424201")
    assert(bob.publicKey == PublicKey(hex"0324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c"))
    val carol = PrivateKey(hex"434343434343434343434343434343434343434343434343434343434343434301")
    assert(carol.publicKey == PublicKey(hex"027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007"))
    val dave = PrivateKey(hex"444444444444444444444444444444444444444444444444444444444444444401")
    assert(dave.publicKey == PublicKey(hex"032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991"))

    val blindingSecret = PrivateKey(hex"050505050505050505050505050505050505050505050505050505050505050501")
    assert(blindingSecret.publicKey == PublicKey(hex"0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f7"))
    val blindingOverride = PrivateKey(hex"070707070707070707070707070707070707070707070707070707070707070701")
    assert(blindingOverride.publicKey == PublicKey(hex"02989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f"))

    //  Building the onion manually
    val messageForAlice = TlvStream[RouteBlindingEncryptedDataTlv](OutgoingNodeId(bob.publicKey))
    val encodedForAlice = blindedRouteDataCodec.encode(messageForAlice).require.bytes
    assert(encodedForAlice == hex"04210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")
    val messageForBob = TlvStream[RouteBlindingEncryptedDataTlv](OutgoingNodeId(carol.publicKey), NextBlinding(blindingOverride.publicKey))
    val encodedForBob = blindedRouteDataCodec.encode(messageForBob).require.bytes
    assert(encodedForBob == hex"0421027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007082102989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f")
    val messageForCarol = TlvStream[RouteBlindingEncryptedDataTlv](Padding(hex"0000000000000000000000000000000000000000000000000000000000000000000000"), OutgoingNodeId(dave.publicKey))
    val encodedForCarol = blindedRouteDataCodec.encode(messageForCarol).require.bytes
    assert(encodedForCarol == hex"012300000000000000000000000000000000000000000000000000000000000000000000000421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
    val messageForDave = TlvStream[RouteBlindingEncryptedDataTlv](PathId(hex"01234567"))
    val encodedForDave = blindedRouteDataCodec.encode(messageForDave).require.bytes
    assert(encodedForDave == hex"060401234567")

    // Building blinded path Carol -> Dave
    val routeFromCarol = Sphinx.RouteBlinding.create(blindingOverride, carol.publicKey :: dave.publicKey :: Nil, encodedForCarol :: encodedForDave :: Nil).route

    // Building blinded path Alice -> Bob
    val routeToCarol = Sphinx.RouteBlinding.create(blindingSecret, alice.publicKey :: bob.publicKey :: Nil, encodedForAlice :: encodedForBob :: Nil).route

    val publicKeys = routeToCarol.blindedNodes.map(_.blindedPublicKey) concat routeFromCarol.blindedNodes.map(_.blindedPublicKey)
    val encryptedPayloads = routeToCarol.encryptedPayloads ++ routeFromCarol.encryptedPayloads
    val payloads = encryptedPayloads.map(encTlv => perHopPayloadCodec.encode(TlvStream(EncryptedData(encTlv))).require.bytes)
    val expectedPayloads = List(
      hex"3504336970e870b473ddbc27e3098bfa45bb1aa54f1f637f803d957e6271d8ffeba89da2665d62123763d9b634e30714144a1c165ac9",
      hex"5804561630da85e8759b8f3b94d74a539c6f0d870a87cf03d4986175865a2985553c997b560c32613bd9184c1a6d41a37027aabdab5433009d8409a1b638eb90373778a05716af2c2140b3196dca23997cdad4cfa7a7adc8d4",
      hex"5a04588285acbceb37dfb38b877a888900539be656233cd74a55c55344fb068f9d8da365340d21db96fb41b76123207daeafdfb1f571e3fea07a22e10da35f03109a0380b3c69fcbed9c698086671809658761cf65ecbc3c07a2e5",
      hex"180416a20771fd5ff63f8ee26fac46c9de93cf6bd5916a928c"
    )
    assert(payloads == expectedPayloads)

    val sessionKey = PrivateKey(hex"090909090909090909090909090909090909090909090909090909090909090901")

    val PacketAndSecrets(packet, _) = Sphinx.create(sessionKey, 1300, publicKeys, payloads, None).get
    assert(packet.hmac == ByteVector32(hex"d84e7135092450c8cc98bb969aa6d9127dd07da53a3c46b2e9339d111f5f301d"))
    assert(packet.publicKey == PublicKey(hex"0256b328b30c8bf5839e24058747879408bdb36241dc9c2e7c619faa12b2920967").value)
    assert(packet.payload == hex"37d167dcefdb678725cb8074d3224dfe235ba3f22f71ac8a2c9d1398b1175295b1dd3f14c02d698021e8a8856637306c6f195e01494e6d75bfc0812f3f6d74e4dce347ffc1c8e01595fa595f68f3e7358aad4bf2d9412e9f307a25b6d5e4045174551b1c867264d3905e4f05b2e5bcfed7e7276660bf7e956bce5afa3d5e7e4c15883b856bc93dd9d6a968838ef51314d38dd41e5ab84b8846dca3c61d87e54c0ecf116b3cd5b3f1fcfbba3067cc329437cb301749447ad106f43955a643b52c66d465fc7abd2add1ab398aa63c890ae3dc564395bb7a4bbe28325ccdb07503285dacf90b5e09f4e455fb42459741f9d497000298b99f1e70adc28f59a1be85a96952f27b6a6c5d6a08822b4f5cae05daa6c2ce2f8ca5fdd4e8f0df46b94791b3159fe8eace11bcf8d58b532967a024f7e7e85929456a1332d9139ce7de92b9a5985acab8cd7630c9a0580bfd74b28e7ce5bd25e63e7ae369795dfe74c21e24b8bbf02d1f4eb8fbd86920f41d573488abe059166aabbc3be187c435423ead6a5473994e0246efe76e419893aa2d7566b2645f3496d97585de9c92b8c5a5226398cc459ce84abc02fe2b45b5ecaf21961730d4a34bbe6fdfe720e71e3d81a494c01080d8039360d534c6ee5a3c47a1874e526969add9126b30d9192f85ba45bcfd7029cc7560f0e25e14b5deaa805360c4967705e85325ac055922863470f5397e8404022488caebf9204acd6cb02a11088aebf7e497b4ff1172f0a9c6bf980914cc4eb42fc78b457add549abf1134f84922b217502938b42d10b35079f44c5168d4c3e9fe7ca8094ef72ed73ef84f1d3530b6b3545f9f4f013e7e8cbcf2619f57754a7380ce6a9532ee14c55990faa43df6c09530a314b5f4ce597f5ec9b776e8597ce258ac47dac43bd3ac9e52788ff3a66b7dc07cd1bc3e6d197339d85fa8d3d6c3054dd1a5e416c714b544de6eb55209e40e3cac412a51748370160d2d73b6d97abd62f7bae70df27cd199c511fa693019c5717d471e934906b98cd974fda4dd1cb5e2d721044a0be2bdf24d0971e09f2f39488fe389fc5230699b4df7cec7447e5be4ea49bd7c3fe1a5ec7358510dc1dd9c1a8da68c0863188d80549e49f7c00f57d2009b2427b2aed1569603fc247734039469f9fdf3ddd3a22fa95c5d8066a468327a02b474c9915419af82c8edc67686984767fe7885207c6820f6c2e57cb8fd0bcb9981ebc8065c74e970a5d593c3b73ee25a0877ca096a9f7edfee6d43bd817c7d415fea9abb6f206c61aa36942df9318762a76b9da26d0d41a0ae9eee042a175f82dc134bf6f2d46a218db358d6852940e6e30df4a58ac6cb409e7ce99afe1e3f42768bd617af4d0a235d0ba0dd5075f9cc091784395d30e7e42d4e006db21bea9b45d1f122b75c051e84e2281573ef54ebad053218fff0cc28ea89a06adc218d4134f407654990592e75462f5ee4a463c1e46425222d48761162da8049613cafd7ecc52ff8024e9d58512b958e3a3d12dede84e1441247700bca0f992875349448b430683c756438fd4e91f3d44f3cf624ed21f3c63cf92615ecc201d0cd3159b1b3fccd8f29d2daba9ac5ba87b1dd2f83323a2b2d3176b803ce9c7bdc4bae615925eb22a213df1eeb2f8ff95586536caf042d565984aacf1425a120a5d8d7a9cbb70bf4852e116b89ff5b198d672220af2be4246372e7c3836cf50d732212a3e3346ff92873ace57fa687b2b1aab3e8dc6cb9f93f865d998cff0a1680d9012a9597c90a070e525f66226cc287814f4ac4157b15a0b25aa110946cd69fd404fafd5656669bfd1d9e509eabc004c5a")
    val onionForAlice = OnionMessage(blindingSecret.publicKey, packet)

    // Building the onion with functions from `OnionMessages`
    val replyPath = buildRoute(blindingOverride, IntermediateNode(carol.publicKey, padding = Some(hex"0000000000000000000000000000000000000000000000000000000000000000000000")) :: Nil, Recipient(dave.publicKey, pathId = Some(hex"01234567")))
    assert(replyPath == routeFromCarol)
    val Right((_, message)) = buildMessage(randomKey(), sessionKey, blindingSecret, IntermediateNode(alice.publicKey) :: IntermediateNode(bob.publicKey) :: Nil, BlindedPath(replyPath), TlvStream.empty)
    assert(message == onionForAlice)

    // Checking that the onion is relayed properly
    process(alice, onionForAlice) match {
      case SendMessage(nextNodeId, onionForBob) =>
        assert(nextNodeId == bob.publicKey)
        process(bob, onionForBob) match {
          case SendMessage(nextNodeId, onionForCarol) =>
            assert(nextNodeId == carol.publicKey)
            process(carol, onionForCarol) match {
              case SendMessage(nextNodeId, onionForDave) =>
                assert(nextNodeId == dave.publicKey)
                process(dave, onionForDave) match {
                  case ReceiveMessage(finalPayload) => assert(finalPayload.pathId_opt.contains(hex"01234567"))
                  case x => fail(x.toString)
                }
              case x => fail(x.toString)
            }
          case x => fail(x.toString)
        }
      case x => fail(x.toString)
    }
  }

  test("relay message from alice to bob") {
    val alice = PrivateKey(hex"414141414141414141414141414141414141414141414141414141414141414101")
    val bob = PrivateKey(hex"424242424242424242424242424242424242424242424242424242424242424201")
    val blindingSecret = PrivateKey(hex"050505050505050505050505050505050505050505050505050505050505050501")
    val blindingKey = PublicKey(hex"0362c0a046dacce86ddd0343c6d3c7c79c2208ba0d9c9cf24a6d046d21d21f90f7")
    assert(blindingSecret.publicKey == blindingKey)
    val sharedSecret = ByteVector32(hex"2e83e9bc7821d3f6cec7301fa8493aee407557624fb5745bede9084852430e3f")
    assert(Sphinx.computeSharedSecret(alice.publicKey, blindingSecret) == sharedSecret)
    assert(Sphinx.computeSharedSecret(blindingKey, alice) == sharedSecret)
    assert(Sphinx.mac(ByteVector("blinded_node_id".getBytes), sharedSecret) == ByteVector32(hex"7d846b3445621d49a665e5698c52141e9dda8fa2fe0c3da7e0f9008ccc588a38"))
    val blindedAlice = PublicKey(hex"02004b5662061e9db495a6ad112b6c4eba228a079e8e304d9df50d61043acbc014")
    val blindedPayload = TlvStream[RouteBlindingEncryptedDataTlv](OutgoingNodeId(bob.publicKey))
    val encodedBlindedPayload = blindedRouteDataCodec.encode(blindedPayload).require.bytes
    assert(encodedBlindedPayload == hex"04210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")
    val blindedRoute = Sphinx.RouteBlinding.create(blindingSecret, alice.publicKey :: Nil, encodedBlindedPayload :: Nil).route
    assert(blindedRoute.blindedNodes.head.blindedPublicKey == blindedAlice)
    assert(Crypto.sha256(blindingKey.value ++ sharedSecret.bytes) == ByteVector32(hex"bae3d9ea2b06efd1b7b9b49b6cdcaad0e789474a6939ffa54ff5ec9224d5b76c"))
    assert(blindedRoute.blindedNodes.head.encryptedPayload == hex"6970e870b473ddbc27e3098bfa45bb1aa54f1f637f803d957e6271d8ffeba89da2665d62123763d9b634e30714144a1c165ac9")
    val Right(decryptedPayload) = RouteBlindingEncryptedDataCodecs.decode(alice, blindingKey, blindedRoute.blindedNodes.head.encryptedPayload)
    assert(decryptedPayload.tlvs == blindedPayload)
  }

  test("relay message from bob to carol with blinding override") {
    val bob = PrivateKey(hex"424242424242424242424242424242424242424242424242424242424242424201")
    val carol = PrivateKey(hex"434343434343434343434343434343434343434343434343434343434343434301")
    val blindingSecret = PrivateKey(hex"76d4de6c329c79623842dcf8f8eaee90c9742df1b5231f5350df4a231d16ebcf01")
    val blindingKey = PublicKey(hex"03fc5e56da97b462744c9a6b0ba9d5b3ffbfb1a08367af9cc6ea5ae03c79a78eec")
    assert(blindingSecret.publicKey == blindingKey)
    val sharedSecret = ByteVector32(hex"f18a1ddb1cb27d8fc4faf2cf317e87524fcc6b7f053496d95bf6e6809d09851e")
    assert(Sphinx.computeSharedSecret(bob.publicKey, blindingSecret) == sharedSecret)
    assert(Sphinx.computeSharedSecret(blindingKey, bob) == sharedSecret)
    assert(Sphinx.mac(ByteVector("blinded_node_id".getBytes), sharedSecret) == ByteVector32(hex"8074773a3745818b0d97dd875023486cc35e7afd95f5e9ec1363f517979e8373"))
    val blindedBob = PublicKey(hex"026ea8e36f78e038c659beba9229699796127471d9c7a24a0308533371fd63ad48")
    val blindingOverride = PrivateKey(hex"070707070707070707070707070707070707070707070707070707070707070701").publicKey
    val blindedPayload = TlvStream[RouteBlindingEncryptedDataTlv](OutgoingNodeId(carol.publicKey), NextBlinding(blindingOverride))
    val encodedBlindedPayload = blindedRouteDataCodec.encode(blindedPayload).require.bytes
    assert(encodedBlindedPayload == hex"0421027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007082102989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f")
    val blindedRoute = Sphinx.RouteBlinding.create(blindingSecret, bob.publicKey :: Nil, encodedBlindedPayload :: Nil).route
    assert(blindedRoute.blindedNodes.head.blindedPublicKey == blindedBob)
    assert(Crypto.sha256(blindingKey.value ++ sharedSecret.bytes) == ByteVector32(hex"9afb8b2ebc174dcf9e270be24771da7796542398d29d4ff6a4e7b6b4b9205cfe"))
    assert(blindedRoute.blindedNodes.head.encryptedPayload == hex"1630da85e8759b8f3b94d74a539c6f0d870a87cf03d4986175865a2985553c997b560c32613bd9184c1a6d41a37027aabdab5433009d8409a1b638eb90373778a05716af2c2140b3196dca23997cdad4cfa7a7adc8d4")
    val Right(decryptedPayload) = RouteBlindingEncryptedDataCodecs.decode(bob, blindingKey, blindedRoute.blindedNodes.head.encryptedPayload)
    assert(decryptedPayload.tlvs == blindedPayload)
    assert(decryptedPayload.nextBlinding == blindingOverride)
  }

  test("relay message from carol to dave with padding") {
    val carol = PrivateKey(hex"434343434343434343434343434343434343434343434343434343434343434301")
    val dave = PrivateKey(hex"444444444444444444444444444444444444444444444444444444444444444401")
    val blindingSecret = PrivateKey(hex"070707070707070707070707070707070707070707070707070707070707070701")
    val blindingKey = PublicKey(hex"02989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f")
    assert(blindingSecret.publicKey == blindingKey)
    val sharedSecret = ByteVector32(hex"8c0f7716da996c4913d720dbf691b559a4945bf70cdd18e0b61e3e42635efc9c")
    assert(Sphinx.computeSharedSecret(carol.publicKey, blindingSecret) == sharedSecret)
    assert(Sphinx.computeSharedSecret(blindingKey, carol) == sharedSecret)
    assert(Sphinx.mac(ByteVector("blinded_node_id".getBytes), sharedSecret) == ByteVector32(hex"02afb2187075c8af51488242194b44c02624785ccd6fd43b5796c68f3025bf88"))
    val blindedCarol = PublicKey(hex"02f4f524562868a09d5f54fb956ade3fa51ef071d64d923e395cc6db5e290ec67b")
    val blindedPayload = TlvStream[RouteBlindingEncryptedDataTlv](Padding(hex"0000000000000000000000000000000000000000000000000000000000000000000000"), OutgoingNodeId(dave.publicKey))
    val encodedBlindedPayload = blindedRouteDataCodec.encode(blindedPayload).require.bytes
    assert(encodedBlindedPayload == hex"012300000000000000000000000000000000000000000000000000000000000000000000000421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
    val blindedRoute = Sphinx.RouteBlinding.create(blindingSecret, carol.publicKey :: Nil, encodedBlindedPayload :: Nil).route
    assert(blindedRoute.blindedNodes.head.blindedPublicKey == blindedCarol)
    assert(Crypto.sha256(blindingKey.value ++ sharedSecret.bytes) == ByteVector32(hex"cc3b918cda6b1b049bdbe469c4dd952935e7c1518dd9c7ed0cd2cd5bc2742b82"))
    assert(blindedRoute.blindedNodes.head.encryptedPayload == hex"8285acbceb37dfb38b877a888900539be656233cd74a55c55344fb068f9d8da365340d21db96fb41b76123207daeafdfb1f571e3fea07a22e10da35f03109a0380b3c69fcbed9c698086671809658761cf65ecbc3c07a2e5")
    val Right(decryptedPayload) = RouteBlindingEncryptedDataCodecs.decode(carol, blindingKey, blindedRoute.blindedNodes.head.encryptedPayload)
    assert(decryptedPayload.tlvs == blindedPayload)
  }

  test("build message with existing route") {
    val nodeKey = randomKey()
    val sessionKey = randomKey()
    val blindingSecret = randomKey()
    val blindingOverride = randomKey()
    val destination = randomKey()
    val replyPath = buildRoute(blindingOverride, IntermediateNode(destination.publicKey) :: Nil, Recipient(destination.publicKey, pathId = Some(hex"01234567")))
    assert(replyPath.blindingKey == blindingOverride.publicKey)
    assert(replyPath.introductionNodeId == destination.publicKey)
    val Right((nextNodeId, message)) = buildMessage(nodeKey, sessionKey, blindingSecret, Nil, BlindedPath(replyPath), TlvStream.empty)
    assert(nextNodeId == destination.publicKey)
    assert(message.blindingKey == blindingOverride.publicKey) // blindingSecret was not used as the replyPath was used as is

    process(destination, message) match {
      case ReceiveMessage(finalPayload) => assert(finalPayload.pathId_opt.contains(hex"01234567"))
      case x => fail(x.toString)
    }
  }

  test("very large multi-hop onion message") {
    val nodeKey = randomKey()
    val alice = randomKey()
    val bob = randomKey()
    val carol = randomKey()
    val sessionKey = randomKey()
    val blindingSecret = randomKey()
    val pathId = randomBytes(65201)
    val Right((_, messageForAlice)) = buildMessage(nodeKey, sessionKey, blindingSecret, IntermediateNode(alice.publicKey) :: IntermediateNode(bob.publicKey) :: Nil, Recipient(carol.publicKey, Some(pathId)), TlvStream.empty)

    // Checking that the onion is relayed properly
    process(alice, messageForAlice) match {
      case SendMessage(nextNodeId, onionForBob) =>
        assert(nextNodeId == bob.publicKey)
        process(bob, onionForBob) match {
          case SendMessage(nextNodeId, onionForCarol) =>
            assert(nextNodeId == carol.publicKey)
            process(carol, onionForCarol) match {
              case ReceiveMessage(finalPayload) => assert(finalPayload.pathId_opt.contains(pathId))
              case x => fail(x.toString)
            }
          case x => fail(x.toString)
        }
      case x => fail(x.toString)
    }
  }

  test("too large multi-hop onion message") {
    val nodeKey = randomKey()
    val alice = randomKey()
    val bob = randomKey()
    val carol = randomKey()
    val sessionKey = randomKey()
    val blindingSecret = randomKey()

    val pathId = randomBytes(65202)

    assert(buildMessage(nodeKey, sessionKey, blindingSecret, IntermediateNode(alice.publicKey) :: IntermediateNode(bob.publicKey) :: Nil, Recipient(carol.publicKey, Some(pathId)), TlvStream.empty) == Left(MessageTooLarge(65433)))
  }
}
