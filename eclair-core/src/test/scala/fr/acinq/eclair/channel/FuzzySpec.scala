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

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.payment.PaymentLifecycle.ReceivePayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire._
import grizzled.slf4j.Logging
import org.scalatest.{Outcome, Tag}

import scala.collection.immutable.Nil
import scala.concurrent.duration._
import scala.util.Random

/**
  * Created by PM on 05/07/2016.
  */

class FuzzySpec extends TestkitBaseClass with StateTestsHelperMethods with Logging {

  case class FixtureParam(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel], pipe: ActorRef, relayerA: ActorRef, relayerB: ActorRef, paymentHandlerA: ActorRef, paymentHandlerB: ActorRef)

  override def withFixture(test: OneArgTest): Outcome = {
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
      alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, Alice.channelParams, pipe, bobInit, channelFlags = 0x00.toByte, ChannelVersion.STANDARD)
      bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, Bob.channelParams, pipe, aliceInit)
      pipe ! (alice, bob)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
      alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42, fundingTx)
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42, fundingTx)
      alice2blockchain.expectMsgType[WatchLost]
      bob2blockchain.expectMsgType[WatchLost]
      awaitCond(alice.stateName == NORMAL, 1 minute)
      awaitCond(bob.stateName == NORMAL, 1 minute)
    }
    withFixture(test.toNoArgTest(FixtureParam(alice, bob, pipe, relayerA, relayerB, paymentHandlerA, paymentHandlerB)))
  }

  class SenderActor(sendChannel: TestFSMRef[State, Data, Channel], paymentHandler: ActorRef, latch: CountDownLatch, count: Int) extends Actor with ActorLogging {

    // we don't want to be below htlcMinimumMsat
    val requiredAmount = 1000000 msat

    def buildCmdAdd(paymentHash: ByteVector32, dest: PublicKey) = {
      // allow overpaying (no more than 2 times the required amount)
      val amount = requiredAmount + Random.nextInt(requiredAmount.toLong.toInt).msat
      val expiry = (Channel.MIN_CLTV_EXPIRY_DELTA + 1).toCltvExpiry(blockHeight = 400000)
      PaymentLifecycle.buildCommand(UUID.randomUUID(), paymentHash, Hop(null, dest, null) :: Nil, FinalLegacyPayload(amount, expiry))._1
    }

    def initiatePaymentOrStop(remaining: Int): Unit =
      if (remaining > 0) {
        paymentHandler ! ReceivePayment(Some(requiredAmount), "One coffee")
        context become {
          case req: PaymentRequest =>
            sendChannel ! buildCmdAdd(req.paymentHash, req.nodeId)
            context become {
              case u: UpdateFulfillHtlc =>
                log.info(s"successfully sent htlc #${u.id}")
                initiatePaymentOrStop(remaining - 1)
              case u: UpdateFailHtlc =>
                log.warning(s"htlc failed: ${u.id}")
                initiatePaymentOrStop(remaining - 1)
              case Status.Failure(t) =>
                log.error(s"htlc error: ${t.getMessage}")
                initiatePaymentOrStop(remaining - 1)
            }
        }
      } else {
        context stop self
        latch.countDown()
      }

    initiatePaymentOrStop(count)

    override def receive: Receive = ???

  }

  test("fuzzy test with only one party sending HTLCs", Tag("fuzzy")) { f =>
    import f._
    val senders = 2
    val totalMessages = 100
    val latch = new CountDownLatch(senders)
    for (_ <- 0 until senders) system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch, totalMessages / senders)))
    awaitCond(latch.getCount == 0, max = 2 minutes)
    assert(List(NORMAL, OFFLINE, SYNCING).contains(alice.stateName))
    assert(List(NORMAL, OFFLINE, SYNCING).contains(bob.stateName))
  }

  test("fuzzy test with both parties sending HTLCs", Tag("fuzzy")) { f =>
    import f._
    val senders = 2
    val totalMessages = 100
    val latch = new CountDownLatch(2 * senders)
    for (_ <- 0 until senders) system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch, totalMessages / senders)))
    for (_ <- 0 until senders) system.actorOf(Props(new SenderActor(bob, paymentHandlerA, latch, totalMessages / senders)))
    awaitCond(latch.getCount == 0, max = 2 minutes)
    assert(List(NORMAL, OFFLINE, SYNCING).contains(alice.stateName))
    assert(List(NORMAL, OFFLINE, SYNCING).contains(bob.stateName))
  }

  test("one party sends lots of htlcs then shutdown") { f =>
    import f._
    val senders = 2
    val totalMessages = 20
    val latch = new CountDownLatch(senders)
    for (_ <- 0 until senders) system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch, totalMessages / senders)))
    awaitCond(latch.getCount == 0, max = 2 minutes)
    val sender = TestProbe()
    awaitCond({
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsgAnyClassOf(classOf[String], classOf[Status.Failure]) == "ok"
    }, max = 30 seconds)
    awaitCond(alice.stateName == CLOSING, max = 3 minutes, interval = 1 second)
    awaitCond(bob.stateName == CLOSING, max = 3 minutes, interval = 1 second)
  }

  test("both parties send lots of htlcs then shutdown") { f =>
    import f._
    val senders = 2
    val totalMessages = 100
    val latch = new CountDownLatch(2 * senders)
    for (_ <- 0 until senders) system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch, totalMessages / senders)))
    for (_ <- 0 until senders) system.actorOf(Props(new SenderActor(bob, paymentHandlerA, latch, totalMessages / senders)))
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
    awaitCond(alice.stateName == CLOSING, max = 3 minutes, interval = 1 second)
    awaitCond(bob.stateName == CLOSING, max = 3 minutes, interval = 1 second)
  }

}
