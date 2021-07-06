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

/**
 * Created by t-bast on 24/06/2021.
 */

/**
 * Internal configuration option impacting the channel's structure or behavior.
 * This must be set when creating the channel and cannot be changed afterwards.
 */
trait ChannelConfigOption {
  // @formatter:off
  def supportBit: Int
  def name: String
  // @formatter:on
}

case class ChannelConfig(options: Set[ChannelConfigOption]) {

  def hasOption(option: ChannelConfigOption): Boolean = options.contains(option)

}

object ChannelConfig {

  val standard: ChannelConfig = ChannelConfig(options = Set(FundingPubKeyBasedChannelKeyPath))

  def apply(opts: ChannelConfigOption*): ChannelConfig = ChannelConfig(Set.from(opts))

  /**
   * If set, the channel's BIP32 key path will be deterministically derived from the funding public key.
   * It makes it very easy to retrieve funds when channel data has been lost:
   *  - connect to your peer and use option_data_loss_protect to get them to publish their remote commit tx
   *  - retrieve the commit tx from the bitcoin network, extract your funding pubkey from its witness data
   *  - recompute your channel keys and spend your output
   */
  case object FundingPubKeyBasedChannelKeyPath extends ChannelConfigOption {
    override val supportBit: Int = 0
    override val name: String = "funding_pubkey_based_channel_keypath"
  }

}
