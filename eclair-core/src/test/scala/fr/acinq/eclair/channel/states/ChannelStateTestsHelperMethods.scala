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

import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, actorRefAdapter}
import akka.actor.{ActorContext, ActorRef, ActorSystem}
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, SatoshiLong, Script, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.blockchain.{DummyOnChainWallet, OnChainPubkeyCache, OnChainWallet, SingleKeyOnChainWallet}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx}
import fr.acinq.eclair.channel.publish._
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.FakeTxPublisherFactory
import fr.acinq.eclair.payment.send.SpontaneousRecipient
import fr.acinq.eclair.payment.{Invoice, OutgoingPaymentPacket}
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.router.Router.{ChannelHop, HopRelayParams, Route}
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol._
import org.scalatest.Assertions
import org.scalatest.concurrent.Eventually

import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._

object ChannelStateTestsTags {
  /** If set, channels will not use option_support_large_channel. */
  val DisableWumbo = "disable_wumbo"
  /** If set, channels will use option_dual_fund. */
  val DualFunding = "dual_funding"
  /** If set, a liquidity ads will be used when opening a channel. */
  val LiquidityAds = "liquidity_ads"
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
  /** If set, initial announcement_signatures and channel_updates will not be intercepted and ignored. */
  val DoNotInterceptGossip = "do_not_intercept_gossip"
  /** If set, no amount will be pushed when opening a channel (by default the initiator pushes a small amount). */
  val NoPushAmount = "no_push_amount"
  /** If set, the non-initiator will push a small amount when opening a dual-funded channel. */
  val NonInitiatorPushAmount = "non_initiator_push_amount"
  /** If set, max-htlc-value-in-flight will be set to the highest possible value for Alice and Bob. */
  val NoMaxHtlcValueInFlight = "no_max_htlc_value_in_flight"
  /** If set, max-htlc-value-in-flight will be set to a low value for Alice. */
  val AliceLowMaxHtlcValueInFlight = "alice_low_max_htlc_value_in_flight"
  /** If set, channels will use option_upfront_shutdown_script. */
  val UpfrontShutdownScript = "option_upfront_shutdown_script"
  /** If set, Alice will have a much higher dust limit than Bob. */
  val HighDustLimitDifferenceAliceBob = "high_dust_limit_difference_alice_bob"
  /** If set, Bob will have a much higher dust limit than Alice. */
  val HighDustLimitDifferenceBobAlice = "high_dust_limit_difference_bob_alice"
  /** If set, Alice and Bob will use a very large tolerance for feerate mismatch. */
  val HighFeerateMismatchTolerance = "high_feerate_mismatch_tolerance"
  /** If set, channels will use option_zeroconf. */
  val ZeroConf = "zeroconf"
  /** If set, channels will use option_scid_alias. */
  val ScidAlias = "scid_alias"
  /** If set, we won't spend anchors to fee-bump commitments without htlcs (no funds at risk). */
  val DontSpendAnchorWithoutHtlcs = "dont-spend-anchor-without-htlcs"
  /** If set, the non-initiator won't allow RBF attempts. */
  val RejectRbfAttempts = "reject_rbf_attempts"
  /** If set, the non-initiator will require a 1-block delay between RBF attempts. */
  val DelayRbfAttempts = "delay_rbf_attempts"
  /** If set, the non-initiator will not enforce any restriction between RBF attempts. */
  val UnlimitedRbfAttempts = "unlimited_rbf_attempts"
  /** If set, channels will adapt their max HTLC amount to the available balance. */
  val AdaptMaxHtlcAmount = "adapt_max_htlc_amount"
  /** If set, closing will use option_simple_close. */
  val SimpleClose = "option_simple_close"
  /** If set, disable option_splice for one node. */
  val DisableSplice = "disable_splice"
  /** If set, channels will use taproot. */
  val OptionSimpleTaprootPhoenix = "option_simple_taproot_phoenix"
  val OptionSimpleTaproot = "option_simple_taproot"
}

trait ChannelStateTestsBase extends Assertions with Eventually {

  case class SetupFixture(alice: TestFSMRef[ChannelState, ChannelData, Channel],
                          bob: TestFSMRef[ChannelState, ChannelData, Channel],
                          aliceOpenReplyTo: TestProbe,
                          alice2bob: TestProbe,
                          bob2alice: TestProbe,
                          alice2blockchain: TestProbe,
                          bob2blockchain: TestProbe,
                          router: TestProbe,
                          alice2relayer: TestProbe,
                          bob2relayer: TestProbe,
                          channelUpdateListener: TestProbe,
                          wallet: OnChainWallet with OnChainPubkeyCache,
                          alicePeer: TestProbe,
                          bobPeer: TestProbe) {
    def currentBlockHeight: BlockHeight = alice.underlyingActor.nodeParams.currentBlockHeight
  }

