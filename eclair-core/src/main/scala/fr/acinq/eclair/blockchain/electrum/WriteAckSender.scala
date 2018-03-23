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

package fr.acinq.eclair.blockchain.electrum

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Terminated}
import akka.io.Tcp
import akka.util.ByteString


/**
  * Simple ACK-based throttling mechanism for sending messages to a TCP connection
  * See https://doc.akka.io/docs/akka/snapshot/scala/io-tcp.html#throttling-reads-and-writes
  */
class WriteAckSender(connection: ActorRef) extends Actor with ActorLogging {

  // this actor will kill itself if connection dies
  context watch connection

  case object Ack extends Tcp.Event

  override def receive = idle

  def idle: Receive = {
    case data: ByteString =>
      connection ! Tcp.Write(data, Ack)
      context become buffering(Vector.empty[ByteString])
  }

  def buffering(buffer: Vector[ByteString]): Receive = {
    case _: ByteString if buffer.size > MAX_BUFFERED =>
      log.warning(s"buffer overrun, closing connection")
      connection ! PoisonPill
    case data: ByteString =>
      log.debug("buffering write {}", data)
      context become buffering(buffer :+ data)
    case Ack =>
      buffer.headOption match {
        case Some(data) =>
          connection ! Tcp.Write(data, Ack)
          context become buffering(buffer.drop(1))
        case None =>
          log.debug(s"got last ack, back to idle")
          context become idle
      }
  }

  override def unhandled(message: Any): Unit = message match {
    case _: Tcp.ConnectionClosed => context stop self
    case Terminated(_) => context stop self
    case _ => log.warning(s"unhandled message $message")
  }

  val MAX_BUFFERED = 100000L

}
