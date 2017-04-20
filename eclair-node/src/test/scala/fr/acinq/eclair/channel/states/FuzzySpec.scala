package fr.acinq.eclair.channel.states

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{ActorRef, Cancellable, Props}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.wire._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.collection.immutable.Nil
import scala.concurrent.duration._
import scala.util.Random

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class FuzzySpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple7[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], ActorRef, ActorRef, ActorRef, ActorRef, ActorRef]

  override def withFixture(test: OneArgTest) = {
    val pipe = system.actorOf(Props(new Pipe()))
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
      alice ! INPUT_INIT_FUNDER("00" * 32, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, Alice.channelParams, pipe, bobInit)
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

  // we don't want to be below htlcMinimumMsat
  val requiredAmount = 1000000

  def buildCmdAdd(paymentHash: BinaryData, dest: PublicKey) = {
    // allow overpaying (no more than 2 times the required amount)
    val amount = requiredAmount + Random.nextInt(requiredAmount)
    val onion = PaymentLifecycle.buildOnion(dest :: Nil, Nil, paymentHash)

    CMD_ADD_HTLC(amount, paymentHash, Globals.blockCount.get() + PaymentLifecycle.defaultHtlcExpiry, onion.onionPacket, upstream_opt = None, commit = true)
  }

  def gatling(parallel: Int, total: Int, channel: TestFSMRef[State, Data, Channel], paymentHandler: ActorRef, destination: PublicKey): Unit = {
    for (i <- 0 until total / parallel) {
      // we don't want to be above maxHtlcValueInFlightMsat or maxAcceptedHtlcs
      awaitCond(channel.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.size < 10 && channel.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommit.spec.htlcs.size < 10)
      val senders = for (i <- 0 until parallel) yield TestProbe()
      senders.foreach(_.send(paymentHandler, ReceivePayment(MilliSatoshi(requiredAmount))))
      val paymentHashes = senders.map(_.expectMsgType[PaymentRequest]).map(pr => pr.paymentHash)
      val cmds = paymentHashes.map(h => buildCmdAdd(h, destination))
      senders.zip(cmds).foreach {
        case (s, cmd) => s.send(channel, cmd)
      }
      val oks = senders.map(_.expectMsgType[String])
      val fulfills = senders.map(_.expectMsgType[UpdateFulfillHtlc](10 seconds))
    }
  }

  def randomDisconnect(initialPipe: ActorRef): Cancellable = {
    import scala.concurrent.ExecutionContext.Implicits.global
    var currentPipe = initialPipe
    system.scheduler.schedule(3 seconds, 3 seconds) {
      currentPipe ! INPUT_DISCONNECTED
      val newPipe = system.actorOf(Props(new Pipe()))
      system.scheduler.scheduleOnce(500 millis) {
        currentPipe ! INPUT_RECONNECTED(newPipe)
        currentPipe = newPipe
      }
    }
  }

  test("fuzzy testing with only one party sending HTLCs") {
    case (alice, bob, pipe, relayerA, relayerB, paymentHandlerA, paymentHandlerB) =>
      val success1 = new AtomicBoolean(false)
      val gatling1 = new Thread(new Runnable {
        override def run(): Unit = {
          gatling(5, 100, alice, paymentHandlerB, Bob.id)
          success1.set(true)
        }
      })
      gatling1.start()
      val chaosMonkey = randomDisconnect(pipe)
      gatling1.join()
      assert(success1.get())
      chaosMonkey.cancel()
  }

  test("fuzzy testing with both parties sending HTLCs") {
    case (alice, bob, pipe, relayerA, relayerB, paymentHandlerA, paymentHandlerB) =>
      val success1 = new AtomicBoolean(false)
      val gatling1 = new Thread(new Runnable {
        override def run(): Unit = {
          gatling(4, 100, alice, paymentHandlerB, Bob.id)
          success1.set(true)
        }
      })
      gatling1.start()
      val success2 = new AtomicBoolean(false)
      val gatling2 = new Thread(new Runnable {
        override def run(): Unit = {
          gatling(4, 100, bob, paymentHandlerA, Alice.id)
          success2.set(true)
        }
      })
      gatling2.start()
      val chaosMonkey = randomDisconnect(pipe)
      gatling1.join()
      assert(success1.get())
      gatling2.join()
      assert(success2.get())
      chaosMonkey.cancel()
  }

}
