package fr.acinq.eclair.io

import java.net.InetSocketAddress

import akka.actor.{Props, _}
import akka.io.Tcp.SO.KeepAlive
import akka.io.{IO, Tcp}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.NodeParams
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.crypto.TransportHandler
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted
import fr.acinq.eclair.wire.{LightningMessage, LightningMessageCodecs}

/**
  * Created by PM on 27/10/2015.
  */
class Client(nodeParams: NodeParams, switchboard: ActorRef, address: InetSocketAddress, remoteNodeId: PublicKey, origin: ActorRef) extends Actor with ActorLogging {

  import Tcp._
  import context.system

  IO(Tcp) ! Connect(address, options = KeepAlive(true) :: Nil)

  def receive = {
    case CommandFailed(_: Connect) =>
      origin ! Status.Failure(new RuntimeException("connection failed"))
      context stop self

    case Connected(remote, _) =>
      log.info(s"connected to $remote")
      val connection = sender
      val transport = context.actorOf(Props(
        new TransportHandler[LightningMessage](
          KeyPair(nodeParams.privateKey.publicKey.toBin, nodeParams.privateKey.toBin),
          Some(remoteNodeId),
          connection = connection,
          codec = LightningMessageCodecs.lightningMessageCodec)))
      context watch transport
      context become authenticating(transport)
  }

  def authenticating(transport: ActorRef): Receive = {
    case Terminated(actor) if actor == transport =>
      origin ! Status.Failure(new RuntimeException("authentication failed"))
      context stop self

    case h: HandshakeCompleted =>
      log.info(s"handshake completed with ${h.remoteNodeId}")
      origin ! "connected"
      switchboard ! h
      context become connected(transport)
  }

  def connected(transport: ActorRef): Receive = {
    case Terminated(actor) if actor == transport =>
      context stop self

    case msg => log.warning(s"unexpected message $msg")
  }

  // we should not restart a failing transport
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = true) { case _ => SupervisorStrategy.Stop }
}

object Client extends App {

  def props(nodeParams: NodeParams, switchboard: ActorRef, address: InetSocketAddress, remoteNodeId: PublicKey, origin: ActorRef): Props = Props(new Client(nodeParams, switchboard, address, remoteNodeId, origin))

}
