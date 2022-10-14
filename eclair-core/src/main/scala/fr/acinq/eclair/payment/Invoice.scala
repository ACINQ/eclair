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
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.wire.protocol.OfferTypes.PaymentInfo
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload.Partial
import fr.acinq.eclair.wire.protocol.PaymentOnion.PerHopPayload
import fr.acinq.eclair.wire.protocol.{ChannelUpdate, GenericTlv, OnionRoutingPacket}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, InvoiceFeature, MilliSatoshi, MilliSatoshiLong, ShortChannelId, TimestampSecond}
import scodec.bits.ByteVector

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

trait Invoice {
  val amount_opt: Option[MilliSatoshi]

  val createdAt: TimestampSecond

  val nodeId: PublicKey

  val paymentHash: ByteVector32

  val paymentMetadata: Option[ByteVector]

  val description: Either[String, ByteVector32]

  val extraEdges: Seq[Invoice.ExtraEdge]

  val relativeExpiry: FiniteDuration

  val minFinalCltvExpiryDelta: CltvExpiryDelta

  val features: Features[InvoiceFeature]

  def isExpired(): Boolean = createdAt + relativeExpiry.toSeconds <= TimestampSecond.now()

  def toString: String

  def singlePartFinalPayload(amount: MilliSatoshi, expiry: CltvExpiry, userCustomTlvs: Seq[GenericTlv] = Nil): PerHopPayload

  def multiPartFinalPayload(totalAmount: MilliSatoshi, expiry: CltvExpiry, userCustomTlvs: Seq[GenericTlv] = Nil): Partial

  def trampolinePayload(totalAmount: MilliSatoshi, expiry: CltvExpiry, trampolineSecret: ByteVector32, trampolinePacket: OnionRoutingPacket): Partial
}

object Invoice {
  /** An extra edge that can be used to pay a given invoice and may not be part of the public graph. */
  sealed trait ExtraEdge {
    // @formatter:off
    def sourceNodeId: PublicKey
    def targetNodeId: PublicKey
    def shortChannelId: ShortChannelId
    def feeBase: MilliSatoshi
    def feeProportionalMillionths: Long
    def cltvExpiryDelta: CltvExpiryDelta
    def htlcMinimum: MilliSatoshi
    def htlcMaximum_opt: Option[MilliSatoshi]
    final def relayFees: Relayer.RelayFees = Relayer.RelayFees(feeBase = feeBase, feeProportionalMillionths = feeProportionalMillionths)
    // @formatter:on
  }

  /** A normal graph edge, that should be handled exactly like public graph edges. */
  case class BasicEdge(sourceNodeId: PublicKey,
                       targetNodeId: PublicKey,
                       shortChannelId: ShortChannelId,
                       feeBase: MilliSatoshi,
                       feeProportionalMillionths: Long,
                       cltvExpiryDelta: CltvExpiryDelta) extends ExtraEdge {
    override val htlcMinimum: MilliSatoshi = 0 msat
    override val htlcMaximum_opt: Option[MilliSatoshi] = None

    def update(u: ChannelUpdate): BasicEdge = copy(feeBase = u.feeBaseMsat, feeProportionalMillionths = u.feeProportionalMillionths, cltvExpiryDelta = u.cltvExpiryDelta)
  }

  case class BlindedEdge(path: BlindedRoute, payInfo: PaymentInfo, targetNodeId: PublicKey) extends ExtraEdge {
    override val sourceNodeId: PublicKey = path.introductionNodeId
    override val shortChannelId: ShortChannelId = ShortChannelId.generateLocalAlias()
    override val feeBase: MilliSatoshi = payInfo.feeBase
    override val feeProportionalMillionths: Long = payInfo.feeProportionalMillionths
    override val cltvExpiryDelta: CltvExpiryDelta = payInfo.cltvExpiryDelta
    override val htlcMinimum: MilliSatoshi = payInfo.minHtlc
    override val htlcMaximum_opt: Option[MilliSatoshi] = Some(payInfo.maxHtlc)
  }

  def fromString(input: String): Try[Invoice] = {
    if (input.toLowerCase.startsWith("lni")) {
      Bolt12Invoice.fromString(input)
    } else {
      Bolt11Invoice.fromString(input)
    }
  }
}
