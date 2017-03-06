package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.Tcp.SO.KeepAlive
import akka.io.{IO, Tcp}
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted
import fr.acinq.eclair.wire.LightningMessage
import fr.acinq.eclair.{NodeParams, TCPBindError}

/**
  * Created by PM on 27/10/2015.
  */
class Server(nodeParams: NodeParams, switchboard: ActorRef, address: InetSocketAddress) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, address, options = KeepAlive(true) :: Nil)

  def receive() = main(Set())

  def main(transports: Set[ActorRef]): Receive = {
    case Bound(localAddress) =>
      log.info(s"bound on $localAddress")

    case CommandFailed(_: Bind) =>
      system.eventStream.publish(TCPBindError)
      context stop self

    case Connected(remote, _) =>
      log.info(s"connected to $remote")
      val connection = sender
      val transport = context.actorOf(Props(
        new TransportHandler[LightningMessage](
          KeyPair(nodeParams.privateKey.publicKey.toBin, nodeParams.privateKey.toBin),
          None,
          connection = connection,
          serializer = LightningMessageSerializer)))
      connection ! akka.io.Tcp.Register(transport)
      context become main(transports + transport)

    case h: HandshakeCompleted =>
      switchboard ! h
  }
}

object Server {

  def props(nodeParams: NodeParams, switchboard: ActorRef, address: InetSocketAddress): Props = Props(new Server(nodeParams, switchboard, address))

}

