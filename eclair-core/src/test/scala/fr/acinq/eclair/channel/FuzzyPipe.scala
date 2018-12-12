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

import akka.actor.{Actor, ActorLogging, ActorRef, Stash}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.channel.Commitments.msg2String
import fr.acinq.eclair.wire.{Init, LightningMessage}

import scala.concurrent.duration._
import scala.util.Random

/**
  * A Fuzzy [[fr.acinq.eclair.Pipe]] which randomly disconnects/reconnects peers.
  */
class FuzzyPipe(fuzzy: Boolean) extends Actor with Stash with ActorLogging {

  import scala.concurrent.ExecutionContext.Implicits.global

  def receive = {
    case (a: ActorRef, b: ActorRef) =>
      unstashAll()
      context become connected(a, b, 10)

    case _ => stash()
  }

  def stayOrDisconnect(a: ActorRef, b: ActorRef, countdown: Int) = {
    if (!fuzzy) context become connected(a, b, countdown - 1) // fuzzy mode disabled, we never disconnect
    else if (countdown > 1) context become connected(a, b, countdown - 1)
    else {
      log.debug("DISCONNECTED")
      a ! INPUT_DISCONNECTED
      b ! INPUT_DISCONNECTED
      context.system.scheduler.scheduleOnce(100 millis, self, 'reconnect)
      context become disconnected(a, b)
    }
  }

  def connected(a: ActorRef, b: ActorRef, countdown: Int): Receive = {
    case msg: LightningMessage if sender() == a =>
      log.debug(f"A ---${msg2String(msg)}%-6s--> B")
      b forward msg
      stayOrDisconnect(a, b, countdown)
    case msg: LightningMessage if sender() == b =>
      log.debug(f"A <--${msg2String(msg)}%-6s--- B")
      a forward msg
      stayOrDisconnect(a, b, countdown)
  }

  def disconnected(a: ActorRef, b: ActorRef): Receive = {
    case msg: LightningMessage if sender() == a =>
      // dropped
      log.info(f"A ---${msg2String(msg)}%-6s-X")
    case msg: LightningMessage if sender() == b =>
      // dropped
      log.debug(f"  X-${msg2String(msg)}%-6s--- B")
    case 'reconnect =>
      log.debug("RECONNECTED")
      val dummyInit = Init(BinaryData.empty, BinaryData.empty)
      a ! INPUT_RECONNECTED(self, dummyInit, dummyInit)
      b ! INPUT_RECONNECTED(self, dummyInit, dummyInit)
      context become connected(a, b, Random.nextInt(40))
  }
}
