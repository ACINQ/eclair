package fr.acinq.lightning

import java.math.BigInteger
import java.nio.file.{FileSystems, Files}
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
 * Created by PM on 14/10/2015.
 */
object Onion extends App {
  lazy val zeroes: BinaryData = Array.fill(192)(0:Byte)

  def encPad(secrets: Secrets)(input: BinaryData) : BinaryData = aesEncrypt(input, secrets.aes_key, secrets.pad_iv)

  def decPad(secrets: Secrets)(input: BinaryData) : BinaryData = aesDecrypt(input, secrets.aes_key, secrets.pad_iv)

  def encMsg(secrets: Secrets)(input: BinaryData) : BinaryData = aesEncrypt(input, secrets.aes_key, secrets.iv)

  def decMsg(secrets: Secrets)(input: BinaryData) : BinaryData = aesDecrypt(input, secrets.aes_key, secrets.iv)

  def hmac(secrets: Secrets)(input: BinaryData) : BinaryData = hmac256(secrets.hmac_key, input)

  def makeLastMessage(clientPrivateKeys: Seq[BinaryData], nodePublicKeys: Seq[BinaryData], plaintext: Seq[BinaryData]) : BinaryData = {
    val size = clientPrivateKeys.length
    val secrets = for (i <- 0 until size) yield generate_secrets(ecdh(nodePublicKeys(i), clientPrivateKeys(i)))
    var padding: BinaryData = Array.empty[Byte]
    for (i <- 0 until size - 1) {
      padding = if (i == 0) encPad(secrets(i))(zeroes) else encPad(secrets(i))(zeroes) ++ decMsg(secrets(i))(padding)
    }
    val encrypted: BinaryData = encMsg(secrets.last)(encMsg(secrets.last)(padding) ++ plaintext.last)
    val pub: BinaryData = Crypto.publicKeyFromPrivateKey(clientPrivateKeys.last :+ 1.toByte)
    val unsigned = encrypted ++ pub.drop(1)
    unsigned ++ hmac(secrets.last)(unsigned)
  }

  def makePreviousMessage(nextMessage: BinaryData, secret: Secrets, clientPrivateKey: BinaryData, plaintext: BinaryData) : BinaryData = {
    val encrypted = encMsg(secret)(nextMessage.drop(192) ++ plaintext)
    val pub: BinaryData = Crypto.publicKeyFromPrivateKey(clientPrivateKey :+ 1.toByte)
    val unsigned = encrypted ++ pub.drop(1)
    unsigned ++ hmac(secret)(unsigned)
  }

  def makeFirstMessage(clientPrivateKeys: Seq[BinaryData], nodePublicKeys: Seq[BinaryData], plaintext: Seq[BinaryData]) : BinaryData = {
    Security.addProvider(new BouncyCastleProvider())
    val size = clientPrivateKeys.length
    val secrets = for (i <- 0 until size) yield generate_secrets(ecdh(nodePublicKeys(i), clientPrivateKeys(i)))
    val lastMessage = makeLastMessage(clientPrivateKeys, nodePublicKeys, plaintext)
    var firstMessage: BinaryData = lastMessage
    for (i <- (size - 2) to 0 by -1) {
      firstMessage = makePreviousMessage(firstMessage, secrets(i), clientPrivateKeys(i), plaintext(i))
    }
    firstMessage
  }

  def decode(buf: BinaryData, priv: BinaryData): (BinaryData, BinaryData) = {
    val sig = BinaryData(buf.takeRight(32))
    // by convention only 02XXX...XX keys are used (so that they take only 32 bytes)
    val pub = BinaryData(0x02.toByte +: buf.takeRight(64).take(32))
    val ecdh_key = ecdh(pub, priv)

    val secrets = generate_secrets(ecdh_key)

    val sig2 = hmac256(secrets.hmac_key, buf.dropRight(32))
    if (!sig.data.sameElements(sig2.data)) throw new RuntimeException("sig mismatch!")

    val decrypted:BinaryData = aesDecrypt(buf.dropRight(64).toArray, secrets.aes_key, secrets.iv)
    val payload = decrypted.takeRight(128)
    val payloadstring = new String(payload.toArray)
    val newmsg = aesEncrypt(Array.fill[Byte](192)(0x00), secrets.aes_key, secrets.pad_iv) ++ decrypted.dropRight(128)
    (payload, newmsg)
  }

  case class Secrets(aes_key: BinaryData, hmac_key: BinaryData, iv: BinaryData, pad_iv: BinaryData)

  def ecdh(pub: BinaryData, priv: BinaryData): BinaryData = {
    val ecSpec = ECNamedCurveTable.getParameterSpec("secp256k1")
    val pubPoint = ecSpec.getCurve.decodePoint(pub)
    val mult = pubPoint.multiply(new BigInteger(1, priv)).normalize()
    val x = mult.getXCoord.toBigInteger.toByteArray.takeRight(32)
    val prefix = if (mult.getYCoord.toBigInteger.testBit(0)) 0x03.toByte else 0x02.toByte
    BinaryData(prefix +: x)
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

  lazy val rand = new SecureRandom()

  def generatePrivateKey(): BinaryData = {
    val key = new Array[Byte](32)
    do {
      rand.nextBytes(key)
    } while (Crypto.publicKeyFromPrivateKey(key :+ 0x01.toByte)(0) != 0x02)
    key
  }
}
