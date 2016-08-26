package fr.acinq.eclair.channel.simulator.states.b

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.{OPEN_WAITING_OURANCHOR, _}
import lightning._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, fixture}

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class OpenWaitForCommitSigStateSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike with BeforeAndAfterAll {

  type FixtureParam = Tuple5[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, ActorRef]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = TestActorRef(new PeerWatcher(new TestBitcoinClient(), 300))
    val bob2blockchain = TestProbe()
    val paymentHandler = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams, "A"))
    within(30 seconds) {
      alice2bob.expectMsgType[open_channel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[open_channel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeAnchor]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[open_anchor]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == OPEN_WAIT_FOR_COMMIT_SIG)
    }
    test((alice, alice2bob, bob2alice, alice2blockchain, blockchainA))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  test("recv open_commit_sig with valid signature") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      bob2alice.expectMsgType[open_commit_sig]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == OPEN_WAITING_OURANCHOR)
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[Publish]
    }
  }

  test("recv open_commit_sig with invalid signature") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      // sending an invalid sig
      alice ! open_commit_sig(signature(0, 0, 0, 0, 0, 0, 0, 0))
      awaitCond(alice.stateName == CLOSED)
      alice2bob.expectMsgType[error]
    }
  }

  test("recv CMD_CLOSE") { case (alice, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      alice ! CMD_CLOSE(None)
      awaitCond(alice.stateName == CLOSED)
    }
  }

}
