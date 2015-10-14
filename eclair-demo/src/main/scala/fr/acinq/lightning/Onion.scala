package fr.acinq.lightning

import java.math.BigInteger
import java.nio.file.{FileSystems, Files}
import java.security.{KeyFactory, Security}
import javax.crypto.{Cipher, KeyAgreement}
import javax.crypto.spec.{SecretKeySpec, IvParameterSpec}

import fr.acinq.bitcoin.{BinaryData, Crypto}
import org.bouncycastle.crypto.digests.{SHA512Digest, SHA256Digest}
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.{ECPublicKeySpec, ECPrivateKeySpec}


/**
 * Created by PM on 14/10/2015.
 */
object Onion extends App {

  val pub_node = BinaryData("03c79f1f4aa1aaee5118e2c3cb809c34f9c70606ca77c519e2289a5b48ccb343fb")
  val priv_node = BinaryData("1DA94C44F8843628930806C6BF0B6FCAB005A9285BC23641397BDE189C01A746")
  val msg = Files.readAllBytes(FileSystems.getDefault().getPath("msg"))

  assert(msg.length == 3840)

  val (decoded, newmsg) = decode(msg)
  println(s"decoded: '${new String(decoded)}'")

  def decode(buf: BinaryData): (BinaryData, BinaryData) = {
      val sig = BinaryData(msg.takeRight(32))
      val pub = BinaryData(0x2.toByte +: msg.takeRight(64).take(32))
    val ecdh_key = ecdh(pub, priv_node)
    val secrets = generate_secrets(ecdh_key)

    val sig2 = hmac256(secrets.hmac_key, msg.take(3840 - 32))
    if (!sig.data.sameElements(sig2.data)) throw new RuntimeException("sig mismatch!")

    val decrypted = aesDecrypt(msg.take(3840 - 64), secrets.aes_key, secrets.iv)
    val payload = decrypted.takeRight(128)

    val newmsg = aesEncrypt(Array.fill[Byte](192)(0x00.toByte), secrets.aes_key, secrets.pad_iv) ++ msg

    (payload, newmsg)
  }

  case class Secrets(aes_key: BinaryData, hmac_key: BinaryData, iv: BinaryData, pad_iv: BinaryData)

  def ecdh(pub: BinaryData, priv: BinaryData) = {
    Security.addProvider(new BouncyCastleProvider())
    val ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
    val curve = ecSpec.getCurve

    val priKeySpec = new ECPrivateKeySpec(
      new BigInteger(priv), // d
      ecSpec)
    val pubKeySpec = new ECPublicKeySpec(
      curve.decodePoint(pub), // Q
      ecSpec)

    val fact = KeyFactory.getInstance("ECDSA", "BC")

    val a = fact.generatePrivate(priKeySpec)
    val B = fact.generatePublic(pubKeySpec)

    val agreement = KeyAgreement.getInstance("ECDH", "BC")
    agreement.init(a)
    agreement.doPhase(B, true)
    //TODO prepend with 02/03 depending on y parity
    val key = BinaryData(0x03.toByte +: agreement.generateSecret())

    key
  }

  def generate_secrets(ecdh_key: BinaryData): Secrets = {

    val key_hash = Crypto.sha256(ecdh_key)

    def tweak_hash(buf: Array[Byte], tweak: Byte): Array[Byte] = {
      val digest = new SHA256Digest
      digest.update(key_hash, 0, key_hash.length)
      digest.update(tweak)
      val out = new Array[Byte](digest.getDigestSize)
      digest.doFinal(out, 0)
      out
    }

    val enckey = tweak_hash(key_hash, 0x00).take(16)
    val hmac = tweak_hash(key_hash, 0x01)
    val ivs = tweak_hash(key_hash, 0x02)
    val iv = ivs.take(16)
    val pad_iv = ivs.takeRight(16)

    Secrets(enckey, hmac, iv, pad_iv)
  }

  def hmac256(key: Seq[Byte], data: Seq[Byte]): BinaryData = {
    val mac = new HMac(new SHA256Digest())
    mac.init(new KeyParameter(key.toArray))
    mac.update(data.toArray, 0, data.length)
    val out = new Array[Byte](mac.getMacSize)
    mac.doFinal(out, 0)
    out
  }

  def aesEncrypt(data: Array[Byte], key: Array[Byte], iv: Array[Byte]): BinaryData = {
    val ivSpec  = new IvParameterSpec(iv)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding ", "BC")
    val secretKeySpec = new SecretKeySpec(key, "AES")
    cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec, ivSpec);
    val encrypted = new Array[Byte](cipher.getOutputSize(data.length))
    val ctLength = cipher.update(data, 0, data.length, encrypted,0)
    cipher.doFinal (encrypted, ctLength)
    encrypted
  }

  def aesDecrypt(data: Array[Byte], key: Array[Byte], iv: Array[Byte]): BinaryData = {
    val ivSpec  = new IvParameterSpec(iv)
    val cipher = Cipher.getInstance("AES/CTR/NoPadding ", "BC")
    val secretKeySpec = new SecretKeySpec(key, "AES")
    cipher.init(Cipher.DECRYPT_MODE, secretKeySpec, ivSpec)
    val decrypted = new Array[Byte](cipher.getOutputSize(data.length))
    val ptLength = cipher.update(data, 0, data.length, decrypted, 0)
    cipher.doFinal (decrypted, ptLength)
    decrypted
  }

}
