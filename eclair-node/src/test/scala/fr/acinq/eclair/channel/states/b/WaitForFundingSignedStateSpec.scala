package fr.acinq.eclair.channel.states.b

import akka.actor.ActorRef
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.wire.{AcceptChannel, Error, FundingCreated, FundingSigned, Init, OpenChannel}
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class WaitForFundingSignedStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

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
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeFundingTx]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_SIGNED)
    }
    test((alice, alice2bob, bob2alice, alice2blockchain, blockchainA))
  }

  test("recv FundingSigned with valid signature") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.expectMsgType[PublishAsap]
    }
  }

  test("recv FundingSigned with invalid signature") { case (alice, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      // sending an invalid sig
      alice ! FundingSigned(0, BinaryData("00" * 64))
      awaitCond(alice.stateName == CLOSED)
      alice2bob.expectMsgType[Error]
    }
  }

  test("recv CMD_CLOSE") { case (alice, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      alice ! CMD_CLOSE(None)
      awaitCond(alice.stateName == CLOSED)
    }
  }

}
