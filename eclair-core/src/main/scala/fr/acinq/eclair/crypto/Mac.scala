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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.params.KeyParameter
import scodec.bits.ByteVector

/**
 * Created by t-bast on 04/07/19.
 */

/**
 * Create and verify message authentication codes.
 */
trait Mac32 {

  def mac(message: ByteVector): ByteVector32

  def verify(mac: ByteVector32, message: ByteVector): Boolean

}

case class Hmac256(key: ByteVector) extends Mac32 {

  override def mac(message: ByteVector): ByteVector32 = Mac32.hmac256(key, message)

  override def verify(mac: ByteVector32, message: ByteVector): Boolean = this.mac(message) == mac

}

object Mac32 {

  def hmac256(key: ByteVector, message: ByteVector): ByteVector32 = {
    val mac = new HMac(new SHA256Digest())
    mac.init(new KeyParameter(key.toArray))
    mac.update(message.toArray, 0, message.length.toInt)
    val output = new Array[Byte](32)
    mac.doFinal(output, 0)
    ByteVector32(ByteVector.view(output))
  }

}