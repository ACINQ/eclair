/*
 * Copyright 2024 ACINQ SAS
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
import akka.actor.typed.scaladsl.Behaviors
import fr.acinq.eclair.{PeerStorageConfig, TimestampSecond}

/**
 * This actor frequently deletes from our DB peer storage from nodes with whom we don't have channels anymore, after a
 * grace period.
 */
object PeerStorageCleaner {
  // @formatter:off
  sealed trait Command
  private case object CleanPeerStorage extends Command
  // @formatter:on

  def apply(db: PeersDb, config: PeerStorageConfig): Behavior[Command] = {
    Behaviors.withTimers { timers =>
      timers.startTimerWithFixedDelay(CleanPeerStorage, config.cleanUpFrequency)
      Behaviors.receiveMessage {
        case CleanPeerStorage =>
          db.removePeerStorage(TimestampSecond.now() - config.removalDelay)
          Behaviors.same
      }
    }
  }

}
