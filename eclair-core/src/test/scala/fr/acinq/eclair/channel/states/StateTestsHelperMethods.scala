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

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.actor.{ActorContext, ActorRef}
import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Crypto, SatoshiLong, ScriptFlags, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob, TestFeeEstimator}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeTargets
import fr.acinq.eclair.blockchain.{EclairWallet, TestWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.states.StateTestsHelperMethods.FakeTxPublisherFactory
import fr.acinq.eclair.payment.OutgoingPacket
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.router.Router.ChannelHop
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol._
import org.scalatest.{FixtureTestSuite, ParallelTestExecution}

import java.util.UUID
import scala.concurrent.duration._

/**
 * Created by PM on 23/08/2016.
 */
trait StateTestsBase extends StateTestsHelperMethods with FixtureTestSuite with ParallelTestExecution {

  implicit class ChannelWithTestFeeConf(a: TestFSMRef[State, Data, Channel]) {
    // @formatter:off
    def feeEstimator: TestFeeEstimator = a.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator]
    def feeTargets: FeeTargets = a.underlyingActor.nodeParams.onChainFeeConf.feeTargets
    // @formatter:on
  }

}

object StateTestsTags {
  /** If set, channels will use option_support_large_channel. */
  val Wumbo = "wumbo"
  /** If set, channels will use option_static_remotekey. */
  val StaticRemoteKey = "static_remotekey"
  /** If set, channels will use option_anchor_outputs. */
  val AnchorOutputs = "anchor_outputs"
  /** If set, channels will use option_shutdown_anysegwit. */
  val ShutdownAnySegwit = "shutdown_anysegwit"
  /** If set, channels will be public (otherwise we don't announce them by default). */
  val ChannelsPublic = "channels_public"
  /** If set, no amount will be pushed when opening a channel (by default we push a small amount). */
  val NoPushMsat = "no_push_msat"
  /** If set, max-htlc-value-in-flight will be set to the highest possible value for Alice and Bob. */
  val NoMaxHtlcValueInFlight = "no_max_htlc_value_in_flight"
  /** If set, max-htlc-value-in-flight will be set to a low value for Alice. */
  val AliceLowMaxHtlcValueInFlight = "alice_low_max_htlc_value_in_flight"
}

trait StateTestsHelperMethods extends TestKitBase {

  case class SetupFixture(alice: TestFSMRef[State, Data, Channel],
                          bob: TestFSMRef[State, Data, Channel],
                          alice2bob: TestProbe,
                          bob2alice: TestProbe,
                          alice2blockchain: TestProbe,
                          bob2blockchain: TestProbe,
                          router: TestProbe,
                          relayerA: TestProbe,
                          relayerB: TestProbe,
                          channelUpdateListener: TestProbe,
                          wallet: EclairWallet) {
    def currentBlockHeight: Long = alice.underlyingActor.nodeParams.currentBlockHeight
  }

