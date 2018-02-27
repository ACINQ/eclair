package fr.acinq.eclair.crypto

import java.nio.ByteOrder

import akka.actor.{Actor, ActorRef, FSM, PoisonPill, Props, Terminated}
import akka.io.Tcp
import akka.util.ByteString
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.crypto.Noise._
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate, NodeAnnouncement}
import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult}

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.reflect.ClassTag

/**
  * see BOLT #8
  * This class handles the transport layer:
  * - initial handshake. upon completion we will have  a pair of cipher states (one for encryption, one for decryption)
  * - encryption/decryption of messages
  *
  * Once the initial handshake has been completed successfully, the handler will create a listener actor with the
  * provided factory, and will forward it all decrypted messages
  *
  * @param keyPair    private/public key pair for this node
  * @param rs         remote node static public key (which must be known before we initiate communication)
  * @param connection actor that represents the other node's
  */
class TransportHandler[T: ClassTag](keyPair: KeyPair, rs: Option[BinaryData], connection: ActorRef, codec: Codec[T]) extends Actor with FSM[TransportHandler.State, TransportHandler.Data] {

  import TransportHandler._

  connection ! Tcp.Register(self)
  connection ! Tcp.ResumeReading

  def buf(message: BinaryData): ByteString = ByteString.fromArray(message)

  // it means we initiate the dialog
  val isWriter = rs.isDefined

  context.watch(connection)

  val reader = if (isWriter) {
    val state = makeWriter(keyPair, rs.get)
    val (state1, message, None) = state.write(BinaryData.empty)
    log.debug(s"sending prefix + $message")
    connection ! Tcp.Write(buf(TransportHandler.prefix +: message))
    state1
  } else {
    makeReader(keyPair)
  }

  def sendToListener(listener: ActorRef, plaintextMessages: Seq[BinaryData]): Map[T, Int] = {
    var m: Map[T, Int] = Map()
    plaintextMessages.foreach(plaintext => codec.decode(BitVector(plaintext.data)) match {
      case Attempt.Successful(DecodeResult(message, _)) =>
        listener ! message
        m += (message -> (m.getOrElse(message, 0) + 1))
      case Attempt.Failure(err) =>
        log.error(s"cannot deserialize $plaintext: $err")
    })
    m
  }

  startWith(Handshake, HandshakeData(reader))

  when(Handshake) {
    case Event(Tcp.Received(data), HandshakeData(reader, buffer)) =>
      connection ! Tcp.ResumeReading
      log.debug("received {}", BinaryData(data))
      val buffer1 = buffer ++ data
      if (buffer1.length < expectedLength(reader))
        stay using HandshakeData(reader, buffer1)
      else {
        require(buffer1.head == TransportHandler.prefix, s"invalid transport prefix first64=${BinaryData(buffer1.take(64))}")
        val (payload, remainder) = buffer1.tail.splitAt(expectedLength(reader) - 1)

        reader.read(payload) match {
          case (writer, _, Some((dec, enc, ck))) =>
            val remoteNodeId = PublicKey(writer.rs)
            context.parent ! HandshakeCompleted(connection, self, remoteNodeId)
            val nextStateData = WaitingForListenerData(Encryptor(ExtendedCipherState(enc, ck)), Decryptor(ExtendedCipherState(dec, ck), ciphertextLength = None, remainder))
            goto(WaitingForListener) using nextStateData

          case (writer, _, None) => {
            writer.write(BinaryData.empty) match {
              case (reader1, message, None) => {
                // we're still in the middle of the handshake process and the other end must first received our next
                // message before they can reply
                require(remainder.isEmpty, "unexpected additional data received during handshake")
                connection ! Tcp.Write(buf(TransportHandler.prefix +: message))
                stay using HandshakeData(reader1, remainder)
              }
              case (_, message, Some((enc, dec, ck))) => {
                connection ! Tcp.Write(buf(TransportHandler.prefix +: message))
                val remoteNodeId = PublicKey(writer.rs)
                context.parent ! HandshakeCompleted(connection, self, remoteNodeId)
                val nextStateData = WaitingForListenerData(Encryptor(ExtendedCipherState(enc, ck)), Decryptor(ExtendedCipherState(dec, ck), ciphertextLength = None, remainder))
                goto(WaitingForListener) using nextStateData
              }
            }
          }
        }
      }
  }

