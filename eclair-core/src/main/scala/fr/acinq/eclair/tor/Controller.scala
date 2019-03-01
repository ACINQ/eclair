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

package fr.acinq.eclair.tor

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, OneForOneStrategy, Props, SupervisorStrategy, Terminated}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import scala.concurrent.{ExecutionContext, Promise}

/**
  * Created by rorp
  *
  * @param address              Tor control address
  * @param protocolHandlerProps Tor protocol handler props
  * @param ec                   execution context
  */
class Controller(address: InetSocketAddress, protocolHandlerProps: Props)
                (implicit ec: ExecutionContext = ExecutionContext.global) extends Actor with ActorLogging {

  import Controller._
  import Tcp._
  import context.system

  IO(Tcp) ! Connect(address)

  def receive = {
    case e@CommandFailed(_: Connect) =>
      e.cause match {
        case Some(ex) => log.error(ex, "Cannot connect")
        case _ => log.error("Cannot connect")
      }
      context stop self
    case c: Connected =>
      val protocolHandler = context actorOf protocolHandlerProps
      protocolHandler ! c
      val connection = sender()
      connection ! Register(self)
      context watch connection
      context become {
        case data: ByteString =>
          connection ! Write(data)
        case CommandFailed(w: Write) =>
          // O/S buffer was full
          protocolHandler ! SendFailed
          log.error("Tor command failed")
        case Received(data) =>
          protocolHandler ! data
        case _: ConnectionClosed =>
          context stop self
        case Terminated(actor) if actor == connection =>
          context stop self
      }
  }

  // we should not restart a failing tor session
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Escalate }

}

object Controller {
  def props(address: InetSocketAddress, protocolHandlerProps: Props)(implicit ec: ExecutionContext = ExecutionContext.global) =
    Props(new Controller(address, protocolHandlerProps))

  case object SendFailed

}