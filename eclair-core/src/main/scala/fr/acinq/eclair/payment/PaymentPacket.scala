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
import fr.acinq.eclair.router.Router.{ChannelHop, Hop, NodeHop, Route}
import fr.acinq.eclair.wire.protocol.OnionPaymentPayloadTlv.EncryptedRecipientData
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload, PerHopPayload}
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.RouteBlindingDecryptedData
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Feature, Features, MilliSatoshi, ShortChannelId, UInt64, randomBytes32, randomKey}
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}

import java.util.UUID
import scala.util.{Failure, Try}

/**
 * Created by t-bast on 08/10/2019.
 */

sealed trait IncomingPaymentPacket

/** Helpers to handle incoming payment packets. */
object IncomingPaymentPacket {

  // @formatter:off
  /** We are the final recipient. */
  case class FinalPacket(add: UpdateAddHtlc, payload: FinalPayload) extends IncomingPaymentPacket
  /** We are an intermediate node. */
  sealed trait RelayPacket extends IncomingPaymentPacket
  /** We must relay the payment to a direct peer. */
  case class ChannelRelayPacket(add: UpdateAddHtlc, payload: IntermediatePayload.ChannelRelay, nextPacket: OnionRoutingPacket) extends RelayPacket {
    val amountToForward: MilliSatoshi = payload.amountToForward(add.amountMsat)
    val outgoingCltv: CltvExpiry = payload.outgoingCltv(add.cltvExpiry)
    val relayFeeMsat: MilliSatoshi = add.amountMsat - amountToForward
    val expiryDelta: CltvExpiryDelta = add.cltvExpiry - outgoingCltv
  }
  /** We must relay the payment to a remote node. */
  case class NodeRelayPacket(add: UpdateAddHtlc, outerPayload: FinalPayload.Standard, innerPayload: IntermediatePayload.NodeRelay.Standard, nextPacket: OnionRoutingPacket) extends RelayPacket
  // @formatter:on

  case class DecodedOnionPacket(payload: TlvStream[OnionPaymentPayloadTlv], next_opt: Option[OnionRoutingPacket])

  private[payment] def decryptOnion(paymentHash: ByteVector32, privateKey: PrivateKey, packet: OnionRoutingPacket): Either[FailureMessage, DecodedOnionPacket] =
    Sphinx.peel(privateKey, Some(paymentHash), packet) match {
      case Right(p: Sphinx.DecryptedPacket) =>
        PaymentOnionCodecs.perHopPayloadCodec.decode(p.payload.bits) match {
          case Attempt.Successful(DecodeResult(perHopPayload, _)) if p.isLastPacket => Right(DecodedOnionPacket(perHopPayload, None))
          case Attempt.Successful(DecodeResult(perHopPayload, _)) => Right(DecodedOnionPacket(perHopPayload, Some(p.nextPacket)))
          case Attempt.Failure(_) =>
            // Onion is correctly encrypted but the content of the per-hop payload couldn't be decoded.
            // It's hard to provide tag and offset information from scodec failures, so we currently don't do it.
            Left(InvalidOnionPayload(UInt64(0), 0))
        }
      case Left(badOnion) => Left(badOnion)
    }

  case class DecodedEncryptedRecipientData(payload: TlvStream[RouteBlindingEncryptedDataTlv], nextBlinding: PublicKey)

  private[payment] def decryptEncryptedRecipientData(add: UpdateAddHtlc, privateKey: PrivateKey, payload: TlvStream[OnionPaymentPayloadTlv], encryptedRecipientData: ByteVector): Either[FailureMessage, DecodedEncryptedRecipientData] = {
    if (add.blinding_opt.isDefined && payload.get[OnionPaymentPayloadTlv.BlindingPoint].isDefined) {
      Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
    } else {
      add.blinding_opt.orElse(payload.get[OnionPaymentPayloadTlv.BlindingPoint].map(_.publicKey)) match {
        case Some(blinding) => RouteBlindingEncryptedDataCodecs.decode(privateKey, blinding, encryptedRecipientData) match {
          case Left(_) =>
            // There are two possibilities in this case:
            //  - the blinding point is invalid: the sender or the previous node is buggy or malicious
            //  - the encrypted data is invalid: the sender, the previous node or the recipient must be buggy or malicious
            Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
          case Right(decoded) => Right(DecodedEncryptedRecipientData(decoded.tlvs, decoded.nextBlinding))
        }
        case None =>
          // The sender is trying to use route blinding, but we didn't receive the blinding point used to derive
          // the decryption key. The sender or the previous peer is buggy or malicious.
          Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      }
    }
  }

