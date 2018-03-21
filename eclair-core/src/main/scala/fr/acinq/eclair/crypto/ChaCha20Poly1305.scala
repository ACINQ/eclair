/*
 * Copyright 2018 ACINQ SAS
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

import java.nio.ByteOrder

import fr.acinq.bitcoin.{BinaryData, Protocol}
import grizzled.slf4j.Logging
import org.spongycastle.crypto.engines.{ChaCha7539Engine, ChaChaEngine}
import org.spongycastle.crypto.params.{KeyParameter, ParametersWithIV}

/**
  * Poly1305 authenticator
  * see https://tools.ietf.org/html/rfc7539#section-2.5
  */
object Poly1305 {
  /**
    *
    * @param key  input key
    * @param data input data
    * @return a 16 byte authentication tag
    */
  def mac(key: BinaryData, data: BinaryData): BinaryData = {
    val out = new Array[Byte](16)
    val poly = new org.spongycastle.crypto.macs.Poly1305()
    poly.init(new KeyParameter(key))
    poly.update(data, 0, data.length)
    poly.doFinal(out, 0)
    out
  }
}

/**
  * ChaCha20 block cipher
  * see https://tools.ietf.org/html/rfc7539#section-2.5
  */
object ChaCha20 {

  def encrypt(plaintext: BinaryData, key: BinaryData, nonce: BinaryData, counter: Int = 0): BinaryData = {
    val engine = new ChaCha7539Engine()
    engine.init(true, new ParametersWithIV(new KeyParameter(key), nonce))
    val ciphertext: BinaryData = new Array[Byte](plaintext.length)
    counter match {
      case 0 => ()
      case 1 =>
        // skip 1 block == set counter to 1 instead of 0
        val dummy = new Array[Byte](64)
        engine.processBytes(new Array[Byte](64), 0, 64, dummy, 0)
      case _ => throw new RuntimeException(s"chacha20 counter must be 0 or 1")
    }
    val len = engine.processBytes(plaintext.toArray, 0, plaintext.length, ciphertext, 0)
    require(len == plaintext.length, "ChaCha20 encryption failed")
    ciphertext
  }

  def decrypt(ciphertext: BinaryData, key: BinaryData, nonce: BinaryData, counter: Int = 0): BinaryData = {
    val engine = new ChaCha7539Engine
    engine.init(false, new ParametersWithIV(new KeyParameter(key), nonce))
    val plaintext: BinaryData = new Array[Byte](ciphertext.length)
    counter match {
      case 0 => ()
      case 1 =>
        // skip 1 block == set counter to 1 instead of 0
        val dummy = new Array[Byte](64)
        engine.processBytes(new Array[Byte](64), 0, 64, dummy, 0)
      case _ => throw new RuntimeException(s"chacha20 counter must be 0 or 1")
    }
    val len = engine.processBytes(ciphertext.toArray, 0, ciphertext.length, plaintext, 0)
    require(len == ciphertext.length, "ChaCha20 decryption failed")
    plaintext
  }
}

/**
  * ChaCha20Poly1305 AEAD (Authenticated Encryption with Additional Data) algorithm
  * see https://tools.ietf.org/html/rfc7539#section-2.5
  *
  * This what we should be using (see BOLT #8)
  */
object ChaCha20Poly1305 extends Logging {
  /**
    *
    * @param key       32 bytes encryption key
    * @param nonce     12 bytes nonce
    * @param plaintext plain text
    * @param aad       additional authentication data. can be empty
    * @return a (ciphertext, mac) tuple
    */
  def encrypt(key: BinaryData, nonce: BinaryData, plaintext: BinaryData, aad: BinaryData): (BinaryData, BinaryData) = {
    val polykey: BinaryData = ChaCha20.encrypt(new Array[Byte](32), key, nonce)
    val ciphertext = ChaCha20.encrypt(plaintext, key, nonce, 1)
    val data = aad ++ pad16(aad) ++ ciphertext ++ pad16(ciphertext) ++ Protocol.writeUInt64(aad.length, ByteOrder.LITTLE_ENDIAN) ++ Protocol.writeUInt64(ciphertext.length, ByteOrder.LITTLE_ENDIAN)
    val tag = Poly1305.mac(polykey, data)
    logger.debug(s"encrypt($key, $nonce, $aad, $plaintext) = ($ciphertext, $tag)")
    (ciphertext, tag)
  }

