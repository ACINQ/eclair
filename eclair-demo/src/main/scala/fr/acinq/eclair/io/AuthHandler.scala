package fr.acinq.eclair.io

import javax.crypto.Cipher

import akka.actor._
import akka.io.Tcp.{Register, Received, Write}
import akka.util.ByteString
import com.trueaccord.scalapb.GeneratedMessage
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.LightningCrypto._
import fr.acinq.eclair.io.AuthHandler.Secrets
import lightning._
import lightning.pkt.Pkt._

import scala.annotation.tailrec


/**
  * Created by PM on 27/10/2015.
  */

// @formatter:off

sealed trait Data
case object Nothing extends Data
case class SessionData(their_session_key: BinaryData, decryptor: Decryptor, encryptor: Encryptor) extends Data
case class Normal(channel: ActorRef, sessionData: SessionData) extends Data

sealed trait State
case object IO_WAITING_FOR_SESSION_KEY extends State
case object IO_WAITING_FOR_AUTH extends State
case object IO_NORMAL extends State

//case class Received(msg: GeneratedMessage, acknowledged: Long)

// @formatter:on

/**
  * message format used by lightningd:
  * header: 20 bytes
  * - 4 bytes: body length
  * - 16 bytes: AEAD tag
  * body: header.length + 16 bytes
  * - length bytes: encrypted plaintext
  * - 16 bytes: AEAD tag
  *
  * header and body are encrypted with the same key, with a nonce that is incremented each time:
  * header = encrypt(plaintext.length, key, nonce++)
  * body = encrypt(plaintext, key, nonce++)
  */


object Decryptor {
  case class Header(length: Int)

  def add(state: Decryptor, data: ByteString) : Decryptor = state match {
    case Decryptor(key, nonce, buffer, None, None) =>
      val buffer1 = buffer ++ data
      if (buffer1.length >= 20) {
        val plaintext = AeadChacha20Poly1305.decrypt(key, Protocol.writeUInt64(nonce), buffer1.take(4), Array.emptyByteArray, buffer1.drop(4).take(16))
        val length = Protocol.uint32(plaintext.take(4)).toInt
        val state1 = state.copy(header = Some(Header(length)), nonce = nonce + 1, buffer = ByteString.empty)
        add(state1, buffer1.drop(20))
      }
      else state.copy(buffer = buffer1)
    case Decryptor(key, nonce, buffer, Some(header), None) =>
      val buffer1 = buffer ++ data
      if (buffer1.length >= header.length) {
        val plaintext = AeadChacha20Poly1305.decrypt(key, Protocol.writeUInt64(nonce), buffer1.take(header.length), Array.emptyByteArray, buffer1.drop(header.length).take(16))
        state.copy(nonce = nonce + 1, body =  Some(plaintext), buffer = ByteString.empty)
      } else state.copy(buffer = buffer1)
  }
}

case class Decryptor(key: BinaryData, nonce: Long, buffer: ByteString = ByteString.empty, header: Option[Decryptor.Header] = None, body: Option[BinaryData] = None)

object Encryptor {
  def encrypt(encryptor: Encryptor, data: BinaryData) : (Encryptor, BinaryData) = {
    val header = Protocol.writeUInt32(data.length)
    val (ciphertext1, mac1) = AeadChacha20Poly1305.encrypt(encryptor.key, Protocol.writeUInt64(encryptor.nonce), header, Array.emptyByteArray)
    val (ciphertext2, mac2) = AeadChacha20Poly1305.encrypt(encryptor.key, Protocol.writeUInt64(encryptor.nonce + 1), data, Array.emptyByteArray)
    (encryptor.copy(nonce = encryptor.nonce + 2), ciphertext1 ++ mac1 ++ ciphertext2 ++ mac2)
  }
}

case class Encryptor(key: BinaryData, nonce: Long)

class AuthHandler(them: ActorRef, blockchain: ActorRef, our_params: OurChannelParams) extends LoggingFSM[State, Data] with Stash {

  import AuthHandler._

  val session_key = randomKeyPair()

  them ! Register(self)

  val firstMessage = Protocol.writeUInt32(session_key.pub.length) ++ session_key.pub

  them ! Write(ByteString.fromArray(firstMessage))

