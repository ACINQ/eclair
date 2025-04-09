/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.crypto

import akka.actor.{Actor, ActorRef, ExtendedActorSystem, FSM, PoisonPill, Props, Terminated}
import akka.event.Logging.MDC
import akka.event._
import akka.io.Tcp
import akka.util.ByteString
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.Protocol
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.crypto.ChaCha20Poly1305.ChaCha20Poly1305Error
import fr.acinq.eclair.crypto.Noise._
import fr.acinq.eclair.remote.EclairInternalsSerializer.RemoteTypes
import fr.acinq.eclair.wire.protocol.{AnnouncementSignatures, LightningMessage, RoutingMessage}
import fr.acinq.eclair.{Diagnostics, FSMDiagnosticActorLogging, Logs, getSimpleClassName}
import scodec.bits.ByteVector
import scodec.{Attempt, Codec, DecodeResult}

import java.nio.ByteOrder
import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.reflect.ClassTag

/**
 * see BOLT #8
 * This class handles the transport layer:
 * - initial handshake. upon completion we will have a pair of cipher states (one for encryption, one for decryption)
 * - encryption/decryption of messages
 *
 * Once the initial handshake has been completed successfully, the handler will create a listener actor with the
 * provided factory, and will forward it all decrypted messages
 *
 * @param keyPair    private/public key pair for this node
 * @param rs         remote node static public key (which must be known before we initiate communication)
 * @param connection actor that represents the other node's
 */
class TransportHandler(keyPair: KeyPair, rs: Option[ByteVector], connection: ActorRef, codec: Codec[LightningMessage]) extends Actor with FSMDiagnosticActorLogging[TransportHandler.State, TransportHandler.Data] {

  // will hold the peer's public key once it is available (we don't know it right away in case of an incoming connection)
  var remoteNodeId_opt: Option[PublicKey] = rs.map(PublicKey(_))

  private val wireLog = new BusLogging(context.system.eventStream, "", classOf[Diagnostics], context.system.asInstanceOf[ExtendedActorSystem].logFilter) with DiagnosticLoggingAdapter

  private def logMessage(message: LightningMessage, direction: String): Unit = {
    val channelId_opt = Logs.channelId(message)
    wireLog.mdc(Logs.mdc(LogCategory(message), remoteNodeId_opt, channelId_opt))
    if (channelId_opt.isDefined) {
      // channel-related messages are logged as info
      wireLog.info("{} msg={}", direction, message)
    } else {
      // other messages (e.g. routing gossip) are logged as debug
      wireLog.debug("{} msg={}", direction, message)
    }
    wireLog.clearMDC()
  }

  import TransportHandler._

  connection ! Tcp.Register(self)
  connection ! Tcp.ResumeReading

  def buf(message: ByteVector): ByteString = ByteString.fromArray(message.toArray)

  // it means we initiate the dialog
  private val isWriter = rs.isDefined

  context.watch(connection)

  private val reader = if (isWriter) {
    val state = makeWriter(keyPair, rs.get)
    val (state1, message, None) = state.write(ByteVector.empty)
    log.debug(s"sending prefix + $message")
    connection ! Tcp.Write(buf(TransportHandler.prefix +: message))
    state1
  } else {
    makeReader(keyPair)
  }

  private def decodeAndSendToListener(listener: ActorRef, plaintextMessages: Seq[ByteVector]): Map[LightningMessage, Int] = {
    log.debug("decoding {} plaintext messages", plaintextMessages.size)
    var m = Map.empty[LightningMessage, Int]
    plaintextMessages.foreach(plaintext => codec.decode(plaintext.bits) match {
      case Attempt.Successful(DecodeResult(message, _)) =>
        logMessage(message, "IN")
        Monitoring.Metrics.MessageSize.withTag(Monitoring.Tags.MessageDirection, Monitoring.Tags.MessageDirections.IN).record(plaintext.size)
        listener ! message
        m += (message -> (m.getOrElse(message, 0) + 1))
      case Attempt.Failure(err) =>
        log.warning("cannot deserialize {}: {}", plaintext.toHex, err.message)
    })
    log.debug("decoded {} messages", m.values.sum)
    m
  }

