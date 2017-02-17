package fr.acinq.eclair.channel.states

import akka.actor.{Actor, ActorRef, Props}
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
    val relayerA = system.actorOf(Props(new FuzzyRelayer()))
    val relayerB = system.actorOf(Props(new FuzzyRelayer()))
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

  class FuzzyRelayer() extends Actor {

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
        println(s"received fulfill for htlc ${fulfill.id}, htlcInFlight = ${htlcInFlight - 1}")
        if (htlcInFlight <= 1) {
          if (htlcSent < 100) {
            self ! 'add
          } else {
            origin ! "done"
            context stop self
          }
        }
        context become main(origin, channel, htlcSent + 1, htlcInFlight - 1)

      case 'add =>
        val cmds = for (i <- 0 to Random.nextInt(10)) yield CMD_ADD_HTLC(Random.nextInt(1000000), paymentHash, 400144)
        println(s"sending ${cmds.size} htlcs")
        cmds.foreach(channel ! _)
        channel ! CMD_SIGN
        context become main(origin, channel, htlcSent + 1, htlcInFlight + cmds.size)

      case 'sign =>
        channel ! CMD_SIGN

      case "ok" => {}

      /*case paymentHash: BinaryData =>
        for(i <- 0 until Random.nextInt(5))
        channel ! CMD_ADD_HTLC(42000, paymentHash, 400144)
        if (Random.nextInt(5) == 0) {
          self ! 'sign
        } else {
          self ! 'add
        }
        context become main(htlcSent + 1)*/

    }

  }

  test("fuzzy testing in NORMAL state with only one party sending HTLCs") {
    case (alice, bob, pipe, relayerA, relayerB) =>
      val sender = TestProbe()
      sender.send(relayerA, 'start)
      sender.send(relayerB, 'start)
      relayerA ! 'add
      /*import scala.concurrent.ExecutionContext.Implicits.global
      system.scheduler.scheduleOnce(2 seconds, pipe, INPUT_DISCONNECTED)
      system.scheduler.scheduleOnce(5 seconds, pipe, INPUT_RECONNECTED(pipe))*/
      println(sender.expectMsgType[String](1 hour))
  }


}
