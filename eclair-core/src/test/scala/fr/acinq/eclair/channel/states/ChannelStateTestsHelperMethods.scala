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
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Crypto, SatoshiLong, ScriptFlags, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.{FeeTargets, FeeratePerKw}
import fr.acinq.eclair.blockchain.{DummyOnChainWallet, OnChainWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher
import fr.acinq.eclair.channel.publish.TxPublisher.PublishReplaceableTx
import fr.acinq.eclair.channel.states.ChannelStateTestsHelperMethods.FakeTxPublisherFactory
import fr.acinq.eclair.payment.OutgoingPaymentPacket
import fr.acinq.eclair.payment.OutgoingPaymentPacket.Upstream
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
trait ChannelStateTestsBase extends ChannelStateTestsHelperMethods with FixtureTestSuite with ParallelTestExecution {

  implicit class ChannelWithTestFeeConf(a: TestFSMRef[ChannelState, ChannelData, Channel]) {
    // @formatter:off
    def feeEstimator: TestFeeEstimator = a.underlyingActor.nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator]
    def feeTargets: FeeTargets = a.underlyingActor.nodeParams.onChainFeeConf.feeTargets
    // @formatter:on
  }

}

object ChannelStateTestsTags {
  /** If set, channels will use option_support_large_channel. */
  val Wumbo = "wumbo"
  /** If set, channels will use option_static_remotekey. */
  val StaticRemoteKey = "static_remotekey"
  /** If set, channels will use option_anchor_outputs. */
  val AnchorOutputs = "anchor_outputs"
  /** If set, channels will use option_anchors_zero_fee_htlc_tx. */
  val AnchorOutputsZeroFeeHtlcTxs = "anchor_outputs_zero_fee_htlc_tx"
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
  /** If set, channels will use option_upfront_shutdown_script. */
  val OptionUpfrontShutdownScript = "option_upfront_shutdown_script"
  /** If set, Alice will have a much higher dust limit than Bob. */
  val HighDustLimitDifferenceAliceBob = "high_dust_limit_difference_alice_bob"
  /** If set, Bob will have a much higher dust limit than Alice. */
  val HighDustLimitDifferenceBobAlice = "high_dust_limit_difference_bob_alice"
  /** If set, channels will use option_channel_type. */
  val ChannelType = "option_channel_type"
}

trait ChannelStateTestsHelperMethods extends TestKitBase {

  case class SetupFixture(alice: TestFSMRef[ChannelState, ChannelData, Channel],
                          bob: TestFSMRef[ChannelState, ChannelData, Channel],
                          aliceOrigin: TestProbe,
                          alice2bob: TestProbe,
                          bob2alice: TestProbe,
                          alice2blockchain: TestProbe,
                          bob2blockchain: TestProbe,
                          router: TestProbe,
                          relayerA: TestProbe,
                          relayerB: TestProbe,
                          channelUpdateListener: TestProbe,
                          wallet: OnChainWallet,
                          alicePeer: TestProbe,
                          bobPeer: TestProbe) {
    def currentBlockHeight: BlockHeight = alice.underlyingActor.nodeParams.currentBlockHeight
  }

