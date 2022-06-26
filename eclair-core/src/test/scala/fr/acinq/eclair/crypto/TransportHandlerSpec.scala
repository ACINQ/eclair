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

import akka.actor.{Actor, ActorLogging, ActorRef, OneForOneStrategy, Props, Stash, SupervisorStrategy, Terminated}
import akka.io.Tcp
import akka.testkit.{TestActorRef, TestFSMRef, TestProbe}
import fr.acinq.eclair.TestKitBaseClass
import fr.acinq.eclair.crypto.Noise.{Chacha20Poly1305CipherFunctions, CipherState}
import fr.acinq.eclair.crypto.TransportHandler.{Encryptor, ExtendedCipherState, Listener}
import fr.acinq.eclair.wire.protocol.CommonCodecs
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.Codec
import scodec.bits._
import scodec.codecs._

import java.nio.charset.Charset
import scala.annotation.tailrec
import scala.concurrent.duration._

class TransportHandlerSpec extends TestKitBaseClass with AnyFunSuiteLike with BeforeAndAfterAll {

  import TransportHandlerSpec._

  object Initiator {
    val s = Noise.Secp256k1DHFunctions.generateKeyPair(hex"1111111111111111111111111111111111111111111111111111111111111111")
  }

  object Responder {
    val s = Noise.Secp256k1DHFunctions.generateKeyPair(hex"2121212121212121212121212121212121212121212121212121212121212121")
  }

  test("successful handshake") {
    val pipe = system.actorOf(Props[MyPipe]())
    val probe1 = TestProbe()
    val probe2 = TestProbe()
    val initiator = TestFSMRef(new TransportHandler(Initiator.s, Some(Responder.s.pub), pipe, CommonCodecs.varsizebinarydata))
    val responder = TestFSMRef(new TransportHandler(Responder.s, None, pipe, CommonCodecs.varsizebinarydata))
    pipe ! (initiator, responder)

    awaitCond(initiator.stateName == TransportHandler.WaitingForListener)
    awaitCond(responder.stateName == TransportHandler.WaitingForListener)

    initiator ! Listener(probe1.ref)
    responder ! Listener(probe2.ref)

    awaitCond(initiator.stateName == TransportHandler.Normal)
    awaitCond(responder.stateName == TransportHandler.Normal)

    initiator.tell(ByteVector("hello".getBytes), probe1.ref)
    probe2.expectMsg(ByteVector("hello".getBytes))

    responder.tell(ByteVector("bonjour".getBytes), probe2.ref)
    probe1.expectMsg(ByteVector("bonjour".getBytes))

    probe1.watch(pipe)
    initiator.stop()
    responder.stop()
    system.stop(pipe)
    probe1.expectTerminated(pipe)
  }

  test("successful handshake with custom serializer") {
    case class MyMessage(payload: String)
    val mycodec: Codec[MyMessage] = ("payload" | scodec.codecs.string32L(Charset.defaultCharset())).as[MyMessage]
    val pipe = system.actorOf(Props[MyPipe]())
    val probe1 = TestProbe()
    val probe2 = TestProbe()
    val initiator = TestFSMRef(new TransportHandler(Initiator.s, Some(Responder.s.pub), pipe, mycodec))
    val responder = TestFSMRef(new TransportHandler(Responder.s, None, pipe, mycodec))
    pipe ! (initiator, responder)

    awaitCond(initiator.stateName == TransportHandler.WaitingForListener)
    awaitCond(responder.stateName == TransportHandler.WaitingForListener)

    initiator ! Listener(probe1.ref)
    responder ! Listener(probe2.ref)

    awaitCond(initiator.stateName == TransportHandler.Normal)
    awaitCond(responder.stateName == TransportHandler.Normal)

    initiator.tell(MyMessage("hello"), probe1.ref)
    probe2.expectMsg(MyMessage("hello"))

    responder.tell(MyMessage("bonjour"), probe2.ref)
    probe1.expectMsg(MyMessage("bonjour"))

    probe1.watch(pipe)
    initiator.stop()
    responder.stop()
    system.stop(pipe)
    probe1.expectTerminated(pipe)
  }

