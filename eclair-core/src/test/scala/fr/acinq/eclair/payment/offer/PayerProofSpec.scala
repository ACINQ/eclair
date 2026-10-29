/*
 * Copyright 2026 ACINQ SAS
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

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64}
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.payment.Bolt12Invoice
import fr.acinq.eclair.payment.offer.PayerProof.IncludedFields
import fr.acinq.eclair.wire.protocol.OfferTypes.{LeafHashes, MissingHashes, OmittedTlvs, ProofSignature}
import fr.acinq.eclair.wire.protocol.{OfferCodecs, OfferTypes}
import org.json4s.DefaultFormats
import org.json4s.jackson.JsonMethods
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.HexStringSyntax

import java.io.File
import scala.io.Source

class PayerProofSpec extends AnyFunSuite {

  case class TestVectors(valid_vectors: Seq[ValidTestVector], invalid_vectors: Seq[InvalidTestVector])

  case class ValidTestVector(name: String, input: TestVectorInput, working: TestVectorWorking, result: TestVectorResult)

  case class TestVectorInput(invoice: String, preimage: String, note: Option[String], invoice_fields: Seq[InvoiceField])

  case class InvoiceField(tag: Long, included: Boolean)

  case class TestVectorWorking(invoice_merkle_root: String, proof_leaf_hashes: Seq[String], proof_omitted_tlvs: Seq[Long], proof_missing_hashes: Seq[String])

  case class TestVectorResult(payer_sig: String, bech32: String)

  case class InvalidTestVector(reason: String, bech32: String)

  test("official test vectors") {
    implicit val formats: DefaultFormats.type = DefaultFormats

    val src = Source.fromFile(new File(getClass.getResource("/payer-proof-test.json").getFile))
    val f = JsonMethods.parse(src.mkString).extract[TestVectors]
    src.close()

    val payerKey = PrivateKey(hex"4242424242424242424242424242424242424242424242424242424242424242")

    f.valid_vectors.foreach(t => {
      val invoice = Bolt12Invoice.fromString(t.input.invoice).get
      assert(OfferTypes.rootHash(OfferTypes.removeSignature(invoice.records), OfferCodecs.invoiceTlvCodec) == ByteVector32.fromValidHex(t.working.invoice_merkle_root))
      val preimage = ByteVector32.fromValidHex(t.input.preimage)
      val includedFields = IncludedFields(
        offerChains = t.input.invoice_fields.exists(f => f.tag == 2 && f.included),
        offerMetadata = t.input.invoice_fields.exists(f => f.tag == 4 && f.included),
        offerCurrency = t.input.invoice_fields.exists(f => f.tag == 6 && f.included),
        offerAmount = t.input.invoice_fields.exists(f => f.tag == 8 && f.included),
        offerDescription = t.input.invoice_fields.exists(f => f.tag == 10 && f.included),
        offerFeatures = t.input.invoice_fields.exists(f => f.tag == 12 && f.included),
        offerAbsoluteExpiry = t.input.invoice_fields.exists(f => f.tag == 14 && f.included),
        offerPaths = t.input.invoice_fields.exists(f => f.tag == 16 && f.included),
        offerIssuer = t.input.invoice_fields.exists(f => f.tag == 18 && f.included),
        offerQuantityMax = t.input.invoice_fields.exists(f => f.tag == 20 && f.included),
        offerNodeId = t.input.invoice_fields.exists(f => f.tag == 22 && f.included),
        invoiceRequestChain = t.input.invoice_fields.exists(f => f.tag == 80 && f.included),
        invoiceRequestAmount = t.input.invoice_fields.exists(f => f.tag == 82 && f.included),
        invoiceRequestFeatures = t.input.invoice_fields.exists(f => f.tag == 84 && f.included),
        invoiceRequestQuantity = t.input.invoice_fields.exists(f => f.tag == 86 && f.included),
        invoiceRequestPayerNote = t.input.invoice_fields.exists(f => f.tag == 89 && f.included),
        invoicePaths = t.input.invoice_fields.exists(f => f.tag == 160 && f.included),
        invoiceAccountable = t.input.invoice_fields.exists(f => f.tag == 161 && f.included),
        invoiceBlindedPay = t.input.invoice_fields.exists(f => f.tag == 162 && f.included),
        invoiceCreatedAt = t.input.invoice_fields.exists(f => f.tag == 164 && f.included),
        invoiceRelativeExpiry = t.input.invoice_fields.exists(f => f.tag == 166 && f.included),
        invoiceAmount = t.input.invoice_fields.exists(f => f.tag == 170 && f.included),
        invoiceFallbacks = t.input.invoice_fields.exists(f => f.tag == 172 && f.included),
        unknown = t.input.invoice_fields.filter(f => f.tag > 250 && f.included).map(f => UInt64(f.tag)).toSet,
      )
      if (t.name == "empty_proof_omitted_tlvs_explicit") {
        // This test vector verifies that we correctly handle an explicitly included empty proof_omitted_tlvs field.
        val decoded = PayerProof.fromString(t.result.bech32)
        assert(decoded.isSuccess)
        assert(decoded.get.records.get[ProofSignature].get.signature == ByteVector64.fromValidHex(t.result.payer_sig))
        assert(decoded.get.verifySigs())
        // Since we omit the proof_omitted_tlvs field when it's empty, we don't generate the same proof, which is fine.
        assert(PayerProof.create(invoice, preimage, payerKey, includedFields, t.input.note).toString != t.result.bech32)
      } else {
        val payerProof = PayerProof.create(invoice, preimage, payerKey, includedFields, t.input.note)
        assert(payerProof.records.get[LeafHashes].nonEmpty)
        payerProof.records.get[LeafHashes].foreach(h => assert(h.hashes == t.working.proof_leaf_hashes.map(ByteVector32.fromValidHex)))
        payerProof.records.get[OmittedTlvs].foreach(o => assert(o.missing == t.working.proof_omitted_tlvs.map(UInt64(_))))
        payerProof.records.get[MissingHashes].foreach(h => assert(h.missing == t.working.proof_missing_hashes.map(ByteVector32.fromValidHex)))
        assert(payerProof.records.get[ProofSignature].nonEmpty)
        assert(payerProof.records.get[ProofSignature].get.signature == ByteVector64.fromValidHex(t.result.payer_sig))
        assert(payerProof.toString == t.result.bech32)
        assert(PayerProof.fromString(t.result.bech32).isSuccess)
        assert(PayerProof.fromString(t.result.bech32).get == payerProof)
        assert(payerProof.verifySigs())
      }
    })
    f.invalid_vectors.foreach(t => {
      assert(!PayerProof.fromString(t.bech32).map(_.verifySigs()).getOrElse(false))
    })
  }

}