  def init(nodeParamsA: NodeParams = TestConstants.Alice.nodeParams, nodeParamsB: NodeParams = TestConstants.Bob.nodeParams, wallet: OnChainWallet = new DummyOnChainWallet(), tags: Set[String] = Set.empty): SetupFixture = {
    val aliceOrigin = TestProbe()
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
    val finalNodeParamsA = nodeParamsA
      .modify(_.channelConf.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(5000 sat)
      .modify(_.channelConf.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(1000 sat)
      .modify(_.channelConf.maxRemoteDustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(10000 sat)
      .modify(_.channelConf.maxRemoteDustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(10000 sat)
    val finalNodeParamsB = nodeParamsB
      .modify(_.channelConf.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(1000 sat)
      .modify(_.channelConf.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(5000 sat)
      .modify(_.channelConf.maxRemoteDustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(10000 sat)
      .modify(_.channelConf.maxRemoteDustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(10000 sat)
    val alice: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(finalNodeParamsA, wallet, finalNodeParamsB.nodeId, alice2blockchain.ref, relayerA.ref, FakeTxPublisherFactory(alice2blockchain), origin_opt = Some(aliceOrigin.ref)), alicePeer.ref)
    val bob: TestFSMRef[ChannelState, ChannelData, Channel] = TestFSMRef(new Channel(finalNodeParamsB, wallet, finalNodeParamsA.nodeId, bob2blockchain.ref, relayerB.ref, FakeTxPublisherFactory(bob2blockchain)), bobPeer.ref)
    SetupFixture(alice, bob, aliceOrigin, alice2bob, bob2alice, alice2blockchain, bob2blockchain, router, relayerA, relayerB, channelUpdateListener, wallet, alicePeer, bobPeer)
  }

  def computeFeatures(setup: SetupFixture, tags: Set[String]): (LocalParams, LocalParams, SupportedChannelType) = {
    import setup._

    val aliceInitFeatures = Alice.nodeParams.features
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.Wumbo))(_.updated(Features.Wumbo, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.StaticRemoteKey))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.AnchorOutputs))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional).updated(Features.AnchorOutputs, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional).updated(Features.AnchorOutputs, FeatureSupport.Optional).updated(Features.AnchorOutputsZeroFeeHtlcTx, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.ShutdownAnySegwit))(_.updated(Features.ShutdownAnySegwit, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.OptionUpfrontShutdownScript))(_.updated(Features.UpfrontShutdownScript, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.ChannelType))(_.updated(Features.ChannelType, FeatureSupport.Optional))
      .initFeatures()
    val bobInitFeatures = Bob.nodeParams.features
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.Wumbo))(_.updated(Features.Wumbo, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.StaticRemoteKey))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.AnchorOutputs))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional).updated(Features.AnchorOutputs, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional).updated(Features.AnchorOutputs, FeatureSupport.Optional).updated(Features.AnchorOutputsZeroFeeHtlcTx, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.ShutdownAnySegwit))(_.updated(Features.ShutdownAnySegwit, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.OptionUpfrontShutdownScript))(_.updated(Features.UpfrontShutdownScript, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.ChannelType))(_.updated(Features.ChannelType, FeatureSupport.Optional))
      .initFeatures()

    val channelType = ChannelTypes.defaultFromFeatures(aliceInitFeatures, bobInitFeatures)

    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val aliceParams = Alice.channelParams
      .modify(_.initFeatures).setTo(aliceInitFeatures)
      .modify(_.walletStaticPaymentBasepoint).setToIf(channelType.paysDirectlyToWallet)(Some(Helpers.getWalletPaymentBasepoint(wallet)))
      .modify(_.maxHtlcValueInFlightMsat).setToIf(tags.contains(ChannelStateTestsTags.NoMaxHtlcValueInFlight))(UInt64.MaxValue)
      .modify(_.maxHtlcValueInFlightMsat).setToIf(tags.contains(ChannelStateTestsTags.AliceLowMaxHtlcValueInFlight))(UInt64(150000000))
      .modify(_.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(5000 sat)
      .modify(_.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(1000 sat)
    val bobParams = Bob.channelParams
      .modify(_.initFeatures).setTo(bobInitFeatures)
      .modify(_.walletStaticPaymentBasepoint).setToIf(channelType.paysDirectlyToWallet)(Some(Helpers.getWalletPaymentBasepoint(wallet)))
      .modify(_.maxHtlcValueInFlightMsat).setToIf(tags.contains(ChannelStateTestsTags.NoMaxHtlcValueInFlight))(UInt64.MaxValue)
      .modify(_.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(1000 sat)
      .modify(_.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(5000 sat)

    (aliceParams, bobParams, channelType)
  }

  def reachNormal(setup: SetupFixture, tags: Set[String] = Set.empty): Unit = {

    import setup._

    val channelConfig = ChannelConfig.standard
    val (aliceParams, bobParams, channelType) = computeFeatures(setup, tags)
    val channelFlags = ChannelFlags(announceChannel = tags.contains(ChannelStateTestsTags.ChannelsPublic))
    val initialFeeratePerKw = if (tags.contains(ChannelStateTestsTags.AnchorOutputs) || tags.contains(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) TestConstants.anchorOutputsFeeratePerKw else TestConstants.feeratePerKw
    val (fundingSatoshis, pushMsat) = if (tags.contains(ChannelStateTestsTags.NoPushMsat)) {
      (TestConstants.fundingSatoshis, 0.msat)
    } else {
      (TestConstants.fundingSatoshis, TestConstants.pushMsat)
    }

    val aliceInit = Init(aliceParams.initFeatures)
    val bobInit = Init(bobParams.initFeatures)
    alice ! INPUT_INIT_FUNDER(ByteVector32.Zeroes, fundingSatoshis, pushMsat, initialFeeratePerKw, TestConstants.feeratePerKw, aliceParams, alice2bob.ref, bobInit, channelFlags, channelConfig, channelType)
    assert(alice2blockchain.expectMsgType[TxPublisher.SetChannelId].channelId === ByteVector32.Zeroes)
    bob ! INPUT_INIT_FUNDEE(ByteVector32.Zeroes, bobParams, bob2alice.ref, aliceInit, channelConfig, channelType)
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
    alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
    bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
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

  def localOrigin(replyTo: ActorRef): Origin.LocalHot = Origin.LocalHot(replyTo, UUID.randomUUID())

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: BlockHeight): (ByteVector32, CMD_ADD_HTLC) = {
    makeCmdAdd(amount, CltvExpiryDelta(144), destination, randomBytes32(), currentBlockHeight, Upstream.Local(UUID.randomUUID()))
  }

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: BlockHeight, paymentPreimage: ByteVector32): (ByteVector32, CMD_ADD_HTLC) = {
    makeCmdAdd(amount, destination, currentBlockHeight, paymentPreimage, Upstream.Local(UUID.randomUUID()))
  }

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: BlockHeight, paymentPreimage: ByteVector32, upstream: Upstream): (ByteVector32, CMD_ADD_HTLC) = {
    makeCmdAdd(amount, CltvExpiryDelta(144), destination, paymentPreimage, currentBlockHeight, upstream)
  }

  def makeCmdAdd(amount: MilliSatoshi, cltvExpiryDelta: CltvExpiryDelta, destination: PublicKey, paymentPreimage: ByteVector32, currentBlockHeight: BlockHeight, upstream: Upstream, replyTo: ActorRef = TestProbe().ref): (ByteVector32, CMD_ADD_HTLC) = {
    val paymentHash: ByteVector32 = Crypto.sha256(paymentPreimage)
    val expiry = cltvExpiryDelta.toCltvExpiry(currentBlockHeight)
    val cmd = OutgoingPaymentPacket.buildCommand(replyTo, upstream, paymentHash, ChannelHop(null, destination, null) :: Nil, PaymentOnion.createSinglePartPayload(amount, expiry, randomBytes32(), None)).get._1.copy(commit = false)
    (paymentPreimage, cmd)
  }

  def addHtlc(amount: MilliSatoshi, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): (ByteVector32, UpdateAddHtlc) = {
    addHtlc(amount, CltvExpiryDelta(144), s, r, s2r, r2s)
  }

  def addHtlc(amount: MilliSatoshi, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, replyTo: ActorRef): (ByteVector32, UpdateAddHtlc) = {
    addHtlc(amount, CltvExpiryDelta(144), s, r, s2r, r2s, replyTo)
  }

  def addHtlc(amount: MilliSatoshi, cltvExpiryDelta: CltvExpiryDelta, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, replyTo: ActorRef = TestProbe().ref): (ByteVector32, UpdateAddHtlc) = {
    val currentBlockHeight = s.underlyingActor.nodeParams.currentBlockHeight
    val (payment_preimage, cmd) = makeCmdAdd(amount, cltvExpiryDelta, r.underlyingActor.nodeParams.nodeId, randomBytes32(), currentBlockHeight, Upstream.Local(UUID.randomUUID()), replyTo)
    val htlc = addHtlc(cmd, s, r, s2r, r2s)
    (payment_preimage, htlc)
  }

  def addHtlc(cmdAdd: CMD_ADD_HTLC, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): UpdateAddHtlc = {
    s ! cmdAdd
    val htlc = s2r.expectMsgType[UpdateAddHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(htlc))
    htlc
  }

  def fulfillHtlc(id: Long, preimage: ByteVector32, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    s ! CMD_FULFILL_HTLC(id, preimage)
    val fulfill = s2r.expectMsgType[UpdateFulfillHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(fulfill))
  }

  def failHtlc(id: Long, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    s ! CMD_FAIL_HTLC(id, Right(TemporaryNodeFailure))
    val fail = s2r.expectMsgType[UpdateFailHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(fail))
  }

  def crossSign(s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
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

  def updateFee(feerate: FeeratePerKw, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    s ! CMD_UPDATE_FEE(feerate, commit = true)
    s2r.expectMsgType[UpdateFee]
    s2r.forward(r)
    s2r.expectMsgType[CommitSig]
    s2r.forward(r)
    r2s.expectMsgType[RevokeAndAck]
    r2s.forward(s)
    r2s.expectMsgType[CommitSig]
    r2s.forward(s)
    s2r.expectMsgType[RevokeAndAck]
    s2r.forward(r)
    awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.spec.commitTxFeerate == feerate)
  }

  def mutualClose(s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, s2blockchain: TestProbe, r2blockchain: TestProbe): Unit = {
    val sender = TestProbe()
    // s initiates a closing
    s ! CMD_CLOSE(sender.ref, None, None)
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

  def localClose(s: TestFSMRef[ChannelState, ChannelData, Channel], s2blockchain: TestProbe): LocalCommitPublished = {
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

    val publishedLocalCommitTx = s2blockchain.expectMsgType[TxPublisher.PublishFinalTx].tx
    assert(publishedLocalCommitTx.txid == commitTx.txid)
    val commitInput = closingState.commitments.commitInput
    Transaction.correctlySpends(publishedLocalCommitTx, Map(commitInput.outPoint -> commitInput.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    if (closingState.commitments.commitmentFormat.isInstanceOf[Transactions.AnchorOutputsCommitmentFormat]) {
      assert(s2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx].txInfo.isInstanceOf[ClaimLocalAnchorOutputTx])
    }
    // if s has a main output in the commit tx (when it has a non-dust balance), it should be claimed
    localCommitPublished.claimMainDelayedOutputTx.foreach(tx => s2blockchain.expectMsg(TxPublisher.PublishFinalTx(tx, tx.fee, None)))
    closingState.commitments.commitmentFormat match {
      case Transactions.DefaultCommitmentFormat =>
        // all htlcs success/timeout should be published as-is, without claiming their outputs
        s2blockchain.expectMsgAllOf(localCommitPublished.htlcTxs.values.toSeq.collect { case Some(tx) => TxPublisher.PublishFinalTx(tx, tx.fee, Some(commitTx.txid)) }: _*)
        assert(localCommitPublished.claimHtlcDelayedTxs.isEmpty)
      case _: Transactions.AnchorOutputsCommitmentFormat =>
        // all htlcs success/timeout should be published as replaceable txs, without claiming their outputs
        val htlcTxs = localCommitPublished.htlcTxs.values.collect { case Some(tx: HtlcTx) => tx }
        val publishedTxs = htlcTxs.map(_ => s2blockchain.expectMsgType[TxPublisher.PublishReplaceableTx])
        assert(publishedTxs.map(_.input).toSet == htlcTxs.map(_.input.outPoint).toSet)
        assert(localCommitPublished.claimHtlcDelayedTxs.isEmpty)
    }

    // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
    assert(s2blockchain.expectMsgType[WatchTxConfirmed].txId === commitTx.txid)
    localCommitPublished.claimMainDelayedOutputTx.foreach(claimMain => assert(s2blockchain.expectMsgType[WatchTxConfirmed].txId === claimMain.tx.txid))

    // we watch outputs of the commitment tx that both parties may spend and anchor outputs
    val watchedOutputIndexes = localCommitPublished.htlcTxs.keySet.map(_.index) ++ localCommitPublished.claimAnchorTxs.collect { case tx: ClaimLocalAnchorOutputTx => tx.input.outPoint.index }
    val spentWatches = watchedOutputIndexes.map(_ => s2blockchain.expectMsgType[WatchOutputSpent])
    spentWatches.foreach(ws => assert(ws.txId == commitTx.txid))
    assert(spentWatches.map(_.outputIndex) == watchedOutputIndexes)
    s2blockchain.expectNoMessage(1 second)

    // s is now in CLOSING state with txs pending for confirmation before going in CLOSED state
    closingState.localCommitPublished.get
  }

  def remoteClose(rCommitTx: Transaction, s: TestFSMRef[ChannelState, ChannelData, Channel], s2blockchain: TestProbe): RemoteCommitPublished = {
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
      s2blockchain.expectMsg(TxPublisher.PublishFinalTx(claimMain, claimMain.fee, None))
    })
    // all htlcs success/timeout should be claimed
    val claimHtlcTxs = remoteCommitPublished.claimHtlcTxs.values.collect { case Some(tx: ClaimHtlcTx) => tx }.toSeq
    claimHtlcTxs.foreach(claimHtlc => Transaction.correctlySpends(claimHtlc.tx, rCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    val publishedClaimHtlcTxs = claimHtlcTxs.map(_ => s2blockchain.expectMsgType[PublishReplaceableTx])
    assert(publishedClaimHtlcTxs.map(_.input).toSet == claimHtlcTxs.map(_.input.outPoint).toSet)

    // we watch the confirmation of the "final" transactions that send funds to our wallets (main delayed output and 2nd stage htlc transactions)
    assert(s2blockchain.expectMsgType[WatchTxConfirmed].txId === rCommitTx.txid)
    remoteCommitPublished.claimMainOutputTx.foreach(claimMain => assert(s2blockchain.expectMsgType[WatchTxConfirmed].txId === claimMain.tx.txid))

    // we watch outputs of the commitment tx that both parties may spend
    val htlcOutputIndexes = remoteCommitPublished.claimHtlcTxs.keySet.map(_.index)
    val spentWatches = htlcOutputIndexes.map(_ => s2blockchain.expectMsgType[WatchOutputSpent])
    spentWatches.foreach(ws => assert(ws.txId == rCommitTx.txid))
    assert(spentWatches.map(_.outputIndex) == htlcOutputIndexes)
    s2blockchain.expectNoMessage(1 second)

    // s is now in CLOSING state with txs pending for confirmation before going in CLOSED state
    remoteCommitPublished
  }

  def channelId(a: TestFSMRef[ChannelState, ChannelData, Channel]): ByteVector32 = a.stateData.channelId

  def getHtlcSuccessTxs(lcp: LocalCommitPublished): Seq[HtlcSuccessTx] = lcp.htlcTxs.values.collect { case Some(tx: HtlcSuccessTx) => tx }.toSeq

  def getHtlcTimeoutTxs(lcp: LocalCommitPublished): Seq[HtlcTimeoutTx] = lcp.htlcTxs.values.collect { case Some(tx: HtlcTimeoutTx) => tx }.toSeq

  def getClaimHtlcSuccessTxs(rcp: RemoteCommitPublished): Seq[ClaimHtlcSuccessTx] = rcp.claimHtlcTxs.values.collect { case Some(tx: ClaimHtlcSuccessTx) => tx }.toSeq

  def getClaimHtlcTimeoutTxs(rcp: RemoteCommitPublished): Seq[ClaimHtlcTimeoutTx] = rcp.claimHtlcTxs.values.collect { case Some(tx: ClaimHtlcTimeoutTx) => tx }.toSeq

}

object ChannelStateTestsHelperMethods {

  case class FakeTxPublisherFactory(txPublisher: TestProbe) extends Channel.TxPublisherFactory {
    override def spawnTxPublisher(context: ActorContext, remoteNodeId: PublicKey): akka.actor.typed.ActorRef[TxPublisher.Command] = txPublisher.ref
  }

}