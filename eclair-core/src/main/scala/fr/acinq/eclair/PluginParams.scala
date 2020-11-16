/*
 * Copyright 2020 ACINQ SAS
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

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.channel.Origin
import fr.acinq.eclair.payment.relay.PostRestartHtlcCleaner.IncomingHtlc

/** Custom plugin parameters. */
sealed trait PluginParams {
  /** Plugin's friendly name. */
  def name: String
}

/** Parameters for a plugin that adds support for an experimental or unofficial Bolt9 feature. */
trait CustomFeaturePlugin extends PluginParams {
  /** A set of LightningMessage tags that the plugin wants to listen to. */
  def messageTags: Set[Int]

  /** Feature bit that the plugin wants to advertise through Init message. */
  def feature: Feature

  /** Plugin feature is always defined as unknown and optional. */
  def pluginFeature: UnknownFeature = UnknownFeature(feature.optional)
}

trait ConnectionControlPlugin extends PluginParams {
  /** Once disconnect happens, should Eclair attempt periodic reconnects to a given remote node even if there is no normal channels left */
  def forceReconnect(nodeId: PublicKey): Boolean
}

/** Parameters for a plugin that defines custom commitment transactions (or non-standard HTLCs). */
trait CustomCommitmentsPlugin extends PluginParams {
  /**
   * If we do nothing after a restart, incoming HTLCs that were committed upstream but not relayed will eventually
   * expire. If your plugin defines non-standard HTLCs, and they need to be automatically failed, they should be
   * returned by this method.
   */
  def getIncomingHtlcs: Seq[IncomingHtlc]

  /**
   * Outgoing HTLC sets that are still pending may either succeed or fail: we need to watch them to properly forward the
   * result upstream to preserve channels. If you have non-standard HTLCs that may be in this situation, they should be
   * returned by this method.
   */
  def getHtlcsRelayedOut(htlcsIn: Seq[IncomingHtlc]): Map[Origin, Set[(ByteVector32, Long)]]
}