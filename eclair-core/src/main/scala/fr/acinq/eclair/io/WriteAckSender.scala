package fr.acinq.eclair.io

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill}
import akka.io.Tcp
import akka.util.ByteString


/**
  * This implements an ACK-based throttling mechanism
  * See https://doc.akka.io/docs/akka/snapshot/scala/io-tcp.html#throttling-reads-and-writes
  */
class WriteAckSender(connection: ActorRef) extends Actor with ActorLogging {

  // Note: this actor should be killed if connection dies

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
      log.debug(s"buffering write $data")
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

  override def unhandled(message: Any): Unit = log.warning(s"unhandled message $message")

  val MAX_BUFFERED = 100000L

}
