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

import akka.actor.ActorRef
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ScriptFlags, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob, TestFeeEstimator}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.blockchain.{CurrentBlockCount, CurrentFeerates}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishRawTx, PublishTx}
import fr.acinq.eclair.channel.states.StateTestsBase
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.HtlcSuccessTx
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshiLong, TestConstants, TestKitBaseClass, randomBytes32}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class OfflineStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsBase {

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

  val aliceInit = Init(TestConstants.Alice.nodeParams.features)
  val bobInit = Init(TestConstants.Bob.nodeParams.features)

  test("re-send lost htlc and signature after first commitment") { f =>
    import f._
    // alice         bob
    //   |            |
    //   |--- add --->|
    //   |--- sig --X |
    //   |            |
    val sender = TestProbe()
    alice ! CMD_ADD_HTLC(sender.ref, 1000000 msat, ByteVector32.Zeroes, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    // bob receives the htlc
    alice2bob.forward(bob)
    alice ! CMD_SIGN()
    val sig = alice2bob.expectMsgType[CommitSig]
    // bob doesn't receive the sig
    disconnect(alice, bob)

    val (aliceCurrentPerCommitmentPoint, bobCurrentPerCommitmentPoint) = reconnect(alice, bob, alice2bob, bob2alice)
    val reestablishA = alice2bob.expectMsg(ChannelReestablish(htlc.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint))
    val reestablishB = bob2alice.expectMsg(ChannelReestablish(htlc.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint))
    alice2bob.forward(bob, reestablishA)
    bob2alice.forward(alice, reestablishB)

    // both nodes will send the funding_locked message because all updates have been cancelled
    alice2bob.expectMsgType[FundingLocked]
    bob2alice.expectMsgType[FundingLocked]

    // alice will re-send the update and the sig
    alice2bob.expectMsg(htlc)
    alice2bob.expectMsg(sig)
    alice2bob.forward(bob, htlc)
    alice2bob.forward(bob, sig)

    // and bob will reply with a revocation
    val rev = bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice, rev)
    // then bob signs
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    // and alice answers with her revocation which completes the commitment update
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)

    alice2bob.expectNoMsg(500 millis)
    bob2alice.expectNoMsg(500 millis)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localNextHtlcId == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextHtlcId == 1)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  test("re-send lost revocation") { f =>
    import f._
    // alice         bob
    //   |            |
    //   |--- add --->|
    //   |--- sig --->|
    //   | X-- rev ---|
    //   | X-- sig ---|
    val sender = TestProbe()
    alice ! CMD_ADD_HTLC(ActorRef.noSender, 1000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    // bob receives the htlc and the signature
    alice2bob.forward(bob, htlc)
    alice ! CMD_SIGN()
    val sigA = alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob, sigA)

    // bob received the signature, but alice won't receive the revocation
    val revB = bob2alice.expectMsgType[RevokeAndAck]
    val sigB = bob2alice.expectMsgType[CommitSig]
    bob2alice.expectNoMsg(500 millis)

    disconnect(alice, bob)
    val (aliceCurrentPerCommitmentPoint, bobCurrentPerCommitmentPoint) = reconnect(alice, bob, alice2bob, bob2alice)
    val reestablishA = alice2bob.expectMsg(ChannelReestablish(htlc.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint))
    val reestablishB = bob2alice.expectMsg(ChannelReestablish(htlc.channelId, 2, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint))
    alice2bob.forward(bob, reestablishA)
    bob2alice.forward(alice, reestablishB)

    // bob re-sends the lost revocation
    bob2alice.expectMsg(revB)
    bob2alice.forward(alice, revB)
    // and a signature
    bob2alice.expectMsg(sigB)

    alice2bob.expectNoMsg(500 millis)
    bob2alice.expectNoMsg(500 millis)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localNextHtlcId == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextHtlcId == 1)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  test("re-send lost signature after revocation") { f =>
    import f._
    // alice         bob
    //   |            |
    //   |--- add --->|
    //   |--- sig --->|
    //   |<--- rev ---|
    //   | X-- sig ---|
    val sender = TestProbe()
    alice ! CMD_ADD_HTLC(ActorRef.noSender, 1000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    // bob receives the htlc and the signature
    alice2bob.forward(bob, htlc)
    alice ! CMD_SIGN()
    val sigA = alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob, sigA)

    // bob sends a revocation and a signature
    val revB = bob2alice.expectMsgType[RevokeAndAck]
    val sigB = bob2alice.expectMsgType[CommitSig]
    bob2alice.expectNoMsg(500 millis)

    // alice receives the revocation but not the signature
    bob2alice.forward(alice, revB)
    disconnect(alice, bob)

    {
      val (aliceCurrentPerCommitmentPoint, bobCurrentPerCommitmentPoint) = reconnect(alice, bob, alice2bob, bob2alice)
      val reestablishA = alice2bob.expectMsg(ChannelReestablish(htlc.channelId, 1, 1, revB.perCommitmentSecret, aliceCurrentPerCommitmentPoint))
      val reestablishB = bob2alice.expectMsg(ChannelReestablish(htlc.channelId, 2, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint))
      alice2bob.forward(bob, reestablishA)
      bob2alice.forward(alice, reestablishB)
    }

    // bob re-sends the lost signature (but not the revocation)
    bob2alice.expectMsg(sigB)

    alice2bob.expectNoMsg(500 millis)
    bob2alice.expectNoMsg(500 millis)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localNextHtlcId == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextHtlcId == 1)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)

    // alice         bob
    //   |            |
    //   |--- add --->|
    //   |--- sig --->|
    //   |<--- rev ---|
    //   |<--- sig ---|
    //   |--- rev --X |
    bob2alice.forward(alice, sigB)
    bob2alice.expectNoMsg(500 millis)
    val revA = alice2bob.expectMsgType[RevokeAndAck]
    disconnect(alice, bob)

    {
      val (aliceCurrentPerCommitmentPoint, bobCurrentPerCommitmentPoint) = reconnect(alice, bob, alice2bob, bob2alice)
      val reestablishA = alice2bob.expectMsg(ChannelReestablish(htlc.channelId, 2, 1, revB.perCommitmentSecret, aliceCurrentPerCommitmentPoint))
      val reestablishB = bob2alice.expectMsg(ChannelReestablish(htlc.channelId, 2, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint))
      alice2bob.forward(bob, reestablishA)
      bob2alice.forward(alice, reestablishB)
    }

    // alice re-sends the lost revocation
    alice2bob.expectMsg(revA)
    alice2bob.expectNoMsg(500 millis)

    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  test("discover that we have a revoked commitment") { f =>
    import f._

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
    disconnect(alice, bob)

    // then we manually replace alice's state with an older one
    alice.setState(OFFLINE, oldStateData)

    // then we reconnect them
    reconnect(alice, bob, alice2bob, bob2alice)

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]

    // alice then realizes it has an old state...
    bob2alice.forward(alice)
    // ... and ask bob to publish its current commitment
    val error = alice2bob.expectMsgType[Error]
    assert(error === Error(channelId(alice), PleasePublishYourCommitment(channelId(alice)).getMessage))

    // alice now waits for bob to publish its commitment
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob is nice and publishes its commitment
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // alice is able to claim its main output
    val claimMainOutput = alice2blockchain.expectMsgType[PublishRawTx].tx
    Transaction.correctlySpends(claimMainOutput, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("discover that they have a more recent commit than the one we know") { f =>
    import f._

    // we start by storing the current state
    val oldStateData = alice.stateData
    // then we add an htlc and sign it
    addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SIGN()
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // alice will receive neither the revocation nor the commit sig
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.expectMsgType[CommitSig]

    // we simulate a disconnection
    disconnect(alice, bob)

    // then we manually replace alice's state with an older one
    alice.setState(OFFLINE, oldStateData)

    // then we reconnect them
    reconnect(alice, bob, alice2bob, bob2alice)

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]

    // alice then realizes it has an old state...
    bob2alice.forward(alice)
    // ... and ask bob to publish its current commitment
    val error = alice2bob.expectMsgType[Error]
    assert(error === Error(channelId(alice), PleasePublishYourCommitment(channelId(alice)).getMessage))

    // alice now waits for bob to publish its commitment
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob is nice and publishes its commitment
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // alice is able to claim its main output
    val claimMainOutput = alice2blockchain.expectMsgType[PublishRawTx].tx
    Transaction.correctlySpends(claimMainOutput, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("counterparty lies about having a more recent commitment") { f =>
    import f._
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    // we simulate a disconnection followed by a reconnection
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    // bob sends an invalid channel_reestablish
    val invalidReestablish = bob2alice.expectMsgType[ChannelReestablish].copy(nextRemoteRevocationNumber = 42)

    // alice then finds out bob is lying
    bob2alice.send(alice, invalidReestablish)
    val error = alice2bob.expectMsgType[Error]
    assert(alice2blockchain.expectMsgType[PublishRawTx].tx === aliceCommitTx)
    val claimMainOutput = alice2blockchain.expectMsgType[PublishRawTx].tx
    Transaction.correctlySpends(claimMainOutput, aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    assert(error === Error(channelId(alice), InvalidRevokedCommitProof(channelId(alice), 0, 42, invalidReestablish.yourLastPerCommitmentSecret).getMessage))
  }

  test("change relay fee while offline") { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection
    disconnect(alice, bob)

    // alice and bob will not announce that their channel is OFFLINE
    channelUpdateListener.expectNoMsg(300 millis)

    // we make alice update here relay fee
    alice ! CMD_UPDATE_RELAY_FEE(sender.ref, 4200 msat, 123456)
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_RELAY_FEE]]

    // alice doesn't broadcast the new channel_update yet
    channelUpdateListener.expectNoMsg(300 millis)

    // then we reconnect them
    reconnect(alice, bob, alice2bob, bob2alice)

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
    disconnect(alice, bob)

    // alice and bob will not announce that their channel is OFFLINE
    channelUpdateListener.expectNoMsg(300 millis)

    // we attempt to send a payment
    alice ! CMD_ADD_HTLC(sender.ref, 4200 msat, randomBytes32(), CltvExpiry(123456), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    sender.expectMsgType[RES_ADD_FAILED[ChannelUnavailable]]

    // alice will broadcast a new disabled channel_update
    val update = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(!Announcements.isEnabled(update.channelUpdate.channelFlags))
  }

  test("replay pending commands when going back to NORMAL") { f =>
    import f._
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    disconnect(alice, bob)

    // We simulate a pending fulfill
    bob.underlyingActor.nodeParams.db.pendingCommands.addSettlementCommand(initialState.channelId, CMD_FULFILL_HTLC(htlc.id, r, commit = true))

    // then we reconnect them
    reconnect(alice, bob, alice2bob, bob2alice)

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
    alice ! CMD_CLOSE(sender.ref, None, None)
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)

    disconnect(alice, bob)

    // We simulate a pending fulfill
    bob.underlyingActor.nodeParams.db.pendingCommands.addSettlementCommand(initialState.channelId, CMD_FULFILL_HTLC(htlc.id, r, commit = true))

    // then we reconnect them
    reconnect(alice, bob, alice2bob, bob2alice)

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
    bob2alice.expectMsgType[CommitSig]
  }

  test("pending non-relayed fulfill htlcs will timeout upstream") { f =>
    import f._
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[ChannelErrorOccurred])

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val initialCommitTx = initialState.commitments.localCommit.publishableTxs.commitTx.tx
    val HtlcSuccessTx(_, htlcSuccessTx, _, _) = initialState.commitments.localCommit.publishableTxs.htlcTxsAndSigs.head.txinfo

    disconnect(alice, bob)

    // We simulate a pending fulfill on that HTLC but not relayed.
    // When it is close to expiring upstream, we should close the channel.
    bob.underlyingActor.nodeParams.db.pendingCommands.addSettlementCommand(initialState.channelId, CMD_FULFILL_HTLC(htlc.id, r, commit = true))
    bob ! CurrentBlockCount((htlc.cltvExpiry - bob.underlyingActor.nodeParams.fulfillSafetyBeforeTimeout).toLong)

    val ChannelErrorOccurred(_, _, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccurred]
    assert(isFatal)
    assert(err.isInstanceOf[HtlcsWillTimeoutUpstream])

    assert(bob2blockchain.expectMsgType[PublishRawTx].tx === initialCommitTx)
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId === initialCommitTx.txid)
    bob2blockchain.expectMsgType[WatchTxConfirmed] // main delayed
    bob2blockchain.expectMsgType[WatchOutputSpent] // htlc

    assert(bob2blockchain.expectMsgType[PublishRawTx].tx === initialCommitTx)
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    assert(bob2blockchain.expectMsgType[PublishRawTx].tx.txOut === htlcSuccessTx.txOut)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId === initialCommitTx.txid)
    bob2blockchain.expectMsgType[WatchTxConfirmed] // main delayed
    bob2blockchain.expectMsgType[WatchOutputSpent] // htlc
    bob2blockchain.expectNoMsg(500 millis)
  }

  test("pending non-relayed fail htlcs will timeout upstream") { f =>
    import f._
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    disconnect(alice, bob)

    // We simulate a pending failure on that HTLC.
    // Even if we get close to expiring upstream we shouldn't close the channel, because we have nothing to lose.
    bob ! CMD_FAIL_HTLC(htlc.id, Right(IncorrectOrUnknownPaymentDetails(0 msat, 0)))
    bob ! CurrentBlockCount((htlc.cltvExpiry - bob.underlyingActor.nodeParams.fulfillSafetyBeforeTimeout).toLong)

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
    disconnect(alice, bob)

    val aliceStateData = alice.stateData.asInstanceOf[DATA_NORMAL]
    val aliceCommitTx = aliceStateData.commitments.localCommit.publishableTxs.commitTx.tx

    val currentFeeratePerKw = aliceStateData.commitments.localCommit.spec.feeratePerKw
    // we receive a feerate update that makes our current feerate too low compared to the network's (we multiply by 1.1
    // to ensure the network's feerate is 10% above our threshold).
    val networkFeeratePerKw = currentFeeratePerKw * (1.1 / alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(Bob.nodeParams.nodeId).ratioLow)
    val networkFeerate = FeeratesPerKw.single(networkFeeratePerKw)

    // alice is funder
    alice ! CurrentFeerates(networkFeerate)
    if (shouldClose) {
      assert(alice2blockchain.expectMsgType[PublishRawTx].tx === aliceCommitTx)
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
    disconnect(alice, bob)

    val aliceStateData = alice.stateData.asInstanceOf[DATA_NORMAL]
    val currentFeeratePerKw = aliceStateData.commitments.localCommit.spec.feeratePerKw
    // we receive a feerate update that makes our current feerate too low compared to the network's (we multiply by 1.1
    // to ensure the network's feerate is 10% above our threshold).
    val networkFeeratePerKw = currentFeeratePerKw * (1.1 / alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(Bob.nodeParams.nodeId).ratioLow)
    val networkFeerate = FeeratesPerKw.single(networkFeeratePerKw)

    // this time Alice will ignore feerate changes for the offline channel
    alice ! CurrentFeerates(networkFeerate)
    alice2blockchain.expectNoMsg()
    alice2bob.expectNoMsg()
  }

  def testUpdateFeeOnReconnect(f: FixtureParam, shouldUpdateFee: Boolean): Unit = {
    import f._

    // we simulate a disconnection
    disconnect(alice, bob)

    val localFeeratePerKw = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.feeratePerKw
    val networkFeeratePerKw = localFeeratePerKw * 2
    val networkFeerate = FeeratesPerKw.single(networkFeeratePerKw)

    // Alice ignores feerate changes while offline
    alice.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator].setFeerate(networkFeerate)
    alice ! CurrentFeerates(networkFeerate)
    alice2blockchain.expectNoMsg()
    alice2bob.expectNoMsg()

    // then we reconnect them; Alice should send the feerate changes to Bob
    reconnect(alice, bob, alice2bob, bob2alice)

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)

    alice2bob.expectMsgType[FundingLocked] // since the channel's commitment hasn't been updated, we re-send funding_locked
    if (shouldUpdateFee) {
      alice2bob.expectMsg(UpdateFee(channelId(alice), networkFeeratePerKw))
    } else {
      alice2bob.expectMsgType[Shutdown]
      alice2bob.expectNoMsg(100 millis)
    }
  }

  test("handle feerate changes while offline (update at reconnection)") { f =>
    testUpdateFeeOnReconnect(f, shouldUpdateFee = true)
  }

  test("handle feerate changes while offline (shutdown sent, don't update at reconnection)") { f =>
    import f._

    // alice initiates a shutdown
    val sender = TestProbe()
    alice ! CMD_CLOSE(sender.ref, None, None)
    alice2bob.expectMsgType[Shutdown]

    testUpdateFeeOnReconnect(f, shouldUpdateFee = false)
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
    disconnect(alice, bob)

    val bobStateData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val bobCommitTx = bobStateData.commitments.localCommit.publishableTxs.commitTx.tx

    val currentFeeratePerKw = bobStateData.commitments.localCommit.spec.feeratePerKw
    // we receive a feerate update that makes our current feerate too low compared to the network's (we multiply by 1.1
    // to ensure the network's feerate is 10% above our threshold).
    val networkFeeratePerKw = currentFeeratePerKw * (1.1 / bob.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(Alice.nodeParams.nodeId).ratioLow)
    val networkFeerate = FeeratesPerKw.single(networkFeeratePerKw)

    // bob is fundee
    bob ! CurrentFeerates(networkFeerate)
    if (shouldClose) {
      assert(bob2blockchain.expectMsgType[PublishRawTx].tx === bobCommitTx)
    } else {
      bob2blockchain.expectNoMsg()
    }
  }

  test("re-send channel_update at reconnection for private channels") { f =>
    import f._

    // we simulate a disconnection / reconnection
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)
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
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)

    // at this point the channel still isn't deeply buried: channel_update isn't sent again
    alice2bob.expectNoMsg()
    bob2alice.expectNoMsg()

    // funding tx gets 6 confirmations, channel is private so there is no announcement sigs
    alice ! WatchFundingDeeplyBuriedTriggered(400000, 42, null)
    bob ! WatchFundingDeeplyBuriedTriggered(400000, 42, null)
    alice2bob.expectMsgType[ChannelUpdate]
    bob2alice.expectMsgType[ChannelUpdate]

    // we get disconnected again
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)

    // this time peers re-send their channel_update
    alice2bob.expectMsgType[ChannelUpdate]
    bob2alice.expectMsgType[ChannelUpdate]
    // and the channel is enabled
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
  }

  def disconnect(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel]): Unit = {
    alice ! INPUT_DISCONNECTED
    bob ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
  }

  def reconnect(alice: TestFSMRef[State, Data, Channel], bob: TestFSMRef[State, Data, Channel], alice2bob: TestProbe, bob2alice: TestProbe): (PublicKey, PublicKey) = {
    alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)

    val aliceCommitments = alice.stateData.asInstanceOf[HasCommitments].commitments
    val aliceCurrentPerCommitmentPoint = TestConstants.Alice.channelKeyManager.commitmentPoint(
      TestConstants.Alice.channelKeyManager.keyPath(aliceCommitments.localParams, aliceCommitments.channelVersion),
      aliceCommitments.localCommit.index)

    val bobCommitments = bob.stateData.asInstanceOf[HasCommitments].commitments
    val bobCurrentPerCommitmentPoint = TestConstants.Bob.channelKeyManager.commitmentPoint(
      TestConstants.Bob.channelKeyManager.keyPath(bobCommitments.localParams, bobCommitments.channelVersion),
      bobCommitments.localCommit.index)

    (aliceCurrentPerCommitmentPoint, bobCurrentPerCommitmentPoint)
  }

}
