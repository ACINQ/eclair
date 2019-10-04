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
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, OutPoint, ScriptFlags, Transaction, TxOut}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.Channel.LocalError
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.crypto.ShaChain
import fr.acinq.eclair.payment.CommandBuffer
import fr.acinq.eclair.payment.CommandBuffer.CommandSend
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.{CommitmentSpec, Transactions}
import fr.acinq.eclair.transactions.Transactions.{CommitTx, HtlcSuccessTx}
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, LongToBtcAmount, TestConstants, TestkitBaseClass, UInt64, randomBytes32}
import org.scalatest.{Outcome, Tag}
import scodec.bits._

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class OfflineStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

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

  def aliceInit = Init(TestConstants.Alice.nodeParams.globalFeatures, TestConstants.Alice.nodeParams.localFeatures)

  def bobInit = Init(TestConstants.Bob.nodeParams.globalFeatures, TestConstants.Bob.nodeParams.localFeatures)

  /**
   * This test checks the case where a disconnection occurs *right before* the counterparty receives a new sig
   */
  test("re-send update+sig after first commitment") { f =>
    import f._
    val sender = TestProbe()

    sender.send(alice, CMD_ADD_HTLC(1000000 msat, ByteVector32.Zeroes, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, upstream = Left(UUID.randomUUID())))
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

    sender.send(alice, CMD_ADD_HTLC(1000000 msat, randomBytes32, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, upstream = Left(UUID.randomUUID())))
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

  test("ask the last per-commitment-secret to remote and make it publish its commitment tx") { f =>
    import f._
    val sender = TestProbe()

    val oldAliceState = alice.stateData.asInstanceOf[DATA_NORMAL]

    // simulate a fulfilled payment to move forward the commitment index
    addHtlc(250000000 msat, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)

    addHtlc(210000000 msat, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)

    addHtlc(210000000 msat, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)

    // there have been 3 fully ack'ed and revoked commitments
    val effectiveLastCommitmentIndex = 3
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.index == effectiveLastCommitmentIndex)

    val mockAliceIndex = 1 // alice will claim to be at this index when reestablishing the channel - IT MUST BE STRICTLY SMALLER THAN THE ACTUAL INDEX
    val mockBobIndex = 123 // alice will claim that BOB is at this index when reestablishing the channel

    // the mock state contain "random" data that is not really associated with the channel
    // most importantly this data is made in such a way that it will trigger a channel failure from the remote
    val mockAliceState = DATA_NORMAL(
      commitments = Commitments(
        channelVersion = ChannelVersion.STANDARD,
        localParams = oldAliceState.commitments.localParams, // during the actual recovery flow this can be reconstructed with seed + channelKeyPath
        remoteParams = RemoteParams(
          Bob.nodeParams.nodeId,
          dustLimit = 0 sat,
          maxHtlcValueInFlightMsat = UInt64(0),
          channelReserve = 0 sat,
          htlcMinimum = 0 msat,
          toSelfDelay = CltvExpiryDelta(0),
          maxAcceptedHtlcs = 0,
          fundingPubKey = PublicKey(hex"02184615bf2294acc075701892d7bd8aff28d78f84330e8931102e537c8dfe92a3"),
          revocationBasepoint = PublicKey(hex"020beeba2c3015509a16558c35b930bed0763465cf7a9a9bc4555fd384d8d383f6"),
          paymentBasepoint = PublicKey(hex"02e63d3b87e5269d96f1935563ca7c197609a35a928528484da1464eee117335c5"),
          delayedPaymentBasepoint = PublicKey(hex"033dea641e24e7ae550f7c3a94bd9f23d55b26a649c79cd4a3febdf912c6c08281"),
          htlcBasepoint = PublicKey(hex"0274a89988063045d3589b162ac6eea5fa0343bf34220648e92a636b1c2468a434"),
          globalFeatures = hex"00",
          localFeatures = hex"00"
        ),
        channelFlags = 1.toByte,
        localCommit = LocalCommit(
          mockAliceIndex,
          spec = CommitmentSpec(
            htlcs = Set(),
            feeratePerKw = 234,
            toLocal = 0 msat,
            toRemote = 0 msat
          ),
          publishableTxs = PublishableTxs(
            CommitTx(
              input = Transactions.InputInfo(
                outPoint = OutPoint(ByteVector32.Zeroes, 0),
                txOut = TxOut(0 sat, ByteVector.empty),
                redeemScript = ByteVector.empty
              ),
              tx = Transaction.read("0200000000010163c75c555d712a81998ddbaf9ce1d55b153fc7cb71441ae1782143bb6b04b95d0000000000a325818002bc893c0000000000220020ae8d04088ff67f3a0a9106adb84beb7530097b262ff91f8a9a79b7851b50857f00127a0000000000160014be0f04e9ed31b6ece46ca8c17e1ed233c71da0e9040047304402203b280f9655f132f4baa441261b1b590bec3a6fcd6d7180c929fa287f95d200f80220100d826d56362c65d09b8687ca470a31c1e2bb3ad9a41321ceba355d60b77b79014730440220539e34ab02cced861f9c39f9d14ece41f1ed6aed12443a9a4a88eb2792356be6022023dc4f18730a6471bdf9b640dfb831744b81249ffc50bd5a756ae85d8c6749c20147522102184615bf2294acc075701892d7bd8aff28d78f84330e8931102e537c8dfe92a3210367d50e7eab4a0ab0c6b92aa2dcf6cc55a02c3db157866b27a723b8ec47e1338152ae74f15a20")
            ),
            htlcTxsAndSigs = List.empty
          )
        ),
        remoteCommit = RemoteCommit(
          mockBobIndex,
          spec = CommitmentSpec(
            htlcs = Set(),
            feeratePerKw = 432,
            toLocal = 0 msat,
            toRemote = 0 msat
          ),
          txid = ByteVector32.fromValidHex("b70c3314af259029e7d11191ca0fe6ee407352dfaba59144df7f7ce5cc1c7b51"),
          remotePerCommitmentPoint = PublicKey(hex"0286f6253405605640f6c19ea85a51267795163183a17df077050bf680ed62c224")
        ),
        localChanges = LocalChanges(
          proposed = List.empty,
          signed = List.empty,
          acked = List.empty
        ),
        remoteChanges = RemoteChanges(
          proposed = List.empty,
          signed = List.empty,
          acked = List.empty
        ),
        localNextHtlcId = 0,
        remoteNextHtlcId = 0,
        originChannels = Map(),
        remoteNextCommitInfo = Right(PublicKey(hex"0386f6253405605640f6c19ea85a51267795163183a17df077050bf680ed62c224")),
        commitInput = Transactions.InputInfo(
          outPoint = OutPoint(ByteVector32.Zeroes, 0),
          txOut = TxOut(0 sat, ByteVector.empty),
          redeemScript = ByteVector.empty
        ),
        remotePerCommitmentSecrets = ShaChain.init,
        channelId = oldAliceState.commitments.channelId
      ),
      shortChannelId = oldAliceState.shortChannelId,
      buried = oldAliceState.buried,
      channelAnnouncement = None,
      channelUpdate = ChannelUpdate(
        signature = ByteVector64.Zeroes,
        chainHash = Alice.nodeParams.chainHash,
        shortChannelId = oldAliceState.shortChannelId,
        timestamp = 1556526043L,
        messageFlags = 0.toByte,
        channelFlags = 0.toByte,
        cltvExpiryDelta = CltvExpiryDelta(144),
        htlcMinimumMsat = 0 msat,
        feeBaseMsat = 0 msat,
        feeProportionalMillionths = 0,
        htlcMaximumMsat = None
      ),
      localShutdown = None,
      remoteShutdown = None
    )

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // alice's state data contains dummy values
    alice.setState(OFFLINE, mockAliceState)

    // then we reconnect them
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit))

    // peers exchange channel_reestablish messages
    val bobCommitments = bob.stateData.asInstanceOf[HasCommitments].commitments
    val bobCurrentPerCommitmentPoint = Bob.keyManager.commitmentPoint(Bob.keyManager.channelKeyPath(bobCommitments.localParams, bobCommitments.channelVersion), bobCommitments.localCommit.index)
    val aliceCurrentPerCommitmentPoint = Alice.keyManager.commitmentPoint(Alice.keyManager.channelKeyPath(mockAliceState.commitments.localParams,  mockAliceState.commitments.channelVersion), mockAliceIndex)
    // that's what we expect from Bob, Alice's per-commitment-secret generated using the latest commitment index
    val aliceLatestPerCommitmentSecret = Alice.keyManager.commitmentSecret(Alice.keyManager.channelKeyPath(mockAliceState.commitments.localParams,  mockAliceState.commitments.channelVersion), effectiveLastCommitmentIndex - 1)

    // Alice sends the indexes and commitment points according to her (mistaken) view of the commitment, Bob will let her know she's behind
    alice2bob.expectMsg(ChannelReestablish(oldAliceState.commitments.channelId, mockAliceIndex + 1, mockBobIndex, Some(PrivateKey(ByteVector32.Zeroes)), Some(aliceCurrentPerCommitmentPoint)))
    bob2alice.expectMsg(ChannelReestablish(oldAliceState.commitments.channelId, effectiveLastCommitmentIndex + 1, effectiveLastCommitmentIndex, Some(aliceLatestPerCommitmentSecret), Some(bobCurrentPerCommitmentPoint)))

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
    sender.send(alice, CMD_UPDATE_RELAY_FEE(4200 msat, 123456))
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
    sender.send(alice, CMD_ADD_HTLC(4200 msat, randomBytes32, CltvExpiry(123456), TestConstants.emptyOnionPacket, upstream = Left(UUID.randomUUID())))
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
    sender.send(commandBuffer, CommandSend(htlc.channelId, htlc.id, CMD_FULFILL_HTLC(htlc.id, r, commit = true)))
    sender.send(bob, CurrentBlockCount((htlc.cltvExpiry - bob.underlyingActor.nodeParams.fulfillSafetyBeforeTimeoutBlocks).toLong))

    val ChannelErrorOccurred(_, _, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccurred]
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
    val (_, htlc) = addHtlc(50000000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    // We simulate a pending failure on that HTLC.
    // Even if we get close to expiring upstream we shouldn't close the channel, because we have nothing to lose.
    sender.send(commandBuffer, CommandSend(htlc.channelId, htlc.id, CMD_FAIL_HTLC(htlc.id, Right(IncorrectOrUnknownPaymentDetails(0 msat, 0)))))
    sender.send(bob, CurrentBlockCount((htlc.cltvExpiry - bob.underlyingActor.nodeParams.fulfillSafetyBeforeTimeoutBlocks).toLong))

    bob2blockchain.expectNoMsg(250 millis)
    alice2blockchain.expectNoMsg(250 millis)
  }

  test("handle feerate changes while offline (funder scenario)") { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    val aliceStateData = alice.stateData.asInstanceOf[DATA_NORMAL]
    val aliceCommitTx = aliceStateData.commitments.localCommit.publishableTxs.commitTx.tx

    val localFeeratePerKw = aliceStateData.commitments.localCommit.spec.feeratePerKw
    val tooHighFeeratePerKw = ((alice.underlyingActor.nodeParams.onChainFeeConf.maxFeerateMismatch + 6) * localFeeratePerKw).toLong
    val highFeerate = FeeratesPerKw.single(tooHighFeeratePerKw)

    // alice is funder
    sender.send(alice, CurrentFeerates(highFeerate))
    alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))
  }

  test("handle feerate changes while offline (don't close on mismatch)", Tag("disable-offline-mismatch")) { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    val aliceStateData = alice.stateData.asInstanceOf[DATA_NORMAL]
    val aliceCommitTx = aliceStateData.commitments.localCommit.publishableTxs.commitTx.tx

    val localFeeratePerKw = aliceStateData.commitments.localCommit.spec.feeratePerKw
    val tooHighFeeratePerKw = ((alice.underlyingActor.nodeParams.onChainFeeConf.maxFeerateMismatch + 6) * localFeeratePerKw).toLong
    val highFeerate = FeeratesPerKw.single(tooHighFeeratePerKw)

    // this time Alice will ignore feerate changes for the offline channel
    sender.send(alice, CurrentFeerates(highFeerate))
    alice2blockchain.expectNoMsg()
    alice2bob.expectNoMsg()
  }

  test("handle feerate changes while offline (fundee scenario)") { f =>
    import f._
    val sender = TestProbe()

    // we simulate a disconnection
    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)

    val bobStateData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val bobCommitTx = bobStateData.commitments.localCommit.publishableTxs.commitTx.tx

    val localFeeratePerKw = bobStateData.commitments.localCommit.spec.feeratePerKw
    val tooHighFeeratePerKw = ((bob.underlyingActor.nodeParams.onChainFeeConf.maxFeerateMismatch + 6) * localFeeratePerKw).toLong
    val highFeerate = FeeratesPerKw.single(tooHighFeeratePerKw)

    // bob is fundee
    sender.send(bob, CurrentFeerates(highFeerate))
    bob2blockchain.expectMsg(PublishAsap(bobCommitTx))
  }
}
