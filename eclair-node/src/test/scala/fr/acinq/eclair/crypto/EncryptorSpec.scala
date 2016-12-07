package fr.acinq.eclair.crypto

import java.util.concurrent.{CountDownLatch, TimeUnit}

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props}
import akka.util.ByteString
import fr.acinq.bitcoin.{BinaryData, Crypto, Hash}
import fr.acinq.eclair.channel.simulator.Pipe
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object EncryptorSpec {
  val random = new scala.util.Random()

  class Ping(pong: ActorRef, sendingKey: BinaryData, receivinKey: BinaryData, latch: CountDownLatch) extends Actor with ActorLogging {
    context.system.scheduler.schedule(50 + random.nextInt(50) milliseconds, 25 milliseconds, self, 'send)

    def receive = running(Encryptor(sendingKey, 0), Decryptor(receivinKey, 0))

    def running(encryptor: Encryptor, decryptor: Decryptor): Receive = {
      case chunk: BinaryData =>
        val decryptor1 = Decryptor.add(decryptor, ByteString.fromArray(chunk))
        decryptor1.bodies.map(_ => latch.countDown())
        context become running(encryptor, decryptor1.copy(bodies = Vector.empty[BinaryData]))
      case 'send =>
        val message: BinaryData = s"it is now ${System.currentTimeMillis()}".getBytes("UTF-8")
        val (encryptor1, ciphertext) = Encryptor.encrypt(encryptor, message)
        pong ! ciphertext
        context become running(encryptor1, decryptor)
    }
  }

}

@RunWith(classOf[JUnitRunner])
class EncryptorSpec extends FunSuite {

  import EncryptorSpec._

  test("encryption/description tests") {
    val key: BinaryData = Crypto.sha256(Hash.Zeroes)
    var enc = Encryptor(key, 0)
    var dec = Decryptor(key, 0)

    def run(e: Encryptor, d: Decryptor): (Encryptor, Decryptor) = {
      val plaintext = new Array[Byte](30)
      random.nextBytes(plaintext)
      val (e1, c) = Encryptor.encrypt(e, plaintext)
      val d1 = Decryptor.add(d, ByteString.fromArray(c))
      assert(java.util.Arrays.equals(plaintext, d1.bodies.head))
      (e1, d1.copy(bodies = Vector()))
    }

    for (i <- 0 until 10) {
      val (e, d) = run(enc, dec)
      enc = e
      dec = d
    }
  }

  test("decryption of several messages in multiples chunk") {
    val key: BinaryData = Crypto.sha256(Hash.Zeroes)
    val enc = Encryptor(key, 0)
    var dec = Decryptor(key, 0)

    val plaintext1: BinaryData = new Array[Byte](200)
    random.nextBytes(plaintext1)
    val plaintext2: BinaryData = new Array[Byte](300)
    random.nextBytes(plaintext2)

    val (enc1, ciphertext1) = Encryptor.encrypt(enc, plaintext1)
    val (enc2, ciphertext2) = Encryptor.encrypt(enc1, plaintext2)

    val chunks = (ciphertext1 ++ ciphertext2).grouped(35).toList
    chunks.map(chunk => dec = Decryptor.add(dec, chunk))

    assert(dec.header == None && dec.bodies == Vector(plaintext1, plaintext2))
  }

  test("decryption of several messages in a single chunk") {
    val key: BinaryData = Crypto.sha256(Hash.Zeroes)
    val random = new scala.util.Random()
    val enc = Encryptor(key, 0)
    val dec = Decryptor(key, 0)

    val plaintext1: BinaryData = new Array[Byte](200)
    random.nextBytes(plaintext1)
    val plaintext2: BinaryData = new Array[Byte](300)
    random.nextBytes(plaintext2)

    val (enc1, ciphertext1) = Encryptor.encrypt(enc, plaintext1)
    val (enc2, ciphertext2) = Encryptor.encrypt(enc1, plaintext2)

    val dec1 = Decryptor.add(dec, ciphertext1 ++ ciphertext2)

    assert(dec1.header == None && dec1.bodies == Vector(plaintext1, plaintext2))
  }

  test("concurrency tests") {
    val system = ActorSystem("mySystem")

    val k1: BinaryData = Crypto.sha256(Hash.One)
    val k0: BinaryData = Crypto.sha256(Hash.Zeroes)
    val pipe = system.actorOf(Props[Pipe], "pipe")
    val latch = new CountDownLatch(100)
    val a = system.actorOf(Props(classOf[Ping], pipe, k0, k1, latch), "a")
    Thread.sleep(7)
    val b = system.actorOf(Props(classOf[Ping], pipe, k1, k0, latch), "b")
    pipe ! (a, b)

    latch.await(5, TimeUnit.SECONDS)
    system.shutdown()
  }
}
