package fr.acinq.eclair.db

import java.io.File

import akka.actor.{Actor, ActorLogging, Props}
import akka.dispatch.{BoundedMessageQueueSemantics, RequiresMessageQueue}
import fr.acinq.eclair.channel.ChannelPersisted


/**
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
  * @param databases  database to backup
  * @param backupFile backup file
  *
  * Constructor is private so users will have to use BackupHandler.props() which always specific a custom mailbox
  */
class BackupHandler private(databases: Databases, backupFile: File) extends Actor with RequiresMessageQueue[BoundedMessageQueueSemantics] with ActorLogging {

  // we listen to ChannelPersisted events, which will trigger a backup
  context.system.eventStream.subscribe(self, classOf[ChannelPersisted])

  def receive = {
    case persisted: ChannelPersisted =>
      val start = System.currentTimeMillis()
      val tmpFile = new File(backupFile.getAbsolutePath.concat(".tmp"))
      databases.backup(tmpFile)
      val result = tmpFile.renameTo(backupFile)
      require(result, s"cannot rename $tmpFile to $backupFile")
      val end = System.currentTimeMillis()
      log.info(s"database backup triggered by channelId=${persisted.channelId} took ${end - start}ms")
  }
}

object BackupHandler {
  // using this method is the only way to create a BackupHandler actor
  // we make sure that it uses a custom bounded mailbox, and a custom pinned dispatcher (i.e our actor will have its own thread pool with 1 single thread)
  def props(databases: Databases, backupFile: File) = Props(new BackupHandler(databases, backupFile)).withMailbox("eclair.backup-mailbox").withDispatcher("eclair.backup-dispatcher")
}
