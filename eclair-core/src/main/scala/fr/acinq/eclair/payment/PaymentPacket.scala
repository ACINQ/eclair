/*
 * Copyright 2019 ACINQ SAS
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

import akka.actor.ActorRef
import akka.event.LoggingAdapter
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, CMD_FAIL_HTLC, CannotExtractSharedSecret, Origin}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.router.Router.{ChannelHop, Hop, NodeHop}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Feature, Features, MilliSatoshi, ShortChannelId, UInt64, randomKey}
import scodec.bits.ByteVector
import scodec.{Attempt, Codec, DecodeResult}

import java.util.UUID
import scala.util.{Failure, Success, Try}

/**
 * Created by t-bast on 08/10/2019.
 */

sealed trait IncomingPaymentPacket

/** Helpers to handle incoming payment packets. */
object IncomingPaymentPacket {

  // @formatter:off
  /** We are the final recipient. */
  case class FinalPacket(add: UpdateAddHtlc, payload: PaymentOnion.FinalData) extends IncomingPaymentPacket
  /** We are an intermediate node. */
  sealed trait RelayPacket extends IncomingPaymentPacket
  /** We must relay the payment to a direct peer. */
  case class ChannelRelayPacket(add: UpdateAddHtlc, payload: PaymentOnion.ChannelRelayData, nextPacket: OnionRoutingPacket, nextBlindingKey_opt: Option[PublicKey]) extends RelayPacket {
    val relayFeeMsat: MilliSatoshi = add.amountMsat - payload.amountToForward
    val expiryDelta: CltvExpiryDelta = add.cltvExpiry - payload.outgoingCltv
  }
  /** We must relay the payment to a remote node. */
  case class NodeRelayPacket(add: UpdateAddHtlc, outerPayload: PaymentOnion.FinalTlvPayload, innerPayload: PaymentOnion.NodeRelayPayload, nextPacket: OnionRoutingPacket) extends RelayPacket
  // @formatter:on

  case class DecodedOnionPacket[T <: PaymentOnion.PacketType](payload: T, next: OnionRoutingPacket)

  private[payment] def decryptOnion[T <: PaymentOnion.PacketType](paymentHash: ByteVector32, privateKey: PrivateKey, packet: OnionRoutingPacket, perHopPayloadCodec: Boolean => Codec[T])(implicit log: LoggingAdapter): Either[FailureMessage, DecodedOnionPacket[T]] =
    Sphinx.peel(privateKey, Some(paymentHash), packet) match {
      case Right(p@Sphinx.DecryptedPacket(payload, nextPacket, _)) =>
        perHopPayloadCodec(p.isLastPacket).decode(payload.bits) match {
          case Attempt.Successful(DecodeResult(perHopPayload, _)) => Right(DecodedOnionPacket(perHopPayload, nextPacket))
          case Attempt.Failure(e: OnionRoutingCodecs.MissingRequiredTlv) => Left(e.failureMessage)
          case Attempt.Failure(e: OnionRoutingCodecs.ForbiddenTlv) => Left(e.failureMessage)
          // Onion is correctly encrypted but the content of the per-hop payload couldn't be parsed.
          // It's hard to provide tag and offset information from scodec failures, so we currently don't do it.
          case Attempt.Failure(_) => Left(InvalidOnionPayload(UInt64(0), 0))
        }
      case Left(badOnion) => Left(badOnion)
    }

  private def unblind[T <: BlindedRouteData.PaymentData](add: UpdateAddHtlc, privateKey: PrivateKey, payload: PaymentOnion.BlindedPayload, dataCodec: Codec[T]): Either[FailureMessage, (T, PublicKey)] = {
    if (add.blinding_opt.isDefined && payload.blinding_opt.isDefined) {
      Left(InvalidOnionPayload(UInt64(12), 0))
    } else {
      add.blinding_opt.orElse(payload.blinding_opt) match {
        case Some(blinding) =>
          RouteBlindingEncryptedDataCodecs.decode(privateKey, blinding, payload.encryptedRecipientData, dataCodec) match {
            case Failure(_) =>
              // There are two possibilities in this case:
              //  - the blinding point is invalid: the sender or the previous node is buggy or malicious
              //  - the encrypted data is invalid: the recipient is buggy or malicious
              // TODO: return an unparseable error
              Left(InvalidOnionPayload(UInt64(12), 0))
            case Success((data, nextBlinding)) =>
              // TODO: update this when payment features are added
              val featuresUsed: Features[Feature] = Features.empty
              if (isValidBlindedPayment(data, add.amountMsat, add.cltvExpiry, featuresUsed)) {
                Right((data, nextBlinding))
              } else {
                // TODO: return an unparseable error
                Left(InvalidOnionPayload(UInt64(12), 0))
              }
          }
        case None =>
          // The sender is trying to use route blinding, but we didn't receive the blinding point used to derive
          // the decryption key. The sender or the previous peer is buggy or malicious.
          // TODO: return an unparseable error
          Left(InvalidOnionPayload(UInt64(12), 0))
      }
    }
  }