  startWith(Handshake, HandshakeData(reader))

  when(Handshake) {
    handleExceptions {
      case Event(Tcp.Received(data), HandshakeData(reader, buffer)) =>
        connection ! Tcp.ResumeReading
        log.debug("received {}", ByteVector(data))
        val buffer1 = buffer ++ data
        if (buffer1.length < expectedLength(reader))
          stay() using HandshakeData(reader, buffer1)
        else {
          if (buffer1.head != TransportHandler.prefix) throw InvalidTransportPrefix(ByteVector(buffer1))

          val (payload, remainder) = buffer1.tail.splitAt(expectedLength(reader) - 1)

          reader.read(ByteVector.view(payload.asByteBuffer)) match {
            case (writer, _, Some((dec, enc, ck))) =>
              val remoteNodeId = PublicKey(writer.rs)
              remoteNodeId_opt = Some(remoteNodeId)
              context.parent ! HandshakeCompleted(remoteNodeId)
              val nextStateData = WaitingForListenerData(Encryptor(ExtendedCipherState(enc, ck)), Decryptor(ExtendedCipherState(dec, ck), ciphertextLength = None, remainder))
              goto(WaitingForListener) using nextStateData

            case (writer, _, None) =>
              writer.write(ByteVector.empty) match {
                case (reader1, message, None) =>
                  // we're still in the middle of the handshake process and the other end must first received our next
                  // message before they can reply
                  if (remainder.nonEmpty) throw UnexpectedDataDuringHandshake(ByteVector(remainder))
                  connection ! Tcp.Write(buf(TransportHandler.prefix +: message))
                  stay() using HandshakeData(reader1, remainder)
                case (_, message, Some((enc, dec, ck))) =>
                  connection ! Tcp.Write(buf(TransportHandler.prefix +: message))
                  val remoteNodeId = PublicKey(writer.rs)
                  remoteNodeId_opt = Some(remoteNodeId)
                  context.parent ! HandshakeCompleted(remoteNodeId)
                  val nextStateData = WaitingForListenerData(Encryptor(ExtendedCipherState(enc, ck)), Decryptor(ExtendedCipherState(dec, ck), ciphertextLength = None, remainder))
                  goto(WaitingForListener) using nextStateData
              }
          }
        }
    }
  }

  when(WaitingForListener) {
    handleExceptions {
      case Event(Tcp.Received(data), d@WaitingForListenerData(_, dec)) =>
        stay() using d.copy(decryptor = dec.copy(buffer = dec.buffer ++ data))

      case Event(Listener(listener), d@WaitingForListenerData(_, dec)) =>
        context.watch(listener)
        val (dec1, plaintextMessages) = dec.decrypt()
        val unackedReceived1 = decodeAndSendToListener(listener, plaintextMessages)
        if (unackedReceived1.isEmpty) {
          log.debug("no decoded messages, resuming reading")
          connection ! Tcp.ResumeReading
        }
        goto(Normal) using NormalData(d.encryptor, dec1, listener, sendBuffer = SendBuffer(Queue.empty[LightningMessage], Queue.empty[LightningMessage]), unackedReceived = unackedReceived1, unackedSent = None)
    }
  }

