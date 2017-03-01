package fr.acinq.eclair.channel.states

import akka.actor.{ActorRef, Cancellable, Props}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Hop
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
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient())))
    val bob2blockchain = TestProbe()
    val paymentHandlerA = system.actorOf(Props(new LocalPaymentHandler()), name = "payment-handler-a")
    val paymentHandlerB = system.actorOf(Props(new LocalPaymentHandler()), name = "payment-handler-b")
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
      alice ! INPUT_INIT_FUNDER(0, TestConstants.fundingSatoshis, TestConstants.pushMsat, Alice.channelParams, pipe, bobInit)
      bob ! INPUT_INIT_FUNDEE(0, Bob.channelParams, pipe, aliceInit)
      pipe ! (alice, bob)
      alice2blockchain.expectMsgType[MakeFundingTx]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[PublishAsap]
      alice2blockchain.forward(blockchainA)
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
      bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
      alice2blockchain.expectMsgType[WatchLost]
      bob2blockchain.expectMsgType[WatchLost]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
    }
    test((alice, bob, pipe, relayerA, relayerB, paymentHandlerA, paymentHandlerB))
  }

  def buildCmdAdd(paymentHash: BinaryData) = {
    val channelUpdate_ab = ChannelUpdate("00" * 64, 0, 0, "0000", cltvExpiryDelta = 4, feeBaseMsat = 642000, feeProportionalMillionths = 7, htlcMinimumMsat = 0)
    val hops = Hop(Alice.nodeParams.privateKey.publicKey, Bob.nodeParams.privateKey.publicKey, channelUpdate_ab) :: Nil
    // we don't want to be below htlcMinimumMsat
    val amount = Random.nextInt(1000000) + 1000
    PaymentLifecycle.buildCommand(amount, paymentHash, hops, 444000)
  }

  def gatling(parallel: Int, total: Int, channel: TestFSMRef[State, Data, Channel], paymentHandler: ActorRef): Unit = {
    for (i <- 0 until total / parallel) {
      // we don't want to be above maxHtlcValueInFlightMsat or maxAcceptedHtlcs
      awaitCond(channel.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.size < 10 && channel.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommit.spec.htlcs.size < 10)
      val senders = for (i <- 0 until parallel) yield TestProbe()
      senders.foreach(_.send(paymentHandler, 'genh))
      val paymentHashes = senders.map(_.expectMsgType[BinaryData])
      val cmds = paymentHashes.map(buildCmdAdd(_))
      senders.zip(cmds).foreach(x => x._1.send(channel, x._2))
      val oks = senders.map(_.expectMsgType[String])
      val fulfills = senders.map(_.expectMsgType[UpdateFulfillHtlc])
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
      val gatling1 = new Thread(new Runnable {
        override def run(): Unit = gatling(5, 100, alice, paymentHandlerB)
      })
      gatling1.start()
      val chaosMonkey = randomDisconnect(pipe)
      gatling1.join()
      chaosMonkey.cancel()
  }

  test("fuzzy testing with only both parties sending HTLCs") {
    case (alice, bob, pipe, relayerA, relayerB, paymentHandlerA, paymentHandlerB) =>
      val gatling1 = new Thread(new Runnable {
        override def run(): Unit = gatling(4, 100, alice, paymentHandlerB)
      })
      gatling1.start()
      val gatling2 = new Thread(new Runnable {
        override def run(): Unit = gatling(4, 100, bob, paymentHandlerA)
      })
      gatling2.start()
      val chaosMonkey = randomDisconnect(pipe)
      gatling1.join()
      gatling2.join()
      chaosMonkey.cancel()
  }

}
