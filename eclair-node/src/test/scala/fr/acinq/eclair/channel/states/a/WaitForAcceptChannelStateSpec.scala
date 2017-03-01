package fr.acinq.eclair.channel.states.a

import akka.actor.ActorRef
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{WAIT_FOR_FUNDING_INTERNAL, _}
import fr.acinq.eclair.db.DummyDb
import fr.acinq.eclair.wire.{AcceptChannel, Error, Init, OpenChannel}
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForAcceptChannelStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple5[TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, ActorRef]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    within(30 seconds) {
      alice ! INPUT_INIT_FUNDER(0, TestConstants.fundingSatoshis, TestConstants.pushMsat, Alice.channelParams, alice2bob.ref, bobInit)
      bob ! INPUT_INIT_FUNDEE(0, Bob.channelParams, bob2alice.ref, aliceInit)
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == WAIT_FOR_ACCEPT_CHANNEL)
    }
    test((alice, alice2bob, bob2alice, alice2blockchain, blockchainA))
  }

  test("recv AcceptChannel") { case (alice, _, bob2alice, _, _) =>
    within(30 seconds) {
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_INTERNAL)
    }
  }

  test("recv AcceptChannel (reserve too high)") { case (alice, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val accept = bob2alice.expectMsgType[AcceptChannel]
      // 30% is huge, recommended ratio is 1%
      val reserveTooHigh = (0.3 * TestConstants.fundingSatoshis).toLong
      alice ! accept.copy(channelReserveSatoshis = reserveTooHigh)
      val error = alice2bob.expectMsgType[Error]
      assert(new String(error.data) === "requirement failed: channelReserveSatoshis too high: ratio=0.3 max=0.05")
      awaitCond(alice.stateName == CLOSED)
    }
  }

  /*test("recv funding tx") { case (alice, alice2bob, bob2alice, alice2blockchain, blockchain) =>
    within(30 seconds) {
      bob2alice.expectMsgType[OpenChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeFundingTx]
      alice2blockchain.forward(blockchain)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_SIGNED)
      alice2bob.expectMsgType[OpenChannel]
    }
  }*/

  test("recv Error") { case (bob, alice2bob, bob2alice, _, _) =>
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
