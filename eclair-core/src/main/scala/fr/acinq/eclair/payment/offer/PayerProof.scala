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

import fr.acinq.bitcoin.Bech32
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, LexicographicalOrdering}
import fr.acinq.eclair.UInt64
import fr.acinq.eclair.payment.Bolt12Invoice
import fr.acinq.eclair.wire.protocol.CommonCodecs.varint
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.OnionRoutingCodecs.{InvalidTlvPayload, InvalidTlvValue, MissingRequiredTlv}
import fr.acinq.eclair.wire.protocol._
import scodec.bits.ByteVector

import scala.util.{Failure, Try}

/**
 * Created by t-bast on 28/05/2026.
 */

case class PayerProof(records: TlvStream[PayerProofTlv]) {

  import PayerProof._

  def verifySigs(): Boolean = {
    PayerProof.validate(records) match {
      case Left(_) => false
      case Right(_) =>
        // The proof signature must be valid: it signs all non-signature TLVs using the invreq_payer_id.
        val payerSig = records.get[ProofSignature].get.signature
        val payerId = records.get[InvoiceRequestPayerId].get.publicKey
        val proofRootHash = rootHash(TlvStream(records.records.filterNot(r => r.isInstanceOf[Signature] || r.isInstanceOf[ProofSignature]), records.unknown), OfferCodecs.payerProofCodec)
        if (!verifySchnorr(PayerProof.signatureTag, proofRootHash, payerSig, payerId)) {
          return false
        }
        // The proof must contain enough data to reconstruct the invoice merkle root.
        val leafNonces = records.get[LeafHashes].map(_.hashes).getOrElse(Nil)
        val markers = records.get[OmittedTlvs].map(_.missing).getOrElse(Nil)
        val missingHashes = records.get[MissingHashes].map(_.missing).getOrElse(Nil)
        // Disclosed invoice merkle-tree leaves: invoice fields (excluding the invoice signature) and unknown TLVs.
        val knownLeaves = records.records.toSeq
          .filterNot(_.isInstanceOf[Signature])
          .collect { case tlv: InvoiceTlv => tlv }
          .map(tlv => TlvCodecs.genericTlv.decode(OfferCodecs.payerProofTlvCodec.encode(tlv).require).require.value)
        val disclosedLeaves = (knownLeaves ++ records.unknown).sortBy(_.tag)
        val tagSet = disclosedLeaves.map(_.tag).toSet
        // The omitted_tlvs field cannot contain the number of an included invoice TLV field.
        // The leaf_hashes field must contain exactly one hash for each non-signature invoice TLV field.
        if (leafNonces.size != disclosedLeaves.size || markers.exists(tagSet.contains)) {
          return false
        }
        // We combine disclosed and omitted leaves and sort them.
        val allLeavesExceptMetadata = disclosedLeaves
          .zip(leafNonces)
          .map { case (tlv, nonce) => (tlv.tag, Some(LeafWithNonce(tlv, nonce))) }
          .appendedAll(markers.map(m => (m, None)))
          .sortBy(_._1)
        // Each omitted leaf's marker must equal the previous leaf's value + 1 (with 0 implied for the always-omitted invreq_metadata at tag 0).
        val omittedLeavesOk = allLeavesExceptMetadata.zipWithIndex.forall {
          case ((_, Some(_)), _) => true
          case ((marker, None), idx) if idx == 0 => marker == UInt64(1)
          case ((marker, None), idx) =>
            val previous = allLeavesExceptMetadata(idx - 1)._1
            val expected = if (previous == UInt64(239)) UInt64(1_000_000_000) else previous + 1
            marker == expected
        }
        if (!omittedLeavesOk) {
          return false
        }
        // The first leaf is always the implicitly-omitted invreq_metadata.
        val allLeaves = (Option.empty[LeafWithNonce] +: allLeavesExceptMetadata.map(_._2)).toIndexedSeq
        PayerProofTree.merkleRoot(allLeaves, missingHashes) match {
          case Left(_) => false
          case Right(invoiceRootHash) =>
            // The invoice signature must be valid against invoice_node_id over the reconstructed merkle root.
            val invoiceSig = records.get[Signature].get.signature
            val invoiceNodeId = records.get[InvoiceNodeId].get.nodeId
            verifySchnorr(Bolt12Invoice.signatureTag, invoiceRootHash, invoiceSig, invoiceNodeId)
        }
    }
  }

  override def toString: String = {
    val data = OfferCodecs.payerProofCodec.encode(records).require.bytes
    Bech32.encodeBytes(PayerProof.hrp, data.toArray, Bech32.Encoding.Beck32WithoutChecksum)
  }

}

