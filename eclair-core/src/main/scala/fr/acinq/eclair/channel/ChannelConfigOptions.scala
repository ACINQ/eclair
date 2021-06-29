/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.channel

import scodec.bits.{BitVector, ByteVector}

/**
 * Created by t-bast on 24/06/2021.
 */

/**
 * Internal configuration option impacting the channel's structure or behavior.
 * This must be set when creating the channel and cannot be changed afterwards.
 */
trait ChannelConfigOption {
  def supportBit: Int
}

case class ChannelConfigOptions(activated: Set[ChannelConfigOption]) {

  def hasOption(option: ChannelConfigOption): Boolean = activated.contains(option)

  def bytes: ByteVector = toByteVector

  def toByteVector: ByteVector = {
    val indices = activated.map(_.supportBit)
    if (indices.isEmpty) {
      ByteVector.empty
    } else {
      // NB: when converting from BitVector to ByteVector, scodec pads right instead of left, so we make sure we pad to bytes *before* setting bits.
      var buffer = BitVector.fill(indices.max + 1)(high = false).bytes.bits
      indices.foreach(i => buffer = buffer.set(i))
      buffer.reverse.bytes
    }
  }

}

object ChannelConfigOptions {

  def standard: ChannelConfigOptions = ChannelConfigOptions(activated = Set(FundingPubKeyBasedChannelKeyPath))

  def apply(options: ChannelConfigOption*): ChannelConfigOptions = ChannelConfigOptions(Set.from(options))

  def apply(bytes: ByteVector): ChannelConfigOptions = {
    val activated: Set[ChannelConfigOption] = bytes.bits.toIndexedSeq.reverse.zipWithIndex.collect {
      case (true, 0) => FundingPubKeyBasedChannelKeyPath
    }.toSet
    ChannelConfigOptions(activated)
  }

  /**
   * If set, the channel's BIP32 key path will be deterministically derived from the funding public key.
   * It makes it very easy to retrieve funds when channel data has been lost:
   *  - connect to your peer and use option_data_loss_protect to get them to publish their remote commit tx
   *  - retrieve the commit tx from the bitcoin network, extract your funding pubkey from its witness data
   *  - recompute your channel keys and spend your output
   */
  case object FundingPubKeyBasedChannelKeyPath extends ChannelConfigOption {
    override def supportBit = 0
  }

}
