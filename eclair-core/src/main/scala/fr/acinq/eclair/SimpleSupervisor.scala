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

package fr.acinq.eclair

import akka.actor.{Actor, ActorLogging, OneForOneStrategy, Props, SupervisorStrategy}

import scala.concurrent.duration._

/**
  * This supervisor will supervise a single child actor using the provided SupervisorStrategy
  * All incoming messages will be forwarded to the child actor.
  *
  * Created by PM on 17/03/2017.
  */
class SimpleSupervisor(childProps: Props, childName: String, strategy: SupervisorStrategy.Directive) extends Actor with ActorLogging {

  val child = context.actorOf(childProps, childName)

  override def receive: Receive = {
    case msg => child forward msg
  }

  // we allow at most <maxNrOfRetries> within <withinTimeRange>, otherwise the child actor is not restarted (this avoids restart loops)
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false, maxNrOfRetries = 100, withinTimeRange = 1 minute) {
    case t =>
      // log this as silent errors are dangerous
      log.error(t, s"supervisor caught error for child=$childName strategy=$strategy ")
      strategy
  }
}

object SimpleSupervisor {

  def props(childProps: Props, childName: String, strategy: SupervisorStrategy.Directive) = Props(new SimpleSupervisor(childProps, childName, strategy))

}
