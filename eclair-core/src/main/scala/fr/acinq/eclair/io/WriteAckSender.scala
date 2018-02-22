package fr.acinq.eclair.io

import akka.actor.{Actor, ActorLogging, ActorRef, PoisonPill, Terminated}
import akka.io.Tcp
import akka.util.ByteString

import scala.collection.immutable.Queue


/**
  * This implements an ACK-based throttling mechanism [1] with basic priority management
  *
  * [1] https://doc.akka.io/docs/akka/snapshot/scala/io-tcp.html#throttling-reads-and-writes
  */
class WriteAckSender(connection: ActorRef) extends Actor with ActorLogging {

  import WriteAckSender._

  // this actor will kill itself if connection dies
  context watch connection

  case object Ack extends Tcp.Event

  override def receive = idle

  def idle: Receive = {
    case Send(data, _) =>
      connection ! Tcp.Write(data, Ack)
      context become buffering(Queue.empty, Queue.empty)

  }

  def buffering(bufferLow: Queue[ByteString], bufferNormal: Queue[ByteString]): Receive = {
    case _: Send if (bufferLow.size + bufferNormal.size) > MAX_BUFFERED =>
      log.warning(s"buffer overrun, closing connection")
      connection ! PoisonPill

    case Send(data, priority) =>
      log.debug("buffering write priority={} data={}", priority, data)
      val (bufferLow1, bufferNormal1) = priority match {
        case LowPriority => (bufferLow :+ data, bufferNormal)
        case NormalPriority => (bufferLow, bufferNormal :+ data)
      }
      context become buffering(bufferLow1, bufferNormal1)

    case Ack =>
      bufferNormal.dequeueOption match {
        case Some((data, bufferNormal1)) =>
          connection ! Tcp.Write(data, Ack)
          context become buffering(bufferLow, bufferNormal1)
        case None =>
          bufferLow.dequeueOption match {
            case Some((data, bufferLow1)) =>
              connection ! Tcp.Write(data, Ack)
              context become buffering(bufferLow1, bufferNormal)
            case None =>
              log.debug(s"got last ack, back to idle")
              context become idle
          }
      }
  }

  override def unhandled(message: Any): Unit = message match {
    case _: Tcp.ConnectionClosed => context stop self
    case _: Terminated => context stop self
    case _ => log.warning(s"unhandled message $message")
  }

}

object WriteAckSender {

  val MAX_BUFFERED = 100000L

  // @formatter:off
  sealed trait Priority
  case object LowPriority extends Priority
  case object NormalPriority extends Priority
  // @formatter:on

  case class Send(data: ByteString, priority: Priority = NormalPriority)

}
