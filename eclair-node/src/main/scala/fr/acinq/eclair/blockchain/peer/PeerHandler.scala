package fr.acinq.eclair.blockchain.peer

import java.net.InetSocketAddress

import akka.actor._
import akka.io.{IO, Tcp}
import akka.util.ByteString
import fr.acinq.bitcoin._

import scala.util.{Failure, Success, Try}

/**
 * handles communication with a remote BTC node
 * @param remote address of the remote node
 * @param listener listener actor BTC messages sent by the remote node will be forwarded to
 */
class PeerHandler(remote: InetSocketAddress, listener: ActorRef) extends Actor with ActorLogging {
  import akka.io.Tcp._
  implicit val system = context.system

  context.watch(listener)

  IO(Tcp) ! Connect(remote)

  override def unhandled(message: Any): Unit = message match {
    case Terminated(actor) if listener == actor => {
      context.unwatch(listener)
      context.stop(self)
    }
    case _ => {
      log.warning(s"unhandled message $message")
      super.unhandled(message)
    }
  }

  def receive = {
    case CommandFailed(_: Connect) =>
      log.error(s"connection to $remote failed")
      context stop self

    case c@Connected(remote, local) =>
      log.info(s"connected to $remote")
      val connection = sender()
      listener ! c
      connection ! Register(self)
      context become connected(connection)
  }

  def connected(connection: ActorRef): Receive = {
    case message: fr.acinq.bitcoin.Message =>
      log.debug(s"sending $message")
      connection ! Write(ByteString(fr.acinq.bitcoin.Message.write(message).toArray))
    case CommandFailed(w: Write) =>
      // O/S buffer was full
      log.error("write failed")
    case Received(data) =>
      log.debug(s"received $data")
      Try(fr.acinq.bitcoin.Message.read(data.toArray)) match {
        case Success(message) => listener ! message
        case Failure(cause) => log.error(cause, s"cannot parse ${toHexString(data.toArray)}")
      }
    case 'close =>
      connection ! Close
    case _: ConnectionClosed =>
      log.info(s"connection to $remote closed")
      context stop self
  }
}


