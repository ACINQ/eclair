package fr.acinq.eclair.crypto

import java.nio.ByteOrder

import akka.actor.{Actor, ActorRef, FSM, Props, Terminated}
import akka.io.Tcp
import akka.util.ByteString
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.crypto.Noise._
import fr.acinq.eclair.io.WriteAckSender
import scodec.bits.BitVector
import scodec.{Attempt, Codec, DecodeResult}

import scala.annotation.tailrec
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

  connection ! akka.io.Tcp.Register(self)

  val out = context.actorOf(Props(new WriteAckSender(connection)))

  def buf(message: BinaryData): ByteString = ByteString.fromArray(message)

  // it means we initiate the dialog
  val isWriter = rs.isDefined

  context.watch(connection)

  val reader = if (isWriter) {
    val state = makeWriter(keyPair, rs.get)
    val (state1, message, None) = state.write(BinaryData.empty)
    log.debug(s"sending prefix + $message")
    out ! buf(TransportHandler.prefix +: message)
    state1
  } else {
    makeReader(keyPair)
  }

  def sendToListener(listener: ActorRef, plaintextMessages: Seq[BinaryData]) = {
    plaintextMessages.map(plaintext => {
      codec.decode(BitVector(plaintext.data)) match {
        case Attempt.Successful(DecodeResult(message, _)) => listener ! message
        case Attempt.Failure(err) => log.error(s"cannot deserialize $plaintext: $err")
      }
    })
  }

  startWith(Handshake, HandshakeData(reader))

  when(Handshake) {
    case Event(Tcp.Received(data), HandshakeData(reader, buffer)) =>
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
            val nextStateData = WaitingForListenerData(ExtendedCipherState(enc, ck), ExtendedCipherState(dec, ck), remainder)
            goto(WaitingForListener) using nextStateData

          case (writer, _, None) => {
            writer.write(BinaryData.empty) match {
              case (reader1, message, None) => {
                // we're still in the middle of the handshake process and the other end must first received our next
                // message before they can reply
                require(remainder.isEmpty, "unexpected additional data received during handshake")
                out ! buf(TransportHandler.prefix +: message)
                stay using HandshakeData(reader1, remainder)
              }
              case (_, message, Some((enc, dec, ck))) => {
                out ! buf(TransportHandler.prefix +: message)
                val remoteNodeId = PublicKey(writer.rs)
                context.parent ! HandshakeCompleted(connection, self, remoteNodeId)
                val nextStateData = WaitingForListenerData(ExtendedCipherState(enc, ck), ExtendedCipherState(dec, ck), remainder)
                goto(WaitingForListener) using nextStateData
              }
            }
          }
        }
      }
  }

  when(WaitingForListener) {
    case Event(Tcp.Received(data), currentStateData@WaitingForListenerData(enc, dec, buffer)) =>
      stay using currentStateData.copy(buffer = buffer ++ data)

    case Event(Listener(listener), WaitingForListenerData(enc, dec, buffer)) =>
      val (nextStateData, plaintextMessages) = WaitingForCiphertextData(enc, dec, None, buffer, listener).decrypt
      context.watch(listener)
      sendToListener(listener, plaintextMessages)
      goto(WaitingForCiphertext) using nextStateData

  }

  when(WaitingForCiphertext) {
    case Event(Tcp.Received(data), currentStateData@WaitingForCiphertextData(enc, dec, length, buffer, listener)) =>
      val (nextStateData, plaintextMessages) = WaitingForCiphertextData.decrypt(currentStateData.copy(buffer = buffer ++ data))
      sendToListener(listener, plaintextMessages)
      stay using nextStateData

    case Event(t: T, WaitingForCiphertextData(enc, dec, length, buffer, listener)) =>
      val blob = codec.encode(t).require.toByteArray
      val (enc1, ciphertext) = TransportHandler.encrypt(enc, blob)
      out ! buf(ciphertext)
      stay using WaitingForCiphertextData(enc1, dec, length, buffer, listener)
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
    * @param enc       cipherstate
    * @param plaintext plaintext
    * @return a (cipherstate, ciphertext) tuple where ciphertext is encrypted according to BOLT #8
    */
  def encrypt(enc: CipherState, plaintext: BinaryData): (CipherState, BinaryData) = {
    val (enc1, ciphertext1) = enc.encryptWithAd(BinaryData.empty, Protocol.writeUInt16(plaintext.length, ByteOrder.BIG_ENDIAN))
    val (enc2, ciphertext2) = enc1.encryptWithAd(BinaryData.empty, plaintext)
    (enc2, ciphertext1 ++ ciphertext2)
  }

  def makeWriter(localStatic: KeyPair, remoteStatic: BinaryData) = Noise.HandshakeState.initializeWriter(
    Noise.handshakePatternXK, prologue,
    localStatic, KeyPair(BinaryData.empty, BinaryData.empty), remoteStatic, BinaryData.empty,
    Noise.Secp256k1DHFunctions, Noise.Chacha20Poly1305CipherFunctions, Noise.SHA256HashFunctions)

  def makeReader(localStatic: KeyPair) = Noise.HandshakeState.initializeReader(
    Noise.handshakePatternXK, prologue,
    localStatic, KeyPair(BinaryData.empty, BinaryData.empty), BinaryData.empty, BinaryData.empty,
    Noise.Secp256k1DHFunctions, Noise.Chacha20Poly1305CipherFunctions, Noise.SHA256HashFunctions)

  // @formatter:off
  sealed trait State
  case object Handshake extends State
  case object WaitingForListener extends State
  case object WaitingForCiphertext extends State
  // @formatter:on

  case class Listener(listener: ActorRef)

  case class HandshakeCompleted(connection: ActorRef, transport: ActorRef, remoteNodeId: PublicKey)

  sealed trait Data

  case class HandshakeData(reader: Noise.HandshakeStateReader, buffer: ByteString = ByteString.empty) extends Data

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

  case class WaitingForListenerData(enc: CipherState, dec: CipherState, buffer: ByteString) extends Data

  case class WaitingForCiphertextData(enc: CipherState, dec: CipherState, ciphertextLength: Option[Int], buffer: ByteString, listener: ActorRef) extends Data {
    def decrypt: (WaitingForCiphertextData, Seq[BinaryData]) = WaitingForCiphertextData.decrypt(this)
  }

  object WaitingForCiphertextData {
    @tailrec
    def decrypt(state: WaitingForCiphertextData, acc: Seq[BinaryData] = Nil): (WaitingForCiphertextData, Seq[BinaryData]) = {
      (state.ciphertextLength, state.buffer.length) match {
        case (None, length) if length < 18 => (state, acc)
        case (None, _) =>
          val (ciphertext, remainder) = state.buffer.splitAt(18)
          val (dec1, plaintext) = state.dec.decryptWithAd(BinaryData.empty, ciphertext)
          val length = Protocol.uint16(plaintext, ByteOrder.BIG_ENDIAN)
          decrypt(state.copy(dec = dec1, ciphertextLength = Some(length), buffer = remainder), acc)
        case (Some(expectedLength), length) if length < expectedLength + 16 => (state, acc)
        case (Some(expectedLength), _) =>
          val (ciphertext, remainder) = state.buffer.splitAt(expectedLength + 16)
          val (dec1, plaintext) = state.dec.decryptWithAd(BinaryData.empty, ciphertext)
          decrypt(state.copy(dec = dec1, ciphertextLength = None, buffer = remainder), acc :+ plaintext)
      }
    }
  }

}