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
import fr.acinq.eclair.payment.DummyInvoice.dummyInvoiceCodec
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import fr.acinq.eclair.{CltvExpiryDelta, Features, InvoiceFeature, MilliSatoshi, MilliSatoshiLong, ShortChannelId, TimestampSecond}
import scodec.Codec
import scodec.bits.ByteVector

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

trait Invoice {
  val amount_opt: Option[MilliSatoshi]

  val createdAt: TimestampSecond

  val nodeId: PublicKey

  val paymentHash: ByteVector32

  val paymentSecret: Option[ByteVector32]

  val paymentMetadata: Option[ByteVector]

  val description: Either[String, ByteVector32]

  val extraEdges: Seq[Invoice.ExtraEdge]

  val relativeExpiry: FiniteDuration

  val minFinalCltvExpiryDelta: CltvExpiryDelta

  val features: Features[InvoiceFeature]

  def isExpired(): Boolean = createdAt + relativeExpiry.toSeconds <= TimestampSecond.now()

  def toString: String
}

object Invoice {
  sealed trait ExtraEdge {
    def sourceNodeId: PublicKey
    def feeBase: MilliSatoshi
    def feeProportionalMillionths: Long
    def cltvExpiryDelta: CltvExpiryDelta
    def htlcMinimum: MilliSatoshi
    def htlcMaximum_opt: Option[MilliSatoshi]

    def relayFees: Relayer.RelayFees = Relayer.RelayFees(feeBase = feeBase, feeProportionalMillionths = feeProportionalMillionths)
  }

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

  def fromString(input: String): Try[Invoice] = {
    if (input.toLowerCase.startsWith("lni")) {
      Bolt12Invoice.fromString(input)
    } else if (input.startsWith("lnd")) {
      DummyInvoice.fromString(input)
    } else {
      Bolt11Invoice.fromString(input)
    }
  }
}

/** Dummy invoice used for unsolicited payments
 */
case class DummyInvoice(amount: MilliSatoshi,
                        createdAt: TimestampSecond,
                        nodeId: PublicKey,
                        paymentHash: ByteVector32,
                        paymentSecret: Option[ByteVector32],
                        minFinalCltvExpiryDelta: CltvExpiryDelta,
                        features: Features[InvoiceFeature]) extends Invoice {
  override val amount_opt: Option[MilliSatoshi] = Some(amount)
  override val paymentMetadata: Option[ByteVector] = None
  override val description: Either[String, ByteVector32] = Left("Donation")
  override val extraEdges: Seq[Invoice.ExtraEdge] = Seq.empty
  override val relativeExpiry: FiniteDuration = FiniteDuration(Bolt11Invoice.DEFAULT_EXPIRY_SECONDS, TimeUnit.SECONDS)

  override def toString: String = {
    "lnd" + dummyInvoiceCodec.encode(this).require.bytes.toBase64
  }
}

object DummyInvoice {

  import fr.acinq.eclair.wire.protocol.CommonCodecs._
  import scodec.codecs._

  private val dummyInvoiceCodec: Codec[DummyInvoice] = (
    ("amount" | millisatoshi) ::
      ("createdAt" | timestampSecond) ::
      ("nodeId" | publicKey) ::
      ("paymentHash" | bytes32) ::
      ("paymentSecret" | optional(bool8, bytes32)) ::
      ("minFinalCltvExpiryDelta" | cltvExpiryDelta) ::
      ("features" | bytes.xmap[Features[InvoiceFeature]](Features(_).invoiceFeatures(), _.toByteVector))).as[DummyInvoice]

  def fromString(input: String): Try[DummyInvoice] = Try {
    dummyInvoiceCodec.decodeValue(ByteVector.fromValidBase64(input.stripPrefix("lnd")).bits).require
  }
}
