package fr.acinq.eclair.tor

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import scala.concurrent.ExecutionContext

class Controller(address: InetSocketAddress, listener: ActorRef)
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
      context stop listener
      context stop self
    case c@Connected(remote, local) =>
      listener ! c
      val connection = sender()
      connection ! Register(self)
      context become {
        case data: ByteString =>
          connection ! Write(data)
        case CommandFailed(w: Write) =>
          // O/S buffer was full
          listener ! SendFailed
          log.error("Tor command failed")
        case Received(data) =>
          listener ! data
        case _: ConnectionClosed =>
          context stop listener
          context stop self
      }
  }

}

object Controller {
  def props(address: InetSocketAddress, protocolHandler: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.global) =
    Props(new Controller(address, protocolHandler))

  case object SendFailed
}