  def init(nodeParamsA: NodeParams = TestConstants.Alice.nodeParams, nodeParamsB: NodeParams = TestConstants.Bob.nodeParams, wallet: EclairWallet = new TestWallet): SetupFixture = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alicePeer = TestProbe()
    val bobPeer = TestProbe()
    TestUtils.forwardOutgoingToPipe(alicePeer, alice2bob.ref)
    TestUtils.forwardOutgoingToPipe(bobPeer, bob2alice.ref)
    val alice2blockchain = TestProbe()
    val bob2blockchain = TestProbe()
    val relayerA = TestProbe()
    val relayerB = TestProbe()
    val channelUpdateListener = TestProbe()
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelUpdate])
    system.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelDown])
    val router = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(nodeParamsA, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, relayerA.ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer.ref)
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(nodeParamsB, wallet, Alice.nodeParams.nodeId, bob2blockchain.ref, relayerB.ref, FakeTxPublisherFactory(bob2blockchain)), bobPeer.ref)
    SetupFixture(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, router, relayerA, relayerB, channelUpdateListener, wallet)
  }

  // NB: the features stored in LocalParams contain the node's features, which may be different from the channel features.
  def setLocalFeatures(defaultChannelParams: LocalParams, tags: Set[String]): LocalParams = {
    import com.softwaremill.quicklens._

    defaultChannelParams
      .modify(_.features.activated).usingIf(tags.contains(StateTestsTags.Wumbo))(_.updated(Features.Wumbo, FeatureSupport.Optional))
      .modify(_.features.activated).usingIf(tags.contains(StateTestsTags.StaticRemoteKey))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional))
      .modify(_.features.activated).usingIf(tags.contains(StateTestsTags.AnchorOutputs))(_.updated(Features.StaticRemoteKey, FeatureSupport.Mandatory).updated(Features.AnchorOutputs, FeatureSupport.Optional))
      .modify(_.features.activated).usingIf(tags.contains(StateTestsTags.ShutdownAnySegwit))(_.updated(Features.ShutdownAnySegwit, FeatureSupport.Optional))
  }

  def reachNormal(setup: SetupFixture, tags: Set[String] = Set.empty): Unit = {
    import com.softwaremill.quicklens._
    import setup._

    val channelConfig = ChannelConfig.standard
    val channelFeatures = ChannelFeatures(Features.empty)
      .modify(_.features.activated).usingIf(tags.contains(StateTestsTags.Wumbo))(_.updated(Features.Wumbo, FeatureSupport.Mandatory))
      .modify(_.features.activated).usingIf(tags.contains(StateTestsTags.StaticRemoteKey))(_.updated(Features.StaticRemoteKey, FeatureSupport.Mandatory))
      .modify(_.features.activated).usingIf(tags.contains(StateTestsTags.AnchorOutputs))(_.updated(Features.StaticRemoteKey, FeatureSupport.Mandatory).updated(Features.AnchorOutputs, FeatureSupport.Mandatory))

    val channelFlags = if (tags.contains(StateTestsTags.ChannelsPublic)) ChannelFlags.AnnounceChannel else ChannelFlags.Empty
    val aliceParams = setLocalFeatures(Alice.channelParams, tags)
      .modify(_.walletStaticPaymentBasepoint).setToIf(channelFeatures.paysDirectlyToWallet)(Some(Helpers.getWalletPaymentBasepoint(wallet)))
      .modify(_.maxHtlcValueInFlightMsat).setToIf(tags.contains(StateTestsTags.NoMaxHtlcValueInFlight))(UInt64.MaxValue)
      .modify(_.maxHtlcValueInFlightMsat).setToIf(tags.contains(StateTestsTags.AliceLowMaxHtlcValueInFlight))(UInt64(150000000))
    val bobParams = setLocalFeatures(Bob.channelParams, tags)
      .modify(_.walletStaticPaymentBasepoint).setToIf(channelFeatures.paysDirectlyToWallet)(Some(Helpers.getWalletPaymentBasepoint(wallet)))
      .modify(_.maxHtlcValueInFlightMsat).setToIf(tags.contains(StateTestsTags.NoMaxHtlcValueInFlight))(UInt64.MaxValue)
    val initialFeeratePerKw = if (channelFeatures.hasFeature(Features.AnchorOutputs)) TestConstants.anchorOutputsFeeratePerKw else TestConstants.feeratePerKw
    val (fundingSatoshis, pushMsat) = if (tags.contains(StateTestsTags.NoPushMsat)) {
      (TestConstants.fundingSatoshis, 0.msat)
    } else {
      (TestConstants.fundingSatoshis, TestConstants.pushMsat)
    }

    val aliceInit = Init(aliceParams.features)
    val bobInit = Init(bobParams.features)
    alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, fundingSatoshis, pushMsat, initialFeeratePerKw, TestConstants.feeratePerKw, None, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelFeatures)
    assert(alice2blockchain.expectMsgType[TxPublisher.SetChannelId].channelId === ByteVector32.Zeroes)
    bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, bobParams, bob2alice.ref, aliceInit, channelConfig, channelFeatures)
    assert(bob2blockchain.expectMsgType[TxPublisher.SetChannelId].channelId === ByteVector32.Zeroes)
    alice2bob.expectMsgType[OpenChannel]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[AcceptChannel]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    assert(alice2blockchain.expectMsgType[TxPublisher.SetChannelId].channelId != ByteVector32.Zeroes)
    alice2blockchain.expectMsgType[WatchFundingSpent]
    alice2blockchain.expectMsgType[WatchFundingConfirmed]
    assert(bob2blockchain.expectMsgType[TxPublisher.SetChannelId].channelId != ByteVector32.Zeroes)
    bob2blockchain.expectMsgType[WatchFundingSpent]
    bob2blockchain.expectMsgType[WatchFundingConfirmed]
    awaitCond(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED)
    val fundingTx = alice.stateData.asInstanceOf[DATA_WAIT_FOR_FUNDING_CONFIRMED].fundingTx.get
    alice ! WatchFundingConfirmedTriggered(400000, 42, fundingTx)
    bob ! WatchFundingConfirmedTriggered(400000, 42, fundingTx)
    alice2blockchain.expectMsgType[WatchFundingLost]
    bob2blockchain.expectMsgType[WatchFundingLost]
    alice2bob.expectMsgType[FundingLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    bob2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.availableBalanceForSend == (pushMsat - aliceParams.channelReserve).max(0 msat))
    // x2 because alice and bob share the same relayer
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
  }

  def localOrigin(replyTo: ActorRef): Origin.LocalHot = Origin.LocalHot(replyTo, UUID.randomUUID)

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: Long, paymentPreimage: ByteVector32 = randomBytes32(), upstream: Upstream = Upstream.Local(UUID.randomUUID), replyTo: ActorRef = TestProbe().ref): (ByteVector32, CMD_ADD_HTLC) = {
    val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage)
    val expiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
    val cmd = OutgoingPacket.buildCommand(replyTo, upstream, paymentHash, ChannelHop(null, destination, null) :: Nil, Onion.createSinglePartPayload(amount, expiry, randomBytes32()))._1.copy(commit = false)
    (paymentPreimage, cmd)
  }

  def addHtlc(amount: MilliSatoshi, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe, replyTo: ActorRef = TestProbe().ref): (ByteVector32, UpdateAddHtlc) = {
    val currentBlockHeight = s.underlyingActor.nodeParams.currentBlockHeight
    val (payment_preimage, cmd) = makeCmdAdd(amount, r.underlyingActor.nodeParams.nodeId, currentBlockHeight, replyTo = replyTo)
    val htlc = addHtlc(cmd, s, r, s2r, r2s)
    (payment_preimage, htlc)
  }

  def addHtlc(cmdAdd: CMD_ADD_HTLC, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): UpdateAddHtlc = {
    s ! cmdAdd
    val htlc = s2r.expectMsgType[UpdateAddHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(htlc))
    htlc
  }

  def fulfillHtlc(id: Long, preimage: ByteVector32, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    s ! CMD_FULFILL_HTLC(id, preimage)
    val fulfill = s2r.expectMsgType[UpdateFulfillHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(fulfill))
  }

  def failHtlc(id: Long, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    s ! CMD_FAIL_HTLC(id, Right(TemporaryNodeFailure))
    val fail = s2r.expectMsgType[UpdateFailHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(fail))
  }

  def crossSign(s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    val sender = TestProbe()
    val sCommitIndex = s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index
    val rCommitIndex = r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index
    val rHasChanges = Commitments.localHasChanges(r.stateData.asInstanceOf[HasCommitments].commitments)
    s ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
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

  def mutualClose(s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe, s2blockchain: TestProbe, r2blockchain: TestProbe): Unit = {
    val sender = TestProbe()
    // s initiates a closing
    s ! CMD_CLOSE(sender.ref, None)
    s2r.expectMsgType[Shutdown]
    s2r.forward(r)
    r2s.expectMsgType[Shutdown]
    r2s.forward(s)
    // agreeing on a closing fee
    var sCloseFee, rCloseFee = 0.sat
    do {
      sCloseFee = s2r.expectMsgType[ClosingSigned].feeSatoshis
      s2r.forward(r)
      rCloseFee = r2s.expectMsgType[ClosingSigned].feeSatoshis
      r2s.forward(s)
    } while (sCloseFee != rCloseFee)
    s2blockchain.expectMsgType[TxPublisher.PublishTx]
    s2blockchain.expectMsgType[WatchTxConfirmed]
    r2blockchain.expectMsgType[TxPublisher.PublishTx]
    r2blockchain.expectMsgType[WatchTxConfirmed]
    awaitCond(s.stateName == CLOSING)
    awaitCond(r.stateName == CLOSING)
    // both nodes are now in CLOSING state with a mutual close tx pending for confirmation
  }

  def localClose(s: TestFSMRef[State, Data, Channel], s2blockchain: TestProbe): LocalCommitPublished = {
    // an error occurs and s publishes its commit tx
    val localCommit = s.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit
    // check that we store the local txs without sigs
    localCommit.commitTxAndRemoteSig.commitTx.tx.txIn.foreach(txIn => assert(txIn.witness.isNull))
    localCommit.htlcTxsAndRemoteSigs.foreach(_.htlcTx.tx.txIn.foreach(txIn => assert(txIn.witness.isNull)))

    val commitTx = localCommit.commitTxAndRemoteSig.commitTx.tx
    s ! Error(ByteVector32.Zeroes, "oops")
    awaitCond(s.stateName == CLOSING)
    val closingState = s.stateData.asInstanceOf[DATA_CLOSING]
    assert(closingState.localCommitPublished.isDefined)
    val localCommitPublished = closingState.localCommitPublished.get

    val publishedLocalCommitTx = s2blockchain.expectMsgType[TxPublisher.PublishRawTx].tx
    assert(publishedLocalCommitTx.txid == commitTx.txid)
    val commitInput = closingState.commitments.commitInput
    Transaction.correctlySpends(publishedLocalCommitTx, Map(commitInput.outPoint -> commitInput.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    if (closingState.commitments.commitmentFormat == Transactions.AnchorOutputsCommitmentFormat) {
      assert(s2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx].txInfo.isInstanceOf[ClaimLocalAnchorOutputTx])
    }
    // if s has a main output in the commit tx (when it has a non-dust balance), it should be claimed
    localCommitPublished.claimMainDelayedOutputTx.foreach(tx => s2blockchain.expectMsg(TxPublisher.PublishRawTx(tx, None)))
    closingState.commitments.commitmentFormat match {
      case Transactions.DefaultCommitmentFormat =>
        // all htlcs success/timeout should be published as-is, without claiming their outputs
        s2blockchain.expectMsgAllOf(localCommitPublished.htlcTxs.values.toSeq.collect { case Some(tx) => TxPublisher.PublishRawTx(tx, Some(commitTx.txid)) }: _*)
        assert(localCommitPublished.claimHtlcDelayedTxs.isEmpty)
      case Transactions.AnchorOutputsCommitmentFormat =>
        // all htlcs success/timeout should be published as replaceable txs, without claiming their outputs
        val htlcTxs = localCommitPublished.htlcTxs.values.collect { case Some(tx: HtlcTx) => tx }
        val publishedTxs = htlcTxs.map(_ => s2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx])
        assert(publishedTxs.map(_.input).toSet == htlcTxs.map(_.input.outPoint).toSet)
        assert(localCommitPublished.claimHtlcDelayedTxs.isEmpty)
    }

    // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
    assert(s2blockchain.expectMsgType[WatchTxConfirmed].txId === commitTx.txid)
    localCommitPublished.claimMainDelayedOutputTx.foreach(claimMain => assert(s2blockchain.expectMsgType[WatchTxConfirmed].txId === claimMain.tx.txid))

    // we watch outputs of the commitment tx that both parties may spend
    val htlcOutputIndexes = localCommitPublished.htlcTxs.keySet.map(_.index)
    val spentWatches = htlcOutputIndexes.map(_ => s2blockchain.expectMsgType[WatchOutputSpent])
    spentWatches.foreach(ws => assert(ws.txId == commitTx.txid))
    assert(spentWatches.map(_.outputIndex) == htlcOutputIndexes)
    s2blockchain.expectNoMsg(1 second)

    // s is now in CLOSING state with txs pending for confirmation before going in CLOSED state
    closingState.localCommitPublished.get
  }

  def remoteClose(rCommitTx: Transaction, s: TestFSMRef[State, Data, Channel], s2blockchain: TestProbe): RemoteCommitPublished = {
    // we make s believe r unilaterally closed the channel
    s ! WatchFundingSpentTriggered(rCommitTx)
    awaitCond(s.stateName == CLOSING)
    val closingData = s.stateData.asInstanceOf[DATA_CLOSING]
    val remoteCommitPublished_opt = closingData.remoteCommitPublished.orElse(closingData.nextRemoteCommitPublished).orElse(closingData.futureRemoteCommitPublished)
    assert(remoteCommitPublished_opt.isDefined)
    assert(closingData.localCommitPublished.isEmpty)
    val remoteCommitPublished = remoteCommitPublished_opt.get

    // if s has a main output in the commit tx (when it has a non-dust balance), it should be claimed
    remoteCommitPublished.claimMainOutputTx.foreach(claimMain => {
      Transaction.correctlySpends(claimMain.tx, rCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      s2blockchain.expectMsg(TxPublisher.PublishRawTx(claimMain, None))
    })
    // all htlcs success/timeout should be claimed
    val claimHtlcTxs = remoteCommitPublished.claimHtlcTxs.values.collect { case Some(tx: ClaimHtlcTx) => tx }.toSeq
    claimHtlcTxs.foreach(claimHtlc => Transaction.correctlySpends(claimHtlc.tx, rCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    s2blockchain.expectMsgAllOf(claimHtlcTxs.map(claimHtlc => TxPublisher.PublishRawTx(claimHtlc, None)): _*)

    // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
    assert(s2blockchain.expectMsgType[WatchTxConfirmed].txId === rCommitTx.txid)
    remoteCommitPublished.claimMainOutputTx.foreach(claimMain => assert(s2blockchain.expectMsgType[WatchTxConfirmed].txId === claimMain.tx.txid))

    // we watch outputs of the commitment tx that both parties may spend
    val htlcOutputIndexes = remoteCommitPublished.claimHtlcTxs.keySet.map(_.index)
    val spentWatches = htlcOutputIndexes.map(_ => s2blockchain.expectMsgType[WatchOutputSpent])
    spentWatches.foreach(ws => assert(ws.txId == rCommitTx.txid))
    assert(spentWatches.map(_.outputIndex) == htlcOutputIndexes)
    s2blockchain.expectNoMsg(1 second)

    // s is now in CLOSING state with txs pending for confirmation before going in CLOSED state
    remoteCommitPublished
  }

  def channelId(a: TestFSMRef[State, Data, Channel]): ByteVector32 = a.stateData.channelId

  def getHtlcSuccessTxs(lcp: LocalCommitPublished): Seq[HtlcSuccessTx] = lcp.htlcTxs.values.collect { case Some(tx: HtlcSuccessTx) => tx }.toSeq

  def getHtlcTimeoutTxs(lcp: LocalCommitPublished): Seq[HtlcTimeoutTx] = lcp.htlcTxs.values.collect { case Some(tx: HtlcTimeoutTx) => tx }.toSeq

  def getClaimHtlcSuccessTxs(rcp: RemoteCommitPublished): Seq[ClaimHtlcSuccessTx] = rcp.claimHtlcTxs.values.collect { case Some(tx: ClaimHtlcSuccessTx) => tx }.toSeq

  def getClaimHtlcTimeoutTxs(rcp: RemoteCommitPublished): Seq[ClaimHtlcTimeoutTx] = rcp.claimHtlcTxs.values.collect { case Some(tx: ClaimHtlcTimeoutTx) => tx }.toSeq

}

object StateTestsHelperMethods {

  case class FakeTxPublisherFactory(txPublisher: TestProbe) extends Channel.TxPublisherFactory {
    override def spawnTxPublisher(context: ActorContext, remoteNodeId: PublicKey): akka.actor.typed.ActorRef[TxPublisher.Command] = txPublisher.ref
  }

}