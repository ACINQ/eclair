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

import fr.acinq.eclair.ShortChannelId.toShortId

import scala.util.{Failure, Try}

// @formatter:off
sealed trait ShortChannelId extends Ordered[ShortChannelId] {
  def toLong: Long
  // we use an unsigned long comparison here
  override def compare(that: ShortChannelId): Int = (this.toLong + Long.MinValue).compareTo(that.toLong + Long.MinValue)
  override def hashCode(): Int = toLong.hashCode()
  override def equals(obj: Any): Boolean = obj match {
    case scid: ShortChannelId => this.toLong.equals(scid.toLong)
    case _ => false
  }
  def toCoordinatesString: String = {
    val TxCoordinates(blockHeight, txIndex, outputIndex) = ShortChannelId.coordinates(this)
    s"${blockHeight.toLong}x${txIndex}x$outputIndex"
  }
  def toHex: String = s"0x${toLong.toHexString}"
}
/** Sometimes we don't know what a scid really is */
case class UnspecifiedShortChannelId(private val id: Long) extends ShortChannelId {
  override def toLong: Long = id
  override def toString: String = toCoordinatesString // for backwards compatibility, because ChannelUpdate have an unspecified scid
}
case class RealShortChannelId private(private val id: Long) extends ShortChannelId {
  override def toLong: Long = id
  override def toString: String = toCoordinatesString
  def blockHeight: BlockHeight = ShortChannelId.blockHeight(this)
  def outputIndex: Int = ShortChannelId.outputIndex(this)
}
case class Alias(private val id: Long) extends ShortChannelId {
  override def toLong: Long = id
  override def toString: String = toHex
}
// @formatter:on

object ShortChannelId {
  def apply(l: Long): ShortChannelId = UnspecifiedShortChannelId(l)

  def toShortId(blockHeight: Int, txIndex: Int, outputIndex: Int): Long = ((blockHeight & 0xFFFFFFL) << 40) | ((txIndex & 0xFFFFFFL) << 16) | (outputIndex & 0xFFFFL)

  /**
   * At block height 350 000 LN didn't exist, so all real scids less than that will never be used. This results in
   * more than `2^58` values.
   *
   * The expected number of values before we have a collision can be approximated by (*):
   * `Q(H) ~= sqrt( Pi * H / 2) = sqrt(Pi * 2^57) = 673 000 000`
   *
   * We don't expect to have anywhere close to that many channels at any given time on our node, so we don't need to
   * check for duplicate values.
   *
   * (*) https://en.wikipedia.org/wiki/Birthday_attack
   */
  private val aliasUpperBound = Math.pow(2, 58).toLong

  def generateLocalAlias(): Alias = {
    // modulo won't skew the distribution because 2^64 is a multiple of 2^58
    val l = Math.abs(randomLong() % aliasUpperBound)
    Alias(l)
  }

  @inline
  def blockHeight(shortChannelId: ShortChannelId): BlockHeight = BlockHeight((shortChannelId.toLong >> 40) & 0xFFFFFF)

  @inline
  def txIndex(shortChannelId: ShortChannelId): Int = ((shortChannelId.toLong >> 16) & 0xFFFFFF).toInt

  @inline
  def outputIndex(shortChannelId: ShortChannelId): Int = (shortChannelId.toLong & 0xFFFF).toInt

  def coordinates(shortChannelId: ShortChannelId): TxCoordinates = TxCoordinates(blockHeight(shortChannelId), txIndex(shortChannelId), outputIndex(shortChannelId))

  def fromCoordinates(s: String): Try[ShortChannelId] = s.split("x").toList match {
    case blockHeight :: txIndex :: outputIndex :: Nil => Try {
      UnspecifiedShortChannelId(toShortId(blockHeight.toInt, txIndex.toInt, outputIndex.toInt))
    }
    case _ => Failure(new IllegalArgumentException(s"Invalid short channel id: $s"))
  }

  // A special alias for a virtual channel to ourselves. Used to add extra hops at the end of a blinded route.
  val toSelf: Alias = Alias(aliasUpperBound)
}

/**
 * A real short channel id uniquely identifies a channel by the coordinates of its funding tx output in the blockchain.
 * See BOLT 7: https://github.com/lightningnetwork/lightning-rfc/blob/master/07-routing-gossip.md#requirements
 */
object RealShortChannelId {
  def apply(blockHeight: BlockHeight, txIndex: Int, outputIndex: Int): RealShortChannelId = RealShortChannelId(toShortId(blockHeight.toInt, txIndex, outputIndex))
}

case class TxCoordinates(blockHeight: BlockHeight, txIndex: Int, outputIndex: Int)