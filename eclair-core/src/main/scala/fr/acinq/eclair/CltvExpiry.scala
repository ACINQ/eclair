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
 * Bitcoin scripts (in particular HTLCs) need an absolute block expiry (greater than the current block count) to work
 * with OP_CLTV.
 *
 * @param get the absolute cltv expiry value (current block count + some delta).
 */
case class CltvExpiry(get: Long) {
  // @formatter:off
  def +(d: CltvExpiryDelta): CltvExpiry = CltvExpiry(get + d.delta)
  def -(d: CltvExpiryDelta): CltvExpiry = CltvExpiry(get - d.delta)
  def -(other: CltvExpiry): CltvExpiryDelta = CltvExpiryDelta((get - other.get).toInt)
  def compare(other: CltvExpiry): Int = if (get == other.get) 0 else if (get < other.get) -1 else 1
  def <=(that: CltvExpiry): Boolean = compare(that) <= 0
  def >=(that: CltvExpiry): Boolean = compare(that) >= 0
  def <(that: CltvExpiry): Boolean = compare(that) < 0
  def >(that: CltvExpiry): Boolean = compare(that) > 0
  // @formatter:on
}

/**
 * Channels advertise a cltv expiry delta that should be used when routing through them.
 * This value needs to be converted to a [[fr.acinq.eclair.CltvExpiry]] to be used in OP_CLTV.
 *
 * CltvExpiryDelta can also be used when working with OP_CSV which is by design a delta.
 *
 * @param delta the cltv expiry delta value.
 */
case class CltvExpiryDelta(delta: Int) {

  /**
   * Adds the current block height to the given delta to obtain an absolute expiry.
   */
  def toCltvExpiry = CltvExpiry(Globals.blockCount.get() + delta)

  // @formatter:off
  def +(other: Int): CltvExpiryDelta = CltvExpiryDelta(delta + other)
  def +(other: CltvExpiryDelta): CltvExpiryDelta = CltvExpiryDelta(delta + other.delta)
  def compare(other: CltvExpiryDelta): Int = if (delta == other.delta) 0 else if (delta < other.delta) -1 else 1
  def <=(that: CltvExpiryDelta): Boolean = compare(that) <= 0
  def >=(that: CltvExpiryDelta): Boolean = compare(that) >= 0
  def <(that: CltvExpiryDelta): Boolean = compare(that) < 0
  def >(that: CltvExpiryDelta): Boolean = compare(that) > 0
  // @formatter:on

}
