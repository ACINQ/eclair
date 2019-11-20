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
import scodec.bits.HexStringSyntax

case class UInt64(private val underlying: Long) extends Ordered[UInt64] {

  override def compare(o: UInt64): Int = UnsignedLongs.compare(underlying, o.underlying)
  private def compare(other: MilliSatoshi): Int = other.toLong match {
    case l if l < 0 => 1                    // if @param 'other' is negative then is always smaller than 'this'
    case _ => compare(UInt64(other.toLong)) // we must do an unsigned comparison here because the uint64 can exceed the capacity of MilliSatoshi class
  }

  def <(other: MilliSatoshi): Boolean = compare(other) < 0
  def >(other: MilliSatoshi): Boolean = compare(other) > 0
  def <=(other: MilliSatoshi): Boolean = compare(other) <= 0
  def >=(other: MilliSatoshi): Boolean = compare(other) >= 0

  def toByteVector: ByteVector = ByteVector.fromLong(underlying)

  def toBigInt: BigInt = (BigInt(underlying >>> 1) << 1) + (underlying & 1)

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
