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
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, CurrentFeerates}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx}
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.PimpTestFSM
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.Transactions.{ClaimHtlcTimeoutTx, ClaimRemoteAnchorTx, UnsignedHtlcSuccessTx}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, MilliSatoshiLong, TestConstants, TestKitBaseClass, TestUtils, randomBytes32}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class OfflineStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  /** If set, do not close channel in case of a fee mismatch when disconnected */
  val DisableOfflineMismatch = "disable_offline_mismatch"
  /** If set, channel_update will be ignored */
  val IgnoreChannelUpdates = "ignore_channel_updates"

  override def withFixture(test: OneArgTest): Outcome = {
    val aliceParams = Alice.nodeParams
      .modify(_.onChainFeeConf.closeOnOfflineMismatch).setToIf(test.tags.contains(DisableOfflineMismatch))(false)
    val setup = init(nodeParamsA = aliceParams, tags = test.tags)
    import setup._
    within(30 seconds) {
      reachNormal(setup, tags = test.tags)
      if (test.tags.contains(IgnoreChannelUpdates)) {
        setup.alice2bob.ignoreMsg({ case _: ChannelUpdate => true })
        setup.bob2alice.ignoreMsg({ case _: ChannelUpdate => true })
      }
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(setup))
    }
  }

  private def lastFundingLockedTlvs(commitments: Commitments): Set[ChannelReestablishTlv] =
    commitments.lastLocalLocked_opt.map(c => ChannelReestablishTlv.MyCurrentFundingLockedTlv(c.fundingTxId)).toSet ++
      commitments.lastRemoteLocked_opt.map(c => ChannelReestablishTlv.YourLastFundingLockedTlv(c.fundingTxId)).toSet

  test("reconnect after creating channel", Tag(IgnoreChannelUpdates)) { f =>
    import f._

    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)
    val channelId = alice2bob.expectMsgType[ChannelReestablish].channelId
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)

    // The channel is ready to process payments.
    alicePeer.fishForMessage() {
      case e: ChannelReadyForPayments =>
        assert(e.fundingTxIndex == 0)
        assert(e.channelId == channelId)
        true
      case _ => false
    }
    bobPeer.fishForMessage() {
      case e: ChannelReadyForPayments =>
        assert(e.fundingTxIndex == 0)
        assert(e.channelId == channelId)
        true
      case _ => false
    }
  }

  test("re-send lost htlc and signature after first commitment", Tag(IgnoreChannelUpdates)) { f =>
    import f._
    // alice         bob
    //   |            |
    //   |--- add --->|
    //   |--- sig --X |
    //   |            |
    val sender = TestProbe()
    alice ! CMD_ADD_HTLC(sender.ref, 1000000 msat, ByteVector32.Zeroes, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, Reputation.Score.max, None, localOrigin(sender.ref))
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    // bob receives the htlc
    alice2bob.forward(bob)
    alice ! CMD_SIGN()
    val sig = alice2bob.expectMsgType[CommitSig]
    // bob doesn't receive the sig
    disconnect(alice, bob)

    val (aliceCurrentPerCommitmentPoint, bobCurrentPerCommitmentPoint) = reconnect(alice, bob, alice2bob, bob2alice)
    val reestablishA = alice2bob.expectMsg(ChannelReestablish(htlc.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint, TlvStream(lastFundingLockedTlvs(alice.stateData.asInstanceOf[DATA_NORMAL].commitments))))
    val reestablishB = bob2alice.expectMsg(ChannelReestablish(htlc.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint, TlvStream(lastFundingLockedTlvs(bob.stateData.asInstanceOf[DATA_NORMAL].commitments))))
    alice2bob.forward(bob, reestablishA)
    bob2alice.forward(alice, reestablishB)

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

    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.localNextHtlcId == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.remoteNextHtlcId == 1)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  test("re-send lost revocation", Tag(IgnoreChannelUpdates)) { f =>
    import f._
    // alice         bob
    //   |            |
    //   |--- add --->|
    //   |--- sig --->|
    //   | X-- rev ---|
    //   | X-- sig ---|
    val sender = TestProbe()
    alice ! CMD_ADD_HTLC(ActorRef.noSender, 1000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, Reputation.Score.max, None, localOrigin(sender.ref))
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    // bob receives the htlc and the signature
    alice2bob.forward(bob, htlc)
    alice ! CMD_SIGN()
    val sigA = alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob, sigA)

    // bob received the signature, but alice won't receive the revocation
    val revB = bob2alice.expectMsgType[RevokeAndAck]
    val sigB = bob2alice.expectMsgType[CommitSig]
    bob2alice.expectNoMessage(100 millis)

    disconnect(alice, bob)
    val (aliceCurrentPerCommitmentPoint, bobCurrentPerCommitmentPoint) = reconnect(alice, bob, alice2bob, bob2alice)
    val reestablishA = alice2bob.expectMsg(ChannelReestablish(htlc.channelId, 1, 0, PrivateKey(ByteVector32.Zeroes), aliceCurrentPerCommitmentPoint, TlvStream(lastFundingLockedTlvs(alice.stateData.asInstanceOf[DATA_NORMAL].commitments))))
    val reestablishB = bob2alice.expectMsg(ChannelReestablish(htlc.channelId, 2, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint, TlvStream(lastFundingLockedTlvs(bob.stateData.asInstanceOf[DATA_NORMAL].commitments))))
    alice2bob.forward(bob, reestablishA)
    bob2alice.forward(alice, reestablishB)

    // bob re-sends the lost revocation
    bob2alice.expectMsg(revB)
    bob2alice.forward(alice, revB)
    // and a signature
    bob2alice.expectMsg(sigB)

    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.localNextHtlcId == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.remoteNextHtlcId == 1)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  test("re-send lost signature after revocation", Tag(IgnoreChannelUpdates)) { f =>
    import f._
    // alice         bob
    //   |            |
    //   |--- add --->|
    //   |--- sig --->|
    //   |<--- rev ---|
    //   | X-- sig ---|
    val sender = TestProbe()
    alice ! CMD_ADD_HTLC(ActorRef.noSender, 1000000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, Reputation.Score.max, None, localOrigin(sender.ref))
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    // bob receives the htlc and the signature
    alice2bob.forward(bob, htlc)
    alice ! CMD_SIGN()
    val sigA = alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob, sigA)

    // bob sends a revocation and a signature
    val revB = bob2alice.expectMsgType[RevokeAndAck]
    val sigB = bob2alice.expectMsgType[CommitSig]
    bob2alice.expectNoMessage(100 millis)

    // alice receives the revocation but not the signature
    bob2alice.forward(alice, revB)
    disconnect(alice, bob)

    {
      val (aliceCurrentPerCommitmentPoint, bobCurrentPerCommitmentPoint) = reconnect(alice, bob, alice2bob, bob2alice)
      val reestablishA = alice2bob.expectMsg(ChannelReestablish(htlc.channelId, 1, 1, revB.perCommitmentSecret, aliceCurrentPerCommitmentPoint, TlvStream(lastFundingLockedTlvs(alice.stateData.asInstanceOf[DATA_NORMAL].commitments))))
      val reestablishB = bob2alice.expectMsg(ChannelReestablish(htlc.channelId, 2, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint, TlvStream(lastFundingLockedTlvs(bob.stateData.asInstanceOf[DATA_NORMAL].commitments))))
      alice2bob.forward(bob, reestablishA)
      bob2alice.forward(alice, reestablishB)
    }

    // bob re-sends the lost signature (but not the revocation)
    bob2alice.expectMsg(sigB)

    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.localNextHtlcId == 1)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.remoteNextHtlcId == 1)
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
    bob2alice.expectNoMessage(100 millis)
    val revA = alice2bob.expectMsgType[RevokeAndAck]
    disconnect(alice, bob)

    {
      val (aliceCurrentPerCommitmentPoint, bobCurrentPerCommitmentPoint) = reconnect(alice, bob, alice2bob, bob2alice)
      val reestablishA = alice2bob.expectMsg(ChannelReestablish(htlc.channelId, 2, 1, revB.perCommitmentSecret, aliceCurrentPerCommitmentPoint, TlvStream(lastFundingLockedTlvs(alice.stateData.asInstanceOf[DATA_NORMAL].commitments))))
      val reestablishB = bob2alice.expectMsg(ChannelReestablish(htlc.channelId, 2, 0, PrivateKey(ByteVector32.Zeroes), bobCurrentPerCommitmentPoint, TlvStream(lastFundingLockedTlvs(bob.stateData.asInstanceOf[DATA_NORMAL].commitments))))
      alice2bob.forward(bob, reestablishA)
      bob2alice.forward(alice, reestablishB)
    }

    // alice re-sends the lost revocation
    alice2bob.expectMsg(revA)
    alice2bob.expectNoMessage(100 millis)

    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  test("resume htlc settlement", Tag(IgnoreChannelUpdates)) { f =>
    import f._

    // Successfully send a first payment.
    val (r1, htlc1) = addHtlc(15_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlc1.id, r1, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // Send a second payment and disconnect right after the fulfill was signed.
    val (r2, htlc2) = addHtlc(25_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    fulfillHtlc(htlc2.id, r2, alice, bob, alice2bob, bob2alice)
    val sender = TestProbe()
    alice ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    val revB = bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.expectMsgType[CommitSig]
    disconnect(alice, bob)

    reconnect(alice, bob, alice2bob, bob2alice)
    val reestablishA = alice2bob.expectMsgType[ChannelReestablish]
    assert(reestablishA.nextLocalCommitmentNumber == 4)
    assert(reestablishA.nextRemoteRevocationNumber == 3)
    val reestablishB = bob2alice.expectMsgType[ChannelReestablish]
    assert(reestablishB.nextLocalCommitmentNumber == 5)
    assert(reestablishB.nextRemoteRevocationNumber == 3)

    bob2alice.forward(alice, reestablishB)
    // alice does not re-send messages bob already received
    alice2bob.expectNoMessage(100 millis)

    alice2bob.forward(bob, reestablishA)
    // bob re-sends its revocation and signature, alice then completes the update
    bob2alice.expectMsg(revB)
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)

    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommitIndex == 4)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommitIndex == 4)
  }

  test("reconnect with an outdated commitment", Tag(IgnoreChannelUpdates), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
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
    val reestablishA = alice2bob.expectMsgType[ChannelReestablish]
    val reestablishB = bob2alice.expectMsgType[ChannelReestablish]

    // alice then realizes it has an old state...
    bob2alice.forward(alice, reestablishB)
    // ... and ask bob to publish its current commitment
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(channelId(alice), PleasePublishYourCommitment(channelId(alice)).getMessage))
    alice2bob.forward(bob)

    // alice now waits for bob to publish its commitment
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob publishes its commitment when it detects that alice has an outdated commitment
    alice2bob.forward(bob, reestablishA)
    val bobCommitTx = bob2blockchain.expectMsgType[PublishFinalTx].tx
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // alice is able to claim its main output
    val claimMainOutput = alice2blockchain.expectMsgType[PublishFinalTx].tx
    Transaction.correctlySpends(claimMainOutput, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("reconnect with an outdated commitment (but counterparty can't tell)", Tag(IgnoreChannelUpdates), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
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

    // we keep track of bob commitment tx for later
    val bobCommitTx = bob.signCommitTx()

    // we simulate a disconnection
    disconnect(alice, bob)

    // then we manually replace alice's state with an older one
    alice.setState(OFFLINE, oldStateData)

    // then we reconnect them
    reconnect(alice, bob, alice2bob, bob2alice)

    val reestablishA = alice2bob.expectMsgType[ChannelReestablish]
    val reestablishB = bob2alice.expectMsgType[ChannelReestablish]

    // bob cannot detect that alice is late (because alice has just missed one state), so it starts normally
    alice2bob.forward(bob, reestablishA)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.expectMsgType[CommitSig]
    bob2alice.expectNoMessage(100 millis)
    bob2blockchain.expectNoMessage(100 millis)

    // alice realizes she has an old state when receiving Bob's reestablish
    bob2alice.forward(alice, reestablishB)
    // alice asks bob to publish its current commitment
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(channelId(alice), PleasePublishYourCommitment(channelId(alice)).getMessage))
    alice2bob.forward(bob)

    // alice now waits for bob to publish its commitment
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob is nice and publishes its commitment
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // alice is able to claim its main output
    val claimMainOutput = alice2blockchain.expectMsgType[PublishFinalTx].tx
    Transaction.correctlySpends(claimMainOutput, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("counterparty lies about having a more recent commitment and publishes current commitment", Tag(IgnoreChannelUpdates), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    // the current state contains a pending htlc
    addHtlc(250_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val bobCommitTx = bob.signCommitTx()

    // we simulate a disconnection followed by a reconnection
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)
    // bob sends an invalid channel_reestablish
    alice2bob.expectMsgType[ChannelReestablish]
    val invalidReestablish = bob2alice.expectMsgType[ChannelReestablish].copy(nextRemoteRevocationNumber = 42)

    // alice then asks bob to publish its commitment to find out if bob is lying
    bob2alice.send(alice, invalidReestablish)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(channelId(alice), PleasePublishYourCommitment(channelId(alice)).getMessage))
    // alice now waits for bob to publish its commitment
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob publishes the latest commitment
    alice ! WatchFundingSpentTriggered(bobCommitTx)

    // alice is able to claim her main output and the htlc (once it times out)
    alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMainOutput = alice2blockchain.expectMsgType[PublishFinalTx].tx
    Transaction.correctlySpends(claimMainOutput, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val claimHtlc = alice2blockchain.expectReplaceableTxPublished[ClaimHtlcTimeoutTx]
    Transaction.correctlySpends(claimHtlc.sign(), bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  }

  test("counterparty lies about having a more recent commitment and publishes revoked commitment", Tag(IgnoreChannelUpdates), Tag(ChannelStateTestsTags.StaticRemoteKey), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._

    // we sign a new commitment to make sure the first one is revoked
    val bobRevokedCommitTx = bob.signCommitTx()
    addHtlc(250_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // we simulate a disconnection followed by a reconnection
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)
    // bob sends an invalid channel_reestablish
    alice2bob.expectMsgType[ChannelReestablish]
    val invalidReestablish = bob2alice.expectMsgType[ChannelReestablish].copy(nextLocalCommitmentNumber = 42)

    // alice then asks bob to publish its commitment to find out if bob is lying
    bob2alice.send(alice, invalidReestablish)
    val error = alice2bob.expectMsgType[Error]
    assert(error == Error(channelId(alice), PleasePublishYourCommitment(channelId(alice)).getMessage))
    // alice now waits for bob to publish its commitment
    alice2blockchain.expectNoMessage(100 millis)
    awaitCond(alice.stateName == WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT)

    // bob publishes the revoked commitment
    alice ! WatchFundingSpentTriggered(bobRevokedCommitTx)

    // alice is able to claim all outputs
    assert(bobRevokedCommitTx.txOut.length == 4)
    val claimMainOutput = alice2blockchain.expectMsgType[PublishFinalTx].tx
    Transaction.correctlySpends(claimMainOutput, bobRevokedCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val claimRevokedOutput = alice2blockchain.expectMsgType[PublishFinalTx].tx
    Transaction.correctlySpends(claimRevokedOutput, bobRevokedCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    assert(claimRevokedOutput.txIn.head.outPoint.index != claimMainOutput.txIn.head.outPoint.index)
  }

  test("change relay fee while offline", Tag(IgnoreChannelUpdates)) { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection
    disconnect(alice, bob)

    // alice and bob will not announce that their channel is OFFLINE
    channelUpdateListener.expectNoMessage(300 millis)

    // we make alice update here relay fee
    alice ! CMD_UPDATE_RELAY_FEE(sender.ref, 4200 msat, 123456)
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_RELAY_FEE]]

    // alice doesn't broadcast the new channel_update yet
    channelUpdateListener.expectNoMessage(300 millis)

    // then we reconnect them
    reconnect(alice, bob, alice2bob, bob2alice)

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    // note that we don't forward the channel_reestablish so that only alice reaches NORMAL state, it facilitates the test below
    bob2alice.forward(alice)

    // then alice reaches NORMAL state, and after a delay she broadcasts the channel_update
    val channelUpdate = channelUpdateListener.expectMsgType[LocalChannelUpdate](20 seconds).channelUpdate
    assert(channelUpdate.feeBaseMsat == 4200.msat)
    assert(channelUpdate.feeProportionalMillionths == 123456)
    assert(channelUpdate.channelFlags.isEnabled)

    // no more messages
    channelUpdateListener.expectNoMessage(300 millis)
  }

  test("broadcast disabled channel_update while offline") { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection
    disconnect(alice, bob)

    // alice and bob will not announce that their channel is OFFLINE
    channelUpdateListener.expectNoMessage(300 millis)

    // we attempt to send a payment
    alice ! CMD_ADD_HTLC(sender.ref, 4200 msat, randomBytes32(), CltvExpiry(123456), TestConstants.emptyOnionPacket, None, Reputation.Score.max, None, localOrigin(sender.ref))
    sender.expectMsgType[RES_ADD_FAILED[ChannelUnavailable]]

    // alice will broadcast a new disabled channel_update
    val update = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(!update.channelUpdate.channelFlags.isEnabled)
  }

  test("replay pending commands when going back to NORMAL", Tag(IgnoreChannelUpdates)) { f =>
    import f._
    val (r, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    disconnect(alice, bob)

    // We simulate a pending fulfill
    bob.underlyingActor.nodeParams.db.pendingCommands.addSettlementCommand(initialState.channelId, CMD_FULFILL_HTLC(htlc.id, r, None, commit = true))

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
    bob.underlyingActor.nodeParams.db.pendingCommands.addSettlementCommand(initialState.channelId, CMD_FULFILL_HTLC(htlc.id, r, None, commit = true))

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
    val (r, htlc) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    val listener = TestProbe()
    bob.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelErrorOccurred])

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val initialCommitTx = bob.signCommitTx()
    val htlcSuccessTx = bob.htlcTxs().head
    assert(htlcSuccessTx.isInstanceOf[UnsignedHtlcSuccessTx])

    disconnect(alice, bob)

    // We simulate a pending fulfill on that HTLC but not relayed.
    // When it is close to expiring upstream, we should close the channel.
    bob.underlyingActor.nodeParams.db.pendingCommands.addSettlementCommand(initialState.channelId, CMD_FULFILL_HTLC(htlc.id, r, None, commit = true))
    bob ! CurrentBlockHeight(htlc.cltvExpiry.blockHeight - bob.underlyingActor.nodeParams.channelConf.fulfillSafetyBeforeTimeout.toInt)

    val ChannelErrorOccurred(_, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccurred]
    assert(isFatal)
    assert(err.isInstanceOf[HtlcsWillTimeoutUpstream])

    bob2blockchain.expectFinalTxPublished(initialCommitTx.txid)
    val mainDelayedTx = bob2blockchain.expectFinalTxPublished("local-main-delayed")
    bob2blockchain.expectWatchTxConfirmed(initialCommitTx.txid)
    bob2blockchain.expectWatchOutputSpent(mainDelayedTx.input)
    bob2blockchain.expectWatchOutputSpent(htlcSuccessTx.input.outPoint)
    val publishHtlcTx = bob2blockchain.expectFinalTxPublished("htlc-success")
    assert(publishHtlcTx.input == htlcSuccessTx.input.outPoint)
    bob2blockchain.expectNoMessage(100 millis)
  }

  test("pending non-relayed fail htlcs will timeout upstream") { f =>
    import f._
    val (_, htlc) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    disconnect(alice, bob)

    // We simulate a pending failure on that HTLC.
    // Even if we get close to expiring upstream we shouldn't close the channel, because we have nothing to lose.
    bob ! CMD_FAIL_HTLC(htlc.id, FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(0 msat, BlockHeight(0))), None)
    bob ! CurrentBlockHeight(htlc.cltvExpiry.blockHeight - bob.underlyingActor.nodeParams.channelConf.fulfillSafetyBeforeTimeout.toInt)
    bob2blockchain.expectNoMessage(100 millis)
    alice2blockchain.expectNoMessage(100 millis)
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

    val aliceCommitTx = alice.signCommitTx()
    val currentFeerate = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.commitTxFeerate
    // we receive a feerate update that makes our current feerate too low compared to the network's (we multiply by 1.1
    // to ensure the network's feerate is 10% above our threshold).
    val networkFeerate = currentFeerate * (1.1 / alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(Bob.nodeParams.nodeId).ratioLow)
    val networkFeerates = FeeratesPerKw.single(networkFeerate)

    // alice is funder
    alice.setBitcoinCoreFeerates(networkFeerates)
    alice ! CurrentFeerates.BitcoinCore(networkFeerates)
    if (shouldClose) {
      assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == aliceCommitTx.txid)
    } else {
      alice2blockchain.expectNoMessage(100 millis)
    }
  }

  test("handle feerate changes while offline (don't close on mismatch)", Tag(DisableOfflineMismatch)) { f =>
    import f._

    // we only close channels on feerate mismatch if there are HTLCs at risk in the commitment
    addHtlc(125000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // we simulate a disconnection
    disconnect(alice, bob)

    val aliceStateData = alice.stateData.asInstanceOf[DATA_NORMAL]
    val currentFeeratePerKw = aliceStateData.commitments.latest.localCommit.spec.commitTxFeerate
    // we receive a feerate update that makes our current feerate too low compared to the network's (we multiply by 1.1
    // to ensure the network's feerate is 10% above our threshold).
    val networkFeeratePerKw = currentFeeratePerKw * (1.1 / alice.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(Bob.nodeParams.nodeId).ratioLow)
    val networkFeerates = FeeratesPerKw.single(networkFeeratePerKw)

    // this time Alice will ignore feerate changes for the offline channel
    alice.setBitcoinCoreFeerates(networkFeerates)
    alice ! CurrentFeerates.BitcoinCore(networkFeerates)
    alice2blockchain.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)
  }

  def testUpdateFeeOnReconnect(f: FixtureParam, shouldUpdateFee: Boolean): Unit = {
    import f._

    // we simulate a disconnection
    disconnect(alice, bob)

    val localFeeratePerKw = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.commitTxFeerate
    val networkFeeratePerKw = localFeeratePerKw * 2
    val networkFeerates = FeeratesPerKw.single(networkFeeratePerKw)

    // Alice ignores feerate changes while offline
    alice.setBitcoinCoreFeerates(networkFeerates)
    alice ! CurrentFeerates.BitcoinCore(networkFeerates)
    alice2blockchain.expectNoMessage(100 millis)
    alice2bob.expectNoMessage(100 millis)

    // then we reconnect them; Alice should send the feerate changes to Bob
    reconnect(alice, bob, alice2bob, bob2alice)

    // peers exchange channel_reestablish messages
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)

    if (shouldUpdateFee) {
      alice2bob.expectMsg(UpdateFee(channelId(alice), networkFeeratePerKw))
    } else {
      alice2bob.expectMsgType[Shutdown]
      alice2bob.expectNoMessage(100 millis)
    }
  }

  test("handle feerate changes while offline (update at reconnection)", Tag(IgnoreChannelUpdates)) { f =>
    testUpdateFeeOnReconnect(f, shouldUpdateFee = true)
  }

  test("handle feerate changes while offline (shutdown sent, don't update at reconnection)", Tag(IgnoreChannelUpdates)) { f =>
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

    val bobCommitTx = bob.signCommitTx()
    val currentFeerate = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.latest.localCommit.spec.commitTxFeerate
    // we receive a feerate update that makes our current feerate too low compared to the network's (we multiply by 1.1
    // to ensure the network's feerate is 10% above our threshold).
    val networkFeerate = currentFeerate * (1.1 / bob.underlyingActor.nodeParams.onChainFeeConf.feerateToleranceFor(Alice.nodeParams.nodeId).ratioLow)
    val networkFeerates = FeeratesPerKw.single(networkFeerate)

    // bob is fundee
    bob.setBitcoinCoreFeerates(networkFeerates)
    bob ! CurrentFeerates.BitcoinCore(networkFeerates)
    if (shouldClose) {
      assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == bobCommitTx.txid)
    } else {
      bob2blockchain.expectNoMessage(100 millis)
    }
  }

  test("re-send announcement_signatures on reconnection", Tag(ChannelStateTestsTags.ChannelsPublic), Tag(ChannelStateTestsTags.DoNotInterceptGossip)) { f =>
    import f._

    // Alice receives Bob's announcement_signatures, but Bob doesn't receive Alice's.
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[ChannelUpdate]
    alice2bob.expectMsgType[AnnouncementSignatures]
    alice2bob.expectMsgType[ChannelUpdate]

    // We simulate a disconnection / reconnection.
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)

    // Alice and Bob exchange channel_reestablish and channel_ready again.
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)
    alice2bob.expectNoMessage(100 millis)

    // Bob retransmits his channel_ready and announcement_signatures because he hasn't received Alice's announcement_signatures.
    bob2alice.expectMsgType[ChannelReady]
    bob2alice.forward(alice)

    val annSigsBob = bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice, annSigsBob)
    // Alice retransmits her announcement_signatures when receiving Bob's.
    val annSigsAlice = alice2bob.expectMsgType[AnnouncementSignatures]
    alice2bob.forward(bob, annSigsAlice)
    // Alice and Bob ignore redundant announcement_signatures.
    alice2bob.forward(bob, annSigsAlice)
    bob2alice.expectNoMessage(100 millis)
    bob2alice.forward(alice, annSigsBob)
    alice2bob.expectNoMessage(100 millis)
  }

  test("re-send channel_update on reconnection for unannounced channels") { f =>
    import f._

    // we simulate a disconnection / reconnection
    disconnect(alice, bob)
    // we wait 1s so the new channel_update doesn't have the same timestamp
    TestUtils.waitFor(1 second)
    reconnect(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)

    // alice and bob resend their channel update at reconnection (unannounced channel)
    alice2bob.expectMsgType[ChannelUpdate]
    bob2alice.expectMsgType[ChannelUpdate]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // we make the peers exchange a few messages
    addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // we simulate a disconnection / reconnection
    disconnect(alice, bob)
    // we wait 1s so the new channel_update doesn't have the same timestamp
    TestUtils.waitFor(1 second)
    reconnect(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)

    // alice and bob resend their channel update at reconnection (unannounced channel)
    alice2bob.expectMsgType[ChannelUpdate]
    bob2alice.expectMsgType[ChannelUpdate]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)

    // we get disconnected again
    disconnect(alice, bob)
    // we wait 1s so the new channel_update doesn't have the same timestamp
    TestUtils.waitFor(1 second)
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

  test("recv WatchFundingSpentTriggered (unrecognized commit)") { f =>
    import f._
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == NORMAL)
  }

  def disconnect(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel]): Unit = {
    alice ! INPUT_DISCONNECTED
    bob ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
  }

  def reconnect(alice: TestFSMRef[ChannelState, ChannelData, Channel], bob: TestFSMRef[ChannelState, ChannelData, Channel], alice2bob: TestProbe, bob2alice: TestProbe): (PublicKey, PublicKey) = {
    val aliceInit = Init(alice.nodeParams.initFeaturesFor(bob.nodeParams.nodeId))
    val bobInit = Init(bob.nodeParams.initFeaturesFor(alice.nodeParams.nodeId))

    alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)

    val aliceCommitments = alice.stateData.asInstanceOf[ChannelDataWithCommitments].commitments
    val aliceCurrentPerCommitmentPoint = alice.underlyingActor.channelKeys.commitmentPoint(aliceCommitments.localCommitIndex)

    val bobCommitments = bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments
    val bobCurrentPerCommitmentPoint = bob.underlyingActor.channelKeys.commitmentPoint(bobCommitments.localCommitIndex)

    (aliceCurrentPerCommitmentPoint, bobCurrentPerCommitmentPoint)
  }

  test("re-send channel_ready when peer expects pre-splice behavior", Tag(ChannelStateTestsTags.DisableSplice), Tag(IgnoreChannelUpdates)) { f =>
    import f._

    // we simulate a disconnection / reconnection
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)

    // Alice will resend her channel_ready on reconnection because the channel hasn't been used for any payment yet (pre-splice behavior).
    alice2bob.expectMsgType[ChannelReady]
    bob2alice.expectMsgType[ChannelReady]
    alice2bob.expectNoMessage(100 millis)

    // we update the channel
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // we simulate a disconnection / reconnection
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)

    // Alice will NOT resend her channel_ready on reconnection because the channel has been used for a payment (pre-splice behavior).
    alice2bob.expectNoMessage(100 millis)
  }

  test("re-send announcement_signatures when peer expects pre-splice behavior", Tag(ChannelStateTestsTags.ChannelsPublic), Tag(ChannelStateTestsTags.DisableSplice), Tag(IgnoreChannelUpdates)) { f =>
    import f._

    // we simulate a disconnection / reconnection
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)

    // Alice will resend her channel_ready and announcement_signatures at reconnection because she has not received
    // announcement_signatures from bob (pre-splice behavior).
    alice2bob.expectMsgType[ChannelReady]
    alice2bob.expectMsgType[AnnouncementSignatures]
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectMsgType[ChannelReady]
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.expectNoMessage(100 millis)

    // we update the channel
    addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // we simulate a disconnection / reconnection
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)

    // Alice will resend her channel_ready and announcement_signatures on reconnection, even though the channel has already been used, because
    // she still has not received announcement_signatures from bob (pre-splice behavior).
    alice2bob.expectMsgType[ChannelReady]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[AnnouncementSignatures]
    alice2bob.forward(bob)
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectMsgType[ChannelReady]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    bob2alice.expectNoMessage(100 millis)

    // we simulate a disconnection / reconnection
    disconnect(alice, bob)
    reconnect(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[ChannelReestablish]
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ChannelReady]
    bob2alice.forward(alice)

    // Alice will NOT resend their channel_ready at reconnection because she has received bob's announcement_signatures (pre-splice behavior).
    alice2bob.expectNoMessage(100 millis)
    bob2alice.expectNoMessage(100 millis)
  }

}