object PayerProof {

  val hrp = "lnp"
  val signatureTag: ByteVector = ByteVector(("lightning" + "payer_proof" + "proof_signature").getBytes)

  /** Specifies which optional fields from the invoice should be included in the payer proof. */
  case class IncludedFields(offerChains: Boolean = false,
                            offerMetadata: Boolean = false,
                            offerCurrency: Boolean = false,
                            offerAmount: Boolean = false,
                            offerDescription: Boolean = false,
                            offerFeatures: Boolean = false,
                            offerAbsoluteExpiry: Boolean = false,
                            offerPaths: Boolean = false,
                            offerIssuer: Boolean = false,
                            offerQuantityMax: Boolean = false,
                            offerNodeId: Boolean = false,
                            invoiceRequestChain: Boolean = false,
                            invoiceRequestAmount: Boolean = false,
                            invoiceRequestFeatures: Boolean = false,
                            invoiceRequestQuantity: Boolean = false,
                            invoiceRequestPayerNote: Boolean = false,
                            invoicePaths: Boolean = false,
                            invoiceAccountable: Boolean = false,
                            invoiceBlindedPay: Boolean = false,
                            invoiceCreatedAt: Boolean = false,
                            invoiceRelativeExpiry: Boolean = false,
                            invoiceAmount: Boolean = false,
                            invoiceFallbacks: Boolean = false,
                            unknown: Set[UInt64] = Set.empty)

