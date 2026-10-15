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

import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.testkit.TestProbe
import fr.acinq.eclair.channel.ChannelPersisted
import fr.acinq.eclair.db.Databases.FileBackup
import fr.acinq.eclair.db.FileBackupHandler.{BackupCompleted, BackupEvent}
import fr.acinq.eclair.db.sqlite.{SqliteChannelsDb, SqliteInboundFeesDb}
import fr.acinq.eclair.payment.relay.Relayer.InboundFees
import fr.acinq.eclair.wire.internal.channel.ChannelCodecsSpec
import fr.acinq.eclair.{MilliSatoshiLong, TestConstants, TestDatabases, TestKitBaseClass, TestUtils, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuiteLike

import java.io.File
import java.sql.DriverManager
import java.util.UUID
import scala.concurrent.duration.DurationInt

class SqliteFileBackupHandlerSpec extends TestKitBaseClass with AnyFunSuiteLike {

  test("process backups") {
    val db = TestDatabases.inMemoryDb()
    val dest = new File(TestUtils.BUILD_DIRECTORY, s"backup-${UUID.randomUUID()}")
    dest.deleteOnExit()
    // inbound fees are backed up to a separate file, always named inboundfees.sqlite.bak, next to the main backup file
    val inboundFeesDest = new File(TestUtils.BUILD_DIRECTORY, "inboundfees.sqlite.bak")
    inboundFeesDest.delete()
    inboundFeesDest.deleteOnExit()
    val channel = ChannelCodecsSpec.normal
    db.channels.addOrUpdateChannel(channel)
    assert(db.channels.listLocalChannels() == Seq(channel))
    val nodeId = randomKey().publicKey
    val inboundFees = InboundFees(1000 msat, 100)
    db.inboundFees.addOrUpdateInboundFees(nodeId, inboundFees)
    assert(db.inboundFees.getInboundFees(nodeId).contains(inboundFees))

    val params = FileBackupHandler.FileBackupParams(
      interval = 10 seconds,
      targetFile = dest,
      script_opt = None
    )

    val handler = system.spawn(FileBackupHandler(db.asInstanceOf[FileBackup], params), name = "filebackup")
    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[BackupEvent])

    handler ! FileBackupHandler.WrappedChannelPersisted(ChannelPersisted(null, TestConstants.Alice.nodeParams.nodeId, randomBytes32(), null))
    handler ! FileBackupHandler.WrappedChannelPersisted(ChannelPersisted(null, TestConstants.Alice.nodeParams.nodeId, randomBytes32(), null))
    handler ! FileBackupHandler.WrappedChannelPersisted(ChannelPersisted(null, TestConstants.Alice.nodeParams.nodeId, randomBytes32(), null))
    probe.expectMsg(20 seconds, BackupCompleted)
    probe.expectNoMessage()

    val db1 = new SqliteChannelsDb(DriverManager.getConnection(s"jdbc:sqlite:$dest"))
    val check = db1.listLocalChannels()
    assert(check == Seq(channel))

    val db2 = new SqliteInboundFeesDb(DriverManager.getConnection(s"jdbc:sqlite:$inboundFeesDest"))
    assert(db2.getInboundFees(nodeId).contains(inboundFees))
  }
}
