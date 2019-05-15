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


import java.util.BitSet

import scodec.bits.ByteVector

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

  val OPTION_MULTI_FRAME_ONION_MANDATORY = 0
  val OPTION_MULTI_FRAME_ONION_OPTIONAL = 1

  def hasFeature(features: BitSet, bit: Int): Boolean = features.get(bit)

  def hasFeature(features: ByteVector, bit: Int): Boolean = hasFeature(BitSet.valueOf(features.reverse.toArray), bit)

  /**
    * Check that the features that we understand are correctly specified, and that there are no mandatory features that
    * we don't understand (even bits)
    */
  def areSupported(bitset: BitSet): Boolean = {
    val supportedMandatoryFeatures = Set(OPTION_DATA_LOSS_PROTECT_MANDATORY)
    for (i <- 0 until bitset.length() by 2) {
      if (bitset.get(i) && !supportedMandatoryFeatures.contains(i)) return false
    }

    true
  }

  /**
    * A feature set is supported if all even bits are supported.
    * We just ignore unknown odd bits.
    */
  def areSupported(features: ByteVector): Boolean = areSupported(BitSet.valueOf(features.reverse.toArray))
}
