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

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fr.acinq.eclair.KamonExt
import fr.acinq.eclair.channel.ChannelPersisted
import fr.acinq.eclair.db.Databases.FileBackup
import fr.acinq.eclair.db.FileBackupHandler._
import fr.acinq.eclair.db.Monitoring.Metrics

import java.io.File
import java.nio.file.{Files, StandardCopyOption}
import scala.concurrent.duration.FiniteDuration
import scala.sys.process.Process
import scala.util.{Failure, Success, Try}


/**
 * This actor will make a backup of the database it was initialized with at a scheduled interval. It will only
 * perform a backup if a ChannelPersisted event was received since the previous backup.
 */
object FileBackupHandler {

  // @formatter:off

  /**
   * @param targetFile  backup file
   * @param script_opt  (optional) script to execute after the backup completes
   * @param interval    interval between two backups
   */
  case class FileBackupParams(interval: FiniteDuration,
                              targetFile: File,
                              script_opt: Option[String])

  sealed trait Command
  case class WrappedChannelPersisted(wrapped: ChannelPersisted) extends Command
  private case object TickBackup extends Command

  sealed trait BackupEvent
  // this notification is sent when we have completed our backup process (our backup file is ready to be used)
  case object BackupCompleted extends BackupEvent
  // @formatter:on

  def apply(databases: FileBackup, backupParams: FileBackupParams): Behavior[Command] =
    Behaviors.setup { context =>
      // we listen to ChannelPersisted events, which will trigger a backup
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ChannelPersisted](WrappedChannelPersisted))
      Behaviors.withTimers { timers =>
        timers.startTimerAtFixedRate(TickBackup, backupParams.interval)
        new FileBackupHandler(databases, backupParams, context).normal(false)
      }
    }
}

class FileBackupHandler private(databases: FileBackup,
                                backupParams: FileBackupParams,
                                context: ActorContext[Command]) {

  def normal(willBackup: Boolean): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case _: WrappedChannelPersisted =>
        context.log.debug("will perform backup")
        normal(willBackup = true)
      case TickBackup => if (willBackup) {
        context.log.debug("performing backup")
        doBackup()
        normal(willBackup = false)
      } else {
        Behaviors.same
      }
    }

  private def doBackup(): Unit = {
    KamonExt.time(Metrics.FileBackupDuration.withoutTags()) {
      val tmpFile = new File(backupParams.targetFile.getAbsolutePath.concat(".tmp"))
      databases.backup(tmpFile)

      // this will throw an exception if it fails, which is possible if the backup file is not on the same filesystem
      // as the temporary file
      Files.move(tmpFile.toPath, backupParams.targetFile.toPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

      // publish a notification that we have updated our backup
      context.system.eventStream ! EventStream.Publish(BackupCompleted)
      Metrics.FileBackupCompleted.withoutTags().increment()
    }

    backupParams.script_opt.foreach(backupScript => {
      Try {
        // run the script in the current thread and wait until it terminates
        Process(backupScript).!
      } match {
        case Success(exitCode) => context.log.debug(s"backup notify script $backupScript returned $exitCode")
        case Failure(cause) => context.log.warn(s"cannot start backup notify script $backupScript:  $cause")
      }
    })
  }

}