  /**
   * Decrypt the onion packet of a received htlc. If we are the final recipient, we validate that the HTLC fields match
   * the onion fields (this prevents intermediate nodes from sending an invalid amount or expiry).
   *
   * NB: we can't fully validate relay packets here because it requires knowing the channel/route we'll be using next,
   * which we don't know yet. Such validation is the responsibility of downstream components.
   *
   * @param add        incoming htlc
   * @param privateKey this node's private key
   * @return whether the payment is to be relayed or if our node is the final recipient (or an error).
   */
  def decrypt(add: UpdateAddHtlc, privateKey: PrivateKey, features: Features[Feature])(implicit log: LoggingAdapter): Either[FailureMessage, IncomingPaymentPacket] = {
    // We first derive the decryption key used to peel the onion.
    val outerOnionDecryptionKey = add.blinding_opt match {
      case Some(blinding) => Sphinx.RouteBlinding.derivePrivateKey(privateKey, blinding)
      case None => privateKey
    }
    decryptOnion(add.paymentHash, outerOnionDecryptionKey, add.onionRoutingPacket).flatMap {
      case DecodedOnionPacket(payload, Some(nextPacket)) =>
        payload.get[OnionPaymentPayloadTlv.EncryptedRecipientData] match {
          case Some(_) if !features.hasFeature(Features.RouteBlinding) => Left(InvalidOnionPayload(UInt64(10), 0))
          case Some(encrypted) =>
            decryptEncryptedRecipientData(add, privateKey, payload, encrypted.data).flatMap {
              case DecodedEncryptedRecipientData(blindedPayload, nextBlinding) =>
                validateBlindedChannelRelayPayload(add, payload, blindedPayload, nextBlinding, nextPacket).flatMap {
                  case ChannelRelayPacket(_, payload, nextPacket) if payload.outgoingChannelId == ShortChannelId.toSelf =>
                    decrypt(add.copy(onionRoutingPacket = nextPacket, tlvStream = add.tlvStream.copy(records = Seq(UpdateAddHtlcTlv.BlindingPoint(nextBlinding)))), privateKey, features)
                  case relayPacket => Right(relayPacket)
                }
            }
          case None if add.blinding_opt.isDefined => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
          case None => IntermediatePayload.ChannelRelay.Standard.validate(payload).left.map(_.failureMessage).map {
            payload => ChannelRelayPacket(add, payload, nextPacket)
          }
        }
      case DecodedOnionPacket(payload, None) =>
        payload.get[OnionPaymentPayloadTlv.EncryptedRecipientData] match {
          case Some(_) if !features.hasFeature(Features.RouteBlinding) => Left(InvalidOnionPayload(UInt64(10), 0))
          case Some(encrypted) =>
            decryptEncryptedRecipientData(add, privateKey, payload, encrypted.data).flatMap {
              case DecodedEncryptedRecipientData(blindedPayload, _) => validateBlindedFinalPayload(add, payload, blindedPayload)
            }
          case None if add.blinding_opt.isDefined => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
          case None =>
            // We check if the payment is using trampoline: if it is, we may not be the final recipient.
            payload.get[OnionPaymentPayloadTlv.TrampolineOnion] match {
              case Some(OnionPaymentPayloadTlv.TrampolineOnion(trampolinePacket)) =>
                // NB: when we enable blinded trampoline routes, we will need to check if the outer onion contains a
                // blinding point and use it to derive the decryption key for the blinded trampoline onion.
                decryptOnion(add.paymentHash, privateKey, trampolinePacket).flatMap {
                  case DecodedOnionPacket(innerPayload, Some(next)) => validateNodeRelay(add, payload, innerPayload, next)
                  case DecodedOnionPacket(innerPayload, None) => validateTrampolineFinalPayload(add, payload, innerPayload)
                }
              case None => validateFinalPayload(add, payload)
            }
        }
    }
  }

  private def validateBlindedChannelRelayPayload(add: UpdateAddHtlc,
                                                 payload: TlvStream[OnionPaymentPayloadTlv],
                                                 blindedPayload: TlvStream[RouteBlindingEncryptedDataTlv],
                                                 nextBlinding: PublicKey,
                                                 nextPacket: OnionRoutingPacket): Either[FailureMessage, ChannelRelayPacket] = {
    IntermediatePayload.ChannelRelay.Blinded.validate(payload, blindedPayload, nextBlinding).left.map(_.failureMessage).flatMap {
      case payload if add.amountMsat < payload.paymentConstraints.minAmount => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if add.cltvExpiry > payload.paymentConstraints.maxCltvExpiry => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if !Features.areCompatible(Features.empty, payload.allowedFeatures) => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload => Right(ChannelRelayPacket(add, payload, nextPacket))
    }
  }

