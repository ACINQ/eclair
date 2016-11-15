package fr.acinq.eclair.crypto

import java.math.BigInteger
import java.security.{SecureRandom, Security}
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}

import fr.acinq.bitcoin.{BinaryData, Crypto, Protocol}
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaChaEngine
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.{KeyParameter, ParametersWithIV}
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider

import scala.util.Random

/**
 * Created by PM on 27/10/2015.
 */
object LightningCrypto {

  Security.addProvider(new BouncyCastleProvider())

  def ecdh(pub: BinaryData, priv: BinaryData): BinaryData = {
    val ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
    val pubPoint = ecSpec.getCurve.decodePoint(pub)
    val mult = pubPoint.multiply(new BigInteger(1, priv)).normalize()
    val x = mult.getXCoord.toBigInteger.toByteArray.takeRight(32)
    val prefix = if (mult.getYCoord.toBigInteger.testBit(0)) 0x03.toByte else 0x02.toByte
    Crypto.sha256(prefix +: x)
  }

  def hmac256(key: Seq[Byte], data: Seq[Byte]): BinaryData = {
    val mac = new HMac(new SHA256Digest())
    mac.init(new KeyParameter(key.toArray))
    mac.update(data.toArray, 0, data.length)
    val out = new Array[Byte](mac.getMacSize)
    mac.doFinal(out, 0)
    out
  }

  def tweak_hash(buf: Array[Byte], tweak: Byte): Array[Byte] = {
    val digest = new SHA256Digest
    digest.update(buf, 0, buf.length)
    digest.update(tweak)
    val out = new Array[Byte](digest.getDigestSize)
    digest.doFinal(out, 0)
    out
  }

  def poly1305(key: BinaryData, data: BinaryData): BinaryData = {
    val out = new Array[Byte](16)
    Poly3105.crypto_onetimeauth(out, 0, data, 0, data.length, key)
    out
  }

  def chacha20Encrypt(plaintext: BinaryData, key: BinaryData, nonce: BinaryData, counter: Int = 0) : BinaryData = {
    val engine = new ChaChaEngine(20)
    engine.init(true, new ParametersWithIV(new KeyParameter(key), nonce))
    val ciphertext: BinaryData = new Array[Byte](plaintext.length)
    counter match {
      case 0 => ()
      case 1 =>
        // skip 1 block == set counter to 1 instead of 0
        val dummy = new Array[Byte](64)
        engine.processBytes(new Array[Byte](64), 0, 64, dummy, 0)
      case _ => throw new RuntimeException(s"chacha20 counter must be 0 or 1")
    }
    val len = engine.processBytes(plaintext.toArray, 0, plaintext.length, ciphertext, 0)
    assert(len == plaintext.length)
    ciphertext
  }

  def chacha20Decrypt(ciphertext: BinaryData, key: BinaryData, nonce: BinaryData, counter: Int = 0) : BinaryData = {
    val engine = new ChaChaEngine(20)
    engine.init(false, new ParametersWithIV(new KeyParameter(key), nonce))
    val plaintext: BinaryData = new Array[Byte](ciphertext.length)
    counter match {
      case 0 => ()
      case 1 =>
        // skip 1 block == set counter to 1 instead of 0
        val dummy = new Array[Byte](64)
        engine.processBytes(new Array[Byte](64), 0, 64, dummy, 0)
      case _ => throw new RuntimeException(s"chacha20 counter must be 0 or 1")
    }
    val len = engine.processBytes(ciphertext.toArray, 0, ciphertext.length, plaintext, 0)
    assert(len == ciphertext.length)
    plaintext
  }

  def poly1305KenGen(key: BinaryData, nonce: BinaryData): BinaryData = chacha20Encrypt(new Array[Byte](32), key, nonce)

  def pad16(data: Seq[Byte]) : Seq[Byte] =
    if (data.size % 16 == 0)
      Seq.empty[Byte]
    else
      Seq.fill[Byte](16 - (data.size % 16))(0)

  object AeadChacha20Poly1305 {
    def encrypt(key: BinaryData, nonce: BinaryData, plaintext: BinaryData, aad: BinaryData) : (BinaryData, BinaryData) = {
      val polykey: BinaryData = poly1305KenGen(key, nonce)
      val ciphertext = chacha20Encrypt(plaintext, key, nonce, 1)
      val data = aad ++ Protocol.writeUInt64(aad.length) ++ ciphertext ++ Protocol.writeUInt64(ciphertext.length)
      val tag = poly1305(polykey, data)
      (ciphertext, tag)
    }

    def decrypt(key: BinaryData, nonce: BinaryData, ciphertext: BinaryData, aad: BinaryData, mac: BinaryData) : BinaryData = {
      val polykey: BinaryData = poly1305KenGen(key, nonce)
      val data = aad ++ Protocol.writeUInt64(aad.length) ++ ciphertext ++ Protocol.writeUInt64(ciphertext.length)
      val tag = poly1305(polykey, data)
      val plaintext = chacha20Decrypt(ciphertext, key, nonce, 1)
      assert(tag == mac, "invalid mac")
      plaintext
    }
  }

  def aesEncrypt(data: Array[Byte], key: Array[Byte], iv: Array[Byte]): BinaryData = {
    val ivSpec = new IvParameterSpec(iv)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding ", "BC")
    val secretKeySpec = new SecretKeySpec(key, "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)
    cipher.doFinal(data)
  }

  def aesDecrypt(data: Array[Byte], key: Array[Byte], iv: Array[Byte]): BinaryData = {
    val ivSpec = new IvParameterSpec(iv)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding ", "BC")
    val secretKeySpec = new SecretKeySpec(key, "AES")
    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
    cipher.doFinal(data)
  }

  def aesEncryptCipher(key: Array[Byte], iv: Array[Byte]): Cipher = {
    val ivSpec = new IvParameterSpec(iv)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding ", "BC")
    val secretKeySpec = new SecretKeySpec(key, "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec)
    cipher
  }

  def aesDecryptCipher(key: Array[Byte], iv: Array[Byte]): Cipher = {
    val ivSpec = new IvParameterSpec(iv)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding ", "BC")
    val secretKeySpec = new SecretKeySpec(key, "AES")
    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
    cipher
  }

  case class KeyPair(pub: BinaryData, priv: BinaryData)

  // see http://bugs.java.com/view_bug.do?bug_id=6521844
  //lazy val random = SecureRandom.getInstanceStrong
  lazy val random = new Random()

  def randomKeyPair(): KeyPair = {
    val key = new Array[Byte](32)
    random.nextBytes(key)
    KeyPair(pub = Crypto.publicKeyFromPrivateKey(key :+ 0x01.toByte), priv = key)
  }


}
