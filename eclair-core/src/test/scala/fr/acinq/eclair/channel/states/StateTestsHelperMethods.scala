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

package fr.acinq.eclair.channel.states

import java.util.UUID

import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.TestConstants.{Alice, Bob, TestFeeEstimator}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.FeeTargets
import fr.acinq.eclair.channel._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.router.ChannelHop
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{NodeParams, TestConstants, randomBytes32, _}
import org.scalatest.{ParallelTestExecution, fixture}

/**
 * Created by PM on 23/08/2016.
 */
trait StateTestsHelperMethods extends TestKitBase with fixture.TestSuite with ParallelTestExecution {

  case class SetupFixture(alice: TestFSMRef[State, Data, Channel],
                          bob: TestFSMRef[State, Data, Channel],
                          alice2bob: TestProbe,
                          bob2alice: TestProbe,
                          alice2blockchain: TestProbe,
                          bob2blockchain: TestProbe,
                          router: TestProbe,
                          relayerA: TestProbe,
                          relayerB: TestProbe,
                          channelUpdateListener: TestProbe) {
    def currentBlockHeight = alice.underlyingActor.nodeParams.currentBlockHeight
  }

  def init(nodeParamsA: NodeParams = TestConstants.Alice.nodeParams, nodeParamsB: NodeParams = TestConstants.Bob.nodeParams, wallet: EclairWallet = new TestWallet): SetupFixture = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val bob2blockchain = TestProbe()
    val relayerA = TestProbe()
    val relayerB = TestProbe()
    val channelUpdateListener = TestProbe()
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelUpdate])
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelDown])
    val router = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(nodeParamsA, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, router.ref, relayerA.ref))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(nodeParamsB, wallet, Alice.nodeParams.nodeId, bob2blockchain.ref, router.ref, relayerB.ref))
    SetupFixture(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, router, relayerA, relayerB, channelUpdateListener)
  }

  def reachNormal(setup: SetupFixture,
                  tags: Set[String] = Set.empty): Unit = {
    import setup._
    val channelVersion = ChannelVersion.STANDARD
    val channelFlags = if (tags.contains("channels_public")) ChannelFlags.AnnounceChannel else ChannelFlags.Empty
    val pushMsat = if (tags.contains("no_push_msat")) 0.msat else TestConstants.pushMsat
    val (aliceParams, bobParams) = (Alice.channelParams, Bob.channelParams)
    val aliceInit = Init(aliceParams.globalFeatures, aliceParams.localFeatures)
    val bobInit = Init(bobParams.globalFeatures, bobParams.localFeatures)
    alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, TestConstants.fundingSatoshis, pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, aliceParams, alice2bob.ref, bobInit, channelFlags, channelVersion)
    bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, bobParams, bob2alice.ref, aliceInit)
    alice2bob.expectMsgType[OpenChannel]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[AcceptChannel]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchSpent]
    alice2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[WatchSpent]
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42, fundingTx)
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42, fundingTx)
    alice2blockchain.expectMsgType[WatchLost]
    bob2blockchain.expectMsgType[WatchLost]
    alice2bob.expectMsgType[FundingLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchConfirmed] // deeply buried
    bob2blockchain.expectMsgType[WatchConfirmed] // deeply buried
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.availableBalanceForSend == (pushMsat - aliceParams.channelReserve).max(0 msat))
    // x2 because alice and bob share the same relayer
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
  }

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long): (ByteVector32, CMD_ADD_HTLC) = {
    val payment_preimage: ByteVector32 = randomBytes32
    val payment_hash: ByteVector32 = Crypto.sha256(payment_preimage)
    val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
    val cmd = OutgoingPacket.buildCommand(UUID.randomUUID, payment_hash, ChannelHop(null, destination, null) :: Nil, FinalLegacyPayload(amount, expiry))._1.copy(commit = false)
    (payment_preimage, cmd)
  }

  def addHtlc(amount: MilliSatoshi, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): (ByteVector32, UpdateAddHtlc) = {
    val sender = TestProbe()
    val currentBlockHeight = s.underlyingActor.nodeParams.currentBlockHeight
    val (payment_preimage, cmd) = makeCmdAdd(amount, r.underlyingActor.nodeParams.nodeId, currentBlockHeight)
    sender.send(s, cmd)
    sender.expectMsg("ok")
    val htlc = s2r.expectMsgType[UpdateAddHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(htlc))
    (payment_preimage, htlc)
  }

  def fulfillHtlc(id: Long, R: ByteVector32, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    val sender = TestProbe()
    sender.send(s, CMD_FULFILL_HTLC(id, R))
    sender.expectMsg("ok")
    val fulfill = s2r.expectMsgType[UpdateFulfillHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(fulfill))
  }

  def crossSign(s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    val sender = TestProbe()
    val sCommitIndex = s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index
    val rCommitIndex = r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index
    val rHasChanges = Commitments.localHasChanges(r.stateData.asInstanceOf[HasCommitments].commitments)
    sender.send(s, CMD_SIGN)
    sender.expectMsg("ok")
    s2r.expectMsgType[CommitSig]
    s2r.forward(r)
    r2s.expectMsgType[RevokeAndAck]
    r2s.forward(s)
    r2s.expectMsgType[CommitSig]
    r2s.forward(s)
    s2r.expectMsgType[RevokeAndAck]
    s2r.forward(r)
    if (rHasChanges) {
      s2r.expectMsgType[CommitSig]
      s2r.forward(r)
      r2s.expectMsgType[RevokeAndAck]
      r2s.forward(s)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == sCommitIndex + 1)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == sCommitIndex + 2)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == rCommitIndex + 2)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == rCommitIndex + 1)
    } else {
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == sCommitIndex + 1)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == sCommitIndex + 1)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == rCommitIndex + 1)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == rCommitIndex + 1)
    }

  }

  def channelId(a: TestFSMRef[State, Data, Channel]) = Helpers.getChannelId(a.stateData)


  implicit class ChannelWithTestFeeConf(a: TestFSMRef[State, Data, Channel]) {
    def feeEstimator: TestFeeEstimator = a.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator]

    def feeTargets: FeeTargets = a.underlyingActor.nodeParams.onChainFeeConf.feeTargets
  }


  implicit class PeerWithTestFeeConf(a: TestFSMRef[Peer.State, Peer.Data, Peer]) {
    def feeEstimator: TestFeeEstimator = a.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator]

    def feeTargets: FeeTargets = a.underlyingActor.nodeParams.onChainFeeConf.feeTargets
  }

}
