package fr.acinq.eclair.db

import java.io.File
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorLogging, ActorSystem, Deploy, PoisonPill, Props}
import akka.dispatch.{BoundedMailbox, BoundedMessageQueueSemantics, NonBlockingBoundedMailbox, PriorityGenerator, RequiresMessageQueue, UnboundedPriorityMailbox}
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.eclair.channel.ChannelPersisted

import scala.concurrent.duration.FiniteDuration


/**
  * README !
  * This actor will synchronously make a backup of the database it was initialized with whenever it receives
  * a ChannelPersisted event.
  * To avoid piling up messages and entering an endless backup loop, it is supposed to be used with a bounded mailbox
  * with a single item:
  *
  * backup-mailbox {
  *   mailbox-type = "akka.dispatch.BoundedMailbox"
  *   mailbox-capacity = 1
  *   mailbox-push-timeout-time = 0
  * }
  *
  * Messages that cannot be processed will be sent to dead letters
  *
  * @param databases database to backup
  * @param tmpFile temporary file
  * @param backupFile final backup file
  */
class BackupHandler(databases: Databases, tmpFile: File, backupFile: File) extends Actor with RequiresMessageQueue[BoundedMessageQueueSemantics] with ActorLogging {

  // we listen to ChannelPersisted events, which will trigger a backup
  context.system.eventStream.subscribe(self, classOf[ChannelPersisted])

  def receive = {
    case persisted: ChannelPersisted =>
      val start = System.currentTimeMillis()
      databases.backup(tmpFile)
      val result = tmpFile.renameTo(backupFile)
      require(result, s"cannot rename $tmpFile to $backupFile")
      val end = System.currentTimeMillis()
      log.info(s"database backup triggered by channelId=${persisted.channelId} took ${end - start}ms")
  }
}

trait BackupHandlerMailboxSemantics

object BackupHandler {

  def props(databases: Databases, tmpFile: File, backupFile: File) = Props(new BackupHandler(databases, tmpFile, backupFile))
}