  /**
   * Decrypt the onion packet of a received htlc. If we are the final recipient, we validate that the HTLC fields match
   * the onion fields (this prevents intermediate nodes from sending an invalid amount or expiry).
   *
   * NB: we can't fully validate RelayPackets because it requires knowing the channel/route we'll be using, which we
   * don't know yet. Such validation is the responsibility of downstream components.
   *
   * @param add        incoming htlc
   * @param privateKey this node's private key
   * @return whether the payment is to be relayed or if our node is the final recipient (or an error).
   */
  def decrypt(add: UpdateAddHtlc, privateKey: PrivateKey)(implicit log: LoggingAdapter): Either[FailureMessage, IncomingPaymentPacket] = {
    // We first derive the decryption key used to peel the onion.
    val outerOnionDecryptionKey = add.blinding_opt match {
      case Some(blinding) => Sphinx.RouteBlinding.derivePrivateKey(privateKey, blinding)
      case None => privateKey
    }
    decryptOnion(add.paymentHash, outerOnionDecryptionKey, add.onionRoutingPacket, PaymentOnionCodecs.paymentOnionPerHopPayloadCodec) match {
      case Left(failure) => Left(failure)
      case Right(DecodedOnionPacket(payload: PaymentOnion.ChannelRelayPayload, next)) =>
        payload match {
          case payload: PaymentOnion.BlindedChannelRelayPayload =>
            unblind(add, privateKey, payload, RouteBlindingEncryptedDataCodecs.paymentRelayDataCodec) match {
              case Left(failure) => Left(failure)
              case Right((relayData, nextBlinding)) =>
                if (relayData.outgoingChannelId == ShortChannelId.toSelf) {
                  decrypt(add.copy(onionRoutingPacket = next, tlvStream = add.tlvStream.copy(records = Seq(UpdateAddHtlcTlv.BlindingPoint(nextBlinding)))), privateKey)
                } else {
                  Right(ChannelRelayPacket(add, PaymentOnion.BlindedChannelRelayData(relayData, add.amountMsat, add.cltvExpiry), next, Some(nextBlinding)))
                }
            }
          case _ if add.blinding_opt.isDefined => Left(InvalidOnionPayload(UInt64(12), 0))
          // NB: we don't validate the ChannelRelayPacket here because its fees and cltv depend on what channel we'll choose to use.
          case payload: PaymentOnion.RelayLegacyPayload => Right(ChannelRelayPacket(add, payload, next, None))
          case payload: PaymentOnion.ChannelRelayTlvPayload => Right(ChannelRelayPacket(add, payload, next, None))
        }
      case Right(DecodedOnionPacket(payload: PaymentOnion.FinalPayload, _)) =>
        payload match {
          case payload: PaymentOnion.BlindedFinalPayload =>
            unblind(add, privateKey, payload, RouteBlindingEncryptedDataCodecs.paymentRecipientDataCodec) match {
              case Left(failure) => Left(failure)
              case Right((recipientData, _)) =>
                validateFinal(add, PaymentOnion.BlindedFinalData(recipientData, payload.amount, payload.expiry))
            }
          case _ if add.blinding_opt.isDefined => Left(InvalidOnionPayload(UInt64(12), 0))
          case payload: PaymentOnion.FinalTlvPayload =>
            // We check if the payment is using trampoline: if it is, we may not be the final recipient.
            payload.records.get[OnionPaymentPayloadTlv.TrampolineOnion] match {
              case Some(OnionPaymentPayloadTlv.TrampolineOnion(trampolinePacket)) =>
                // NB: when we enable blinded trampoline routes, we will need to check if the outer onion contains a blinding
                // point and use it to derive the decryption key for the blinded trampoline onion.
                decryptOnion(add.paymentHash, privateKey, trampolinePacket, PaymentOnionCodecs.trampolineOnionPerHopPayloadCodec) match {
                  case Left(failure) => Left(failure)
                  case Right(DecodedOnionPacket(innerPayload: PaymentOnion.NodeRelayPayload, next)) => validateNodeRelay(add, payload, innerPayload, next)
                  case Right(DecodedOnionPacket(innerPayload: PaymentOnion.FinalTlvPayload, _)) => validateFinal(add, payload, innerPayload)
                  case Right(DecodedOnionPacket(_: PaymentOnion.BlindedFinalPayload, _)) => Left(InvalidOnionPayload(UInt64(12), 0)) // trampoline blinded routes are not supported yet
                }
              case None => validateFinal(add, payload)
            }
        }
    }
  }