  when(Normal) {
    handleExceptions {
      case Event(Tcp.Received(data), d: NormalData) =>
        log.debug("received chunk of size={}", data.size)
        val (dec1, plaintextMessages) = d.decryptor.copy(buffer = d.decryptor.buffer ++ data).decrypt()
        val unackedReceived1 = decodeAndSendToListener(d.listener, plaintextMessages)
        if (unackedReceived1.isEmpty) {
          log.debug("no decoded messages, resuming reading")
          connection ! Tcp.ResumeReading
        }
        stay() using d.copy(decryptor = dec1, unackedReceived = unackedReceived1)

      case Event(ReadAck(msg: LightningMessage), d: NormalData) =>
        // how many occurrences of this message are still unacked?
        val remaining = d.unackedReceived.getOrElse(msg, 0) - 1
        log.debug("acking message {}", msg)
        // if all occurrences have been acked then we remove the entry from the map
        val unackedReceived1 = if (remaining > 0) d.unackedReceived + (msg -> remaining) else d.unackedReceived - msg
        if (unackedReceived1.isEmpty) {
          log.debug("last incoming message was acked, resuming reading")
          connection ! Tcp.ResumeReading
        } else {
          log.debug("still waiting for readacks, unacked={}", unackedReceived1)
        }
        stay() using d.copy(unackedReceived = unackedReceived1)

      case Event(t: LightningMessage, d: NormalData) =>
        if (d.sendBuffer.normalPriority.size + d.sendBuffer.lowPriority.size >= MAX_BUFFERED) {
          log.warning("send buffer overrun, closing connection")
          connection ! PoisonPill
          stop(FSM.Normal)
        } else if (d.unackedSent.isDefined) {
          log.debug("buffering send data={}", t)
          val sendBuffer1 = t match {
            case _: AnnouncementSignatures => d.sendBuffer.copy(normalPriority = d.sendBuffer.normalPriority :+ t)
            case _: RoutingMessage => d.sendBuffer.copy(lowPriority = d.sendBuffer.lowPriority :+ t)
            case _ => d.sendBuffer.copy(normalPriority = d.sendBuffer.normalPriority :+ t)
          }
          stay() using d.copy(sendBuffer = sendBuffer1)
        } else {
          logMessage(t, "OUT")
          val blob = codec.encode(t).require.toByteVector
          Monitoring.Metrics.MessageSize.withTag(Monitoring.Tags.MessageDirection, Monitoring.Tags.MessageDirections.OUT).record(blob.size)
          val (enc1, ciphertext) = d.encryptor.encrypt(blob)
          connection ! Tcp.Write(buf(ciphertext), WriteAck)
          stay() using d.copy(encryptor = enc1, unackedSent = Some(t))
        }

      case Event(WriteAck, d: NormalData) =>
        def send(t: LightningMessage) = {
          logMessage(t, "OUT")
          val blob = codec.encode(t).require.toByteVector
          Monitoring.Metrics.MessageSize.withTag(Monitoring.Tags.MessageDirection, Monitoring.Tags.MessageDirections.OUT).record(blob.size)
          val (enc1, ciphertext) = d.encryptor.encrypt(blob)
          connection ! Tcp.Write(buf(ciphertext), WriteAck)
          enc1
        }

        d.sendBuffer.normalPriority.dequeueOption match {
          case Some((t, normalPriority1)) =>
            val enc1 = send(t)
            stay() using d.copy(encryptor = enc1, sendBuffer = d.sendBuffer.copy(normalPriority = normalPriority1), unackedSent = Some(t))
          case None =>
            d.sendBuffer.lowPriority.dequeueOption match {
              case Some((t, lowPriority1)) =>
                val enc1 = send(t)
                stay() using d.copy(encryptor = enc1, sendBuffer = d.sendBuffer.copy(lowPriority = lowPriority1), unackedSent = Some(t))
              case None =>
                stay() using d.copy(unackedSent = None)
            }
        }
    }
  }

  whenUnhandled {
    handleExceptions {
      case Event(closed: Tcp.ConnectionClosed, _) =>
        log.debug(s"connection closed: $closed")
        stop(FSM.Normal)

      case Event(Terminated(actor), _) if actor == connection =>
        log.debug("connection actor died")
        // this can be the connection or the listener, either way it is a cause of death
        stop(FSM.Normal)

      case Event(msg, d) =>
        d match {
          case n: NormalData => log.warning(s"unhandled message $msg in state normal unackedSent=${n.unackedSent.size} unackedReceived=${n.unackedReceived.size} sendBuffer.lowPriority=${n.sendBuffer.lowPriority.size} sendBuffer.normalPriority=${n.sendBuffer.normalPriority.size}")
          case _ => log.warning(s"unhandled message $msg in state ${d.getClass.getSimpleName}")
        }
        stay()
    }
  }

