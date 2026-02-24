/*
 * Copyright 2023 ACINQ SAS
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

package fr.acinq.eclair.io

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.softwaremill.quicklens.ModifyPimp
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, OutPoint, SatoshiLong, Transaction, TxId, TxOut}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.channel.ChannelTypes.UnsupportedChannelType
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.states.ChannelStateTestsTags
import fr.acinq.eclair.io.OpenChannelInterceptor.{OpenChannelInitiator, OpenChannelNonInitiator}
import fr.acinq.eclair.io.Peer.{OpenChannelResponse, OutgoingMessage, SpawnChannelInitiator, SpawnChannelNonInitiator}
import fr.acinq.eclair.io.PeerSpec.{createOpenChannelMessage, createOpenDualFundedChannelMessage}
import fr.acinq.eclair.io.PendingChannelsRateLimiter.AddOrRejectChannel
import fr.acinq.eclair.transactions.Transactions.{ClosingTx, InputInfo}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol.{ChannelReestablish, Error, IPAddress, LiquidityAds, NodeAddress, OpenChannel, Shutdown, TlvStream}
import fr.acinq.eclair.{AcceptOpenChannel, BlockHeight, FeatureSupport, Features, InitFeature, InterceptOpenChannelCommand, InterceptOpenChannelPlugin, InterceptOpenChannelReceived, MilliSatoshiLong, RejectOpenChannel, TestConstants, UnknownFeature, randomBytes32, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import java.net.InetAddress
import scala.concurrent.duration.DurationInt

class OpenChannelInterceptorSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val remoteNodeId: Crypto.PublicKey = randomKey().publicKey
  val openChannel: OpenChannel = createOpenChannelMessage(ChannelTypes.AnchorOutputsZeroFeeHtlcTx())
  val remoteAddress: NodeAddress = IPAddress(InetAddress.getLoopbackAddress, 19735)
  val defaultFeatures: Features[InitFeature] = Features(Map[InitFeature, FeatureSupport](StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, ChannelType -> Optional))

  val noPlugin = "no plugin"

  override def withFixture(test: OneArgTest): Outcome = {
    val peer = TestProbe[Any]()
    val peerConnection = TestProbe[Any]()
    val pluginInterceptor = TestProbe[InterceptOpenChannelCommand]()
    val wallet = new DummyOnChainWallet()
    val pendingChannelsRateLimiter = TestProbe[PendingChannelsRateLimiter.Command]()
    val plugin = new InterceptOpenChannelPlugin {
      // @formatter:off
      override def name: String = "OpenChannelInterceptorPlugin"
      override def openChannelInterceptor: ActorRef[InterceptOpenChannelCommand] = pluginInterceptor.ref
      // @formatter:on
    }
    val nodeParams = TestConstants.Alice.nodeParams
      .modify(_.pluginParams).usingIf(!test.tags.contains(noPlugin))(_ :+ plugin)

    val eventListener = TestProbe[ChannelAborted]()
    system.eventStream ! EventStream.Subscribe(eventListener.ref)

    val openChannelInterceptor = testKit.spawn(OpenChannelInterceptor(peer.ref, nodeParams, remoteNodeId, wallet, pendingChannelsRateLimiter.ref, 10 millis))
    withFixture(test.toNoArgTest(FixtureParam(openChannelInterceptor, peer, pluginInterceptor, pendingChannelsRateLimiter, peerConnection, eventListener, wallet)))
  }

  case class FixtureParam(openChannelInterceptor: ActorRef[OpenChannelInterceptor.Command], peer: TestProbe[Any], pluginInterceptor: TestProbe[InterceptOpenChannelCommand], pendingChannelsRateLimiter: TestProbe[PendingChannelsRateLimiter.Command], peerConnection: TestProbe[Any], eventListener: TestProbe[ChannelAborted], wallet: DummyOnChainWallet)

  private def commitments(isOpener: Boolean = false): Commitments = {
    CommitmentsSpec.makeCommitments(500_000 msat, 400_000 msat, TestConstants.Alice.nodeParams.nodeId, remoteNodeId, announcement_opt = None)
      .modify(_.channelParams.localParams.isChannelOpener).setTo(isOpener)
      .modify(_.channelParams.localParams.paysCommitTxFees).setTo(isOpener)
  }

  test("reject channel open if timeout waiting for plugin to respond") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), defaultFeatures, defaultFeatures, peerConnection.ref, remoteAddress)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived]
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("plugin timeout"))
    eventListener.expectMessageType[ChannelAborted]
  }

  test("continue channel open if pending channels rate limiter and interceptor plugin accept it") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), defaultFeatures, defaultFeatures, peerConnection.ref, remoteAddress)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived].replyTo ! AcceptOpenChannel(randomBytes32(), addFunding_opt = None)
    assert(peer.expectMessageType[SpawnChannelNonInitiator].addFunding_opt.isEmpty)
  }

  test("add liquidity if interceptor plugin requests it") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), defaultFeatures, defaultFeatures, peerConnection.ref, remoteAddress)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    val addFunding = LiquidityAds.AddFunding(100_000 sat, None)
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived].replyTo ! AcceptOpenChannel(randomBytes32(), Some(addFunding))
    assert(peer.expectMessageType[SpawnChannelNonInitiator].addFunding_opt.contains(addFunding))
  }

  test("add liquidity if on-the-fly funding is used", Tag(noPlugin)) { f =>
    import f._

    val features = defaultFeatures.add(Features.Splicing, FeatureSupport.Optional).add(Features.OnTheFlyFunding, FeatureSupport.Optional)
    val requestFunding = LiquidityAds.RequestFunding(250_000 sat, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(randomBytes32() :: Nil))
    val open = createOpenDualFundedChannelMessage(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), Some(requestFunding)).copy(
      channelFlags = ChannelFlags(nonInitiatorPaysCommitFees = true, announceChannel = false),
    )
    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Right(open), features, features, peerConnection.ref, remoteAddress)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    // We check that all existing channels (if any) are closing before accepting the request.
    val currentChannels = Seq(
      Peer.ChannelInfo(TestProbe().ref, SHUTDOWN, DATA_SHUTDOWN(commitments(isOpener = true), Shutdown(randomBytes32(), ByteVector.empty), Shutdown(randomBytes32(), ByteVector.empty), CloseStatus.Initiator(None))),
      Peer.ChannelInfo(TestProbe().ref, NEGOTIATING, DATA_NEGOTIATING(commitments(), Shutdown(randomBytes32(), ByteVector.empty), Shutdown(randomBytes32(), ByteVector.empty), List(Nil), None)),
      Peer.ChannelInfo(TestProbe().ref, CLOSING, DATA_CLOSING(commitments(), BlockHeight(0), ByteVector.empty, Nil, ClosingTx(InputInfo(OutPoint(TxId(randomBytes32()), 5), TxOut(100_000 sat, Nil)), Transaction(2, Nil, Nil, 0), None) :: Nil)),
      Peer.ChannelInfo(TestProbe().ref, WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT, DATA_WAIT_FOR_REMOTE_PUBLISH_FUTURE_COMMITMENT(commitments(), ChannelReestablish(randomBytes32(), 0, 0, randomKey(), randomKey().publicKey))),
    )
    peer.expectMessageType[Peer.GetPeerChannels].replyTo ! Peer.PeerChannels(remoteNodeId, currentChannels)
    val result = peer.expectMessageType[SpawnChannelNonInitiator]
    assert(!result.localParams.isChannelOpener)
    assert(result.localParams.paysCommitTxFees)
    assert(result.addFunding_opt.map(_.fundingAmount).contains(250_000 sat))
    assert(result.addFunding_opt.flatMap(_.rates_opt).contains(TestConstants.defaultLiquidityRates))
  }

  test("expect remote funding contribution in max_htlc_value_in_flight") { f =>
    import f._

    val probe = TestProbe[Any]()
    val requestFunding = LiquidityAds.RequestFunding(150_000 sat, LiquidityAds.FundingRate(0 sat, 200_000 sat, 400, 100, 0 sat, 0 sat), LiquidityAds.PaymentDetails.FromChannelBalance)
    val openChannelInitiator = OpenChannelInitiator(probe.ref, remoteNodeId, Peer.OpenChannel(remoteNodeId, 300_000 sat, Some(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()), None, None, None, Some(requestFunding), None, None), defaultFeatures, defaultFeatures)
    openChannelInterceptor ! openChannelInitiator
    val result = peer.expectMessageType[SpawnChannelInitiator]
    assert(result.cmd == openChannelInitiator.open)
  }

  test("continue channel open if no interceptor plugin registered and pending channels rate limiter accepts it") { f =>
    import f._

    // no open channel interceptor plugin registered
    val wallet = new DummyOnChainWallet()
    val openChannelInterceptor = testKit.spawn(OpenChannelInterceptor(peer.ref, TestConstants.Alice.nodeParams, remoteNodeId, wallet, pendingChannelsRateLimiter.ref, 10 millis))
    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), defaultFeatures, defaultFeatures, peerConnection.ref, remoteAddress)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectNoMessage(10 millis)
    assert(peer.expectMessageType[SpawnChannelNonInitiator].addFunding_opt.isEmpty)
  }

  test("reject open channel request if rejected by the plugin") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), defaultFeatures, defaultFeatures, peerConnection.ref, remoteAddress)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived].replyTo ! RejectOpenChannel(randomBytes32(), Error(randomBytes32(), "rejected"))
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("rejected"))
    eventListener.expectMessageType[ChannelAborted]
  }

  test("reject open channel request if pending channels rate limit reached") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), defaultFeatures, defaultFeatures, peerConnection.ref, remoteAddress)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.ChannelRateLimited
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("rate limit reached"))
    eventListener.expectMessageType[ChannelAborted]
  }

  test("reject open channel request if concurrent request in progress") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), defaultFeatures, defaultFeatures, peerConnection.ref, remoteAddress)
    openChannelInterceptor ! openChannelNonInitiator

    // waiting for rate limiter to respond to the first request, do not accept any other requests
    openChannelInterceptor ! openChannelNonInitiator.copy(open = Left(openChannel.copy(temporaryChannelId = ByteVector32.One)))
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].channelId == ByteVector32.One)

    // waiting for plugin to respond to the first request, do not accept any other requests
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    openChannelInterceptor ! openChannelNonInitiator.copy(open = Left(openChannel.copy(temporaryChannelId = ByteVector32.One)))
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].channelId == ByteVector32.One)

    // original request accepted after plugin accepts it
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived].replyTo ! AcceptOpenChannel(randomBytes32(), None)
    assert(peer.expectMessageType[SpawnChannelNonInitiator].open == Left(openChannel))
    eventListener.expectMessageType[ChannelAborted]
  }

  test("reject on-the-fly channel if another channel exists", Tag(noPlugin)) { f =>
    import f._

    val features = defaultFeatures.add(Features.Splicing, FeatureSupport.Optional).add(Features.OnTheFlyFunding, FeatureSupport.Optional)
    val requestFunding = LiquidityAds.RequestFunding(250_000 sat, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(randomBytes32() :: Nil))
    val open = createOpenDualFundedChannelMessage(ChannelTypes.AnchorOutputsZeroFeeHtlcTx(), Some(requestFunding)).copy(
      channelFlags = ChannelFlags(nonInitiatorPaysCommitFees = true, announceChannel = false),
    )
    val currentChannel = Seq(
      Peer.ChannelInfo(TestProbe().ref, NORMAL, ChannelCodecsSpec.normal),
      Peer.ChannelInfo(TestProbe().ref, OFFLINE, ChannelCodecsSpec.normal),
      Peer.ChannelInfo(TestProbe().ref, SYNCING, ChannelCodecsSpec.normal),
    )
    currentChannel.foreach(channel => {
      val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Right(open), features, features, peerConnection.ref, remoteAddress)
      openChannelInterceptor ! openChannelNonInitiator
      pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
      peer.expectMessageType[Peer.GetPeerChannels].replyTo ! Peer.PeerChannels(remoteNodeId, Seq(channel))
      assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].channelId == open.temporaryChannelId)
    })
  }

  test("don't spawn a wumbo channel if wumbo feature isn't enabled", Tag(ChannelStateTestsTags.DisableWumbo)) { f =>
    import f._

    val probe = TestProbe[Any]()
    val fundingAmountBig = Channel.MAX_FUNDING_WITHOUT_WUMBO + 10_000.sat
    openChannelInterceptor ! OpenChannelInitiator(probe.ref, remoteNodeId, Peer.OpenChannel(remoteNodeId, fundingAmountBig, None, None, None, None, None, None, None), defaultFeatures, defaultFeatures.add(Wumbo, Optional))
    assert(probe.expectMessageType[OpenChannelResponse.Rejected].reason.contains("you must enable large channels support"))
  }

  test("don't spawn a wumbo channel if remote doesn't support wumbo", Tag(ChannelStateTestsTags.DisableWumbo)) { f =>
    import f._

    val probe = TestProbe[Any]()
    val fundingAmountBig = Channel.MAX_FUNDING_WITHOUT_WUMBO + 10_000.sat
    openChannelInterceptor ! OpenChannelInitiator(probe.ref, remoteNodeId, Peer.OpenChannel(remoteNodeId, fundingAmountBig, None, None, None, None, None, None, None), defaultFeatures.add(Wumbo, Optional), defaultFeatures)
    assert(probe.expectMessageType[OpenChannelResponse.Rejected].reason == s"fundingAmount=$fundingAmountBig is too big, the remote peer doesn't support wumbo")
  }

  test("don't spawn a channel if we don't support their channel type") { f =>
    import f._

    // We don't support non-anchor static_remotekey channels.
    {
      val open = createOpenChannelMessage(ChannelTypes.UnsupportedChannelType(Features(StaticRemoteKey -> Mandatory)))
      openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), defaultFeatures, defaultFeatures, peerConnection.ref, remoteAddress)
      peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "invalid channel_type=0x1000"), peerConnection.ref.toClassic))
      eventListener.expectMessageType[ChannelAborted]
    }
    // They only support unsafe anchor outputs and we don't.
    {
      val open = createOpenChannelMessage(ChannelTypes.AnchorOutputs())
      openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), defaultFeatures, defaultFeatures.add(AnchorOutputs, Optional), peerConnection.ref, remoteAddress)
      peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "invalid channel_type=anchor_outputs"), peerConnection.ref.toClassic))
      eventListener.expectMessageType[ChannelAborted]
    }
    // They want to use a channel type we don't support yet.
    {
      val open = createOpenChannelMessage(UnsupportedChannelType(Features(activated = Map.empty, unknown = Set(UnknownFeature(120)))))
      openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), defaultFeatures, defaultFeatures, peerConnection.ref, remoteAddress)
      peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "invalid channel_type=0x01000000000000000000000000000000"), peerConnection.ref.toClassic))
      eventListener.expectMessageType[ChannelAborted]
    }
  }

  test("don't spawn a channel if channel type is missing") { f =>
    import f._

    val open = createOpenChannelMessage(ChannelTypes.AnchorOutputsZeroFeeHtlcTx()).copy(tlvStream = TlvStream.empty)
    openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), defaultFeatures, defaultFeatures, peerConnection.ref, remoteAddress)
    peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "option_channel_type was negotiated but channel_type is missing"), peerConnection.ref.toClassic))
    eventListener.expectMessageType[ChannelAborted]
  }

  test("don't spawn a channel if we cannot find a satisfying channel type") { f =>
    import f._

    val probe = TestProbe[Any]()

    // If we both support anchor outputs, it is selected by default.
    {
      val features = Features[InitFeature](StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, ChannelType -> Optional, ScidAlias -> Optional)
      val open = Peer.OpenChannel(remoteNodeId, 500_000 sat, None, None, None, None, None, None, None)
      openChannelInterceptor ! OpenChannelInitiator(probe.ref, remoteNodeId, open, features, features)
      assert(peer.expectMessageType[Peer.SpawnChannelInitiator].channelType == ChannelTypes.AnchorOutputsZeroFeeHtlcTx(scidAlias = true))
    }
    // If our peer doesn't support anchor outputs, we can't find a satisfying channel type.
    {
      val localFeatures = Features[InitFeature](StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional, ChannelType -> Optional, ScidAlias -> Optional)
      val remoteFeatures = Features[InitFeature](StaticRemoteKey -> Optional, ChannelType -> Optional, ScidAlias -> Optional)
      val open = Peer.OpenChannel(remoteNodeId, 500_000 sat, None, None, None, None, None, None, None)
      openChannelInterceptor ! OpenChannelInitiator(probe.ref, remoteNodeId, open, localFeatures, remoteFeatures)
      assert(probe.expectMessageType[OpenChannelResponse.Rejected].reason == "channel_type must be provided and compatible with our peer's features")
    }
  }

}