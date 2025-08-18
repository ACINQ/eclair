/*
 * Copyright 2025 ACINQ SAS
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
import akka.testkit.TestKit.awaitCond
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.{OutgoingHtlcAdded, OutgoingHtlcFailed, OutgoingHtlcFulfilled, Upstream}
import fr.acinq.eclair.reputation.ReputationRecorder._
import fr.acinq.eclair.wire.protocol.{TlvStream, UpdateAddHtlc, UpdateAddHtlcTlv, UpdateFailHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{BlockHeight, CltvExpiry, MilliSatoshi, MilliSatoshiLong, TimestampMilli, randomBytes, randomBytes32, randomKey, randomLong}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class ReputationRecorderSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {
  val originNode: PublicKey = randomKey().publicKey

  case class FixtureParam(config: Reputation.Config, reputationRecorder: ActorRef[Command], replyTo: TestProbe[Reputation.Score])

  override def withFixture(test: OneArgTest): Outcome = {
    val config = Reputation.Config(enabled = true, 1 day, 10 minutes)
    val replyTo = TestProbe[Reputation.Score]("confidence")
    val reputationRecorder = testKit.spawn(ReputationRecorder(config))
    withFixture(test.toNoArgTest(FixtureParam(config, reputationRecorder.ref, replyTo)))
  }

  def makeChannelUpstream(nodeId: PublicKey, endorsement: Int, amount: MilliSatoshi = 1000000 msat): Upstream.Hot.Channel =
    Upstream.Hot.Channel(UpdateAddHtlc(randomBytes32(), randomLong(), amount, randomBytes32(), CltvExpiry(1234), null, TlvStream(UpdateAddHtlcTlv.Endorsement(endorsement))), TimestampMilli.now(), nodeId, 0.25)

  def makeOutgoingHtlcAdded(upstream: Upstream.Hot, downstream: PublicKey, fee: MilliSatoshi, expiry: CltvExpiry): OutgoingHtlcAdded =
    OutgoingHtlcAdded(UpdateAddHtlc(randomBytes32(), randomLong(), 100000 msat, randomBytes32(), expiry, null, TlvStream.empty), downstream, upstream, fee)

  def makeOutgoingHtlcFulfilled(add: UpdateAddHtlc): OutgoingHtlcFulfilled =
    OutgoingHtlcFulfilled(UpdateFulfillHtlc(add.channelId, add.id, randomBytes32(), TlvStream.empty))

  def makeOutgoingHtlcFailed(add: UpdateAddHtlc): OutgoingHtlcFailed =
    OutgoingHtlcFailed(UpdateFailHtlc(add.channelId, add.id, randomBytes(100), TlvStream.empty))

  test("channel relay") { f =>
    import f._

    val (nextA, nextB) = (randomKey().publicKey, randomKey().publicKey)

    val upstream1 = makeChannelUpstream(originNode, 7)
    reputationRecorder ! GetConfidence(replyTo.ref, upstream1, Some(nextA), 2000 msat, BlockHeight(0), CltvExpiry(2))
    replyTo.expectMessage(Reputation.Score(0.0, 0.0))
    val added1 = makeOutgoingHtlcAdded(upstream1, nextA, 2000 msat, CltvExpiry(2))
    testKit.system.eventStream ! EventStream.Publish(added1)
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFulfilled(added1.add))
    val upstream2 = makeChannelUpstream(originNode, 7)
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, upstream2, Some(nextB), 1000 msat, BlockHeight(0), CltvExpiry(2))
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence === (2.0 / 4) +- 0.001 && score.outgoingConfidence == 0.0
    }, max = 60 seconds)
    val added2 = makeOutgoingHtlcAdded(upstream2, nextB, 1000 msat, CltvExpiry(2))
    testKit.system.eventStream ! EventStream.Publish(added2)
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(originNode, 7), Some(nextA), 3000 msat, BlockHeight(0), CltvExpiry(2))
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence === (2.0 / 10) +- 0.001 && score.outgoingConfidence === (2.0 / 8) +- 0.001
    }, max = 60 seconds)
    val upstream3 = makeChannelUpstream(originNode, 7)
    reputationRecorder ! GetConfidence(replyTo.ref, upstream3, Some(nextB), 1000 msat, BlockHeight(0), CltvExpiry(2))
    assert({
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence === (2.0 / 6) +- 0.001 && score.outgoingConfidence == 0.0
    })
    val added3 = makeOutgoingHtlcAdded(upstream3, nextB, 1000 msat, CltvExpiry(2))
    testKit.system.eventStream ! EventStream.Publish(added3)
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFulfilled(added3.add))
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFailed(added2.add))
    // Not endorsed
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(originNode, 0), Some(nextA), 1000 msat, BlockHeight(0), CltvExpiry(2))
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence == 0.0 && score.outgoingConfidence === (2.0 / 4) +- 0.001
    }, max = 60 seconds)
    // Different origin node
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(randomKey().publicKey, 7), Some(randomKey().publicKey), 1000 msat, BlockHeight(0), CltvExpiry(2))
    assert({
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence == 0.0 && score.outgoingConfidence == 0.0
    })
    // Very large HTLC
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(originNode, 7), Some(nextA), 100000000 msat, BlockHeight(0), CltvExpiry(2))
    assert({
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence === 0.0 +- 0.001 && score.outgoingConfidence === 0.0 +- 0.001
    })
  }

  test("trampoline relay") { f =>
    import f._

    val (a, b, c) = (randomKey().publicKey, randomKey().publicKey, randomKey().publicKey)
    val (d, e) = (randomKey().publicKey, randomKey().publicKey)

    val upstream1 = Upstream.Hot.Trampoline(makeChannelUpstream(a, 7, 20000 msat) :: makeChannelUpstream(b, 7, 40000 msat) :: makeChannelUpstream(c, 0, 10000 msat) :: makeChannelUpstream(c, 2, 20000 msat) :: makeChannelUpstream(c, 2, 30000 msat) :: Nil)
    reputationRecorder ! GetConfidence(replyTo.ref, upstream1, Some(d), 12000 msat, BlockHeight(0), CltvExpiry(2))
    assert({
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence == 0.0 && score.outgoingConfidence == 0.0
    })
    val added1 = makeOutgoingHtlcAdded(upstream1, d, 6000 msat, CltvExpiry(2))
    testKit.system.eventStream ! EventStream.Publish(added1)
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFulfilled(added1.add))
    val upstream2 = Upstream.Hot.Trampoline(makeChannelUpstream(a, 7, 10000 msat) :: makeChannelUpstream(c, 0, 10000 msat) :: Nil)
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, upstream2, Some(d), 2000 msat, BlockHeight(0), CltvExpiry(2))
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence === (1.0 / 3) +- 0.001 && score.outgoingConfidence === (6.0 / 10) +- 0.001
    }, max = 60 seconds)
    val added2 = makeOutgoingHtlcAdded(upstream2, d, 2000 msat, CltvExpiry(2))
    testKit.system.eventStream ! EventStream.Publish(added2)
    val upstream3 = Upstream.Hot.Trampoline(makeChannelUpstream(a, 0, 10000 msat) :: makeChannelUpstream(b, 7, 15000 msat) :: makeChannelUpstream(b, 7, 5000 msat) :: Nil)
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, upstream3, Some(e), 3000 msat, BlockHeight(0), CltvExpiry(2))
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence == 0.0 && score.outgoingConfidence == 0.0
    }, max = 60 seconds)
    val added3 = makeOutgoingHtlcAdded(upstream3, e, 3000 msat, CltvExpiry(2))
    testKit.system.eventStream ! EventStream.Publish(added3)
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFailed(added2.add))
    testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFulfilled(added3.add))

    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(a, 7), Some(d), 1000 msat, BlockHeight(0), CltvExpiry(2))
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence === (2.0 / 4) +- 0.001 && score.outgoingConfidence === (6.0 / 8) +- 0.001
    }, max = 60 seconds)
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(a, 0), Some(d), 1000 msat, BlockHeight(0), CltvExpiry(2))
    assert({
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence === (1.0 / 3) +- 0.001 && score.outgoingConfidence === (6.0 / 8) +- 0.001
    })
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(b, 7), Some(d), 1000 msat, BlockHeight(0), CltvExpiry(2))
    assert({
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence === (4.0 / 6) +- 0.001 && score.outgoingConfidence === (6.0 / 8) +- 0.001
    })
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(b, 0), Some(e), 1000 msat, BlockHeight(0), CltvExpiry(2))
    assert({
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence == 0.0 && score.outgoingConfidence === (3.0 / 5) +- 0.001
    })
    reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(c, 0), Some(e), 1000 msat, BlockHeight(0), CltvExpiry(2))
    assert({
      val score = replyTo.expectMessageType[Reputation.Score]
      score.incomingConfidence === (3.0 / 5) +- 0.001 && score.outgoingConfidence === (3.0 / 5) +- 0.001
    })
  }

  test("basic attack") { f =>
    import f._

    val nextNode = randomKey().publicKey

    // Our peer builds a good reputation by sending successful endorsed payments
    for (_ <- 1 to 200) {
      val upstream = makeChannelUpstream(originNode, 7)
      val added = makeOutgoingHtlcAdded(upstream, nextNode, 10000 msat, CltvExpiry(2))
      testKit.system.eventStream ! EventStream.Publish(added)
      testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFulfilled(added.add))
    }
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(originNode, 7), Some(nextNode), 10000 msat, BlockHeight(0), CltvExpiry(2))
      val score = replyTo.expectMessageType[Reputation.Score]
      (score.incomingConfidence === 1.0 +- 0.01) && (score.outgoingConfidence === 1.0 +- 0.01)
    }, max = 60 seconds)

    // HTLCs with lower endorsement don't benefit from this high reputation.
    for (endorsement <- 0 to 6) {
      reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(originNode, endorsement), Some(nextNode), 10000 msat, BlockHeight(0), CltvExpiry(2))
      assert(replyTo.expectMessageType[Reputation.Score].incomingConfidence == 0.0)
    }

    // The attack starts, HTLCs stay pending.
    for (_ <- 1 to 100) {
      val upstream = makeChannelUpstream(originNode, 7)
      val added = makeOutgoingHtlcAdded(upstream, nextNode, 10000 msat, CltvExpiry(2))
      testKit.system.eventStream ! EventStream.Publish(added)
    }
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(originNode, 7), Some(nextNode), 10000 msat, BlockHeight(0), CltvExpiry(2))
      replyTo.expectMessageType[Reputation.Score].incomingConfidence < 1.0 / 2
    }, max = 60 seconds)
  }

  test("sink attack") {f =>
    import f._

    val (a, b, c) = (randomKey().publicKey, randomKey().publicKey, randomKey().publicKey)
    val attacker = randomKey().publicKey

    // A, B and C are good nodes with a good reputation.
    for (node <- Seq(a, b, c)) {
      val upstream = makeChannelUpstream(node, 7)
      val added = makeOutgoingHtlcAdded(upstream, randomKey().publicKey, 10000000 msat, CltvExpiry(2))
      testKit.system.eventStream ! EventStream.Publish(added)
      testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFulfilled(added.add))
      awaitCond({
        reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(node, 7), Some(attacker), 10000 msat, BlockHeight(0), CltvExpiry(2))
        val score = replyTo.expectMessageType[Reputation.Score]
        score.incomingConfidence > 0.9 && score.outgoingConfidence == 0.0
      }, max = 60 seconds)
    }

    // The attacker attracts payments by setting low fees and builds its outgoing reputation.
    for (node <- Seq(a, b, c)) {
      for (_ <- 1 to 100) {
        val upstream = makeChannelUpstream(node, 7)
        val added = makeOutgoingHtlcAdded(upstream, attacker, 10000 msat, CltvExpiry(2))
        testKit.system.eventStream ! EventStream.Publish(added)
        testKit.system.eventStream ! EventStream.Publish(makeOutgoingHtlcFulfilled(added.add))
      }
    }

    // When the attack starts, the outgoing confidence goes down quickly.
    for (node <- Seq(a, b, c)) {
      for (_ <- 1 to 50) {
        val upstream = makeChannelUpstream(node, 7)
        val added = makeOutgoingHtlcAdded(upstream, attacker, 10000 msat, CltvExpiry(2))
        testKit.system.eventStream ! EventStream.Publish(added)
      }
    }
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, makeChannelUpstream(a, 7), Some(attacker), 10000 msat, BlockHeight(0), CltvExpiry(2))
      replyTo.expectMessageType[Reputation.Score].outgoingConfidence < 1.0 / 2
    }, max = 60 seconds)
  }
}