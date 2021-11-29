/*
 * Copyright 2021 ACINQ SAS
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
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient}
import fr.acinq.eclair.randomKey
import fr.acinq.eclair.wire.protocol.OnionMessage
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

class MessageRelaySpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val aliceId: PublicKey = Alice.nodeParams.nodeId
  val bobId: PublicKey = Bob.nodeParams.nodeId

  case class FixtureParam(relay: ActorRef[MessageRelay.Command], switchboard: TestProbe[Peer.Connect], peerConnection: TestProbe[OnionMessage], probe: TestProbe[MessageRelay.Status])

  override def withFixture(test: OneArgTest): Outcome = {
    val switchboard = TestProbe[Peer.Connect]("switchboard")
    val peerConnection = TestProbe[OnionMessage]("peerConnection")
    val probe = TestProbe[MessageRelay.Status]("probe")
    val relay = testKit.spawn(MessageRelay())
    try {
      withFixture(test.toNoArgTest(FixtureParam(relay, switchboard, peerConnection, probe)))
    } finally {
      testKit.stop(relay)
    }
  }

  test("relay with new connection") { f =>
    import f._

    val (_, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(aliceId)), Left(Recipient(bobId, None)), Nil)
    relay ! MessageRelay.RelayMessage(switchboard.ref.toClassic, bobId, message, probe.ref)

    val connectToNextPeer = switchboard.expectMessageType[Peer.Connect]
    assert(connectToNextPeer.nodeId === bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.Connected(peerConnection.ref.toClassic)
    peerConnection.expectMessage(message)
    probe.expectMessage(MessageRelay.Success)
  }

  test("relay with existing connection") { f =>
    import f._

    val (_, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(aliceId)), Left(Recipient(bobId, None)), Nil)
    relay ! MessageRelay.RelayMessage(switchboard.ref.toClassic, bobId, message, probe.ref)

    val connectToNextPeer = switchboard.expectMessageType[Peer.Connect]
    assert(connectToNextPeer.nodeId === bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.AlreadyConnected(peerConnection.ref.toClassic)
    peerConnection.expectMessage(message)
    probe.expectMessage(MessageRelay.Success)
  }

  test("can't open new connection") { f =>
    import f._

    val (_, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(aliceId)), Left(Recipient(bobId, None)), Nil)
    relay ! MessageRelay.RelayMessage(switchboard.ref.toClassic, bobId, message, probe.ref)

    val connectToNextPeer = switchboard.expectMessageType[Peer.Connect]
    assert(connectToNextPeer.nodeId === bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.NoAddressFound
    probe.expectMessage(MessageRelay.Failure(PeerConnection.ConnectionResult.NoAddressFound))
  }
}
