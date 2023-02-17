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

import akka.actor.Status
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{AnchorOutputs, ChannelType, StaticRemoteKey, Wumbo}
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.channel.ChannelTypes.UnsupportedChannelType
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.states.ChannelStateTestsTags
import fr.acinq.eclair.channel.{ChannelAborted, ChannelTypes}
import fr.acinq.eclair.io.OpenChannelInterceptor.{DefaultParams, OpenChannelInitiator, OpenChannelNonInitiator}
import fr.acinq.eclair.io.Peer.{OutgoingMessage, SpawnChannelNonInitiator}
import fr.acinq.eclair.io.PeerSpec.createOpenChannelMessage
import fr.acinq.eclair.io.PendingChannelsRateLimiter.AddOrRejectChannel
import fr.acinq.eclair.payment.Bolt11Invoice.defaultFeatures.initFeatures
import fr.acinq.eclair.wire.protocol.{ChannelTlv, Error, OpenChannel, OpenChannelTlv, TlvStream}
import fr.acinq.eclair.{AcceptOpenChannel, CltvExpiryDelta, Features, InterceptOpenChannelCommand, InterceptOpenChannelPlugin, InterceptOpenChannelReceived, MilliSatoshiLong, RejectOpenChannel, TestConstants, UnknownFeature, randomBytes32, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration.DurationInt

class OpenChannelInterceptorSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val remoteNodeId: Crypto.PublicKey = randomKey().publicKey
  val defaultParams: DefaultParams = DefaultParams(100 sat, 100000 msat, 100 msat, CltvExpiryDelta(288), 10)
  val openChannel: OpenChannel = createOpenChannelMessage()

  override def withFixture(test: OneArgTest): Outcome = {
    val peer = TestProbe[Any]()
    val peerConnection = TestProbe[Any]()
    val pluginInterceptor = TestProbe[InterceptOpenChannelCommand]()
    val wallet = new DummyOnChainWallet()
    val pendingChannelsRateLimiter = TestProbe[PendingChannelsRateLimiter.Command]()
    val plugin = new InterceptOpenChannelPlugin {
      override def name: String = "OpenChannelInterceptorPlugin"
      override def openChannelInterceptor: ActorRef[InterceptOpenChannelCommand] = pluginInterceptor.ref
    }
    val pluginParams = TestConstants.Alice.nodeParams.pluginParams :+ plugin
    val nodeParams = TestConstants.Alice.nodeParams.copy(pluginParams = pluginParams)
    val eventListener = TestProbe[ChannelAborted]()
    system.eventStream ! EventStream.Subscribe(eventListener.ref)

    val openChannelInterceptor = testKit.spawn(OpenChannelInterceptor(peer.ref, nodeParams, remoteNodeId, wallet, pendingChannelsRateLimiter.ref, 10 millis))
    withFixture(test.toNoArgTest(FixtureParam(openChannelInterceptor, peer, pluginInterceptor, pendingChannelsRateLimiter, peerConnection, eventListener, wallet)))
  }

  case class FixtureParam(openChannelInterceptor: ActorRef[OpenChannelInterceptor.Command], peer: TestProbe[Any], pluginInterceptor: TestProbe[InterceptOpenChannelCommand], pendingChannelsRateLimiter: TestProbe[PendingChannelsRateLimiter.Command], peerConnection: TestProbe[Any], eventListener: TestProbe[ChannelAborted], wallet: DummyOnChainWallet)

  test("reject channel open if timeout waiting for plugin to respond") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), Features.empty, Features.empty, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived]
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("plugin timeout"))
    eventListener.expectMessageType[ChannelAborted]
  }

  test("continue channel open if pending channels rate limiter and interceptor plugin accept it") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), Features.empty, Features.empty, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived].replyTo ! AcceptOpenChannel(randomBytes32(), defaultParams)
    val updatedLocalParams = peer.expectMessageType[SpawnChannelNonInitiator].localParams
    assert(updatedLocalParams.dustLimit == defaultParams.dustLimit)
    assert(updatedLocalParams.htlcMinimum == defaultParams.htlcMinimum)
    assert(updatedLocalParams.maxAcceptedHtlcs == defaultParams.maxAcceptedHtlcs)
    assert(updatedLocalParams.maxHtlcValueInFlightMsat == defaultParams.maxHtlcValueInFlightMsat)
    assert(updatedLocalParams.toSelfDelay == defaultParams.toSelfDelay)
  }

  test("continue channel open if no interceptor plugin registered and pending channels rate limiter accepts it") { f =>
    import f._

    // no open channel interceptor plugin registered
    val wallet = new DummyOnChainWallet()
    val openChannelInterceptor = testKit.spawn(OpenChannelInterceptor(peer.ref, TestConstants.Alice.nodeParams, remoteNodeId, wallet, pendingChannelsRateLimiter.ref, 10 millis))
    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), Features.empty, Features.empty, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectNoMessage(10 millis)
    peer.expectMessageType[SpawnChannelNonInitiator]
  }

  test("reject open channel request if rejected by the plugin") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), Features.empty, Features.empty, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived].replyTo ! RejectOpenChannel(randomBytes32(), Error(randomBytes32(), "rejected"))
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("rejected"))
    eventListener.expectMessageType[ChannelAborted]
  }

  test("reject open channel request if pending channels rate limit reached") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), Features.empty, Features.empty, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.ChannelRateLimited
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("rate limit reached"))
    eventListener.expectMessageType[ChannelAborted]
  }

  test("reject open channel request if concurrent request in progress") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), Features.empty, Features.empty, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator

    // waiting for rate limiter to respond to the first request, do not accept any other requests
    openChannelInterceptor ! openChannelNonInitiator.copy(open = Left(openChannel.copy(temporaryChannelId = ByteVector32.One)))
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].channelId == ByteVector32.One)

    // waiting for plugin to respond to the first request, do not accept any other requests
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    openChannelInterceptor ! openChannelNonInitiator.copy(open = Left(openChannel.copy(temporaryChannelId = ByteVector32.One)))
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].channelId == ByteVector32.One)

    // original request accepted after plugin accepts it
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived].replyTo ! AcceptOpenChannel(randomBytes32(), defaultParams)
    assert(peer.expectMessageType[SpawnChannelNonInitiator].open == Left(openChannel))
    eventListener.expectMessageType[ChannelAborted]
  }

  test("don't spawn a wumbo channel if wumbo feature isn't enabled") { f =>
    import f._

    val probe = TestProbe[Any]()
    val fundingAmountBig = Channel.MAX_FUNDING + 10000.sat

    openChannelInterceptor ! OpenChannelInitiator(probe.ref, remoteNodeId, Peer.OpenChannel(remoteNodeId, fundingAmountBig, None, None, None, None, None), Features.empty, Features.empty)

    assert(probe.expectMessageType[Status.Failure].cause.getMessage == s"fundingAmount=$fundingAmountBig is too big, you must enable large channels support in 'eclair.features' to use funding above ${Channel.MAX_FUNDING} (see eclair.conf)")
  }

  test("don't spawn a wumbo channel if remote doesn't support wumbo", Tag(ChannelStateTestsTags.Wumbo)) { f =>
    import f._

    val probe = TestProbe[Any]()
    val fundingAmountBig = Channel.MAX_FUNDING + 10000.sat

    openChannelInterceptor ! OpenChannelInitiator(probe.ref, remoteNodeId, Peer.OpenChannel(remoteNodeId, fundingAmountBig, None, None, None, None, None), initFeatures().add(Wumbo, Optional), Features.empty)

    assert(probe.expectMessageType[Status.Failure].cause.getMessage == s"fundingAmount=$fundingAmountBig is too big, the remote peer doesn't support wumbo")
  }

  test("don't spawn a channel if fundingSatoshis is greater than maxFundingSatoshis", Tag(ChannelStateTestsTags.Wumbo)) { f =>
    import f._

    val probe = TestProbe[Any]()
    val fundingAmountBig = Channel.MAX_FUNDING + 10000.sat

    openChannelInterceptor ! OpenChannelInitiator(probe.ref, remoteNodeId, Peer.OpenChannel(remoteNodeId, fundingAmountBig, None, None, None, None, None), initFeatures().add(Wumbo, Optional), initFeatures().add(Wumbo, Optional))

    assert(probe.expectMessageType[Status.Failure].cause.getMessage == s"fundingAmount=$fundingAmountBig is too big for the current settings, increase 'eclair.max-funding-satoshis' (see eclair.conf)")
  }

  test("don't spawn a channel if we don't support their channel type") { f =>
    import f._

    // They only support anchor outputs and we don't.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputs())))
      openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), Features.empty, Features.empty, peerConnection.ref)
      peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "invalid channel_type=anchor_outputs, expected channel_type=standard"), peerConnection.ref.toClassic))
      eventListener.expectMessageType[ChannelAborted]
    }
    // They only support anchor outputs with zero fee htlc txs and we don't.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(ChannelTypes.AnchorOutputsZeroFeeHtlcTx())))
      openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), Features.empty, Features.empty, peerConnection.ref)
      peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "invalid channel_type=anchor_outputs_zero_fee_htlc_tx, expected channel_type=standard"), peerConnection.ref.toClassic))
      eventListener.expectMessageType[ChannelAborted]
    }
    // They want to use a channel type that doesn't exist in the spec.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(UnsupportedChannelType(Features(AnchorOutputs -> Optional)))))
      openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), Features.empty, Features.empty, peerConnection.ref)
      peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "invalid channel_type=0x200000, expected channel_type=standard"), peerConnection.ref.toClassic))
      eventListener.expectMessageType[ChannelAborted]
    }
    // They want to use a channel type we don't support yet.
    {
      val open = createOpenChannelMessage(TlvStream[OpenChannelTlv](ChannelTlv.ChannelTypeTlv(UnsupportedChannelType(Features(Map(StaticRemoteKey -> Mandatory), unknown = Set(UnknownFeature(22)))))))
      openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), Features.empty, Features.empty, peerConnection.ref)
      peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "invalid channel_type=0x401000, expected channel_type=standard"), peerConnection.ref.toClassic))
      eventListener.expectMessageType[ChannelAborted]
    }
  }

  test("don't spawn a channel if channel type is missing with the feature bit set", Tag(ChannelStateTestsTags.ChannelType)) { f =>
    import f._

    val open = createOpenChannelMessage()
    openChannelInterceptor ! OpenChannelNonInitiator(remoteNodeId, Left(open), initFeatures().add(ChannelType, Optional), initFeatures().add(ChannelType, Optional), peerConnection.ref)
    peer.expectMessage(OutgoingMessage(Error(open.temporaryChannelId, "option_channel_type was negotiated but channel_type is missing"), peerConnection.ref.toClassic))
    eventListener.expectMessageType[ChannelAborted]
  }

}