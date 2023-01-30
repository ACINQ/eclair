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

import akka.actor
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, DeterministicWallet, Satoshi, SatoshiLong}
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{ChannelFlags, LocalParams}
import fr.acinq.eclair.io.OpenChannelInterceptor.{DefaultParams, OpenChannelNonInitiator}
import fr.acinq.eclair.io.Peer.{ChannelId, OutgoingMessage, SpawnChannelNonInitiator}
import fr.acinq.eclair.io.PendingChannelsRateLimiter.AddOrRejectChannel
import fr.acinq.eclair.wire.protocol.{Error, Init, NodeAddress, OpenChannel}
import fr.acinq.eclair.{AcceptOpenChannel, CltvExpiryDelta, Features, InterceptOpenChannelCommand, InterceptOpenChannelPlugin, InterceptOpenChannelReceived, MilliSatoshiLong, RejectOpenChannel, TestConstants, UInt64, randomBytes32}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt

class OpenChannelInterceptorSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val fundingAmount: Satoshi = 1 sat
  val publicKey: Crypto.PublicKey = PrivateKey(ByteVector32.One).publicKey
  val remoteNodeId: Crypto.PublicKey = PrivateKey(ByteVector32.One).publicKey
  val temporaryChannelId: ByteVector32 = ByteVector32.Zeroes
  val openChannel: OpenChannel = OpenChannel(ByteVector32.Zeroes, temporaryChannelId, fundingAmount, 0 msat, 1 sat, UInt64(1), 1 sat, 1 msat, FeeratePerKw(1 sat), CltvExpiryDelta(1), 1, publicKey, publicKey, publicKey, publicKey, publicKey, publicKey, ChannelFlags.Private)
  val localParams: LocalParams = LocalParams(remoteNodeId, DeterministicWallet.KeyPath(Seq(42L)), 1 sat, Long.MaxValue.msat, Some(500 sat), 1 msat, CltvExpiryDelta(144), 50, isInitiator = false, None, None, Features.empty)
  val defaultParams: DefaultParams = DefaultParams(100 sat, 100000 msat, 100 msat, CltvExpiryDelta(288), 10)
  val channels: Map[Peer.FinalChannelId, actor.ActorRef] = Map(Peer.FinalChannelId(randomBytes32()) -> system.classicSystem.deadLetters)
  val connectedData: Peer.ConnectedData = Peer.ConnectedData(NodeAddress.fromParts("1.2.3.4", 42000).get, system.classicSystem.deadLetters, Init(TestConstants.Alice.nodeParams.features.initFeatures()), Init(TestConstants.Bob.nodeParams.features.initFeatures()), channels.map { case (k: ChannelId, v) => (k, v) })
  def publicKey(fill: Byte): Crypto.PublicKey = PrivateKey(ByteVector.fill(32)(fill)).publicKey

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

    val openChannelInterceptor = testKit.spawn(OpenChannelInterceptor(peer.ref, nodeParams, wallet, pendingChannelsRateLimiter.ref, 10 millis))
    withFixture(test.toNoArgTest(FixtureParam(openChannelInterceptor, peer, pluginInterceptor, pendingChannelsRateLimiter, peerConnection)))
  }

  case class FixtureParam(openChannelInterceptor: ActorRef[OpenChannelInterceptor.Command], peer: TestProbe[Any], pluginInterceptor: TestProbe[InterceptOpenChannelCommand], pendingChannelsRateLimiter: TestProbe[PendingChannelsRateLimiter.Command], peerConnection: TestProbe[Any])

  test("reject channel open if timeout waiting for plugin to respond") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), connectedData.localFeatures, connectedData.remoteFeatures, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived]
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("plugin timeout"))
  }

  test("continue channel open if pending channels rate limiter and interceptor plugin accept it") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), connectedData.localFeatures, connectedData.remoteFeatures, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived].replyTo ! AcceptOpenChannel(temporaryChannelId, defaultParams)
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
    val openChannelInterceptor = testKit.spawn(OpenChannelInterceptor(peer.ref, TestConstants.Alice.nodeParams, wallet, pendingChannelsRateLimiter.ref, 10 millis))
    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), connectedData.localFeatures, connectedData.remoteFeatures, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectNoMessage(10 millis)
    peer.expectMessageType[SpawnChannelNonInitiator]
  }

  test("reject open channel request if rejected by the plugin") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), connectedData.localFeatures, connectedData.remoteFeatures, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived].replyTo ! RejectOpenChannel(temporaryChannelId, Error(temporaryChannelId, "rejected"))
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("rejected"))
  }

  test("reject open channel request if pending channels rate limit reached") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), connectedData.localFeatures, connectedData.remoteFeatures, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.ChannelRateLimited
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("rate limit reached"))
  }

  test("reject open channel request if concurrent request in progress") { f =>
    import f._

    val openChannelNonInitiator = OpenChannelNonInitiator(remoteNodeId, Left(openChannel), connectedData.localFeatures, connectedData.remoteFeatures, peerConnection.ref)
    openChannelInterceptor ! openChannelNonInitiator

    // waiting for rate limiter to respond to the first request, do not accept any other requests
    openChannelInterceptor ! openChannelNonInitiator.copy(open = Left(openChannel.copy(temporaryChannelId = ByteVector32.One)))
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].channelId == ByteVector32.One)

    // waiting for plugin to respond to the first request, do not accept any other requests
    pendingChannelsRateLimiter.expectMessageType[AddOrRejectChannel].replyTo ! PendingChannelsRateLimiter.AcceptOpenChannel
    openChannelInterceptor ! openChannelNonInitiator.copy(open = Left(openChannel.copy(temporaryChannelId = ByteVector32.One)))
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].channelId == ByteVector32.One)

    // original request accepted after plugin accepts it
    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived].replyTo ! AcceptOpenChannel(temporaryChannelId, defaultParams)
    peer.expectMessageType[SpawnChannelNonInitiator]
  }
}