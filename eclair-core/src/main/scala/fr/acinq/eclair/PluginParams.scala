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

import akka.actor.typed.ActorRef
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi}
import fr.acinq.eclair.channel.Origin
import fr.acinq.eclair.io.OpenChannelInterceptor.{DefaultParams, OpenChannelNonInitiator}
import fr.acinq.eclair.payment.relay.PostRestartHtlcCleaner.IncomingHtlc
import fr.acinq.eclair.wire.protocol.Error

/** Custom plugin parameters. */
trait PluginParams {
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

/** Parameters for a plugin that defines custom commitment transactions (or non-standard HTLCs). */
trait CustomCommitmentsPlugin extends PluginParams {
  /**
   * If we do nothing after a restart, incoming HTLCs that were committed upstream but not relayed will eventually
   * expire. If your plugin defines non-standard HTLCs, and they need to be automatically failed, they should be
   * returned by this method.
   */
  def getIncomingHtlcs(nodeParams: NodeParams, log: LoggingAdapter): Seq[IncomingHtlc]

  /**
   * Outgoing HTLC sets that are still pending may either succeed or fail: we need to watch them to properly forward the
   * result upstream to preserve channels. If you have non-standard HTLCs that may be in this situation, they should be
   * returned by this method.
   */
  def getHtlcsRelayedOut(htlcsIn: Seq[IncomingHtlc], nodeParams: NodeParams, log: LoggingAdapter): Map[Origin, Set[(ByteVector32, Long)]]
}

// @formatter:off
trait InterceptOpenChannelCommand
case class InterceptOpenChannelReceived(replyTo: ActorRef[InterceptOpenChannelResponse], openChannelNonInitiator: OpenChannelNonInitiator, defaultParams: DefaultParams) extends InterceptOpenChannelCommand {
  val remoteFundingAmount: Satoshi = openChannelNonInitiator.open.fold(_.fundingSatoshis, _.fundingAmount)
  val temporaryChannelId: ByteVector32 = openChannelNonInitiator.open.fold(_.temporaryChannelId, _.temporaryChannelId)
}

sealed trait InterceptOpenChannelResponse
case class AcceptOpenChannel(temporaryChannelId: ByteVector32, defaultParams: DefaultParams) extends InterceptOpenChannelResponse
case class RejectOpenChannel(temporaryChannelId: ByteVector32, error: Error) extends InterceptOpenChannelResponse
// @formatter:on

trait InterceptOpenChannelPlugin extends PluginParams {
  def openChannelInterceptor: ActorRef[InterceptOpenChannelCommand]
}