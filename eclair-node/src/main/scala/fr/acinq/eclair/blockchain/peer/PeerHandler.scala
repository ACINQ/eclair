package fr.acinq.eclair.blockchain.peer

import java.net.InetSocketAddress
import java.nio.ByteOrder

import akka.actor._
import akka.io.{IO, Tcp}
import akka.util.ByteString
import fr.acinq.bitcoin._

import scala.util.{Failure, Success, Try}

/**
  * handles communication with a remote BTC node
  *
  * @param remote   address of the remote node
  * @param listener listener actor BTC messages sent by the remote node will be forwarded to
  */
class PeerHandler(remote: InetSocketAddress, listener: ActorRef) extends Actor with ActorLogging {

  import PeerHandler._
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
      context become connected(connection, Nil)
  }

  def connected(connection: ActorRef, buffer: Seq[Byte]): Receive = {
    case message: fr.acinq.bitcoin.Message =>
      log.debug(s"sending $message")
      connection ! Write(ByteString(fr.acinq.bitcoin.Message.write(message).toArray))

    case CommandFailed(w: Write) =>
      // O/S buffer was full
      log.error("write failed")

    case Received(data) =>
      log.debug(s"received $data")
      // peer sent some data
      // extract bitcoin messages and process them
      val (messages, remainder) = extract(buffer ++ data)
      messages.map(message => listener ! message)
      context become connected(connection, remainder)
      
    case 'close =>
      connection ! Close

    case _: ConnectionClosed =>
      log.info(s"connection to $remote closed")
      context stop self
  }
}

object PeerHandler {
  /**
    * extract bitcoin messages from raw data
    *
    * @param data raw data
    * @param acc  messages that we have so far
    * @return a (messages, remainder) tuple where messages are all the messages that could be extracted and
    *         remainder is what is left of the input data (i.e could not be parsed because the message it contains
    *         has not been fully received yet
    */
  def extract(data: Seq[Byte], acc: Vector[Message] = Vector.empty[Message]): (Vector[Message], Seq[Byte]) = {
    if (data.length < 24) {
      (acc, data)
    } else {
      val magic = Protocol.uint32(data, ByteOrder.LITTLE_ENDIAN)
      val data1 = data.drop(4)
      val command = extractCommand(data1)
      val data2 = data1.drop(12)
      val length = Protocol.uint32(data2, ByteOrder.LITTLE_ENDIAN)
      val data3 = data2.drop(4)
      val checksum = Protocol.uint32(data3, ByteOrder.LITTLE_ENDIAN)
      val data4 = data3.drop(4)
      if (data4.length >= length) {
        val payload = data4.take(length.toInt)
        require(checksum == computeChecksum(payload), "invalid checksum")
        extract(data4.drop(length.toInt), acc :+ Message(magic, command, BinaryData(payload)))
      } else (acc, data)
    }
  }

  def extractCommand(data: Seq[Byte]): String = new String(data.take(12).takeWhile(_ != 0).toArray, "ISO-8859-1")

  def computeChecksum(payload: Seq[Byte]): Long = Protocol.uint32(Crypto.hash256(payload).take(4), ByteOrder.LITTLE_ENDIAN)

  def verifyChecksum(checksum: Long, payload: Seq[Byte]): Boolean = checksum == computeChecksum(payload)
}