  private def validateFinalPayload(add: UpdateAddHtlc, payload: TlvStream[OnionPaymentPayloadTlv]): Either[FailureMessage, FinalPacket] = {
    FinalPayload.Standard.validate(payload).left.map(_.failureMessage).flatMap {
      case payload if add.amountMsat != payload.amount => Left(FinalIncorrectHtlcAmount(add.amountMsat))
      case payload if add.cltvExpiry != payload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
      case payload => Right(FinalPacket(add, payload))
    }
  }

  private def validateBlindedFinalPayload(add: UpdateAddHtlc, payload: TlvStream[OnionPaymentPayloadTlv], blindedPayload: TlvStream[RouteBlindingEncryptedDataTlv]): Either[FailureMessage, FinalPacket] = {
    FinalPayload.Blinded.validate(payload, blindedPayload).left.map(_.failureMessage).flatMap {
      case payload if add.amountMsat < payload.paymentConstraints.minAmount => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if add.cltvExpiry > payload.paymentConstraints.maxCltvExpiry => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if !Features.areCompatible(Features.empty, payload.allowedFeatures) => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload => Right(FinalPacket(add, payload))
    }
  }

  private def validateTrampolineFinalPayload(add: UpdateAddHtlc, outerPayload: TlvStream[OnionPaymentPayloadTlv], innerPayload: TlvStream[OnionPaymentPayloadTlv]): Either[FailureMessage, FinalPacket] = {
    // The outer payload cannot use route blinding, but the inner payload may (but it's not supported yet).
    FinalPayload.Standard.validate(outerPayload).left.map(_.failureMessage).flatMap { outerPayload =>
      FinalPayload.Standard.validate(innerPayload).left.map(_.failureMessage).flatMap {
        case _ if add.amountMsat != outerPayload.amount => Left(FinalIncorrectHtlcAmount(add.amountMsat))
        case _ if add.cltvExpiry != outerPayload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
        case innerPayload if outerPayload.expiry != innerPayload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry)) // previous trampoline didn't forward the right expiry
        case innerPayload if outerPayload.totalAmount != innerPayload.amount => Left(FinalIncorrectHtlcAmount(outerPayload.totalAmount)) // previous trampoline didn't forward the right amount
        case innerPayload =>
          // We merge contents from the outer and inner payloads.
          // We must use the inner payload's total amount and payment secret because the payment may be split between multiple trampoline payments (#reckless).
          Right(FinalPacket(add, FinalPayload.Standard.createMultiPartPayload(outerPayload.amount, innerPayload.totalAmount, outerPayload.expiry, innerPayload.paymentSecret, innerPayload.paymentMetadata)))
      }
    }
  }

  private def validateNodeRelay(add: UpdateAddHtlc, outerPayload: TlvStream[OnionPaymentPayloadTlv], innerPayload: TlvStream[OnionPaymentPayloadTlv], next: OnionRoutingPacket): Either[FailureMessage, NodeRelayPacket] = {
    // The outer payload cannot use route blinding, but the inner payload may (but it's not supported yet).
    FinalPayload.Standard.validate(outerPayload).left.map(_.failureMessage).flatMap { outerPayload =>
      IntermediatePayload.NodeRelay.Standard.validate(innerPayload).left.map(_.failureMessage).flatMap {
        case _ if add.amountMsat < outerPayload.amount => Left(FinalIncorrectHtlcAmount(add.amountMsat))
        case _ if add.cltvExpiry != outerPayload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
        case innerPayload => Right(NodeRelayPacket(add, outerPayload, innerPayload, next))
      }
    }
  }

}

/** Helpers to create outgoing payment packets. */
object OutgoingPaymentPacket {

  /**
   * Build an encrypted onion packet from onion payloads and node public keys.
   */
  private def buildOnion(packetPayloadLength: Int, nodes: Seq[PublicKey], payloads: Seq[PerHopPayload], associatedData: ByteVector32): Try[Sphinx.PacketAndSecrets] = {
    require(nodes.size == payloads.size)
    val sessionKey = randomKey()
    val payloadsBin: Seq[ByteVector] = payloads
      .map(p => PaymentOnionCodecs.perHopPayloadCodec.encode(p.records))
      .map {
        case Attempt.Successful(bits) => bits.bytes
        case Attempt.Failure(cause) => throw new RuntimeException(s"serialization error: $cause")
      }
    Sphinx.create(sessionKey, packetPayloadLength, nodes, payloadsBin, Some(associatedData))
  }

