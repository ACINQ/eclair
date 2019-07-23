/*
 * Copyright 2019 ACINQ SAS
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

import java.util.UUID

import akka.actor.Status
import akka.testkit.{TestActorRef, TestProbe}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{ByteVector32, ScriptFlags, Transaction}
import fr.acinq.eclair.blockchain.{CurrentBlockCount, PublishAsap, WatchConfirmed, WatchEventSpent}
import fr.acinq.eclair.channel.Channel.LocalError
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.payment.CommandBuffer
import fr.acinq.eclair.payment.CommandBuffer.CommandSend
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.HtlcSuccessTx
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestConstants, TestkitBaseClass, randomBytes32}
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

    sender.send(alice, CMD_ADD_HTLC(1000000, ByteVector32.Zeroes, 400144, TestConstants.emptyOnionPacket, upstream = Left(UUID.randomUUID())))
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
    val ab_reestablish = alice2bob.expectMsg(ChannelReestablish(ab_add_0.channelId, 1, 0, Some(PrivateKey(ByteVector32.Zeroes)), Some(aliceCurrentPerCommitmentPoint)))
    // b didn't receive the sig
    val ba_reestablish = bob2alice.expectMsg(ChannelReestablish(ab_add_0.channelId, 1, 0, Some(PrivateKey(ByteVector32.Zeroes)), Some(bobCurrentPerCommitmentPoint)))

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

    sender.send(alice, CMD_ADD_HTLC(1000000, randomBytes32, 400144, TestConstants.emptyOnionPacket, upstream = Left(UUID.randomUUID())))
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
    val ab_reestablish = alice2bob.expectMsg(ChannelReestablish(ab_add_0.channelId, 1, 0, Some(PrivateKey(ByteVector32.Zeroes)), Some(aliceCurrentPerCommitmentPoint)))
    // b did receive the sig
    val ba_reestablish = bob2alice.expectMsg(ChannelReestablish(ab_add_0.channelId, 2, 0, Some(PrivateKey(ByteVector32.Zeroes)), Some(bobCurrentPerCommitmentPoint)))

    // reestablish ->b
    alice2bob.forward(bob, ab_reestablish)
    // reestablish ->a
    bob2alice.forward(alice, ba_reestablish)

    // b will re-send the lost revocation
    val ba_rev_0_re = bob2alice.expectMsg(ba_rev_0)
    // rev ->a
    bob2alice.forward(alice, ba_rev_0_re)

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
    assert(new String(error.data.toArray) === PleasePublishYourCommitment(channelId(alice)).getMessage)

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
    addHtlc(250000000, alice, bob, alice2bob, bob2alice)
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
    assert(new String(error.data.toArray) === PleasePublishYourCommitment(channelId(alice)).getMessage)

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
    assert(new String(error.data.toArray) === InvalidRevokedCommitProof(channelId(alice), 0, 42, ba_reestablish_forged.yourLastPerCommitmentSecret.get).getMessage)
  }

  test("change relay fee while offline") { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // alice and bob will not announce that their channel is OFFLINE
    channelUpdateListener.expectNoMsg(300 millis)

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

    // then alice reaches NORMAL state, and after a delay she broadcasts the channel_update
    val channelUpdate = channelUpdateListener.expectMsgType[LocalChannelUpdate](20 seconds).channelUpdate
    assert(channelUpdate.feeBaseMsat === 4200)
    assert(channelUpdate.feeProportionalMillionths === 123456)
    assert(Announcements.isEnabled(channelUpdate.channelFlags))

    // no more messages
    channelUpdateListener.expectNoMsg(300 millis)
  }

  test("broadcast disabled channel_update while offline") { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // alice and bob will not announce that their channel is OFFLINE
    channelUpdateListener.expectNoMsg(300 millis)

    // we attempt to send a payment
    sender.send(alice, CMD_ADD_HTLC(4200, randomBytes32, 123456, TestConstants.emptyOnionPacket, upstream = Left(UUID.randomUUID())))
    val failure = sender.expectMsgType[Status.Failure]
    val AddHtlcFailed(_, _, ChannelUnavailable(_), _, _, _) = failure.cause

    // alice will broadcast a new disabled channel_update
    val update = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(!Announcements.isEnabled(update.channelUpdate.channelFlags))
  }

  test("pending non-relayed fulfill htlcs will timeout upstream") { f =>
    import f._
    val sender = TestProbe()
    val register = TestProbe()
    val commandBuffer = TestActorRef(new CommandBuffer(bob.underlyingActor.nodeParams, register.ref))
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[ChannelErrorOccured])

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val initialCommitTx = initialState.commitments.localCommit.publishableTxs.commitTx.tx
    val HtlcSuccessTx(_, htlcSuccessTx, _) = initialState.commitments.localCommit.publishableTxs.htlcTxsAndSigs.head.txinfo

    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // We simulate a pending fulfill on that HTLC but not relayed.
    // When it is close to expiring upstream, we should close the channel.
    sender.send(commandBuffer, CommandSend(htlc.channelId, htlc.id, CMD_FULFILL_HTLC(htlc.id, r, commit = true)))
    sender.send(bob, CurrentBlockCount(htlc.cltvExpiry - bob.underlyingActor.nodeParams.fulfillSafetyBeforeTimeoutBlocks))

    val ChannelErrorOccured(_, _, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccured]
    assert(isFatal)
    assert(err.isInstanceOf[HtlcWillTimeoutUpstream])

    bob2blockchain.expectMsg(PublishAsap(initialCommitTx))
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    assert(bob2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(initialCommitTx))
    bob2blockchain.expectMsgType[WatchConfirmed] // main delayed

    bob2blockchain.expectMsg(PublishAsap(initialCommitTx))
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    assert(bob2blockchain.expectMsgType[PublishAsap].tx.txOut === htlcSuccessTx.txOut)
    bob2blockchain.expectMsgType[PublishAsap] // htlc delayed
    alice2blockchain.expectNoMsg(500 millis)
  }

  test("pending non-relayed fail htlcs will timeout upstream") { f =>
    import f._
    val sender = TestProbe()
    val register = TestProbe()
    val commandBuffer = TestActorRef(new CommandBuffer(bob.underlyingActor.nodeParams, register.ref))
    val (_, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // We simulate a pending failure on that HTLC.
    // Even if we get close to expiring upstream we shouldn't close the channel, because we have nothing to lose.
    sender.send(commandBuffer, CommandSend(htlc.channelId, htlc.id, CMD_FAIL_HTLC(htlc.id, Right(IncorrectOrUnknownPaymentDetails(0)))))
    sender.send(bob, CurrentBlockCount(htlc.cltvExpiry - bob.underlyingActor.nodeParams.fulfillSafetyBeforeTimeoutBlocks))

    bob2blockchain.expectNoMsg(250 millis)
    alice2blockchain.expectNoMsg(250 millis)
  }

}
