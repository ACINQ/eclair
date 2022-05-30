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

import fr.acinq.bitcoin.scalacompat.{Btc, BtcAmount, MilliBtc, Satoshi, btc2satoshi, millibtc2satoshi}

/**
 * Created by t-bast on 22/08/2019.
 */

/**
 * One MilliSatoshi is a thousandth of a Satoshi, the smallest unit usable in bitcoin
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
  def unary_- = MilliSatoshi(-underlying)

  override def compare(other: MilliSatoshi): Int = underlying.compareTo(other.underlying)
  // Since BtcAmount is a sealed trait that MilliSatoshi cannot extend, we need to redefine comparison operators.
  def compare(other: BtcAmount): Int = compare(other.toMilliSatoshi)
  def <=(other: BtcAmount): Boolean = compare(other) <= 0
  def >=(other: BtcAmount): Boolean = compare(other) >= 0
  def <(other: BtcAmount): Boolean = compare(other) < 0
  def >(other: BtcAmount): Boolean = compare(other) > 0

  // We provide asymmetric min/max functions to provide more control on the return type.
  def max(other: MilliSatoshi): MilliSatoshi = if (this > other) this else other
  def max(other: BtcAmount): MilliSatoshi = if (this > other) this else other.toMilliSatoshi
  def min(other: MilliSatoshi): MilliSatoshi = if (this < other) this else other
  def min(other: BtcAmount): MilliSatoshi = if (this < other) this else other.toMilliSatoshi

  def truncateToSatoshi: Satoshi = Satoshi(underlying / 1000)
  def toLong: Long = underlying
  override def toString = s"$underlying msat"
  // @formatter:on

}

object MilliSatoshi {

  private def satoshi2millisatoshi(input: Satoshi): MilliSatoshi = MilliSatoshi(input.toLong * 1000L)

  def toMilliSatoshi(amount: BtcAmount): MilliSatoshi = amount match {
    case sat: Satoshi => satoshi2millisatoshi(sat)
    case millis: MilliBtc => satoshi2millisatoshi(millibtc2satoshi(millis))
    case bitcoin: Btc => satoshi2millisatoshi(btc2satoshi(bitcoin))
  }

}
