package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Props, _}
import akka.io.Tcp.SO.KeepAlive
import akka.io.{IO, Tcp}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.io.Client.ConnectionFailed

import scala.concurrent.duration._

/**
  * Created by PM on 27/10/2015.
  *
  */
class Client(nodeParams: NodeParams, authenticator: ActorRef, address: InetSocketAddress, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  log.info(s"connecting to pubkey=$remoteNodeId host=${address.getHostString} port=${address.getPort}")
  IO(Tcp) ! Connect(address, timeout = Some(5 seconds), options = KeepAlive(true) :: Nil, pullMode = true)

  def receive = {
    case CommandFailed(_: Connect) =>
      log.info(s"connection failed to $remoteNodeId@${address.getHostString}:${address.getPort}")
      origin_opt.map(_ ! Status.Failure(ConnectionFailed(address)))
      context stop self

    case Connected(remote, _) =>
      log.info(s"connected to pubkey=$remoteNodeId host=${remote.getHostString} port=${remote.getPort}")
      val connection = sender
      authenticator ! Authenticator.PendingAuth(connection, remoteNodeId_opt = Some(remoteNodeId), address = address, origin_opt = origin_opt)
      context watch connection
      context become connected(connection)
  }

  def connected(connection: ActorRef): Receive = {
    case Terminated(actor) if actor == connection =>
      context stop self
  }

  override def unhandled(message: Any): Unit = log.warning(s"unhandled message=$message")
}

object Client extends App {

  def props(nodeParams: NodeParams, authenticator: ActorRef, address: InetSocketAddress, remoteNodeId: PublicKey, origin_opt: Option[ActorRef]): Props = Props(new Client(nodeParams, authenticator, address, remoteNodeId, origin_opt))

  case class ConnectionFailed(address: InetSocketAddress) extends RuntimeException(s"connection failed to $address")

}
