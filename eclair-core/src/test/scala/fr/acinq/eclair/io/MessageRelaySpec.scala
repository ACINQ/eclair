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

import akka.actor.{ActorContext, ActorRef}
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain.DummyOnChainWallet
import fr.acinq.eclair.io.Peer.ChannelFactory
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient}
import fr.acinq.eclair.wire.protocol
import fr.acinq.eclair.wire.protocol.{NodeAddress, OnionMessage}
import fr.acinq.eclair.{Features, NodeParams, TestKitBaseClass, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.{AnyFunSuiteLike, FixtureAnyFunSuiteLike}

case class FakeChannelFactory(channel: TestProbe) extends ChannelFactory {
  override def spawn(context: ActorContext, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]): ActorRef = {
    channel.ref
  }
}

class MessageRelaySpec extends TestKitBaseClass with FixtureAnyFunSuiteLike {

  val fakeIPAddress: NodeAddress = NodeAddress.fromParts("1.2.3.4", 42000).get

  case class FixtureParam(nodeParams: NodeParams, remoteNodeId: PublicKey, peer: TestFSMRef[Peer.State, Peer.Data, Peer], switchboard:TestProbe, peerConnection: TestProbe)

  override protected def withFixture(test: OneArgTest): Outcome = {
    val wallet = new DummyOnChainWallet()
    import com.softwaremill.quicklens._
    val aliceParams = Alice.nodeParams.modify(_.features).setTo(Features(Features.OnionMessages -> Optional))
    val remoteNodeId = Bob.nodeParams.nodeId
    val switchboard = TestProbe()
    val channel = TestProbe()
    val peerConnection = TestProbe()
    val peer: TestFSMRef[Peer.State, Peer.Data, Peer] = TestFSMRef(new Peer(aliceParams, remoteNodeId, wallet, FakeChannelFactory(channel)), switchboard.ref)
    switchboard.send(peer, Peer.Init(Set.empty))
    val localInit = protocol.Init(peer.underlyingActor.nodeParams.features)
    val remoteInit = protocol.Init(Bob.nodeParams.features)
    switchboard.send(peer, PeerConnection.ConnectionReady(peerConnection.ref, remoteNodeId, fakeIPAddress.socketAddress, outgoing = true, localInit, remoteInit))

    val probe = TestProbe()
    probe.send(peer, Peer.GetPeerInfo)
    assert(probe.expectMsgType[Peer.PeerInfo].state == "CONNECTED")

    withFixture(test.toNoArgTest(FixtureParam(aliceParams, remoteNodeId, peer, switchboard, peerConnection)))
  }

  test("relay") { f =>
    import f._

    val (_, message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(nodeParams.nodeId)), Left(Recipient(remoteNodeId, None)), Nil)
    peerConnection.send(peer, message)

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId === remoteNodeId)
    val relay = switchboard.lastSender
    peerConnection.send(relay, PeerConnection.ConnectionResult.Connected)
    peerConnection.expectMsgType[OnionMessage]
  }
}