  when(WaitingForListener) {
    case Event(Tcp.Received(data), d@WaitingForListenerData(_, dec)) =>
      stay using d.copy(decryptor = dec.copy(buffer = dec.buffer ++ data))

    case Event(Listener(listener), d@WaitingForListenerData(_, dec)) =>
      context.watch(listener)
      val (dec1, plaintextMessages) = dec.decrypt()
      if (plaintextMessages.isEmpty) {
        connection ! Tcp.ResumeReading
        goto(Normal) using NormalData(d.encryptor, dec1, listener, sendBuffer = SendBuffer(Queue.empty, Queue.empty), unackedReceived = Map.empty[T, Int], unackedSent = None)
      } else {
        log.debug(s"read ${plaintextMessages.size} messages, waiting for readacks")
        val unackedReceived = sendToListener(listener, plaintextMessages)
        goto(Normal) using NormalData(d.encryptor, dec1, listener, sendBuffer = SendBuffer(Queue.empty, Queue.empty), unackedReceived, unackedSent = None)
      }
  }

  when(Normal) {
    case Event(Tcp.Received(data), d: NormalData[T]) =>
      val (dec1, plaintextMessages) = d.decryptor.copy(buffer = d.decryptor.buffer ++ data).decrypt()
      if (plaintextMessages.isEmpty) {
        connection ! Tcp.ResumeReading
        stay using d.copy(decryptor = dec1)
      } else {
        log.debug(s"read {} messages, waiting for readacks", plaintextMessages.size)
        val unackedReceived = sendToListener(d.listener, plaintextMessages)
        stay using NormalData(d.encryptor, dec1, d.listener, d.sendBuffer, unackedReceived, d.unackedSent)
      }

    case Event(ReadAck(msg: T), d: NormalData[T]) =>
      // how many occurences of this message are still unacked?
      val remaining = d.unackedReceived.getOrElse(msg, 0) - 1
      // if all occurences have been acked then we remove the entry from the map
      val unackedReceived1 = if (remaining > 0) d.unackedReceived + (msg -> remaining) else d.unackedReceived - msg
      if (unackedReceived1.isEmpty) {
        log.debug("last incoming message was acked, resuming reading")
        connection ! Tcp.ResumeReading
        stay using d.copy(unackedReceived = unackedReceived1)
      } else {
        stay using d.copy(unackedReceived = unackedReceived1)
      }

    case Event(t: T, d: NormalData[T]) =>
      if (d.sendBuffer.normalPriority.size + d.sendBuffer.lowPriority.size >= MAX_BUFFERED) {
        log.warning(s"send buffer overrun, closing connection")
        connection ! PoisonPill
        stop(FSM.Normal)
      } else if (d.unackedSent.isDefined) {
        log.debug("buffering send data={}", t)
        val sendBuffer1 = t match {
          case _: ChannelAnnouncement => d.sendBuffer.copy(lowPriority = d.sendBuffer.lowPriority :+ t)
          case _: NodeAnnouncement => d.sendBuffer.copy(lowPriority = d.sendBuffer.lowPriority :+ t)
          case _: ChannelUpdate => d.sendBuffer.copy(lowPriority = d.sendBuffer.lowPriority :+ t)
          case _ => d.sendBuffer.copy(normalPriority = d.sendBuffer.normalPriority :+ t)
        }
        stay using d.copy(sendBuffer = sendBuffer1)
      } else {
        val blob = codec.encode(t).require.toByteArray
        val (enc1, ciphertext) = d.encryptor.encrypt(blob)
        connection ! Tcp.Write(buf(ciphertext), WriteAck)
        stay using d.copy(encryptor = enc1, unackedSent = Some(t))
      }

    case Event(WriteAck, d: NormalData[T]) =>
      def send(t: T) = {
        val blob = codec.encode(t).require.toByteArray
        val (enc1, ciphertext) = d.encryptor.encrypt(blob)
        connection ! Tcp.Write(buf(ciphertext), WriteAck)
        enc1
      }
      d.sendBuffer.normalPriority.dequeueOption match {
        case Some((t, normalPriority1)) =>
          val enc1 = send(t)
          stay using d.copy(encryptor = enc1, sendBuffer = d.sendBuffer.copy(normalPriority = normalPriority1), unackedSent = Some(t))
        case None =>
          d.sendBuffer.lowPriority.dequeueOption match {
            case Some((t, lowPriority1)) =>
              val enc1 = send(t)
              stay using d.copy(encryptor = enc1, sendBuffer = d.sendBuffer.copy(lowPriority = lowPriority1), unackedSent = Some(t))
            case None =>
              stay using d.copy(unackedSent = None)
          }
      }
  }

