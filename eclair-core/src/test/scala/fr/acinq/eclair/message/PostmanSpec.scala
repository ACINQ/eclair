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
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.io.MessageRelay.{Disconnected, Sent}
import fr.acinq.eclair.io.Switchboard.RelayMessage
import fr.acinq.eclair.message.OnionMessages.{BlindedPath, IntermediateNode, ReceiveMessage, Recipient, buildMessage, buildRoute}
import fr.acinq.eclair.message.Postman._
import fr.acinq.eclair.wire.protocol.{GenericTlv, OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{NodeParams, TestConstants, UInt64, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.concurrent.duration._

class PostmanSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(postman: ActorRef[Command], nodeParams: NodeParams, messageRecipient: TestProbe[OnionMessageResponse], switchboard: TestProbe[RelayMessage])

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = TestConstants.Alice.nodeParams
    val messageRecipient = TestProbe[OnionMessageResponse]("messageRecipient")
    val switchboard = TestProbe[RelayMessage]("switchboard")
    val postman = testKit.spawn(Postman(nodeParams, switchboard.ref))
    try {
      withFixture(test.toNoArgTest(FixtureParam(postman, nodeParams, messageRecipient, switchboard)))
    } finally {
      testKit.stop(postman)
    }
  }

  test("message forwarded only once") { f =>
    import f._

    val ourKey = randomKey()
    val recipientKey = randomKey()

    postman ! SendMessage(Nil, Recipient(recipientKey.publicKey, None), Some(Seq(ourKey.publicKey)), TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), messageRecipient.ref, 100 millis)

    val RelayMessage(messageId, _, nextNodeId, message, _, _) = switchboard.expectMessageType[RelayMessage]
    assert(nextNodeId == recipientKey.publicKey)
    postman ! SendingStatus(Sent(messageId))
    val ReceiveMessage(finalPayload) = OnionMessages.process(recipientKey, message)
    assert(finalPayload.records.unknown == Set(GenericTlv(UInt64(33), hex"abcd")))

    val replyPath = finalPayload.replyPath_opt.get
    val Right((_, reply)) = buildMessage(recipientKey, randomKey(), randomKey(), Nil, BlindedPath(replyPath), TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(55), hex"1234"))))
    val ReceiveMessage(replyPayload) = OnionMessages.process(ourKey, reply)

    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(replyPayload))
    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(replyPayload))

    messageRecipient.expectMessage(Response(replyPayload))
    messageRecipient.expectNoMessage()
  }

  test("sending failure") { f =>
    import f._

    val ourKey = randomKey()
    val recipientKey = randomKey()

    postman ! SendMessage(Nil, Recipient(recipientKey.publicKey, None), Some(Seq(ourKey.publicKey)), TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), messageRecipient.ref, 100 millis)

    val RelayMessage(messageId, _, nextNodeId, _, _, _) = switchboard.expectMessageType[RelayMessage]
    assert(nextNodeId == recipientKey.publicKey)
    postman ! SendingStatus(Disconnected(messageId))

    messageRecipient.expectMessage(MessageFailed("Peer is not connected"))
    messageRecipient.expectNoMessage()
  }

  test("timeout") { f =>
    import f._

    val ourKey = randomKey()
    val recipientKey = randomKey()

    postman ! SendMessage(Nil, Recipient(recipientKey.publicKey, None), Some(Seq(ourKey.publicKey)), TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), messageRecipient.ref, 1 millis)

    val RelayMessage(messageId, _, nextNodeId, message, _, _) = switchboard.expectMessageType[RelayMessage]
    assert(nextNodeId == recipientKey.publicKey)
    postman ! SendingStatus(Sent(messageId))
    val ReceiveMessage(finalPayload) = OnionMessages.process(recipientKey, message)
    assert(finalPayload.records.unknown == Set(GenericTlv(UInt64(33), hex"abcd")))

    messageRecipient.expectMessage(NoReply)

    val replyPath = finalPayload.replyPath_opt.get
    val Right((_, reply)) = buildMessage(recipientKey, randomKey(), randomKey(), Nil, BlindedPath(replyPath), TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(55), hex"1234"))))
    val ReceiveMessage(replyPayload) = OnionMessages.process(ourKey, reply)
    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(replyPayload))

    messageRecipient.expectNoMessage()
  }

  test("do not expect reply") { f =>
    import f._

    val recipientKey = randomKey()

    postman ! SendMessage(Nil, Recipient(recipientKey.publicKey, None), None, TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), messageRecipient.ref, 100 millis)

    val RelayMessage(messageId, _, nextNodeId, message, _, _) = switchboard.expectMessageType[RelayMessage]
    assert(nextNodeId == recipientKey.publicKey)
    postman ! SendingStatus(Sent(messageId))
    val ReceiveMessage(finalPayload) = OnionMessages.process(recipientKey, message)
    assert(finalPayload.records.unknown == Set(GenericTlv(UInt64(33), hex"abcd")))
    assert(finalPayload.replyPath_opt.isEmpty)

    messageRecipient.expectMessage(MessageSent)
    messageRecipient.expectNoMessage()
  }

  test("send to route that starts at ourselves") {f =>
    import f._

    val recipientKey = randomKey()

    val blindedRoute = buildRoute(randomKey(), Seq(IntermediateNode(nodeParams.nodeId)), Recipient(recipientKey.publicKey, None))
    postman ! SendMessage(Nil, BlindedPath(blindedRoute), None, TlvStream(Set.empty[OnionMessagePayloadTlv], Set(GenericTlv(UInt64(33), hex"abcd"))), messageRecipient.ref, 100 millis)

    val RelayMessage(messageId, _, nextNodeId, message, _, _) = switchboard.expectMessageType[RelayMessage]
    assert(nextNodeId == recipientKey.publicKey)
    postman ! SendingStatus(Sent(messageId))
    val ReceiveMessage(finalPayload) = OnionMessages.process(recipientKey, message)
    assert(finalPayload.records.unknown == Set(GenericTlv(UInt64(33), hex"abcd")))
    assert(finalPayload.replyPath_opt.isEmpty)

    messageRecipient.expectMessage(MessageSent)
    messageRecipient.expectNoMessage()
  }
}
