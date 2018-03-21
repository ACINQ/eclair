/*
 * Copyright 2018 ACINQ SAS
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

package fr.acinq.eclair.channel

import akka.actor.{Actor, ActorLogging, ActorRef}

/**
  * Created by PM on 19/03/2016.
  */

/**
  * Purpose of this actor is to be an alias for its origin actor.
  * It allows to reference the using {{{system.actorSelection()}}} with a meaningful name
  *
  * @param origin aliased actor
  */
class AliasActor(origin: ActorRef) extends Actor with ActorLogging {

  log.info(s"forwarding messages from $self to $origin")

  override def receive: Actor.Receive = {
    case m => origin forward m
  }
}
