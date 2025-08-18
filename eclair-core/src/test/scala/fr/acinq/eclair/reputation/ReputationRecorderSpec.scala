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
import akka.testkit.TestKit.awaitCond
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.{OutgoingHtlcAdded, OutgoingHtlcFailed, OutgoingHtlcFulfilled, Upstream}
import fr.acinq.eclair.reputation.ReputationRecorder._
import fr.acinq.eclair.wire.protocol._
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

  def makeOutgoingHtlcAdded(downstream: PublicKey, fee: MilliSatoshi, expiry: CltvExpiry, accountable: Boolean): OutgoingHtlcAdded =
    OutgoingHtlcAdded(UpdateAddHtlc(randomBytes32(), randomLong(), 100000 msat, randomBytes32(), expiry, null, None, accountable, None), downstream, fee)

  def makeOutgoingHtlcFulfilled(add: UpdateAddHtlc): OutgoingHtlcFulfilled =
    OutgoingHtlcFulfilled(UpdateFulfillHtlc(add.channelId, add.id, randomBytes32(), TlvStream.empty))

  def makeOutgoingHtlcFailed(add: UpdateAddHtlc): OutgoingHtlcFailed =
    OutgoingHtlcFailed(UpdateFailHtlc(add.channelId, add.id, randomBytes(100), TlvStream.empty))

  test("channel relay") { f =>
    import f._

    val nextNode = randomKey().publicKey

    reputationRecorder ! GetConfidence(replyTo.ref, Some(nextNode), 2000 msat, BlockHeight(0), CltvExpiry(2), accountable = true)
    replyTo.expectMessage(Reputation.Score(0.0, accountable = true))
    val added1 = makeOutgoingHtlcAdded(nextNode, 2000 msat, CltvExpiry(2), accountable = true)
    reputationRecorder ! WrappedOutgoingHtlcAdded(added1)
    reputationRecorder ! WrappedOutgoingHtlcSettled(makeOutgoingHtlcFulfilled(added1.add))
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, Some(nextNode), 1000 msat, BlockHeight(0), CltvExpiry(2), accountable = true)
      val score = replyTo.expectMessageType[Reputation.Score]
      score.outgoingConfidence === (2.0 / 4) +- 0.001
    }, max = 60 seconds)
    val added2 = makeOutgoingHtlcAdded(nextNode, 1000 msat, CltvExpiry(2), accountable = true)
    reputationRecorder ! WrappedOutgoingHtlcAdded(added2)
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, Some(nextNode), 3000 msat, BlockHeight(0), CltvExpiry(2), accountable = true)
      val score = replyTo.expectMessageType[Reputation.Score]
      score.outgoingConfidence === (2.0 / 10) +- 0.001
    }, max = 60 seconds)
    reputationRecorder ! GetConfidence(replyTo.ref, Some(nextNode), 1000 msat, BlockHeight(0), CltvExpiry(2), accountable = true)
    assert({
      val score = replyTo.expectMessageType[Reputation.Score]
      score.outgoingConfidence === (2.0 / 6) +- 0.001
    })
    val added3 = makeOutgoingHtlcAdded(nextNode, 1000 msat, CltvExpiry(2), accountable = true)
    reputationRecorder ! WrappedOutgoingHtlcAdded(added3)
    reputationRecorder ! WrappedOutgoingHtlcSettled(makeOutgoingHtlcFulfilled(added3.add))
    reputationRecorder ! WrappedOutgoingHtlcSettled(makeOutgoingHtlcFailed(added2.add))
    // Not accountable
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, Some(nextNode), 1000 msat, BlockHeight(0), CltvExpiry(2), accountable = false)
      val score = replyTo.expectMessageType[Reputation.Score]
      score.outgoingConfidence == 0.0
    }, max = 60 seconds)
    // Different next node
    reputationRecorder ! GetConfidence(replyTo.ref, Some(randomKey().publicKey), 1000 msat, BlockHeight(0), CltvExpiry(2), accountable = true)
    assert({
      val score = replyTo.expectMessageType[Reputation.Score]
      score.outgoingConfidence == 0.0
    })
    // Very large HTLC
    reputationRecorder ! GetConfidence(replyTo.ref, Some(nextNode), 100000000 msat, BlockHeight(0), CltvExpiry(2), accountable = true)
    assert({
      val score = replyTo.expectMessageType[Reputation.Score]
      score.outgoingConfidence === 0.0 +- 0.001
    })
  }

  test("basic attack") { f =>
    import f._

    val nextNode = randomKey().publicKey

    // Our peer builds a good reputation by sending successful accountable payments
    for (_ <- 1 to 200) {
      val added = makeOutgoingHtlcAdded(nextNode, 10000 msat, CltvExpiry(2), accountable = true)
      reputationRecorder ! WrappedOutgoingHtlcAdded(added)
      reputationRecorder ! WrappedOutgoingHtlcSettled(makeOutgoingHtlcFulfilled(added.add))
    }
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, Some(nextNode), 10000 msat, BlockHeight(0), CltvExpiry(2), accountable = true)
      val score = replyTo.expectMessageType[Reputation.Score]
      score.outgoingConfidence === 0.99 +- 0.01
    }, max = 60 seconds)

    // HTLCs that are not accountable don't benefit from this high reputation.
    reputationRecorder ! GetConfidence(replyTo.ref, Some(nextNode), 10000 msat, BlockHeight(0), CltvExpiry(2), accountable = false)
    assert(replyTo.expectMessageType[Reputation.Score].outgoingConfidence == 0.0)

    // The attack starts, HTLCs stay pending.
    for (_ <- 1 to 100) {
      val added = makeOutgoingHtlcAdded(nextNode, 10000 msat, CltvExpiry(2), accountable = true)
      reputationRecorder ! WrappedOutgoingHtlcAdded(added)
    }
    awaitCond({
      reputationRecorder ! GetConfidence(replyTo.ref, Some(nextNode), 10000 msat, BlockHeight(0), CltvExpiry(2), accountable = true)
      replyTo.expectMessageType[Reputation.Score].outgoingConfidence < 1.0 / 2
    }, max = 60 seconds)
  }
}