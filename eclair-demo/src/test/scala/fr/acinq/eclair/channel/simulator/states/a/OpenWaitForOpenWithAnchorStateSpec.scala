package fr.acinq.eclair.channel.simulator.states.a

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.{MakeAnchor, PollingWatcher}
import fr.acinq.eclair.channel.{OPEN_WAIT_FOR_OPEN_WITHANCHOR, _}
import lightning.{error, open_anchor, open_channel}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, fixture}

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class OpenWaitForOpenWithAnchorStateSpec extends TestKit(ActorSystem("test"))  with fixture.FunSuiteLike with BeforeAndAfterAll {

  type FixtureParam = Tuple5[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, ActorRef]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val bob2blockchain = TestProbe()
    val paymentHandler = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams, "A"))
    within(30 seconds) {
      alice2bob.expectMsgType[open_channel]
      awaitCond(alice.stateName == OPEN_WAIT_FOR_OPEN_WITHANCHOR)
    }
    test((alice, alice2bob, bob2alice, alice2blockchain, blockchainA))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  test("recv open_channel") { case (alice, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      bob2alice.expectMsgType[open_channel]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == OPEN_WAIT_FOR_OPEN_WITHANCHOR)
    }
  }

  test("recv anchor") { case (alice, alice2bob, bob2alice, alice2blockchain, blockchain) =>
    within(30 seconds) {
      bob2alice.expectMsgType[open_channel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeAnchor]
      alice2blockchain.forward(blockchain)
      awaitCond(alice.stateName == OPEN_WAIT_FOR_COMMIT_SIG)
      alice2bob.expectMsgType[open_anchor]
    }
  }

  test("recv error") { case (bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      bob ! error(Some("oops"))
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
