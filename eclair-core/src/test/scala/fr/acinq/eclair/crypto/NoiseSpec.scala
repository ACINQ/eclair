/*
 * Copyright 2018 ACINQ SAS
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

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.crypto.Noise._
import org.scalatest.FunSuite
import org.spongycastle.crypto.ec.CustomNamedCurves


class NoiseSpec extends FunSuite {

  import Noise._
  import NoiseSpec._

  test("hash tests") {
    // see https://tools.ietf.org/html/rfc4231
    assert(SHA256HashFunctions.hmacHash(BinaryData("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), BinaryData("4869205468657265")) == BinaryData("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"))
    assert(SHA256HashFunctions.hkdf(BinaryData("4e6f6973655f4e4e5f32353531395f436861436861506f6c795f534841323536"), BinaryData("37e0e7daacbd6bfbf669a846196fd44d1c8745d33f2be42e31d4674199ad005e")) == (BinaryData("f6f78327c10316fdad06633fb965e03182e9a8b1f755d613f7980fbb85ebf46d"), BinaryData("4ee4220f31dbd3c9e2367e66a87f1e98a2433e4b9fbecfd986d156dcf027b937")))
  }

  test("25519 DH test") {
    // see https://tools.ietf.org/html/rfc7748#section-6.1
    val dh = Curve25519DHFunctions

    val p1 = dh.generateKeyPair(BinaryData("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
    assert(p1.pub == BinaryData("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"))

    val p2 = dh.generateKeyPair(BinaryData("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
    assert(p2.pub == BinaryData("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"))

    assert(dh.dh(p1, p2.pub) == BinaryData("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"))
  }

  test("SymmetricState test") {
    val name = "Noise_NN_25519_ChaChaPoly_SHA256"
    val symmetricState = SymmetricState(name.getBytes("UTF-8"), Chacha20Poly1305CipherFunctions, SHA256HashFunctions)
    val symmetricState1 = symmetricState.mixHash("notsecret".getBytes("UTF-8"))
    assert(symmetricState1.ck == BinaryData("4e6f6973655f4e4e5f32353531395f436861436861506f6c795f534841323536"))
    assert(symmetricState1.h == BinaryData("03b71896d90661cfed747aaaca293d1b01365b3f0f6605dbc3c85a7eb9fff519"))
  }

  test("Noise_NN_25519_ChaChaPoly_SHA256 test") {
    // see https://github.com/trevp/screech/blob/master/vectors.txt
    val key0: BinaryData = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    val key1: BinaryData = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    val key2: BinaryData = "2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40"
    val key3: BinaryData = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
    val key4: BinaryData = "4142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60"

    val initiator = HandshakeState.initializeWriter(
      handshakePattern = handshakePatternNN,
      prologue = BinaryData("6e6f74736563726574"),
      s = KeyPair(BinaryData.empty, BinaryData.empty), e = KeyPair(BinaryData.empty, BinaryData.empty), rs = BinaryData.empty, re = BinaryData.empty,
      dh = Curve25519DHFunctions,
      Chacha20Poly1305CipherFunctions,
      SHA256HashFunctions,
      FixedStream(key3))

    val responder = HandshakeState.initializeReader(
      handshakePattern = handshakePatternNN,
      prologue = BinaryData("6e6f74736563726574"),
      s = KeyPair(BinaryData.empty, BinaryData.empty), e = KeyPair(BinaryData.empty, BinaryData.empty), rs = BinaryData.empty, re = BinaryData.empty,
      dh = Curve25519DHFunctions,
      Chacha20Poly1305CipherFunctions,
      SHA256HashFunctions,
      FixedStream(key4))

    val (outputs, (enc, dec)) = handshake(initiator, responder, BinaryData.empty :: BinaryData.empty :: Nil)
    assert(outputs == List(
      BinaryData("358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254"),
      BinaryData("64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d484665cda04f69d491f9bf509e632fc1a20dd")
    ))

    val (enc1, ciphertext01) = enc.encryptWithAd(BinaryData.empty, BinaryData("79656c6c6f777375626d6172696e65"))
    assert(ciphertext01 == BinaryData("96cd46be111804586a935795eeb4ce62bdec121048a10520b00266b22722eb"))
    val (dec1, ciphertext02) = dec.encryptWithAd(BinaryData.empty, BinaryData("7375626d6172696e6579656c6c6f77"))
    assert(ciphertext02 == BinaryData("fe2bc534e31964c0bd56337223e921565e39dbc5f156aa04766ced4689a2a2"))
  }

  test("Noise_NN_25519_ChaChaPoly_SHA256 cacophony test") {
    // see https://raw.githubusercontent.com/centromere/cacophony/master/vectors/cacophony.txt
    // @formatter:off
    /*
     {
      "name": "Noise_NN_25519_ChaChaPoly_SHA256",
      "pattern": "NN",
      "dh": "25519",
      "cipher": "ChaChaPoly",
      "hash": "SHA256",
      "init_prologue": "4a6f686e2047616c74",
      "init_ephemeral": "893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a",
      "resp_prologue": "4a6f686e2047616c74",
      "resp_ephemeral": "bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b",
      "messages": [
        {
          "payload": "4c756477696720766f6e204d69736573",
          "ciphertext": "ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c79444c756477696720766f6e204d69736573"
        },
        {
          "payload": "4d757272617920526f746862617264",
          "ciphertext": "95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843a0ff96bdf86b579ef7dbf94e812a7470b903c20a85a87e3a1fe863264ae547"
        },
        {
          "payload": "462e20412e20486179656b",
          "ciphertext": "eb1a3e3d80c1792b1bb9cb0e1382f8d8322bfb1ca7c4c8517bb686"
        },
        {
          "payload": "4361726c204d656e676572",
          "ciphertext": "c781b198d2a974eb1da2c7d518c000cf6396de87ca540963c03713"
        },
        {
          "payload": "4a65616e2d426170746973746520536179",
          "ciphertext": "c77048eb6919fdfe8fe45842bfc5b8d1ff50d1e20c717453ccdfe6176d805b996d"
        },
        {
          "payload": "457567656e2042f6686d20766f6e2042617765726b",
          "ciphertext": "61834d7069dcfb7a1adf8d5ac910f83fa04c73a67789895c6f5f995c5db2ce88e49b124178"
        }
      ]
    }
    */
    // @formatter:on

    val initiator = HandshakeState.initializeWriter(
      handshakePattern = handshakePatternNN,
      prologue = BinaryData("4a6f686e2047616c74"),
      s = KeyPair(BinaryData.empty, BinaryData.empty), e = KeyPair(BinaryData.empty, BinaryData.empty), rs = BinaryData.empty, re = BinaryData.empty,
      dh = Curve25519DHFunctions,
      Chacha20Poly1305CipherFunctions,
      SHA256HashFunctions,
      FixedStream(BinaryData("893e28b9dc6ca8d611ab664754b8ceb7bac5117349a4439a6b0569da977c464a")))

    val responder = HandshakeState.initializeReader(
      handshakePattern = handshakePatternNN,
      prologue = BinaryData("4a6f686e2047616c74"),
      s = KeyPair(BinaryData.empty, BinaryData.empty), e = KeyPair(BinaryData.empty, BinaryData.empty), rs = BinaryData.empty, re = BinaryData.empty,
      dh = Curve25519DHFunctions,
      Chacha20Poly1305CipherFunctions,
      SHA256HashFunctions,
      FixedStream(BinaryData("bbdb4cdbd309f1a1f2e1456967fe288cadd6f712d65dc7b7793d5e63da6b375b")))

    val (outputs, (enc, dec)) = handshake(initiator, responder, BinaryData("4c756477696720766f6e204d69736573") :: BinaryData("4d757272617920526f746862617264") :: Nil)
    assert(outputs == List(
      BinaryData("ca35def5ae56cec33dc2036731ab14896bc4c75dbb07a61f879f8e3afa4c79444c756477696720766f6e204d69736573"),
      BinaryData("95ebc60d2b1fa672c1f46a8aa265ef51bfe38e7ccb39ec5be34069f144808843a0ff96bdf86b579ef7dbf94e812a7470b903c20a85a87e3a1fe863264ae547")
    ))

    val (enc1, ciphertext01) = enc.encryptWithAd(BinaryData.empty, BinaryData("462e20412e20486179656b"))
    assert(ciphertext01 == BinaryData("eb1a3e3d80c1792b1bb9cb0e1382f8d8322bfb1ca7c4c8517bb686"))
    val (dec1, ciphertext02) = dec.encryptWithAd(BinaryData.empty, BinaryData("4361726c204d656e676572"))
    assert(ciphertext02 == BinaryData("c781b198d2a974eb1da2c7d518c000cf6396de87ca540963c03713"))
    val (enc2, ciphertext03) = enc1.encryptWithAd(BinaryData.empty, BinaryData("4a65616e2d426170746973746520536179"))
    assert(ciphertext03 == BinaryData("c77048eb6919fdfe8fe45842bfc5b8d1ff50d1e20c717453ccdfe6176d805b996d"))
    val (dec2, ciphertext04) = dec1.encryptWithAd(BinaryData.empty, BinaryData("457567656e2042f6686d20766f6e2042617765726b"))
    assert(ciphertext04 == BinaryData("61834d7069dcfb7a1adf8d5ac910f83fa04c73a67789895c6f5f995c5db2ce88e49b124178"))
  }

  test("Noise_XK_25519_ChaChaPoly_SHA256 test") {

    /*
    see https://github.com/flynn/noise/blob/master/vectors.txt
    handshake=Noise_XK_25519_ChaChaPoly_SHA256
    init_static=000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f
    resp_static=0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20
    gen_init_ephemeral=202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f
    gen_resp_ephemeral=4142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60
    msg_0_payload=
    msg_0_ciphertext=358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd1662549963aa4003cb0f60f51f7f8b1c0e6a9c
    msg_1_payload=
    msg_1_ciphertext=64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d4846630166c893dafe95f71d102a8ac640a52
    msg_2_payload=
    msg_2_ciphertext=24a819b832ab7a11dd1464c2baf72f2c49e0665757911662ab11495a5fd4437e0abe01f5c07176e776e02716c4cb98a005ec4c884c4dc7500d2d9b99e9670ab3
    msg_3_payload=79656c6c6f777375626d6172696e65
    msg_3_ciphertext=e8b0f2fc220f7edc287a91ba45c76f6da1327405789dc61e31a649f57d6d93
    msg_4_payload=7375626d6172696e6579656c6c6f77
    msg_4_ciphertext=ed6901a7cd973e880242b047fc86da03b498e8ed8e9838d6f3d107420dfcd9
     */
    val key0: BinaryData = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    val key1: BinaryData = "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
    val key2: BinaryData = "2122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f40"
    val key3: BinaryData = "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f"
    val key4: BinaryData = "4142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f60"

    val initiator = HandshakeState.initializeWriter(
      handshakePattern = handshakePatternXK,
      prologue = BinaryData.empty,
      s = Curve25519DHFunctions.generateKeyPair(key0), e = KeyPair(BinaryData.empty, BinaryData.empty),
      rs = Curve25519DHFunctions.generateKeyPair(key1).pub, re = BinaryData.empty,
      dh = Curve25519DHFunctions,
      Chacha20Poly1305CipherFunctions,
      SHA256HashFunctions,
      FixedStream(key3))

    val responder = HandshakeState.initializeReader(
      handshakePattern = handshakePatternXK,
      prologue = BinaryData.empty,
      s = Curve25519DHFunctions.generateKeyPair(key1), e = KeyPair(BinaryData.empty, BinaryData.empty),
      rs = BinaryData.empty, re = BinaryData.empty,
      dh = Curve25519DHFunctions,
      Chacha20Poly1305CipherFunctions,
      SHA256HashFunctions,
      FixedStream(key4))

    val (outputs, (enc, dec)) = handshake(initiator, responder, BinaryData.empty :: BinaryData.empty :: BinaryData.empty :: Nil)
    assert(outputs == List(
      BinaryData("358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd1662549963aa4003cb0f60f51f7f8b1c0e6a9c"),
      BinaryData("64b101b1d0be5a8704bd078f9895001fc03e8e9f9522f188dd128d9846d4846630166c893dafe95f71d102a8ac640a52"),
      BinaryData("24a819b832ab7a11dd1464c2baf72f2c49e0665757911662ab11495a5fd4437e0abe01f5c07176e776e02716c4cb98a005ec4c884c4dc7500d2d9b99e9670ab3")
    ))

    val (enc1, ciphertext01) = enc.encryptWithAd(BinaryData.empty, BinaryData("79656c6c6f777375626d6172696e65"))
    assert(ciphertext01 == BinaryData("e8b0f2fc220f7edc287a91ba45c76f6da1327405789dc61e31a649f57d6d93"))
    val (dec1, ciphertext02) = dec.encryptWithAd(BinaryData.empty, BinaryData("7375626d6172696e6579656c6c6f77"))
    assert(ciphertext02 == BinaryData("ed6901a7cd973e880242b047fc86da03b498e8ed8e9838d6f3d107420dfcd9"))
  }

  test("Noise_XK_secp256k1_ChaChaPoly_SHA256 test vectors") {
    val dh = Secp256k1DHFunctions
    val prologue = "lightning".getBytes("UTF-8")

    object Initiator {
      val s = dh.generateKeyPair("1111111111111111111111111111111111111111111111111111111111111111")
      val e: BinaryData = "1212121212121212121212121212121212121212121212121212121212121212"
    }
    object Responder {
      val s = dh.generateKeyPair("2121212121212121212121212121212121212121212121212121212121212121")
      val e: BinaryData = "2222222222222222222222222222222222222222222222222222222222222222"
    }

    val initiator = HandshakeState.initializeWriter(
      handshakePattern = handshakePatternXK,
      prologue = prologue,
      s = Initiator.s, e = KeyPair(BinaryData.empty, BinaryData.empty), rs = Responder.s.pub, re = BinaryData.empty,
      dh = dh, cipher = Chacha20Poly1305CipherFunctions, hash = SHA256HashFunctions,
      byteStream = FixedStream(Initiator.e))

    val responder = HandshakeState.initializeReader(
      handshakePattern = handshakePatternXK,
      prologue = prologue,
      s = Responder.s, e = KeyPair(BinaryData.empty, BinaryData.empty), rs = BinaryData.empty, re = BinaryData.empty,
      dh = dh, cipher = Chacha20Poly1305CipherFunctions, hash = SHA256HashFunctions,
      byteStream = FixedStream(Responder.e))

    val (outputs, (enc, dec)) = handshake(initiator, responder, BinaryData.empty :: BinaryData.empty :: BinaryData.empty :: Nil)

    assert(enc.asInstanceOf[InitializedCipherState].k == BinaryData("0x969ab31b4d288cedf6218839b27a3e2140827047f2c0f01bf5c04435d43511a9"))
    assert(dec.asInstanceOf[InitializedCipherState].k == BinaryData("0xbb9020b8965f4df047e07f955f3c4b88418984aadc5cdb35096b9ea8fa5c3442"))
    val (enc1, ciphertext01) = enc.encryptWithAd(BinaryData.empty, BinaryData("79656c6c6f777375626d6172696e65"))
    val (dec1, ciphertext02) = dec.encryptWithAd(BinaryData.empty, BinaryData("7375626d6172696e6579656c6c6f77"))
    assert(outputs == List(
      BinaryData("0x036360e856310ce5d294e8be33fc807077dc56ac80d95d9cd4ddbd21325eff73f70df6086551151f58b8afe6c195782c6a"),
      BinaryData("0x02466d7fcae563e5cb09a0d1870bb580344804617879a14949cf22285f1bae3f276e2470b93aac583c9ef6eafca3f730ae"),
      BinaryData("0xb9e3a702e93e3a9948c2ed6e5fd7590a6e1c3a0344cfc9d5b57357049aa22355361aa02e55a8fc28fef5bd6d71ad0c38228dc68b1c466263b47fdf31e560e139ba")
    ))
    assert(ciphertext01 == BinaryData("b64b348cbb37c88e5b76af12dce00a4a69cbe224a374aad16a4ab1b93741c4"))
    assert(ciphertext02 == BinaryData("289de201e633a43e01ea5b0ec1df9726bd04d0109f530f7172efa5808c3108"))
  }
}