  test("handle unknown messages") {
    sealed trait Message
    case object Msg1 extends Message
    case object Msg2 extends Message

    val codec1: Codec[Message] = discriminated[Message].by(uint8)
      .typecase(1, provide(Msg1))

    val codec12: Codec[Message] = discriminated[Message].by(uint8)
      .typecase(1, provide(Msg1))
      .typecase(2, provide(Msg2))

    val pipe = system.actorOf(Props[MyPipePull]())
    val probe1 = TestProbe()
    val probe2 = TestProbe()
    val initiator = TestFSMRef(new TransportHandler(Initiator.s, Some(Responder.s.pub), pipe, codec1))
    val responder = TestFSMRef(new TransportHandler(Responder.s, None, pipe, codec12))
    pipe ! (initiator, responder)

    awaitCond(initiator.stateName == TransportHandler.WaitingForListener)
    awaitCond(responder.stateName == TransportHandler.WaitingForListener)

    initiator ! Listener(probe1.ref)
    responder ! Listener(probe2.ref)

    awaitCond(initiator.stateName == TransportHandler.Normal)
    awaitCond(responder.stateName == TransportHandler.Normal)

    responder ! Msg1
    probe1.expectMsg(Msg1)
    probe1.reply(TransportHandler.ReadAck(Msg1))

    responder ! Msg2
    probe1.expectNoMessage(2 seconds) // unknown message

    responder ! Msg1
    probe1.expectMsg(Msg1)
    probe1.reply(TransportHandler.ReadAck(Msg1))

    probe1.watch(pipe)
    initiator.stop()
    responder.stop()
    system.stop(pipe)
    probe1.expectTerminated(pipe)
  }

  test("handle messages split in chunks") {
    val pipe = system.actorOf(Props[MyPipeSplitter]())
    val probe1 = TestProbe()
    val probe2 = TestProbe()
    val initiator = TestFSMRef(new TransportHandler(Initiator.s, Some(Responder.s.pub), pipe, CommonCodecs.varsizebinarydata))
    val responder = TestFSMRef(new TransportHandler(Responder.s, None, pipe, CommonCodecs.varsizebinarydata))
    pipe ! (initiator, responder)

    awaitCond(initiator.stateName == TransportHandler.WaitingForListener)
    awaitCond(responder.stateName == TransportHandler.WaitingForListener)

    initiator ! Listener(probe1.ref)
    responder ! Listener(probe2.ref)

    awaitCond(initiator.stateName == TransportHandler.Normal)
    awaitCond(responder.stateName == TransportHandler.Normal)

    initiator.tell(ByteVector("hello".getBytes), probe1.ref)
    probe2.expectMsg(ByteVector("hello".getBytes))

    responder.tell(ByteVector("bonjour".getBytes), probe2.ref)
    probe1.expectMsg(ByteVector("bonjour".getBytes))

    probe1.watch(pipe)
    initiator.stop()
    responder.stop()
    system.stop(pipe)
    probe1.expectTerminated(pipe)
  }

  test("failed handshake") {
    val pipe = system.actorOf(Props[MyPipe]())
    val probe1 = TestProbe()
    val supervisor = TestActorRef(Props(new MySupervisor()))
    val initiator = TestFSMRef(new TransportHandler(Initiator.s, Some(Initiator.s.pub), pipe, CommonCodecs.varsizebinarydata), supervisor, "ini")
    val responder = TestFSMRef(new TransportHandler(Responder.s, None, pipe, CommonCodecs.varsizebinarydata), supervisor, "res")
    probe1.watch(responder)
    pipe ! (initiator, responder)

    probe1.expectTerminated(responder, 3 seconds)
  }

