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

package fr.acinq.eclair

import com.google.common.primitives.UnsignedLongs
import scodec.bits.ByteVector
import scodec.bits._

case class UInt64(private val underlying: Long) extends Ordered[UInt64] {

  override def compare(o: UInt64): Int = UnsignedLongs.compare(underlying, o.underlying)

  def toByteVector: ByteVector = this match {
    case x if x <= UInt64(255) => ByteVector.fromLong(underlying, size = 1)
    case x if x <= UInt64(65535) => ByteVector.fromLong(underlying, size = 2)
    case x if x <= UInt64(16777215) => ByteVector.fromLong(underlying, size = 3)
    case x if x <= UInt64(4294967295L) => ByteVector.fromLong(underlying, size = 4)
    case x if x <= UInt64(1099511627775L) => ByteVector.fromLong(underlying, size = 5)
    case x if x <= UInt64(281474976710655L) => ByteVector.fromLong(underlying, size = 6)
    case x if x <= UInt64(72057594037927935L) => ByteVector.fromLong(underlying, size = 7)
    case _ => ByteVector.fromLong(underlying, size = 8)
  }

  def toBigInt: BigInt = BigInt(toString)

  def toLong: Long = underlying

  override def toString: String = UnsignedLongs.toString(underlying, 10)
}

object UInt64 {

  val MaxValue = UInt64(hex"0xffffffffffffffff")

  def apply(bin: ByteVector): UInt64 = UInt64(bin.toLong(signed = false))

  object Conversions {

    implicit def intToUint64(l: Int) = UInt64(l)

    implicit def longToUint64(l: Long) = UInt64(l)
  }
}
