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

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.PacketAndSecrets
import fr.acinq.eclair.message.OnionMessages._
import fr.acinq.eclair.randomKey
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.lightningMessageCodec
import fr.acinq.eclair.wire.protocol.MessageOnion.{BlindedFinalPayload, BlindedRelayPayload, FinalPayload, RelayPayload}
import fr.acinq.eclair.wire.protocol.MessageOnionCodecs.{blindedFinalPayloadCodec, blindedRelayPayloadCodec, finalPerHopPayloadCodec, relayPerHopPayloadCodec}
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.EncryptedData
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv._
import fr.acinq.eclair.wire.protocol.{CommonCodecs, OnionMessage, TlvStream}
import org.json4s.JsonAST._
import org.json4s.jackson.JsonMethods
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.{ByteVector, HexStringSyntax}
import scodec.codecs
import scodec.codecs.variableSizeBytesLong

import scala.io.Source

/**
 * Created by thomash on 23/09/2021.
 */

class OnionMessagesSpec extends AnyFunSuite {

  test("Most basic test") {
    val sessionKey = randomKey()
    val blindingSecret = randomKey()
    val destination = randomKey()
    val (nextNodeId, message) = buildMessage(sessionKey, blindingSecret, Nil, Left(Recipient(destination.publicKey, None)), Nil)
    assert(nextNodeId == destination.publicKey)

    process(destination, message) match {
      case ReceiveMessage(_, _) => ()
      case x => fail(x.toString)
    }
  }

