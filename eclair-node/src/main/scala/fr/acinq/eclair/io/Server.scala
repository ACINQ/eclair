package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.{IO, Tcp}
import fr.acinq.eclair.TCPBindError
import fr.acinq.eclair.channel.Register.CreateChannel

/**
  * Created by PM on 27/10/2015.
  */
class Server(address: InetSocketAddress, register: ActorRef) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, address)

  def receive = {
    case b@Bound(localAddress) =>
      log.info(s"bound on $b")

    case CommandFailed(_: Bind) =>
      system.eventStream.publish(TCPBindError)
      context stop self

    case c@Connected(remote, local) =>
      log.info(s"connected to $remote")
      val connection = sender()
      register ! CreateChannel(connection, None, None, None)
  }
}

object Server extends App {

  def props(address: InetSocketAddress, register: ActorRef): Props = Props(classOf[Server], address, register)

  def props(host: String, port: Int, register: ActorRef): Props = Props(classOf[Server], new InetSocketAddress(host, port), register)

}