  onTermination {
    case _: StopEvent =>
      // we need to set the mdc here, because StopEvent doesn't go through the regular actor's mailbox
      Logs.withMdc(diagLog)(Logs.mdc(category_opt = Some(Logs.LogCategory.CONNECTION), remoteNodeId_opt = remoteNodeId_opt)) {
        connection ! Tcp.Close // attempts to gracefully close the connection when dying
        stateData match {
          case normal: NormalData =>
            // NB: we deduplicate on the class name: each class will appear once but there may be many instances (less verbose and gives debug hints)
            log.info("stopping (unackedReceived={} unackedSent={})", normal.unackedReceived.keys.map(getSimpleClassName).toSet.mkString(","), normal.unackedSent.map(getSimpleClassName))
          case _ =>
            log.info("stopping")
        }
      }
  }

  initialize()

  override def mdc(currentMessage: Any): MDC = {
    val category_opt = LogCategory(currentMessage)
    Logs.mdc(category_opt, remoteNodeId_opt = remoteNodeId_opt)
  }

  def handleExceptions(s: StateFunction): StateFunction = {
    case event if s.isDefinedAt(event) =>
      try {
        s(event)
      } catch {
        case t: Throwable =>
          t match {
            // for well known crypto error, we don't display the stack trace
            case _: InvalidTransportPrefix | _: ChaCha20Poly1305Error => log.error(s"crypto error: ${t.getMessage}")
            case _ => log.error(t, "")
          }
          throw t
      }
  }

}

object TransportHandler {

  def props[T: ClassTag](keyPair: KeyPair, rs: Option[ByteVector], connection: ActorRef, codec: Codec[LightningMessage]): Props = Props(new TransportHandler(keyPair, rs, connection, codec))

  private val MAX_BUFFERED = 1000000L

  // see BOLT #8
  // this prefix is prepended to all Noise messages sent during the handshake phase
  val prefix: Byte = 0x00
  private val prologue = ByteVector.view("lightning".getBytes("UTF-8"))

  // @formatter:off
  private case class InvalidTransportPrefix(buffer: ByteVector) extends RuntimeException(s"invalid transport prefix first64=${buffer.take(64).toHex}")
  private case class UnexpectedDataDuringHandshake(buffer: ByteVector) extends RuntimeException(s"unexpected additional data received during handshake first64=${buffer.take(64).toHex}")
  // @formatter:on

  /**
   * See BOLT #8: during the handshake phase we are expecting 3 messages of 50, 50 and 66 bytes (including the prefix)
   *
   * @param reader handshake state reader
   * @return the size of the message the reader is expecting
   */
  private def expectedLength(reader: Noise.HandshakeStateReader): Int = reader.messages.length match {
    case 3 | 2 => 50
    case 1 => 66
  }

  private def makeWriter(localStatic: KeyPair, remoteStatic: ByteVector): HandshakeStateWriter = Noise.HandshakeState.initializeWriter(
    Noise.handshakePatternXK, prologue,
    localStatic, KeyPair(ByteVector.empty, ByteVector.empty), remoteStatic, ByteVector.empty,
    Noise.Secp256k1DHFunctions, Noise.Chacha20Poly1305CipherFunctions, Noise.SHA256HashFunctions)

  private def makeReader(localStatic: KeyPair): HandshakeStateReader = Noise.HandshakeState.initializeReader(
    Noise.handshakePatternXK, prologue,
    localStatic, KeyPair(ByteVector.empty, ByteVector.empty), ByteVector.empty, ByteVector.empty,
    Noise.Secp256k1DHFunctions, Noise.Chacha20Poly1305CipherFunctions, Noise.SHA256HashFunctions)