  def create(invoice: Bolt12Invoice, preimage: ByteVector32, payerKey: PrivateKey, fields: IncludedFields, note_opt: Option[String]): PayerProof = {
    // Valid Bolt12 invoices always contain the invreq_metadata field: it is used as a hashing nonce to prevent brute-forcing private fields.
    val invreqMetadata = invoice.records.get[InvoiceRequestMetadata].get
    // We select invoice fields that we want to include in our payer proof.
    val knownLeaves: Set[(InvoiceTlv, Boolean)] = invoice.records.records
      .filterNot(_.isInstanceOf[Signature])
      .map {
        case tlv: OfferChains => (tlv, fields.offerChains)
        case tlv: OfferMetadata => (tlv, fields.offerMetadata)
        case tlv: OfferCurrency => (tlv, fields.offerCurrency)
        case tlv: OfferAmount => (tlv, fields.offerAmount)
        case tlv: OfferDescription => (tlv, fields.offerDescription)
        case tlv: OfferFeatures => (tlv, fields.offerFeatures)
        case tlv: OfferAbsoluteExpiry => (tlv, fields.offerAbsoluteExpiry)
        case tlv: OfferPaths => (tlv, fields.offerPaths)
        case tlv: OfferIssuer => (tlv, fields.offerIssuer)
        case tlv: OfferQuantityMax => (tlv, fields.offerQuantityMax)
        case tlv: OfferNodeId => (tlv, fields.offerNodeId)
        case tlv: InvoiceRequestMetadata => (tlv, false)
        case tlv: InvoiceRequestChain => (tlv, fields.invoiceRequestChain)
        case tlv: InvoiceRequestAmount => (tlv, fields.invoiceRequestAmount)
        case tlv: InvoiceRequestFeatures => (tlv, fields.invoiceRequestFeatures)
        case tlv: InvoiceRequestQuantity => (tlv, fields.invoiceRequestQuantity)
        case tlv: InvoiceRequestPayerId => (tlv, true)
        case tlv: InvoiceRequestPayerNote => (tlv, fields.invoiceRequestPayerNote)
        case tlv: InvoicePaths => (tlv, fields.invoicePaths)
        case tlv: InvoiceAccountable => (tlv, fields.invoiceAccountable)
        case tlv: InvoiceBlindedPay => (tlv, fields.invoiceBlindedPay)
        case tlv: InvoiceCreatedAt => (tlv, fields.invoiceCreatedAt)
        case tlv: InvoiceRelativeExpiry => (tlv, fields.invoiceRelativeExpiry)
        case tlv: InvoicePaymentHash => (tlv, true)
        case tlv: InvoiceAmount => (tlv, fields.invoiceAmount)
        case tlv: InvoiceFallbacks => (tlv, fields.invoiceFallbacks)
        case tlv: InvoiceFeatures => (tlv, true)
        case tlv: InvoiceNodeId => (tlv, true)
        case tlv: Signature => (tlv, false) // already filtered out above
      }
    // We must also include unknown TLVs, otherwise the merkle tree will be incomplete.
    val unknownLeaves: Set[(GenericTlv, Boolean)] = invoice.records.unknown.map {
      tlv => (tlv, fields.unknown.contains(tlv.tag))
    }
    // We convert everything to generic TLVs to combine known and unknown leaves.
    val leaves = knownLeaves
      .map { case (tlv, included) => (TlvCodecs.genericTlv.decode(OfferCodecs.payerProofTlvCodec.encode(tlv).require).require.value, included) }
      .concat(unknownLeaves)
      .toSeq
      .sortBy(_._1.tag)
      .map { case (tlv, included) => PayerProofTree.Leaf(tlv, included) }
    // We include the leaf nonce for each invoice TLV we include in the payer proof.
    val leafNonces = leaves.collect {
      case leaf if leaf.included => PayerProofTree.leafNonce(invreqMetadata, leaf.tlv)
    }.toList
    val omittedTlvs = leaves.foldLeft((Seq.empty[UInt64], Option.empty[UInt64])) {
      case ((omitted, _), leaf) if leaf.included =>
        // This TLV is included in the payer proof, so we don't add an entry (it is not omitted).
        // However, we tell the next TLV that they need to use a marker higher than the current TLV tag.
        (omitted, Some(leaf.tlv.tag))
      case ((omitted, previousTlv_opt), leaf) if leaf.tlv.tag == UInt64(0) =>
        // The invreq_metadata field is always implicitly omitted (not included in omitted fields).
        (omitted, previousTlv_opt)
      case ((omitted, previousTlv_opt), _) =>
        // This TLV is not included in the payer proof: we add an entry with a marker.
        val markerNumber = previousTlv_opt match {
          case Some(tag) => tag + 1
          case None => omitted.lastOption match {
            case Some(tag) if tag == UInt64(239) => UInt64(1_000_000_000)
            case Some(tag) => tag + 1
            case None => UInt64(1)
          }
        }
        (omitted :+ markerNumber, None)
    }._1.toList
    val proofTree = PayerProofTree(leaves)
    val missingHashes = PayerProofTree.computeMissingHashes(proofTree, invreqMetadata)
    val includedInvoiceTlvs = knownLeaves.collect { case (tlv, true) => tlv }.toSet[PayerProofTlv]
    val payerProofTlvs = Set(
      Some(InvoicePreimage(preimage)),
      Some(LeafHashes(leafNonces)),
      if (omittedTlvs.nonEmpty) Some(OmittedTlvs(omittedTlvs)) else None,
      if (missingHashes.nonEmpty) Some(MissingHashes(missingHashes)) else None,
      note_opt.map(note => ProofNote(note)),
    ).flatten[PayerProofTlv]
    val includedUnknownTlvs = unknownLeaves.collect { case (tlv, true) => tlv }
    // The invoice signature is included in the final payer proof but is excluded from the proof merkle root (signature
    // fields are never signed). Note that the invreq_metadata isn't disclosed in the proof: we will use the first TLV
    // as a hashing nonce instead.
    val proofSig = signSchnorr(signatureTag, rootHash(TlvStream[PayerProofTlv](includedInvoiceTlvs ++ payerProofTlvs, includedUnknownTlvs), OfferCodecs.payerProofCodec), payerKey)
    val finalTlvs = TlvStream[PayerProofTlv](includedInvoiceTlvs ++ invoice.records.get[Signature].toSet ++ payerProofTlvs + ProofSignature(proofSig), includedUnknownTlvs)
    PayerProof(finalTlvs)
  }

  /** When validating a proof, the leaf nonce is directly provided to avoid brute-forcing omitted fields. */
  private case class LeafWithNonce(tlv: GenericTlv, nonce: ByteVector32)

  sealed trait PayerProofTree {
    /** True if all leaves of that subtree are included. */
    def included: Boolean