  def send(encryptor: Encryptor, message: BinaryData) : Encryptor = {
    val (encryptor1, ciphertext) = Encryptor.encrypt(encryptor, message)
    them ! Write(ByteString.fromArray(ciphertext))
    encryptor1
  }

  def send(encryptor: Encryptor, message: pkt) : Encryptor = send(encryptor, message.toByteArray)

  startWith(IO_WAITING_FOR_SESSION_KEY, Nothing)

  when(IO_WAITING_FOR_SESSION_KEY) {
    case Event(Received(data), _) =>
      val their_session_key_length = Protocol.uint32(data.take(4))
      log.info(s"session key length: $their_session_key_length")
      val their_session_key: BinaryData = data.drop(4).take(33)
      log.info(s"their_session_key=$their_session_key")
      /**
        * BOLT #1:
        * sending-key: SHA256(shared-secret || sending-node-id)
        * receiving-key: SHA256(shared-secret || receiving-node-id)
        */
      val shared_secret = ecdh(their_session_key, session_key.priv)
      val sending_key: BinaryData = Crypto.sha256(shared_secret ++ session_key.pub)
      val receiving_key: BinaryData = Crypto.sha256(shared_secret ++ their_session_key)
      log.debug(s"shared_secret: $shared_secret")
      log.debug(s"sending key: $sending_key")
      log.debug(s"receiving key: $receiving_key")
      /**
        * node_id is the expected value for the sending node.
        * session_sig is a valid secp256k1 ECDSA signature encoded as a 32-byte big endian R value, followed by a 32-byte big endian S value.
        * session_sig is the signature of the SHA256 of SHA256 of the receivers node_id, using the secret key corresponding to the sender's node_id.
        */
      val sig: BinaryData = Crypto.encodeSignature(Crypto.sign(Crypto.hash256(their_session_key), Globals.Node.privateKey))
      val our_auth = pkt(Auth(lightning.authenticate(Globals.Node.publicKey, bin2signature(sig))))

      val encryptor = Encryptor(sending_key, 0)
      val encryptor1 = send(encryptor, our_auth)
      val decryptor = Decryptor(receiving_key, 0)

      goto(IO_WAITING_FOR_AUTH) using SessionData(their_session_key, decryptor, encryptor1)
  }

  when(IO_WAITING_FOR_AUTH) {
    case Event(Received(chunk), s@SessionData(theirpub, decryptor, encryptor)) =>
      log.debug(s"received chunk=${BinaryData(chunk)}")
      val decryptor1 = Decryptor.add(decryptor, chunk)
      decryptor1.body match {
        case None => stay using s.copy(decryptor = decryptor1)
        case Some(plaintext) =>
          val pkt(Auth(auth)) = pkt.parseFrom(plaintext)
          val their_nodeid: BinaryData = auth.nodeId
          val their_sig: BinaryData = auth.sessionSig
          if (!Crypto.verifySignature(Crypto.hash256(session_key.pub), their_sig, their_nodeid)) {
            log.error(s"cannot verify peer signature $their_sig for public key $their_nodeid")
            context.stop(self)
          }
          val channel = context.actorOf(Channel.props(self, blockchain, our_params), name = "channel")
          goto(IO_NORMAL) using Normal(channel, s.copy(decryptor = decryptor1.copy(header = None, body = None)))
     }
  }

