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
import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.channel.{CMD_CLOSE, Register}
import fr.acinq.eclair.plugins.offlinecommands.OfflineChannelsCloser.{CloseChannels, CloseCommandsRegistered, Response, WaitingForPeer}
import fr.acinq.eclair.{TestConstants, randomBytes32}
import org.scalatest.funsuite.AnyFunSuiteLike

class OfflineChannelsCloserSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  test("receive command to close channels") {
    val probe = TestProbe[Response]()
    val register = TestProbe[Register.Forward[CMD_CLOSE]]()
    val channelsCloser = testKit.spawn(OfflineChannelsCloser(TestConstants.Alice.nodeParams, register.ref.toClassic))
    val channel1 = randomBytes32()
    val channel2 = randomBytes32()
    channelsCloser ! CloseChannels(probe.ref, Seq(channel1, channel2), None, None)
    probe.expectMessage(CloseCommandsRegistered(Map(channel1 -> WaitingForPeer, channel2 -> WaitingForPeer)))
  }

}
