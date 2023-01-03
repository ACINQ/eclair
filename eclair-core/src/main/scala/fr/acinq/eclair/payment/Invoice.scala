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
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import fr.acinq.eclair.{CltvExpiryDelta, Features, InvoiceFeature, MilliSatoshi, ShortChannelId, TimestampSecond}

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
  def features: Features[InvoiceFeature]
  def isExpired(now: TimestampSecond = TimestampSecond.now()): Boolean = createdAt + relativeExpiry.toSeconds <= now
  def toString: String
  // @formatter:on
}

object Invoice {
  /** An extra edge that can be used to pay a given invoice and may not be part of the public graph. */
  case class ExtraEdge(sourceNodeId: PublicKey,
                       targetNodeId: PublicKey,
                       shortChannelId: ShortChannelId,
                       feeBase: MilliSatoshi,
                       feeProportionalMillionths: Long,
                       cltvExpiryDelta: CltvExpiryDelta,
                       htlcMinimum: MilliSatoshi,
                       htlcMaximum_opt: Option[MilliSatoshi]) {
    val relayFees = Relayer.RelayFees(feeBase, feeProportionalMillionths)

    def update(u: ChannelUpdate): ExtraEdge = copy(
      feeBase = u.feeBaseMsat,
      feeProportionalMillionths = u.feeProportionalMillionths,
      cltvExpiryDelta = u.cltvExpiryDelta,
      htlcMinimum = u.htlcMinimumMsat,
      htlcMaximum_opt = Some(u.htlcMaximumMsat)
    )
  }

  def fromString(input: String): Try[Invoice] = {
    if (input.toLowerCase.startsWith("lni")) {
      Bolt12Invoice.fromString(input)
    } else {
      Bolt11Invoice.fromString(input)
    }
  }
}