  whenUnhandled {
    case Event(closed: Tcp.ConnectionClosed, _) =>
      log.info(s"connection closed: $closed")
      stop(FSM.Normal)

    case Event(Terminated(actor), _) if actor == connection =>
      log.info(s"connection terminated, stopping the transport")
      // this can be the connection or the listener, either way it is a cause of death
      stop(FSM.Normal)
  }

  override def aroundPostStop(): Unit = connection ! Tcp.Close // attempts to gracefully close the connection when dying

  initialize()

}

object TransportHandler {

  def props[T: ClassTag](keyPair: KeyPair, rs: Option[BinaryData], connection: ActorRef, codec: Codec[T]): Props = Props(new TransportHandler(keyPair, rs, connection, codec))

  val MAX_BUFFERED = 100000L

  // see BOLT #8
  // this prefix is prepended to all Noise messages sent during the handshake phase
  val prefix: Byte = 0x00

  val prologue = "lightning".getBytes("UTF-8")

  /**
    * See BOLT #8: during the handshake phase we are expecting 3 messages of 50, 50 and 66 bytes (including the prefix)
    *
    * @param reader handshake state reader
    * @return the size of the message the reader is expecting
    */
  def expectedLength(reader: Noise.HandshakeStateReader) = reader.messages.length match {
    case 3 | 2 => 50
    case 1 => 66
  }

  def makeWriter(localStatic: KeyPair, remoteStatic: BinaryData) = Noise.HandshakeState.initializeWriter(
    Noise.handshakePatternXK, prologue,
    localStatic, KeyPair(BinaryData.empty, BinaryData.empty), remoteStatic, BinaryData.empty,
    Noise.Secp256k1DHFunctions, Noise.Chacha20Poly1305CipherFunctions, Noise.SHA256HashFunctions)

  def makeReader(localStatic: KeyPair) = Noise.HandshakeState.initializeReader(
    Noise.handshakePatternXK, prologue,
    localStatic, KeyPair(BinaryData.empty, BinaryData.empty), BinaryData.empty, BinaryData.empty,
    Noise.Secp256k1DHFunctions, Noise.Chacha20Poly1305CipherFunctions, Noise.SHA256HashFunctions)

  /**
    * extended cipher state which implements key rotation as per BOLT #8
    *
    * @param cs cipher state
    * @param ck chaining key
    */
  case class ExtendedCipherState(cs: CipherState, ck: BinaryData) extends CipherState {
    override def cipher: CipherFunctions = cs.cipher

    override def hasKey: Boolean = cs.hasKey

    override def encryptWithAd(ad: BinaryData, plaintext: BinaryData): (CipherState, BinaryData) = {
      cs match {
        case UninitializedCipherState(_) => (this, plaintext)
        case InitializedCipherState(k, n, _) if n == 999 => {
          val (_, ciphertext) = cs.encryptWithAd(ad, plaintext)
          val (ck1, k1) = SHA256HashFunctions.hkdf(ck, k)
          (this.copy(cs = cs.initializeKey(k1), ck = ck1), ciphertext)
        }
        case InitializedCipherState(_, n, _) => {
          val (cs1, ciphertext) = cs.encryptWithAd(ad, plaintext)
          (this.copy(cs = cs1), ciphertext)
        }
      }
    }

    override def decryptWithAd(ad: BinaryData, ciphertext: BinaryData): (CipherState, BinaryData) = {
      cs match {
        case UninitializedCipherState(_) => (this, ciphertext)
        case InitializedCipherState(k, n, _) if n == 999 => {
          val (_, plaintext) = cs.decryptWithAd(ad, ciphertext)
          val (ck1, k1) = SHA256HashFunctions.hkdf(ck, k)
          (this.copy(cs = cs.initializeKey(k1), ck = ck1), plaintext)
        }
        case InitializedCipherState(_, n, _) => {
          val (cs1, plaintext) = cs.decryptWithAd(ad, ciphertext)
          (this.copy(cs = cs1), plaintext)
        }
      }
    }
  }

