package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Props, _}
import akka.io.{IO, Tcp}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.Globals
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted
import fr.acinq.eclair.wire.LightningMessage

/**
  * Created by PM on 27/10/2015.
  */
class Client(switchboard: ActorRef, address: InetSocketAddress, remoteNodeId: PublicKey, origin: ActorRef) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Connect(address)

  def receive = {
    case CommandFailed(_: Connect) =>
      origin ! Status.Failure(new RuntimeException("connection failed"))
      context stop self

    case Connected(remote, _) =>
      log.info(s"connected to $remote")
      val connection = sender
      val transport = context.actorOf(Props(
        new TransportHandler[LightningMessage](
          KeyPair(Globals.Node.publicKey.toBin, Globals.Node.privateKey.toBin),
          Some(remoteNodeId),
          connection = connection,
          serializer = LightningMessageSerializer)))
      connection ! akka.io.Tcp.Register(transport)
      context watch transport
      context become connected(transport)
  }

  def connected(transport: ActorRef): Receive = {
    case Terminated(actor) if actor == transport =>
      origin ! Status.Failure(new RuntimeException("authentication failed"))
      context stop self

    case h: HandshakeCompleted =>
      origin ! "connected"
      switchboard ! h
  }
}

object Client extends App {

  def props(switchboard: ActorRef, address: InetSocketAddress, remoteNodeId: PublicKey, origin: ActorRef): Props = Props(classOf[Client], switchboard, address, remoteNodeId, origin)

}
