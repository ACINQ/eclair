/*
 * Copyright 2018 ACINQ SAS
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

import java.util.concurrent.CountDownLatch

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.payment.PaymentLifecycle.ReceivePayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire._
import grizzled.slf4j.Logging
import org.scalatest.Tag

import scala.collection.immutable.Nil
import scala.concurrent.duration._
import scala.util.Random

/**
  * Created by PM on 05/07/2016.
  */

class FuzzySpec extends TestkitBaseClass with StateTestsHelperMethods with Logging {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel], pipe: ActorRef, relayerA: ActorRef, relayerB: ActorRef, paymentHandlerA: ActorRef, paymentHandlerB: ActorRef)

  override def withFixture(test: OneArgTest) = {
    val fuzzy = test.tags.contains("fuzzy")
    val pipe = system.actorOf(Props(new FuzzyPipe(fuzzy)))
    val alice2blockchain = TestProbe()
    val bob2blockchain = TestProbe()
    val paymentHandlerA = system.actorOf(Props(new LocalPaymentHandler(Alice.nodeParams)))
    val paymentHandlerB = system.actorOf(Props(new LocalPaymentHandler(Bob.nodeParams)))
    val registerA = TestProbe()
    val registerB = TestProbe()
    val relayerA = system.actorOf(Relayer.props(Alice.nodeParams, registerA.ref, paymentHandlerA))
    val relayerB = system.actorOf(Relayer.props(Bob.nodeParams, registerB.ref, paymentHandlerB))
    val router = TestProbe()
    val wallet = new TestWallet
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(Alice.nodeParams, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, router.ref, relayerA))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(Bob.nodeParams, wallet, Alice.nodeParams.nodeId, bob2blockchain.ref, router.ref, relayerB))
    within(30 seconds) {
      val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
      val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
      relayerA ! alice
      relayerB ! bob
      // no announcements
      alice ! INPUT_INIT_FUNDER("00" * 32, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Alice.channelParams, pipe, bobInit, channelFlags = 0x00.toByte)
      bob ! INPUT_INIT_FUNDEE("00" * 32, Bob.channelParams, pipe, aliceInit)
      pipe ! (alice, bob)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
      alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      alice2blockchain.expectMsgType[WatchLost]
      bob2blockchain.expectMsgType[WatchLost]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
    }
    withFixture(test.toNoArgTest(FixtureParam(alice, bob, pipe, relayerA, relayerB, paymentHandlerA, paymentHandlerB)))
  }

  class SenderActor(channel: TestFSMRef[State, Data, Channel], paymentHandler: ActorRef, latch: CountDownLatch) extends Actor with ActorLogging {

    // we don't want to be below htlcMinimumMsat
    val requiredAmount = 1000000

    def buildCmdAdd(paymentHash: BinaryData, dest: PublicKey) = {
      // allow overpaying (no more than 2 times the required amount)
      val amount = requiredAmount + Random.nextInt(requiredAmount)
      val expiry = Globals.blockCount.get().toInt + Channel.MIN_CLTV_EXPIRY + 1
      PaymentLifecycle.buildCommand(amount, expiry, paymentHash, Hop(null, dest, null) :: Nil)._1
    }

    def initiatePayment(stopping: Boolean) =
      if (stopping) {
        context stop self
      } else {
        paymentHandler ! ReceivePayment(Some(MilliSatoshi(requiredAmount)), "One coffee")
        context become waitingForPaymentRequest
      }

    initiatePayment(false)

    override def receive: Receive = ???

    def waitingForPaymentRequest: Receive = {
      case req: PaymentRequest =>
        channel ! buildCmdAdd(req.paymentHash, req.nodeId)
        context become waitingForFulfill(false)
    }

    def waitingForFulfill(stopping: Boolean): Receive = {
      case u: UpdateFulfillHtlc =>
        log.info(s"successfully sent htlc #${u.id}")
        latch.countDown()
        initiatePayment(stopping)
      case u: UpdateFailHtlc =>
        log.warning(s"htlc failed: ${u.id}")
        initiatePayment(stopping)
      case Status.Failure(t) =>
        log.error(s"htlc error: ${t.getMessage}")
        initiatePayment(stopping)
      case 'stop =>
        log.warning(s"stopping...")
        context become waitingForFulfill(true)
    }

  }

  test("fuzzy test with only one party sending HTLCs", Tag("fuzzy")) { f =>
    import f._
    val latch = new CountDownLatch(100)
    system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch)))
    system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch)))
    awaitCond(latch.getCount == 0, max = 2 minutes)
    assert(alice.stateName == NORMAL || alice.stateName == OFFLINE)
    assert(bob.stateName == NORMAL || alice.stateName == OFFLINE)
  }

  test("fuzzy test with both parties sending HTLCs", Tag("fuzzy")) { f =>
    import f._
    val latch = new CountDownLatch(100)
    system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch)))
    system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch)))
    system.actorOf(Props(new SenderActor(bob, paymentHandlerA, latch)))
    system.actorOf(Props(new SenderActor(bob, paymentHandlerA, latch)))
    awaitCond(latch.getCount == 0, max = 2 minutes)
    assert(alice.stateName == NORMAL || alice.stateName == OFFLINE)
    assert(bob.stateName == NORMAL || alice.stateName == OFFLINE)
  }

  test("one party sends lots of htlcs send shutdown") { f =>
    import f._
    val latch = new CountDownLatch(20)
    val senders = system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch))) ::
      system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch))) ::
      system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch))) :: Nil
    awaitCond(latch.getCount == 0, max = 2 minutes)
    val sender = TestProbe()
    awaitCond({
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsgAnyClassOf(classOf[String], classOf[Status.Failure]) == "ok"
    }, max = 30 seconds)
    senders.foreach(_ ! 'stop)
    awaitCond(alice.stateName == CLOSING)
    awaitCond(alice.stateName == CLOSING)
  }

  test("both parties send lots of htlcs send shutdown") { f =>
    import f._
    val latch = new CountDownLatch(30)
    val senders = system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch))) ::
      system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch))) ::
      system.actorOf(Props(new SenderActor(bob, paymentHandlerA, latch))) ::
      system.actorOf(Props(new SenderActor(bob, paymentHandlerA, latch))) :: Nil
    awaitCond(latch.getCount == 0, max = 2 minutes)
    val sender = TestProbe()
    awaitCond({
      sender.send(alice, CMD_CLOSE(None))
      val resa = sender.expectMsgAnyClassOf(classOf[String], classOf[Status.Failure])
      sender.send(bob, CMD_CLOSE(None))
      val resb = sender.expectMsgAnyClassOf(classOf[String], classOf[Status.Failure])
      // we only need that one of them succeeds
      resa == "ok" || resb == "ok"
    }, max = 30 seconds)
    senders.foreach(_ ! 'stop)
    awaitCond(alice.stateName == CLOSING)
    awaitCond(alice.stateName == CLOSING)
  }

}
