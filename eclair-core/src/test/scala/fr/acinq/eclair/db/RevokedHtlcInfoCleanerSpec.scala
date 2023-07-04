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

package fr.acinq.eclair.db

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import com.softwaremill.quicklens.ModifyPimp
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.TestDatabases.TestSqliteDatabases
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.{CltvExpiry, randomBytes32}
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class RevokedHtlcInfoCleanerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  test("clean closed channels at regular intervals") {
    val channelsDb = TestSqliteDatabases().channels

    val channelId = randomBytes32()
    channelsDb.addOrUpdateChannel(ChannelCodecsSpec.normal.modify(_.commitments.params.channelId).setTo(channelId))
    channelsDb.addHtlcInfo(channelId, 17, randomBytes32(), CltvExpiry(561))
    channelsDb.addHtlcInfo(channelId, 19, randomBytes32(), CltvExpiry(1105))
    channelsDb.addHtlcInfo(channelId, 23, randomBytes32(), CltvExpiry(1729))
    channelsDb.removeChannel(channelId)
    assert(channelsDb.listHtlcInfos(channelId, 17).nonEmpty)
    assert(channelsDb.listHtlcInfos(channelId, 19).nonEmpty)
    assert(channelsDb.listHtlcInfos(channelId, 23).nonEmpty)

    val config = RevokedHtlcInfoCleaner.Config(batchSize = 1, interval = 10 millis)
    testKit.spawn(RevokedHtlcInfoCleaner(channelsDb, config))

    eventually {
      assert(channelsDb.listHtlcInfos(channelId, 17).isEmpty)
      assert(channelsDb.listHtlcInfos(channelId, 19).isEmpty)
      assert(channelsDb.listHtlcInfos(channelId, 23).isEmpty)
    }
  }

}
