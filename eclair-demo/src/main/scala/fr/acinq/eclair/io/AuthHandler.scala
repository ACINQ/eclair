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
import org.bouncycastle.util.encoders.Hex

import scala.annotation.tailrec


/**
  * Created by PM on 27/10/2015.
  */

// @formatter:off

sealed trait Data
case object Nothing extends Data
case class SessionData(their_session_key: BinaryData, secrets_in: Secrets, secrets_out: Secrets, cipher_in: Cipher, cipher_out: Cipher, totlen_in: Long, totlen_out: Long, acc_in: BinaryData) extends Data
case class Normal(channel: ActorRef, sessionData: SessionData) extends Data

sealed trait State
case object IO_WAITING_FOR_SESSION_KEY extends State
case object IO_WAITING_FOR_AUTH extends State
case object IO_NORMAL extends State

// @formatter:on

class AuthHandler(them: ActorRef, blockchain: ActorRef, our_params: OurChannelParams) extends LoggingFSM[State, Data] with Stash {

  import AuthHandler._

  val session_key = randomKeyPair()

  them ! Register(self)
  them ! Write(ByteString.fromArray(session_key.pub))
  startWith(IO_WAITING_FOR_SESSION_KEY, Nothing)

  when(IO_WAITING_FOR_SESSION_KEY) {
    case Event(Received(data), _) =>
      val their_session_key = BinaryData(data)
      log.info(s"their_session_key=$their_session_key")
      val secrets_in = generate_secrets(ecdh(their_session_key, session_key.priv), their_session_key)
      val secrets_out = generate_secrets(ecdh(their_session_key, session_key.priv), session_key.pub)
      log.info(s"generated secrets_in=$secrets_in secrets_out=$secrets_out")
      val cipher_in = aesDecryptCipher(secrets_in.aes_key, secrets_in.aes_iv)
      val cipher_out = aesEncryptCipher(secrets_out.aes_key, secrets_out.aes_iv)
      val our_auth = pkt(Auth(lightning.authenticate(Globals.node_id.pub, bin2signature(Crypto.encodeSignature(Crypto.sign(Crypto.hash256(their_session_key), Globals.node_id.priv))))))
      val (d, new_totlen_out) = writeMsg(our_auth, secrets_out, cipher_out, 0)
      them ! Write(ByteString.fromArray(d))
      goto(IO_WAITING_FOR_AUTH) using SessionData(their_session_key, secrets_in, secrets_out, cipher_in, cipher_out, 0, new_totlen_out, BinaryData(Seq()))
  }

  when(IO_WAITING_FOR_AUTH) {
    case Event(Received(chunk), s@SessionData(theirpub, secrets_in, secrets_out, cipher_in, cipher_out, totlen_in, totlen_out, acc_in)) =>
      log.debug(s"received chunk=${BinaryData(chunk)}")
      val (rest, new_totlen_in) = split(acc_in ++ chunk, secrets_in, cipher_in, totlen_in, m => self ! pkt.parseFrom(m))
      stay using s.copy(totlen_in = new_totlen_in, acc_in = rest)

    case Event(pkt(Auth(auth)), s: SessionData) =>
      log.info(s"their_nodeid=${BinaryData(auth.nodeId.key.toByteArray)}")
      assert(Crypto.verifySignature(Crypto.hash256(session_key.pub), signature2bin(auth.sessionSig), pubkey2bin(auth.nodeId)), "auth failed")
      val channel = context.actorOf(Channel.props(self, blockchain, our_params), name = "channel")
      goto(IO_NORMAL) using Normal(channel, s)
  }

  when(IO_NORMAL) {
    case Event(Received(chunk), n@Normal(channel, s@SessionData(theirpub, secrets_in, secrets_out, cipher_in, cipher_out, totlen_in, totlen_out, acc_in))) =>
      log.debug(s"received chunk=${BinaryData(chunk)}")
      val (rest, new_totlen_in) = split(acc_in ++ chunk, secrets_in, cipher_in, totlen_in, m => self ! pkt.parseFrom(m))
      stay using n.copy(sessionData = s.copy(totlen_in = new_totlen_in, acc_in = rest))

    case Event(packet: pkt, n@Normal(channel, s@SessionData(theirpub, secrets_in, secrets_out, cipher_in, cipher_out, totlen_in, totlen_out, acc_in))) =>
      log.debug(s"sending $packet")
      packet.pkt match {
        case Open(o) => channel ! o
        case OpenAnchor(o) => channel ! o
        case OpenCommitSig(o) => channel ! o
        case OpenComplete(o) => channel ! o
        case Update(o) => channel ! o
        case UpdateAddHtlc(o) => channel ! o
        case UpdateAccept(o) => channel ! o
        case UpdateSignature(o) => channel ! o
        case UpdateComplete(o) => channel ! o
        case UpdateDeclineHtlc(o) => channel ! o
        case UpdateFulfillHtlc(o) => channel ! o
        case UpdateTimedoutHtlc(o) => channel ! o
        case UpdateRoutefailHtlc(o) => channel ! o
        case Close(o) => channel ! o
        case CloseComplete(o) => channel ! o
        case CloseAck(o) => channel ! o
        case Error(o) => channel ! o
      }
      stay

    case Event(msg: GeneratedMessage, n@Normal(channel, s@SessionData(theirpub, secrets_in, secrets_out, cipher_in, cipher_out, totlen_in, totlen_out, acc_in))) =>
      val packet = msg match {
        case o: open_channel => pkt(Open(o))
        case o: open_anchor => pkt(OpenAnchor(o))
        case o: open_commit_sig => pkt(OpenCommitSig(o))
        case o: open_complete => pkt(OpenComplete(o))
        case o: update => pkt(Update(o))
        case o: update_add_htlc => pkt(UpdateAddHtlc(o))
        case o: update_accept => pkt(UpdateAccept(o))
        case o: update_signature => pkt(UpdateSignature(o))
        case o: update_complete => pkt(UpdateComplete(o))
        case o: update_decline_htlc => pkt(UpdateDeclineHtlc(o))
        case o: update_fulfill_htlc => pkt(UpdateFulfillHtlc(o))
        case o: update_timedout_htlc => pkt(UpdateTimedoutHtlc(o))
        case o: update_routefail_htlc => pkt(UpdateRoutefailHtlc(o))
        case o: close_channel => pkt(Close(o))
        case o: close_channel_complete => pkt(CloseComplete(o))
        case o: close_channel_ack => pkt(CloseAck(o))
        case o: error => pkt(Error(o))
      }
      log.debug(s"receiving $packet")
      val (data, new_totlen_out) = writeMsg(packet, secrets_out, cipher_out, totlen_out)
      them ! Write(ByteString.fromArray(data))
      stay using n.copy(sessionData = s.copy(totlen_out = new_totlen_out))

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