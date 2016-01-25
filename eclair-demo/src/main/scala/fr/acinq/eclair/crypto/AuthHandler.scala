package fr.acinq.eclair.crypto

import akka.actor._
import akka.io.Tcp.{Received, Write}
import akka.util.ByteString
import fr.acinq.bitcoin.{Base58Check, BinaryData, Crypto, Protocol}
import fr.acinq.eclair._
import LightningCrypto._
import lightning.locktime.Locktime.Blocks
import lightning.open_channel.anchor_offer.WONT_CREATE_ANCHOR
import lightning.pkt.Pkt.{Open, Auth}
import lightning._

import scala.annotation.tailrec


/**
 * Created by PM on 27/10/2015.
 */

object AuthHandler {

  case class Secrets(aes_key: BinaryData, hmac_key: BinaryData, aes_iv: BinaryData)

  def generate_secrets(ecdh_key: BinaryData, pub: BinaryData): Secrets = {
    val aes_key =  Crypto.sha256(ecdh_key ++ pub :+ 0x00.toByte).take(16)
    val hmac_key =  Crypto.sha256(ecdh_key ++ pub :+ 0x01.toByte)
    val aes_iv =  Crypto.sha256(ecdh_key ++ pub :+ 0x02.toByte).take(16)
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

  def writeMsg(msg: pkt, secrets: Secrets, totlen_prev: Long): (BinaryData, Long) = {
    val buf = pkt.toByteArray(msg)
    val enclen = round16(buf.length)
    val enc = aesEncrypt(buf.padTo(enclen, 0x00: Byte), secrets.aes_key, secrets.aes_iv)
    val totlen = totlen_prev + buf.length
    val totlen_bin = Protocol.writeUInt64(totlen)
    (hmac256(secrets.hmac_key, totlen_bin ++ enc) ++ totlen_bin ++ enc, totlen)
  }

  /**
   * splits buffer in separate msg
    *
    * @param data raw data received (possibly one, multiple or fractions of messages)
   * @param totlen_prev
   * @param f will be applied to full messages
   * @return rest of the buffer (incomplete msg)
   */
  @tailrec
  def split(data: BinaryData, secrets: Secrets, totlen_prev: Long, f: BinaryData => Unit): (BinaryData, Long) = {
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
        val dec = aesDecrypt(data.slice(32 + 8, 32 + 8 + enclen).toArray, secrets.aes_key, secrets.aes_iv)
        f(dec.take(len))
        split(splitted._2, secrets, totlen_prev + len, f)
      }
    }
  }
}

class AuthHandler(them: ActorRef) extends Actor with ActorLogging {

  import AuthHandler._

  val session_key = randomKeyPair()

  log.info(s"session pubkey=${session_key.pub}")

  override def receive: Receive = {
    case 'init => them ! Write(ByteString.fromArray(session_key.pub))
    case Received(data) if data.size == 33 =>
      val theirpub = BinaryData(data)
      log.info(s"received pubkey $theirpub")
      val secrets_in = generate_secrets(ecdh(theirpub, session_key.priv), theirpub)
      val secrets_out = generate_secrets(ecdh(theirpub, session_key.priv), session_key.pub)
      log.info(s"generated secrets_in=$secrets_in secrets_out=$secrets_out")
      val mynodeid = randomKeyPair()
      val (d, new_totlen_out) = writeMsg(pkt(Auth(lightning.authenticate(mynodeid.pub, bin2signature(Crypto.encodeSignature(Crypto.sign(Crypto.hash256(theirpub), mynodeid.priv)))))), secrets_out, 0)
      them ! Write(ByteString.fromArray(d))
      context.become(authenticate(them, theirpub, secrets_in, secrets_out, 0, new_totlen_out, BinaryData(Seq())))
    case other =>
      log.warning(s"ignored $other")
  }

  def authenticate(them: ActorRef, theirpub: BinaryData, secrets_in: Secrets, secrets_out: Secrets, totlen_in: Long, totlen_out: Long, acc_in: BinaryData): Receive = {
    case Received(chunk) =>
      log.info(s"received chunk=${BinaryData(chunk)}")
      val (rest, new_totlen_in) = split(acc_in ++ chunk, secrets_in, totlen_in, m => self ! pkt.parseFrom(m))
      context.become(authenticate(them, theirpub, secrets_in, secrets_out, new_totlen_in, totlen_out, rest))

    case auth: pkt if auth.pkt.isAuth =>
      log.info(s"received authenticate: $auth")
      log.info(s"nodeid: ${BinaryData(auth.getAuth.nodeId.key.toByteArray)}")
      log.info(s"sig: ${BinaryData(signature2bin(auth.getAuth.sessionSig))}")
      //assert(Crypto.verifySignature(Crypto.hash256(session_key.pub), signature2bin(auth.getAuth.sessionSig), pubkey2bin(auth.getAuth.nodeId)), "auth failed")

      val alice_commit_priv = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")._2
      val alice_final_priv = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")._2
      val ourCommitPubKey = bitcoin_pubkey(com.google.protobuf.ByteString.copyFrom(Crypto.publicKeyFromPrivateKey(alice_commit_priv.key.toByteArray)))
      val ourFinalPubKey = bitcoin_pubkey(com.google.protobuf.ByteString.copyFrom(Crypto.publicKeyFromPrivateKey(alice_final_priv.key.toByteArray)))
      val rev = sha256_hash(7, 7, 7, 7)
      val revHash = Crypto.sha256(rev)
      self ! pkt(Open(open_channel(locktime(Blocks(10)), revHash, alice_commit_priv, ourFinalPubKey, WONT_CREATE_ANCHOR, Some(10), 10000)))
      context.become(mainloop(them, auth.getAuth.nodeId, theirpub, secrets_in, secrets_out, totlen_in, totlen_out, acc_in))

    case msg => log.warning(s"unhandled $msg")
  }

  def mainloop(them: ActorRef, nodeid: bitcoin_pubkey, theirpub: BinaryData, secrets_in: Secrets, secrets_out: Secrets, totlen_in: Long, totlen_out: Long, acc_in: BinaryData): Receive = {
    case Received(chunk) =>
      log.info(s"received chunk=${BinaryData(chunk)}")
      val (rest, new_totlen_in) = split(acc_in ++ chunk, secrets_in, totlen_in, m => self ! m)
      context.become(mainloop(them, nodeid, theirpub, secrets_in, secrets_out, new_totlen_in, totlen_out, rest))

    case data: BinaryData =>
      val msg = pkt.parseFrom(data)
      log.info(s"received $msg")
      context.become(mainloop(them, nodeid, theirpub, secrets_in, secrets_out, totlen_in, totlen_out, acc_in))

    case msg: pkt =>
      log.info(s"sending msg=$msg")
      val (data, new_totlen_out) = writeMsg(msg, secrets_out, totlen_out)
      them ! Write(ByteString.fromArray(data))
      context.become(mainloop(them, nodeid, theirpub, secrets_in, secrets_out, totlen_in, new_totlen_out, acc_in))
  }

}