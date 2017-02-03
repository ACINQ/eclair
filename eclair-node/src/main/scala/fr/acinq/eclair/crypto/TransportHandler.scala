package fr.acinq.eclair.crypto

import java.nio.ByteOrder

import akka.actor.{Actor, ActorContext, ActorRef, LoggingFSM, Terminated}
import akka.actor.{Actor, ActorRef, LoggingFSM, OneForOneStrategy, SupervisorStrategy, Terminated}
import akka.io.Tcp.{PeerClosed, _}
import akka.util.ByteString
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.channel.{CMD_CLOSE, Command}
import fr.acinq.eclair.crypto.Noise._

import scala.annotation.tailrec
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

/**
  * see BOLT #8
  * This class handles the transport layer:
  * - initial handshake. upon completion we will have  a pair of cipher states (one for encryption, one for decryption)
  * - encryption/decryption of messages
  *
  * Once the initial handshake has been completed successfully, the handler will create a listener actor with the
  * provided factory, and will forward it all decrypted messages
  *
  * @param keyPair         private/public key pair for this node
  * @param rs              remote node static public key (which must be known before we initiate communication)
  * @param them            actor htat represents the other node's
  * @param isWriter        if true we initiate the dialog
  * @param listenerFactory factory that will be used to create the listener that will receive decrypted messages once the
  *                        handshake phase as been completed. Its parameters are a tuple (transport handler, remote public key)
  */
class TransportHandler[T: ClassTag](keyPair: KeyPair, rs: Option[BinaryData], them: ActorRef, isWriter: Boolean, listenerFactory: (ActorRef, PublicKey, ActorContext) => ActorRef, serializer: TransportHandler.Serializer[T]) extends Actor with LoggingFSM[TransportHandler.State, TransportHandler.Data] {

  import TransportHandler._

  if (isWriter) require(rs.isDefined)

  context.watch(them)

  val reader = if (isWriter) {
    val state = makeWriter(keyPair, rs.get)
    val (state1, message, None) = state.write(BinaryData.empty)
    log.debug(s"sending prefix + $message")
    them ! Write(TransportHandler.prefix +: message)
    state1
  } else {
    makeReader(keyPair)
  }

  def sendToListener(listener: ActorRef, plaintextMessages: Seq[BinaryData]) = {
    plaintextMessages.map(plaintext => {
      Try(serializer.deserialize(plaintext)) match {
        case Success(message) => listener ! message
        case Failure(t) =>
          log.error(t, s"cannot deserialize $plaintext")
      }
    })
  }

  startWith(Handshake, HandshakeData(reader))

  when(Handshake) {
    case Event(Received(data), HandshakeData(reader, buffer)) =>
      val buffer1 = buffer ++ data
      if (buffer1.length < expectedLength(reader))
        stay using HandshakeData(reader, buffer1)
      else {
        require(buffer1.head == TransportHandler.prefix, s"invalid transport prefix ${buffer1.head}")
        val (payload, remainder) = buffer1.tail.splitAt(expectedLength(reader) - 1)

        reader.read(payload) match {
          case (writer, _, Some((dec, enc, ck))) =>
            val listener = listenerFactory(self, PublicKey(writer.rs), context)
            context.watch(listener)
            val (nextStateData, plaintextMessages) = WaitingForCyphertextData(ExtendedCipherState(enc, ck), ExtendedCipherState(dec, ck), None, remainder, listener).decrypt
            sendToListener(listener, plaintextMessages)
            goto(WaitingForCyphertext) using nextStateData

          case (writer, _, None) => {
            writer.write(BinaryData.empty) match {
              case (reader1, message, None) => {
                // we're still in the middle of the handshake process and the other end must first received our next
                // message before they can reply
                require(remainder.isEmpty, "unexpected additional data received during handshake")
                them ! Write(TransportHandler.prefix +: message)
                stay using HandshakeData(reader1, remainder)
              }
              case (_, message, Some((enc, dec, ck))) => {
                them ! Write(TransportHandler.prefix +: message)
                val listener = listenerFactory(self, PublicKey(writer.rs), context)
                context.watch(listener)
                val (nextStateData, plaintextMessages) = WaitingForCyphertextData(ExtendedCipherState(enc, ck), ExtendedCipherState(dec, ck), None, remainder, listener).decrypt
                sendToListener(listener, plaintextMessages)
                goto(WaitingForCyphertext) using nextStateData
              }
            }
          }
        }
      }

    case Event(ErrorClosed(cause), _) =>
      log.warning(s"connection closed: $cause")
      context.stop(self)
      stay()
  }

