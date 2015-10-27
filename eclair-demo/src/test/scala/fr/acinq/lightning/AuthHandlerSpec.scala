package fr.acinq.lightning

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
    val msg2 = AuthHandler.readMsg(AuthHandler.writeMsg(msg1, secrets), secrets)
    assert(msg1 === msg2)
  }

}
