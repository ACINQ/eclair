package fr.acinq.eclair.crypto

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Stash}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.Pipe
import fr.acinq.eclair.crypto.Noise.{CipherState, KeyPair}

/**
  * Created by fabrice on 12/12/16.
  */
object NoiseDemo extends App {
  implicit val system = ActorSystem("mySystem")

  class NoiseHandler(keyPair: KeyPair, rs: Option[BinaryData], them: ActorRef, isWriter: Boolean, listenerFactory: => ActorRef) extends Actor with Stash {
    // initiator must know pubkey (i.e long-term ID) of responder
    if (isWriter) require(!rs.isEmpty)

    def receive = ???

    val handshakeState = if (isWriter) {
      val state = Noise.HandshakeState.initializeWriter(
        Noise.handshakePatternXK,
        "lightning".getBytes(),
        keyPair, KeyPair(BinaryData.empty, BinaryData.empty), rs.get, BinaryData.empty,
        Noise.Secp256k1DHFunctions, Noise.Chacha20Poly1305CipherFunctions, Noise.SHA256HashFunctions)
      val (state1, message, None) = state.write(BinaryData.empty)
      them ! message
      state1
    } else {
      val state = Noise.HandshakeState.initializeReader(
        Noise.handshakePatternXK,
        "lightning".getBytes(),
        keyPair, KeyPair(BinaryData.empty, BinaryData.empty), BinaryData.empty, BinaryData.empty,
        Noise.Secp256k1DHFunctions, Noise.Chacha20Poly1305CipherFunctions, Noise.SHA256HashFunctions)
      state
    }

    context become handshake(handshakeState)

    def toNormal(enc: CipherState, dec: CipherState) = {
      unstashAll()
      val listener = listenerFactory
      context become normal(enc, dec, listener)
    }

    def handshake(state: Noise.HandshakeStateReader): Receive = {
      case message: BinaryData =>
        state.read(message) match {
          case (_, _, Some((cs0, cs1, _))) if isWriter => {
            toNormal(cs0, cs1)
          }
          case (_, _, Some((cs0, cs1, _))) => {
            toNormal(cs1, cs0)
          }
          case (writer, _, None) =>
            val (reader1, output, cipherstates) = writer.write(BinaryData.empty)
            them ! output
            cipherstates match {
              case None => context become handshake(reader1)
              case Some((cs0, cs1, _)) if isWriter => {
                toNormal(cs0, cs1)
              }
              case Some((cs0, cs1, _)) => {
                toNormal(cs1, cs0)
              }
            }
        }
    }

    def normal(enc: Noise.CipherState, dec: Noise.CipherState, listener: ActorRef): Receive = {
      case plaintext: BinaryData if sender == listener =>
        val (enc1, ciphertext) = enc.encryptWithAd(BinaryData.empty, plaintext)
        them ! ciphertext
        context become normal(enc1, dec, listener)
      case ciphertext: BinaryData if sender == them =>
        val (dec1, plaintext) = dec.decryptWithAd(BinaryData.empty, ciphertext)
        listener ! plaintext
        context become normal(enc, dec1, listener)
    }
  }

  class MyActor extends Actor {
    var count = 0

    def receive = {
      case message: BinaryData =>
        println(s"received ${new String(message)}")
        sender ! BinaryData("response to ".getBytes() ++ message)
        count = count + 1
        if (count == 5) context stop self
    }
  }

  object Initiator {
    val s = Noise.Secp256k1DHFunctions.generateKeyPair("1111111111111111111111111111111111111111111111111111111111111111")
  }

  object Responder {
    val s = Noise.Secp256k1DHFunctions.generateKeyPair("2121212121212121212121212121212121212121212121212121212121212121")
  }

  val pipe = system.actorOf(Props[Pipe], "pipe")
  val foo = system.actorOf(Props[MyActor], "foo")
  val fooHandler = system.actorOf(Props(new NoiseHandler(Initiator.s, Some(Responder.s.pub), pipe, true, foo)), "foohandler")
  val bar = system.actorOf(Props[MyActor], "bar")
  val barHandler = system.actorOf(Props(new NoiseHandler(Responder.s, None, pipe, false, bar)), "barhandler")
  pipe ! (fooHandler, barHandler)

  bar.tell(BinaryData("hello".getBytes()), foo)
}
