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
import fr.acinq.eclair.wire.protocol.{ChannelReestablish, ChannelTlv, Error, IPAddress, LiquidityAds, NodeAddress, OpenChannel, OpenChannelTlv, Shutdown, TlvStream}
import fr.acinq.eclair.{AcceptOpenChannel, BlockHeight, FeatureSupport, Features, InitFeature, InterceptOpenChannelCommand, InterceptOpenChannelPlugin, InterceptOpenChannelReceived, MilliSatoshiLong, RejectOpenChannel, TestConstants, UInt64, UnknownFeature, randomBytes32, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import java.net.InetAddress
import scala.concurrent.duration.DurationInt

class OpenChannelInterceptorSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val remoteNodeId: Crypto.PublicKey = randomKey().publicKey
  val openChannel: OpenChannel = createOpenChannelMessage()
  val remoteAddress: NodeAddress = IPAddress(InetAddress.getLoopbackAddress, 19735)
  val defaultFeatures: Features[InitFeature] = Features(Map[InitFeature, FeatureSupport](StaticRemoteKey -> Optional, AnchorOutputsZeroFeeHtlcTx -> Optional))
  val staticRemoteKeyFeatures: Features[InitFeature] = Features(Map[InitFeature, FeatureSupport](StaticRemoteKey -> Optional))

  val acceptStaticRemoteKeyChannelsTag = "accept static_remote_key channels"
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
      .modify(_.channelConf).usingIf(test.tags.contains(acceptStaticRemoteKeyChannelsTag))(_.copy(acceptIncomingStaticRemoteKeyChannels = true))

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

    val features = defaultFeatures.add(Features.SplicePrototype, FeatureSupport.Optional).add(Features.OnTheFlyFunding, FeatureSupport.Optional)
    val requestFunding = LiquidityAds.RequestFunding(250_000 sat, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(randomBytes32() :: Nil))
    val open = createOpenDualFundedChannelMessage().copy(
      channelFlags = ChannelFlags(nonInitiatorPaysCommitFees = true, announceChannel = false),
      tlvStream = TlvStream(ChannelTlv.RequestFundingTlv(requestFunding))
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
    val openChannelInitiator = OpenChannelInitiator(probe.ref, remoteNodeId, Peer.OpenChannel(remoteNodeId, 300_000 sat, None, None, None, None, Some(requestFunding), None, None), defaultFeatures, defaultFeatures)
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

  test("reject open channel request if channel type is obsolete") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), Features.empty, Features.empty, peerConnection.ref, remoteAddress)
    openChannelInterceptor ! openChannelNonInitiator
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("rejecting incoming channel: anchor outputs must be used for new channels"))
    eventListener.expectMessageType[ChannelAborted]
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

  test("reject static_remote_key open channel request") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), staticRemoteKeyFeatures, staticRemoteKeyFeatures, peerConnection.ref, remoteAddress)
    openChannelInterceptor ! openChannelNonInitiator
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("rejecting incoming static_remote_key channel: anchor outputs must be used for new channels"))
    eventListener.expectMessageType[ChannelAborted]
  }

  test("accept static_remote_key open channel request if node is configured to accept them", Tag(acceptStaticRemoteKeyChannelsTag)) { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), staticRemoteKeyFeatures, staticRemoteKeyFeatures, peerConnection.ref, remoteAddress)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived].replyTo ! AcceptOpenChannel(randomBytes32(), Some(LiquidityAds.AddFunding(50_000 sat, None)))
    peer.expectMessageType[SpawnChannelNonInitiator]
  }

  test("reject on-the-fly channel if another channel exists", Tag(noPlugin)) { f =>
    import f._

    val features = defaultFeatures.add(Features.SplicePrototype, FeatureSupport.Optional).add(Features.OnTheFlyFunding, FeatureSupport.Optional)
    val requestFunding = LiquidityAds.RequestFunding(250_000 sat, TestConstants.defaultLiquidityRates.fundingRates.head, LiquidityAds.PaymentDetails.FromChannelBalanceForFutureHtlc(randomBytes32() :: Nil))
    val open = createOpenDualFundedChannelMessage().copy(
      channelFlags = ChannelFlags(nonInitiatorPaysCommitFees = true, announceChannel = false),
      tlvStream = TlvStream(ChannelTlv.RequestFundingTlv(requestFunding))
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

    // They only support anchor outputs and we don't.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputs())))
      openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), Features.empty, Features.empty, peerConnection.ref, remoteAddress)
      peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "invalid channel_type=anchor_outputs, expected channel_type=standard"), peerConnection.ref.toClassic))
      eventListener.expectMessageType[ChannelAborted]
    }
    // They only support anchor outputs with zero fee htlc txs and we don't.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx())))
      openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), Features.empty, Features.empty, peerConnection.ref, remoteAddress)
      peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "invalid channel_type=anchor_outputs_zero_fee_htlc_tx, expected channel_type=standard"), peerConnection.ref.toClassic))
      eventListener.expectMessageType[ChannelAborted]
    }
    // They want to use a channel type that doesn't exist in the spec.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(UnsupportedChannelType(Features(AnchorOutputs -> Optional)))))
      openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), Features.empty, Features.empty, peerConnection.ref, remoteAddress)
      peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "invalid channel_type=0x200000, expected channel_type=standard"), peerConnection.ref.toClassic))
      eventListener.expectMessageType[ChannelAborted]
    }
    // They want to use a channel type we don't support yet.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(UnsupportedChannelType(Features(Map(StaticRemoteKey -> Mandatory), unknown = Set(UnknownFeature(22)))))))
      openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), Features.empty, Features.empty, peerConnection.ref, remoteAddress)
      peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "invalid channel_type=0x401000, expected channel_type=standard"), peerConnection.ref.toClassic))
      eventListener.expectMessageType[ChannelAborted]
    }
  }

  test("don't spawn a channel if channel type is missing with the feature bit set") { f =>
    import f._

    val open = createOpenChannelMessage()
    openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), defaultFeatures.add(ChannelType, Optional), defaultFeatures.add(ChannelType, Optional), peerConnection.ref, remoteAddress)
    peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "option_channel_type was negotiated but channel_type is missing"), peerConnection.ref.toClassic))
    eventListener.expectMessageType[ChannelAborted]
  }

}