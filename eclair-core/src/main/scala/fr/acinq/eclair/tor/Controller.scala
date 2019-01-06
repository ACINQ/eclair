package fr.acinq.eclair.tor

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}
import akka.io.{IO, Tcp}
import akka.util.ByteString

import scala.concurrent.ExecutionContext

/**
  * Created by rorp
  *
  * @param address         Tor control address
  * @param protocolHandler Tor protocol handler
  * @param ec              execution context
  */
class Controller(address: InetSocketAddress, protocolHandler: ActorRef)
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
      context stop protocolHandler
      context stop self
    case c@Connected(remote, local) =>
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
          context stop protocolHandler
          context stop self
        case Terminated(actor) if actor == connection =>
          context stop protocolHandler
          context stop self
      }
  }

}

object Controller {
  def props(address: InetSocketAddress, protocolHandler: ActorRef)(implicit ec: ExecutionContext = ExecutionContext.global) =
    Props(new Controller(address, protocolHandler))

  case object SendFailed

}