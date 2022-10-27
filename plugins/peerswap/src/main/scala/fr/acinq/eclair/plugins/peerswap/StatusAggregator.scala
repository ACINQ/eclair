/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.plugins.peerswap

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.eclair.plugins.peerswap.SwapResponses.Status

object StatusAggregator {
  def apply(swapsCount: Int, replyTo: ActorRef[Iterable[Status]]): Behavior[Status] = Behaviors.setup { context =>
    if (swapsCount == 0) {
      replyTo ! Seq()
      Behaviors.stopped
    } else {
      new StatusAggregator(context, swapsCount, replyTo).waiting(Set())
    }
  }
}

private class StatusAggregator(context: ActorContext[Status], swapsCount: Int, replyTo: ActorRef[Iterable[Status]]) {
  private def waiting(statuses: Set[Status]): Behavior[Status] = {
    Behaviors.receiveMessage[Status] {
      case s: Status if statuses.size + 1 == swapsCount =>
          replyTo ! (statuses + s)
          Behaviors.stopped
      case s: Status =>
        waiting(statuses + s)
    }
  }
}