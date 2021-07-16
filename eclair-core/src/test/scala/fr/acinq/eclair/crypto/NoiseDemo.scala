/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.crypto

import akka.actor.{Actor, ActorRef, ActorSystem, Props, Stash}
import fr.acinq.eclair.Pipe
import fr.acinq.eclair.crypto.Noise.{CipherState, KeyPair}
import scodec.bits.{ByteVector, _}

/**
  * Created by fabrice on 12/12/16.
  */
object NoiseDemo extends App {
  implicit val system = ActorSystem("test")

  class NoiseHandler(keyPair: KeyPair, rs: Option[ByteVector], them: ActorRef, isWriter: Boolean, listenerFactory: => ActorRef) extends Actor with Stash {
    // initiator must know pubkey (i.e long-term ID) of responder
    if (isWriter) require(!rs.isEmpty)

    def receive = ???

    val handshakeState = if (isWriter) {
      val state = Noise.HandshakeState.initializeWriter(
        Noise.handshakePatternXK,
        ByteVector.view("lightning".getBytes()),
        keyPair, KeyPair(ByteVector.empty, ByteVector.empty), rs.get, ByteVector.empty,
        Noise.Secp256k1DHFunctions, Noise.Chacha20Poly1305CipherFunctions, Noise.SHA256HashFunctions)
      val (state1, message, None) = state.write(ByteVector.empty)
      them ! message
      state1
    } else {
      val state = Noise.HandshakeState.initializeReader(
        Noise.handshakePatternXK,
        ByteVector.view("lightning".getBytes()),
        keyPair, KeyPair(ByteVector.empty, ByteVector.empty), ByteVector.empty, ByteVector.empty,
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
      case message: ByteVector =>
        state.read(message) match {
          case (_, _, Some((cs0, cs1, _))) if isWriter => {
            toNormal(cs0, cs1)
          }
          case (_, _, Some((cs0, cs1, _))) => {
            toNormal(cs1, cs0)
          }
          case (writer, _, None) =>
            val (reader1, output, cipherstates) = writer.write(ByteVector.empty)
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
      case plaintext: ByteVector if sender() == listener =>
        val (enc1, ciphertext) = enc.encryptWithAd(ByteVector.empty, plaintext)
        them ! ciphertext
        context become normal(enc1, dec, listener)
      case ciphertext: ByteVector if sender() == them =>
        val (dec1, plaintext) = dec.decryptWithAd(ByteVector.empty, ciphertext)
        listener ! plaintext
        context become normal(enc, dec1, listener)
    }
  }

  class MyActor extends Actor {
    var count = 0

    def receive = {
      case message: ByteVector =>
        sender() ! ByteVector("response to ".getBytes()) ++ message
        count = count + 1
        if (count == 5) context stop self
    }
  }

  object Initiator {
    val s = Noise.Secp256k1DHFunctions.generateKeyPair(hex"1111111111111111111111111111111111111111111111111111111111111111")
  }

  object Responder {
    val s = Noise.Secp256k1DHFunctions.generateKeyPair(hex"2121212121212121212121212121212121212121212121212121212121212121")
  }

  val pipe = system.actorOf(Props[Pipe](), "pipe")
  val foo = system.actorOf(Props[MyActor](), "foo")
  val fooHandler = system.actorOf(Props(new NoiseHandler(Initiator.s, Some(Responder.s.pub), pipe, true, foo)), "foohandler")
  val bar = system.actorOf(Props[MyActor](), "bar")
  val barHandler = system.actorOf(Props(new NoiseHandler(Responder.s, None, pipe, false, bar)), "barhandler")
  pipe ! (fooHandler, barHandler)

  bar.tell(ByteVector("hello".getBytes()), foo)
}
