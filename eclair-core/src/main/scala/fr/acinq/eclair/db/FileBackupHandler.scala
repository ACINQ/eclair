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

import akka.actor.{Actor, ActorLogging, Props}
import akka.dispatch.{BoundedMessageQueueSemantics, RequiresMessageQueue}
import fr.acinq.eclair.KamonExt
import fr.acinq.eclair.channel.ChannelPersisted
import fr.acinq.eclair.db.Databases.FileBackup
import fr.acinq.eclair.db.Monitoring.Metrics

import java.io.File
import java.nio.file.{Files, StandardCopyOption}
import scala.sys.process.Process
import scala.util.{Failure, Success, Try}


/**
 * This actor will synchronously make a backup of the database it was initialized with whenever it receives
 * a ChannelPersisted event.
 * To avoid piling up messages and entering an endless backup loop, it is supposed to be used with a bounded mailbox
 * with a single item:
 *
 * backup-mailbox {
 *   mailbox-type = "akka.dispatch.NonBlockingBoundedMailbox"
 *   mailbox-capacity = 1
 * }
 *
 * Messages that cannot be processed will be sent to dead letters
 *
 * NB: Constructor is private so users will have to use BackupHandler.props() which always specific a custom mailbox.
 *
 * @param databases        database to backup
 * @param backupFile       backup file
 * @param backupScript_opt (optional) script to execute after the backup completes
 */
class FileBackupHandler private(databases: FileBackup, backupFile: File, backupScript_opt: Option[String]) extends Actor with RequiresMessageQueue[BoundedMessageQueueSemantics] with ActorLogging {

  // we listen to ChannelPersisted events, which will trigger a backup
  context.system.eventStream.subscribe(self, classOf[ChannelPersisted])

  def receive: Receive = {
    case persisted: ChannelPersisted =>
      KamonExt.time(Metrics.FileBackupDuration.withoutTags()) {
        val tmpFile = new File(backupFile.getAbsolutePath.concat(".tmp"))
        databases.backup(tmpFile)

        // this will throw an exception if it fails, which is possible if the backup file is not on the same filesystem
        // as the temporary file
        Files.move(tmpFile.toPath, backupFile.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

        // publish a notification that we have updated our backup
        context.system.eventStream.publish(BackupCompleted)
        Metrics.FileBackupCompleted.withoutTags().increment()
      }

      backupScript_opt.foreach(backupScript => {
        Try {
          // run the script in the current thread and wait until it terminates
          Process(backupScript).!
        } match {
          case Success(exitCode) => log.debug(s"backup notify script $backupScript returned $exitCode")
          case Failure(cause) => log.warning(s"cannot start backup notify script $backupScript:  $cause")
        }
      })
  }
}

sealed trait BackupEvent

// this notification is sent when we have completed our backup process (our backup file is ready to be used)
case object BackupCompleted extends BackupEvent

object FileBackupHandler {
  // using this method is the only way to create a BackupHandler actor
  // we make sure that it uses a custom bounded mailbox, and a custom pinned dispatcher (i.e our actor will have its own thread pool with 1 single thread)
  def props(databases: FileBackup, backupFile: File, backupScript_opt: Option[String]) =
    Props(new FileBackupHandler(databases, backupFile, backupScript_opt))
      .withMailbox("eclair.backup-mailbox")
      .withDispatcher("eclair.backup-dispatcher")
}