  private def isValidBlindedPayment(data: BlindedRouteData.PaymentData, amount: MilliSatoshi, cltvExpiry: CltvExpiry, features: Features[Feature]): Boolean = {
    val amountOk = amount >= data.paymentConstraints.minAmount
    val expiryOk = cltvExpiry <= data.paymentConstraints.maxCltvExpiry
    val featuresOk = Features.areCompatible(features, data.allowedFeatures)
    amountOk && expiryOk && featuresOk
  }

  private def validateFinal(add: UpdateAddHtlc, payload: PaymentOnion.FinalData): Either[FailureMessage, IncomingPaymentPacket] = {
    if (add.amountMsat != payload.amount) {
      Left(FinalIncorrectHtlcAmount(add.amountMsat))
    } else if (add.cltvExpiry != payload.expiry) {
      Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
    } else {
      Right(FinalPacket(add, payload))
    }
  }

  private def validateFinal(add: UpdateAddHtlc, outerPayload: PaymentOnion.FinalTlvPayload, innerPayload: PaymentOnion.FinalTlvPayload): Either[FailureMessage, IncomingPaymentPacket] = {
    if (add.amountMsat != outerPayload.amount) {
      Left(FinalIncorrectHtlcAmount(add.amountMsat))
    } else if (add.cltvExpiry != outerPayload.expiry) {
      Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
    } else if (outerPayload.expiry != innerPayload.expiry) {
      Left(FinalIncorrectCltvExpiry(add.cltvExpiry)) // previous trampoline didn't forward the right expiry
    } else if (outerPayload.totalAmount != innerPayload.amount) {
      Left(FinalIncorrectHtlcAmount(outerPayload.totalAmount)) // previous trampoline didn't forward the right amount
    } else {
      // We merge contents from the outer and inner payloads.
      // We must use the inner payload's total amount and payment secret because the payment may be split between multiple trampoline payments (#reckless).
      Right(FinalPacket(add, PaymentOnion.createMultiPartPayload(outerPayload.amount, innerPayload.totalAmount, outerPayload.expiry, innerPayload.paymentSecret, innerPayload.paymentMetadata)))
    }
  }

  private def validateNodeRelay(add: UpdateAddHtlc, outerPayload: PaymentOnion.FinalTlvPayload, innerPayload: PaymentOnion.NodeRelayPayload, next: OnionRoutingPacket): Either[FailureMessage, IncomingPaymentPacket] = {
    if (add.amountMsat < outerPayload.amount) {
      Left(FinalIncorrectHtlcAmount(add.amountMsat))
    } else if (add.cltvExpiry != outerPayload.expiry) {
      Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
    } else {
      Right(NodeRelayPacket(add, outerPayload, innerPayload, next))
    }
  }

}

/** Helpers to create outgoing payment packets. */
object OutgoingPaymentPacket {

