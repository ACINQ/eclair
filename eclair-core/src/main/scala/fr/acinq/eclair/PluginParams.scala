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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, Satoshi, TxOut}
import fr.acinq.eclair.channel.Origin
import fr.acinq.eclair.io.OpenChannelInterceptor.OpenChannelNonInitiator
import fr.acinq.eclair.payment.relay.PostRestartHtlcCleaner.IncomingHtlc
import fr.acinq.eclair.wire.protocol.{Error, LiquidityAds}

import scala.concurrent.Future

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
  def getHtlcsRelayedOut(htlcsIn: Seq[IncomingHtlc], nodeParams: NodeParams, log: LoggingAdapter): Map[Origin.Cold, Set[(ByteVector32, Long)]]
}

/**
 * Plugins implementing this trait will be called to validate the remote inputs and outputs used in interactive-tx.
 * This can be used for example to reject interactive transactions that send to specific addresses before signing them.
 */
trait ValidateInteractiveTxPlugin extends PluginParams {
  /**
   * This function will be called for every interactive-tx, before signing it. The plugin should return:
   *  - [[Future.successful(())]] to accept the transaction
   *  - [[Future.failed(...)]] to reject it: the error message will be sent to the remote node, so make sure you don't
   *    include information that should stay private.
   *
   * Note that eclair will run standard validation on its own: you don't need for example to verify that inputs exist
   * and aren't already spent. This function should only be used for custom, non-standard validation that node operators
   * want to apply.
   */
  def validateSharedTx(remoteInputs: Map[OutPoint, TxOut], remoteOutputs: Seq[TxOut]): Future[Unit]
}

// @formatter:off
trait InterceptOpenChannelCommand
case class InterceptOpenChannelReceived(replyTo: ActorRef[InterceptOpenChannelResponse], openChannelNonInitiator: OpenChannelNonInitiator) extends InterceptOpenChannelCommand {
  val remoteFundingAmount: Satoshi = openChannelNonInitiator.open.fold(_.fundingSatoshis, _.fundingAmount)
  val temporaryChannelId: ByteVector32 = openChannelNonInitiator.open.fold(_.temporaryChannelId, _.temporaryChannelId)
}

sealed trait InterceptOpenChannelResponse
case class AcceptOpenChannel(temporaryChannelId: ByteVector32, addFunding_opt: Option[LiquidityAds.AddFunding]) extends InterceptOpenChannelResponse
case class RejectOpenChannel(temporaryChannelId: ByteVector32, error: Error) extends InterceptOpenChannelResponse
// @formatter:on

trait InterceptOpenChannelPlugin extends PluginParams {
  def openChannelInterceptor: ActorRef[InterceptOpenChannelCommand]
}