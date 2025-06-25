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
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, EncodedNodeId, Features, MilliSatoshi, MilliSatoshiLong, randomKey}
import scodec.bits.ByteVector

object BlindedRouteCreation {

  /** Compute aggregated fees and expiry for a given route. */
  def aggregatePaymentInfo(amount: MilliSatoshi, hops: Seq[ChannelHop], minFinalCltvExpiryDelta: CltvExpiryDelta): PaymentInfo = {
    val zeroPaymentInfo = PaymentInfo(0 msat, 0, minFinalCltvExpiryDelta, 0 msat, amount, ByteVector.empty)
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

  /** Create a blinded route from a list of channel hops. */
  def createBlindedRouteFromHops(hops: Seq[Router.ChannelHop], finalNodeId: PublicKey, pathId: ByteVector, minAmount: MilliSatoshi, routeFinalExpiry: CltvExpiry): Sphinx.RouteBlinding.BlindedRouteDetails = {
    // We use the same constraints for all nodes so they can't use it to guess their position.
    val routeExpiry = hops.foldLeft(routeFinalExpiry) { case (expiry, hop) => expiry + hop.cltvExpiryDelta }
    val routeMinAmount = hops.foldLeft(minAmount) { case (amount, hop) => amount.max(hop.params.htlcMinimum) }
    val finalPayload = RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(TlvStream(
      RouteBlindingEncryptedDataTlv.PaymentConstraints(routeExpiry, routeMinAmount),
      RouteBlindingEncryptedDataTlv.PathId(pathId),
    )).require.bytes
    val payloads = hops.map(channel =>
      TlvStream[RouteBlindingEncryptedDataTlv](
        RouteBlindingEncryptedDataTlv.OutgoingChannelId(channel.shortChannelId),
        RouteBlindingEncryptedDataTlv.PaymentRelay(channel.cltvExpiryDelta, channel.params.relayFees.feeProportionalMillionths, channel.params.relayFees.feeBase),
        RouteBlindingEncryptedDataTlv.PaymentConstraints(routeExpiry, routeMinAmount),
      )
    )
    /*
    Size of the payloads:
    - OutgoingChannelId:
      - tag: 1 byte
      - length: 1 byte
      - short_channel_id: 8 bytes
    - PaymentRelay:
      - tag: 1 byte
      - length: 1 byte
      - cltv_expiry_delta: 2 bytes
      - fee_proportional_millionths: 4 bytes
      - fee_base_msat: 0 to 4 bytes
    - PaymentConstraints:
      - tag: 1 byte
      - length: 1 byte
      - max_cltv_expiry: 4 bytes
      - htlc_minimum_msat: 0 to 8 bytes
    Total: 24 to 36 bytes
     */
    val targetLength = 36
    val paddedPayloads = payloads.map(tlvs => {
      val payloadLength = RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(tlvs).require.bytes.length
      tlvs.copy(records = tlvs.records + RouteBlindingEncryptedDataTlv.Padding(ByteVector.fill(targetLength - payloadLength)(0)))
    })
    val encodedPayloads = paddedPayloads.map(RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(_).require.bytes) :+ finalPayload
    val nodeIds = hops.map(_.nodeId) :+ finalNodeId
    Sphinx.RouteBlinding.create(randomKey(), nodeIds, encodedPayloads)
  }

  /** Create a blinded route where the recipient is a wallet node. */
  def createBlindedRouteToWallet(hop: Router.ChannelHop, pathId: ByteVector, minAmount: MilliSatoshi, routeFinalExpiry: CltvExpiry): Sphinx.RouteBlinding.BlindedRouteDetails = {
    val routeExpiry = routeFinalExpiry + hop.cltvExpiryDelta
    val finalPayload = RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(TlvStream(
      RouteBlindingEncryptedDataTlv.PaymentConstraints(routeExpiry, minAmount),
      RouteBlindingEncryptedDataTlv.PathId(pathId),
    )).require.bytes
    val intermediatePayload = RouteBlindingEncryptedDataCodecs.blindedRouteDataCodec.encode(TlvStream[RouteBlindingEncryptedDataTlv](
      RouteBlindingEncryptedDataTlv.OutgoingNodeId(EncodedNodeId.WithPublicKey.Wallet(hop.nextNodeId)),
      RouteBlindingEncryptedDataTlv.PaymentRelay(hop.cltvExpiryDelta, hop.params.relayFees.feeProportionalMillionths, hop.params.relayFees.feeBase),
      RouteBlindingEncryptedDataTlv.PaymentConstraints(routeExpiry, minAmount),
    )).require.bytes
    Sphinx.RouteBlinding.create(randomKey(), Seq(hop.nodeId, hop.nextNodeId), Seq(intermediatePayload, finalPayload))
  }

}
