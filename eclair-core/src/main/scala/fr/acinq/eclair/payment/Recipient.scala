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

package fr.acinq.eclair.payment

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.wire.protocol.OfferTypes.PaymentInfo
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload, PerHopPayload}
import fr.acinq.eclair.wire.protocol.{GenericTlv, OnionPaymentPayloadTlv, OnionRoutingPacket}
import fr.acinq.eclair.{CltvExpiry, Features, InvoiceFeature, MilliSatoshi, randomBytes32}
import scodec.bits.ByteVector

sealed trait Recipient {
  def nodeId: PublicKey
  def introductionNodeId: PublicKey

  def features: Features[InvoiceFeature]

  def amountToSend(amount: MilliSatoshi): MilliSatoshi

  def additionalTlvs: Seq[OnionPaymentPayloadTlv]

  def userCustomTlvs: Seq[GenericTlv]

  def withCustomTlvs(customTlvs: Seq[GenericTlv]): Recipient

  def contains(id: PublicKey): Boolean

  def buildFinalPayloads(amount: MilliSatoshi,
                         totalAmount: MilliSatoshi,
                         expiry: CltvExpiry): (MilliSatoshi, CltvExpiry, Seq[PerHopPayload])
}

case class ClearRecipient(nodeId: PublicKey,
                          paymentSecret: ByteVector32,
                          paymentMetadata_opt: Option[ByteVector],
                          features: Features[InvoiceFeature] = Features.empty,
                          additionalTlvs: Seq[OnionPaymentPayloadTlv] = Nil,
                          userCustomTlvs: Seq[GenericTlv] = Nil) extends Recipient {
  override def introductionNodeId: PublicKey = nodeId

  override def amountToSend(amount: MilliSatoshi): MilliSatoshi = amount

  override def withCustomTlvs(customTlvs: Seq[GenericTlv]): Recipient = copy(userCustomTlvs = customTlvs)

  override def contains(id: PublicKey): Boolean = id == nodeId

  override def buildFinalPayloads(amount: MilliSatoshi,
                         totalAmount: MilliSatoshi,
                         expiry: CltvExpiry): (MilliSatoshi, CltvExpiry, Seq[PerHopPayload]) =
    (amount, expiry, Seq(FinalPayload.Standard.createMultiPartPayload(amount, totalAmount, expiry, paymentSecret, paymentMetadata_opt, additionalTlvs, userCustomTlvs)))
}

object ClearRecipient {
  def fromTrampolinePayload(payload : IntermediatePayload.NodeRelay.Standard): ClearRecipient =
    ClearRecipient(payload.outgoingNodeId, payload.paymentSecret.get, payload.paymentMetadata, payload.invoiceFeatures.map(Features(_).invoiceFeatures()).getOrElse(Features.empty))
}

object KeySendRecipient {
  def apply(nodeId: PublicKey, paymentPreimage: ByteVector32, userCustomTlvs: Seq[GenericTlv]): ClearRecipient =
    ClearRecipient(nodeId, randomBytes32(), None, additionalTlvs = Seq(OnionPaymentPayloadTlv.KeySend(paymentPreimage)), userCustomTlvs = userCustomTlvs)
}

object TrampolineRecipient {
  def apply(trampolineNodeId: PublicKey, trampolineOnion: OnionRoutingPacket, paymentMetadata_opt: Option[ByteVector]): ClearRecipient =
    ClearRecipient(trampolineNodeId, randomBytes32(), paymentMetadata_opt, additionalTlvs = Seq(OnionPaymentPayloadTlv.TrampolineOnion(trampolineOnion)))
}

case class BlindRecipient(route: RouteBlinding.BlindedRoute,
                          paymentInfo: PaymentInfo,
                          capacity_opt: Option[MilliSatoshi],
                          additionalTlvs: Seq[OnionPaymentPayloadTlv] = Nil,
                          userCustomTlvs: Seq[GenericTlv] = Nil) extends Recipient {
  override def nodeId: PublicKey = route.blindedNodeIds.last

  override def introductionNodeId: PublicKey = route.introductionNodeId

  override def features: Features[InvoiceFeature] = paymentInfo.allowedFeatures.invoiceFeatures()

  override def amountToSend(amount: MilliSatoshi): MilliSatoshi = amount + paymentInfo.fee(amount)

  override def withCustomTlvs(customTlvs: Seq[GenericTlv]): Recipient = copy(userCustomTlvs = customTlvs)

  override def contains(id: PublicKey): Boolean =
    id == route.introductionNodeId || route.blindedNodeIds.contains(id)

  override def buildFinalPayloads(amount: MilliSatoshi,
                         totalAmount: MilliSatoshi,
                         expiry: CltvExpiry): (MilliSatoshi, CltvExpiry, Seq[PerHopPayload]) = {
    val blindedPayloads = if (route.encryptedPayloads.length > 1) {
      val middlePayloads = route.encryptedPayloads.drop(1).dropRight(1).map(IntermediatePayload.ChannelRelay.Blinded.create(_, None))
      val finalPayload = FinalPayload.Blinded.create(amount, totalAmount, expiry, route.encryptedPayloads.last, None, additionalTlvs, userCustomTlvs)
      val introductionPayload = IntermediatePayload.ChannelRelay.Blinded.create(route.encryptedPayloads.head, Some(route.blindingKey))
      introductionPayload +: middlePayloads :+ finalPayload
    } else {
      Seq(FinalPayload.Blinded.create(amount, totalAmount, expiry, route.encryptedPayloads.last, Some(route.blindingKey), additionalTlvs, userCustomTlvs))
    }
    (amount + paymentInfo.fee(amount), expiry + paymentInfo.cltvExpiryDelta, blindedPayloads)

  }
}
