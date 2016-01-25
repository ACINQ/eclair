package fr.acinq.protos

import java.net.InetSocketAddress

import akka.actor.{ActorSystem, Props, ActorLogging, Actor}
import akka.io.{IO, Tcp}
import fr.acinq.eclair.io.AuthHandler

/**
 * Created by PM on 27/10/2015.
 */
class Server extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress("localhost", 57776))

  def receive = {
    case b @ Bound(localAddress) =>
    // do some logging or setup ...

    case CommandFailed(_: Bind) => context stop self

    case c @ Connected(remote, local) =>
      log.info(s"connected to $remote")
      val connection = sender()
      val handler = context.actorOf(Props(classOf[AuthHandler], connection))
      connection ! Register(handler)
      handler ! 'init
  }

}

object Server extends App {
  implicit val system = ActorSystem("system")
  val server = system.actorOf(Props[Server], "server")
}

