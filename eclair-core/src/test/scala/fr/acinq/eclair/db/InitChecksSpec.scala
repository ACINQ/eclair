/*
 * Copyright 2019 ACINQ SAS
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

import fr.acinq.eclair.db.Databases.SafetyChecks
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.payment.ChannelPaymentRelayed
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{Features, MilliSatoshiLong, TestDatabases, TimestampMilli, TimestampSecond, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.DurationInt

class InitChecksSpec extends AnyFunSuite {

  import fr.acinq.eclair.TestDatabases.forAllDbs

  test("db init checks") {
    forAllDbs { db: TestDatabases =>

      // populating data
      db.channels.addOrUpdateChannel(ChannelCodecsSpec.normal)
      db.channels.updateChannelMeta(ChannelCodecsSpec.normal.channelId, ChannelEvent.EventType.Created)
      db.network.addNode(Announcements.makeNodeAnnouncement(randomKey(), "node-A", Color(50, 99, -80), Nil, Features.empty, TimestampSecond.now() - 45.days))
      db.network.addNode(Announcements.makeNodeAnnouncement(randomKey(), "node-B", Color(50, 99, -80), Nil, Features.empty, TimestampSecond.now() - 3.days))
      db.network.addNode(Announcements.makeNodeAnnouncement(randomKey(), "node-C", Color(50, 99, -80), Nil, Features.empty, TimestampSecond.now() - 7.minutes))
      db.audit.add(ChannelPaymentRelayed(421 msat, 400 msat, randomBytes32(), randomBytes32(), randomBytes32(), TimestampMilli.now() - 3.seconds))

      // this check is passing
      db.check(SafetyChecks(
        localChannelsMaxAge = 3 minutes,
        networkNodesMaxAge = 30 minutes,
        auditRelayedMaxAge = 10 minutes,
        localChannelsMinCount = 1,
        networkNodesMinCount = 2,
        networkChannelsMinCount = 0
      ))

      // this check is failing
      intercept[IllegalArgumentException] {
        db.check(SafetyChecks(
          localChannelsMaxAge = 3 minutes,
          networkNodesMaxAge = 30 minutes,
          auditRelayedMaxAge = 10 minutes,
          localChannelsMinCount = 10,
          networkNodesMinCount = 2,
          networkChannelsMinCount = 0
        ))
      }
    }
  }


}