object NoiseSpec {


  object Curve25519DHFunctions extends DHFunctions {
    val curve = CustomNamedCurves.getByName("curve25519")

    override val name = "25519"

    override def generateKeyPair(priv: BinaryData): KeyPair = {
      val pub = new Array[Byte](32)
      Curve25519.eval(pub, 0, priv, null)
      KeyPair(pub, priv)
    }

    override def dh(keyPair: KeyPair, publicKey: BinaryData): BinaryData = {
      val sharedKey = new Array[Byte](32)
      Curve25519.eval(sharedKey, 0, keyPair.priv, publicKey)
      sharedKey
    }

    override def dhLen: Int = 32

    override def pubKeyLen: Int = 32
  }

  /**
    * ByteStream implementation that always returns the same data.
    */
  case class FixedStream(data: BinaryData) extends ByteStream {
    override def nextBytes(length: Int): BinaryData = data
  }

  /**
    * Performs a Noise handshake. Initiator and responder must use the same handshake pattern.
    *
    * @param init    initiator
    * @param resp    responder
    * @param inputs  inputs messages (can all be empty, but the number of input messages must be equal to the number of
    *                remaining handshake patterns)
    * @param outputs accumulator, for internal use only
    * @return the list of output messages produced during the handshake, and the pair of cipherstates produced during the
    *         final stage of the handshake
    */
  def handshake(init: HandshakeStateWriter, resp: HandshakeStateReader, inputs: List[BinaryData], outputs: List[BinaryData] = Nil): (List[BinaryData], (CipherState, CipherState)) = {
    assert(init.messages == resp.messages)
    assert(init.messages.length == inputs.length)
    (inputs: @unchecked) match {
      case last :: Nil =>
        val (_, message, Some((ics0, ics1, _))) = init.write(last)
        val (_, _, Some((rcs0, rcs1, _))) = resp.read(message)
        assert(ics0 == rcs0)
        assert(ics1 == rcs1)
        ((message :: outputs).reverse, (ics0, ics1))
      case head :: tail =>
        val (resp1, message, None) = init.write(head)
        val (init1, _, None) = resp.read(message)
        handshake(init1, resp1, tail, message :: outputs)
    }
  }
}