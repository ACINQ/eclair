package fr.acinq.eclair.channel.states.e

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.bitcoin.Crypto.Scalar
import fr.acinq.eclair.TestkitBaseClass
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.crypto.{Generators, Sphinx}
import fr.acinq.eclair.wire._
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class OfflineStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple7[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayer)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayer))
    }
  }

  /**
    * This test checks the case where a disconnection occurs *right before* the counterparty receives a new sig
    */
  test("re-send update+sig after first commitment") { case (alice, bob, alice2bob, bob2alice, _, _, _) =>
    val sender = TestProbe()

    sender.send(alice, CMD_ADD_HTLC(1000000, BinaryData("42" * 32), 400144))
    val ab_add_0 = alice2bob.expectMsgType[UpdateAddHtlc]
    // add ->b
    alice2bob.forward(bob)

    sender.send(alice, CMD_SIGN)
    val ab_sig_0 = alice2bob.expectMsgType[CommitSig]
    // bob doesn't receive the sig

    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref))

    val bobCommitments = bob.stateData.asInstanceOf[HasCommitments].commitments
    val aliceCommitments = alice.stateData.asInstanceOf[HasCommitments].commitments

    val bobCurrentPerCommitmentPoint = Generators.perCommitPoint(bobCommitments.localParams.shaSeed, bobCommitments.localCommit.index)
    val aliceCurrentPerCommitmentPoint = Generators.perCommitPoint(aliceCommitments.localParams.shaSeed, aliceCommitments.localCommit.index)


    // a didn't receive any update or sig
    val ab_reestablish = alice2bob.expectMsg(ChannelReestablish(ab_add_0.channelId, 1, 0, Some(Scalar(Sphinx zeroes 32)), Some(aliceCurrentPerCommitmentPoint)))
    // b didn't receive the sig
    val ba_reestablish = bob2alice.expectMsg(ChannelReestablish(ab_add_0.channelId, 1, 0, Some(Scalar(Sphinx zeroes 32)), Some(bobCurrentPerCommitmentPoint)))

    // reestablish ->b
    alice2bob.forward(bob, ab_reestablish)
    // reestablish ->a
    bob2alice.forward(alice, ba_reestablish)

    // both nodes will send the fundinglocked message because all updates have been cancelled
    alice2bob.expectMsgType[FundingLocked]
    bob2alice.expectMsgType[FundingLocked]

    // a will re-send the update and the sig
    val ab_add_0_re = alice2bob.expectMsg(ab_add_0)
    val ab_sig_0_re = alice2bob.expectMsg(ab_sig_0)

    // add ->b
    alice2bob.forward(bob, ab_add_0_re)
    // sig ->b
    alice2bob.forward(bob, ab_sig_0_re)

    // and b will reply with a revocation
    val ba_rev_0 = bob2alice.expectMsgType[RevokeAndAck]
    // rev ->a
    bob2alice.forward(alice, ba_rev_0)

    // then b sends a sig
    bob2alice.expectMsgType[CommitSig]
    // sig -> a
    bob2alice.forward(alice)

    // and a answers with a rev
    alice2bob.expectMsgType[RevokeAndAck]
    // sig -> a
    alice2bob.forward(bob)

    alice2bob.expectNoMsg(500 millis)
    bob2alice.expectNoMsg(500 millis)

    alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localNextHtlcId == 1

    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  /**
    * This test checks the case where a disconnection occurs *right after* the counterparty receives a new sig
    */
  test("re-send lost revocation") { case (alice, bob, alice2bob, bob2alice, _, _, _) =>
    val sender = TestProbe()

    sender.send(alice, CMD_ADD_HTLC(1000000, BinaryData("42" * 32), 400144))
    val ab_add_0 = alice2bob.expectMsgType[UpdateAddHtlc]
    // add ->b
    alice2bob.forward(bob, ab_add_0)

    sender.send(alice, CMD_SIGN)
    val ab_sig_0 = alice2bob.expectMsgType[CommitSig]
    // sig ->b
    alice2bob.forward(bob, ab_sig_0)

    // bob received the sig, but alice didn't receive the revocation
    val ba_rev_0 = bob2alice.expectMsgType[RevokeAndAck]
    val ba_sig_0 = bob2alice.expectMsgType[CommitSig]

    bob2alice.expectNoMsg(500 millis)

    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref))

    val bobCommitments = bob.stateData.asInstanceOf[HasCommitments].commitments
    val aliceCommitments = alice.stateData.asInstanceOf[HasCommitments].commitments

    val bobCurrentPerCommitmentPoint = Generators.perCommitPoint(bobCommitments.localParams.shaSeed, bobCommitments.localCommit.index)
    val aliceCurrentPerCommitmentPoint = Generators.perCommitPoint(aliceCommitments.localParams.shaSeed, aliceCommitments.localCommit.index)

    // a didn't receive the sig
    val ab_reestablish = alice2bob.expectMsg(ChannelReestablish(ab_add_0.channelId, 1, 0, Some(Scalar(Sphinx zeroes 32)), Some(aliceCurrentPerCommitmentPoint)))
    // b did receive the sig
    val ba_reestablish = bob2alice.expectMsg(ChannelReestablish(ab_add_0.channelId, 2, 0, Some(Scalar(Sphinx zeroes 32)), Some(bobCurrentPerCommitmentPoint)))

    // reestablish ->b
    alice2bob.forward(bob, ab_reestablish)
    // reestablish ->a
    bob2alice.forward(alice, ba_reestablish)

    // b will re-send the lost revocation
    val ba_rev_0_re = bob2alice.expectMsg(ba_rev_0)
    // rev ->a
    bob2alice.forward(alice, ba_rev_0)

    // and b will attempt a new signature
    bob2alice.expectMsg(ba_sig_0)

    alice2bob.expectNoMsg(500 millis)
    bob2alice.expectNoMsg(500 millis)

    alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localNextHtlcId == 1

    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)

  }

}
