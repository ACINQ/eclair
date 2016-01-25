package fr.acinq.eclair.io

import javax.crypto.Cipher

import akka.actor._
import akka.io.Tcp.{Received, Write}
import akka.util.ByteString
import com.trueaccord.scalapb.GeneratedMessage
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.PollingWatcher
import fr.acinq.eclair.channel.{INPUT_NONE, Channel, OurChannelParams, AnchorInput}
import fr.acinq.eclair.crypto.LightningCrypto._
import fr.acinq.eclair.io.AuthHandler.Secrets
import lightning._
import lightning.locktime.Locktime.{Seconds, Blocks}
import lightning.pkt.Pkt.{OpenCommitSig, OpenAnchor, Open, Auth}
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST.{JInt, JObject, JValue}

import scala.annotation.tailrec
import scala.concurrent.Future


/**
  * Created by PM on 27/10/2015.
  */

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

// @formatter:off

sealed trait Data
case object Nothing extends Data
case class SessionData(theirpub: BinaryData, secrets_in: Secrets, secrets_out: Secrets, cipher_in: Cipher, cipher_out: Cipher, totlen_in: Long, totlen_out: Long, acc_in: BinaryData) extends Data
case class Normal(channel: ActorRef, sessionData: SessionData) extends Data

sealed trait State
case object IO_WAITING_FOR_SESSION_KEY extends State
case object IO_WAITING_FOR_AUTH extends State
case object IO_NORMAL extends State

// @formatter:on

class AuthHandler(them: ActorRef) extends LoggingFSM[State, Data] with Stash {

  import AuthHandler._

  val node_id = randomKeyPair()
  val session_key = randomKeyPair()

  them ! Write(ByteString.fromArray(session_key.pub))
  startWith(IO_WAITING_FOR_SESSION_KEY, Nothing)

  when(IO_WAITING_FOR_SESSION_KEY) {
    case Event(Received(data), _) =>
      val theirpub = BinaryData(data)
      log.info(s"received pubkey $theirpub")
      val secrets_in = generate_secrets(ecdh(theirpub, session_key.priv), theirpub)
      val secrets_out = generate_secrets(ecdh(theirpub, session_key.priv), session_key.pub)
      log.info(s"generated secrets_in=$secrets_in secrets_out=$secrets_out")
      val cipher_in = aesDecryptCipher(secrets_in.aes_key, secrets_in.aes_iv)
      val cipher_out = aesEncryptCipher(secrets_out.aes_key, secrets_out.aes_iv)
      val our_auth = pkt(Auth(lightning.authenticate(node_id.pub, bin2signature(Crypto.encodeSignature(Crypto.sign(Crypto.hash256(theirpub), node_id.priv))))))
      val (d, new_totlen_out) = writeMsg(our_auth, secrets_out, cipher_out, 0)
      them ! Write(ByteString.fromArray(d))
      goto(IO_WAITING_FOR_AUTH) using SessionData(theirpub, secrets_in, secrets_out, cipher_in, cipher_out, 0, new_totlen_out, BinaryData(Seq()))
  }

  when(IO_WAITING_FOR_AUTH) {
    case Event(Received(chunk), s@SessionData(theirpub, secrets_in, secrets_out, cipher_in, cipher_out, totlen_in, totlen_out, acc_in)) =>
      log.info(s"received chunk=${BinaryData(chunk)}")
      val (rest, new_totlen_in) = split(acc_in ++ chunk, secrets_in, cipher_in, totlen_in, m => self ! pkt.parseFrom(m))
      stay using s.copy(totlen_in = new_totlen_in, acc_in = rest)

    case Event(auth: pkt, s: SessionData) if auth.pkt.isAuth =>
      log.info(s"received authenticate: $auth")
      log.info(s"nodeid: ${BinaryData(auth.getAuth.nodeId.key.toByteArray)}")
      log.info(s"sig: ${BinaryData(signature2bin(auth.getAuth.sessionSig))}")
      assert(Crypto.verifySignature(Crypto.hash256(session_key.pub), signature2bin(auth.getAuth.sessionSig), pubkey2bin(auth.getAuth.nodeId)), "auth failed")
      log.info(s"initializing channel actor")
      val anchorInput = AnchorInput(100100000L, OutPoint(Hex.decode("7727730d21428276a4d6b0e16f3a3e6f3a07a07dc67151e6a88d4a8c3e8edb24").reverse, 1), SignData("76a914e093fbc19866b98e0fbc25d79d0ad0f0375170af88ac", Base58Check.decode("cU1YgK56oUKAtV6XXHZeJQjEx1KGXkZS1pGiKpyW4mUyKYFJwWFg")._2))
      val alice_commit_priv = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")._2
      val alice_final_priv = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")._2
      val alice_params = OurChannelParams(locktime(Seconds(86400)), alice_commit_priv, alice_final_priv, 1, 50000, "alice-seed".getBytes())
      val mockCoreClient = new BitcoinJsonRPCClient("foo", "bar") {
        override def invoke(method: String, params: Any*): Future[JValue] = method match {
          case "getrawtransaction" => Future.successful(JObject(("confirmations", JInt(100))))
          case "gettxout" => Future.successful(JObject())
          case _ => ???
        }
      }
      val blockchain = context.system.actorOf(Props(new PollingWatcher(mockCoreClient)), name = "blockchain")
      val channel = context.system.actorOf(Props(new Channel(blockchain, alice_params, Some(anchorInput))), name = "alice")
      channel ! INPUT_NONE
      goto(IO_NORMAL) using Normal(channel, s)
  }

  when(IO_NORMAL) {
    case Event(Received(chunk), n@Normal(channel, s@SessionData(theirpub, secrets_in, secrets_out, cipher_in, cipher_out, totlen_in, totlen_out, acc_in))) =>
      log.info(s"received chunk=${BinaryData(chunk)}")
      val (rest, new_totlen_in) = split(acc_in ++ chunk, secrets_in, cipher_in, totlen_in, m => self ! pkt.parseFrom(m))
      stay using n.copy(sessionData = s.copy(totlen_in = new_totlen_in, acc_in = rest))

    case Event(packet: pkt, n@Normal(channel, s@SessionData(theirpub, secrets_in, secrets_out, cipher_in, cipher_out, totlen_in, totlen_out, acc_in))) =>
      log.info(s">>>>>> $packet")
      packet.pkt match {
        case Open(o) => channel ! o
        case OpenCommitSig(o) => channel ! o
      }
      stay

    case Event(msg: GeneratedMessage, n@Normal(channel, s@SessionData(theirpub, secrets_in, secrets_out, cipher_in, cipher_out, totlen_in, totlen_out, acc_in))) =>
      val packet = msg match {
        case o: open_channel => pkt(Open(o))
        case o: open_anchor => pkt(OpenAnchor(o))
      }
      log.info(s"<<<<<< $packet")
      val (data, new_totlen_out) = writeMsg(packet, secrets_out, cipher_out, totlen_out)
      them ! Write(ByteString.fromArray(data))
      stay using n.copy(sessionData = s.copy(totlen_out = new_totlen_out))
  }

  initialize()
}