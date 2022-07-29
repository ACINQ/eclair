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
import fr.acinq.bitcoin.scalacompat.SatoshiLong
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.plugins.offlinecommands.OfflineChannelsCloser._
import fr.acinq.eclair.{TestConstants, randomBytes32, randomKey}
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.HexStringSyntax

import java.sql.DriverManager
import scala.concurrent.duration.DurationInt

class OfflineChannelsCloserSpec extends ScalaTestWithActorTestKit with AnyFunSuiteLike {

  test("close channels when online") {
    val db = new SqliteOfflineCommandsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
    val (channelId1, channelId2) = (randomBytes32(), randomBytes32())
    val senderProbe = TestProbe[CloseCommandsRegistered]()
    val statusProbe = TestProbe[PendingCommands]()
    val register = TestProbe[Register.Forward[CMD_CLOSE]]()
    val channelsCloser = testKit.spawn(OfflineChannelsCloser(TestConstants.Alice.nodeParams, db, register.ref.toClassic))

    channelsCloser ! CloseChannels(senderProbe.ref, Seq(channelId1, channelId2), None, Some(hex"001436f83c329274e04b104000fbb27fcfe20a47cdaf"), None)
    val commands = Seq(
      register.expectMessageType[Register.Forward[CMD_CLOSE]],
      register.expectMessageType[Register.Forward[CMD_CLOSE]]
    )
    assert(commands.map(_.channelId).toSet == Set(channelId1, channelId2))
    commands.foreach(c => assert(c.message.scriptPubKey.contains(hex"001436f83c329274e04b104000fbb27fcfe20a47cdaf")))
    senderProbe.expectMessage(CloseCommandsRegistered(Map(channelId1 -> ClosingStatus.Pending, channelId2 -> ClosingStatus.Pending)))
    assert(db.listPendingCloseCommands().keySet == Set(channelId1, channelId2))

    // The first channel was online and successfully closes.
    testKit.system.eventStream ! EventStream.Publish(ChannelStateChanged(null, channelId1, null, randomKey().publicKey, CLOSING, CLOSED, None))
    statusProbe.awaitAssert {
      channelsCloser ! GetPendingCommands(statusProbe.ref)
      assert(statusProbe.expectMessageType[PendingCommands].channels.keySet == Set(channelId2))
    }
    assert(db.listPendingCloseCommands().keySet == Set(channelId2))

    // The second channel was offline and comes back online.
    testKit.system.eventStream ! EventStream.Publish(ChannelStateChanged(null, channelId2, null, randomKey().publicKey, SYNCING, NORMAL, None))
    assert(register.expectMessageType[Register.Forward[CMD_CLOSE]].channelId == channelId2)
    register.expectNoMessage(100 millis)
    channelsCloser ! GetPendingCommands(statusProbe.ref)
    assert(statusProbe.expectMessageType[PendingCommands].channels.keySet == Set(channelId2))

    testKit.system.eventStream ! EventStream.Publish(ChannelStateChanged(null, channelId2, null, randomKey().publicKey, CLOSING, CLOSED, None))
    statusProbe.awaitAssert {
      channelsCloser ! GetPendingCommands(statusProbe.ref)
      assert(statusProbe.expectMessageType[PendingCommands].channels.isEmpty)
    }
    assert(db.listPendingCloseCommands().isEmpty)
  }

  test("force-close after delay") {
    val db = new SqliteOfflineCommandsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
    val channel = randomBytes32()
    val register = TestProbe[Register.Forward[CloseCommand]]()
    val channelsCloser = testKit.spawn(OfflineChannelsCloser(TestConstants.Alice.nodeParams, db, register.ref.toClassic))

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

  test("replay previous commands stored in db") {
    val db = new SqliteOfflineCommandsDb(DriverManager.getConnection("jdbc:sqlite::memory:"))
    val (channelId1, channelId2, channelId3) = (randomBytes32(), randomBytes32(), randomBytes32())
    val feerates = ClosingFeerates(FeeratePerKw(750 sat), FeeratePerKw(500 sat), FeeratePerKw(800 sat))
    val senderProbe = TestProbe[CloseCommandsRegistered]()
    val register = TestProbe[Register.Forward[CloseCommand]]()

    {
      // We run the plugin a first time.
      val channelsCloser = testKit.spawn(OfflineChannelsCloser(TestConstants.Alice.nodeParams, db, register.ref.toClassic))
      channelsCloser ! CloseChannels(senderProbe.ref, Seq(channelId1), Some(1 hour), Some(hex"001436f83c329274e04b104000fbb27fcfe20a47cdaf"), Some(feerates))
      assert(register.expectMessageType[Register.Forward[CloseCommand]].message.isInstanceOf[CMD_CLOSE])
      senderProbe.expectMessage(CloseCommandsRegistered(Map(channelId1 -> ClosingStatus.Pending)))
      assert(db.listPendingCloseCommands().keySet == Set(channelId1))

      channelsCloser ! CloseChannels(senderProbe.ref, Seq(channelId2), Some(50 millis), None, None)
      assert(register.expectMessageType[Register.Forward[CloseCommand]].message.isInstanceOf[CMD_CLOSE])
      assert(register.expectMessageType[Register.Forward[CloseCommand]].message.isInstanceOf[CMD_FORCECLOSE])
      senderProbe.expectMessage(CloseCommandsRegistered(Map(channelId2 -> ClosingStatus.Pending)))
      assert(db.listPendingCloseCommands().keySet == Set(channelId1, channelId2))
    }
    {
      // After restarting our node, pending commands are automatically replayed.
      val channelsCloser = testKit.spawn(OfflineChannelsCloser(TestConstants.Alice.nodeParams, db, register.ref.toClassic))
      val previousCmds = Seq(
        register.expectMessageType[Register.Forward[CloseCommand]],
        register.expectMessageType[Register.Forward[CloseCommand]],
      )
      assert(previousCmds.map(_.channelId).toSet == Set(channelId1, channelId2))
      val cmd1 = previousCmds.find(_.channelId == channelId1).value.message.asInstanceOf[CMD_CLOSE]
      assert(cmd1.scriptPubKey.contains(hex"001436f83c329274e04b104000fbb27fcfe20a47cdaf"))
      assert(cmd1.feerates.contains(feerates))
      val cmd2 = previousCmds.find(_.channelId == channelId2).value
      assert(cmd2.message.isInstanceOf[CMD_FORCECLOSE])

      channelsCloser ! CloseChannels(senderProbe.ref, Seq(channelId3), None, None, Some(feerates))
      val cmd3 = register.expectMessageType[Register.Forward[CloseCommand]]
      assert(cmd3.channelId == channelId3)
      assert(cmd3.message.asInstanceOf[CMD_CLOSE].scriptPubKey.isEmpty)
      assert(cmd3.message.asInstanceOf[CMD_CLOSE].feerates.contains(feerates))
      senderProbe.expectMessage(CloseCommandsRegistered(Map(channelId3 -> ClosingStatus.Pending)))
    }
  }

}
