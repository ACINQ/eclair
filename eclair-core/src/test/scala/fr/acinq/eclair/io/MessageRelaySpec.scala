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

import akka.actor.typed.scaladsl.adapter.{ClassicActorRefOps, ClassicActorSystemOps}
import akka.actor.{ActorContext, ActorRef}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.io.Peer.ChannelFactory
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient}
import fr.acinq.eclair.wire.protocol.OnionMessage
import fr.acinq.eclair.{TestKitBaseClass, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike

case class FakeChannelFactory(channel: TestProbe) extends ChannelFactory {
  override def spawn(context: ActorContext, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]): ActorRef = {
    channel.ref
  }
}

class MessageRelaySpec extends TestKitBaseClass with AnyFunSuiteLike {
  val aliceId: PublicKey = Alice.nodeParams.nodeId
  val bobId: PublicKey = Bob.nodeParams.nodeId

  test("relay with new connection") {
    val switchboard = TestProbe()
    val peerConnection = TestProbe()

    val (_, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(aliceId)), Left(Recipient(bobId, None)), Nil)

    val probe = TestProbe()

    val relay = system.spawnAnonymous(MessageRelay())
    relay ! MessageRelay.RelayMessage(switchboard.ref, bobId, message, probe.ref.toTyped)

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId === bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.Connected(peerConnection.ref)
    peerConnection.expectMsgType[OnionMessage]
    probe.expectMsg(MessageRelay.Success)
  }

  test("relay with existing connection") {
    val switchboard = TestProbe()
    val peerConnection = TestProbe()

    val (_, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(aliceId)), Left(Recipient(bobId, None)), Nil)

    val probe = TestProbe()

    val relay = system.spawnAnonymous(MessageRelay())
    relay ! MessageRelay.RelayMessage(switchboard.ref, bobId, message, probe.ref.toTyped)

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId === bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.AlreadyConnected(peerConnection.ref)
    peerConnection.expectMsgType[OnionMessage]
    probe.expectMsg(MessageRelay.Success)
  }

  test("can't open new connection") {
    val switchboard = TestProbe()
    val peerConnection = TestProbe()

    val (_, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(aliceId)), Left(Recipient(bobId, None)), Nil)

    val probe = TestProbe()

    val relay = system.spawnAnonymous(MessageRelay())
    relay ! MessageRelay.RelayMessage(switchboard.ref, bobId, message, probe.ref.toTyped)

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId === bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.NoAddressFound
    probe.expectMsg(MessageRelay.Failure(PeerConnection.ConnectionResult.NoAddressFound))
  }
}
