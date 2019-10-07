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

import scodec.bits.{BitVector, ByteVector}

/**
  * Created by PM on 13/02/2017.
  */
object Features {
  val OPTION_DATA_LOSS_PROTECT_MANDATORY = 0
  val OPTION_DATA_LOSS_PROTECT_OPTIONAL = 1

  // reserved but not used as per lightningnetwork/lightning-rfc/pull/178
  //val INITIAL_ROUTING_SYNC_BIT_MANDATORY = 2
  val INITIAL_ROUTING_SYNC_BIT_OPTIONAL = 3

  val CHANNEL_RANGE_QUERIES_BIT_MANDATORY = 6
  val CHANNEL_RANGE_QUERIES_BIT_OPTIONAL = 7

  val CHANNEL_RANGE_QUERIES_EX_BIT_MANDATORY = 10
  val CHANNEL_RANGE_QUERIES_EX_BIT_OPTIONAL = 11

  val VARIABLE_LENGTH_ONION_MANDATORY = 8
  val VARIABLE_LENGTH_ONION_OPTIONAL = 9

  // Note that BitVector indexes from left to right whereas the specification indexes from right to left.
  // This is why we have to reverse the bits to check if a feature is set.

  def hasFeature(features: BitVector, bit: Int): Boolean = if (features.sizeLessThanOrEqual(bit)) false else features.reverse.get(bit)

  def hasFeature(features: ByteVector, bit: Int): Boolean = hasFeature(features.bits, bit)

  /**
   * We currently don't distinguish mandatory and optional. Interpreting VARIABLE_LENGTH_ONION_MANDATORY strictly would
   * be very restrictive and probably fork us out of the network.
   * We may implement this distinction later, but for now both flags are interpreted as an optional support.
   */
  def hasVariableLengthOnion(features: ByteVector): Boolean = hasFeature(features, VARIABLE_LENGTH_ONION_MANDATORY) || hasFeature(features, VARIABLE_LENGTH_ONION_OPTIONAL)

  /**
    * Check that the features that we understand are correctly specified, and that there are no mandatory features that
    * we don't understand (even bits).
    */
  def areSupported(features: BitVector): Boolean = {
    val supportedMandatoryFeatures = Set[Long](OPTION_DATA_LOSS_PROTECT_MANDATORY, VARIABLE_LENGTH_ONION_MANDATORY)
    val reversed = features.reverse
    for (i <- 0L until reversed.length by 2) {
      if (reversed.get(i) && !supportedMandatoryFeatures.contains(i)) return false
    }

    true
  }

  /**
    * A feature set is supported if all even bits are supported.
    * We just ignore unknown odd bits.
    */
  def areSupported(features: ByteVector): Boolean = areSupported(features.bits)

}