  when(WaitingForCyphertext) {
    case Event(Terminated(actor), WaitingForCyphertextData(_, _, _, _, listener)) if actor == listener =>
      context.stop(self)
      stay()

    case Event(Received(data), currentStateData@WaitingForCyphertextData(enc, dec, length, buffer, listener)) =>
      val (nextStateData, plaintextMessages) = WaitingForCyphertextData.decrypt(currentStateData.copy(buffer = buffer ++ data))
      sendToListener(listener, plaintextMessages)
      stay using nextStateData

    case Event(plaintext: BinaryData, WaitingForCyphertextData(enc, dec, length, buffer, listener)) =>
      val (enc1, ciphertext) = TransportHandler.encrypt(enc, plaintext)
      them ! Write(ByteString.fromArray(ciphertext.toArray))
      stay using WaitingForCyphertextData(enc1, dec, length, buffer, listener)

    case Event(t: T, WaitingForCyphertextData(enc, dec, length, buffer, listener)) =>
      val blob = serializer.serialize(t)
      val (enc1, ciphertext) = TransportHandler.encrypt(enc, blob)
      them ! Write(ByteString.fromArray(ciphertext.toArray))
      stay using WaitingForCyphertextData(enc1, dec, length, buffer, listener)

    case Event(cmd: Command, WaitingForCyphertextData(_, _, _, _, listener)) =>
      listener forward cmd
      stay

    case Event(ErrorClosed(cause), WaitingForCyphertextData(_, _, _, _, listener)) =>
      // we transform connection closed events into application error so that it triggers a uniclose
      log.error(s"tcp connection error: $cause")
      listener ! fr.acinq.eclair.wire.Error(0, cause.getBytes("UTF-8"))
      stay

    case Event(PeerClosed, WaitingForCyphertextData(_, _, _, _, listener)) =>
      listener ! fr.acinq.eclair.wire.Error(0, "peer closed".getBytes("UTF-8"))
      stay
  }

  whenUnhandled {
    case Event(Terminated(actor), _) if actor == them =>
      log.warning("peer closed")
      stay()

    case Event(message, _) =>
      log.warning(s"unhandled $message")
      stay()
  }
}

object TransportHandler {
  // see BOLT #8
  // this prefix is prepended to all Noise messages sent during the hanshake phase
  val prefix: Byte = 0

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

  sealed trait State

  case object Handshake extends State

  case object WaitingForCyphertext extends State

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
        case UnitializedCipherState(_) => (this, plaintext)
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
        case UnitializedCipherState(_) => (this, ciphertext)
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

  case class WaitingForCyphertextData(enc: CipherState, dec: CipherState, ciphertextLength: Option[Int], buffer: ByteString, listener: ActorRef) extends Data {
    def decrypt: (WaitingForCyphertextData, Seq[BinaryData]) = WaitingForCyphertextData.decrypt(this)
  }

  object WaitingForCyphertextData {
    @tailrec
    def decrypt(state: WaitingForCyphertextData, acc: Seq[BinaryData] = Nil): (WaitingForCyphertextData, Seq[BinaryData]) = {
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

  trait Serializer[T] {
    def serialize(t: T): BinaryData

    def deserialize(bin: BinaryData): T
  }

  object Noop extends Serializer[BinaryData] {
    override def serialize(t: BinaryData): BinaryData = t

    override def deserialize(bin: BinaryData): BinaryData = bin
  }

}