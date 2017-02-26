package fr.acinq.eclair.channel.states.b

import akka.actor.Props
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.{PeerWatcher, WatchConfirmed, WatchSpent}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.DummyDb
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestBitcoinClient, TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForFundingCreatedStateSpec extends TestkitBaseClass {

  type FixtureParam = Tuple4[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient())))
    val bob2blockchain = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, blockchainA, router.ref, relayer.ref, new DummyDb()))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, router.ref, relayer.ref, new DummyDb()))
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(Bob.id, 0, TestConstants.fundingSatoshis, TestConstants.pushMsat, Alice.channelParams, bobInit)
      bob ! INPUT_INIT_FUNDEE(Alice.id, 0, Bob.channelParams, aliceInit)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_CREATED)
    }
    test((bob, alice2bob, bob2alice, bob2blockchain))
  }

  test("recv FundingCreated") { case (bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      awaitCond(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      bob2alice.expectMsgType[FundingSigned]
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv Error") { case (bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      bob ! Error(0, "oops".getBytes)
      awaitCond(bob.stateName == CLOSED)
    }
  }

  test("recv CMD_CLOSE") { case (bob, alice2bob, bob2alice, bob2blockchain) =>
    within(30 seconds) {
      bob ! CMD_CLOSE(None)
      awaitCond(bob.stateName == CLOSED)
    }
  }

}
