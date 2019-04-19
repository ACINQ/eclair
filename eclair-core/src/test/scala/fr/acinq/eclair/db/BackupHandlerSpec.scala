package fr.acinq.eclair.db

import java.io.File
import java.sql.DriverManager
import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.testkit.TestKit
import fr.acinq.eclair.channel.ChannelPersisted
import fr.acinq.eclair.db.sqlite.SqliteChannelsDb
import fr.acinq.eclair.{TestConstants, TestUtils, randomBytes32}
import org.scalatest.FunSuiteLike

import scala.concurrent.duration._

class BackupHandlerSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  test("process backups") {
    val db = TestConstants.inMemoryDb()
    val wip = new File(TestUtils.BUILD_DIRECTORY, s"wip-${UUID.randomUUID()}")
    val dest = new File(TestUtils.BUILD_DIRECTORY, s"backup-${UUID.randomUUID()}")
    wip.deleteOnExit()
    dest.deleteOnExit()
    val channel = ChannelStateSpec.normal
    db.channels.addOrUpdateChannel(channel)
    assert(db.channels.listLocalChannels() == Seq(channel))

    val handler = system.actorOf(BackupHandler.props(db, dest, ""))
    handler ! ChannelPersisted(null, TestConstants.Alice.nodeParams.nodeId, randomBytes32, null)
    handler ! ChannelPersisted(null, TestConstants.Alice.nodeParams.nodeId, randomBytes32, null)
    handler ! ChannelPersisted(null, TestConstants.Alice.nodeParams.nodeId, randomBytes32, null)
    awaitCond(dest.exists(), 5 seconds)

    val db1 = new SqliteChannelsDb(DriverManager.getConnection(s"jdbc:sqlite:$dest"))
    val check = db1.listLocalChannels()
    assert(check == Seq(channel))
  }
}
