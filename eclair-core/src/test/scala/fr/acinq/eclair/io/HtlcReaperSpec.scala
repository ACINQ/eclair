/*
 * Copyright 2018 ACINQ SAS
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

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.ChannelStateSpec
import fr.acinq.eclair.randomBytes
import fr.acinq.eclair.wire.{TemporaryNodeFailure, UpdateAddHtlc}
import org.scalatest.FunSuiteLike

import scala.concurrent.duration._

/**
  * Created by PM on 27/01/2017.
  */

class HtlcReaperSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  test("init and cleanup") {

    val data = ChannelStateSpec.normal

    // assuming that data has incoming htlcs 0 and 1, we don't care about the amount/payment_hash/onion fields
    val add0 = UpdateAddHtlc(data.channelId, 0, 20000, randomBytes(32), 100, "")
    val add1 = UpdateAddHtlc(data.channelId, 1, 30000, randomBytes(32), 100, "")

    // unrelated htlc
    val add99 = UpdateAddHtlc(randomBytes(32), 0, 12345678, randomBytes(32), 100, "")

    val brokenHtlcs = Seq(add0, add1, add99)
    val brokenHtlcKiller = system.actorOf(Props[HtlcReaper], name = "htlc-reaper")
    brokenHtlcKiller ! brokenHtlcs

    val sender = TestProbe()
    val channel = TestProbe()

    // channel goes to NORMAL state
    sender.send(brokenHtlcKiller, ChannelStateChanged(channel.ref, system.deadLetters, data.commitments.remoteParams.nodeId, OFFLINE, NORMAL, data))
    channel.expectMsg(CMD_FAIL_HTLC(add0.id, Right(TemporaryNodeFailure), commit = true))
    channel.expectMsg(CMD_FAIL_HTLC(add1.id, Right(TemporaryNodeFailure), commit = true))
    channel.expectNoMsg(100 millis)

    // lets'assume that channel was disconnected before having signed the fails, and gets connected again:
    sender.send(brokenHtlcKiller, ChannelStateChanged(channel.ref, system.deadLetters, data.commitments.remoteParams.nodeId, OFFLINE, NORMAL, data))
    channel.expectMsg(CMD_FAIL_HTLC(add0.id, Right(TemporaryNodeFailure), commit = true))
    channel.expectMsg(CMD_FAIL_HTLC(add1.id, Right(TemporaryNodeFailure), commit = true))
    channel.expectNoMsg(100 millis)

    // let's now assume that the channel get's reconnected, and it had the time to fail the htlcs
    val data1 = data.copy(commitments = data.commitments.copy(localCommit = data.commitments.localCommit.copy(spec = data.commitments.localCommit.spec.copy(htlcs = Set.empty))))
    sender.send(brokenHtlcKiller, ChannelStateChanged(channel.ref, system.deadLetters, data.commitments.remoteParams.nodeId, OFFLINE, NORMAL, data1))
    channel.expectNoMsg(100 millis)

    // reaper has cleaned up htlc, so next time it won't fail them anymore, even if we artificially submit the former state
    sender.send(brokenHtlcKiller, ChannelStateChanged(channel.ref, system.deadLetters, data.commitments.remoteParams.nodeId, OFFLINE, NORMAL, data))
    channel.expectNoMsg(100 millis)

  }

}
