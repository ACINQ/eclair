package fr.acinq.lightning

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{IO, Tcp}

/**
 * Created by PM on 27/10/2015.
 */
class Client(remote: InetSocketAddress) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Connect(remote)

  def receive = {
    case CommandFailed(_: Connect) =>
      context stop self

    case c@Connected(remote, local) =>
      log.info(s"connected to $remote")
      val connection = sender()
      val handler = context.actorOf(Props(classOf[AuthHandler], connection))
      connection ! Register(handler)
      handler ! 'init
  }
}

object Client extends App {
  implicit val system = ActorSystem("system")
  val client = system.actorOf(Props(classOf[Client], new InetSocketAddress("localhost", 56912)), "server")
}