  /**
    *
    * @param key        32 bytes decryption key
    * @param nonce      12 bytes nonce
    * @param ciphertext ciphertext
    * @param aad        additional authentication data. can be empty
    * @param mac        authentication mac
    * @return the decrypted plaintext if the mac is valid.
    */
  def decrypt(key: BinaryData, nonce: BinaryData, ciphertext: BinaryData, aad: BinaryData, mac: BinaryData): BinaryData = {
    val polykey: BinaryData = ChaCha20.encrypt(new Array[Byte](32), key, nonce)
    val data = aad ++ pad16(aad) ++ ciphertext ++ pad16(ciphertext) ++ Protocol.writeUInt64(aad.length, ByteOrder.LITTLE_ENDIAN) ++ Protocol.writeUInt64(ciphertext.length, ByteOrder.LITTLE_ENDIAN)
    val tag = Poly1305.mac(polykey, data)
    require(tag == mac, "invalid mac")
    val plaintext = ChaCha20.decrypt(ciphertext, key, nonce, 1)
    logger.debug(s"decrypt($key, $nonce, $aad, $ciphertext, $mac) = $plaintext")
    plaintext
  }

  def pad16(data: Seq[Byte]): Seq[Byte] =
    if (data.size % 16 == 0)
      Seq.empty[Byte]
    else
      Seq.fill[Byte](16 - (data.size % 16))(0)
}

object ChaCha20Legacy {
  def encrypt(plaintext: BinaryData, key: BinaryData, nonce: BinaryData, counter: Int = 0): BinaryData = {
    val engine = new ChaChaEngine(20)
    engine.init(true, new ParametersWithIV(new KeyParameter(key), nonce))
    val ciphertext: BinaryData = new Array[Byte](plaintext.length)
    counter match {
      case 0 => ()
      case 1 =>
        // skip 1 block == set counter to 1 instead of 0
        val dummy = new Array[Byte](64)
        engine.processBytes(new Array[Byte](64), 0, 64, dummy, 0)
      case _ => throw new RuntimeException(s"chacha20 counter must be 0 or 1")
    }
    val len = engine.processBytes(plaintext.toArray, 0, plaintext.length, ciphertext, 0)
    require(len == plaintext.length, "ChaCha20Legacy encryption failed")
    ciphertext
  }

  def decrypt(ciphertext: BinaryData, key: BinaryData, nonce: BinaryData, counter: Int = 0): BinaryData = {
    val engine = new ChaChaEngine(20)
    engine.init(false, new ParametersWithIV(new KeyParameter(key), nonce))
    val plaintext: BinaryData = new Array[Byte](ciphertext.length)
    counter match {
      case 0 => ()
      case 1 =>
        // skip 1 block == set counter to 1 instead of 0
        val dummy = new Array[Byte](64)
        engine.processBytes(new Array[Byte](64), 0, 64, dummy, 0)
      case _ => throw new RuntimeException(s"chacha20 counter must be 0 or 1")
    }
    val len = engine.processBytes(ciphertext.toArray, 0, ciphertext.length, plaintext, 0)
    require(len == ciphertext.length, "ChaCha20Legacy decryption failed")
    plaintext
  }
}

/**
  * Legacy implementation of ChaCha20Poly1305
  * Nonce is 8 bytes instead of 12 and the output tag computation is different
  *
  * Used in our first interop tests with lightning-c, should not be needed anymore
  */
object Chacha20Poly1305Legacy {
  def encrypt(key: BinaryData, nonce: BinaryData, plaintext: BinaryData, aad: BinaryData): (BinaryData, BinaryData) = {
    val polykey: BinaryData = ChaCha20Legacy.encrypt(new Array[Byte](32), key, nonce)
    val ciphertext = ChaCha20Legacy.encrypt(plaintext, key, nonce, 1)
    val data = aad ++ Protocol.writeUInt64(aad.length, ByteOrder.LITTLE_ENDIAN) ++ ciphertext ++ Protocol.writeUInt64(ciphertext.length, ByteOrder.LITTLE_ENDIAN)
    val tag = Poly1305.mac(polykey, data)
    (ciphertext, tag)
  }

  def decrypt(key: BinaryData, nonce: BinaryData, ciphertext: BinaryData, aad: BinaryData, mac: BinaryData): BinaryData = {
    val polykey: BinaryData = ChaCha20Legacy.encrypt(new Array[Byte](32), key, nonce)
    val data = aad ++ Protocol.writeUInt64(aad.length, ByteOrder.LITTLE_ENDIAN) ++ ciphertext ++ Protocol.writeUInt64(ciphertext.length, ByteOrder.LITTLE_ENDIAN)
    val tag = Poly1305.mac(polykey, data)
    require(tag == mac, "invalid mac")
    val plaintext = ChaCha20Legacy.decrypt(ciphertext, key, nonce, 1)
    plaintext
  }
}
