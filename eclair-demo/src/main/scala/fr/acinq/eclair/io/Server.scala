package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.io.{IO, Tcp}
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.Boot
import fr.acinq.eclair.channel.Register.CreateChannel

/**
 * Created by PM on 27/10/2015.
 */
class Server(address: InetSocketAddress) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, address)

  def receive = {
    case b@Bound(localAddress) =>
      log.info(s"bound on $b")

    case CommandFailed(_: Bind) => context stop self

    case c@Connected(remote, local) =>
      log.info(s"connected to $remote")
      val connection = sender()
      Boot.register ! CreateChannel(connection, None)
  }
}

object Server extends App {
  implicit val system = ActorSystem("system")
  val config = ConfigFactory.load()
  val server = system.actorOf(Server.props(config.getString("eclair.server.address"), config.getInt("eclair.server.port")), "server")

  def props(address: InetSocketAddress): Props = Props(classOf[Server], address)
  def props(address: String, port: Int): Props = props(new InetSocketAddress(address, port))
}

