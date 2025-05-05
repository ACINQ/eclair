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

package fr.acinq.eclair.reputation

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.eventstream.EventStream
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.{OutgoingHtlcAdded, OutgoingHtlcFailed, OutgoingHtlcFulfilled, Upstream}
import fr.acinq.eclair.reputation.ReputationRecorder._
import fr.acinq.eclair.wire.protocol.{TlvStream, UpdateAddHtlc, UpdateAddHtlcTlv, UpdateFailHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{CltvExpiry, MilliSatoshi, MilliSatoshiLong, TimestampMilli, randomBytes, randomBytes32, randomKey, randomLong}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class ReputationRecorderSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val originNode: PublicKey = randomKey().publicKey

  case class FixtureParam(config: Reputation.Config, reputationRecorder: ActorRef[Command], replyTo: TestProbe[Confidence])

  override def withFixture(test: OneArgTest): Outcome = {
    val config = Reputation.Config(enabled = true, 1 day, 10 seconds, 2)
    val replyTo = TestProbe[Confidence]("confidence")
    val reputationRecorder = testKit.spawn(ReputationRecorder(config))
    withFixture(test.toNoArgTest(FixtureParam(config, reputationRecorder.ref, replyTo)))
  }

  def makeChannelUpstream(nodeId: PublicKey, endorsement: Int, amount: MilliSatoshi = 1000000 msat): Upstream.Hot.Channel =
    Upstream.Hot.Channel(UpdateAddHtlc(randomBytes32(), randomLong(), amount, randomBytes32(), CltvExpiry(1234), null, TlvStream(UpdateAddHtlcTlv.Endorsement(endorsement))), TimestampMilli.now(), nodeId)

  def makeOutgoingHtlcAdded(upstream: Upstream.Hot, fee: MilliSatoshi): OutgoingHtlcAdded =
    OutgoingHtlcAdded(UpdateAddHtlc(randomBytes32(), randomLong(), 100000 msat, randomBytes32(), CltvExpiry(456), null, TlvStream.empty), upstream, fee)

  def makeOutgoingHtlcFulfilled(add: UpdateAddHtlc): OutgoingHtlcFulfilled =
    OutgoingHtlcFulfilled(UpdateFulfillHtlc(add.channelId, add.id, randomBytes32(), TlvStream.empty))

  def makeOutgoingHtlcFailed(add: UpdateAddHtlc): OutgoingHtlcFailed =
    OutgoingHtlcFailed(UpdateFailHtlc(add.channelId, add.id, randomBytes(100), TlvStream.empty))

  test("channel relay") { f =>
    import f._

    val listener = TestProbe[Any]()
    testKit.system.eventStream ! EventStream.Subscribe(listener.ref)
    testKit.system.eventStream ! EventStream.Subscribe(listener.ref)
    testKit.system.eventStream ! EventStream.Subscribe(listener.ref)

    val upstream1 = makeChannelUpstream(originNode, 7)
    reputationRecorder ! GetConfidence(replyTo.ref, upstream1, 2000 msat)
    assert(replyTo.expectMessageType[Confidence].value == 0)
    val added1 = makeOutgoingHtlcAdded(upstream1, 2000 msat)
    testKit.system.eventStream ! EventStream.Publish(added1)
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFulfilled(added1.add))
    listener.expectMessageType[OutgoingHtlcAdded]
    listener.expectMessageType[OutgoingHtlcFulfilled]
    val upstream2 = makeChannelUpstream(originNode, 7)
    reputationRecorder ! GetConfidence(replyTo.ref, upstream2, 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value === (2.0 / 4) +- 0.001)
    val added2 = makeOutgoingHtlcAdded(upstream2, 1000 msat)
    testKit.system.eventStream ! EventStream.Publish(added2)
    listener.expectMessageType[OutgoingHtlcAdded]
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(originNode, 7), 3000 msat)
    assert(replyTo.expectMessageType[Confidence].value === (2.0 / 10) +- 0.001)
    val upstream3 = makeChannelUpstream(originNode, 7)
    reputationRecorder ! GetConfidence(replyTo.ref, upstream3, 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value === (2.0 / 6) +- 0.001)
    val added3 = makeOutgoingHtlcAdded(upstream3, 1000 msat)
    testKit.system.eventStream ! EventStream.Publish(added3)
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFulfilled(added3.add))
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFailed(added2.add))
    listener.expectMessageType[OutgoingHtlcAdded]
    listener.expectMessageType[OutgoingHtlcFulfilled]
    listener.expectMessageType[OutgoingHtlcFailed]
    // Not endorsed
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(originNode, 0), 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value == 0)
    // Different origin node
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(randomKey().publicKey, 7), 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value == 0)
    // Very large HTLC
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(originNode, 7), 100000000 msat)
    assert(replyTo.expectMessageType[Confidence].value === 0.0 +- 0.001)
  }

  test("trampoline relay") { f =>
    import f._

    val listener = TestProbe[Any]()
    testKit.system.eventStream ! EventStream.Subscribe(listener.ref)
    testKit.system.eventStream ! EventStream.Subscribe(listener.ref)
    testKit.system.eventStream ! EventStream.Subscribe(listener.ref)

    val (a, b, c) = (randomKey().publicKey, randomKey().publicKey, randomKey().publicKey)

    val upstream1 = Upstream.Hot.Trampoline(makeChannelUpstream(a, 7, 20000 msat) :: makeChannelUpstream(b, 7, 40000 msat) :: makeChannelUpstream(c, 0, 60000 msat) :: Nil)
    reputationRecorder ! GetTrampolineConfidence(replyTo.ref, upstream1, 12000 msat)
    assert(replyTo.expectMessageType[Confidence].value == 0)
    val added1 = makeOutgoingHtlcAdded(upstream1, 6000 msat)
    testKit.system.eventStream ! EventStream.Publish(added1)
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFulfilled(added1.add))
    listener.expectMessageType[OutgoingHtlcAdded]
    listener.expectMessageType[OutgoingHtlcFulfilled]
    val upstream2 = Upstream.Hot.Trampoline(makeChannelUpstream(a, 7, 10000 msat) :: makeChannelUpstream(c, 0, 10000 msat) :: Nil)
    reputationRecorder ! GetTrampolineConfidence(replyTo.ref, upstream2, 2000 msat)
    assert(replyTo.expectMessageType[Confidence].value === (1.0 / 3) +- 0.001)
    val added2 = makeOutgoingHtlcAdded(upstream2, 2000 msat)
    testKit.system.eventStream ! EventStream.Publish(added2)
    listener.expectMessageType[OutgoingHtlcAdded]
    val upstream3 = Upstream.Hot.Trampoline(makeChannelUpstream(a, 0, 10000 msat) :: makeChannelUpstream(b, 7, 20000 msat) :: Nil)
    reputationRecorder ! GetTrampolineConfidence(replyTo.ref, upstream3, 3000 msat)
    assert(replyTo.expectMessageType[Confidence].value == 0)
    val added3 = makeOutgoingHtlcAdded(upstream3, 3000 msat)
    testKit.system.eventStream ! EventStream.Publish(added3)
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFailed(added2.add))
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFulfilled(added3.add))
    listener.expectMessageType[OutgoingHtlcAdded]
    listener.expectMessageType[OutgoingHtlcFailed]
    listener.expectMessageType[OutgoingHtlcFulfilled]

    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(a, 7), 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value === (1.0 / 3) +- 0.001)
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(a, 0), 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value === (1.0 / 3) +- 0.001)
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(b, 7), 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value === (4.0 / 6) +- 0.001)
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(b, 0), 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value == 0.0)
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(c, 0), 1000 msat)
    assert(replyTo.expectMessageType[Confidence].value === (3.0 / 5) +- 0.001)
  }
}