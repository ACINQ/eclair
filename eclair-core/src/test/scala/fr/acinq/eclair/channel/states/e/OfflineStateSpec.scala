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

import akka.actor.{ActorRef, Status}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{ByteVector32, ScriptFlags, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, TestFeeEstimator}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.HtlcSuccessTx
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, LongToBtcAmount, TestConstants, TestKitBaseClass, randomBytes32}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class OfflineStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsHelperMethods {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = test.tags.contains("disable-offline-mismatch") match {
      case false => init()
      case true => init(nodeParamsA = Alice.nodeParams.copy(onChainFeeConf = Alice.nodeParams.onChainFeeConf.copy(closeOnOfflineMismatch = false)))
    }
    import setup._
    within(30 seconds) {
      reachNormal(setup)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(setup))
    }
  }

  def aliceInit = Init(TestConstants.Alice.nodeParams.features)

  def bobInit = Init(TestConstants.Bob.nodeParams.features)

  /**
   * This test checks the case where a disconnection occurs *right before* the counterparty receives a new sig
   */
  test("re-send update+sig after first commitment") { f =>
    import f._
    val sender = TestProbe()

    sender.send(alice, CMD_ADD_HTLC(sender.ref, 1000000 msat, ByteVector32.Zeroes, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref)))
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

    val bobCurrentPerCommitmentPoint = TestConstants.Bob.keyManager.commitmentPoint(
      TestConstants.Bob.keyManager.channelKeyPath(bobCommitments.localParams, bobCommitments.channelVersion),
      bobCommitments.localCommit.index)
    val aliceCurrentPerCommitmentPoint = TestConstants.Alice.keyManager.commitmentPoint(
      TestConstants.Alice.keyManager.channelKeyPath(aliceCommitments.localParams, aliceCommitments.channelVersion),
      aliceCommitments.localCommit.index)

    // a didn't receive any update or sig
    val ab_reestablish = alice2bob.expectMsg(ChannelReestablish(ab_add_0.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint))
    // b didn't receive the sig
    val ba_reestablish = bob2alice.expectMsg(ChannelReestablish(ab_add_0.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint))

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

    sender.send(alice, CMD_ADD_HTLC(ActorRef.noSender, 1000000 msat, randomBytes32, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref)))
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

    val bobCurrentPerCommitmentPoint = TestConstants.Bob.keyManager.commitmentPoint(
      TestConstants.Bob.keyManager.channelKeyPath(bobCommitments.localParams, bobCommitments.channelVersion),
      bobCommitments.localCommit.index)
    val aliceCurrentPerCommitmentPoint = TestConstants.Alice.keyManager.commitmentPoint(
      TestConstants.Alice.keyManager.channelKeyPath(aliceCommitments.localParams, aliceCommitments.channelVersion),
      aliceCommitments.localCommit.index)

    // a didn't receive the sig
    val ab_reestablish = alice2bob.expectMsg(ChannelReestablish(ab_add_0.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint))
    // b did receive the sig
    val ba_reestablish = bob2alice.expectMsg(ChannelReestablish(ab_add_0.channelId, 2, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint))

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

    val (ra1, htlca1) = addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000 msat, alice, bob, alice2bob, bob2alice)
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
    addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN.type]]
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
    assert(new String(error.data.toArray) === InvalidRevokedCommitProof(channelId(alice), 0, 42, ba_reestablish_forged.yourLastPerCommitmentSecret).getMessage)
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
    sender.send(alice, CMD_UPDATE_RELAY_FEE(4200 msat, 123456))
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_RELAY_FEE]]

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
    assert(channelUpdate.feeBaseMsat === 4200.msat)
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
    sender.send(alice, CMD_ADD_HTLC(ActorRef.noSender, 4200 msat, randomBytes32, CltvExpiry(123456), TestConstants.emptyOnionPacket, localOrigin(sender.ref)))
    sender.expectMsgType[RES_ADD_FAILED[ChannelUnavailable]]

    // alice will broadcast a new disabled channel_update
    val update = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(!Announcements.isEnabled(update.channelUpdate.channelFlags))
  }

  test("replay pending commands when going back to NORMAL") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // We simulate a pending fulfill
    bob.underlyingActor.nodeParams.db.pendingRelay.addPendingRelay(initialState.channelId, CMD_FULFILL_HTLC(htlc.id, r, commit = true))

    // then we reconnect them
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob)
    bob2alice.forward(alice)

    bob2alice.expectMsgType[UpdateFulfillHtlc]
  }

  test("replay pending commands when going back to SHUTDOWN") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    // We initiate a mutual close
    sender.send(alice, CMD_CLOSE(None))
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)

    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // We simulate a pending fulfill
    bob.underlyingActor.nodeParams.db.pendingRelay.addPendingRelay(initialState.channelId, CMD_FULFILL_HTLC(htlc.id, r, commit = true))

    // then we reconnect them
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob)
    bob2alice.forward(alice)

    // peers re-exchange shutdown messages
    alice2bob.expectMsgType[Shutdown]
    bob2alice.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.forward(alice)

    bob2alice.expectMsgType[UpdateFulfillHtlc]
  }

  test("pending non-relayed fulfill htlcs will timeout upstream") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[ChannelErrorOccurred])

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val initialCommitTx = initialState.commitments.localCommit.publishableTxs.commitTx.tx
    val HtlcSuccessTx(_, htlcSuccessTx, _) = initialState.commitments.localCommit.publishableTxs.htlcTxsAndSigs.head.txinfo

    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // We simulate a pending fulfill on that HTLC but not relayed.
    // When it is close to expiring upstream, we should close the channel.
    bob.underlyingActor.nodeParams.db.pendingRelay.addPendingRelay(initialState.channelId, CMD_FULFILL_HTLC(htlc.id, r, commit = true))
    sender.send(bob, CurrentBlockCount((htlc.cltvExpiry - bob.underlyingActor.nodeParams.fulfillSafetyBeforeTimeout).toLong))

    val ChannelErrorOccurred(_, _, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccurred]
    assert(isFatal)
    assert(err.isInstanceOf[HtlcsWillTimeoutUpstream])

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
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // We simulate a pending failure on that HTLC.
    // Even if we get close to expiring upstream we shouldn't close the channel, because we have nothing to lose.
    sender.send(bob, CMD_FAIL_HTLC(htlc.id, Right(IncorrectOrUnknownPaymentDetails(0 msat, 0))))
    sender.send(bob, CurrentBlockCount((htlc.cltvExpiry - bob.underlyingActor.nodeParams.fulfillSafetyBeforeTimeout).toLong))

    bob2blockchain.expectNoMsg(250 millis)
    alice2blockchain.expectNoMsg(250 millis)
  }

  test("handle feerate changes while offline (funder scenario)") { f =>
    import f._

    // we only close channels on feerate mismatch if there are HTLCs at risk in the commitment
    addHtlc(125000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    testHandleFeerateFunder(f, shouldClose = true)
  }

  test("handle feerate changes while offline without HTLCs (funder scenario)") { f =>
    testHandleFeerateFunder(f, shouldClose = false)
  }

  def testHandleFeerateFunder(f: FixtureParam, shouldClose: Boolean): Unit = {
    import f._

    // we simulate a disconnection
    val sender = TestProbe()
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    val aliceStateData = alice.stateData.asInstanceOf[DATA_NORMAL]
    val aliceCommitTx = aliceStateData.commitments.localCommit.publishableTxs.commitTx.tx

    val currentFeeratePerKw = aliceStateData.commitments.localCommit.spec.feeratePerKw
    // we receive a feerate update that makes our current feerate too low compared to the network's (we multiply by 1.1
    // to ensure the network's feerate is 10% above our threshold).
    val networkFeeratePerKw = currentFeeratePerKw * (1.1 / alice.underlyingActor.nodeParams.onChainFeeConf.maxFeerateMismatch.ratioLow)
    val networkFeerate = FeeratesPerKw.single(networkFeeratePerKw)

    // alice is funder
    sender.send(alice, CurrentFeerates(networkFeerate))
    if (shouldClose) {
      alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))
    } else {
      alice2blockchain.expectNoMsg()
    }
  }

  test("handle feerate changes while offline (don't close on mismatch)", Tag("disable-offline-mismatch")) { f =>
    import f._

    // we only close channels on feerate mismatch if there are HTLCs at risk in the commitment
    addHtlc(125000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // we simulate a disconnection
    val sender = TestProbe()
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    val aliceStateData = alice.stateData.asInstanceOf[DATA_NORMAL]
    val currentFeeratePerKw = aliceStateData.commitments.localCommit.spec.feeratePerKw
    // we receive a feerate update that makes our current feerate too low compared to the network's (we multiply by 1.1
    // to ensure the network's feerate is 10% above our threshold).
    val networkFeeratePerKw = currentFeeratePerKw * (1.1 / alice.underlyingActor.nodeParams.onChainFeeConf.maxFeerateMismatch.ratioLow)
    val networkFeerate = FeeratesPerKw.single(networkFeeratePerKw)

    // this time Alice will ignore feerate changes for the offline channel
    sender.send(alice, CurrentFeerates(networkFeerate))
    alice2blockchain.expectNoMsg()
    alice2bob.expectNoMsg()
  }

  test("handle feerate changes while offline (update at reconnection)") { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    val localFeeratePerKw = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.feeratePerKw
    val networkFeeratePerKw = localFeeratePerKw * 2
    val networkFeerate = FeeratesPerKw.single(networkFeeratePerKw)

    // Alice ignores feerate changes while offline
    alice.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator].setFeerate(networkFeerate)
    sender.send(alice, CurrentFeerates(networkFeerate))
    alice2blockchain.expectNoMsg()
    alice2bob.expectNoMsg()

    // then we reconnect them; Alice should send the feerate changes to Bob
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    // note that we don't forward the channel_reestablish so that only alice reaches NORMAL state, it facilitates the test below
    bob2alice.forward(alice)

    alice2bob.expectMsgType[FundingLocked] // since the channel's commitment hasn't been updated, we re-send funding_locked
    alice2bob.expectMsg(UpdateFee(channelId(alice), networkFeeratePerKw))
  }

  test("handle feerate changes while offline (fundee scenario)") { f =>
    import f._

    // we only close channels on feerate mismatch if there are HTLCs at risk in the commitment
    addHtlc(125000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    testHandleFeerateFundee(f, shouldClose = true)
  }

  test("handle feerate changes while offline without HTLCs (fundee scenario)") { f =>
    testHandleFeerateFundee(f, shouldClose = false)
  }

  def testHandleFeerateFundee(f: FixtureParam, shouldClose: Boolean): Unit = {
    import f._

    // we simulate a disconnection
    val sender = TestProbe()
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    val bobStateData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val bobCommitTx = bobStateData.commitments.localCommit.publishableTxs.commitTx.tx

    val currentFeeratePerKw = bobStateData.commitments.localCommit.spec.feeratePerKw
    // we receive a feerate update that makes our current feerate too low compared to the network's (we multiply by 1.1
    // to ensure the network's feerate is 10% above our threshold).
    val networkFeeratePerKw = currentFeeratePerKw * (1.1 / bob.underlyingActor.nodeParams.onChainFeeConf.maxFeerateMismatch.ratioLow)
    val networkFeerate = FeeratesPerKw.single(networkFeeratePerKw)

    // bob is fundee
    sender.send(bob, CurrentFeerates(networkFeerate))
    if (shouldClose) {
      bob2blockchain.expectMsg(PublishAsap(bobCommitTx))
    } else {
      bob2blockchain.expectNoMsg()
    }
  }

  test("re-send channel_update at reconnection for private channels") { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection / reconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)

    // at this point the channel isn't deeply buried: channel_update isn't sent again
    alice2bob.expectMsgType[FundingLocked]
    bob2alice.expectMsgType[FundingLocked]
    alice2bob.expectNoMsg()
    bob2alice.expectNoMsg()

    // we make the peers exchange a few messages
    addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // we simulate a disconnection / reconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)

    //  at this point the channel still isn't deeply buried: channel_update isn't sent again
    alice2bob.expectNoMsg()
    bob2alice.expectNoMsg()

    // funding tx gets 6 confirmations, channel is private so there is no announcement sigs
    sender.send(alice, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null))
    sender.send(bob, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42, null))
    alice2bob.expectMsgType[ChannelUpdate]
    bob2alice.expectMsgType[ChannelUpdate]

    // we get disconnected again
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)

    // this time peers re-send their channel_update
    alice2bob.expectMsgType[ChannelUpdate]
    bob2alice.expectMsgType[ChannelUpdate]
  }
}
