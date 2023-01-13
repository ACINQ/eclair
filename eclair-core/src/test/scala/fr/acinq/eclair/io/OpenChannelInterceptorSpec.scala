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
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel.{ChannelConfig, ChannelFlags, ChannelTypes, LocalParams}
import fr.acinq.eclair.io.OpenChannelInterceptor.WrappedOpenChannelResponse
import fr.acinq.eclair.io.Peer.{ChannelId, OutgoingMessage, SpawnChannelNonInitiator}
import fr.acinq.eclair.wire.protocol.{Error, NodeAddress, OpenChannel}
import fr.acinq.eclair.{AcceptOpenChannel, CltvExpiryDelta, Features, InterceptOpenChannelPlugin, InterceptOpenChannelReceived, MilliSatoshiLong, RejectOpenChannel, UInt64, randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.duration.DurationInt

class OpenChannelInterceptorSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val publicKey: Crypto.PublicKey = PrivateKey(ByteVector32.One).publicKey
  val fundingAmount: Satoshi = 1 sat
  val temporaryChannelId: ByteVector32 = ByteVector32.Zeroes
  val openChannel: OpenChannel = OpenChannel(ByteVector32.Zeroes, temporaryChannelId, fundingAmount, 0 msat, 1 sat, UInt64(1), 1 sat, 1 msat, FeeratePerKw(1 sat), CltvExpiryDelta(1), 1, publicKey, publicKey, publicKey, publicKey, publicKey, publicKey, ChannelFlags.Private)
  val localParams: LocalParams = LocalParams(randomKey().publicKey, DeterministicWallet.KeyPath(Seq(42L)), 1 sat, Long.MaxValue.msat, Some(500 sat), 1 msat, CltvExpiryDelta(144), 50, isInitiator = false, ByteVector.empty, None, Features.empty)
  val fakeIPAddress: NodeAddress = NodeAddress.fromParts("1.2.3.4", 42000).get
  val channels: Map[Peer.FinalChannelId, actor.ActorRef] = Map(Peer.FinalChannelId(randomBytes32()) -> system.classicSystem.deadLetters)
  val connectedData: Peer.ConnectedData = Peer.ConnectedData(fakeIPAddress, system.classicSystem.deadLetters, null, null, channels.map { case (k: ChannelId, v) => (k, v) })

  case class FixtureParam(openChannelInterceptor: ActorRef[OpenChannelInterceptor.Command], peer: TestProbe[Any], pluginInterceptor: TestProbe[InterceptOpenChannelReceived]) {
  }

  override def withFixture(test: OneArgTest): Outcome = {
    val peer = TestProbe[Any]()
    val peerConnection = TestProbe[Any]()
    val pluginInterceptor = TestProbe[InterceptOpenChannelReceived]()
    val plugin = new InterceptOpenChannelPlugin {
      override def name: String = "OpenChannelInterceptorPlugin"
      override def openChannelInterceptor: ActorRef[InterceptOpenChannelReceived] = pluginInterceptor.ref
    }

    val openChannelInterceptor = testKit.spawn(OpenChannelInterceptor(peer.ref, plugin, 10 millis, peerConnection.ref, temporaryChannelId, localParams, Left(openChannel), ChannelTypes.Standard(), ChannelConfig.standard))
    withFixture(test.toNoArgTest(FixtureParam(openChannelInterceptor, peer, pluginInterceptor)))
  }

  test("peer does not receive response from plugin (timeout)") { f =>
    import f._

    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived]
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("plugin timeout"))
  }

  test("peer receives accept from plugin") { f =>
    import f._

    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived]
    openChannelInterceptor ! WrappedOpenChannelResponse(AcceptOpenChannel(temporaryChannelId, localParams))
    assert(peer.expectMessageType[SpawnChannelNonInitiator].localParams == localParams)
  }

  test("peer receives reject from plugin") { f =>
    import f._

    pluginInterceptor.expectMessageType[InterceptOpenChannelReceived]
    openChannelInterceptor ! WrappedOpenChannelResponse(RejectOpenChannel(temporaryChannelId, Error(temporaryChannelId, "rejected")))
    assert(peer.expectMessageType[OutgoingMessage].msg.asInstanceOf[Error].toAscii.contains("rejected"))
  }
}