  /**
   * Build the onion payloads for each hop.
   *
   * @param clearHops        the hops as computed by the router + extra routes from the invoice
   * @param recipient        payment recipient
   * @param amount           amount to send to this route
   * @param totalAmount      total amount of the invoice
   * @param expiry           expiry for this route
   * @param skipIntroduction if we are the introduction point of the blinded route, we should ignore the first blinded hop
   * @return a (firstAmount, firstExpiry, payloads) tuple where:
   *         - firstAmount is the amount for the first htlc in the route
   *         - firstExpiry is the cltv expiry for the first htlc in the route
   *         - a sequence of payloads that will be used to build the onion
   */
  def buildPayloads(clearHops: Seq[Hop],
                    recipient: Recipient,
                    amount: MilliSatoshi,
                    totalAmount: MilliSatoshi,
                    expiry: CltvExpiry,
                    skipIntroduction: Boolean): (MilliSatoshi, CltvExpiry, Seq[PerHopPayload]) = {
    val (endAmount, endExpiry, finalPayloads) = recipient.buildFinalPayloads(amount, totalAmount, expiry)
    val endPayloads = if (skipIntroduction) finalPayloads.drop(1) else finalPayloads
    clearHops.reverse.foldLeft((endAmount, endExpiry, endPayloads)) {
      case ((amount, expiry, payloads), hop) =>
        val payload = hop match {
          case hop: ChannelHop => IntermediatePayload.ChannelRelay.Standard(hop.shortChannelId, amount, expiry)
          case hop: NodeHop => IntermediatePayload.NodeRelay.Standard(amount, expiry, hop.nextNodeId)
        }
        (amount + hop.fee(amount), expiry + hop.cltvExpiryDelta, payload +: payloads)
    }
  }

