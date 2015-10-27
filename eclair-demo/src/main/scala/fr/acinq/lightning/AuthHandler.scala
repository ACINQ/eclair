package fr.acinq.lightning

import akka.actor._
import akka.io.Tcp.{Received, Write}
import akka.util.ByteString

import LightningCrypto._
import fr.acinq.bitcoin.{BinaryData, Protocol, Crypto}
import lightning.{error, pkt}


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

  def writeMsg(msg: pkt, secrets: Secrets): BinaryData = {
    val buf = pkt.toByteArray(msg)
    val enclen = buf.length % 16 match {
      case 0 => buf.length
      case x => buf.length + 16 - x
    }
    val enc = aesEncrypt(buf.padTo(enclen, 0x00: Byte), secrets.aes_key, secrets.aes_iv)
    val len = Protocol.writeUInt64(8 + buf.length) // msg length + length itself as long
    hmac256(secrets.hmac_key, len ++ enc) ++ len ++ enc
  }

  def readMsg(data: BinaryData, secrets: Secrets): pkt = {
    val refsig = data.take(32)
    val sig = hmac256(secrets.hmac_key, data.drop(32))
    assert(sig.data.sameElements(refsig), "sig mismatch!")
    val totlen = Protocol.uint64(data.slice(32, 32 + 8)).asInstanceOf[Int]
    val dec = aesDecrypt(data.slice(32+8, 32 + totlen).toArray, secrets.aes_key, secrets.aes_iv)
    pkt.parseFrom(dec)
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
      val enc_secrets = generate_secrets(ecdh(theirpub, session_key.priv), session_key.pub)
      val dec_secrets = generate_secrets(ecdh(theirpub, session_key.priv), theirpub)
      log.info(s"generated enc_secrets=$enc_secrets dec_secrets=$dec_secrets")
      self ! pkt().withError(error(Some("hello")))
      context.become(mainloop(them, enc_secrets, dec_secrets))
    case other =>
      log.warning(s"ignored $other")
  }

  def mainloop(them: ActorRef, enc_secrets: Secrets, dec_secrets: Secrets): Receive = {
    case Received(data) =>
      val msg = readMsg(data, dec_secrets)
      log.info(s"received $msg")

    case msg: pkt =>
      log.info(s"sending $msg")
      them ! Write(ByteString.fromArray(writeMsg(msg, enc_secrets)))
  }

}