    /** The invreq_metadata (mandatory) field is used as a nonce to provide randomness. */
    def hash(invreqMetadata: InvoiceRequestMetadata): ByteVector32
  }

  object PayerProofTree {
    /** NB: leaves must usually be ordered by tag by the caller. */
    def apply(leaves: Seq[Leaf]): PayerProofTree = {
      merkleTree(leaves, 0, leaves.length)
    }

    case class Leaf(tlv: GenericTlv, included: Boolean) extends PayerProofTree {
      override def hash(invreqMetadata: InvoiceRequestMetadata): ByteVector32 = {
        val nonce = leafNonce(invreqMetadata, tlv)
        val encodedTlv = TlvCodecs.genericTlv.encode(tlv).require.bytes
        combine(nonce, OfferTypes.hash(ByteVector("LnLeaf".getBytes), encodedTlv))
      }
    }

    case class Branch(left: PayerProofTree, right: PayerProofTree, included: Boolean) extends PayerProofTree {
      override def hash(invreqMetadata: InvoiceRequestMetadata): ByteVector32 = {
        combine(left.hash(invreqMetadata), right.hash(invreqMetadata))
      }
    }

    def computeMissingHashes(tree: PayerProofTree, invreqMetadata: InvoiceRequestMetadata): List[ByteVector32] = {
      if (!tree.included) {
        tree.hash(invreqMetadata) :: Nil
      } else {
        tree match {
          case _: Leaf => Nil
          case Branch(left, right, _) =>
            // Post-order DFS: at each internal node with exactly one fully-omitted branch, emit that branch's hash
            // *after* recursing into the other (mixed) branch.
            (left.included, right.included) match {
              case (true, true) => computeMissingHashes(left, invreqMetadata) ++ computeMissingHashes(right, invreqMetadata)
              case (false, true) => computeMissingHashes(right, invreqMetadata) :+ left.hash(invreqMetadata)
              case (true, false) => computeMissingHashes(left, invreqMetadata) :+ right.hash(invreqMetadata)
              case (false, false) => Nil // unreachable: parent's `included` would be false
            }
        }
      }
    }

    /** Leaves are combined with a nonce acting as a neighbor leaf. */
    def leafNonce(invreqMetadata: InvoiceRequestMetadata, tlv: GenericTlv): ByteVector32 = {
      val encodedMetadata = OfferCodecs.payerProofTlvCodec.encode(invreqMetadata).require.bytes
      val encodedTlvTag = varint.encode(tlv.tag).require.bytes
      OfferTypes.hash(ByteVector("LnNonce".getBytes) ++ encodedMetadata, encodedTlvTag)
    }

    private def combine(h1: ByteVector32, h2: ByteVector32): ByteVector32 = {
      if (LexicographicalOrdering.isLessThan(h1, h2)) {
        OfferTypes.hash(ByteVector("LnBranch".getBytes), h1 ++ h2)
      } else {
        OfferTypes.hash(ByteVector("LnBranch".getBytes), h2 ++ h1)
      }
    }

    private def merkleTree(leaves: Seq[Leaf], i: Int, j: Int): PayerProofTree = {
      if (j - i == 1) {
        leaves(i)
      } else {
        val k = i + previousPowerOfTwo(j - i)
        val left = merkleTree(leaves, i, k)
        val right = merkleTree(leaves, k, j)
        Branch(left, right, left.included || right.included)
      }
    }

    def merkleRoot(leaves: Seq[Option[LeafWithNonce]], missingHashes: Seq[ByteVector32]): Either[String, ByteVector32] = {
      merkleRoot(leaves, missingHashes, 0, leaves.length) match {
        case Left(f) => Left(f)
        case Right((_, remainingHashes)) if remainingHashes.nonEmpty => Left("invalid payer proof: missing_hashes not empty after recomputing invoice merkle root")
        case Right((None, _)) => Left("invalid payer proof: cannot recompute invoice merkle root")
        case Right((Some(root), _)) => Right(root)
      }
    }

