package fr.acinq.lightning

import java.math.BigInteger
import java.nio.file.{FileSystems, Files}
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.spec.{SecretKeySpec, IvParameterSpec}

import fr.acinq.bitcoin.{BinaryData, Crypto}
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params. KeyParameter
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider


/**
 * Created by PM on 14/10/2015.
 */
object Onion extends App {
  Security.addProvider(new BouncyCastleProvider())

  val client_priv = BinaryData("5B1D31B60D34800419D6944B4BF8533FA40E1A19534D4F77E2E1DA56F2BE4A2A")
  val client_pub = BinaryData("0204FCE508DF2836842F4EE0AF6E0CDAB7544E9D3A1F9652B65C536157B61FD694")

  val node_priv_1 = BinaryData("FAC7EAD05B043FF16B9A8D8C8DE845C2F9ADC15F2628C35BB292BA2B493CD089")
  val node_pub_1 = BinaryData("0270EE5414731665E45E10B841C895C813CF562CD412A6C98FEC7723BDC840AB70")

  val node_priv_2 = BinaryData("9BDB1239D733C530E4E2E5C0392F33F937A8CCA1B4E33376DA30DCCC15ECD601")
  val node_pub_2 = BinaryData("022A357AE97570040D763C9EC0DD9E47A9CBF0D92CB7BFF4DB1D60F04A04C8F5EB")

  //val encoded = encode(client_priv, node_pub)
  //Files.write(FileSystems.getDefault().getPath("ourmsg"), encoded)

    /*val msg = Files.readAllBytes(FileSystems.getDefault().getPath("msg"))
    assert(msg.length == 3840)
    val (decoded_1, newmsg_1) = decode(msg, node_priv_1)
    println(s"decoded: '${new String(decoded_1)}'")
    assert(newmsg_1.length == 3840)
    val (decoded_2, newmsg_2) = decode(newmsg_1, node_priv_2)
    println(s"decoded: '${new String(decoded_2)}'")*/

  val hops = List(
    Hop(node_pub_1, ("First message".getBytes ++ Array.fill[Byte](128)(0x00)).take(128)),
    Hop(node_pub_2, ("Second message".getBytes ++ Array.fill[Byte](128)(0x00)).take(128)))
  .map(hop => HopWithSecrets(client_priv, client_pub, hop.their_pub, generate_secrets(ecdh(hop.their_pub, client_priv)), hop.msg))
  val msg = encodeMulti(hops)
  assert(msg.length == 3840)
  val (decoded_1, newmsg_1) = decode(msg, node_priv_1)
  println(s"decoded: '${new String(decoded_1)}'")
  assert(newmsg_1.length == 3840)
  val (decoded_2, newmsg_2) = decode(newmsg_1, node_priv_2)
  println(s"decoded: '${new String(decoded_2)}'")

  case class Hop(their_pub: BinaryData, msg: BinaryData)
  case class HopWithSecrets(our_priv: BinaryData, our_pub: BinaryData, their_pub: BinaryData, secrets: Secrets, msg: BinaryData)

  def encodeMulti(hops: Seq[HopWithSecrets]): BinaryData = {
    assert(hops.size <= 20, s"there shouldn't be more than 20 hops (${hops.size})")

    // first we generate the padding
    val padding = BinaryData(hops.foldLeft(Seq[Byte]()) {
      case (agg, hop) => aesEncrypt(Array.fill[Byte](192)(0x00), hop.secrets.aes_key, hop.secrets.pad_iv) ++ agg
    })
      //++ Array.fill[Byte]((20 - hops.size - 1) * 192)(0x00))


    hops.reverse.foldLeft(padding) {
      case (agg, hop) =>
        val encrypted = aesEncrypt((agg.drop ++ hop.msg).toArray, hop.secrets.aes_key, hop.secrets.iv)
        val sig = hmac256(hop.secrets.hmac_key, encrypted ++ hop.our_pub.takeRight(32))
        encrypted ++ hop.our_pub.takeRight(32) ++ sig
    }

  }

  def encode(our_priv: BinaryData, their_pub: BinaryData): BinaryData = {
    val ecdh_key = ecdh(their_pub, our_priv)
    val secrets = generate_secrets(ecdh_key)
    println("encode secrets: " + secrets)
    // 128 bytes payload
    val payload = "I will be back!.................................................................................................................".getBytes("UTF-8")
    val padding = aesDecrypt(Array.fill[Byte](19 * 192)(0x00.toByte), secrets.aes_key, secrets.pad_iv)
    val encrypted = aesEncrypt((padding ++ payload).toArray, secrets.aes_key, secrets.iv)
    val sig = hmac256(secrets.hmac_key, encrypted ++ client_pub.takeRight(32))
    encrypted ++ client_pub.takeRight(32) ++ sig
  }

  def decode(buf: BinaryData, priv: BinaryData): (BinaryData, BinaryData) = {
    val sig = BinaryData(buf.takeRight(32))
    // by convention only 02XXX...XX keys are used (so that they take only 32 bytes)
    val pub = BinaryData(0x02.toByte +: buf.takeRight(64).take(32))
    println("client pubkey: " + pub)
    val ecdh_key = ecdh(pub, priv)
    val secrets = generate_secrets(ecdh_key)
    println("decode secrets: " + secrets)

    val sig2 = hmac256(secrets.hmac_key, buf.dropRight(32))
    if (!sig.data.sameElements(sig2.data)) throw new RuntimeException("sig mismatch!")

    //val decrypted = aesDecrypt(buf.drop(64).toArray, secrets.aes_key, secrets.iv)
    val decrypted = aesDecrypt(buf.dropRight(64).toArray, secrets.aes_key, secrets.iv)
    val payload = decrypted.takeRight(128)

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

}