  /**
   * Build an encrypted onion packet with the given final payload.
   *
   * @param clearHops   the hops as computed by the router + extra routes from the invoice, including ourselves in the first hop
   * @param recipient   payment recipient
   * @param amount      amount to send to this route
   * @param totalAmount total amount of the invoice
   * @param expiry      expiry for this route
   * @return a (firstAmount, firstExpiry, onion) tuple where:
   *         - firstAmount is the amount for the first htlc in the route
   *         - firstExpiry is the cltv expiry for the first htlc in the route
   *         - the onion to include in the HTLC
   */
  private def buildPacket(packetPayloadLength: Int,
                          paymentHash: ByteVector32,
                          clearHops: Seq[Hop],
                          recipient: Recipient,
                          amount: MilliSatoshi,
                          totalAmount: MilliSatoshi,
                          expiry: CltvExpiry): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] = {
    val (firstAmount, firstExpiry, payloads) = buildPayloads(clearHops.drop(1), recipient, amount, totalAmount, expiry, clearHops.isEmpty)
    val clearNodes = clearHops.map(_.nextNodeId)
    val nodes = recipient match {
      case blinded: BlindRecipient => clearNodes ++ blinded.route.blindedNodeIds.drop(1)
      case _: ClearRecipient => clearNodes
    }
    // BOLT 2 requires that associatedData == paymentHash
    buildOnion(packetPayloadLength, nodes, payloads, paymentHash).map(onion => (firstAmount, firstExpiry, onion))
  }

  def buildPaymentPacket(paymentHash: ByteVector32,
                         clearHops: Seq[Hop],
                         recipient: Recipient,
                         amount: MilliSatoshi,
                         totalAmount: MilliSatoshi,
                         expiry: CltvExpiry): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] =
    buildPacket(PaymentOnionCodecs.paymentOnionPayloadLength, paymentHash, clearHops, recipient, amount, totalAmount, expiry)

  def buildTrampolinePacket(paymentHash: ByteVector32,
                            hops: Seq[Hop],
                            recipient: Recipient,
                            amount: MilliSatoshi,
                            totalAmount: MilliSatoshi,
                            expiry: CltvExpiry): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] =
    buildPacket(PaymentOnionCodecs.trampolineOnionPayloadLength, paymentHash, hops, recipient, amount, totalAmount, expiry)

  /**
   * Build an encrypted trampoline onion packet when the final recipient doesn't support trampoline.
   * The next-to-last trampoline node payload will contain instructions to convert to a legacy payment.
   *
   * @param invoice Bolt 11 invoice (features and routing hints will be provided to the next-to-last node).
   * @param hops    the trampoline hops (including ourselves in the first hop, and the non-trampoline final recipient in the last hop).
   * @param amount  amount to send to this route
   * @param expiry  expiry for this route
   * @return a (firstAmount, firstExpiry, onion) tuple where:
   *         - firstAmount is the amount for the trampoline node in the route
   *         - firstExpiry is the cltv expiry for the first trampoline node in the route
   *         - the trampoline onion to include in final payload of a normal onion
   */
  def buildTrampolineToLegacyPacket(invoice: Bolt11Invoice, hops: Seq[NodeHop], amount: MilliSatoshi, expiry: CltvExpiry): Try[(MilliSatoshi, CltvExpiry, Sphinx.PacketAndSecrets)] = {
    // NB: the final payload will never reach the recipient, since the next-to-last node in the trampoline route will convert that to a non-trampoline payment.
    // We use the smallest final payload possible, otherwise we may overflow the trampoline onion size.
    val dummyFinalPayload = FinalPayload.Standard.createSinglePartPayload(amount, expiry, randomBytes32(), None)
    val (firstAmount, firstExpiry, payloads) = hops.drop(1).reverse.foldLeft((amount, expiry, Seq[PerHopPayload](dummyFinalPayload))) {
      case ((amount, expiry, payloads), hop) =>
        // The next-to-last node in the trampoline route must receive invoice data to indicate the conversion to a non-trampoline payment.
        val payload = if (payloads.length == 1) {
          IntermediatePayload.NodeRelay.Standard.createNodeRelayToNonTrampolinePayload(amount, amount, expiry, hop.nextNodeId, invoice)
        } else {
          IntermediatePayload.NodeRelay.Standard(amount, expiry, hop.nextNodeId)
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
   * @return the command, the onion shared secrets (used to decrypt the error in case of payment failure) and the
   *         channel id to send the HTLC to
   */
  def buildCommand(privateKey: PrivateKey,
                   replyTo: ActorRef,
                   upstream: Upstream,
                   paymentHash: ByteVector32,
                   route: Route,
                   amount: MilliSatoshi,
                   totalAmount: MilliSatoshi,
                   expiry: CltvExpiry): Try[(CMD_ADD_HTLC, Seq[(ByteVector32, PublicKey)], ShortChannelId)] = {
    val (shortChannelId, nextBlindingKey_opt, recipient) = if (route.clearHops.nonEmpty) {
      (route.clearHops.head.shortChannelId, None, route.recipient)
    } else {
      route.recipient match {
        case recipient: BlindRecipient if recipient.route.introductionNodeId == privateKey.publicKey =>
          // We assume that there is a next node that is not us, that should be checked before calling the router.
          RouteBlindingEncryptedDataCodecs.decode(privateKey, recipient.route.blindingKey, recipient.route.encryptedPayloads.head) match {
            case Left(e) => return Failure(e)
            case Right(RouteBlindingDecryptedData(encryptedDataTlvs, nextBlindingKey)) =>
              IntermediatePayload.ChannelRelay.Blinded.validate(TlvStream(EncryptedRecipientData(ByteVector.empty)), encryptedDataTlvs, nextBlindingKey) match {
                case Left(invalidTlv) => return Failure(RouteBlindingEncryptedDataCodecs.CannotDecodeData(invalidTlv.failureMessage.message))
                case Right(payload) =>
                  // We assume that fees were checked in the router.
                  val amountWithFees = recipient.amountToSend(amount)
                  val remainingFee = amountWithFees - payload.amountToForward(amountWithFees)
                  val tailPaymentInfo = recipient.paymentInfo.copy(feeBase = remainingFee, feeProportionalMillionths = 0, cltvExpiryDelta = recipient.paymentInfo.cltvExpiryDelta - payload.cltvExpiryDelta)
                  (payload.outgoingChannelId, Some(nextBlindingKey), recipient.copy(paymentInfo = tailPaymentInfo))
              }
          }
        case _ => return Failure(new Exception("Invalid payment route"))
      }
    }
    buildPaymentPacket(paymentHash, route.clearHops, recipient, amount, totalAmount, expiry).map {
      case (firstAmount, firstExpiry, onion) =>
        (CMD_ADD_HTLC(replyTo, firstAmount, paymentHash, firstExpiry, onion.packet, nextBlindingKey_opt, Origin.Hot(replyTo, upstream), commit = true), onion.sharedSecrets, shortChannelId)
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
    buildHtlcFailure(nodeSecret, cmd.reason, add).map(encryptedReason => UpdateFailHtlc(add.channelId, cmd.id, encryptedReason))
  }
}
