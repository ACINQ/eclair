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

package fr.acinq.eclair.wire

import scodec.bits.{BitVector, ByteVector}
import scodec.{Attempt, Codec, DecodeResult, Err, SizeBound, codecs}

/**
  *
  * REMOVE THIS A NEW VERSION OF SCODEC IS RELEASED THAT INCLUDES CHANGES MADE IN
  * https://github.com/scodec/scodec/pull/99/files
  *
  * Created by PM on 02/06/2017.
  */
final class FixedSizeStrictCodec[A](size: Long, codec: Codec[A]) extends Codec[A] {

  override def sizeBound = SizeBound.exact(size)

  override def encode(a: A) = for {
    encoded <- codec.encode(a)
    result <- {
      if (encoded.size != size)
        Attempt.failure(Err(s"[$a] requires ${encoded.size} bits but field is fixed size of exactly $size bits"))
      else
        Attempt.successful(encoded.padTo(size))
    }
  } yield result

  override def decode(buffer: BitVector) = {
    if (buffer.size == size) {
      codec.decode(buffer.take(size)) map { res =>
        DecodeResult(res.value, buffer.drop(size))
      }
    } else {
      Attempt.failure(Err(s"expected exactly $size bits but got ${buffer.size} bits"))
    }
  }

  override def toString = s"fixedSizeBitsStrict($size, $codec)"
}

object FixedSizeStrictCodec {
  /**
    * Encodes by returning the supplied byte vector if its length is `size` bytes, otherwise returning error;
    * decodes by taking `size * 8` bits from the supplied bit vector and converting to a byte vector.
    *
    * @param size number of bits to encode/decode
    * @group bits
    */
  def bytesStrict(size: Int): Codec[ByteVector] = new Codec[ByteVector] {
    private val codec = new FixedSizeStrictCodec(size * 8L, codecs.bits).xmap[ByteVector](_.toByteVector, _.toBitVector)

    def sizeBound = codec.sizeBound

    def encode(b: ByteVector) = codec.encode(b)

    def decode(b: BitVector) = codec.decode(b)

    override def toString = s"bytesStrict($size)"
  }
}