  when(IO_NORMAL) {
    case Event(Received(chunk), n@Normal(channel, s@SessionData(theirpub, decryptor, encryptor))) =>
      log.debug(s"received chunk=${BinaryData(chunk)}")
      val decryptor1 = Decryptor.add(decryptor, chunk)
      decryptor1.body match {
        case None => stay using Normal(channel, s.copy(decryptor = decryptor1))
        case Some(plaintext) =>
          val packet = pkt.parseFrom(plaintext)
          self ! packet
          stay using Normal(channel, s.copy(decryptor = decryptor1.copy(header = None, body = None)))
      }

    case Event(packet: pkt, n@Normal(channel, s@SessionData(theirpub, decryptor, encryptor))) =>
      log.debug(s"receiving $packet")
      packet.pkt match {
        case Open(o) => channel ! o
        case OpenAnchor(o) => channel ! o
        case OpenCommitSig(o) => channel ! o
        case OpenComplete(o) => channel ! o
        case UpdateAddHtlc(o) => channel ! o
        case UpdateFulfillHtlc(o) => channel ! o
        case UpdateFailHtlc(o) => channel ! o
        case UpdateCommit(o) => channel ! o
        case UpdateRevocation(o) => channel ! o
        case CloseClearing(o) => channel ! o
        case CloseSignature(o) => channel ! o
        case Error(o) => channel ! o
      }
      stay

    case Event(msg: GeneratedMessage, n@Normal(channel, s@SessionData(theirpub, decryptor, encryptor))) =>
      val packet = msg match {
        case o: open_channel => pkt(Open(o))
        case o: open_anchor => pkt(OpenAnchor(o))
        case o: open_commit_sig => pkt(OpenCommitSig(o))
        case o: open_complete => pkt(OpenComplete(o))
        case o: update_add_htlc => pkt(UpdateAddHtlc(o))
        case o: update_fulfill_htlc => pkt(UpdateFulfillHtlc(o))
        case o: update_fail_htlc => pkt(UpdateFailHtlc(o))
        case o: update_commit => pkt(UpdateCommit(o))
        case o: update_revocation => pkt(UpdateRevocation(o))
        case o: close_clearing => pkt(CloseClearing(o))
        case o: close_signature => pkt(CloseSignature(o))
        case o: error => pkt(Error(o))
      }
      log.debug(s"sending $packet")
      val encryptor1 = send(encryptor, packet)
      stay using n.copy(sessionData = s.copy(encryptor = encryptor1))

    case Event(cmd: Command, n@Normal(channel, _)) =>
      channel forward cmd
      stay
  }

  initialize()
}

object AuthHandler {

  case class Secrets(aes_key: BinaryData, hmac_key: BinaryData, aes_iv: BinaryData)

  def generate_secrets(ecdh_key: BinaryData, pub: BinaryData): Secrets = {
    val aes_key = Crypto.sha256(ecdh_key ++ pub :+ 0x00.toByte).take(16)
    val hmac_key = Crypto.sha256(ecdh_key ++ pub :+ 0x01.toByte)
    val aes_iv = Crypto.sha256(ecdh_key ++ pub :+ 0x02.toByte).take(16)
    Secrets(aes_key, hmac_key, aes_iv)
  }

  /**
    * Rounds up to a factor of 16
    *
    * @param l
    * @return
    */
  def round16(l: Int) = l % 16 match {
    case 0 => l
    case x => l + 16 - x
  }

  def writeMsg(msg: pkt, secrets: Secrets, cipher: Cipher, totlen_prev: Long): (BinaryData, Long) = {
    val buf = pkt.toByteArray(msg)
    val enclen = round16(buf.length)
    val enc = cipher.update(buf.padTo(enclen, 0x00: Byte))
    val totlen = totlen_prev + buf.length
    val totlen_bin = Protocol.writeUInt64(totlen)
    (hmac256(secrets.hmac_key, totlen_bin ++ enc) ++ totlen_bin ++ enc, totlen)
  }

  /**
    * splits buffer in separate msg
    *
    * @param data raw data received (possibly one, multiple or fractions of messages)
    * @param totlen_prev
    * @param f    will be applied to full messages
    * @return rest of the buffer (incomplete msg)
    */
  @tailrec
  def split(data: BinaryData, secrets: Secrets, cipher: Cipher, totlen_prev: Long, f: BinaryData => Unit): (BinaryData, Long) = {
    if (data.length < 32 + 8) (data, totlen_prev)
    else {
      val totlen = Protocol.uint64(data.slice(32, 32 + 8))
      val len = (totlen - totlen_prev).asInstanceOf[Int]
      val enclen = round16(len)
      if (data.length < 32 + 8 + enclen) (data, totlen_prev)
      else {
        val splitted = data.splitAt(32 + 8 + enclen)
        val refsig = BinaryData(data.take(32))
        val payload = BinaryData(data.slice(32, 32 + 8 + enclen))
        val sig = hmac256(secrets.hmac_key, payload)
        assert(sig.data.sameElements(refsig), "sig mismatch!")
        val dec = cipher.update(payload.drop(8).toArray)
        f(dec.take(len))
        split(splitted._2, secrets, cipher, totlen_prev + len, f)
      }
    }
  }
}