  test("key rotation") {

    /*
    name: transport-message test
    ck=0x919219dbb2920afa8db80f9a51787a840bcf111ed8d588caf9ab4be716e42b01
    sk=0x969ab31b4d288cedf6218839b27a3e2140827047f2c0f01bf5c04435d43511a9
    rk=0xbb9020b8965f4df047e07f955f3c4b88418984aadc5cdb35096b9ea8fa5c3442
    # encrypt l: cleartext=0x0005, AD=NULL, sn=0x000000000000000000000000, sk=0x969ab31b4d288cedf6218839b27a3e2140827047f2c0f01bf5c04435d43511a9 => 0xcf2b30ddf0cf3f80e7c35a6e6730b59fe802
    # encrypt m: cleartext=0x68656c6c6f, AD=NULL, sn=0x000000000100000000000000, sk=0x969ab31b4d288cedf6218839b27a3e2140827047f2c0f01bf5c04435d43511a9 => 0x473180f396d88a8fb0db8cbcf25d2f214cf9ea1d95
    output 0: 0xcf2b30ddf0cf3f80e7c35a6e6730b59fe802473180f396d88a8fb0db8cbcf25d2f214cf9ea1d95
    # encrypt l: cleartext=0x0005, AD=NULL, sn=0x000000000200000000000000, sk=0x969ab31b4d288cedf6218839b27a3e2140827047f2c0f01bf5c04435d43511a9 => 0x72887022101f0b6753e0c7de21657d35a4cb
    # encrypt m: cleartext=0x68656c6c6f, AD=NULL, sn=0x000000000300000000000000, sk=0x969ab31b4d288cedf6218839b27a3e2140827047f2c0f01bf5c04435d43511a9 => 0x2a1f5cde2650528bbc8f837d0f0d7ad833b1a256a1
    output 1: 0x72887022101f0b6753e0c7de21657d35a4cb2a1f5cde2650528bbc8f837d0f0d7ad833b1a256a1
    # 0xcc2c6e467efc8067720c2d09c139d1f77731893aad1defa14f9bf3c48d3f1d31, 0x3fbdc101abd1132ca3a0ae34a669d8d9ba69a587e0bb4ddd59524541cf4813d8 = HKDF(0x919219dbb2920afa8db80f9a51787a840bcf111ed8d588caf9ab4be716e42b01, 0x969ab31b4d288cedf6218839b27a3e2140827047f2c0f01bf5c04435d43511a9)
    # 0xcc2c6e467efc8067720c2d09c139d1f77731893aad1defa14f9bf3c48d3f1d31, 0x3fbdc101abd1132ca3a0ae34a669d8d9ba69a587e0bb4ddd59524541cf4813d8 = HKDF(0x919219dbb2920afa8db80f9a51787a840bcf111ed8d588caf9ab4be716e42b01, 0x969ab31b4d288cedf6218839b27a3e2140827047f2c0f01bf5c04435d43511a9)
    output 500: 0x178cb9d7387190fa34db9c2d50027d21793c9bc2d40b1e14dcf30ebeeeb220f48364f7a4c68bf8
    output 501: 0x1b186c57d44eb6de4c057c49940d79bb838a145cb528d6e8fd26dbe50a60ca2c104b56b60e45bd
    # 0x728366ed68565dc17cf6dd97330a859a6a56e87e2beef3bd828a4c4a54d8df06, 0x9e0477f9850dca41e42db0e4d154e3a098e5a000d995e421849fcd5df27882bd = HKDF(0xcc2c6e467efc8067720c2d09c139d1f77731893aad1defa14f9bf3c48d3f1d31, 0x3fbdc101abd1132ca3a0ae34a669d8d9ba69a587e0bb4ddd59524541cf4813d8)
    # 0x728366ed68565dc17cf6dd97330a859a6a56e87e2beef3bd828a4c4a54d8df06, 0x9e0477f9850dca41e42db0e4d154e3a098e5a000d995e421849fcd5df27882bd = HKDF(0xcc2c6e467efc8067720c2d09c139d1f77731893aad1defa14f9bf3c48d3f1d31, 0x3fbdc101abd1132ca3a0ae34a669d8d9ba69a587e0bb4ddd59524541cf4813d8)
    output 1000: 0x4a2f3cc3b5e78ddb83dcb426d9863d9d9a723b0337c89dd0b005d89f8d3c05c52b76b29b740f09
    output 1001: 0x2ecd8c8a5629d0d02ab457a0fdd0f7b90a192cd46be5ecb6ca570bfc5e268338b1a16cf4ef2d36
    */
    val ck = hex"919219dbb2920afa8db80f9a51787a840bcf111ed8d588caf9ab4be716e42b01"
    val sk = hex"969ab31b4d288cedf6218839b27a3e2140827047f2c0f01bf5c04435d43511a9"
    val rk = hex"bb9020b8965f4df047e07f955f3c4b88418984aadc5cdb35096b9ea8fa5c3442"
    val enc = ExtendedCipherState(CipherState(sk, Chacha20Poly1305CipherFunctions), ck)
    val dec = ExtendedCipherState(CipherState(rk, Chacha20Poly1305CipherFunctions), ck)

    @tailrec
    def loop(cs: Encryptor, count: Int, acc: Vector[ByteVector] = Vector.empty[ByteVector]): Vector[ByteVector] = {
      if (count == 0) acc else {
        val (cs1, ciphertext) = cs.encrypt(ByteVector.view("hello".getBytes()))
        loop(cs1, count - 1, acc :+ ciphertext)
      }
    }

    val ciphertexts = loop(Encryptor(enc), 1002)
    assert(ciphertexts(0) == hex"cf2b30ddf0cf3f80e7c35a6e6730b59fe802473180f396d88a8fb0db8cbcf25d2f214cf9ea1d95")
    assert(ciphertexts(1) == hex"72887022101f0b6753e0c7de21657d35a4cb2a1f5cde2650528bbc8f837d0f0d7ad833b1a256a1")
    assert(ciphertexts(500) == hex"178cb9d7387190fa34db9c2d50027d21793c9bc2d40b1e14dcf30ebeeeb220f48364f7a4c68bf8")
    assert(ciphertexts(501) == hex"1b186c57d44eb6de4c057c49940d79bb838a145cb528d6e8fd26dbe50a60ca2c104b56b60e45bd")
    assert(ciphertexts(1000) == hex"4a2f3cc3b5e78ddb83dcb426d9863d9d9a723b0337c89dd0b005d89f8d3c05c52b76b29b740f09")
    assert(ciphertexts(1001) == hex"2ecd8c8a5629d0d02ab457a0fdd0f7b90a192cd46be5ecb6ca570bfc5e268338b1a16cf4ef2d36")
  }
}

