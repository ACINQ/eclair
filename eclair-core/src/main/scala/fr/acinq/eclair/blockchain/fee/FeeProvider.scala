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
  override def toString: String = s"$feerate/byte"
}

object FeeratePerByte {
  def apply(feeratePerKw: FeeratePerKw): FeeratePerByte = FeeratePerByte(FeeratePerKB(feeratePerKw).feerate / 1000)
}

/** Fee rate in satoshi-per-kilo-bytes (1 kB = 1000 bytes). */
case class FeeratePerKB(feerate: Satoshi) extends Ordered[FeeratePerKB] {
  // @formatter:off
  override def compare(that: FeeratePerKB): Int = feerate.compare(that.feerate)
  def max(other: FeeratePerKB): FeeratePerKB = if (this > other) this else other
  def min(other: FeeratePerKB): FeeratePerKB = if (this < other) this else other
  def toLong: Long = feerate.toLong
  override def toString: String = s"$feerate/kB"
  // @formatter:on
}

object FeeratePerKB {
  // @formatter:off
  def apply(feeratePerByte: FeeratePerByte): FeeratePerKB = FeeratePerKB(feeratePerByte.feerate * 1000)
  def apply(feeratePerKw: FeeratePerKw): FeeratePerKB = FeeratePerKB(feeratePerKw.feerate * 4)
  // @formatter:on
}

/** Fee rate in satoshi-per-kilo-weight. */
case class FeeratePerKw(feerate: Satoshi) extends Ordered[FeeratePerKw] {
  // @formatter:off
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
  def apply(feeratePerKB: FeeratePerKB): FeeratePerKw = MinimumFeeratePerKw.max(FeeratePerKw(feeratePerKB.feerate / 4))
  def apply(feeratePerByte: FeeratePerByte): FeeratePerKw = FeeratePerKw(FeeratePerKB(feeratePerByte))
  // @formatter:on
}

/**
 * Fee rates in satoshis-per-kilo-bytes (1 kb = 1000 bytes).
 * The mempoolMinFee is the minimal fee required for a tx to enter the mempool (and then be relayed to other nodes and eventually get confirmed).
 * If our fee provider doesn't expose this data, using its biggest block target should be a good enough estimation.
 */
case class FeeratesPerKB(mempoolMinFee: FeeratePerKB, block_1: FeeratePerKB, blocks_2: FeeratePerKB, blocks_6: FeeratePerKB, blocks_12: FeeratePerKB, blocks_36: FeeratePerKB, blocks_72: FeeratePerKB, blocks_144: FeeratePerKB, blocks_1008: FeeratePerKB) {
  require(mempoolMinFee.feerate > 0.sat && block_1.feerate > 0.sat && blocks_2.feerate > 0.sat && blocks_6.feerate > 0.sat && blocks_12.feerate > 0.sat && blocks_36.feerate > 0.sat && blocks_72.feerate > 0.sat && blocks_144.feerate > 0.sat && blocks_1008.feerate > 0.sat, "all feerates must be strictly greater than 0")

  def feePerBlock(target: Int): FeeratePerKB = {
    require(target > 0)
    target match {
      case 1 => block_1
      case 2 => blocks_2
      case t if t <= 6 => blocks_6
      case t if t <= 12 => blocks_12
      case t if t <= 36 => blocks_36
      case t if t <= 72 => blocks_72
      case t if t <= 144 => blocks_144
      case _ => blocks_1008
    }
  }
}

/** Fee rates in satoshi-per-kilo-weight (1 kw = 1000 weight units). */
case class FeeratesPerKw(mempoolMinFee: FeeratePerKw, block_1: FeeratePerKw, blocks_2: FeeratePerKw, blocks_6: FeeratePerKw, blocks_12: FeeratePerKw, blocks_36: FeeratePerKw, blocks_72: FeeratePerKw, blocks_144: FeeratePerKw, blocks_1008: FeeratePerKw) {
  require(mempoolMinFee.feerate > 0.sat && block_1.feerate > 0.sat && blocks_2.feerate > 0.sat && blocks_6.feerate > 0.sat && blocks_12.feerate > 0.sat && blocks_36.feerate > 0.sat && blocks_72.feerate > 0.sat && blocks_144.feerate > 0.sat && blocks_1008.feerate > 0.sat, "all feerates must be strictly greater than 0")

  def feePerBlock(target: Int): FeeratePerKw = {
    require(target > 0)
    target match {
      case 1 => block_1
      case 2 => blocks_2
      case t if t <= 6 => blocks_6
      case t if t <= 12 => blocks_12
      case t if t <= 36 => blocks_36
      case t if t <= 72 => blocks_72
      case t if t <= 144 => blocks_144
      case _ => blocks_1008
    }
  }
}

object FeeratesPerKw {
  def apply(feerates: FeeratesPerKB): FeeratesPerKw = FeeratesPerKw(
    mempoolMinFee = FeeratePerKw(feerates.mempoolMinFee),
    block_1 = FeeratePerKw(feerates.block_1),
    blocks_2 = FeeratePerKw(feerates.blocks_2),
    blocks_6 = FeeratePerKw(feerates.blocks_6),
    blocks_12 = FeeratePerKw(feerates.blocks_12),
    blocks_36 = FeeratePerKw(feerates.blocks_36),
    blocks_72 = FeeratePerKw(feerates.blocks_72),
    blocks_144 = FeeratePerKw(feerates.blocks_144),
    blocks_1008 = FeeratePerKw(feerates.blocks_1008))

  /** Used in tests */
  def single(feeratePerKw: FeeratePerKw): FeeratesPerKw = FeeratesPerKw(
    mempoolMinFee = feeratePerKw,
    block_1 = feeratePerKw,
    blocks_2 = feeratePerKw,
    blocks_6 = feeratePerKw,
    blocks_12 = feeratePerKw,
    blocks_36 = feeratePerKw,
    blocks_72 = feeratePerKw,
    blocks_144 = feeratePerKw,
    blocks_1008 = feeratePerKw)
}
