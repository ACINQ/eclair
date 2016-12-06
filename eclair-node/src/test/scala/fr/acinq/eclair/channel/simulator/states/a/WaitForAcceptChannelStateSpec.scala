package fr.acinq.eclair.channel.simulator.states.a

import akka.actor.{ActorRef, Props}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.{MakeAnchor, PeerWatcher}
import fr.acinq.eclair.channel.simulator.states.StateSpecBaseClass
import fr.acinq.eclair.channel.{OPEN_WAIT_FOR_OPEN_WITHANCHOR, _}
import fr.acinq.eclair.wire.{Error, OpenChannel}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForAcceptChannelStateSpec extends StateSpecBaseClass {

  type FixtureParam = Tuple5[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, ActorRef]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)))
    val bob2blockchain = TestProbe()
    val paymentHandler = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams, "A"))
    within(30 seconds) {
      alice2bob.expectMsgType[OpenChannel]
      awaitCond(alice.stateName == OPEN_WAIT_FOR_OPEN_WITHANCHOR)
    }
    test((alice, alice2bob, bob2alice, alice2blockchain, blockchainA))
  }

  test("recv OpenChannel") { case (alice, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      bob2alice.expectMsgType[OpenChannel]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == OPEN_WAIT_FOR_OPEN_WITHANCHOR)
    }
  }

  test("recv anchor") { case (alice, alice2bob, bob2alice, alice2blockchain, blockchain) =>
    within(30 seconds) {
      bob2alice.expectMsgType[OpenChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeAnchor]
      alice2blockchain.forward(blockchain)
      awaitCond(alice.stateName == OPEN_WAIT_FOR_COMMIT_SIG)
      alice2bob.expectMsgType[OpenChannel]
    }
  }

  test("recv error") { case (bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      bob ! Error(0, "oops".getBytes)
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv CMD_CLOSE") { case (alice, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      alice ! CMD_CLOSE(None)
      awaitCond(alice.stateName == CLOSED)
    }
  }

}
