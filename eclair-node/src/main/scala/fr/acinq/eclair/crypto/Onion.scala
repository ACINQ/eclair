package fr.acinq.eclair.crypto

import java.security.SecureRandom

import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.crypto.LightningCrypto._

import scala.util.Random


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

  def generate_secrets(ecdh_key: BinaryData): Secrets = {

    val key_hash: BinaryData = Crypto.sha256(ecdh_key)

    val enckey = tweak_hash(key_hash, 0x00).take(16)
    val hmac = tweak_hash(key_hash, 0x01)
    val ivs = tweak_hash(key_hash, 0x02)
    val iv = ivs.take(16)
    val pad_iv = ivs.takeRight(16)

    Secrets(enckey, hmac, iv, pad_iv)
  }

  // see http://bugs.java.com/view_bug.do?bug_id=6521844
  //lazy val random = SecureRandom.getInstanceStrong
  lazy val random = new Random()


  def generatePrivateKey(): BinaryData = {
    val key = new Array[Byte](32)
    do {
      random.nextBytes(key)
    } while (Crypto.publicKeyFromPrivateKey(key :+ 0x01.toByte)(0) != 0x02)
    key
  }
}