  /**
   * Build an encrypted onion packet from onion payloads and node public keys.
   */
  private def buildOnion(packetPayloadLength: Int, nodes: Seq[PublicKey], payloads: Seq[PaymentOnion.PerHopPayload], associatedData: ByteVector32): Try[Sphinx.PacketAndSecrets] = {
    require(nodes.size == payloads.size)
    val sessionKey = randomKey()
    val payloadsBin: Seq[ByteVector] = payloads
      .map {
        case p: PaymentOnion.FinalPayload => PaymentOnionCodecs.finalPerHopPayloadCodec.encode(p)
        case p: PaymentOnion.ChannelRelayPayload => PaymentOnionCodecs.channelRelayPerHopPayloadCodec.encode(p)
        case p: PaymentOnion.NodeRelayPayload => PaymentOnionCodecs.nodeRelayPerHopPayloadCodec.encode(p)
      }
      .map {
        case Attempt.Successful(bitVector) => bitVector.bytes
        case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
      }
    Sphinx.create(sessionKey, packetPayloadLength, nodes, payloadsBin, Some(associatedData))
  }

  /**
   * Build the onion payloads for each hop.
   *
   * @param hops         the hops as computed by the router + extra routes from the invoice
   * @param finalPayload payload data for the final node (amount, expiry, etc)
   * @return a (firstAmount, firstExpiry, payloads) tuple where:
   *         - firstAmount is the amount for the first htlc in the route
   *         - firstExpiry is the cltv expiry for the first htlc in the route
   *         - a sequence of payloads that will be used to build the onion
   */
  def buildPayloads(hops: Seq[Hop], finalPayload: PaymentOnion.FinalTlvPayload): (MilliSatoshi, CltvExpiry, Seq[PaymentOnion.PerHopPayload]) = {
    hops.reverse.foldLeft((finalPayload.amount, finalPayload.expiry, Seq[PaymentOnion.PerHopPayload](finalPayload))) {
      case ((amount, expiry, payloads), hop) =>
        val payload = hop match {
          case hop: ChannelHop => PaymentOnion.ChannelRelayTlvPayload(hop.shortChannelId, amount, expiry)
          case hop: NodeHop => PaymentOnion.createNodeRelayPayload(amount, expiry, hop.nextNodeId)
        }
        (amount + hop.fee(amount), expiry + hop.cltvExpiryDelta, payload +: payloads)
    }
  }