    /** Recompute the merkle root, consuming missing_hashes in post-order DFS (smallest TLV first). */
    private def merkleRoot(leaves: Seq[Option[LeafWithNonce]], missingHashes: Seq[ByteVector32], i: Int, j: Int): Either[String, (Option[ByteVector32], Seq[ByteVector32])] = {
      if (j - i == 1) {
        // We've reached a leaf: it is either included or omitted, but doesn't consume any missing hash yet.
        leaves(i) match {
          case Some(leaf) =>
            val encodedTlv = TlvCodecs.genericTlv.encode(leaf.tlv).require.bytes
            val hash = combine(leaf.nonce, OfferTypes.hash(ByteVector("LnLeaf".getBytes), encodedTlv))
            Right((Some(hash), missingHashes))
          case None =>
            Right((None, missingHashes))
        }
      } else {
        val k = i + previousPowerOfTwo(j - i)
        merkleRoot(leaves, missingHashes, i, k) match {
          case Left(f) => Left(f)
          case Right((leftRoot_opt, remainingHashesAfterLeft)) =>
            merkleRoot(leaves, remainingHashesAfterLeft, k, j) match {
              case Left(f) => Left(f)
              case Right((rightRoot_opt, remainingHashes)) =>
                (leftRoot_opt, rightRoot_opt, remainingHashes.headOption) match {
                  // Both subtrees are (at least partially) included.
                  case (Some(left), Some(right), _) => Right((Some(combine(left, right)), remainingHashes))
                  // Both subtrees are fully omitted: the parent tree is fully omitted as well.
                  case (None, None, _) => Right((None, remainingHashes))
                  // One of the subtrees is omitted: we use missing_hashes to get its merkle root.
                  case (Some(left), None, Some(missingHash)) => Right((Some(combine(left, missingHash)), remainingHashes.tail))
                  case (None, Some(right), Some(missingHash)) => Right((Some(combine(missingHash, right)), remainingHashes.tail))
                  // One of the subtrees is omitted, but we don't have missing hashes to consume left.
                  case (_, _, None) => Left("cannot recompute invoice merkle root: missing_hashes is incomplete")
                }
            }
        }
      }
    }

    private def previousPowerOfTwo(n: Int): Int = {
      var p = 1
      while (p < n) {
        p = p << 1
      }
      p >> 1
    }
  }

  private def validate(records: TlvStream[PayerProofTlv]): Either[InvalidTlvPayload, PayerProof] = {
    if (records.get[InvoiceRequestPayerId].isEmpty) return Left(MissingRequiredTlv(UInt64(88)))
    if (records.get[InvoicePaymentHash].isEmpty) return Left(MissingRequiredTlv(UInt64(168)))
    if (records.get[InvoiceNodeId].isEmpty) return Left(MissingRequiredTlv(UInt64(176)))
    if (records.get[Signature].isEmpty) return Left(MissingRequiredTlv(UInt64(240)))
    if (records.get[ProofSignature].isEmpty) return Left(MissingRequiredTlv(UInt64(241)))
    if (records.get[InvoicePreimage].isEmpty) return Left(MissingRequiredTlv(UInt64(1001)))
    // The preimage must match the invoice's payment_hash.
    if (Crypto.sha256(records.get[InvoicePreimage].get.preimage) != records.get[InvoicePaymentHash].get.hash) return Left(InvalidTlvValue(UInt64(1001)))
    val omittedTlvs = records.get[OmittedTlvs].map(_.missing).getOrElse(Nil)
    if (omittedTlvs.length != omittedTlvs.distinct.length) return Left(InvalidTlvValue(UInt64(1002)))
    if (omittedTlvs.sorted != omittedTlvs) return Left(InvalidTlvValue(UInt64(1002)))
    if (!omittedTlvs.forall(tag => (tag >= UInt64(1) && tag <= UInt64(239)) || (tag >= UInt64(1_000_000_000L) && tag <= UInt64(3_999_999_999L)))) return Left(InvalidTlvValue(UInt64(1002)))
    Right(PayerProof(records))
  }

  def fromString(input: String): Try[PayerProof] = Try {
    val triple = Bech32.decodeBytes(input.toLowerCase, true)
    val prefix = triple.getFirst
    val encoded = triple.getSecond
    val encoding = triple.getThird
    require(prefix == hrp)
    require(encoding == Bech32.Encoding.Beck32WithoutChecksum)
    val tlvs = OfferCodecs.payerProofCodec.decode(ByteVector(encoded).bits).require.value
    validate(tlvs) match {
      case Left(f) => return Failure(new IllegalArgumentException(f.toString))
      case Right(payerProof) => payerProof
    }
  }

}