  /**
   * extended cipher state which implements key rotation as per BOLT #8
   *
   * @param cs cipher state
   * @param ck chaining key
   */
  case class ExtendedCipherState(cs: CipherState, ck: ByteVector) extends CipherState {
    override val cipher: CipherFunctions = cs.cipher
    override val hasKey: Boolean = cs.hasKey

    override def encryptWithAd(ad: ByteVector, plaintext: ByteVector): (CipherState, ByteVector) = {
      cs match {
        case UninitializedCipherState(_) => (this, plaintext)
        case InitializedCipherState(k, n, _) if n == 999 =>
          val (_, ciphertext) = cs.encryptWithAd(ad, plaintext)
          val (ck1, k1) = SHA256HashFunctions.hkdf(ck, k)
          (this.copy(cs = cs.initializeKey(k1), ck = ck1), ciphertext)
        case _: InitializedCipherState =>
          val (cs1, ciphertext) = cs.encryptWithAd(ad, plaintext)
          (this.copy(cs = cs1), ciphertext)
      }
    }

    override def decryptWithAd(ad: ByteVector, ciphertext: ByteVector): (CipherState, ByteVector) = {
      cs match {
        case UninitializedCipherState(_) => (this, ciphertext)
        case InitializedCipherState(k, n, _) if n == 999 =>
          val (_, plaintext) = cs.decryptWithAd(ad, ciphertext)
          val (ck1, k1) = SHA256HashFunctions.hkdf(ck, k)
          (this.copy(cs = cs.initializeKey(k1), ck = ck1), plaintext)
        case _: InitializedCipherState =>
          val (cs1, plaintext) = cs.decryptWithAd(ad, ciphertext)
          (this.copy(cs = cs1), plaintext)
      }
    }
  }

  case class Decryptor(state: CipherState, ciphertextLength: Option[Int], buffer: ByteString) {
    @tailrec
    final def decrypt(acc: Seq[ByteVector] = Vector()): (Decryptor, Seq[ByteVector]) = {
      (ciphertextLength, buffer.length) match {
        case (None, length) if length < 18 => (this, acc)
        case (None, _) =>
          val (ciphertext, remainder) = buffer.splitAt(18)
          val (dec1, plaintext) = state.decryptWithAd(ByteVector.empty, ByteVector.view(ciphertext.asByteBuffer))
          val length = Protocol.uint16(plaintext.toArray, ByteOrder.BIG_ENDIAN)
          Decryptor(dec1, ciphertextLength = Some(length), buffer = remainder).decrypt(acc)
        case (Some(expectedLength), length) if length < expectedLength + 16 => (Decryptor(state, ciphertextLength, buffer), acc)
        case (Some(expectedLength), _) =>
          val (ciphertext, remainder) = buffer.splitAt(expectedLength + 16)
          val (dec1, plaintext) = state.decryptWithAd(ByteVector.empty, ByteVector.view(ciphertext.asByteBuffer))
          Decryptor(dec1, ciphertextLength = None, buffer = remainder).decrypt(acc :+ plaintext)
      }
    }
  }

  case class Encryptor(state: CipherState) {
    /**
     * see BOLT #8
     * {{{
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
     * }}}
     *
     * @param plaintext plaintext
     * @return a (cipherstate, ciphertext) tuple where ciphertext is encrypted according to BOLT #8
     */
    def encrypt(plaintext: ByteVector): (Encryptor, ByteVector) = {
      val (state1, ciphertext1) = state.encryptWithAd(ByteVector.empty, Protocol.writeUInt16(plaintext.length.toInt, ByteOrder.BIG_ENDIAN))
      val (state2, ciphertext2) = state1.encryptWithAd(ByteVector.empty, plaintext)
      (Encryptor(state2), ciphertext1 ++ ciphertext2)
    }
  }

  // @formatter:off
  sealed trait State
  private case object Handshake extends State
  case object WaitingForListener extends State
  case object Normal extends State

  sealed trait Data
  private case class HandshakeData(reader: Noise.HandshakeStateReader, buffer: ByteString = ByteString.empty) extends Data
  private case class WaitingForListenerData(encryptor: Encryptor, decryptor: Decryptor) extends Data
  private case class NormalData(encryptor: Encryptor, decryptor: Decryptor, listener: ActorRef, sendBuffer: SendBuffer, unackedReceived: Map[LightningMessage, Int], unackedSent: Option[LightningMessage]) extends Data

  private case class SendBuffer(normalPriority: Queue[LightningMessage], lowPriority: Queue[LightningMessage])

  case class Listener(listener: ActorRef)

  case class HandshakeCompleted(remoteNodeId: PublicKey)

  case class ReadAck(msg: Any) extends RemoteTypes
  private case object WriteAck extends Tcp.Event
  // @formatter:on

}