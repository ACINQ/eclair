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

/**
 * Created by t-bast on 21/08/2019.
 */

/**
 * Bitcoin scripts (in particular HTLCs) need an absolute block expiry (greater than the current block height) to work
 * with OP_CLTV.
 *
 * @param underlying the absolute cltv expiry value (current block height + some delta).
 */
case class CltvExpiry(private val underlying: BlockHeight) extends Ordered[CltvExpiry] {
  // @formatter:off
  def +(d: CltvExpiryDelta): CltvExpiry = CltvExpiry(underlying + d.toInt)
  def -(d: CltvExpiryDelta): CltvExpiry = CltvExpiry(underlying - d.toInt)
  def -(other: CltvExpiry): CltvExpiryDelta = CltvExpiryDelta((underlying - other.underlying).toInt)
  override def compare(other: CltvExpiry): Int = underlying.compareTo(other.underlying)
  def blockHeight: BlockHeight = underlying
  def toLong: Long = underlying.toLong
  // @formatter:on
}

object CltvExpiry {
  // @formatter:off
  def apply(underlying: Int): CltvExpiry = CltvExpiry(BlockHeight(underlying))
  def apply(underlying: Long): CltvExpiry = CltvExpiry(BlockHeight(underlying))
  // @formatter:on
}

/**
 * Channels advertise a cltv expiry delta that should be used when routing through them.
 * This value needs to be converted to a [[fr.acinq.eclair.CltvExpiry]] to be used in OP_CLTV.
 *
 * CltvExpiryDelta can also be used when working with OP_CSV which is by design a delta.
 *
 * @param underlying the cltv expiry delta value.
 */
case class CltvExpiryDelta(private val underlying: Int) extends Ordered[CltvExpiryDelta] {

  /**
   * Adds the current block height to the given delta to obtain an absolute expiry.
   */
  def toCltvExpiry(currentBlockHeight: BlockHeight) = CltvExpiry(currentBlockHeight + underlying)

  // @formatter:off
  def +(other: Int): CltvExpiryDelta = CltvExpiryDelta(underlying + other)
  def +(other: CltvExpiryDelta): CltvExpiryDelta = CltvExpiryDelta(underlying + other.underlying)
  def -(other: CltvExpiryDelta): CltvExpiryDelta = CltvExpiryDelta(underlying - other.underlying)
  def *(m: Int): CltvExpiryDelta = CltvExpiryDelta(underlying * m)
  def compare(other: CltvExpiryDelta): Int = underlying.compareTo(other.underlying)
  def toInt: Int = underlying
  // @formatter:on

}
