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

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe => TypedProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.io.MessageRelay._
import fr.acinq.eclair.io.Switchboard.GetPeer
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient}
import fr.acinq.eclair.randomKey
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

class MessageRelaySpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val aliceId: PublicKey = Alice.nodeParams.nodeId
  val bobId: PublicKey = Bob.nodeParams.nodeId

  case class FixtureParam(relay: ActorRef[Command], switchboard: TestProbe, peerConnection: TypedProbe[Nothing], peer: TypedProbe[Peer.RelayOnionMessage], probe: TypedProbe[Status])

  override def withFixture(test: OneArgTest): Outcome = {
    val switchboard = TestProbe("switchboard")(system.classicSystem)
    val peerConnection = TypedProbe[Nothing]("peerConnection")
    val peer = TypedProbe[Peer.RelayOnionMessage]("peer")
    val probe = TypedProbe[Status]("probe")
    val relay = testKit.spawn(MessageRelay())
    try {
      withFixture(test.toNoArgTest(FixtureParam(relay, switchboard, peerConnection, peer, probe)))
    } finally {
      testKit.stop(relay)
    }
  }

  test("relay with new connection") { f =>
    import f._

    val (_, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(aliceId)), Left(Recipient(bobId, None)), Nil)
    relay ! RelayMessage(switchboard.ref, bobId, message, RelayAll, probe.ref)

    switchboard.expectMsgType[GetPeer].replyTo ! None

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId === bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.Connected(peerConnection.ref.toClassic, peer.ref.toClassic)
    assert(peer.expectMessageType[Peer.RelayOnionMessage].msg === message)
  }

  test("relay with existing peer") { f =>
    import f._

    val (_, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(aliceId)), Left(Recipient(bobId, None)), Nil)
    relay ! RelayMessage(switchboard.ref, bobId, message, RelayAll, probe.ref)

    switchboard.expectMsgType[GetPeer].replyTo ! Some(peer.ref.toClassic)

    assert(peer.expectMessageType[Peer.RelayOnionMessage].msg === message)
  }

  test("can't open new connection") { f =>
    import f._

    val (_, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(aliceId)), Left(Recipient(bobId, None)), Nil)
    relay ! RelayMessage(switchboard.ref, bobId, message, RelayAll, probe.ref)

    switchboard.expectMsgType[GetPeer].replyTo ! None

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId === bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.NoAddressFound
    probe.expectMessage(ConnectionFailure(PeerConnection.ConnectionResult.NoAddressFound))
  }

  test("policy prevents relay without existing peer") { f =>
    import f._

    val (_, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(aliceId)), Left(Recipient(bobId, None)), Nil)
    relay ! RelayMessage(switchboard.ref, bobId, message, RelayChannelsOnly, probe.ref)

    switchboard.expectMsgType[GetPeer].replyTo ! None

    probe.expectMessage(AgainstPolicy(RelayChannelsOnly))
  }
}
