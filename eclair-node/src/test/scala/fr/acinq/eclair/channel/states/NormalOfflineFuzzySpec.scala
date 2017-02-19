package fr.acinq.eclair.channel.states

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Pipe, TestBitcoinClient, TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._
import scala.util.Random

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class NormalOfflineFuzzySpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple5[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], ActorRef, ActorRef, ActorRef]

  override def withFixture(test: OneArgTest) = {
    val pipe = system.actorOf(Props(new Pipe()))
    val alice2blockchain = TestProbe()
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient())))
    val bob2blockchain = TestProbe()
    val relayerA = system.actorOf(Props(new FuzzyRelayer(410000)))
    val relayerB = system.actorOf(Props(new FuzzyRelayer(420000)))
    val router = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(pipe, alice2blockchain.ref, router.ref, relayerA))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(pipe, bob2blockchain.ref, router.ref, relayerB))
    within(30 seconds) {
      val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
      val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
      relayerA ! alice
      relayerB ! bob
      alice ! INPUT_INIT_FUNDER(Bob.id, 0, TestConstants.fundingSatoshis, TestConstants.pushMsat, Alice.channelParams, bobInit)
      bob ! INPUT_INIT_FUNDEE(Alice.id, 0, Bob.channelParams, aliceInit)
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
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
    }
    test((alice, bob, pipe, relayerA, relayerB))
  }

  class FuzzyRelayer(expiry: Int) extends Actor with ActorLogging {

    val paymentpreimage = BinaryData("42" * 32)
    val paymentHash = Crypto.sha256(paymentpreimage)

    override def receive: Receive = {
      case channel: ActorRef => context become ready(channel)
    }

    def ready(channel: ActorRef): Receive = {
      case 'start => context become main(sender, channel, 0, 0)
    }

    def main(origin: ActorRef, channel: ActorRef, htlcSent: Int, htlcInFlight: Int): Receive = {

      case htlc: UpdateAddHtlc =>
        val preimage = BinaryData("42" * 32)
        sender ! CMD_FULFILL_HTLC(htlc.id, preimage)
        sender ! CMD_SIGN

      case (add: UpdateAddHtlc, fulfill: UpdateFulfillHtlc) =>
        if (htlcInFlight <= 1) {
          if (htlcSent < 100) {
            log.info(s"already sent $htlcSent, inFlight=${htlcInFlight - 1}")
            self ! 'add
          } else {
            origin ! "done"
          }
        }
        context become main(origin, channel, htlcSent, htlcInFlight - 1)

      case 'add =>
        val cmds = for (i <- 0 to Random.nextInt(10)) yield CMD_ADD_HTLC(Random.nextInt(1000000) + 1000, paymentHash, 400144)
        //val cmds = CMD_ADD_HTLC(Random.nextInt(1000000), paymentHash, expiry) :: Nil
        cmds.foreach(channel ! _)
        channel ! CMD_SIGN
        context become main(origin, channel, htlcSent + cmds.size, htlcInFlight + cmds.size)

      case "ok" => {}

    }

  }

  test("fuzzy testing with only one party sending HTLCs") {
    case (alice, bob, pipe, relayerA, relayerB) =>
      val sender = TestProbe()
      sender.send(relayerA, 'start)
      sender.send(relayerB, 'start)
      relayerA ! 'add
      import scala.concurrent.ExecutionContext.Implicits.global
      var currentPipe = pipe

      val task = system.scheduler.schedule(3 seconds, 3 seconds) {
        currentPipe ! INPUT_DISCONNECTED
        val newPipe = system.actorOf(Props(new Pipe()))
        system.scheduler.scheduleOnce(500 millis) {
          currentPipe ! INPUT_RECONNECTED(newPipe)
          currentPipe = newPipe
        }
      }
      sender.expectMsg(10 minutes, "done")
      task.cancel()
  }

  test("fuzzy testing in with both parties sending HTLCs") {
    case (alice, bob, pipe, relayerA, relayerB) =>
      val sender = TestProbe()
      sender.send(relayerA, 'start)
      sender.send(relayerB, 'start)
      relayerA ! 'add
      relayerB ! 'add
      import scala.concurrent.ExecutionContext.Implicits.global
      var currentPipe = pipe

      val task = system.scheduler.schedule(3 seconds, 3 seconds) {
        currentPipe ! INPUT_DISCONNECTED
        val newPipe = system.actorOf(Props(new Pipe()))
        system.scheduler.scheduleOnce(500 millis) {
          currentPipe ! INPUT_RECONNECTED(newPipe)
          currentPipe = newPipe
        }
      }
      sender.expectMsg(10 minutes, "done")
      sender.expectMsg(10 minutes, "done")
      task.cancel()
  }


}
