package fr.acinq.eclair.crypto

import fr.acinq.eclair.io.AuthHandler
import AuthHandler._
import fr.acinq.eclair.crypto.LightningCrypto._
import lightning.{error, pkt}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

/**
 * Created by PM on 27/10/2015.
 */
@RunWith(classOf[JUnitRunner])
class AuthHandlerSpec extends FunSuite {

  test("encrypt/decrypt loop") {
    val keypairA = randomKeyPair()
    val keypairB = randomKeyPair()
    val secrets = generate_secrets(ecdh(keypairA.pub, keypairB.priv), keypairA.pub)
    val cipher_in = aesDecryptCipher(secrets.aes_key, secrets.aes_iv)
    val cipher_out = aesEncryptCipher(secrets.aes_key, secrets.aes_iv)
    val msg1 = pkt().withError(error(Some("hello")))
    val (data, totlen) = writeMsg(msg1, secrets, cipher_out, 0)
    var msg2: pkt = null
    val (_, totlen2) = split(data, secrets, cipher_in, 0, d => msg2 = pkt.parseFrom(d))
    assert(msg1 === msg2)
  }

  test("decrypt msgs received in bulk") {
    val keypairA = randomKeyPair()
    val keypairB = randomKeyPair()
    val secrets = generate_secrets(ecdh(keypairA.pub, keypairB.priv), keypairA.pub)
    val cipher_in = aesDecryptCipher(secrets.aes_key, secrets.aes_iv)
    val cipher_out = aesEncryptCipher(secrets.aes_key, secrets.aes_iv)
    val msgs = for (i <- 0 until 3) yield pkt().withError(error(Some(s"hello #$i!")))
    val (data0, totlen0) = writeMsg(msgs(0), secrets, cipher_out, 0)
    val (data1, totlen1) = writeMsg(msgs(1), secrets, cipher_out, totlen0)
    val (data2, totlen2) = writeMsg(msgs(2), secrets, cipher_out, totlen1)
    val data = data0 ++ data1 ++ data2
    var splitted: Seq[pkt] = Nil
    split(data, secrets, cipher_in, 0, d => splitted = splitted :+ pkt.parseFrom(d))
    assert(msgs === splitted)
  }

}
