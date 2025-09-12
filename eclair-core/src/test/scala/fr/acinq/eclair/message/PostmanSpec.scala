/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.message

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.softwaremill.quicklens.ModifyPimp
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Block
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.{BlindedHop, BlindedRoute}
import fr.acinq.eclair.io.MessageRelay.{Disconnected, Sent}
import fr.acinq.eclair.io.PeerConnection.ConnectionResult
import fr.acinq.eclair.io.{Peer, PeerConnection}
import fr.acinq.eclair.message.OnionMessages.RoutingStrategy.FindRoute
import fr.acinq.eclair.message.OnionMessages.{BlindedPath, IntermediateNode, ReceiveMessage, Recipient, buildMessage, buildRoute}
import fr.acinq.eclair.message.Postman._
import fr.acinq.eclair.payment.offer.OfferManager.RequestInvoice
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.{MessageRoute, MessageRouteRequest}
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.{InvoiceRequest, ReplyPath}
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv.PathId
import fr.acinq.eclair.wire.protocol.{GenericTlv, MessageOnion, OfferTypes, OnionMessage, OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{EncodedNodeId, Features, MilliSatoshiLong, NodeParams, RealShortChannelId, ShortChannelId, TestConstants, UInt64, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.HexStringSyntax

import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt

class PostmanSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  private val ShortTimeout = "short timeout"
  case class FixtureParam(postman: ActorRef[Command], nodeParams: NodeParams, messageSender: TestProbe[OnionMessageResponse], switchboard: TestProbe[Any], offerManager: TestProbe[RequestInvoice], router: TestProbe[Router.PostmanRequest])

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = if (test.tags.contains(ShortTimeout)) TestConstants.Alice.nodeParams.modify(_.onionMessageConfig.timeout).setTo(1 millis) else TestConstants.Alice.nodeParams
    val messageSender = TestProbe[OnionMessageResponse]("messageSender")
    val switchboard = TestProbe[Any]("switchboard")
    val offerManager = TestProbe[RequestInvoice]("offerManager")
    val router = TestProbe[Router.PostmanRequest]("router")
    val register = TestProbe[Any]("register")
    val postman = testKit.spawn(Postman(nodeParams, switchboard.ref.toClassic, router.ref, register.ref.toClassic, offerManager.ref))
    try {
      withFixture(test.toNoArgTest(FixtureParam(postman, nodeParams, messageSender, switchboard, offerManager, router)))
    } finally {
      testKit.stop(postman)
    }
  }

  private def expectRelayToConnected(switchboard: TestProbe[Any], recipientKey: PublicKey): Peer.RelayOnionMessage = {
    val Peer.Connect(nextNodeId, _, replyTo, _) = switchboard.expectMessageType[Peer.Connect]
    assert(nextNodeId == recipientKey)
    val peerConnection = TestProbe[Any]()
    val peer = TestProbe[Any]()
    replyTo ! PeerConnection.ConnectionResult.AlreadyConnected(peerConnection.ref.toClassic, peer.ref.toClassic)
    peer.expectMessageType[Peer.RelayOnionMessage]
  }

  @tailrec
  private def receive(privateKeys: Seq[PrivateKey], message: OnionMessage): ReceiveMessage = {
    OnionMessages.process(privateKeys.head, message) match {
      case OnionMessages.SendMessage(nextNode, nextMessage) if nextNode == Left(ShortChannelId.toSelf) || nextNode == Right(EncodedNodeId(privateKeys.head.publicKey)) =>
        receive(privateKeys, nextMessage)
      case OnionMessages.SendMessage(nextNode, nextMessage) if nextNode == Right(EncodedNodeId(privateKeys(1).publicKey)) =>
        receive(privateKeys.tail, nextMessage)
      case r: ReceiveMessage => r
      case _ => fail()
    }
  }

  test("message forwarded only once") { f =>
    import f._

    val recipientKey = randomKey()

    postman ! SendMessage(OfferTypes.RecipientNodeId(recipientKey.publicKey), FindRoute, TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), expectsReply = true, messageSender.ref)

    val MessageRouteRequest(waitingForRoute, source, target, _) = router.expectMessageType[MessageRouteRequest]
    assert(source == nodeParams.nodeId)
    assert(target == recipientKey.publicKey)
    waitingForRoute ! MessageRoute(Seq.empty, target)

    val Peer.RelayOnionMessage(messageId, message, Some(replyTo)) = expectRelayToConnected(switchboard, recipientKey.publicKey)
    replyTo ! Sent(messageId)
    val ReceiveMessage(finalPayload, _) = OnionMessages.process(recipientKey, message)
    assert(finalPayload.records.unknown == Set(GenericTlv(UInt64(33), hex"abcd")))

    val replyPath = finalPayload.records.get[ReplyPath].get.blindedRoute
    val Right(reply) = buildMessage(randomKey(), randomKey(), Nil, BlindedPath(replyPath), TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(55), hex"1234"))))
    val ReceiveMessage(replyPayload, blindedKey) = receive(Seq(recipientKey, nodeParams.privateKey), reply)

    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(replyPayload, blindedKey))
    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(replyPayload, blindedKey))

    messageSender.expectMessage(Response(replyPayload))
    messageSender.expectNoMessage(10 millis)
  }

  test("sending failure") { f =>
    import f._

    val recipientKey = randomKey()

    postman ! SendMessage(OfferTypes.RecipientNodeId(recipientKey.publicKey), FindRoute, TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), expectsReply = true, messageSender.ref)

    val MessageRouteRequest(waitingForRoute, source, target, _) = router.expectMessageType[MessageRouteRequest]
    assert(source == nodeParams.nodeId)
    assert(target == recipientKey.publicKey)
    waitingForRoute ! MessageRoute(Seq.empty, target)

    val Peer.RelayOnionMessage(messageId, _, Some(replyTo)) = expectRelayToConnected(switchboard, recipientKey.publicKey)
    replyTo ! Disconnected(messageId)

    messageSender.expectMessage(MessageFailed("Peer is not connected"))
    messageSender.expectNoMessage(10 millis)
  }

  test("timeout", Tag(ShortTimeout)) { f =>
    import f._

    val recipientKey = randomKey()

    postman ! SendMessage(OfferTypes.RecipientNodeId(recipientKey.publicKey), FindRoute, TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), expectsReply = true, messageSender.ref)

    val MessageRouteRequest(waitingForRoute, source, target, _) = router.expectMessageType[MessageRouteRequest]
    assert(source == nodeParams.nodeId)
    assert(target == recipientKey.publicKey)
    waitingForRoute ! MessageRoute(Seq.empty, target)

    val Peer.RelayOnionMessage(messageId, message, Some(replyTo)) = expectRelayToConnected(switchboard, recipientKey.publicKey)
    replyTo ! Sent(messageId)
    val ReceiveMessage(finalPayload, _) = OnionMessages.process(recipientKey, message)
    assert(finalPayload.records.unknown == Set(GenericTlv(UInt64(33), hex"abcd")))

    messageSender.expectMessage(NoReply)

    val replyPath = finalPayload.records.get[ReplyPath].get.blindedRoute
    val Right(reply) = buildMessage(randomKey(), randomKey(), Nil, BlindedPath(replyPath), TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(55), hex"1234"))))
    val receiveReply = receive(Seq(recipientKey, nodeParams.privateKey), reply)
    testKit.system.eventStream ! EventStream.Publish(receiveReply)

    messageSender.expectNoMessage(10 millis)
  }

  test("do not expect reply") { f =>
    import f._

    val recipientKey = randomKey()

    postman ! SendMessage(OfferTypes.RecipientNodeId(recipientKey.publicKey), FindRoute, TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), expectsReply = false, messageSender.ref)

    val MessageRouteRequest(waitingForRoute, source, target, _) = router.expectMessageType[MessageRouteRequest]
    assert(source == nodeParams.nodeId)
    assert(target == recipientKey.publicKey)
    waitingForRoute ! MessageRoute(Seq.empty, target)

    val Peer.RelayOnionMessage(messageId, message, Some(replyTo)) = expectRelayToConnected(switchboard, recipientKey.publicKey)
    replyTo ! Sent(messageId)
    val ReceiveMessage(finalPayload, _) = OnionMessages.process(recipientKey, message)
    assert(finalPayload.records.unknown == Set(GenericTlv(UInt64(33), hex"abcd")))
    assert(finalPayload.records.get[ReplyPath].isEmpty)

    messageSender.expectMessage(MessageSent)
    messageSender.expectNoMessage(10 millis)
  }

  test("send to route that starts at ourselves") { f =>
    import f._

    val recipientKey = randomKey()

    val blindedRoute = buildRoute(randomKey(), Seq(IntermediateNode(nodeParams.nodeId)), Recipient(recipientKey.publicKey, None)).route
    postman ! SendMessage(OfferTypes.BlindedPath(blindedRoute), FindRoute, TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), expectsReply = false, messageSender.ref)

    val Peer.RelayOnionMessage(messageId, message, Some(replyTo)) = expectRelayToConnected(switchboard, recipientKey.publicKey)
    replyTo ! Sent(messageId)
    val ReceiveMessage(finalPayload, _) = OnionMessages.process(recipientKey, message)
    assert(finalPayload.records.unknown == Set(GenericTlv(UInt64(33), hex"abcd")))
    assert(finalPayload.records.get[ReplyPath].isEmpty)

    messageSender.expectMessage(MessageSent)
    messageSender.expectNoMessage(10 millis)
  }

  test("forward invoice request to offer manager") { f =>
    import f._

    val offer = OfferTypes.Offer(None, None, randomKey().publicKey, Features.empty, Block.LivenetGenesisBlock.hash)
    val invoiceRequest = OfferTypes.InvoiceRequest(offer, 1000 msat, 1, Features.empty, randomKey(), Block.LivenetGenesisBlock.hash)
    val replyPath = BlindedRoute(EncodedNodeId(randomKey().publicKey), randomKey().publicKey, Seq(BlindedHop(randomKey().publicKey, hex"")))
    val invoiceRequestPayload = MessageOnion.InvoiceRequestPayload(TlvStream(InvoiceRequest(invoiceRequest.records), ReplyPath(replyPath)), TlvStream(PathId(hex"abcd")))
    postman ! WrappedMessage(invoiceRequestPayload, randomKey())

    val request = offerManager.expectMessageType[RequestInvoice]
    assert(request.messagePayload.pathId_opt.contains(hex"abcd"))
  }

  test("reply path") {f =>
    import f._

    val (a, b, c, d) = (randomKey(), randomKey(), randomKey(), randomKey())

    postman ! SendMessage(OfferTypes.RecipientNodeId(d.publicKey), FindRoute, TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(11), hex"012345"))), expectsReply = true, messageSender.ref)

    val MessageRouteRequest(waitingForRoute, source, target, _) = router.expectMessageType[MessageRouteRequest]
    assert(source == nodeParams.nodeId)
    assert(target == d.publicKey)
    waitingForRoute ! MessageRoute(Seq(a.publicKey, b.publicKey, c.publicKey), target)

    val Peer.RelayOnionMessage(messageId, message1, Some(replyTo)) = expectRelayToConnected(switchboard, a.publicKey)
    replyTo ! Sent(messageId)
    val OnionMessages.SendMessage(Right(next2), message2) = OnionMessages.process(a, message1)
    assert(next2 == EncodedNodeId(b.publicKey))
    val OnionMessages.SendMessage(Right(next3), message3) = OnionMessages.process(b, message2)
    assert(next3 == EncodedNodeId(c.publicKey))
    val OnionMessages.SendMessage(Right(next4), message4) = OnionMessages.process(c, message3)
    assert(next4 == EncodedNodeId(d.publicKey))
    val OnionMessages.ReceiveMessage(payload, _) = OnionMessages.process(d, message4)
    assert(payload.records.unknown == Set(GenericTlv(UInt64(11), hex"012345")))
    assert(payload.records.get[ReplyPath].nonEmpty)
    val replyPath = payload.records.get[ReplyPath].get.blindedRoute
    assert(replyPath.firstNodeId == EncodedNodeId(d.publicKey))
    assert(replyPath.length >= nodeParams.onionMessageConfig.minIntermediateHops)
    assert(nodeParams.onionMessageConfig.minIntermediateHops > 5)

    val Right(reply) = OnionMessages.buildMessage(randomKey(), randomKey(), Nil, OnionMessages.BlindedPath(replyPath), TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(13), hex"6789"))))
    val receiveReply = receive(Seq(d, c, b, a, nodeParams.privateKey), reply)
    assert(receiveReply.finalPayload.records.unknown == Set(GenericTlv(UInt64(13), hex"6789")))

    postman ! WrappedMessage(receiveReply.finalPayload, receiveReply.blindedKey)
    messageSender.expectMessage(Response(receiveReply.finalPayload))
  }

  test("send to compact route") { f =>
    import f._

    val recipientKey = randomKey()

    val route = buildRoute(randomKey(), Seq(), Recipient(recipientKey.publicKey, None)).route
    val compactRoute = OfferTypes.BlindedPath(route.copy(firstNodeId = EncodedNodeId.ShortChannelIdDir(isNode1 = false, RealShortChannelId(1234))))
    postman ! SendMessage(compactRoute, FindRoute, TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), expectsReply = false, messageSender.ref)

    val getNodeId = router.expectMessageType[Router.GetNodeId]
    assert(!getNodeId.isNode1)
    assert(getNodeId.shortChannelId == RealShortChannelId(1234))
    getNodeId.replyTo ! Some(recipientKey.publicKey)

    val MessageRouteRequest(waitingForRoute, source, target, _) = router.expectMessageType[MessageRouteRequest]
    assert(source == nodeParams.nodeId)
    assert(target == recipientKey.publicKey)
    waitingForRoute ! MessageRoute(Seq.empty, target)

    val Peer.Connect(nextNodeId, _, replyConnectedTo, _) = switchboard.expectMessageType[Peer.Connect]
    assert(nextNodeId == recipientKey.publicKey)
    val peerConnection = TestProbe[Any]("peerConnection")
    val peer = TestProbe[Any]("peer")
    replyConnectedTo ! ConnectionResult.Connected(peerConnection.ref.toClassic, peer.ref.toClassic)
    val Peer.RelayOnionMessage(messageId, message, Some(replySentTo)) = peer.expectMessageType[Peer.RelayOnionMessage]
    replySentTo ! Sent(messageId)
    val ReceiveMessage(finalPayload, _) = OnionMessages.process(recipientKey, message)
    assert(finalPayload.records.unknown == Set(GenericTlv(UInt64(33), hex"abcd")))
    assert(finalPayload.records.get[ReplyPath].isEmpty)

    messageSender.expectMessage(MessageSent)
    messageSender.expectNoMessage(10 millis)
  }

  test("send to compact route that starts at ourselves") { f =>
    import f._

    val recipientKey = randomKey()

    val route = buildRoute(randomKey(), Seq(IntermediateNode(nodeParams.nodeId)), Recipient(recipientKey.publicKey, None)).route
    val compactRoute = OfferTypes.BlindedPath(route.copy(firstNodeId = EncodedNodeId.ShortChannelIdDir(isNode1 = true, RealShortChannelId(1234))))
    postman ! SendMessage(compactRoute, FindRoute, TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), expectsReply = false, messageSender.ref)

    val getNodeId = router.expectMessageType[Router.GetNodeId]
    assert(getNodeId.isNode1)
    assert(getNodeId.shortChannelId == RealShortChannelId(1234))
    getNodeId.replyTo ! Some(nodeParams.nodeId)

    val Peer.Connect(nextNodeId, _, replyConnectedTo, _) = switchboard.expectMessageType[Peer.Connect]
    assert(nextNodeId == recipientKey.publicKey)
    val peerConnection = TestProbe[Any]("peerConnection")
    val peer = TestProbe[Any]("peer")
    replyConnectedTo ! ConnectionResult.Connected(peerConnection.ref.toClassic, peer.ref.toClassic)
    val Peer.RelayOnionMessage(messageId, message, Some(replySentTo)) = peer.expectMessageType[Peer.RelayOnionMessage]
    replySentTo ! Sent(messageId)
    val ReceiveMessage(finalPayload, _) = OnionMessages.process(recipientKey, message)
    assert(finalPayload.records.unknown == Set(GenericTlv(UInt64(33), hex"abcd")))
    assert(finalPayload.records.get[ReplyPath].isEmpty)

    messageSender.expectMessage(MessageSent)
    messageSender.expectNoMessage(10 millis)
  }
}
