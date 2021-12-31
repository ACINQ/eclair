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

package fr.acinq.eclair.message

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.message.OnionMessages.ReceiveMessage
import fr.acinq.eclair.message.Postman._
import fr.acinq.eclair.wire.protocol.MessageOnion.FinalPayload
import fr.acinq.eclair.wire.protocol.OnionMessagePayloadTlv.EncryptedData
import fr.acinq.eclair.wire.protocol.{GenericTlv, TlvStream}
import fr.acinq.eclair.{UInt64, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.concurrent.duration._

class PostmanSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(postman: ActorRef[Command], messageRecipient: TestProbe[Option[FinalPayload]])

  override def withFixture(test: OneArgTest): Outcome = {
    val messageRecipient = TestProbe[Option[FinalPayload]]("messageRecipient")
    val postman = testKit.spawn(Postman())
    try {
      withFixture(test.toNoArgTest(FixtureParam(postman, messageRecipient)))
    } finally {
      testKit.stop(postman)
    }
  }

  test("message forwarded only once") { f =>
    import f._

    val pathId = ByteVector32(hex"0101010101010101010101010101010101010101010101010101010101010101")
    val route = OnionMessages.buildRoute(randomKey(), Nil, Left(OnionMessages.Recipient(randomKey().publicKey, Some(pathId))))
    val payload = FinalPayload(TlvStream(EncryptedData(route.encryptedPayloads.last) :: Nil, GenericTlv(UInt64(42), hex"abcd") :: Nil))

    postman ! SubscribeOnce(pathId, messageRecipient.ref, 100 millis)

    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(payload, Some(pathId)))
    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(payload, Some(pathId)))

    messageRecipient.expectMessage(Some(payload))
    messageRecipient.expectNoMessage()
  }

  test("unsubscribe") { f =>
    import f._

    val pathId = ByteVector32(hex"0101010101010101010101010101010101010101010101010101010101010101")
    val route = OnionMessages.buildRoute(randomKey(), Nil, Left(OnionMessages.Recipient(randomKey().publicKey, Some(pathId))))
    val payload = FinalPayload(TlvStream(EncryptedData(route.encryptedPayloads.last) :: Nil, GenericTlv(UInt64(42), hex"abcd") :: Nil))

    postman ! SubscribeOnce(pathId, messageRecipient.ref, 100 millis)
    postman ! Unsubscribe(pathId)

    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(payload, Some(pathId)))

    messageRecipient.expectMessage(None)
    messageRecipient.expectNoMessage()
  }

  test("timeout") { f =>
    import f._

    val pathId = ByteVector32(hex"0101010101010101010101010101010101010101010101010101010101010101")
    val route = OnionMessages.buildRoute(randomKey(), Nil, Left(OnionMessages.Recipient(randomKey().publicKey, Some(pathId))))
    val payload = FinalPayload(TlvStream(EncryptedData(route.encryptedPayloads.last) :: Nil, GenericTlv(UInt64(42), hex"abcd") :: Nil))

    postman ! SubscribeOnce(pathId, messageRecipient.ref, 1 millis)

    messageRecipient.expectMessage(None)

    testKit.system.eventStream ! EventStream.Publish(ReceiveMessage(payload, Some(pathId)))

    messageRecipient.expectNoMessage()
  }
}
