package fr.acinq.eclair.crypto

import akka.util.ByteString
import fr.acinq.bitcoin.{BinaryData, Protocol}
import fr.acinq.eclair.crypto.LightningCrypto.AeadChacha20Poly1305

import scala.annotation.tailrec

/**
  * message format used by lightning:
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

case class Encryptor(key: BinaryData, nonce: Long)

object Encryptor {
  def encrypt(encryptor: Encryptor, data: BinaryData): (Encryptor, BinaryData) = {
    val header = Protocol.writeUInt32(data.length)
    val (ciphertext1, mac1) = AeadChacha20Poly1305.encrypt(encryptor.key, Protocol.writeUInt64(encryptor.nonce), header, Array.emptyByteArray)
    val (ciphertext2, mac2) = AeadChacha20Poly1305.encrypt(encryptor.key, Protocol.writeUInt64(encryptor.nonce + 1), data, Array.emptyByteArray)
    (encryptor.copy(nonce = encryptor.nonce + 2), ciphertext1 ++ mac1 ++ ciphertext2 ++ mac2)
  }
}

case class Decryptor(key: BinaryData, nonce: Long, buffer: ByteString = ByteString.empty, header: Option[Decryptor.Header] = None, bodies: Vector[BinaryData] = Vector.empty[BinaryData])

object Decryptor {

  case class Header(length: Int)

  @tailrec
  def add(state: Decryptor, data: ByteString): Decryptor = {
    if (data.isEmpty) state
    else state match {
      case Decryptor(key, nonce, buffer, None, _) =>
        val buffer1 = buffer ++ data
        if (buffer1.length >= 20) {
          val plaintext = AeadChacha20Poly1305.decrypt(key, Protocol.writeUInt64(nonce), buffer1.take(4), Array.emptyByteArray, buffer1.drop(4).take(16))
          val length = Protocol.uint32(plaintext.take(4)).toInt
          val state1 = state.copy(header = Some(Header(length)), nonce = nonce + 1, buffer = ByteString.empty)
          add(state1, buffer1.drop(20))
        }
        else state.copy(buffer = buffer1)
      case Decryptor(key, nonce, buffer, Some(header), bodies) =>
        val buffer1 = buffer ++ data
        if (buffer1.length >= (header.length + 16)) {
          val plaintext = AeadChacha20Poly1305.decrypt(key, Protocol.writeUInt64(nonce), buffer1.take(header.length), Array.emptyByteArray, buffer1.drop(header.length).take(16))
          val state1 = state.copy(nonce = nonce + 1, header = None, bodies = bodies :+ plaintext, buffer = ByteString.empty)
          add(state1, buffer1.drop(header.length + 16))
        } else state.copy(buffer = buffer1)
    }
  }

  def add(state: Decryptor, data: BinaryData): Decryptor = add(state, ByteString.fromArray(data))
}