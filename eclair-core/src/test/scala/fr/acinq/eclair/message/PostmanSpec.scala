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
import fr.acinq.eclair.message.OnionMessages.ReceiveMessage
import fr.acinq.eclair.message.Postman._
import fr.acinq.eclair.wire.protocol.MessageOnion.FinalPayload
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.{EncryptedData, ReplyPath}
import fr.acinq.eclair.wire.protocol.{GenericTlv, TlvStream}
import fr.acinq.eclair.{UInt64, randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.concurrent.duration._

class PostmanSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(postman: ActorRef[Command], messageRecipient: TestProbe[OnionMessageResponse], switchboard: TestProbe[RelayMessage])

  override def withFixture(test: OneArgTest): Outcome = {
    val messageRecipient = TestProbe[OnionMessageResponse]("messageRecipient")
    val switchboard = TestProbe[RelayMessage]("switchboard")
    val postman = testKit.spawn(Postman(switchboard.ref))
    try {
      withFixture(test.toNoArgTest(FixtureParam(postman, messageRecipient, switchboard)))
    } finally {
      testKit.stop(postman)
    }
  }

  test("message forwarded only once") { f =>
    import f._

    val pathId = randomBytes32()
    val ourNodeId = randomKey().publicKey
    val recipient = randomKey().publicKey
    val replyPath = OnionMessages.buildRoute(randomKey(), Nil, OnionMessages.Recipient(ourNodeId, Some(pathId)))
    val (_, messageExpectingReply) = OnionMessages.buildMessage(randomKey(), randomKey(), Nil, OnionMessages.Recipient(recipient, None), ReplyPath(replyPath) :: Nil)
    val payload = FinalPayload(TlvStream(EncryptedData(replyPath.encryptedPayloads.last) :: Nil, GenericTlv(UInt64(42), hex"abcd") :: Nil))

    postman ! SendMessage(recipient, messageExpectingReply, Some(pathId), messageRecipient.ref, 1 second)

    val RelayMessage(messageId, _, nextNodeId, message, _, _) = switchboard.expectMessageType[RelayMessage]
    assert(nextNodeId === recipient)
    assert(message === messageExpectingReply)
    postman ! SendingStatus(Sent(messageId))
    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(payload, Some(pathId)))
    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(payload, Some(pathId)))

    messageRecipient.expectMessage(Response(payload))
    messageRecipient.expectNoMessage()
  }

  test("sending failure") { f =>
    import f._

    val pathId = randomBytes32()
    val ourNodeId = randomKey().publicKey
    val recipient = randomKey().publicKey
    val replyPath = OnionMessages.buildRoute(randomKey(), Nil, OnionMessages.Recipient(ourNodeId, Some(pathId)))
    val (_, messageExpectingReply) = OnionMessages.buildMessage(randomKey(), randomKey(), Nil, OnionMessages.Recipient(recipient, None), ReplyPath(replyPath) :: Nil)

    postman ! SendMessage(recipient, messageExpectingReply, Some(pathId), messageRecipient.ref, 1 second)

    val RelayMessage(messageId, _, nextNodeId, message, _, _) = switchboard.expectMessageType[RelayMessage]
    assert(nextNodeId === recipient)
    assert(message === messageExpectingReply)
    postman ! SendingStatus(Disconnected(messageId))

    messageRecipient.expectMessage(SendingStatus(Disconnected(messageId)))
  }

  test("timeout") { f =>
    import f._

    val pathId = randomBytes32()
    val ourNodeId = randomKey().publicKey
    val recipient = randomKey().publicKey
    val replyPath = OnionMessages.buildRoute(randomKey(), Nil, OnionMessages.Recipient(ourNodeId, Some(pathId)))
    val (_, messageExpectingReply) = OnionMessages.buildMessage(randomKey(), randomKey(), Nil, OnionMessages.Recipient(recipient, None), ReplyPath(replyPath) :: Nil)
    val payload = FinalPayload(TlvStream(EncryptedData(replyPath.encryptedPayloads.last) :: Nil, GenericTlv(UInt64(42), hex"abcd") :: Nil))

    postman ! SendMessage(recipient, messageExpectingReply, Some(pathId), messageRecipient.ref, 1 millis)

    val RelayMessage(messageId, _, nextNodeId, message, _, _) = switchboard.expectMessageType[RelayMessage]
    assert(nextNodeId === recipient)
    assert(message === messageExpectingReply)
    postman ! SendingStatus(Sent(messageId))

    messageRecipient.expectMessage(NoReply)

    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(payload, Some(pathId)))

    messageRecipient.expectNoMessage()
  }

  test("do not expect reply") { f =>
    import f._

    val recipient = randomKey().publicKey
    val (_, messageExpectingReply) = OnionMessages.buildMessage(randomKey(), randomKey(), Nil, OnionMessages.Recipient(recipient, None), Nil)

    postman ! SendMessage(recipient, messageExpectingReply, None, messageRecipient.ref, 1 second)

    val RelayMessage(messageId, _, nextNodeId, message, _, _) = switchboard.expectMessageType[RelayMessage]
    assert(nextNodeId === recipient)
    assert(message === messageExpectingReply)
    postman ! SendingStatus(Sent(messageId))

    messageRecipient.expectMessage(SendingStatus(Sent(messageId)))
  }
}
