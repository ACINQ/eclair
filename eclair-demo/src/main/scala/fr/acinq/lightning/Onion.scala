package fr.acinq.lightning

import java.math.BigInteger
import java.nio.file.{FileSystems, Files}
import java.security.{SecureRandom, Security}
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

  /*for (i <- 0 to 20) {
    val priv = generatePrivateKey()
    val pub = BinaryData(Crypto.publicKeyFromPrivateKey(priv :+ 0x01.toByte))
    println(s"$priv\t$pub")
  }*/

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

  /*
    client private keys :

    f5d71072c527ae231db4a4c5e1c39db41344f385a770f737157d2cf40510a604
    5de53aca1f7c2a95c9b830d77c34ebe9e0b75a8bf4a71ce4b662d595b1777f50
    fa6766b4d59a7649f2ef77cd08f32451bbcf0b6b0d5ed3134fdab3bbf4aff793
    739150e57aff1c38ad14261ba2b771dd6b2b7e6894060445ee63245d27825a23
    678756d6e127af119b9a1bb8908b2edb94c8ea851c7483f5f184d0bf5a4cfaa2
    6f9df4bb8e41f4a01aa72a0389f1cd01913bf0eb70d0f737d898ad429144c34b
    95846a33262763dbd9fb7cc5e805c31b137e356003cf0adcab004d755b0e36c7
    f3df7e4dff1d6f8c3272b5f574ec93577067d27c80aeee8eb0adb022cd350ff2
    1b59930262519b48af08c11b871d4b8b65481013cf4d2365321d7b6e2a3b83af
    06445ccdb669de4f780e4e4f6f3fe79cca06e4ac9abdfede9c7442e0c810b25c
    e0fff81efc94d8c0ca82dbfe419ea34be1eb9b351d89201f456e6c35b3301590
    87aebf102df6cec68f1515a58c6dea1aa4e310bee8836e43f4dbbb206ba9c782
    44319929b580e775cfef7eee0bf706a4e25cf5d7805b9a2a69542e06858b15bd
    dd5976e51cc89eac28124620a79ffbe037fb973307cce61204dce119a1cbbb3c
    186a6c1a15eb9483060c0d7513f9643c248f550ca39cc989dd267ceec69a4412
    95296c4906461973ee8a0c9d3a37c99935dc6fd1ec3a4174fdee84fbb4265a0b
    5722c1797d81817436518cd274007a867d232c6b288872b0e2f859b0ba3f4943
    71063eb3291a2f5c97228649d037ea5efa2a51a525338f9f9ea4860194f4a7f9
    4cc67010487c08db0eefa4995b721ca2034e9d2a31f3b1c6e6fa92d59c5f6060
    d93db8019a9392b3df11929896639ab1a069a0096e3defa595af16544957526b
   */

  val node_keys = List(
    ("665af9917fbbd9758fd53352275f9b555296ee16e20a73a069931959c0980935", "0204053aed6a50fd4062331fc951d5da830ba64c02bfd7a64571fe16a7c46befa8"),
    ("277ada60ef0f16492325e8624ba0737c1b0e737360f640385079d8f7873fb60f", "02b270abbe82d184d808ea8dfab38b65e86e10a3c3ba97ebc1d67b2a6cea823fe6"),
    ("ae497fa5035c0700d5f2166386c09625c5f9f426d707190866d7125a788f0304", "026f6c65b7352c5803c753909da1fc97e0fceee032e22002797c917f7377dc7217"),
    ("76e37adf50762fd334c2161ee7bd7e728b621d0c2131af9175be5b867ec908fc", "024393f0f8db63b8d3371995436d28d2f10b91117cfc7e9779d2b5b4a6702102ab"),
    ("0c35bc64589c7b6aecd14213dafb196d40f54264ccec173edf0b9d2df6d69dfd", "026551b360902375757424304b87f8fb690b11c74f698625d31807d526cf9891b4"),
    ("e34853d16b71b22a85b2373b3f231b73d4d4a4d4852c56aa0f92ea5f337c7b93", "020bc61f4511d1d3086c78eebf976cc3cd24e06632fa0258d130c6518135b42270"),
    ("b337ffe24504553a62e6df3c4447b91e7590e0789e3dce1429048298dcd8ca90", "028ad1d53cf4a2deca83793810f520a309a638afa25478a47e552439d44fe9436c"),
    ("c3310054174dd03e250a548799a92f9e0c5d03954d5c884b483da7de248f686e", "027bb15dc88014a9c4c08bb8b738d7ab66e90c854a18818901399088f229149a7a"),
    ("2c46588a9f385a9ac497a65b742d05817d225424ffaafd6b314a058aeac8d0b2", "022702c77cf82f564063cb270da5a87cb11a9c2eb59b504cc210bd287dd25d90f1"),
    ("51867838ac3d615f9f59e5ec9dacd0b2d43ab20d9834eb107968f073e29088f6", "0212edd75e97f592203eeec63fd69543e506eaf83da507e401c1cfc34621cff7a0"),
    ("a6e26869e95f561757d3984e70546730e15d4375824057e6f4d358603ad34106", "0206fd8c64dc0a5938824c16f138d035a0352b306d58d780bf49707a5c53dd9824"),
    ("61d7ca7f49b20aa9b2d422dda27c03a02c218db39acf083b22d356ea98a9108f", "0283b9835aecf52c4bd00342908aceda48552184cb21b205e39ef53468aef81c00"),
    ("7dc338e19fe37a2dd366973f16b4caa5e78320dcee49bcf4949e8532aca4cd5c", "02f82b6e7b6d624a4844c5adb946fdd52afac9b7bcabca302daaa902581141cbc8"),
    ("96ff5474a560fac0b76a34e908ec34b93ec632ecbc273ba959e631b1e539e91c", "02dfce8efa9c4a6b99ccb370647ebd4d6b8af1311bdea796bf5308c55452582c02"),
    ("e659eff61eb1f7006a4c4e912a24bc2d73c88bf624e120f94d1ca45cf484f688", "025fd9085645789803a6c5a861dfe78182a117f0a201f86dbcccd4ab84e466bf57"),
    ("afc8052bddb104c6957e07b21afeddbacf21e8418e3709a47ea8e24ade220157", "0260227a20adb45e1f69ca21dfccfe0261f84714a7b206a5b8190e0f09b5ab3ad1"),
    ("9c1f364a849985b21c1f95ba5d719c4f6be2b1e0a38cd9694fdcb59925cf2129", "02ba42326cc6862b0c8ab613cfa396fa5f79190222dd73df2aaff9c0e8acc6cb16"),
    ("67bba93600a6af7b68021513d2a82c3e40206fdae3bcf15e111e8165a1213bd1", "023dcde8ef89511259b0f97108adebb8de78501402845590a45396d030b5826d1d"),
    ("e949e2275e1201ddb02fa71780d112e28d0e2b3c4eb107c0cdc8969066574639", "02ccff5504f13c26c76c095c7d096391687d01d91224db1aa8f82d7a10945286e9"),
    ("7c658eaf5532f5b1227b492f79e32bde474e03454f596066ded64a9e7805d5a3", "028e3d32443d5a927226a3c1959abd21fd7425687a65d39da4d00d19d60bd3dac7")
    )
    var msg: (BinaryData, BinaryData) = (null, BinaryData(Files.readAllBytes(FileSystems.getDefault().getPath("msg20"))))
    for (node_key <- node_keys) {
      msg = decode(msg._2, node_key._1)
      println(s"decoded: '${new String(msg._1)}'")
    }

  /*val hops = List(
    Hop(node_pub_1, ("First message".getBytes ++ Array.fill[Byte](128)(0x00)).take(128)),
    Hop(node_pub_2, ("Second message".getBytes ++ Array.fill[Byte](128)(0x00)).take(128)))
  .map(hop => HopWithSecrets(client_priv, client_pub, hop.their_pub, generate_secrets(ecdh(hop.their_pub, client_priv)), hop.msg))
  val msg = encodeMulti(hops)
  assert(msg.length == 3840)
  val (decoded_1, newmsg_1) = decode(msg, node_priv_1)
  println(s"decoded: '${new String(decoded_1)}'")
  assert(newmsg_1.length == 3840)
  val (decoded_2, newmsg_2) = decode(newmsg_1, node_priv_2)
  println(s"decoded: '${new String(decoded_2)}'")*/

  case class Hop(their_pub: BinaryData, msg: BinaryData)
  case class HopWithSecrets(our_priv: BinaryData, our_pub: BinaryData, their_pub: BinaryData, secrets: Secrets, msg: BinaryData)

  /*def encodeMulti(hops: Seq[HopWithSecrets]): BinaryData = {
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

  }*/

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

  lazy val rand = new SecureRandom()
  def generatePrivateKey(): BinaryData = {
    val key = new Array[Byte](32)
    do {
      rand.nextBytes(key)
    } while (Crypto.publicKeyFromPrivateKey(key :+ 0x01.toByte)(0) != 0x02)
    key
  }

}
