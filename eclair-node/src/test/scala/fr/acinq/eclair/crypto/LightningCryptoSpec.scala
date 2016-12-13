package fr.acinq.eclair.crypto

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.crypto.LightningCrypto.AeadChacha20Poly1305
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner


@RunWith(classOf[JUnitRunner])
class LightningCryptoSpec extends FunSuite {
  test("Poly1305") {
    val key: BinaryData = "85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b"
    val data: BinaryData = "Cryptographic Forum Research Group".getBytes("UTF-8")

    val bar = new Array[Byte](16)
    val mac = LightningCrypto.poly1305(key, data)
    assert(mac === BinaryData("a8061dc1305136c6c22b8baf0c0127a9"))
  }

  test("Chacha20") {
    val plaintext: BinaryData = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".getBytes
    val key: BinaryData = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
    val nonce: BinaryData = "0000004a00000000"

    val ciphertext = LightningCrypto.chacha20Encrypt(plaintext, key, nonce, 1)
    assert(ciphertext == BinaryData("6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0bf91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d807ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab77937365af90bbf74a35be6b40b8eedf2785e42874d"))

    assert(LightningCrypto.chacha20Decrypt(ciphertext, key, nonce, 1) == plaintext)
  }

  test("Chacha20Poly1305 key generation test") {
    // see https://tools.ietf.org/html/rfc7539#section-2.6.2
    val key: BinaryData = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"
    val nonce: BinaryData = "0001020304050607"
    assert(LightningCrypto.poly1305KenGen(key, nonce) == BinaryData("8ad5a08b905f81cc815040274ab29471a833b637e3fd0da508dbb8e2fdd1a646"))
  }

  test("AeadChacha20Poly1305 encryption") {
    val plaintext = BinaryData("010203040506070809")
    val key = BinaryData("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    val nonce = BinaryData("0000004a00000000")
    val aad = BinaryData("0000004a00000000")

    val (ciphertext, mac) = AeadChacha20Poly1305.encrypt(key, nonce, plaintext, aad)
    assert(ciphertext == BinaryData("234d52f7451ddee926"))
    assert(mac == BinaryData("00ef1c5c90d980b565b1ef3fb0d1c062"))

    val check = AeadChacha20Poly1305.decrypt(key, nonce, ciphertext, aad, mac)
    assert(check == plaintext)

    intercept[AssertionError] {
      val mac1 = mac
      mac1(0) = 0xff.toByte
      AeadChacha20Poly1305.decrypt(key, nonce, ciphertext, aad, mac1)
    }
  }
}
