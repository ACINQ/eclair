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

package fr.acinq.eclair.blockchain.fee

import fr.acinq.bitcoin.scalacompat.{Satoshi, SatoshiLong}

import scala.concurrent.Future

/**
 * Used to fetch feerates.
 * Created by PM on 09/07/2017.
 */
trait FeeProvider {
  def getFeerates: Future[FeeratesPerKB]
}

case object CannotRetrieveFeerates extends RuntimeException("cannot retrieve feerates, channels may be at risk: ensure bitcoind estimatesmartfee correctly returns feerates and restart eclair")

/** Fee rate in satoshi-per-bytes. */
case class FeeratePerByte(feerate: Satoshi) {
  def perKw: FeeratePerKw = FeeratePerKw(this)
  def perKB: FeeratePerKB = FeeratePerKB(this)
  override def toString: String = s"$feerate/byte"
}

object FeeratePerByte {
  private[fee] def apply(feeratePerKB: FeeratePerKB): FeeratePerByte = FeeratePerByte(feeratePerKB.feerate / 1000)
  private[fee] def apply(feeratePerKw: FeeratePerKw): FeeratePerByte = FeeratePerByte(FeeratePerKB(feeratePerKw))
}

/** Fee rate in satoshi-per-kilo-bytes (1 kB = 1000 bytes). */
case class FeeratePerKB(feerate: Satoshi) extends Ordered[FeeratePerKB] {
  // @formatter:off
  def perByte: FeeratePerByte = FeeratePerByte(this)
  def perKw: FeeratePerKw = FeeratePerKw(this)
  override def compare(that: FeeratePerKB): Int = feerate.compare(that.feerate)
  def max(other: FeeratePerKB): FeeratePerKB = if (this > other) this else other
  def min(other: FeeratePerKB): FeeratePerKB = if (this < other) this else other
  def toLong: Long = feerate.toLong
  override def toString: String = s"$feerate/kB"
  // @formatter:on
}

object FeeratePerKB {
  // @formatter:off
  private[fee] def apply(feeratePerByte: FeeratePerByte): FeeratePerKB = FeeratePerKB(feeratePerByte.feerate * 1000)
  private[fee] def apply(feeratePerKw: FeeratePerKw): FeeratePerKB = FeeratePerKB(feeratePerKw.feerate * 4)
  // @formatter:on
}

/** Fee rate in satoshi-per-kilo-weight. */
case class FeeratePerKw(feerate: Satoshi) extends Ordered[FeeratePerKw] {
  // @formatter:off
  def perByte: FeeratePerByte = FeeratePerByte(this)
  def perKB: FeeratePerKB = FeeratePerKB(this)
  override def compare(that: FeeratePerKw): Int = feerate.compare(that.feerate)
  def max(other: FeeratePerKw): FeeratePerKw = if (this > other) this else other
  def min(other: FeeratePerKw): FeeratePerKw = if (this < other) this else other
  def +(other: FeeratePerKw): FeeratePerKw = FeeratePerKw(feerate + other.feerate)
  def *(d: Double): FeeratePerKw = FeeratePerKw(feerate * d)
  def *(l: Long): FeeratePerKw = FeeratePerKw(feerate * l)
  def /(l: Long): FeeratePerKw = FeeratePerKw(feerate / l)
  def toLong: Long = feerate.toLong
  override def toString: String = s"$feerate/kw"
  // @formatter:on
}

object FeeratePerKw {
  /**
   * Minimum relay fee rate in satoshi per kilo-vbyte (taken from Bitcoin Core).
   * Note that Bitcoin Core uses a *virtual size* and not the actual size in bytes: see [[MinimumFeeratePerKw]] below.
   */
  val MinimumRelayFeeRate = 1000

  /**
   * Why 253 and not 250 since feerate-per-kw should be feerate-per-kvb / 4 and the minimum relay fee rate is
   * 1000 satoshi/kvb (see [[MinimumRelayFeeRate]])?
   *
   * Because Bitcoin Core uses neither the actual tx size in bytes nor the tx weight to check fees, but a "virtual size"
   * which is (3 + weight) / 4.
   * So we want:
   * fee > 1000 * virtual size
   * feerate-per-kw * weight > 1000 * (3 + weight / 4)
   * feerate-per-kw > 250 + 3000 / (4 * weight)
   *
   * With a conservative minimum weight of 400, assuming the result of the division may be rounded up and using strict
   * inequality to err on the side of safety, we get:
   * feerate-per-kw > 252
   * hence feerate-per-kw >= 253
   *
   * See also https://github.com/ElementsProject/lightning/pull/1251
   */
  val MinimumFeeratePerKw = FeeratePerKw(253 sat)

  // @formatter:off
  private[fee] def apply(feeratePerKB: FeeratePerKB): FeeratePerKw = MinimumFeeratePerKw.max(FeeratePerKw(feeratePerKB.feerate / 4))
  private[fee] def apply(feeratePerByte: FeeratePerByte): FeeratePerKw = FeeratePerKw(FeeratePerKB(feeratePerByte))
  // @formatter:on
}

/**
 * Fee rates in satoshis-per-kilo-bytes (1 kb = 1000 bytes).
 * The mempoolMinFee is the minimal fee required for a tx to enter the mempool (and then be relayed to other nodes and eventually get confirmed).
 * If our fee provider doesn't expose this data, using its biggest block target should be a good enough estimation.
 */
case class FeeratesPerKB(minimum: FeeratePerKB,
                         slow: FeeratePerKB,
                         medium: FeeratePerKB,
                         fast: FeeratePerKB,
                         fastest: FeeratePerKB) {
  require(minimum.feerate > 0.sat && slow.feerate > 0.sat && medium.feerate > 0.sat && fast.feerate > 0.sat && fastest.feerate > 0.sat, "all feerates must be strictly greater than 0")
}

/** Fee rates in satoshi-per-kilo-weight (1 kw = 1000 weight units). */
case class FeeratesPerKw(minimum: FeeratePerKw,
                         slow: FeeratePerKw,
                         medium: FeeratePerKw,
                         fast: FeeratePerKw,
                         fastest: FeeratePerKw) {
  require(minimum.feerate > 0.sat && slow.feerate > 0.sat && medium.feerate > 0.sat && fast.feerate > 0.sat && fastest.feerate > 0.sat, "all feerates must be strictly greater than 0")
}

object FeeratesPerKw {
  def apply(feerates: FeeratesPerKB): FeeratesPerKw = FeeratesPerKw(
    minimum = FeeratePerKw(feerates.minimum),
    slow = FeeratePerKw(feerates.slow),
    medium = FeeratePerKw(feerates.medium),
    fast = FeeratePerKw(feerates.fast),
    fastest = FeeratePerKw(feerates.fastest))

  /** Used in tests */
  def single(feeratePerKw: FeeratePerKw, networkMinFee: FeeratePerKw = FeeratePerByte(1 sat).perKw): FeeratesPerKw = FeeratesPerKw(
    minimum = networkMinFee,
    slow = feeratePerKw,
    medium = feeratePerKw,
    fast = feeratePerKw,
    fastest = feeratePerKw)
}
