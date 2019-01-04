/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel.states.e

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.Scalar
import fr.acinq.bitcoin.{BinaryData, ScriptFlags, Transaction}
import fr.acinq.eclair.blockchain.{PublishAsap, WatchEventSpent}
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
import org.scalatest.Outcome

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */

class OfflineStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(setup)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(setup))
    }
  }

  def aliceInit = Init(TestConstants.Alice.nodeParams.globalFeatures, TestConstants.Alice.nodeParams.localFeatures)
  def bobInit = Init(TestConstants.Bob.nodeParams.globalFeatures, TestConstants.Bob.nodeParams.localFeatures)

  /**
    * This test checks the case where a disconnection occurs *right before* the counterparty receives a new sig
    */
  test("re-send update+sig after first commitment") { f =>
    import f._
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
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    val bobCommitments = bob.stateData.asInstanceOf[HasCommitments].commitments
    val aliceCommitments = alice.stateData.asInstanceOf[HasCommitments].commitments

    val bobCurrentPerCommitmentPoint = TestConstants.Bob.keyManager.commitmentPoint(bobCommitments.localParams.channelKeyPath, bobCommitments.localCommit.index)
    val aliceCurrentPerCommitmentPoint = TestConstants.Alice.keyManager.commitmentPoint(aliceCommitments.localParams.channelKeyPath, aliceCommitments.localCommit.index)


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
  test("re-send lost revocation") { f =>
    import f._
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
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    val bobCommitments = bob.stateData.asInstanceOf[HasCommitments].commitments
    val aliceCommitments = alice.stateData.asInstanceOf[HasCommitments].commitments

    val bobCurrentPerCommitmentPoint = TestConstants.Bob.keyManager.commitmentPoint(bobCommitments.localParams.channelKeyPath, bobCommitments.localCommit.index)
    val aliceCurrentPerCommitmentPoint = TestConstants.Alice.keyManager.commitmentPoint(aliceCommitments.localParams.channelKeyPath, aliceCommitments.localCommit.index)

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

  test("discover that we have a revoked commitment") { f =>
    import f._
    val sender = TestProbe()

    val (ra1, htlca1) = addHtlc(250000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val oldStateData = alice.stateData
    fulfillHtlc(htlca1.id, ra1, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlca2.id, ra2, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlca3.id, ra3, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // then we manually replace alice's state with an older one
    alice.setState(OFFLINE, oldStateData)

    // then we reconnect them
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]

    // alice then realizes it has an old state...
    bob2alice.forward(alice)
    // ... and ask bob to publish its current commitment
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data) === PleasePublishYourCommitment(channelId(alice)).getMessage)

    // alice now waits for bob to publish its commitment
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob is nice and publishes its commitment
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    sender.send(alice, WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx))

    // alice is able to claim its main output
    val claimMainOutput = alice2blockchain.expectMsgType[PublishAsap].tx
    Transaction.correctlySpends(claimMainOutput, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

  }

  test("discover that they have a more recent commit than the one we know") { f =>
    import f._
    val sender = TestProbe()

    // we start by storing the current state
    val oldStateData = alice.stateData
    // then we add an htlc and sign it
    val (ra1, htlca1) = addHtlc(250000000, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // alice will receive neither the revocation nor the commit sig
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.expectMsgType[CommitSig]

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // then we manually replace alice's state with an older one
    alice.setState(OFFLINE, oldStateData)

    // then we reconnect them
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]

    // alice then realizes it has an old state...
    bob2alice.forward(alice)
    // ... and ask bob to publish its current commitment
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data) === PleasePublishYourCommitment(channelId(alice)).getMessage)

    // alice now waits for bob to publish its commitment
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob is nice and publishes its commitment
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    sender.send(alice, WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx))

    // alice is able to claim its main output
    val claimMainOutput = alice2blockchain.expectMsgType[PublishAsap].tx
    Transaction.correctlySpends(claimMainOutput, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

  }

  test("counterparty lies about having a more recent commitment") { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // then we reconnect them
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    val ba_reestablish = bob2alice.expectMsgType[ChannelReestablish]

    // let's forge a dishonest channel_reestablish
    val ba_reestablish_forged = ba_reestablish.copy(nextRemoteRevocationNumber = 42)

    // alice then finds out bob is lying
    bob2alice.send(alice, ba_reestablish_forged)
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data) === InvalidRevokedCommitProof(channelId(alice), 0, 42, ba_reestablish_forged.yourLastPerCommitmentSecret.get).getMessage)
  }

  test("change relay fee while offline") { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // alice and bob announce that their channel is OFFLINE
    assert(Announcements.isEnabled(channelUpdateListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags) == false)
    assert(Announcements.isEnabled(channelUpdateListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags) == false)

    // we make alice update here relay fee
    sender.send(alice, CMD_UPDATE_RELAY_FEE(4200, 123456))
    sender.expectMsg("ok")

    // alice doesn't broadcast the new channel_update yet
    channelUpdateListener.expectNoMsg(300 millis)

    // then we reconnect them
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    // note that we don't forward the channel_reestablish so that only alice reaches NORMAL state, it facilitates the test below
    bob2alice.forward(alice)

    // then alice reaches NORMAL state, and during the transition she broadcasts the channel_update
    val channelUpdate = channelUpdateListener.expectMsgType[LocalChannelUpdate](10 seconds).channelUpdate
    assert(channelUpdate.feeBaseMsat === 4200)
    assert(channelUpdate.feeProportionalMillionths === 123456)
    assert(Announcements.isEnabled(channelUpdate.channelFlags) == true)

    // no more messages
    channelUpdateListener.expectNoMsg(300 millis)
  }

}
