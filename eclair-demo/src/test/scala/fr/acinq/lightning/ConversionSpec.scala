package fr.acinq.lightning

import fr.acinq.bitcoin.{Crypto, BinaryData}
import lightning.{signature, sha256_hash}
import org.junit.runner.RunWith
import org.scalatest.{FunSuite, FlatSpec}
import org.scalatest.junit.JUnitRunner

import scala.util.Random

@RunWith(classOf[JUnitRunner])
class ConversionSpec extends FunSuite {
  val random = new Random()
  def randomData(size: Int) = {
    val data = new Array[Byte](size)
    random.nextBytes(data)
    data
  }

  test("sha256 conversion") {
    val hash: BinaryData = randomData(32)
    val sha256: sha256_hash = hash
    val hash1: BinaryData = sha256
    assert(hash === hash1)
  }

  test("signature conversion") {
    for(i <- 0 to 100) {
      val priv: BinaryData = randomData(32)
      val pub: BinaryData = Crypto.publicKeyFromPrivateKey(priv)
      val data: BinaryData = randomData(73)
      val sig: BinaryData = Crypto.encodeSignature(Crypto.sign(data, priv))
      assert(Crypto.verifySignature(data, sig, pub))
      val protosig: signature = sig
      val sig1: BinaryData = protosig
      assert(Crypto.verifySignature(data, sig1, pub))
      assert(sig1 === sig)
    }
  }
}
