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

import fr.acinq.bitcoin.scalacompat.{ByteVector32, Protocol}
import fr.acinq.eclair.crypto.ChaCha20Poly1305.{DecryptionError, EncryptionError, InvalidCounter}
import grizzled.slf4j.Logging
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.params.{KeyParameter, ParametersWithIV}
import scodec.bits.ByteVector

import java.nio.ByteOrder

/**
 * Poly1305 authenticator
 * see https://tools.ietf.org/html/rfc7539#section-2.5
 */
object Poly1305 {
  /**
   * @param key   input key
   * @param datas input data
   * @return a 16 byte authentication tag
   */
  def mac(key: ByteVector, datas: ByteVector*): ByteVector = {
    val out = new Array[Byte](16)
    val poly = new org.bouncycastle.crypto.macs.Poly1305()
    poly.init(new KeyParameter(key.toArray))
    datas.foreach(data => poly.update(data.toArray, 0, data.length.toInt))
    poly.doFinal(out, 0)
    ByteVector.view(out)
  }
}

/**
 * ChaCha20 block cipher
 * see https://tools.ietf.org/html/rfc7539#section-2.5
 */
object ChaCha20 {
  def encrypt(plaintext: ByteVector, key: ByteVector, nonce: ByteVector, counter: Int = 0): ByteVector = {
    val engine = new ChaCha7539Engine()
    engine.init(true, new ParametersWithIV(new KeyParameter(key.toArray), nonce.toArray))
    val ciphertext: Array[Byte] = new Array[Byte](plaintext.length.toInt)
    counter match {
      case 0 => ()
      case 1 =>
        // skip 1 block == set counter to 1 instead of 0
        val dummy = new Array[Byte](64)
        engine.processBytes(new Array[Byte](64), 0, 64, dummy, 0)
      case _ => throw InvalidCounter()
    }
    val len = engine.processBytes(plaintext.toArray, 0, plaintext.length.toInt, ciphertext, 0)
    if (len != plaintext.length) throw EncryptionError()
    ByteVector.view(ciphertext)
  }

  def decrypt(ciphertext: ByteVector, key: ByteVector, nonce: ByteVector, counter: Int = 0): ByteVector = {
    val engine = new ChaCha7539Engine
    engine.init(false, new ParametersWithIV(new KeyParameter(key.toArray), nonce.toArray))
    val plaintext: Array[Byte] = new Array[Byte](ciphertext.length.toInt)
    counter match {
      case 0 => ()
      case 1 =>
        // skip 1 block == set counter to 1 instead of 0
        val dummy = new Array[Byte](64)
        engine.processBytes(new Array[Byte](64), 0, 64, dummy, 0)
      case _ => throw InvalidCounter()
    }
    val len = engine.processBytes(ciphertext.toArray, 0, ciphertext.length.toInt, plaintext, 0)
    if (len != ciphertext.length) throw DecryptionError()
    ByteVector.view(plaintext)
  }
}

/**
 * ChaCha20Poly1305 AEAD (Authenticated Encryption with Additional Data) algorithm
 * see https://tools.ietf.org/html/rfc7539#section-2.5
 *
 * This what we should be using (see BOLT #8)
 */
object ChaCha20Poly1305 extends Logging {

  // @formatter:off
  abstract class ChaCha20Poly1305Error(msg: String) extends RuntimeException(msg)
  case class InvalidMac() extends ChaCha20Poly1305Error("invalid mac")
  case class DecryptionError() extends ChaCha20Poly1305Error("decryption error")
  case class EncryptionError() extends ChaCha20Poly1305Error("encryption error")
  case class InvalidCounter() extends ChaCha20Poly1305Error("chacha20 counter must be 0 or 1")
  // @formatter:on

  /**
   * @param key       32 bytes encryption key
   * @param nonce     12 bytes nonce
   * @param plaintext plain text
   * @param aad       additional authentication data. can be empty
   * @return a (ciphertext, mac) tuple
   */
  def encrypt(key: ByteVector, nonce: ByteVector, plaintext: ByteVector, aad: ByteVector): (ByteVector, ByteVector) = {
    val polykey = ChaCha20.encrypt(ByteVector32.Zeroes, key, nonce)
    val ciphertext = ChaCha20.encrypt(plaintext, key, nonce, 1)
    val tag = Poly1305.mac(polykey, aad, pad16(aad), ciphertext, pad16(ciphertext), Protocol.writeUInt64(aad.length, ByteOrder.LITTLE_ENDIAN), Protocol.writeUInt64(ciphertext.length, ByteOrder.LITTLE_ENDIAN))
    logger.debug(s"encrypt($key, $nonce, $aad, $plaintext) = ($ciphertext, $tag)")
    (ciphertext, tag)
  }

  /**
   * @param key        32 bytes decryption key
   * @param nonce      12 bytes nonce
   * @param ciphertext ciphertext
   * @param aad        additional authentication data. can be empty
   * @param mac        authentication mac
   * @return the decrypted plaintext if the mac is valid.
   */
  def decrypt(key: ByteVector, nonce: ByteVector, ciphertext: ByteVector, aad: ByteVector, mac: ByteVector): ByteVector = {
    val polykey = ChaCha20.encrypt(ByteVector32.Zeroes, key, nonce)
    val tag = Poly1305.mac(polykey, aad, pad16(aad), ciphertext, pad16(ciphertext), Protocol.writeUInt64(aad.length, ByteOrder.LITTLE_ENDIAN), Protocol.writeUInt64(ciphertext.length, ByteOrder.LITTLE_ENDIAN))
    if (tag != mac) throw InvalidMac()
    val plaintext = ChaCha20.decrypt(ciphertext, key, nonce, 1)
    logger.debug(s"decrypt($key, $nonce, $aad, $ciphertext, $mac) = $plaintext")
    plaintext
  }

  def pad16(data: ByteVector): ByteVector =
    if (data.size % 16 == 0)
      ByteVector.empty
    else
      ByteVector.fill(16 - (data.size % 16))(0)
}

