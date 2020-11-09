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

package fr.acinq.eclair.channel

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.wire.{Init, UpdateAddHtlc}
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._
import scala.util.Random

class ThroughputSpec extends AnyFunSuite {
  ignore("throughput") {
    implicit val system = ActorSystem("test")
    val pipe = system.actorOf(Props[Pipe], "pipe")
    val blockCount = new AtomicLong()
    val blockchain = system.actorOf(ZmqWatcher.props(randomBytes32, blockCount, new TestBitcoinClient()), "blockchain")
    val paymentHandler = system.actorOf(Props(new Actor() {
      val random = new Random()

      context.become(run(Map()))

      override def receive: Receive = ???

      def run(h2r: Map[ByteVector32, ByteVector32]): Receive = {
        case ('add, tgt: ActorRef) =>
          val r = randomBytes32
          val h = Crypto.sha256(r)
          tgt ! CMD_ADD_HTLC(self, 1 msat, h, CltvExpiry(1), TestConstants.emptyOnionPacket, Origin.LocalHot(self, UUID.randomUUID()))
          context.become(run(h2r + (h -> r)))

        case ('sig, tgt: ActorRef) => tgt ! CMD_SIGN()

        case htlc: UpdateAddHtlc if h2r.contains(htlc.paymentHash) =>
          val r = h2r(htlc.paymentHash)
          sender ! CMD_FULFILL_HTLC(htlc.id, r)
          context.become(run(h2r - htlc.paymentHash))
      }
    }), "payment-handler")
    val registerA = TestProbe()
    val registerB = TestProbe()
    val relayerA = system.actorOf(Relayer.props(Alice.nodeParams, TestProbe().ref, registerA.ref, paymentHandler))
    val relayerB = system.actorOf(Relayer.props(Bob.nodeParams, TestProbe().ref, registerB.ref, paymentHandler))
    val wallet = new TestWallet
    val alice = system.actorOf(Channel.props(Alice.nodeParams, wallet, Bob.nodeParams.nodeId, blockchain, relayerA, None), "a")
    val bob = system.actorOf(Channel.props(Bob.nodeParams, wallet, Alice.nodeParams.nodeId, blockchain, relayerB, None), "b")
    val aliceInit = Init(Alice.channelParams.features)
    val bobInit = Init(Bob.channelParams.features)
    alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Alice.channelParams, pipe, bobInit, ChannelFlags.Empty, ChannelVersion.STANDARD)
    bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, Bob.channelParams, pipe, aliceInit, ChannelVersion.STANDARD)

    val latch = new CountDownLatch(2)
    val listener = system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case ChannelStateChanged(_, _, _, _, _, NORMAL, _) => latch.countDown()
      }
    }), "listener")
    system.eventStream.subscribe(listener, classOf[ChannelEvent])

    pipe ! (alice, bob)
    latch.await()

    var i = new AtomicLong(0)
    val random = new Random()

    def msg = random.nextInt(100) % 5 match {
      case 0 | 1 | 2 | 3 => 'add
      case 4 => 'sig
    }

    import scala.concurrent.ExecutionContext.Implicits.global
    system.scheduler.schedule(0 seconds, 50 milliseconds, new Runnable() {
      override def run(): Unit = paymentHandler ! (msg, alice)
    })
    system.scheduler.schedule(5 seconds, 70 milliseconds, new Runnable() {
      override def run(): Unit = paymentHandler ! (msg, bob)
    })

    Thread.sleep(Long.MaxValue)
  }
}
