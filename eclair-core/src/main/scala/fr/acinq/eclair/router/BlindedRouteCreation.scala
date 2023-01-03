/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.router

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Router.ChannelHop
import fr.acinq.eclair.wire.protocol.OfferTypes.PaymentInfo
import fr.acinq.eclair.wire.protocol.{RouteBlindingEncryptedDataCodecs, RouteBlindingEncryptedDataTlv, TlvStream}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, randomKey}
import scodec.bits.ByteVector

object BlindedRouteCreation {

  /** Compute aggregated fees and expiry for a given route. */
  def aggregatePaymentInfo(amount: MilliSatoshi, hops: Seq[ChannelHop], minFinalCltvExpiryDelta: CltvExpiryDelta): PaymentInfo = {
    val zeroPaymentInfo = PaymentInfo(0 msat, 0, minFinalCltvExpiryDelta, 0 msat, amount, Features.empty)
    hops.foldRight(zeroPaymentInfo) {
      case (channel, payInfo) =>
        val newFeeBase = MilliSatoshi((channel.params.relayFees.feeBase.toLong * 1_000_000 + payInfo.feeBase.toLong * (1_000_000 + channel.params.relayFees.feeProportionalMillionths) + 1_000_000 - 1) / 1_000_000)
        val newFeeProp = ((payInfo.feeProportionalMillionths + channel.params.relayFees.feeProportionalMillionths) * 1_000_000 + payInfo.feeProportionalMillionths * channel.params.relayFees.feeProportionalMillionths + 1_000_000 - 1) / 1_000_000
        // Most nodes on the network set `htlc_maximum_msat` to the channel capacity. We cannot expect the route to be
        // able to relay that amount, so we remove 10% as a safety margin.
        val channelMaxHtlc = channel.params.htlcMaximum_opt.map(_ * 0.9).getOrElse(amount)
        PaymentInfo(newFeeBase, newFeeProp, payInfo.cltvExpiryDelta + channel.cltvExpiryDelta, payInfo.minHtlc.max(channel.params.htlcMinimum), payInfo.maxHtlc.min(channelMaxHtlc), payInfo.allowedFeatures)
    }
  }

  /** Create a blinded route from a non-empty list of channel hops. */
  def createBlindedRouteFromHops(hops: Seq[Router.ChannelHop], pathId: ByteVector, minAmount: MilliSatoshi, routeFinalExpiry: CltvExpiry): Sphinx.RouteBlinding.BlindedRouteDetails = {
    require(hops.nonEmpty, "route must contain at least one hop")
    // We use the same constraints for all nodes so they can't use it to guess their position.
    val routeExpiry = hops.foldLeft(routeFinalExpiry) { case (expiry, hop) => expiry + hop.cltvExpiryDelta }
    val routeMinAmount = hops.foldLeft(minAmount) { case (amount, hop) => amount.max(hop.params.htlcMinimum) }
    val finalPayload = RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(TlvStream(
      RouteBlindingEncryptedDataTlv.PaymentConstraints(routeExpiry, routeMinAmount),
      RouteBlindingEncryptedDataTlv.PathId(pathId),
    )).require.bytes
    val payloads = hops.foldRight(Seq(finalPayload)) {
      case (channel, payloads) =>
        val payload = RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(TlvStream(
          RouteBlindingEncryptedDataTlv.OutgoingChannelId(channel.shortChannelId),
          RouteBlindingEncryptedDataTlv.PaymentRelay(channel.cltvExpiryDelta, channel.params.relayFees.feeProportionalMillionths, channel.params.relayFees.feeBase),
          RouteBlindingEncryptedDataTlv.PaymentConstraints(routeExpiry, routeMinAmount),
        )).require.bytes
        payload +: payloads
    }
    val nodeIds = hops.map(_.nodeId) :+ hops.last.nextNodeId
    Sphinx.RouteBlinding.create(randomKey(), nodeIds, payloads)
  }

  /** Create a blinded route where the recipient is also the introduction point (which reveals the recipient's identity). */
  def createBlindedRouteWithoutHops(nodeId: PublicKey, pathId: ByteVector, minAmount: MilliSatoshi, routeExpiry: CltvExpiry): Sphinx.RouteBlinding.BlindedRouteDetails = {
    val finalPayload = RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(TlvStream(
      RouteBlindingEncryptedDataTlv.PaymentConstraints(routeExpiry, minAmount),
      RouteBlindingEncryptedDataTlv.PathId(pathId),
    )).require.bytes
    Sphinx.RouteBlinding.create(randomKey(), Seq(nodeId), Seq(finalPayload))
  }

}
