/*
 * Copyright 2023 ACINQ SAS
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

package fr.acinq.eclair.payment.offer

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto}
import fr.acinq.eclair.{MilliSatoshi, TimestampSecond}
import scodec.bits.ByteVector

/**
 * Created by thomash-acinq on 13/01/2023.
 */

/**
 * When we receive an invoice request for one of our offers, we send an invoice back but we don't store it in our local
 * database. If we did, malicious nodes could constantly send us invoice requests and never pay them, which would fill
 * our local database, effectively DoS-ing our node.
 *
 * We instead include payment metadata in the blinded route's path_id field which lets us generate a minimal invoice
 * once we receive the payment, that is similar to the one that was actually sent to the payer. It will not be exactly
 * the same (notably the blinding route will be missing) but it will contain what we need to fulfill the payment.
 */
object OfferPaymentMetadata {

  /**
   * @param preimage       preimage for that payment.
   * @param payerKey       payer key (from their invoice request).
   * @param createdAt      creation time of the invoice.
   * @param quantity       quantity of items requested.
   * @param amount         amount that must be paid.
   * @param pluginData_opt optional data from the offer plugin.
   */
  case class MinimalInvoiceData(preimage: ByteVector32,
                                payerKey: PublicKey,
                                createdAt: TimestampSecond,
                                quantity: Long,
                                amount: MilliSatoshi,
                                pluginData_opt: Option[ByteVector])

  /**
   * The data is signed so that it can't be forged by the payer, it is also encrypted as part of the blinding route so
   * the payer can't read it. It takes 181 bytes plus the plugin data.
   */
  case class SignedMinimalInvoiceData(signature: ByteVector64, offerId: ByteVector32, invoiceData: ByteVector)

  object MinimalInvoiceData {

    import fr.acinq.eclair.wire.protocol.CommonCodecs._
    import scodec.Codec
    import scodec.codecs._

    private val dataCodec: Codec[MinimalInvoiceData] =
      (("preimage" | bytes32) ::
        ("payerKey" | publicKey) ::
        ("createdAt" | timestampSecond) ::
        ("quantity" | uint64overflow) ::
        ("amount" | millisatoshi) ::
        ("pluginData" | optional(bitsRemaining, bytes))).as[MinimalInvoiceData]

    private val signedDataCodec: Codec[SignedMinimalInvoiceData] =
      (("signature" | bytes64) ::
        ("offerId" | bytes32) ::
        ("invoiceData" | bytes)).as[SignedMinimalInvoiceData]

    def encode(privateKey: PrivateKey, offerId: ByteVector32, data: MinimalInvoiceData): ByteVector = {
      val encodedData = dataCodec.encode(data).require.bytes
      val signature = Crypto.sign(Crypto.sha256(offerId ++ encodedData), privateKey)
      signedDataCodec.encode(SignedMinimalInvoiceData(signature, offerId, encodedData)).require.bytes
    }

    def decode(data: ByteVector): Option[SignedMinimalInvoiceData] = {
      signedDataCodec.decode(data.bits).toOption.map(_.value)
    }

    def verify(publicKey: PublicKey, signedData: SignedMinimalInvoiceData): Option[MinimalInvoiceData] = {
      if (Crypto.verifySignature(Crypto.sha256(signedData.offerId ++ signedData.invoiceData), signedData.signature, publicKey)) {
        dataCodec.decode(signedData.invoiceData.bits).toOption.map(_.value)
      } else {
        None
      }
    }
  }

}
