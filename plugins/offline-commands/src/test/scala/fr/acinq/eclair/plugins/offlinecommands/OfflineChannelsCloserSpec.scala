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

package fr.acinq.eclair.plugins.offlinecommands

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import fr.acinq.eclair.channel._
import fr.acinq.eclair.plugins.offlinecommands.OfflineChannelsCloser._
import fr.acinq.eclair.{TestConstants, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

class OfflineChannelsCloserSpec extends ScalaTestWithActorTestKit with AnyFunSuiteLike {

  test("close channels when online") {
    val channel1 = randomBytes32()
    val channel2 = randomBytes32()
    val senderProbe = TestProbe[CloseCommandsRegistered]()
    val statusProbe = TestProbe[PendingCommands]()
    val register = TestProbe[Register.Forward[CMD_CLOSE]]()
    val channelsCloser = testKit.spawn(OfflineChannelsCloser(TestConstants.Alice.nodeParams, register.ref.toClassic))

    channelsCloser ! CloseChannels(senderProbe.ref, Seq(channel1, channel2), None, Some(hex"001436f83c329274e04b104000fbb27fcfe20a47cdaf"), None)
    val commands = Seq(
      register.expectMessageType[Register.Forward[CMD_CLOSE]],
      register.expectMessageType[Register.Forward[CMD_CLOSE]]
    )
    assert(commands.map(_.channelId).toSet == Set(channel1, channel2))
    commands.foreach(c => assert(c.message.scriptPubKey.contains(hex"001436f83c329274e04b104000fbb27fcfe20a47cdaf")))
    senderProbe.expectMessage(CloseCommandsRegistered(Map(channel1 -> WaitingForPeer, channel2 -> WaitingForPeer)))

    // The first channel was online and successfully closes.
    testKit.system.eventStream ! EventStream.Publish(ChannelStateChanged(null, channel1, null, randomKey().publicKey, CLOSING, CLOSED, None))
    statusProbe.awaitAssert {
      channelsCloser ! GetPendingCommands(statusProbe.ref)
      assert(statusProbe.expectMessageType[PendingCommands].channels.keySet == Set(channel2))
    }

    // The second channel was offline and comes back online.
    testKit.system.eventStream ! EventStream.Publish(ChannelStateChanged(null, channel2, null, randomKey().publicKey, SYNCING, NORMAL, None))
    assert(register.expectMessageType[Register.Forward[CMD_CLOSE]].channelId == channel2)
    register.expectNoMessage(100 millis)
    channelsCloser ! GetPendingCommands(statusProbe.ref)
    assert(statusProbe.expectMessageType[PendingCommands].channels.keySet == Set(channel2))

    testKit.system.eventStream ! EventStream.Publish(ChannelStateChanged(null, channel2, null, randomKey().publicKey, CLOSING, CLOSED, None))
    statusProbe.awaitAssert {
      channelsCloser ! GetPendingCommands(statusProbe.ref)
      assert(statusProbe.expectMessageType[PendingCommands].channels.isEmpty)
    }
  }

  test("force-close after delay") {
    val channel = randomBytes32()
    val register = TestProbe[Register.Forward[CloseCommand]]()
    val channelsCloser = testKit.spawn(OfflineChannelsCloser(TestConstants.Alice.nodeParams, register.ref.toClassic))

    channelsCloser ! CloseChannels(TestProbe[CloseCommandsRegistered]().ref, Seq(channel), Some(50 millis), None, None)
    assert(register.expectMessageType[Register.Forward[CloseCommand]].message.isInstanceOf[CMD_CLOSE])

    // After the delay expires, we automatically force-close, regardless of the channel state.
    val forceClose = register.expectMessageType[Register.Forward[CloseCommand]]
    assert(forceClose.channelId == channel)
    assert(forceClose.message.isInstanceOf[CMD_FORCECLOSE])

    // The channel eventually force-closes.
    testKit.system.eventStream ! EventStream.Publish(ChannelStateChanged(null, channel, null, randomKey().publicKey, OFFLINE, CLOSING, None))
    testKit.system.eventStream ! EventStream.Publish(ChannelStateChanged(null, channel, null, randomKey().publicKey, CLOSING, CLOSED, None))
    val statusProbe = TestProbe[PendingCommands]()
    statusProbe.awaitAssert {
      channelsCloser ! GetPendingCommands(statusProbe.ref)
      assert(statusProbe.expectMessageType[PendingCommands].channels.isEmpty)
    }
  }

}
