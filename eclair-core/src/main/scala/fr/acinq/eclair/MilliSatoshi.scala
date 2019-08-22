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

import fr.acinq.bitcoin.{Btc, BtcAmount, MilliBtc, Satoshi, btc2satoshi, millibtc2satoshi}

/**
 * Created by t-bast on 22/08/2019.
 */

/**
 * One MilliSatoshi is a thousand of a Satoshi, the smallest unit usable in bitcoin
 */
case class MilliSatoshi(private val underlying: Long) extends Ordered[MilliSatoshi] {

  // @formatter:off
  def +(other: MilliSatoshi) = MilliSatoshi(underlying + other.underlying)
  def +(other: BtcAmount) = MilliSatoshi(underlying + other.toMilliSatoshi.underlying)
  def -(other: MilliSatoshi) = MilliSatoshi(underlying - other.underlying)
  def -(other: BtcAmount) = MilliSatoshi(underlying - other.toMilliSatoshi.underlying)
  def *(m: Long) = MilliSatoshi(underlying * m)
  def *(m: Double) = MilliSatoshi((underlying * m).toLong)
  def /(d: Long) = MilliSatoshi(underlying / d)
  def unary_-() = MilliSatoshi(-underlying)
  override def compare(other: MilliSatoshi): Int = underlying.compareTo(other.underlying)
  // Since BtcAmount is a sealed trait that MilliSatoshi cannot extend, we need to redefine comparison operators.
  def compare(other: BtcAmount): Int = compare(other.toMilliSatoshi)
  def <=(that: BtcAmount): Boolean = compare(that) <= 0
  def >=(that: BtcAmount): Boolean = compare(that) >= 0
  def <(that: BtcAmount): Boolean = compare(that) < 0
  def >(that: BtcAmount): Boolean = compare(that) > 0
  def truncateToSatoshi: Satoshi = Satoshi(underlying / 1000)
  def toLong: Long = underlying
  // @formatter:on

}

object MilliSatoshi {

  private def satoshi2millisatoshi(input: Satoshi): MilliSatoshi = MilliSatoshi(input.amount * 1000L)

  def toMilliSatoshi(amount: BtcAmount): MilliSatoshi = amount match {
    case sat: Satoshi => satoshi2millisatoshi(sat)
    case millis: MilliBtc => satoshi2millisatoshi(millibtc2satoshi(millis))
    case bitcoin: Btc => satoshi2millisatoshi(btc2satoshi(bitcoin))
  }

  // @formatter:off
  def maxOf(x: MilliSatoshi, y: MilliSatoshi) = MilliSatoshi(Math.max(x.toLong, y.toLong))
  def maxOf(x: MilliSatoshi, y: BtcAmount) = MilliSatoshi(Math.max(x.toLong, y.toMilliSatoshi.toLong))
  def maxOf(x: BtcAmount, y: MilliSatoshi): MilliSatoshi = maxOf(y, x)
  def maxOf(x: Satoshi, y: Satoshi) = Satoshi(Math.max(x.toLong, y.toLong))
  def minOf(x: MilliSatoshi, y: MilliSatoshi) = MilliSatoshi(Math.min(x.toLong, y.toLong))
  def minOf(x: MilliSatoshi, y: BtcAmount) = MilliSatoshi(Math.min(x.toLong, y.toMilliSatoshi.toLong))
  def minOf(x: BtcAmount, y: MilliSatoshi): MilliSatoshi = minOf(y, x)
  def minOf(x: Satoshi, y: Satoshi) = Satoshi(Math.min(x.toLong, y.toLong))
  // @formatter:on

}