  test("Spec tests") {
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

    /*
    *  Building the onion manually
    */
    val messageForAlice = BlindedRelayPayload(TlvStream(OutgoingNodeId(bob.publicKey)))
    val encodedForAlice = blindedRelayPayloadCodec.encode(messageForAlice).require.bytes
    assert(encodedForAlice == hex"04210324653eac434488002cc06bbfb7f10fe18991e35f9fe4302dbea6d2353dc0ab1c")
    val messageForBob = BlindedRelayPayload(TlvStream(OutgoingNodeId(carol.publicKey), NextBlinding(blindingOverride.publicKey)))
    val encodedForBob = blindedRelayPayloadCodec.encode(messageForBob).require.bytes
    assert(encodedForBob == hex"0421027f31ebc5462c1fdce1b737ecff52d37d75dea43ce11c74d25aa297165faa2007082102989c0b76cb563971fdc9bef31ec06c3560f3249d6ee9e5d83c57625596e05f6f")
    val messageForCarol = BlindedRelayPayload(TlvStream(Padding(hex"0000000000000000000000000000000000000000000000000000000000000000000000"), OutgoingNodeId(dave.publicKey)))
    val encodedForCarol = blindedRelayPayloadCodec.encode(messageForCarol).require.bytes
    assert(encodedForCarol == hex"012300000000000000000000000000000000000000000000000000000000000000000000000421032c0b7cf95324a07d05398b240174dc0c2be444d96b159aa6c7f7b1e668680991")
    val messageForDave = BlindedFinalPayload(TlvStream(PathId(hex"0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")))
    val encodedForDave = blindedFinalPayloadCodec.encode(messageForDave).require.bytes
    assert(encodedForDave == hex"06200102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")

    // Building blinded path Carol -> Dave
    val routeFromCarol = Sphinx.RouteBlinding.create(blindingOverride, carol.publicKey :: dave.publicKey :: Nil, encodedForCarol :: encodedForDave :: Nil)

    // Building blinded path Alice -> Bob
    val routeToCarol = Sphinx.RouteBlinding.create(blindingSecret, alice.publicKey :: bob.publicKey :: Nil, encodedForAlice :: encodedForBob :: Nil)

    val publicKeys = routeToCarol.blindedNodes.map(_.blindedPublicKey) concat routeFromCarol.blindedNodes.map(_.blindedPublicKey)
    val encryptedPayloads = routeToCarol.encryptedPayloads concat routeFromCarol.encryptedPayloads
    val payloads =
      encryptedPayloads.dropRight(1).map(encTlv => relayPerHopPayloadCodec.encode(RelayPayload(TlvStream(EncryptedData(encTlv)))).require.bytes) :+
        finalPerHopPayloadCodec.encode(FinalPayload(TlvStream(EncryptedData(encryptedPayloads.last)))).require.bytes
    assert(payloads == List(
      hex"3504336970e870b473ddbc27e3098bfa45bb1aa54f1f637f803d957e6271d8ffeba89da2665d62123763d9b634e30714144a1c165ac9",
      hex"5804561630da85e8759b8f3b94d74a539c6f0d870a87cf03d4986175865a2985553c997b560c32613bd9184c1a6d41a37027aabdab5433009d8409a1b638eb90373778a05716af2c2140b3196dca23997cdad4cfa7a7adc8d4",
      hex"5a04588285acbceb37dfb38b877a888900539be656233cd74a55c55344fb068f9d8da365340d21db96fb41b76123207daeafdfb1f571e3fea07a22e10da35f03109a0380b3c69fcbed9c698086671809658761cf65ecbc3c07a2e5",
      hex"340432a22371dc1995ff6ff4b266904458242035c2d68547da832010f77b6ad86fbf137e1dbb51231fc9f6c207c97204487a434aaf"))

    val sessionKey = PrivateKey(hex"090909090909090909090909090909090909090909090909090909090909090901")

    val PacketAndSecrets(packet, _) = Sphinx.create(sessionKey, 1300, publicKeys, payloads, None)
    assert(packet.hmac == ByteVector32(hex"e57c5c56ef60bae2614b9de0c715b51059a0d70d66a55d4e38fafdd62ab39bd8"))
    assert(packet.publicKey == PublicKey(hex"0256b328b30c8bf5839e24058747879408bdb36241dc9c2e7c619faa12b2920967").value)
    assert(packet.payload ==
      hex"37d167dcefdb678725cb8074d3224dfe235ba3f22f71ac8a2c9d1398b1175295b1dd3f14c02d698021e8a8856637306c6f195e01494e0a57ee717eb593f3f341659284e7be290ad5cc3ed1ac902d8efb310ad0b1b5172e9f307a25b6d5e4045174551b1c867264d3905e4f05b2e5bcfed7e7276660bf7e956bce5afa3d5e7e4c15883b856bc93dd9d6a968838ef51314d38dd41e5ab84b8846dca3c61d87e54c0ecf116b3cd5b3f1fcfbba3067cc3286f2e9cf19d2f1840f9f6b06c9dddc514f0c94621707642c1eaee7fd1eb0eacdae3dc564395bb7a4bbe28325ccdb07503285dacf90b5e09f4e455fb42459741f9d497000298b99f1e70adc28f59a1be85a96952f27b6a6c5d6a08822b4f5cae05daa6c2ce2f8ca5fdd4e8f0df46b94791b3159fe8eace11bcf8d586f9926f652d532a4f860346ea8de81fa7e9aa81a09218d270cd48abac79aee70acbff34b0ce7ef1db19e82f173fc41185950a37ff50c646a5452d4fb78c6ec4a60fec60d5531ef268eaf5c79c4776a780b2e60a079a5473994dc8ace531414967bc48fd1e49a1151ee34b08c50d463b042fcb090d8289eaba0b35fb542b4e83a5d0217e9fa6f10f137f3812ebe504e377b86c9483fe3a40deb5096ce93a5d0899f7a058151c112cd9b0b975edcc289f54e0603a9ab50a4ae35f5fcf4c248a8b8c84f610f6f8a47fc097c1e835e6cf9897fff68841527c28e634a429a50c9f9ff465272cb0b3ce2192076b2027e60fa279c032b7da85c0ee42074a077bccf37d39c1db4a1c661af615e7aba2cf2c0d4304d3694f5fc3f2bbb3ee4d901a42cf3b795f8e52a81ba9ec9e92e443cd68afbb774effc64c5211aa44267cafb75a6475c4eef75424bbd70714adaaba5f9b1f49be3366bde897d692341ee3739837048520d3ea923a5830d447125af09ee02f45662f2a418faa9d8045ac510291e8c490a9fa6b6f8afff6493c26f333c843b24d2a3aa02e2d17ff50611295dba2888696eaf1ec239e3df9f4165d3b114e3d236a77355433ddb7adac0082ced117b79f36d39b4d067b2f16c0ac505acb545d15a12380bb78b857870b5ac6929b79033f61050690cb018fd53734f8b1bedb6749299782a2d23641bd5f554cf9b5da1980f4c3a83cf6d68ac43a16ad823e1ec4dd049414a08806c12b22aa83a608b5ff14c18628470f5a700ce4ce67ff0a67b59de8a29d6c1bc8562f128ef4873af424cf2e2c0d1173b18036aec64b0e4753a299fcd4d58e911e73957b7a3d9397eb540e33efdeaf5250e251e89c39d5c85a42a0cb875c978fe9300e0051fd7196df97e7dbcc19d0a1da3ae32263fc7f6401c5a2c337344a03778aedb1d165e1acb2df3d9d35dfdc99ee4f38e763fc0330c027da3ddaa6a7ae210702b6885371b5f7635f84beac123bb1621d3ae29529fc000ffdf0711aef417e69631f225bf17109046fcb2fe2b35af1ff9ed711c4d34ca934f7cd8d9c852ca34b3e7cb97256bdeeee58787ae852b9381f076a546d2f9e3a58d62f2eca2005d5a216a614d671287683cd1b53570bf2af197cb53a101f5442a6cf8de4a5e39d2fb80a677546b197c1a4d49a7a0963e88bf559280373ddf549be48beb684e84bcbf842a8e6c25289cffe0075f7680f1b4b66e0b542a968c5231a95e038420b77cd1d93d1c8a2c828f8d572c976e18bff410e2f37e7e94579a77beae7b0959ab41c68bd15632d1435b5466573fccff5455fc45ee8b6bfbad8eafe342667cd6a47e802943235d28df62bead7b6b3a509a67ab247adc860c82955788db61a89159ce855284102d214bc7b0775c1185484039ee9b1a25525c"
    )
    val onionForAlice = OnionMessage(blindingSecret.publicKey, packet)

    /*
    *  Building the onion with functions from `OnionMessages`
    */
    val replyPath = buildRoute(blindingOverride, IntermediateNode(carol.publicKey, padding = Some(hex"0000000000000000000000000000000000000000000000000000000000000000000000")) :: Nil, Left(Recipient(dave.publicKey, pathId = Some(hex"0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"))))
    assert(replyPath == routeFromCarol)
    val (_, message) = buildMessage(sessionKey, blindingSecret, IntermediateNode(alice.publicKey) :: IntermediateNode(bob.publicKey) :: Nil, Right(replyPath), Nil)
    assert(message == onionForAlice)

    /*
    *  Checking that the onion is relayed properly
    */
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
                  case ReceiveMessage(_, path_id) => assert(path_id contains hex"0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20")
                  case x => fail(x.toString)
                }
              case x => fail(x.toString)
            }
          case x => fail(x.toString)
        }
      case x => fail(x.toString)
    }
  }

  test("enctlvs.json") {
    val source = Source.fromInputStream(classOf[OnionMessagesSpec].getResourceAsStream("/onion-messages/enctlvs.json"))
    val enctlvs = JsonMethods.parse(source.getLines().mkString)
    source.close()

    for {
      JArray(arr) <- enctlvs
      JObject(test) <- arr
      JField("test name", JString(testName)) <- test
      JField("node_privkey", JString(nodePrivateKeyHex)) <- test
      JField("node_id", JString(nodeIdHex)) <- test
      JField("blinding_secret", JString(blindingSecretHex)) <- test
      JField("blinding", JString(blindingHex)) <- test
      JField("ss", JString(sharedSecretHex)) <- test
      JField("HMAC256('blinded_node_id', ss)", JString(hmacHex)) <- test
      JField("blinded_node_id", JString(blindedNodeIdHex)) <- test
      JField("encmsg_hex", JString(encmsgHex)) <- test
      JField("H(E || ss)", JString(hashHex)) <- test
      JField("encrypted_recipient_data_hex", JString(encryptedRecipientDataHex)) <- test
      JField("encrypted_data_tlv", JObject(encryptedDataTlv)) <- test
    } {
      println(testName)

      val nodePrivateKey = PrivateKey(ByteVector.fromValidHex(nodePrivateKeyHex))
      val nodeId = PublicKey(ByteVector.fromValidHex(nodeIdHex))
      assert(nodePrivateKey.publicKey == nodeId)
      val blindingSecret = PrivateKey(ByteVector.fromValidHex(blindingSecretHex))
      val blindingKey = PublicKey(ByteVector.fromValidHex(blindingHex))
      assert(blindingSecret.publicKey == blindingKey)
      val sharedSecret = ByteVector32(ByteVector.fromValidHex(sharedSecretHex))
      assert(Sphinx.computeSharedSecret(nodeId, blindingSecret) == sharedSecret)
      assert(Sphinx.computeSharedSecret(blindingKey, nodePrivateKey) == sharedSecret)
      assert(Sphinx.mac(ByteVector("blinded_node_id".getBytes), sharedSecret) == ByteVector32(ByteVector.fromValidHex(hmacHex)))
      val blindedNodeId = PublicKey(ByteVector.fromValidHex(blindedNodeIdHex))
      val encmsg = ByteVector.fromValidHex(encmsgHex)
      val Sphinx.RouteBlinding.BlindedRoute(_, _, blindedHops) = Sphinx.RouteBlinding.create(blindingSecret, nodeId :: Nil, encmsg :: Nil)
      assert(blindedHops.head.blindedPublicKey == blindedNodeId)
      assert(Crypto.sha256(blindingKey.value ++ sharedSecret.bytes) == ByteVector32(ByteVector.fromValidHex(hashHex)))
      val enctlv = ByteVector.fromValidHex(encryptedRecipientDataHex)
      assert(blindedHops.head.encryptedPayload == enctlv)

      val nextNodeId = (for (JField("next_node_id", JString(nextNodeIdHex)) <- encryptedDataTlv) yield PublicKey(ByteVector.fromValidHex(nextNodeIdHex))).headOption
      val padding = (for (JField("padding", JString(paddingHex)) <- encryptedDataTlv) yield ByteVector.fromValidHex(paddingHex)).headOption
      val blinding = (for (JField("blinding", JString(blindingHex)) <- encryptedDataTlv) yield PublicKey(ByteVector.fromValidHex(blindingHex))).headOption
      val selfId = (for (JField("self_id", JString(selfIdHex)) <- encryptedDataTlv) yield ByteVector.fromValidHex(selfIdHex)).headOption
      val tlvs = padding.map(Padding(_) :: Nil).getOrElse(Nil) ++ nextNodeId.map(OutgoingNodeId(_) :: Nil).getOrElse(Nil) ++ blinding.map(NextBlinding(_) :: Nil).getOrElse(Nil) ++ selfId.map(PathId(_) :: Nil).getOrElse(Nil)
      nextNodeId match {
        case Some(_) => // Relay payload
          val message = BlindedRelayPayload(TlvStream(tlvs))
          assert(blindedRelayPayloadCodec.encode(message).require.bytes == encmsg)
          val relayNext = blindedRelayPayloadCodec.decode(encmsg.bits).require.value
          assert(relayNext.records.get[Padding].map(_.dummy) === padding)
          assert(Some(relayNext.nextNodeId) === nextNodeId)
          assert(relayNext.nextBlindingOverride === blinding)
          assert(relayNext.records.get[PathId] === None)
        case None => // Final payload
          val message = BlindedFinalPayload(TlvStream(tlvs))
          assert(blindedFinalPayloadCodec.encode(message).require.bytes == encmsg)
          val fin = blindedFinalPayloadCodec.decode(encmsg.bits).require.value
          assert(fin.records.get[Padding].map(_.dummy) === padding)
          assert(fin.records.get[NextBlinding] === None)
          assert(fin.records.get[OutgoingNodeId] === None)
          assert(fin.pathId === selfId)
      }
      assert(Sphinx.RouteBlinding.decryptPayload(nodePrivateKey, blindingKey, enctlv).get._1 == encmsg)
    }
  }

  test("onion_messages.json") {
    val source = Source.fromInputStream(classOf[OnionMessagesSpec].getResourceAsStream("/onion-messages/onion_messages.json"))
    val onionMessages = JsonMethods.parse(source.getLines().mkString)
    source.close()

    for {
      JObject(obj) <- onionMessages
      JField("onionmsgs", JArray(messages)) <- obj
      (JObject(node), i) <- messages.zipWithIndex
      JField("node_secret", JString(nodePrivateKeyHex)) <- node
      JField("node_id", JString(nodeIdHex)) <- node
      JField("onion_message", JObject(onionMessage)) <- node
      JField("raw", JString(rawHex)) <- onionMessage
      JField("blinding_secret", JString(blindingSecretHex)) <- onionMessage
      JField("blinding", JString(blindingHex)) <- onionMessage
    } {
      val nodePrivateKey = PrivateKey(ByteVector.fromValidHex(nodePrivateKeyHex))
      val nodeId = PublicKey(ByteVector.fromValidHex(nodeIdHex))
      assert(nodePrivateKey.publicKey === nodeId)
      val raw = ByteVector.fromValidHex(rawHex)
      lightningMessageCodec.decode(raw.bits).require.value match {
        case msg: OnionMessage =>
          val blindingSecret = PrivateKey(ByteVector.fromValidHex(blindingSecretHex))
          val blinding = PublicKey(ByteVector.fromValidHex(blindingHex))
          assert(blindingSecret.publicKey === blinding)
          assert(msg.blindingKey === blinding)
          process(nodePrivateKey, msg) match {
            case SendMessage(nextNodeId, nextOnion) =>
              assert(i + 1 < messages.length)
              val JObject(next) = messages(i + 1)
              for {
                JField("node_id", JString(nextNodeIdHex)) <- next
                JField("onion_message", JObject(onion)) <- next
                JField("raw", JString(nextRawHex)) <- onion} {
                assert(nextNodeId === PublicKey(ByteVector.fromValidHex(nextNodeIdHex)))
                assert(lightningMessageCodec.encode(nextOnion).require.bytes === ByteVector.fromValidHex(nextRawHex))
              }
            case ReceiveMessage(finalPayload, _) =>
              assert(i + 1 === messages.length)
              for {JField("onionmsg_payload", JString(payloadHex)) <- onionMessage} {
                val payload = ByteVector.fromValidHex(payloadHex)
                assert(finalPerHopPayloadCodec.encode(finalPayload).require === variableSizeBytesLong(CommonCodecs.varintoverflow, codecs.bytes).encode(payload).require)
              }
            case x => fail(x.toString)
          }
        case x => fail(x.toString)
      }
    }
  }

  test("build message with existing route") {
    val sessionKey = randomKey()
    val blindingSecret = randomKey()
    val blindingOverride = randomKey()
    val destination = randomKey()
    val replyPath = buildRoute(blindingOverride, IntermediateNode(destination.publicKey) :: Nil, Left(Recipient(destination.publicKey, pathId = Some(hex"01234567"))))
    assert(replyPath.blindingKey === blindingOverride.publicKey)
    assert(replyPath.introductionNodeId === destination.publicKey)
    val (nextNodeId, message) = buildMessage(sessionKey, blindingSecret, Nil, Right(replyPath), Nil)
    assert(nextNodeId === destination.publicKey)
    assert(message.blindingKey === blindingOverride.publicKey) // blindingSecret was not used as the replyPath was used as is

    process(destination, message) match {
      case ReceiveMessage(_, Some(pathId)) if pathId == hex"01234567" => ()
      case _ => fail()
    }
  }
}