  case class ChannelParamsFixture(aliceChannelParams: LocalChannelParams,
                                  aliceCommitParams: CommitParams,
                                  aliceInitFeatures: Features[InitFeature],
                                  bobChannelParams: LocalChannelParams,
                                  bobCommitParams: CommitParams,
                                  bobInitFeatures: Features[InitFeature],
                                  channelType: SupportedChannelType,
                                  alice2bob: TestProbe,
                                  bob2alice: TestProbe,
                                  aliceOpenReplyTo: TestProbe) {
    def initChannelAlice(fundingAmount: Satoshi,
                         dualFunded: Boolean = false,
                         requireConfirmedInputs: Boolean = false,
                         channelFlags: ChannelFlags = ChannelFlags(announceChannel = false),
                         requestFunding_opt: Option[LiquidityAds.RequestFunding] = None,
                         pushAmount_opt: Option[MilliSatoshi] = None): INPUT_INIT_CHANNEL_INITIATOR = {
      INPUT_INIT_CHANNEL_INITIATOR(
        temporaryChannelId = ByteVector32.Zeroes,
        fundingAmount = fundingAmount,
        dualFunded = dualFunded,
        commitTxFeerate = channelType.commitmentFormat match {
          case DefaultCommitmentFormat => TestConstants.feeratePerKw
          case _ => TestConstants.anchorOutputsFeeratePerKw
        },
        fundingTxFeerate = TestConstants.feeratePerKw,
        fundingTxFeeBudget_opt = None,
        pushAmount_opt = pushAmount_opt,
        requireConfirmedInputs = requireConfirmedInputs,
        requestFunding_opt = requestFunding_opt,
        localChannelParams = aliceChannelParams,
        proposedCommitParams = ProposedCommitParams(
          localDustLimit = aliceCommitParams.dustLimit,
          localHtlcMinimum = aliceCommitParams.htlcMinimum,
          localMaxHtlcValueInFlight = aliceCommitParams.maxHtlcValueInFlight,
          localMaxAcceptedHtlcs = aliceCommitParams.maxAcceptedHtlcs,
          toRemoteDelay = bobCommitParams.toSelfDelay
        ),
        remote = alice2bob.ref,
        remoteInit = Init(bobInitFeatures),
        channelFlags = channelFlags,
        channelConfig = ChannelConfig.standard,
        channelType = channelType,
        replyTo = aliceOpenReplyTo.ref.toTyped
      )
    }

    def initChannelBob(fundingContribution_opt: Option[LiquidityAds.AddFunding] = None,
                       dualFunded: Boolean = false,
                       requireConfirmedInputs: Boolean = false,
                       pushAmount_opt: Option[MilliSatoshi] = None): INPUT_INIT_CHANNEL_NON_INITIATOR = {
      INPUT_INIT_CHANNEL_NON_INITIATOR(
        temporaryChannelId = ByteVector32.Zeroes,
        fundingContribution_opt = fundingContribution_opt,
        dualFunded = dualFunded,
        pushAmount_opt = pushAmount_opt,
        requireConfirmedInputs = requireConfirmedInputs,
        localChannelParams = bobChannelParams,
        proposedCommitParams = ProposedCommitParams(
          localDustLimit = bobCommitParams.dustLimit,
          localHtlcMinimum = bobCommitParams.htlcMinimum,
          localMaxHtlcValueInFlight = bobCommitParams.maxHtlcValueInFlight,
          localMaxAcceptedHtlcs = bobCommitParams.maxAcceptedHtlcs,
          toRemoteDelay = aliceCommitParams.toSelfDelay
        ),
        remote = bob2alice.ref,
        remoteInit = Init(aliceInitFeatures),
        channelConfig = ChannelConfig.standard,
        channelType = channelType)
    }
  }

  implicit val system: ActorSystem
  val systemA: ActorSystem = ActorSystem("system-alice")
  val systemB: ActorSystem = ActorSystem("system-bob")

  system.registerOnTermination(TestKit.shutdownActorSystem(systemA))
  system.registerOnTermination(TestKit.shutdownActorSystem(systemB))

