package fr.acinq.lightning

import fr.acinq.bitcoin.BinaryData
import fr.acinq.lightning.LightningCrypto._
import lightning.{error, pkt}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import AuthHandler._

/**
 * Created by PM on 27/10/2015.
 */
@RunWith(classOf[JUnitRunner])
class AuthHandlerSpec extends FunSuite {

  test("encrypt/decrypt loop") {
    val keypairA = randomKeyPair()
    val keypairB = randomKeyPair()
    val secrets = generate_secrets(ecdh(keypairA.pub, keypairB.priv), keypairA.pub)
    val msg1 = pkt().withError(error(Some("hello")))
    val (data, totlen) = writeMsg(msg1, secrets, 0)
    var msg2: pkt = null
    val (_, totlen2) = split(data, secrets, 0, d => msg2 = pkt.parseFrom(d))
    assert(msg1 === msg2)
  }

  test("decrypt msgs received in bulk") {
    val keypairA = randomKeyPair()
    val keypairB = randomKeyPair()
    val secrets = generate_secrets(ecdh(keypairA.pub, keypairB.priv), keypairA.pub)
    val msgs = for (i <- 0 until 3) yield pkt().withError(error(Some(s"hello #$i!")))
    val (data0, totlen0) = writeMsg(msgs(0), secrets, 0)
    val (data1, totlen1) = writeMsg(msgs(1), secrets, totlen0)
    val (data2, totlen2) = writeMsg(msgs(2), secrets, totlen1)
    val data = data0 ++ data1 ++ data2
    var splitted: Seq[pkt] = Nil
    split(data, secrets, 0, d => splitted = splitted :+ pkt.parseFrom(d))
    assert(msgs === splitted)
  }

}
