package fr.acinq.eclair.crypto

import java.math.BigInteger
import java.security.{SecureRandom, Security}
import javax.crypto.Cipher
import javax.crypto.spec.{IvParameterSpec, SecretKeySpec}

import fr.acinq.bitcoin.{BinaryData, Crypto}
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider

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

  case class KeyPair(pub: BinaryData, priv: BinaryData)

  lazy val rand = new SecureRandom()

  def randomKeyPair(): KeyPair = {
    val key = new Array[Byte](32)
    rand.nextBytes(key)
    KeyPair(pub = Crypto.publicKeyFromPrivateKey(key :+ 0x01.toByte), priv = key)
  }


}