object TransportHandlerSpec {

  class MyPipe extends Actor with Stash with ActorLogging {

    def receive = {
      case (a: ActorRef, b: ActorRef) =>
        unstashAll()
        context watch a
        context watch b
        context become ready(a, b)

      case msg => stash()
    }

    def ready(a: ActorRef, b: ActorRef): Receive = {
      case Tcp.Write(data, ack) if sender().path == a.path =>
        b forward Tcp.Received(data)
        if (ack != Tcp.NoAck) sender() ! ack
      case Tcp.Write(data, ack) if sender().path == b.path =>
        a forward Tcp.Received(data)
        if (ack != Tcp.NoAck) sender() ! ack
      case Terminated(actor) if actor == a || actor == b => context stop self
    }
  }

  class MyPipeSplitter extends Actor with Stash {

    def receive = {
      case (a: ActorRef, b: ActorRef) =>
        unstashAll()
        context watch a
        context watch b
        context become ready(a, b)

      case msg => stash()
    }

    def ready(a: ActorRef, b: ActorRef): Receive = {
      case Tcp.Write(data, ack) if sender().path == a.path =>
        val (chunk1, chunk2) = data.splitAt(data.length / 2)
        b forward Tcp.Received(chunk1)
        if (ack != Tcp.NoAck) sender() ! ack
        b forward Tcp.Received(chunk2)
        if (ack != Tcp.NoAck) sender() ! ack
      case Tcp.Write(data, ack) if sender().path == b.path =>
        val (chunk1, chunk2) = data.splitAt(data.length / 2)
        a forward Tcp.Received(chunk1)
        if (ack != Tcp.NoAck) sender() ! ack
        a forward Tcp.Received(chunk2)
        if (ack != Tcp.NoAck) sender() ! ack
      case Terminated(actor) if actor == a || actor == b => context stop self
    }
  }

  class MyPipePull extends Actor with Stash {

    def receive = {
      case (a: ActorRef, b: ActorRef) =>
        unstashAll()
        context watch a
        context watch b
        context become ready(a, b, aResume = true, bResume = true)

      case msg => stash()
    }

    def ready(a: ActorRef, b: ActorRef, aResume: Boolean, bResume: Boolean): Receive = {
      case Tcp.Write(data, ack) if sender().path == a.path =>
        if (bResume) {
          b forward Tcp.Received(data)
          if (ack != Tcp.NoAck) sender() ! ack
          context become ready(a, b, aResume, bResume = false)
        } else stash()
      case Tcp.ResumeReading if sender().path == b.path =>
        unstashAll()
        context become ready(a, b, aResume, bResume = true)
      case Tcp.Write(data, ack) if sender().path == b.path =>
        if (aResume) {
          a forward Tcp.Received(data)
          if (ack != Tcp.NoAck) sender() ! ack
          context become ready(a, b, aResume = false, bResume)
        } else stash()
      case Tcp.ResumeReading if sender().path == a.path =>
        unstashAll()
        context become ready(a, b, aResume = true, bResume)
      case Terminated(actor) if actor == a || actor == b => context stop self
    }
  }

  // custom supervisor that will stop an actor if it fails
  class MySupervisor extends Actor {
    override def supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
      case _ => SupervisorStrategy.stop
    }

    def receive = {
      case _ => ()
    }
  }

}
