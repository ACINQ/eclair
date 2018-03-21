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

package fr.acinq.eclair

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import fr.acinq.eclair.channel.Commitments.msg2String
import fr.acinq.eclair.wire.LightningMessage

/**
  * Handles a bi-directional path between 2 actors
  * used to avoid the chicken-and-egg problem of:
  * a = new Channel(b)
  * b = new Channel(a)
  */
class Pipe extends Actor with Stash with ActorLogging {

  def receive = {
    case (a: ActorRef, b: ActorRef) =>
      unstashAll()
      context become connected(a, b)

    case _ => stash()
  }

  def connected(a: ActorRef, b: ActorRef): Receive = {
    case msg: LightningMessage if sender() == a =>
      log.debug(f"A ---${msg2String(msg)}%-6s--> B")
      b forward msg
    case msg: LightningMessage if sender() == b =>
      log.debug(f"A <--${msg2String(msg)}%-6s--- B")
      a forward msg
  }
}