  def init(nodeParamsA: NodeParams = TestConstants.Alice.nodeParams, nodeParamsB: NodeParams = TestConstants.Bob.nodeParams, wallet_opt: Option[OnChainWallet with OnChainPubkeyCache] = None, tags: Set[String] = Set.empty): SetupFixture = {
    val aliceOpenReplyTo = TestProbe()
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alicePeer = TestProbe()
    val bobPeer = TestProbe()
    TestUtils.forwardOutgoingToPipe(alicePeer, alice2bob.ref)
    TestUtils.forwardOutgoingToPipe(bobPeer, bob2alice.ref)
    val alice2blockchain = TestProbe()
    val bob2blockchain = TestProbe()
    val alice2relayer = TestProbe()
    val bob2relayer = TestProbe()
    val channelUpdateListener = TestProbe()
    systemA.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelUpdate])
    systemA.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelDown])
    systemB.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelUpdate])
    systemB.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelDown])
    val router = TestProbe()
    val (nodeParamsA1, nodeParamsB1) = updateInitFeatures(nodeParamsA, nodeParamsB, tags)
    val finalNodeParamsA = nodeParamsA1
      .modify(_.channelConf.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(5000 sat)
      .modify(_.channelConf.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(1000 sat)
      .modify(_.channelConf.maxRemoteDustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(10000 sat)
      .modify(_.channelConf.maxRemoteDustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(10000 sat)
      .modify(_.channelConf.remoteRbfLimits.maxAttempts).setToIf(tags.contains(ChannelStateTestsTags.UnlimitedRbfAttempts))(100)
      .modify(_.channelConf.remoteRbfLimits.attemptDeltaBlocks).setToIf(tags.contains(ChannelStateTestsTags.UnlimitedRbfAttempts))(0)
      .modify(_.onChainFeeConf.defaultFeerateTolerance.ratioLow).setToIf(tags.contains(ChannelStateTestsTags.HighFeerateMismatchTolerance))(0.000001)
      .modify(_.onChainFeeConf.defaultFeerateTolerance.ratioHigh).setToIf(tags.contains(ChannelStateTestsTags.HighFeerateMismatchTolerance))(1000000)
      .modify(_.onChainFeeConf.spendAnchorWithoutHtlcs).setToIf(tags.contains(ChannelStateTestsTags.DontSpendAnchorWithoutHtlcs))(false)
      .modify(_.channelConf.balanceThresholds).setToIf(tags.contains(ChannelStateTestsTags.AdaptMaxHtlcAmount))(Seq(Channel.BalanceThreshold(1_000 sat, 0 sat), Channel.BalanceThreshold(5_000 sat, 1_000 sat), Channel.BalanceThreshold(10_000 sat, 5_000 sat)))
    val finalNodeParamsB = nodeParamsB1
      .modify(_.channelConf.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(1000 sat)
      .modify(_.channelConf.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(5000 sat)
      .modify(_.channelConf.maxRemoteDustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(10000 sat)
      .modify(_.channelConf.maxRemoteDustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(10000 sat)
      .modify(_.channelConf.remoteRbfLimits.maxAttempts).setToIf(tags.contains(ChannelStateTestsTags.RejectRbfAttempts))(0)
      .modify(_.channelConf.remoteRbfLimits.maxAttempts).setToIf(tags.contains(ChannelStateTestsTags.UnlimitedRbfAttempts))(100)
      .modify(_.channelConf.remoteRbfLimits.attemptDeltaBlocks).setToIf(tags.contains(ChannelStateTestsTags.DelayRbfAttempts))(1)
      .modify(_.channelConf.remoteRbfLimits.attemptDeltaBlocks).setToIf(tags.contains(ChannelStateTestsTags.UnlimitedRbfAttempts))(0)
      .modify(_.onChainFeeConf.defaultFeerateTolerance.ratioLow).setToIf(tags.contains(ChannelStateTestsTags.HighFeerateMismatchTolerance))(0.000001)
      .modify(_.onChainFeeConf.defaultFeerateTolerance.ratioHigh).setToIf(tags.contains(ChannelStateTestsTags.HighFeerateMismatchTolerance))(1000000)
      .modify(_.onChainFeeConf.spendAnchorWithoutHtlcs).setToIf(tags.contains(ChannelStateTestsTags.DontSpendAnchorWithoutHtlcs))(false)
      .modify(_.channelConf.balanceThresholds).setToIf(tags.contains(ChannelStateTestsTags.AdaptMaxHtlcAmount))(Seq(Channel.BalanceThreshold(1_000 sat, 0 sat), Channel.BalanceThreshold(5_000 sat, 1_000 sat), Channel.BalanceThreshold(10_000 sat, 5_000 sat)))
    val wallet = wallet_opt match {
      case Some(wallet) => wallet
      case None => if (tags.contains(ChannelStateTestsTags.DualFunding)) new SingleKeyOnChainWallet() else new DummyOnChainWallet()
    }
    val alice: TestFSMRef[ChannelState, ChannelData, Channel] = {
      implicit val system: ActorSystem = systemA
      TestFSMRef(new Channel(finalNodeParamsA, TestConstants.Alice.channelKeys(), wallet, finalNodeParamsB.nodeId, alice2blockchain.ref, alice2relayer.ref, FakeTxPublisherFactory(alice2blockchain)), alicePeer.ref)
    }
    val bob: TestFSMRef[ChannelState, ChannelData, Channel] = {
      implicit val system: ActorSystem = systemB
      TestFSMRef(new Channel(finalNodeParamsB, TestConstants.Bob.channelKeys(), wallet, finalNodeParamsA.nodeId, bob2blockchain.ref, bob2relayer.ref, FakeTxPublisherFactory(bob2blockchain)), bobPeer.ref)
    }
    SetupFixture(alice, bob, aliceOpenReplyTo, alice2bob, bob2alice, alice2blockchain, bob2blockchain, router, alice2relayer, bob2relayer, channelUpdateListener, wallet, alicePeer, bobPeer)
  }

  def updateInitFeatures(nodeParamsA: NodeParams, nodeParamsB: NodeParams, tags: Set[String]): (NodeParams, NodeParams) = {
    val nodeParamsA1 = nodeParamsA.copy(features = nodeParamsA.features
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.DisableWumbo))(_.removed(Features.Wumbo))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.StaticRemoteKey))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.AnchorOutputs))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional).updated(Features.AnchorOutputs, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional).updated(Features.AnchorOutputs, FeatureSupport.Optional).updated(Features.AnchorOutputsZeroFeeHtlcTx, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.ShutdownAnySegwit))(_.updated(Features.ShutdownAnySegwit, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.UpfrontShutdownScript))(_.updated(Features.UpfrontShutdownScript, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.ZeroConf))(_.updated(Features.ZeroConf, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.ScidAlias))(_.updated(Features.ScidAlias, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.DualFunding))(_.updated(Features.DualFunding, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.SimpleClose))(_.updated(Features.SimpleClose, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.OptionSimpleTaprootPhoenix))(_.updated(Features.SimpleTaprootChannelsPhoenix, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.OptionSimpleTaproot))(_.updated(Features.SimpleTaprootChannelsStaging, FeatureSupport.Optional))
    )
    val nodeParamsB1 = nodeParamsB.copy(features = nodeParamsB.features
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.DisableWumbo))(_.removed(Features.Wumbo))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.StaticRemoteKey))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.AnchorOutputs))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional).updated(Features.AnchorOutputs, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs))(_.updated(Features.StaticRemoteKey, FeatureSupport.Optional).updated(Features.AnchorOutputs, FeatureSupport.Optional).updated(Features.AnchorOutputsZeroFeeHtlcTx, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.ShutdownAnySegwit))(_.updated(Features.ShutdownAnySegwit, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.UpfrontShutdownScript))(_.updated(Features.UpfrontShutdownScript, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.ZeroConf))(_.updated(Features.ZeroConf, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.ScidAlias))(_.updated(Features.ScidAlias, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.DualFunding))(_.updated(Features.DualFunding, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.SimpleClose))(_.updated(Features.SimpleClose, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.DisableSplice))(_.removed(Features.SplicePrototype))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.OptionSimpleTaprootPhoenix))(_.updated(Features.SimpleTaprootChannelsPhoenix, FeatureSupport.Optional))
      .modify(_.activated).usingIf(tags.contains(ChannelStateTestsTags.OptionSimpleTaproot))(_.updated(Features.SimpleTaprootChannelsStaging, FeatureSupport.Optional))
    )
    (nodeParamsA1, nodeParamsB1)
  }

  def computeChannelParams(setup: SetupFixture, tags: Set[String], channelFlags: ChannelFlags = ChannelFlags(announceChannel = false)): ChannelParamsFixture = {
    import setup._

    val (nodeParamsA, nodeParamsB) = updateInitFeatures(alice.underlyingActor.nodeParams, bob.underlyingActor.nodeParams, tags)
    val aliceInitFeatures = nodeParamsA.features.initFeatures()
    val bobInitFeatures = nodeParamsB.features.initFeatures()
    val channelType = ChannelTypes.defaultFromFeatures(aliceInitFeatures, bobInitFeatures, announceChannel = channelFlags.announceChannel)

    // those features can only be enabled with AnchorOutputsZeroFeeHtlcTxs, this is to prevent incompatible test configurations
    if (tags.contains(ChannelStateTestsTags.ZeroConf)) assert(tags.contains(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs) || tags.contains(ChannelStateTestsTags.AnchorOutputs) || tags.contains(ChannelStateTestsTags.OptionSimpleTaprootPhoenix) || tags.contains(ChannelStateTestsTags.OptionSimpleTaproot), "invalid test configuration")
    if (tags.contains(ChannelStateTestsTags.ScidAlias)) assert(tags.contains(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs) || tags.contains(ChannelStateTestsTags.AnchorOutputs) || tags.contains(ChannelStateTestsTags.OptionSimpleTaprootPhoenix) || tags.contains(ChannelStateTestsTags.OptionSimpleTaproot), "invalid test configuration")

    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val aliceChannelParams = Alice.channelParams
      .modify(_.initFeatures).setTo(aliceInitFeatures)
      .modify(_.walletStaticPaymentBasepoint).setToIf(channelType.paysDirectlyToWallet)(Some(Await.result(wallet.getP2wpkhPubkey(), 10 seconds)))
      .modify(_.initialRequestedChannelReserve_opt).setToIf(tags.contains(ChannelStateTestsTags.DualFunding))(None)
      .modify(_.upfrontShutdownScript_opt).setToIf(tags.contains(ChannelStateTestsTags.UpfrontShutdownScript))(Some(Script.write(Script.pay2wpkh(Await.result(wallet.getP2wpkhPubkey(), 10 seconds)))))
    val aliceCommitParams = CommitParams(nodeParamsA.channelConf.dustLimit, nodeParamsA.channelConf.htlcMinimum, nodeParamsA.channelConf.maxHtlcValueInFlight(TestConstants.fundingSatoshis, unlimited = false), nodeParamsA.channelConf.maxAcceptedHtlcs, nodeParamsB.channelConf.toRemoteDelay)
      .modify(_.maxHtlcValueInFlight).setToIf(tags.contains(ChannelStateTestsTags.NoMaxHtlcValueInFlight))(UInt64.MaxValue)
      .modify(_.maxHtlcValueInFlight).setToIf(tags.contains(ChannelStateTestsTags.AliceLowMaxHtlcValueInFlight))(UInt64(150_000_000))
      .modify(_.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(5000 sat)
      .modify(_.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(1000 sat)
    val bobChannelParams = Bob.channelParams
      .modify(_.initFeatures).setTo(bobInitFeatures)
      .modify(_.walletStaticPaymentBasepoint).setToIf(channelType.paysDirectlyToWallet)(Some(Await.result(wallet.getP2wpkhPubkey(), 10 seconds)))
      .modify(_.initialRequestedChannelReserve_opt).setToIf(tags.contains(ChannelStateTestsTags.DualFunding))(None)
      .modify(_.upfrontShutdownScript_opt).setToIf(tags.contains(ChannelStateTestsTags.UpfrontShutdownScript))(Some(Script.write(Script.pay2wpkh(Await.result(wallet.getP2wpkhPubkey(), 10 seconds)))))
    val bobCommitParams = CommitParams(nodeParamsB.channelConf.dustLimit, nodeParamsB.channelConf.htlcMinimum, nodeParamsB.channelConf.maxHtlcValueInFlight(TestConstants.fundingSatoshis, unlimited = false), nodeParamsB.channelConf.maxAcceptedHtlcs, nodeParamsA.channelConf.toRemoteDelay)
      .modify(_.maxHtlcValueInFlight).setToIf(tags.contains(ChannelStateTestsTags.NoMaxHtlcValueInFlight))(UInt64.MaxValue)
      .modify(_.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceAliceBob))(1000 sat)
      .modify(_.dustLimit).setToIf(tags.contains(ChannelStateTestsTags.HighDustLimitDifferenceBobAlice))(5000 sat)

    ChannelParamsFixture(aliceChannelParams, aliceCommitParams, aliceInitFeatures, bobChannelParams, bobCommitParams, bobInitFeatures, channelType, alice2bob, bob2alice, aliceOpenReplyTo)
  }

  def reachNormal(setup: SetupFixture, tags: Set[String] = Set.empty): Transaction = {
    import setup._

    val channelFlags = ChannelFlags(announceChannel = tags.contains(ChannelStateTestsTags.ChannelsPublic))
    val channelParams = computeChannelParams(setup, tags, channelFlags)
    val fundingAmount = TestConstants.fundingSatoshis
    val initiatorPushAmount = if (tags.contains(ChannelStateTestsTags.NoPushAmount)) None else Some(TestConstants.initiatorPushAmount)
    val nonInitiatorPushAmount = if (tags.contains(ChannelStateTestsTags.NonInitiatorPushAmount)) Some(TestConstants.nonInitiatorPushAmount) else None
    val dualFunded = tags.contains(ChannelStateTestsTags.DualFunding)
    val liquidityAds = tags.contains(ChannelStateTestsTags.LiquidityAds)
    val requestFunds_opt = if (liquidityAds) {
      Some(LiquidityAds.RequestFunding(TestConstants.nonInitiatorFundingSatoshis, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalance))
    } else {
      None
    }
    val nonInitiatorFunding_opt = if (dualFunded) {
      val leaseRates_opt = if (liquidityAds) Some(TestConstants.defaultLiquidityRates) else None
      Some(LiquidityAds.AddFunding(TestConstants.nonInitiatorFundingSatoshis, leaseRates_opt))
    } else {
      None
    }

    val eventListener = TestProbe()
    systemA.eventStream.subscribe(eventListener.ref, classOf[TransactionPublished])

    alice ! channelParams.initChannelAlice(fundingAmount, dualFunded, pushAmount_opt = initiatorPushAmount, requestFunding_opt = requestFunds_opt, channelFlags = channelFlags)
    assert(alice2blockchain.expectMsgType[TxPublisher.SetChannelId].channelId == ByteVector32.Zeroes)
    bob ! channelParams.initChannelBob(nonInitiatorFunding_opt, dualFunded, pushAmount_opt = nonInitiatorPushAmount)
    assert(bob2blockchain.expectMsgType[TxPublisher.SetChannelId].channelId == ByteVector32.Zeroes)

    val fundingTx = if (!dualFunded) {
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      assert(alice2blockchain.expectMsgType[TxPublisher.SetChannelId].channelId != ByteVector32.Zeroes)
      assert(bob2blockchain.expectMsgType[TxPublisher.SetChannelId].channelId != ByteVector32.Zeroes)
      val fundingTx = eventListener.expectMsgType[TransactionPublished].tx
      eventually(assert(alice.stateName == WAIT_FOR_FUNDING_CONFIRMED))
      eventually(assert(bob.stateName == WAIT_FOR_FUNDING_CONFIRMED))
      if (channelParams.channelType.features.contains(Features.ZeroConf)) {
        alice2blockchain.expectMsgType[WatchPublished]
        bob2blockchain.expectMsgType[WatchPublished]
        alice ! WatchPublishedTriggered(fundingTx)
        bob ! WatchPublishedTriggered(fundingTx)
        alice2blockchain.expectMsgType[WatchFundingConfirmed]
        bob2blockchain.expectMsgType[WatchFundingConfirmed]
      } else {
        alice2blockchain.expectMsgType[WatchFundingConfirmed]
        bob2blockchain.expectMsgType[WatchFundingConfirmed]
        alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
        bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
        alice2blockchain.expectMsgType[WatchFundingSpent]
        bob2blockchain.expectMsgType[WatchFundingSpent]
      }
      eventually(assert(alice.stateName == WAIT_FOR_CHANNEL_READY))
      eventually(assert(bob.stateName == WAIT_FOR_CHANNEL_READY))
      alice2bob.expectMsgType[ChannelReady]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[ChannelReady]
      bob2alice.forward(alice)
      fundingTx
    } else {
      alice2bob.expectMsgType[OpenDualFundedChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptDualFundedChannel]
      bob2alice.forward(alice)
      assert(alice2blockchain.expectMsgType[TxPublisher.SetChannelId].channelId != ByteVector32.Zeroes)
      assert(bob2blockchain.expectMsgType[TxPublisher.SetChannelId].channelId != ByteVector32.Zeroes)
      alice2bob.expectMsgType[TxAddInput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxAddInput]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxAddOutput]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxAddOutput]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxComplete]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxComplete]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxSignatures]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[TxSignatures]
      alice2bob.forward(bob)
      val fundingTx = eventListener.expectMsgType[TransactionPublished].tx
      eventually(assert(alice.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED))
      eventually(assert(bob.stateName == WAIT_FOR_DUAL_FUNDING_CONFIRMED))
      if (channelParams.channelType.features.contains(Features.ZeroConf)) {
        alice2blockchain.expectMsgType[WatchPublished]
        bob2blockchain.expectMsgType[WatchPublished]
        alice ! WatchPublishedTriggered(fundingTx)
        bob ! WatchPublishedTriggered(fundingTx)
        alice2blockchain.expectMsgType[WatchFundingConfirmed]
        bob2blockchain.expectMsgType[WatchFundingConfirmed]
      } else {
        alice2blockchain.expectMsgType[WatchFundingConfirmed]
        bob2blockchain.expectMsgType[WatchFundingConfirmed]
        alice ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
        bob ! WatchFundingConfirmedTriggered(BlockHeight(400000), 42, fundingTx)
        alice2blockchain.expectMsgType[WatchFundingSpent]
        bob2blockchain.expectMsgType[WatchFundingSpent]
      }
      alice2bob.expectMsgType[ChannelReady]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[ChannelReady]
      bob2alice.forward(alice)
      fundingTx
    }

    if (!tags.contains(ChannelStateTestsTags.DoNotInterceptGossip)) {
      if (tags.contains(ChannelStateTestsTags.ChannelsPublic) && !channelParams.channelType.features.contains(Features.ZeroConf)) {
        alice2bob.expectMsgType[AnnouncementSignatures]
        bob2alice.expectMsgType[AnnouncementSignatures]
      }
      // we don't forward the channel updates, in reality they would be processed by the router
      alice2bob.expectMsgType[ChannelUpdate]
      bob2alice.expectMsgType[ChannelUpdate]
    }
    eventually(assert(alice.stateName == NORMAL))
    eventually(assert(bob.stateName == NORMAL))

    // x2 because alice and bob share the same relayer
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    channelUpdateListener.expectMsgType[LocalChannelUpdate]
    fundingTx
  }

  def channelId(a: TestFSMRef[ChannelState, ChannelData, Channel]): ByteVector32 = a.stateData.channelId

  def localOrigin(replyTo: ActorRef): Origin.Hot = Origin.Hot(replyTo, localUpstream())

  def localUpstream(): Upstream.Local = Upstream.Local(UUID.randomUUID())

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: BlockHeight): (ByteVector32, CMD_ADD_HTLC) = {
    makeCmdAdd(amount, CltvExpiryDelta(144), destination, randomBytes32(), currentBlockHeight, Upstream.Local(UUID.randomUUID()))
  }

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: BlockHeight, paymentPreimage: ByteVector32): (ByteVector32, CMD_ADD_HTLC) = {
    makeCmdAdd(amount, destination, currentBlockHeight, paymentPreimage, Upstream.Local(UUID.randomUUID()))
  }

  def makeCmdAdd(amount: MilliSatoshi, destination: PublicKey, currentBlockHeight: BlockHeight, paymentPreimage: ByteVector32, upstream: Upstream.Hot): (ByteVector32, CMD_ADD_HTLC) = {
    makeCmdAdd(amount, CltvExpiryDelta(144), destination, paymentPreimage, currentBlockHeight, upstream)
  }

  def makeCmdAdd(amount: MilliSatoshi, cltvExpiryDelta: CltvExpiryDelta, destination: PublicKey, paymentPreimage: ByteVector32, currentBlockHeight: BlockHeight, upstream: Upstream.Hot, replyTo: ActorRef = TestProbe().ref): (ByteVector32, CMD_ADD_HTLC) = {
    val paymentHash = Crypto.sha256(paymentPreimage)
    val expiry = cltvExpiryDelta.toCltvExpiry(currentBlockHeight)
    val recipient = SpontaneousRecipient(destination, amount, expiry, paymentPreimage)
    val Right(payment) = OutgoingPaymentPacket.buildOutgoingPayment(Origin.Hot(replyTo, upstream), paymentHash, makeSingleHopRoute(amount, destination), recipient, Reputation.Score.max)
    (paymentPreimage, payment.cmd.copy(commit = false))
  }

  def makeSingleHopRoute(amount: MilliSatoshi, destination: PublicKey): Route = {
    val dummyParams = HopRelayParams.FromHint(Invoice.ExtraEdge(randomKey().publicKey, destination, ShortChannelId(0), 0 msat, 0, CltvExpiryDelta(0), 0 msat, None))
    Route(amount, Seq(ChannelHop(ShortChannelId(0), dummyParams.extraHop.sourceNodeId, dummyParams.extraHop.targetNodeId, dummyParams)), None)
  }

  def addHtlc(amount: MilliSatoshi, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): (ByteVector32, UpdateAddHtlc) = {
    addHtlc(amount, CltvExpiryDelta(144), s, r, s2r, r2s)
  }

  def addHtlc(amount: MilliSatoshi, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, upstream: Upstream.Hot): (ByteVector32, UpdateAddHtlc) = {
    addHtlc(amount, CltvExpiryDelta(144), s, r, s2r, r2s, ActorRef.noSender, upstream)
  }

  def addHtlc(amount: MilliSatoshi, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, replyTo: ActorRef): (ByteVector32, UpdateAddHtlc) = {
    addHtlc(amount, CltvExpiryDelta(144), s, r, s2r, r2s, replyTo)
  }

  def addHtlc(amount: MilliSatoshi, cltvExpiryDelta: CltvExpiryDelta, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, replyTo: ActorRef = TestProbe().ref, upstream: Upstream.Hot = Upstream.Local(UUID.randomUUID())): (ByteVector32, UpdateAddHtlc) = {
    val currentBlockHeight = s.underlyingActor.nodeParams.currentBlockHeight
    val (paymentPreimage, cmd) = makeCmdAdd(amount, cltvExpiryDelta, r.underlyingActor.nodeParams.nodeId, randomBytes32(), currentBlockHeight, upstream, replyTo)
    val htlc = addHtlc(cmd, s, r, s2r, r2s)
    (paymentPreimage, htlc)
  }

  def addHtlc(cmdAdd: CMD_ADD_HTLC, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): UpdateAddHtlc = {
    s ! cmdAdd
    val htlc = s2r.expectMsgType[UpdateAddHtlc]
    s2r.forward(r)
    eventually(assert(r.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.changes.remoteChanges.proposed.contains(htlc)))
    htlc
  }

  def fulfillHtlc(id: Long, preimage: ByteVector32, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    s ! CMD_FULFILL_HTLC(id, preimage, None)
    val fulfill = s2r.expectMsgType[UpdateFulfillHtlc]
    s2r.forward(r)
    eventually(assert(r.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.changes.remoteChanges.proposed.contains(fulfill)))
  }

  def failHtlc(id: Long, s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    s ! CMD_FAIL_HTLC(id, FailureReason.LocalFailure(TemporaryNodeFailure()), None)
    val fail = s2r.expectMsgType[UpdateFailHtlc]
    s2r.forward(r)
    eventually(assert(r.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.changes.remoteChanges.proposed.contains(fail)))
  }

  def crossSign(s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe): Unit = {
    val sender = TestProbe()
    val sCommitIndex = s.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.localCommitIndex
    val rCommitIndex = r.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.localCommitIndex
    val rHasChanges = r.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.changes.localHasChanges
    s ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    s2r.expectMsgType[CommitSigs]
    s2r.forward(r)
    r2s.expectMsgType[RevokeAndAck]
    r2s.forward(s)
    r2s.expectMsgType[CommitSigs]
    r2s.forward(s)
    s2r.expectMsgType[RevokeAndAck]
    s2r.forward(r)
    if (rHasChanges) {
      s2r.expectMsgType[CommitSigs]
      s2r.forward(r)
      r2s.expectMsgType[RevokeAndAck]
      r2s.forward(s)
      eventually {
        assert(s.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.localCommitIndex == sCommitIndex + 1)
        assert(s.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.remoteCommitIndex == rCommitIndex + 2)
        assert(r.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.localCommitIndex == rCommitIndex + 2)
        assert(r.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.remoteCommitIndex == sCommitIndex + 1)
      }
    } else {
      eventually {
        assert(s.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.localCommitIndex == sCommitIndex + 1)
        assert(s.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.remoteCommitIndex == rCommitIndex + 1)
        assert(r.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.localCommitIndex == rCommitIndex + 1)
        assert(r.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.remoteCommitIndex == sCommitIndex + 1)
      }
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
    eventually(assert(s.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.latest.localCommit.spec.commitTxFeerate == feerate))
  }

  def mutualClose(s: TestFSMRef[ChannelState, ChannelData, Channel], r: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, r2s: TestProbe, s2blockchain: TestProbe, r2blockchain: TestProbe): Unit = {
    val sender = TestProbe()
    // s initiates a closing
    s ! CMD_CLOSE(sender.ref, None, None)
    s2r.expectMsgType[Shutdown]
    s2r.forward(r)
    r2s.expectMsgType[Shutdown]
    r2s.forward(s)
    if (s.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.localChannelParams.initFeatures.hasFeature(Features.SimpleClose)) {
      s2r.expectMsgType[ClosingComplete]
      s2r.forward(r)
      r2s.expectMsgType[ClosingComplete]
      r2s.forward(s)
      r2s.expectMsgType[ClosingSig]
      r2s.forward(s)
      val sTx = r2blockchain.expectMsgType[PublishFinalTx].tx
      r2blockchain.expectWatchTxConfirmed(sTx.txid)
      s2r.expectMsgType[ClosingSig]
      s2r.forward(r)
      val rTx = s2blockchain.expectMsgType[PublishFinalTx].tx
      s2blockchain.expectWatchTxConfirmed(rTx.txid)
      assert(s2blockchain.expectMsgType[PublishFinalTx].tx.txid == sTx.txid)
      s2blockchain.expectWatchTxConfirmed(sTx.txid)
      assert(r2blockchain.expectMsgType[PublishFinalTx].tx.txid == rTx.txid)
      r2blockchain.expectWatchTxConfirmed(rTx.txid)
    } else {
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
      eventually {
        assert(s.stateName == CLOSING)
        assert(r.stateName == CLOSING)
      }
    }
  }

  case class PublishedForceCloseTxs(mainTx_opt: Option[Transaction], anchorTx_opt: Option[Transaction], htlcSuccessTxs: Seq[Transaction], htlcTimeoutTxs: Seq[Transaction]) {
    val htlcTxs: Seq[Transaction] = htlcSuccessTxs ++ htlcTimeoutTxs
  }

  def localClose(s: TestFSMRef[ChannelState, ChannelData, Channel], s2blockchain: TestProbe, htlcSuccessCount: Int = 0, htlcTimeoutCount: Int = 0): (LocalCommitPublished, PublishedForceCloseTxs) = {
    s ! Error(ByteVector32.Zeroes, "oops")
    eventually(assert(s.stateName == CLOSING))
    val closingState = s.stateData.asInstanceOf[DATA_CLOSING]
    assert(closingState.localCommitPublished.isDefined)
    val localCommitPublished = closingState.localCommitPublished.get
    // It may be strictly greater if we're waiting for preimages for some of our HTLC-success txs, or if we're ignoring
    // HTLCs that where failed downstream or not relayed.
    assert(localCommitPublished.incomingHtlcs.size >= htlcSuccessCount)
    assert(localCommitPublished.outgoingHtlcs.size == htlcTimeoutCount)

    val commitTx = s2blockchain.expectFinalTxPublished("commit-tx").tx
    assert(commitTx.txid == closingState.commitments.latest.localCommit.txId)
    val commitInput = closingState.commitments.latest.commitInput(s.underlyingActor.channelKeys)
    Transaction.correctlySpends(commitTx, Map(commitInput.outPoint -> commitInput.txOut), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    val publishedAnchorTx_opt = closingState.commitments.latest.commitmentFormat match {
      case DefaultCommitmentFormat => None
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => Some(s2blockchain.expectReplaceableTxPublished[ClaimLocalAnchorTx].tx)
    }
    // if s has a main output in the commit tx (when it has a non-dust balance), it should be claimed
    val publishedMainTx_opt = localCommitPublished.localOutput_opt.map(_ => s2blockchain.expectFinalTxPublished("local-main-delayed").tx)
    val (publishedHtlcSuccessTxs, publishedHtlcTimeoutTxs) = closingState.commitments.latest.commitmentFormat match {
      case Transactions.DefaultCommitmentFormat =>
        // all htlcs success/timeout should be published as-is, we cannot RBF
        val publishedHtlcTxs = (0 until htlcSuccessCount + htlcTimeoutCount).map { _ =>
          val htlcTx = s2blockchain.expectMsgType[PublishFinalTx]
          assert(htlcTx.parentTx_opt.contains(commitTx.txid))
          assert(localCommitPublished.htlcOutputs.contains(htlcTx.input))
          assert(htlcTx.desc == "htlc-success" || htlcTx.desc == "htlc-timeout")
          htlcTx
        }
        val successTxs = publishedHtlcTxs.filter(_.desc == "htlc-success").map(_.tx)
        val timeoutTxs = publishedHtlcTxs.filter(_.desc == "htlc-timeout").map(_.tx)
        (successTxs, timeoutTxs)
      case _: Transactions.AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat =>
        // all htlcs success/timeout should be published as replaceable txs
        val publishedHtlcTxs = (0 until htlcSuccessCount + htlcTimeoutCount).map { _ =>
          val htlcTx = s2blockchain.expectMsgType[PublishReplaceableTx]
          assert(htlcTx.commitTx == commitTx)
          assert(localCommitPublished.htlcOutputs.contains(htlcTx.txInfo.input.outPoint))
          htlcTx.txInfo
        }
        // the publisher actors will sign the HTLC transactions, so we sign them here to test witness validity
        val successTxs = publishedHtlcTxs.collect { case tx: HtlcSuccessTx =>
          assert(localCommitPublished.incomingHtlcs.get(tx.input.outPoint).contains(tx.htlcId))
          tx.sign()
        }
        val timeoutTxs = publishedHtlcTxs.collect { case tx: HtlcTimeoutTx =>
          assert(localCommitPublished.outgoingHtlcs.get(tx.input.outPoint).contains(tx.htlcId))
          tx.sign()
        }
        (successTxs, timeoutTxs)
    }
    assert(publishedHtlcSuccessTxs.size == htlcSuccessCount)
    assert(publishedHtlcTimeoutTxs.size == htlcTimeoutCount)
    (publishedHtlcSuccessTxs ++ publishedHtlcTimeoutTxs).foreach(htlcTx => Transaction.correctlySpends(htlcTx, commitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    // we're not claiming the outputs of htlc txs yet
    assert(localCommitPublished.htlcDelayedOutputs.isEmpty)

    // we watch the confirmation of the commitment transaction
    s2blockchain.expectWatchTxConfirmed(commitTx.txid)

    // we watch outputs of the commitment tx that we want to claim
    localCommitPublished.localOutput_opt.foreach(outpoint => s2blockchain.expectWatchOutputSpent(outpoint))
    localCommitPublished.anchorOutput_opt.foreach(outpoint => s2blockchain.expectWatchOutputSpent(outpoint))
    s2blockchain.expectWatchOutputsSpent(localCommitPublished.htlcOutputs.toSeq)
    s2blockchain.expectNoMessage(100 millis)

    // once our closing transactions are published, we watch for their confirmation
    (publishedMainTx_opt ++ publishedAnchorTx_opt ++ publishedHtlcSuccessTxs ++ publishedHtlcTimeoutTxs).foreach(tx => {
      s ! WatchOutputSpentTriggered(tx.txOut.headOption.map(_.amount).getOrElse(330 sat), tx)
      s2blockchain.expectWatchTxConfirmed(tx.txid)
    })

    // s is now in CLOSING state with txs pending for confirmation before going in CLOSED state
    val publishedTxs = PublishedForceCloseTxs(publishedMainTx_opt, publishedAnchorTx_opt, publishedHtlcSuccessTxs, publishedHtlcTimeoutTxs)
    (localCommitPublished, publishedTxs)
  }

  def remoteClose(rCommitTx: Transaction, s: TestFSMRef[ChannelState, ChannelData, Channel], s2blockchain: TestProbe, htlcSuccessCount: Int = 0, htlcTimeoutCount: Int = 0): (RemoteCommitPublished, PublishedForceCloseTxs) = {
    // we make s believe r unilaterally closed the channel
    s ! WatchFundingSpentTriggered(rCommitTx)
    eventually(assert(s.stateName == CLOSING))
    val closingData = s.stateData.asInstanceOf[DATA_CLOSING]
    val remoteCommitPublished_opt = closingData.remoteCommitPublished.orElse(closingData.nextRemoteCommitPublished).orElse(closingData.futureRemoteCommitPublished)
    assert(remoteCommitPublished_opt.isDefined)
    assert(closingData.localCommitPublished.isEmpty)
    val remoteCommitPublished = remoteCommitPublished_opt.get
    // It may be strictly greater if we're waiting for preimages for some of our HTLC-success txs, or if we're ignoring
    // HTLCs that where failed downstream or not relayed. Note that since this is the remote commit, IN/OUT are inverted.
    assert(remoteCommitPublished.incomingHtlcs.size >= htlcSuccessCount)
    assert(remoteCommitPublished.outgoingHtlcs.size == htlcTimeoutCount)

    // If anchor outputs is used, we use the anchor output to bump the fees if necessary.
    val publishedAnchorTx_opt = closingData.commitments.latest.commitmentFormat match {
      case _: AnchorOutputsCommitmentFormat | _: SimpleTaprootChannelCommitmentFormat => Some(s2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx].tx)
      case Transactions.DefaultCommitmentFormat => None
    }
    // if s has a main output in the commit tx (when it has a non-dust balance), it should be claimed
    val publishedMainTx_opt = remoteCommitPublished.localOutput_opt.map(_ => s2blockchain.expectFinalTxPublished("remote-main-delayed").tx)
    publishedMainTx_opt.foreach(tx => Transaction.correctlySpends(tx, rCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    // all htlcs success/timeout should be claimed
    val publishedClaimHtlcTxs = (0 until htlcSuccessCount + htlcTimeoutCount).map { _ =>
      val claimHtlcTx = s2blockchain.expectMsgType[PublishReplaceableTx]
      assert(claimHtlcTx.commitTx == rCommitTx)
      assert(remoteCommitPublished.htlcOutputs.contains(claimHtlcTx.input))
      claimHtlcTx.txInfo
    }
    // the publisher actors will sign the HTLC transactions, so we sign them here to test witness validity
    val publishedHtlcSuccessTxs = publishedClaimHtlcTxs.collect { case tx: ClaimHtlcSuccessTx =>
      assert(remoteCommitPublished.incomingHtlcs.get(tx.input.outPoint).contains(tx.htlcId))
      tx.sign()
    }
    assert(publishedHtlcSuccessTxs.size == htlcSuccessCount)
    val publishedHtlcTimeoutTxs = publishedClaimHtlcTxs.collect { case tx: ClaimHtlcTimeoutTx =>
      assert(remoteCommitPublished.outgoingHtlcs.get(tx.input.outPoint).contains(tx.htlcId))
      tx.sign()
    }
    assert(publishedHtlcTimeoutTxs.size == htlcTimeoutCount)
    (publishedHtlcSuccessTxs ++ publishedHtlcTimeoutTxs).foreach(htlcTx => Transaction.correctlySpends(htlcTx, rCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    // we watch the confirmation of the commitment transaction
    s2blockchain.expectWatchTxConfirmed(rCommitTx.txid)

    // we watch outputs of the commitment tx that we want to claim
    remoteCommitPublished.localOutput_opt.foreach(outpoint => s2blockchain.expectWatchOutputSpent(outpoint))
    remoteCommitPublished.anchorOutput_opt.foreach(outpoint => s2blockchain.expectWatchOutputSpent(outpoint))
    s2blockchain.expectWatchOutputsSpent(remoteCommitPublished.htlcOutputs.toSeq)
    s2blockchain.expectNoMessage(100 millis)

    // once our closing transactions are published, we watch for their confirmation
    (publishedMainTx_opt ++ publishedAnchorTx_opt ++ publishedHtlcSuccessTxs ++ publishedHtlcTimeoutTxs).foreach(tx => {
      s ! WatchOutputSpentTriggered(tx.txOut.headOption.map(_.amount).getOrElse(330 sat), tx)
      s2blockchain.expectWatchTxConfirmed(tx.txid)
    })

    // s is now in CLOSING state with txs pending for confirmation before going in CLOSED state
    val publishedTxs = PublishedForceCloseTxs(publishedMainTx_opt, publishedAnchorTx_opt, publishedHtlcSuccessTxs, publishedHtlcTimeoutTxs)
    (remoteCommitPublished, publishedTxs)
  }

}

object ChannelStateTestsBase {

  case class FakeTxPublisherFactory(txPublisher: TestProbe) extends Channel.TxPublisherFactory {
    override def spawnTxPublisher(context: ActorContext, remoteNodeId: PublicKey): akka.actor.typed.ActorRef[TxPublisher.Command] = txPublisher.ref
  }

  implicit class PimpTestFSM(private val channel: TestFSMRef[ChannelState, ChannelData, Channel]) {
    val nodeParams: NodeParams = channel.underlyingActor.nodeParams

    def commitments: Commitments = channel.stateData.asInstanceOf[ChannelDataWithCommitments].commitments

    def signCommitTx(): Transaction = commitments.latest.fullySignedLocalCommitTx(channel.underlyingActor.channelKeys)

    def htlcTxs(): Seq[UnsignedHtlcTx] = commitments.latest.htlcTxs(channel.underlyingActor.channelKeys).map(_._1)

    def setBitcoinCoreFeerates(feerates: FeeratesPerKw): Unit = channel.underlyingActor.nodeParams.setBitcoinCoreFeerates(feerates)

    def setBitcoinCoreFeerate(feerate: FeeratePerKw): Unit = setBitcoinCoreFeerates(FeeratesPerKw.single(feerate))
  }

}
