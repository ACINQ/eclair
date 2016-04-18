package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{IO, Tcp}
import fr.acinq.eclair.{Globals, CreateChannel, Boot}

/**
 * Created by PM on 27/10/2015.
 */
class Client(remote: InetSocketAddress, amount: Long) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Connect(remote)

  def receive = {
    case CommandFailed(_: Connect) => context stop self

    case c@Connected(remote, local) =>
      log.info(s"connected to $remote")
      val connection = sender()
      Boot.register ! CreateChannel(connection, Globals.params_noanchor.copy(anchorAmount = Some(amount)))
      // TODO : kill this actor ?
  }
}

object Client extends App {
  implicit val system = Boot.system
  val client = system.actorOf(Props(classOf[Client], new InetSocketAddress("localhost", 45000), 1000L), "client")
}