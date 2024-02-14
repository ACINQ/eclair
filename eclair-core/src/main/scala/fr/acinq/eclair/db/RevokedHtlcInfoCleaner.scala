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

import akka.actor.typed.Behavior
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import fr.acinq.bitcoin.scalacompat.ByteVector32

import scala.concurrent.duration.FiniteDuration

/**
 * When a channel is closed or a splice transaction confirms, we can remove the information about old HTLCs that was
 * stored in the DB to punish revoked commitments. We potentially have millions of rows to delete per channel, and there
 * is no rush to remove them. We don't want this to negatively impact active channels, so this actor deletes that data
 * in small batches, at regular intervals.
 */
object RevokedHtlcInfoCleaner {

  // @formatter:off
  sealed trait Command
  case class ForgetHtlcInfos(channelId: ByteVector32, beforeCommitIndex: Long) extends Command
  private case object DeleteBatch extends Command
  // @formatter:on

  case class Config(batchSize: Int, interval: FiniteDuration)

  def apply(db: ChannelsDb, config: Config): Behavior[Command] = {
    Behaviors.setup { context =>
      context.system.eventStream ! EventStream.Subscribe(context.self)
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(DeleteBatch, config.interval)
        Behaviors.receiveMessage {
          case ForgetHtlcInfos(channelId, beforeCommitIndex) =>
            db.markHtlcInfosForRemoval(channelId, beforeCommitIndex)
            Behaviors.same
          case DeleteBatch =>
            db.removeHtlcInfos(config.batchSize)
            Behaviors.same
        }
      }
    }
  }

}
