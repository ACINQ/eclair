package fr.acinq.eclair.channel.states

import java.util.concurrent.CountDownLatch

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Status}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire._
import grizzled.slf4j.Logging
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.collection.immutable.Nil
import scala.concurrent.duration._
import scala.util.Random

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class FuzzySpec extends TestkitBaseClass with StateTestsHelperMethods with Logging {

  type FixtureParam = Tuple7[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], ActorRef, ActorRef, ActorRef, ActorRef, ActorRef]

  override def withFixture(test: OneArgTest) = {
    val pipe = system.actorOf(Props(new FuzzyPipe()))
    val alice2blockchain = TestProbe()
    val bob2blockchain = TestProbe()
    val paymentHandlerA = system.actorOf(Props(new LocalPaymentHandler(Alice.nodeParams)), name = "payment-handler-a")
    val paymentHandlerB = system.actorOf(Props(new LocalPaymentHandler(Bob.nodeParams)), name = "payment-handler-b")
    val relayerA = system.actorOf(Relayer.props(Alice.nodeParams.privateKey, paymentHandlerA), "relayer-a")
    val relayerB = system.actorOf(Relayer.props(Bob.nodeParams.privateKey, paymentHandlerB), "relayer-b")
    val router = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(Alice.nodeParams, Bob.id, alice2blockchain.ref, router.ref, relayerA))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(Bob.nodeParams, Alice.id, bob2blockchain.ref, router.ref, relayerB))
    within(30 seconds) {
      val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
      val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
      relayerA ! alice
      relayerB ! bob
      // no announcements
      alice ! INPUT_INIT_FUNDER("00" * 32, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, Alice.channelParams, pipe, bobInit, channelFlags = 0x00.toByte)
      bob ! INPUT_INIT_FUNDEE("00" * 32, Bob.channelParams, pipe, aliceInit)
      pipe ! (alice, bob)
      val makeFundingTx = alice2blockchain.expectMsgType[MakeFundingTx]
      val dummyFundingTx = TestBitcoinClient.makeDummyFundingTx(makeFundingTx)
      alice ! dummyFundingTx
      val w = alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[PublishAsap]
      alice ! WatchEventSpent(w.event, dummyFundingTx.parentTx)
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice ! WatchEventConfirmed(BITCOIN_TX_CONFIRMED(dummyFundingTx.parentTx), 400000, 42)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.expectMsgType[PublishAsap]
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
      alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      alice2blockchain.expectMsgType[WatchLost]
      bob2blockchain.expectMsgType[WatchLost]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
    }
    test((alice, bob, pipe, relayerA, relayerB, paymentHandlerA, paymentHandlerB))
  }

  class SenderActor(channel: TestFSMRef[State, Data, Channel], paymentHandler: ActorRef, latch: CountDownLatch) extends Actor with ActorLogging {

    /*if (channel.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.size >= 10 || channel.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommit.spec.htlcs.size >= 10) {
      context stop self
    } else {

    }*/

    // we don't want to be below htlcMinimumMsat
    val requiredAmount = 1000000

    def buildCmdAdd(paymentHash: BinaryData, dest: PublicKey) = {
      // allow overpaying (no more than 2 times the required amount)
      val amount = requiredAmount + Random.nextInt(requiredAmount)
      val expiry = Globals.blockCount.get().toInt + PaymentLifecycle.defaultHtlcExpiry
      PaymentLifecycle.buildCommand(amount, expiry, paymentHash, Hop(null, dest, null) :: Nil)._1
    }

    def initiatePayment = {
      paymentHandler ! ReceivePayment(MilliSatoshi(requiredAmount), "One coffee")
      context become waitingForPaymentRequest
    }

    initiatePayment

    override def receive: Receive = ???

    def waitingForPaymentRequest: Receive = {
      case req: PaymentRequest =>
        channel ! buildCmdAdd(req.paymentHash, req.nodeId)
        context become waitingForFulfill
    }

    def waitingForFulfill: Receive = {
      case u: UpdateFulfillHtlc =>
        log.info(s"successfully sent htlc #${u.id}")
        latch.countDown()
        initiatePayment
      case u: UpdateFailHtlc =>
        log.warning(s"htlc failed: ${u.id}")
        initiatePayment
      case Status.Failure(t) =>
        log.error(s"htlc error: ${t.getMessage}")
        initiatePayment
      case 'cancelled =>
        log.warning(s"our htlc was cancelled!")
        // htlc was dropped because of a disconnection
        initiatePayment
    }

  }
  
  test("fuzzy test with only one party sending HTLCs") {
    case (alice, bob, _, _, _, _, paymentHandlerB) =>
      val latch = new CountDownLatch(100)
      system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch)))
      system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch)))
      awaitCond(latch.getCount == 0, max = 2 minutes)
      assert(alice.stateName == NORMAL || alice.stateName == OFFLINE)
      assert(bob.stateName == NORMAL || alice.stateName == OFFLINE)
    }

  test("fuzzy test with both parties sending HTLCs") {
    case (alice, bob, _, _, _, paymentHandlerA, paymentHandlerB) =>
      val latch = new CountDownLatch(100)
      system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch)))
      system.actorOf(Props(new SenderActor(alice, paymentHandlerB, latch)))
      system.actorOf(Props(new SenderActor(bob, paymentHandlerA, latch)))
      system.actorOf(Props(new SenderActor(bob, paymentHandlerA, latch)))
      awaitCond(latch.getCount == 0, max = 2 minutes)
      assert(alice.stateName == NORMAL || alice.stateName == OFFLINE)
      assert(bob.stateName == NORMAL || alice.stateName == OFFLINE)
  }

}
