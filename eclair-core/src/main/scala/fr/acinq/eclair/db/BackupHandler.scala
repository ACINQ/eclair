package fr.acinq.eclair.db

import java.io.File

import akka.actor.{Actor, ActorLogging, Status}
import akka.pattern.pipe
import fr.acinq.eclair.channel.ChannelPersisted
import grizzled.slf4j.{Logger, Logging}

import scala.concurrent.{ExecutionContext, Future}

class BackupHandler(databases: Databases, wip: File, destination: File)(implicit ec: ExecutionContext) extends Actor with ActorLogging {
  import BackupHandler._

  // we listen to ChannelPersisted events, which will trigger a backup
  override def preStart(): Unit = {
    super.preStart()
    context.system.eventStream.subscribe(self, classOf[ChannelPersisted])
  }

  override def unhandled(message: Any): Unit = {
    super.unhandled(message)
    log.warning(s"unhandled message $message")
  }

  def receive = idle

  // idle mode: start backup as soon as we receive an event
  def idle: Receive = {
    case persisted: ChannelPersisted =>
      doBackup(databases, wip, destination, persisted) pipeTo self
      context become busy(None)
  }

  // busy mode: there is already a backup in progress
  // `again` tells us if we have to do another one when we're done with the current one
  def busy(again: Option[ChannelPersisted]): Receive = {
    case persisted: ChannelPersisted =>
      log.info(s"replacing pending backup with a newer one")
      context become busy(Some(persisted))

    case Done if again.isDefined =>
      doBackup(databases, wip, destination, again.get) pipeTo self
      context become busy(None)

    case Done =>
      context become idle

      // we use the pipe pattern so errors are wrapped in akka.Status.Failure
    case Status.Failure(cause) if again.isDefined =>
      log.error("database backup failed: {}", cause)
      doBackup(databases, wip, destination, again.get) pipeTo self
      context become busy(None)

    case Status.Failure(cause) =>
      log.error("database backup failed: {}", cause)
      context become idle
  }
}

object BackupHandler extends Logging {

  case object Done
  /**
    * Database backup of our database to a destination file.
    * Backup is done is 2 steps: first it it written to a wip file, then this file is renamed to the destination file.
    * Renaming/moving a file * should * be atomic so the destination file is always consistent and usable but this is
    * implementation dependent and it may not be the case with some file systems and/or operating systems
    *
    * @param databases database to backup
    * @param wip work-in-progress file
    * @param destination destination file
    * @param persisted channel persistence event that triggered the backup
    * @param ec execution context
    * @return `Done` if backup was successful
    */
  def doBackup(databases: Databases, wip: File, destination: File, persisted: ChannelPersisted)(implicit ec: ExecutionContext) : Future[BackupHandler.Done.type] = Future {
    logger.info(s" database backup triggered by ${persisted.channelId} to $destination starts")
    val start = System.currentTimeMillis()
    databases.backup(wip)
    val result = wip.renameTo(destination)
    require(result, s"cannot rename $wip to $destination")
    val end = System.currentTimeMillis()
    logger.info(s" database backup triggered by ${persisted.channelId} to $destination done in ${end - start} ms")
    Done
  }
}
