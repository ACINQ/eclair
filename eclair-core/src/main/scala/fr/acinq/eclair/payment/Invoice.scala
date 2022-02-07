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

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.{CltvExpiryDelta, Features, InvoiceFeature, MilliSatoshi, TimestampSecond}
import scodec.bits.ByteVector

import scala.concurrent.duration.FiniteDuration

trait Invoice {
  val amount_opt: Option[MilliSatoshi]

  val createdAt: TimestampSecond

  val nodeId: PublicKey

  val paymentHash: ByteVector32

  val paymentSecret: Option[ByteVector32]

  val paymentMetadata: Option[ByteVector]

  val description: Either[String, ByteVector32]

  val routingInfo: Seq[Seq[Bolt11Invoice.ExtraHop]]

  val relativeExpiry: FiniteDuration

  val minFinalCltvExpiryDelta: Option[CltvExpiryDelta]

  val features: Features[InvoiceFeature]

  def isExpired(): Boolean = createdAt + relativeExpiry.toSeconds <= TimestampSecond.now()

  def toString: String
}

object Invoice {
  def fromString(input: String): Invoice = {
    Bolt11Invoice.fromString(input)
  }
}
