/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.remote

import java.nio.ByteBuffer

import akka.serialization.{ByteBufferSerializer, SerializerWithStringManifest}
import scodec.Codec
import scodec.bits.BitVector

class ScodecSerializer[T <: AnyRef](override val identifier: Int, codec: Codec[T]) extends SerializerWithStringManifest with ByteBufferSerializer {

  override def toBinary(o: AnyRef, buf: ByteBuffer): Unit = buf.put(toBinary(o))

  override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef = {
    val bytes = Array.ofDim[Byte](buf.remaining())
    buf.get(bytes)
    fromBinary(bytes, manifest)
  }

  /** we don't rely on the manifest to provide backward compatibility, we will use dedicated codecs instead */
  override def manifest(o: AnyRef): String = fr.acinq.eclair.getSimpleClassName(o)

  override def toBinary(o: AnyRef): Array[Byte] = codec.encode(o.asInstanceOf[T]).require.toByteArray

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = codec.decode(BitVector(bytes)).require.value
}
