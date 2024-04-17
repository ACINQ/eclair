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
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.io.MessageRelay._
import fr.acinq.eclair.io.Peer.{PeerInfo, PeerNotFound}
import fr.acinq.eclair.io.Switchboard.GetPeerInfo
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.{GenericTlv, OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{EncodedNodeId, RealShortChannelId, ShortChannelId, UInt64, randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

class MessageRelaySpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val aliceId: PublicKey = Alice.nodeParams.nodeId
  val bobId: PublicKey = Bob.nodeParams.nodeId

  case class FixtureParam(relay: ActorRef[Command], switchboard: TestProbe, register: TestProbe, router: TypedProbe[Router.GetNodeId], peerConnection: TypedProbe[Nothing], peer: TypedProbe[Peer.RelayOnionMessage], probe: TypedProbe[Status])

  override def withFixture(test: OneArgTest): Outcome = {
    val switchboard = TestProbe("switchboard")(system.classicSystem)
    val register = TestProbe("register")(system.classicSystem)
    val router = TypedProbe[Router.GetNodeId]("router")
    val peerConnection = TypedProbe[Nothing]("peerConnection")
    val peer = TypedProbe[Peer.RelayOnionMessage]("peer")
    val probe = TypedProbe[Status]("probe")
    val relay = testKit.spawn(MessageRelay(Alice.nodeParams, switchboard.ref, register.ref, router.ref))
    try {
      withFixture(test.toNoArgTest(FixtureParam(relay, switchboard, register, router, peerConnection, peer, probe)))
    } finally {
      testKit.stop(relay)
    }
  }

  test("relay with new connection") { f =>
    import f._

    val Right(message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(), Recipient(bobId, None), TlvStream.empty)
    val messageId = randomBytes32()
    relay ! RelayMessage(messageId, randomKey().publicKey, Right(EncodedNodeId(bobId)), message, RelayAll, None)

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId == bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.Connected(peerConnection.ref.toClassic, peer.ref.toClassic)
    assert(peer.expectMessageType[Peer.RelayOnionMessage].msg == message)
  }

  test("relay with existing peer") { f =>
    import f._

    val Right(message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(), Recipient(bobId, None), TlvStream.empty)
    val messageId = randomBytes32()
    relay ! RelayMessage(messageId, randomKey().publicKey, Right(EncodedNodeId(bobId)), message, RelayAll, None)

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId == bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.AlreadyConnected(peerConnection.ref.toClassic, peer.ref.toClassic)
    assert(peer.expectMessageType[Peer.RelayOnionMessage].msg == message)
  }

  test("can't open new connection") { f =>
    import f._

    val Right(message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(), Recipient(bobId, None), TlvStream.empty)
    val messageId = randomBytes32()
    relay ! RelayMessage(messageId, randomKey().publicKey, Right(EncodedNodeId(bobId)), message, RelayAll, Some(probe.ref))

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId == bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.NoAddressFound
    probe.expectMessage(ConnectionFailure(messageId, PeerConnection.ConnectionResult.NoAddressFound))
  }

  test("no channel with previous node") { f =>
    import f._

    val Right(message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(), Recipient(bobId, None), TlvStream.empty)
    val messageId = randomBytes32()
    val previousNodeId = randomKey().publicKey
    relay ! RelayMessage(messageId, previousNodeId, Right(EncodedNodeId(bobId)), message, RelayChannelsOnly, Some(probe.ref))

    val getPeerInfo = switchboard.expectMsgType[GetPeerInfo]
    assert(getPeerInfo.remoteNodeId == previousNodeId)
    getPeerInfo.replyTo ! PeerInfo(peer.ref.toClassic, previousNodeId, Peer.CONNECTED, None, Set.empty)

    probe.expectMessage(AgainstPolicy(messageId, RelayChannelsOnly))
    peer.expectNoMessage(100 millis)
  }

  test("no channel with next node") { f =>
    import f._

    val Right(message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(), Recipient(bobId, None), TlvStream.empty)
    val messageId = randomBytes32()
    val previousNodeId = randomKey().publicKey
    relay ! RelayMessage(messageId, previousNodeId, Right(EncodedNodeId(bobId)), message, RelayChannelsOnly, Some(probe.ref))

    val getPeerInfo1 = switchboard.expectMsgType[GetPeerInfo]
    assert(getPeerInfo1.remoteNodeId == previousNodeId)
    getPeerInfo1.replyTo ! PeerInfo(peer.ref.toClassic, previousNodeId, Peer.CONNECTED, None, Set(TestProbe()(system.classicSystem).ref))

    val getPeerInfo2 = switchboard.expectMsgType[GetPeerInfo]
    assert(getPeerInfo2.remoteNodeId == bobId)
    getPeerInfo2.replyTo ! PeerNotFound(bobId)

    probe.expectMessage(AgainstPolicy(messageId, RelayChannelsOnly))
    peer.expectNoMessage(100 millis)
  }

  test("channels on both ends") { f =>
    import f._

    val Right(message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(), Recipient(bobId, None), TlvStream.empty)
    val messageId = randomBytes32()
    val previousNodeId = randomKey().publicKey
    relay ! RelayMessage(messageId, previousNodeId, Right(EncodedNodeId(bobId)), message, RelayChannelsOnly, None)

    val getPeerInfo1 = switchboard.expectMsgType[GetPeerInfo]
    assert(getPeerInfo1.remoteNodeId == previousNodeId)
    getPeerInfo1.replyTo ! PeerInfo(TestProbe()(system.classicSystem).ref, previousNodeId, Peer.CONNECTED, None, Set(TestProbe()(system.classicSystem).ref))

    val getPeerInfo2 = switchboard.expectMsgType[GetPeerInfo]
    assert(getPeerInfo2.remoteNodeId == bobId)
    getPeerInfo2.replyTo ! PeerInfo(peer.ref.toClassic, bobId, Peer.CONNECTED, None, Set(0, 1).map(_ => TestProbe()(system.classicSystem).ref))

    assert(peer.expectMessageType[Peer.RelayOnionMessage].msg == message)
  }

  test("next node specified with channel id") { f =>
    import f._

    val Right(message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(), Recipient(bobId, None), TlvStream.empty)
    val messageId = randomBytes32()
    val scid = ShortChannelId(123456L)
    relay ! RelayMessage(messageId, randomKey().publicKey, Left(scid), message, RelayAll, None)

    val getNextNodeId = register.expectMsgType[Register.GetNextNodeId]
    assert(getNextNodeId.shortChannelId == scid)
    getNextNodeId.replyTo ! Some(bobId)

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId == bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.AlreadyConnected(peerConnection.ref.toClassic, peer.ref.toClassic)
    assert(peer.expectMessageType[Peer.RelayOnionMessage].msg == message)
  }

  test("next node is compact node id") { f =>
    import f._

    val Right(message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(), Recipient(bobId, None), TlvStream.empty)
    val messageId = randomBytes32()
    val scid = RealShortChannelId(234567L)
    relay ! RelayMessage(messageId, randomKey().publicKey, Right(EncodedNodeId.ShortChannelIdDir(isNode1 = false, scid)), message, RelayAll, None)

    val getNodeId = router.expectMessageType[Router.GetNodeId]
    assert(getNodeId.isNode1 == false)
    assert(getNodeId.shortChannelId == scid)
    getNodeId.replyTo ! Some(bobId)

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId == bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.AlreadyConnected(peerConnection.ref.toClassic, peer.ref.toClassic)
    assert(peer.expectMessageType[Peer.RelayOnionMessage].msg == message)
  }

  test("next node is us as compact node id") { f =>
    import f._

    val Right(message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(IntermediateNode(aliceId)), Recipient(bobId, None), TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(31), hex"f3ed"))))
    val messageId = randomBytes32()
    val scid = RealShortChannelId(345678L)
    relay ! RelayMessage(messageId, randomKey().publicKey, Right(EncodedNodeId.ShortChannelIdDir(isNode1 = true, scid)), message, RelayAll, None)

    val getNodeId = router.expectMessageType[Router.GetNodeId]
    assert(getNodeId.isNode1 == true)
    assert(getNodeId.shortChannelId == scid)
    getNodeId.replyTo ! Some(aliceId)

    val connectToNextPeer = switchboard.expectMsgType[Peer.Connect]
    assert(connectToNextPeer.nodeId == bobId)
    connectToNextPeer.replyTo ! PeerConnection.ConnectionResult.AlreadyConnected(peerConnection.ref.toClassic, peer.ref.toClassic)
    val messageToBob = peer.expectMessageType[Peer.RelayOnionMessage].msg
    val OnionMessages.ReceiveMessage(payload, _) = OnionMessages.process(Bob.nodeParams.privateKey, messageToBob)
    assert(payload.records.unknown == Set(GenericTlv(UInt64(31), hex"f3ed")))
  }

  test("relay to self and receive") { f =>
    import f._

    val probe = TypedProbe[OnionMessages.ReceiveMessage]()
    system.eventStream ! EventStream.Subscribe(probe.ref)

    val Right(message) = OnionMessages.buildMessage(randomKey(), randomKey(), Seq(
      IntermediateNode(aliceId, EncodedNodeId(aliceId), outgoingChannel_opt = Some(ShortChannelId.toSelf)),
      IntermediateNode(aliceId),
      IntermediateNode(aliceId, EncodedNodeId.ShortChannelIdDir(isNode1 = false, scid = RealShortChannelId(123L)))
    ), Recipient(aliceId, None), TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))))
    val messageId = randomBytes32()
    relay ! RelayMessage(messageId, randomKey().publicKey, Right(EncodedNodeId(aliceId)), message, RelayAll, None)

    val getNodeId = router.expectMessageType[Router.GetNodeId]
    assert(getNodeId.isNode1 == false)
    assert(getNodeId.shortChannelId == RealShortChannelId(123L))
    getNodeId.replyTo ! Some(aliceId)

    val OnionMessages.ReceiveMessage(finalPayload, _) = probe.expectMessageType[OnionMessages.ReceiveMessage]
    assert(finalPayload.records.unknown == Set(GenericTlv(UInt64(33), hex"abcd")))
  }
}
