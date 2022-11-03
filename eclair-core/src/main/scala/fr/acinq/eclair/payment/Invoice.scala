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
import fr.acinq.eclair.wire.protocol.{ChannelUpdate, OfferTypes}
import fr.acinq.eclair.{Alias, CltvExpiryDelta, Features, InvoiceFeature, MilliSatoshi, MilliSatoshiLong, ShortChannelId, TimestampSecond}

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

trait Invoice {
  // @formatter:off
  def nodeId: PublicKey
  def amount_opt: Option[MilliSatoshi]
  def createdAt: TimestampSecond
  def paymentHash: ByteVector32
  def description: Either[String, ByteVector32]
  def relativeExpiry: FiniteDuration
  def minFinalCltvExpiryDelta: CltvExpiryDelta
  def features: Features[InvoiceFeature]
  def isExpired(now: TimestampSecond = TimestampSecond.now()): Boolean = createdAt + relativeExpiry.toSeconds <= now
  def toString: String
  // @formatter:on
}

object Invoice {
  /** An extra edge that can be used to pay a given invoice and may not be part of the public graph. */
  sealed trait ExtraEdge {
    // @formatter:off
    def sourceNodeId: PublicKey
    def targetNodeId: PublicKey
    def feeBase: MilliSatoshi
    def feeProportionalMillionths: Long
    def cltvExpiryDelta: CltvExpiryDelta
    def htlcMinimum: MilliSatoshi
    def htlcMaximum_opt: Option[MilliSatoshi]
    def relayFees: Relayer.RelayFees = Relayer.RelayFees(feeBase = feeBase, feeProportionalMillionths = feeProportionalMillionths)
    // @formatter:on
  }

  /** A graph edge that should be handled exactly like public channel edges. */
  case class ChannelEdge(sourceNodeId: PublicKey,
                         targetNodeId: PublicKey,
                         shortChannelId: ShortChannelId,
                         feeBase: MilliSatoshi,
                         feeProportionalMillionths: Long,
                         cltvExpiryDelta: CltvExpiryDelta) extends ExtraEdge {
    override val htlcMinimum: MilliSatoshi = 0 msat
    override val htlcMaximum_opt: Option[MilliSatoshi] = None

    def update(u: ChannelUpdate): ChannelEdge = copy(feeBase = u.feeBaseMsat, feeProportionalMillionths = u.feeProportionalMillionths, cltvExpiryDelta = u.cltvExpiryDelta)
  }

  /** Since a blinded route has to be used from start to end, we model it as a single edge. */
  case class BlindedEdge(dummyId: Alias, targetNodeId: PublicKey, route: BlindedRoute, paymentInfo: OfferTypes.PaymentInfo) extends ExtraEdge {
    override val sourceNodeId = route.introductionNodeId
    override val feeBase = paymentInfo.feeBase
    override val feeProportionalMillionths = paymentInfo.feeProportionalMillionths
    override val cltvExpiryDelta = paymentInfo.cltvExpiryDelta
    override val htlcMinimum = paymentInfo.minHtlc
    override val htlcMaximum_opt = Some(paymentInfo.maxHtlc)
  }

  def fromString(input: String): Try[Invoice] = {
    if (input.toLowerCase.startsWith("lni")) {
      Bolt12Invoice.fromString(input)
    } else {
      Bolt11Invoice.fromString(input)
    }
  }
}