  case class Decryptor(state: CipherState, ciphertextLength: Option[Int], buffer: ByteString) {
    @tailrec
    final def decrypt(acc: Seq[BinaryData] = Vector()): (Decryptor, Seq[BinaryData]) = {
      (ciphertextLength, buffer.length) match {
        case (None, length) if length < 18 => (this, acc)
        case (None, _) =>
          val (ciphertext, remainder) = buffer.splitAt(18)
          val (dec1, plaintext) = state.decryptWithAd(BinaryData.empty, ciphertext)
          val length = Protocol.uint16(plaintext, ByteOrder.BIG_ENDIAN)
          Decryptor(dec1, ciphertextLength = Some(length), buffer = remainder).decrypt(acc)
        case (Some(expectedLength), length) if length < expectedLength + 16 => (Decryptor(state, ciphertextLength, buffer), acc)
        case (Some(expectedLength), _) =>
          val (ciphertext, remainder) = buffer.splitAt(expectedLength + 16)
          val (dec1, plaintext) = state.decryptWithAd(BinaryData.empty, ciphertext)
          Decryptor(dec1, ciphertextLength = None, buffer = remainder).decrypt(acc :+ plaintext)
      }
    }
  }

  case class Encryptor(state: CipherState) {
    /**
      * see BOLT #8
      * +-------------------------------
      * |2-byte encrypted message length|
      * +-------------------------------
      * |  16-byte MAC of the encrypted |
      * |        message length         |
      * +-------------------------------
      * |                               |
      * |                               |
      * |     encrypted lightning       |
      * |            message            |
      * |                               |
      * +-------------------------------
      * |     16-byte MAC of the        |
      * |      lightning message        |
      * +-------------------------------
      *
      * @param plaintext plaintext
      * @return a (cipherstate, ciphertext) tuple where ciphertext is encrypted according to BOLT #8
      */
    def encrypt(plaintext: BinaryData): (Encryptor, BinaryData) = {
      val (state1, ciphertext1) = state.encryptWithAd(BinaryData.empty, Protocol.writeUInt16(plaintext.length, ByteOrder.BIG_ENDIAN))
      val (state2, ciphertext2) = state1.encryptWithAd(BinaryData.empty, plaintext)
      (Encryptor(state2), ciphertext1 ++ ciphertext2)
    }
  }

  // @formatter:off
  sealed trait State
  case object Handshake extends State
  case object WaitingForListener extends State
  case object Normal extends State

  sealed trait Data
  case class HandshakeData(reader: Noise.HandshakeStateReader, buffer: ByteString = ByteString.empty) extends Data
  case class WaitingForListenerData(encryptor: Encryptor, decryptor: Decryptor) extends Data
  case class NormalData[T](encryptor: Encryptor, decryptor: Decryptor, listener: ActorRef, sendBuffer: SendBuffer[T], unackedReceived: Map[T, Int], unackedSent: Option[T]) extends Data

  case class SendBuffer[T](normalPriority: Queue[T], lowPriority: Queue[T])

  case class Listener(listener: ActorRef)

  case class HandshakeCompleted(connection: ActorRef, transport: ActorRef, remoteNodeId: PublicKey)

  case class ReadAck(msg: Any)
  case object WriteAck extends Tcp.Event
  // @formatter:on


}