  /**
   * Build an encrypted onion packet with the given final payload.
   *
   * @param hops         the hops as computed by the router + extra routes from the invoice, including ourselves in the first hop
   * @param finalPayload payload data for the final node (amount, expiry, etc)
   * @return a (firstAmount, firstExpiry, onion) tuple where:
   *         - firstAmount is the amount for the first htlc in the route
   *         - firstExpiry is the cltv expiry for the first htlc in the route
   *         - the onion to include in the HTLC
   */
  private def buildPacket(packetPayloadLength: Int, paymentHash: ByteVector32, hops: Seq[Hop], finalPayload: PaymentOnion.FinalTlvPayload): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] = {
    val (firstAmount, firstExpiry, payloads) = buildPayloads(hops.drop(1), finalPayload)
    val nodes = hops.map(_.nextNodeId)
    // BOLT 2 requires that associatedData == paymentHash
    buildOnion(packetPayloadLength, nodes, payloads, paymentHash).map(onion => (firstAmount, firstExpiry, onion))
  }

  def buildPaymentPacket(paymentHash: ByteVector32, hops: Seq[Hop], finalPayload: PaymentOnion.FinalTlvPayload): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] =
    buildPacket(PaymentOnionCodecs.paymentOnionPayloadLength, paymentHash, hops, finalPayload)

  def buildTrampolinePacket(paymentHash: ByteVector32, hops: Seq[Hop], finalPayload: PaymentOnion.FinalTlvPayload): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] =
    buildPacket(PaymentOnionCodecs.trampolineOnionPayloadLength, paymentHash, hops, finalPayload)

  /**
   * Build an encrypted trampoline onion packet when the final recipient doesn't support trampoline.
   * The next-to-last trampoline node payload will contain instructions to convert to a legacy payment.
   *
   * @param invoice      Bolt 11 invoice (features and routing hints will be provided to the next-to-last node).
   * @param hops         the trampoline hops (including ourselves in the first hop, and the non-trampoline final recipient in the last hop).
   * @param finalPayload payload data for the final node (amount, expiry, etc)
   * @return a (firstAmount, firstExpiry, onion) tuple where:
   *         - firstAmount is the amount for the trampoline node in the route
   *         - firstExpiry is the cltv expiry for the first trampoline node in the route
   *         - the trampoline onion to include in final payload of a normal onion
   */
  def buildTrampolineToLegacyPacket(invoice: Bolt11Invoice, hops: Seq[NodeHop], finalPayload: PaymentOnion.FinalTlvPayload): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] = {
    // NB: the final payload will never reach the recipient, since the next-to-last node in the trampoline route will convert that to a non-trampoline payment.
    // We use the smallest final payload possible, otherwise we may overflow the trampoline onion size.
    val dummyFinalPayload = PaymentOnion.createSinglePartPayload(finalPayload.amount, finalPayload.expiry, finalPayload.paymentSecret, None)
    val (firstAmount, firstExpiry, payloads) = hops.drop(1).reverse.foldLeft((finalPayload.amount, finalPayload.expiry, Seq[PaymentOnion.PerHopPayload](dummyFinalPayload))) {
      case ((amount, expiry, payloads), hop) =>
        // The next-to-last node in the trampoline route must receive invoice data to indicate the conversion to a non-trampoline payment.
        val payload = if (payloads.length == 1) {
          PaymentOnion.createNodeRelayToNonTrampolinePayload(finalPayload.amount, finalPayload.totalAmount, finalPayload.expiry, hop.nextNodeId, invoice)
        } else {
          PaymentOnion.createNodeRelayPayload(amount, expiry, hop.nextNodeId)
        }
        (amount + hop.fee(amount), expiry + hop.cltvExpiryDelta, payload +: payloads)
    }
    val nodes = hops.map(_.nextNodeId)
    buildOnion(PaymentOnionCodecs.trampolineOnionPayloadLength, nodes, payloads, invoice.paymentHash).map(onion => (firstAmount, firstExpiry, onion))
  }

  // @formatter:off
  sealed trait Upstream
  object Upstream {
    case class Local(id: UUID) extends Upstream
    case class Trampoline(adds: Seq[UpdateAddHtlc]) extends Upstream {
      val amountIn: MilliSatoshi = adds.map(_.amountMsat).sum
      val expiryIn: CltvExpiry = adds.map(_.cltvExpiry).min
    }
  }
  // @formatter:on

  /**
   * Build the command to add an HTLC with the given final payload and using the provided hops.
   *
   * @return the command and the onion shared secrets (used to decrypt the error in case of payment failure)
   */
  def buildCommand(replyTo: ActorRef, upstream: Upstream, paymentHash: ByteVector32, hops: Seq[ChannelHop], finalPayload: PaymentOnion.FinalTlvPayload): Try[(CMD_ADD_HTLC, Seq[(ByteVector32, PublicKey)])] = {
    buildPaymentPacket(paymentHash, hops, finalPayload).map {
      case (firstAmount, firstExpiry, onion) =>
        CMD_ADD_HTLC(replyTo, firstAmount, paymentHash, firstExpiry, onion.packet, None, Origin.Hot(replyTo, upstream), commit = true) -> onion.sharedSecrets
    }
  }

  def buildHtlcFailure(nodeSecret: PrivateKey, reason: Either[ByteVector, FailureMessage], add: UpdateAddHtlc): Either[CannotExtractSharedSecret, ByteVector] = {
    Sphinx.peel(nodeSecret, Some(add.paymentHash), add.onionRoutingPacket) match {
      case Right(Sphinx.DecryptedPacket(_, _, sharedSecret)) =>
        val encryptedReason = reason match {
          case Left(forwarded) => Sphinx.FailurePacket.wrap(forwarded, sharedSecret)
          case Right(failure) => Sphinx.FailurePacket.create(sharedSecret, failure)
        }
        Right(encryptedReason)
      case Left(_) => Left(CannotExtractSharedSecret(add.channelId, add))
    }
  }

  def buildHtlcFailure(nodeSecret: PrivateKey, cmd: CMD_FAIL_HTLC, add: UpdateAddHtlc): Either[CannotExtractSharedSecret, UpdateFailHtlc] = {
    buildHtlcFailure(nodeSecret, cmd.reason, add) map {
      encryptedReason => UpdateFailHtlc(add.channelId, cmd.id, encryptedReason)
    }
  }
}
