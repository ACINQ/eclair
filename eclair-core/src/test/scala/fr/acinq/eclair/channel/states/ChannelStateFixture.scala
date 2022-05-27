package fr.acinq.eclair.channel.states

import akka.actor.ActorSystem
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestFSMRef, TestKit, TestProbe}
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.{DummyOnChainWallet, OnChainWallet}
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.states.ChannelStateTestsHelperMethods.FakeTxPublisherFactory
import fr.acinq.eclair.channel.{ChannelData, ChannelState, ChannelTypes, Helpers, LocalChannelDown, LocalChannelUpdate, LocalParams, SupportedChannelType}
import fr.acinq.eclair.{BlockHeight, FeatureSupport, Features, NodeParams, TestConstants, TestUtils, UInt64}

case class ChannelStateFixture(system: ActorSystem,
                               alice: TestFSMRef[ChannelState, ChannelData, Channel],
                               bob: TestFSMRef[ChannelState, ChannelData, Channel],
                               aliceOrigin: TestProbe,
                               alice2bob: TestProbe,
                               bob2alice: TestProbe,
                               alice2blockchain: TestProbe,
                               bob2blockchain: TestProbe,
                               router: TestProbe,
                               alice2relayer: TestProbe,
                               bob2relayer: TestProbe,
                               channelUpdateListener: TestProbe,
                               wallet: OnChainWallet,
                               alicePeer: TestProbe,
                               bobPeer: TestProbe) {
  implicit val implicitSystem: ActorSystem = system

  def currentBlockHeight: BlockHeight = alice.underlyingActor.nodeParams.currentBlockHeight

  def cleanup(): Unit = {
    TestKit.shutdownActorSystem(alice.underlyingActor.context.system)
    TestKit.shutdownActorSystem(bob.underlyingActor.context.system)
    TestKit.shutdownActorSystem(system)
  }
}

object ChannelStateFixture {

  def apply(nodeParamsA: NodeParams = TestConstants.Alice.nodeParams, nodeParamsB: NodeParams = TestConstants.Bob.nodeParams, wallet: OnChainWallet = new DummyOnChainWallet(), tags: Set[String] = Set.empty): ChannelStateFixture = {
    implicit val system: ActorSystem = ActorSystem("system")
    val aliceOrigin = TestProbe()
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
    val systemA: ActorSystem = ActorSystem("system-alice")
    val systemB: ActorSystem = ActorSystem("system-bob")
    systemA.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelUpdate])
    systemA.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelDown])
    systemB.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelUpdate])
    systemB.eventStream.subscribe(channelUpdateListener.ref, classOf[LocalChannelDown])
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
    val alice: TestFSMRef[ChannelState, ChannelData, Channel] = {
      implicit val system: ActorSystem = systemA
      TestFSMRef(new Channel(finalNodeParamsA, wallet, finalNodeParamsB.nodeId, alice2blockchain.ref, alice2relayer.ref, FakeTxPublisherFactory(alice2blockchain), origin_opt = Some(aliceOrigin.ref)), alicePeer.ref)
    }
    val bob: TestFSMRef[ChannelState, ChannelData, Channel] = {
      implicit val system: ActorSystem = systemB
      TestFSMRef(new Channel(finalNodeParamsB, wallet, finalNodeParamsA.nodeId, bob2blockchain.ref, bob2relayer.ref, FakeTxPublisherFactory(bob2blockchain)), bobPeer.ref)
    }
    ChannelStateFixture(system, alice, bob, aliceOrigin, alice2bob, bob2alice, alice2blockchain, bob2blockchain, router, alice2relayer, bob2relayer, channelUpdateListener, wallet, alicePeer, bobPeer)
  }

  def computeFeatures(setup: ChannelStateFixture, tags: Set[String]): (LocalParams, LocalParams, SupportedChannelType) = {
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

}
