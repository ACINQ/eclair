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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, CMD_FAIL_HTLC, CannotExtractSharedSecret, Origin}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.send.Recipient
import fr.acinq.eclair.router.Router.Route
import fr.acinq.eclair.wire.protocol.OnionPaymentPayloadTlv.{InvoiceRoutingInfo, OutgoingBlindedPaths}
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload, PerHopPayload}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Feature, Features, MilliSatoshi, ShortChannelId, UInt64, randomBytes32, randomKey}
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}

import scala.util.{Failure, Success}

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
  sealed trait NodeRelayPacket extends RelayPacket {
    def add: UpdateAddHtlc
    def outerPayload: FinalPayload.Standard
    def innerPayload: IntermediatePayload.NodeRelay
  }
  case class RelayToTrampolinePacket(add: UpdateAddHtlc, outerPayload: FinalPayload.Standard, innerPayload: IntermediatePayload.NodeRelay.Standard, nextPacket: OnionRoutingPacket) extends NodeRelayPacket
  case class RelayToBlindedTrampolinePacket(add: UpdateAddHtlc, outerPayload: FinalPayload.Standard, innerPayload: IntermediatePayload.NodeRelay.Blinded, nextPacket: OnionRoutingPacket) extends NodeRelayPacket
  case class RelayToNonTrampolinePacket(add: UpdateAddHtlc, outerPayload: FinalPayload.Standard, innerPayload: IntermediatePayload.NodeRelay.ToNonTrampoline) extends NodeRelayPacket
  case class RelayToBlindedPathsPacket(add: UpdateAddHtlc, outerPayload: FinalPayload.Standard, innerPayload: IntermediatePayload.NodeRelay.ToBlindedPaths) extends NodeRelayPacket
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

  case class DecodedEncryptedRecipientData(payload: TlvStream[RouteBlindingEncryptedDataTlv], nextPathKey: PublicKey)

  private[payment] def decryptEncryptedRecipientData(add: UpdateAddHtlc, privateKey: PrivateKey, payload: TlvStream[OnionPaymentPayloadTlv], encryptedRecipientData: ByteVector): Either[FailureMessage, DecodedEncryptedRecipientData] = {
    if (add.pathKey_opt.isDefined && payload.get[OnionPaymentPayloadTlv.PathKey].isDefined) {
      Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
    } else {
      val pathKey_opt = add.pathKey_opt.orElse(payload.get[OnionPaymentPayloadTlv.PathKey].map(_.publicKey))
      decryptEncryptedRecipientData(add, privateKey, pathKey_opt, encryptedRecipientData)
    }
  }

  private def decryptEncryptedRecipientData(add: UpdateAddHtlc, privateKey: PrivateKey, pathKey_opt: Option[PublicKey], encryptedRecipientData: ByteVector): Either[FailureMessage, DecodedEncryptedRecipientData] = {
    pathKey_opt match {
      case Some(pathKey) => RouteBlindingEncryptedDataCodecs.decode(privateKey, pathKey, encryptedRecipientData) match {
        case Left(_) =>
          // There are two possibilities in this case:
          //  - the path key is invalid: the sender or the previous node is buggy or malicious
          //  - the encrypted data is invalid: the sender, the previous node or the recipient must be buggy or malicious
          Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
        case Right(decoded) => Right(DecodedEncryptedRecipientData(decoded.tlvs, decoded.nextPathKey))
      }
      case None =>
        // The sender is trying to use route blinding, but we didn't receive the path key used to derive
        // the decryption key. The sender or the previous peer is buggy or malicious.
        Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
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
  def decrypt(add: UpdateAddHtlc, privateKey: PrivateKey, features: Features[Feature]): Either[FailureMessage, IncomingPaymentPacket] = {
    // We first derive the decryption key used to peel the outer onion.
    val outerOnionDecryptionKey = add.pathKey_opt match {
      case Some(blinding) => Sphinx.RouteBlinding.derivePrivateKey(privateKey, blinding)
      case None => privateKey
    }
    decryptOnion(add.paymentHash, outerOnionDecryptionKey, add.onionRoutingPacket).flatMap {
      case DecodedOnionPacket(payload, Some(nextPacket)) =>
        // We are an intermediate node: we need to relay to one of our peers.
        payload.get[OnionPaymentPayloadTlv.EncryptedRecipientData] match {
          case Some(_) if !features.hasFeature(Features.RouteBlinding) => Left(InvalidOnionPayload(UInt64(10), 0))
          case Some(encrypted) =>
            // We are inside a blinded path: channel relay information is encrypted.
            decryptEncryptedRecipientData(add, privateKey, payload, encrypted.data).flatMap {
              case DecodedEncryptedRecipientData(blindedPayload, nextPathKey) =>
                validateBlindedChannelRelayPayload(add, payload, blindedPayload, nextPathKey, nextPacket).flatMap {
                  case ChannelRelayPacket(_, payload, nextPacket) if payload.outgoing == Right(ShortChannelId.toSelf) =>
                    decrypt(add.copy(onionRoutingPacket = nextPacket, tlvStream = add.tlvStream.copy(records = Set(UpdateAddHtlcTlv.PathKey(nextPathKey)))), privateKey, features)
                  case relayPacket => Right(relayPacket)
                }
            }
          case None if add.pathKey_opt.isDefined => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
          case None =>
            // We are not inside a blinded path: channel relay information is directly available.
            IntermediatePayload.ChannelRelay.Standard.validate(payload).left.map(_.failureMessage).map(payload => ChannelRelayPacket(add, payload, nextPacket))
        }
      case DecodedOnionPacket(payload, None) =>
        // We are the final node for the outer onion, so we are either:
        //  - the final recipient of the payment.
        //  - an intermediate trampoline node.
        payload.get[OnionPaymentPayloadTlv.EncryptedRecipientData] match {
          case Some(_) if !features.hasFeature(Features.RouteBlinding) => Left(InvalidOnionPayload(UInt64(10), 0))
          case Some(encrypted) =>
            // We are the final recipient of a blinded payment.
            decryptEncryptedRecipientData(add, privateKey, payload, encrypted.data).flatMap {
              case DecodedEncryptedRecipientData(blindedPayload, _) => validateBlindedFinalPayload(add, payload, blindedPayload)
            }
          case None if add.pathKey_opt.isDefined => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
          case None =>
            // We check if the payment is using trampoline: if it is, we may not be the final recipient.
            val trampolinePacket_opt = payload.get[OnionPaymentPayloadTlv.TrampolineOnion].map(_.packet).orElse(payload.get[OnionPaymentPayloadTlv.LegacyTrampolineOnion].map(_.packet))
            trampolinePacket_opt match {
              case Some(trampolinePacket) =>
                val outerPayload = payload.get[OnionPaymentPayloadTlv.PaymentData] match {
                  case Some(_) => payload
                  // The spec allows omitting the payment_secret field when not using MPP to reach the trampoline node.
                  // We made the payment_secret field mandatory, which lets us factor a lot of our receiving code.
                  // We simply insert a dummy one, which doesn't have any drawback since the sender is using a single
                  // part payment.
                  case None =>
                    val dummyPaymentSecret = randomBytes32()
                    val totalAmount = payload.get[OnionPaymentPayloadTlv.AmountToForward].map(_.amount).getOrElse(add.amountMsat)
                    payload.copy(records = payload.records + OnionPaymentPayloadTlv.PaymentData(dummyPaymentSecret, totalAmount))
                }
                // If we are an intermediate trampoline node inside a blinded path, the payer doesn't know our node_id
                // and has encrypted the trampoline onion to our blinded node_id: in that case, the previous trampoline
                // node will provide the path key in the outer onion.
                val trampolineOnionDecryptionKey = payload.get[OnionPaymentPayloadTlv.PathKey].map(_.publicKey) match {
                  case Some(pathKey) => Sphinx.RouteBlinding.derivePrivateKey(privateKey, pathKey)
                  case None => privateKey
                }
                decryptOnion(add.paymentHash, trampolineOnionDecryptionKey, trampolinePacket).flatMap {
                  case DecodedOnionPacket(innerPayload, Some(next)) =>
                    // We are an intermediate trampoline node.
                    if (innerPayload.get[InvoiceRoutingInfo].isDefined) {
                      // The payment recipient doesn't support trampoline.
                      // They can be reached with the invoice data provided.
                      // The payer is a wallet using the legacy trampoline feature.
                      validateTrampolineToNonTrampoline(add, outerPayload, innerPayload)
                    } else {
                      // The recipient supports trampoline (and may support blinded payments).
                      validateNodeRelay(add, privateKey, outerPayload, innerPayload, next)
                    }
                  case DecodedOnionPacket(innerPayload, None) =>
                    if (innerPayload.get[OutgoingBlindedPaths].isDefined) {
                      // The payment recipient doesn't support trampoline.
                      // They can be reached using the blinded paths provided.
                      validateTrampolineToBlindedPaths(add, outerPayload, innerPayload)
                    } else if (innerPayload.get[InvoiceRoutingInfo].isDefined) {
                      // The payment recipient doesn't support trampoline.
                      // They can be reached with the invoice data provided.
                      validateTrampolineToNonTrampoline(add, outerPayload, innerPayload)
                    } else {
                      // We're the final recipient of this trampoline payment (which may be blinded).
                      validateTrampolineFinalPayload(add, privateKey, outerPayload, innerPayload)
                    }
                }
              case None =>
                // We are the final recipient of a standard (non-blinded, non-trampoline) payment.
                validateFinalPayload(add, payload)
            }
        }
    }
  }

  private def validateBlindedChannelRelayPayload(add: UpdateAddHtlc,
                                                 payload: TlvStream[OnionPaymentPayloadTlv],
                                                 blindedPayload: TlvStream[RouteBlindingEncryptedDataTlv],
                                                 nextPathKey: PublicKey,
                                                 nextPacket: OnionRoutingPacket): Either[FailureMessage, ChannelRelayPacket] = {
    IntermediatePayload.ChannelRelay.Blinded.validate(payload, blindedPayload, nextPathKey).left.map(_.failureMessage).flatMap {
      case payload if add.amountMsat < payload.paymentRelayData.paymentConstraints.minAmount => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if add.cltvExpiry > payload.paymentRelayData.paymentConstraints.maxCltvExpiry => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if !Features.areCompatible(Features.empty, payload.paymentRelayData.allowedFeatures) => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload => Right(ChannelRelayPacket(add, payload, nextPacket))
    }
  }

  private def validateFinalPayload(add: UpdateAddHtlc, payload: TlvStream[OnionPaymentPayloadTlv]): Either[FailureMessage, FinalPacket] = {
    FinalPayload.Standard.validate(payload).left.map(_.failureMessage).flatMap {
      case payload if add.amountMsat < payload.amount => Left(FinalIncorrectHtlcAmount(add.amountMsat))
      case payload if add.cltvExpiry < payload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
      case payload => Right(FinalPacket(add, payload))
    }
  }

  private def validateBlindedFinalPayload(add: UpdateAddHtlc, payload: TlvStream[OnionPaymentPayloadTlv], blindedPayload: TlvStream[RouteBlindingEncryptedDataTlv]): Either[FailureMessage, FinalPacket] = {
    FinalPayload.Blinded.validate(payload, blindedPayload).left.map(_.failureMessage).flatMap {
      case payload if payload.paymentConstraints_opt.exists(c => add.amountMsat < c.minAmount) => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if payload.paymentConstraints_opt.exists(c => c.maxCltvExpiry < add.cltvExpiry) => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if !Features.areCompatible(Features.empty, payload.allowedFeatures) => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if add.cltvExpiry < payload.expiry => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload => Right(FinalPacket(add, payload))
    }
  }

  private def validateTrampolineFinalPayload(add: UpdateAddHtlc, privateKey: PrivateKey, outerPayload: TlvStream[OnionPaymentPayloadTlv], innerPayload: TlvStream[OnionPaymentPayloadTlv]): Either[FailureMessage, FinalPacket] = {
    // The outer payload cannot use route blinding, but the inner payload may.
    FinalPayload.Standard.validate(outerPayload).left.map(_.failureMessage).flatMap {
      case outerPayload if add.amountMsat < outerPayload.amount => Left(FinalIncorrectHtlcAmount(add.amountMsat))
      case outerPayload if add.cltvExpiry < outerPayload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
      case outerPayload =>
        innerPayload.get[OnionPaymentPayloadTlv.EncryptedRecipientData] match {
          case Some(encrypted) =>
            decryptEncryptedRecipientData(add, privateKey, outerPayload.records, encrypted.data).flatMap {
              case DecodedEncryptedRecipientData(blindedPayload, _) => validateBlindedFinalPayload(add, innerPayload, blindedPayload)
            }
          case None =>
            FinalPayload.Standard.validate(innerPayload).left.map(_.failureMessage).flatMap {
              case innerPayload if outerPayload.expiry < innerPayload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry)) // previous trampoline didn't forward the right expiry
              case innerPayload if outerPayload.totalAmount < innerPayload.amount => Left(FinalIncorrectHtlcAmount(outerPayload.totalAmount)) // previous trampoline didn't forward the right amount
              case innerPayload =>
                // We merge contents from the outer and inner payloads.
                // We must use the inner payload's total amount and payment secret because the payment may be split between multiple trampoline payments (#reckless).
                val trampolinePacket = outerPayload.records.get[OnionPaymentPayloadTlv.TrampolineOnion].map(_.packet)
                Right(FinalPacket(add, FinalPayload.Standard.createPayload(outerPayload.amount, innerPayload.totalAmount, innerPayload.expiry, innerPayload.paymentSecret, innerPayload.paymentMetadata, trampolinePacket)))
            }
        }
    }
  }

  private def validateNodeRelay(add: UpdateAddHtlc, privateKey: PrivateKey, outerPayload: TlvStream[OnionPaymentPayloadTlv], innerPayload: TlvStream[OnionPaymentPayloadTlv], next: OnionRoutingPacket): Either[FailureMessage, IncomingPaymentPacket] = {
    // The outer payload cannot use route blinding, but the inner payload may.
    FinalPayload.Standard.validate(outerPayload).left.map(_.failureMessage).flatMap {
      case outerPayload if add.amountMsat < outerPayload.amount => Left(FinalIncorrectHtlcAmount(add.amountMsat))
      case outerPayload if add.cltvExpiry != outerPayload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
      case outerPayload =>
        innerPayload.get[OnionPaymentPayloadTlv.EncryptedRecipientData] match {
          case Some(encrypted) =>
            // The path key can be found:
            //  - in the inner payload if we are the introduction node of the blinded path (provided by the payer).
            //  - in the outer payload if we are an intermediate node in the blinded path (provided by the previous trampoline node).
            val pathKey_opt = innerPayload.get[OnionPaymentPayloadTlv.PathKey].orElse(outerPayload.records.get[OnionPaymentPayloadTlv.PathKey]).map(_.publicKey)
            decryptEncryptedRecipientData(add, privateKey, pathKey_opt, encrypted.data).flatMap {
              case DecodedEncryptedRecipientData(blindedPayload, nextPathKey) =>
                IntermediatePayload.NodeRelay.Blinded.validate(innerPayload, blindedPayload, nextPathKey).left.map(_.failureMessage).map(innerPayload => RelayToBlindedTrampolinePacket(add, outerPayload, innerPayload, next))
            }
          case None =>
            IntermediatePayload.NodeRelay.Standard.validate(innerPayload).left.map(_.failureMessage).map(innerPayload => RelayToTrampolinePacket(add, outerPayload, innerPayload, next))
        }
    }
  }

  private def validateTrampolineToNonTrampoline(add: UpdateAddHtlc, outerPayload: TlvStream[OnionPaymentPayloadTlv], innerPayload: TlvStream[OnionPaymentPayloadTlv]): Either[FailureMessage, RelayToNonTrampolinePacket] = {
    FinalPayload.Standard.validate(outerPayload).left.map(_.failureMessage).flatMap { outerPayload =>
      IntermediatePayload.NodeRelay.ToNonTrampoline.validate(innerPayload).left.map(_.failureMessage).flatMap {
        case _ if add.amountMsat < outerPayload.amount => Left(FinalIncorrectHtlcAmount(add.amountMsat))
        case _ if add.cltvExpiry != outerPayload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
        case innerPayload => Right(RelayToNonTrampolinePacket(add, outerPayload, innerPayload))
      }
    }
  }

  private def validateTrampolineToBlindedPaths(add: UpdateAddHtlc, outerPayload: TlvStream[OnionPaymentPayloadTlv], innerPayload: TlvStream[OnionPaymentPayloadTlv]): Either[FailureMessage, RelayToBlindedPathsPacket] = {
    FinalPayload.Standard.validate(outerPayload).left.map(_.failureMessage).flatMap { outerPayload =>
      IntermediatePayload.NodeRelay.ToBlindedPaths.validate(innerPayload).left.map(_.failureMessage).flatMap {
        case _ if add.amountMsat < outerPayload.amount => Left(FinalIncorrectHtlcAmount(add.amountMsat))
        case _ if add.cltvExpiry != outerPayload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
        case innerPayload => Right(RelayToBlindedPathsPacket(add, outerPayload, innerPayload))
      }
    }
  }

}

/**
 * @param cmd             command to send the HTLC for this payment.
 * @param outgoingChannel channel to send the HTLC to.
 * @param sharedSecrets   shared secrets (used to decrypt the error in case of payment failure).
 */
case class OutgoingPaymentPacket(cmd: CMD_ADD_HTLC, outgoingChannel: ShortChannelId, sharedSecrets: Seq[Sphinx.SharedSecret])

/** Helpers to create outgoing payment packets. */
object OutgoingPaymentPacket {

  // @formatter:off
  case class NodePayload(nodeId: PublicKey, payload: PerHopPayload)
  /**
   * @param outerPathKey_opt (optional) path key that should be sent to the next node outside of the onion.
   *                          This is set when the next node is not the blinded path's introduction node.
   */
  case class PaymentPayloads(amount: MilliSatoshi, expiry: CltvExpiry, payloads: Seq[NodePayload], outerPathKey_opt: Option[PublicKey])

  sealed trait OutgoingPaymentError extends Throwable
  private case class CannotCreateOnion(message: String) extends OutgoingPaymentError { override def getMessage: String = message }
  case class InvalidRouteRecipient(expected: PublicKey, actual: PublicKey) extends OutgoingPaymentError { override def getMessage: String = s"expected route to $expected, got route to $actual" }
  case class IndirectRelayInBlindedRoute(nextNodeId: PublicKey) extends OutgoingPaymentError { override def getMessage: String = s"must relay directly to node $nextNodeId inside blinded route" }
  case class MissingBlindedHop(introductionNodeIds: Set[PublicKey]) extends OutgoingPaymentError { override def getMessage: String = s"expected blinded route using one of the following introduction nodes: ${introductionNodeIds.mkString(", ")}" }
  case object EmptyRoute extends OutgoingPaymentError { override def getMessage: String = "route cannot be empty" }
  // @formatter:on

  /**
   * Build an encrypted onion packet from onion payloads and node public keys.
   * If packetPayloadLength_opt is provided, the onion will be padded to the requested length.
   * In that case, packetPayloadLength_opt must be greater than the actual onion's content.
   */
  def buildOnion(payloads: Seq[NodePayload], associatedData: ByteVector32, packetPayloadLength_opt: Option[Int]): Either[OutgoingPaymentError, Sphinx.PacketAndSecrets] = {
    buildOnion(randomKey(), payloads, associatedData, packetPayloadLength_opt)
  }

  def buildOnion(sessionKey: PrivateKey, payloads: Seq[NodePayload], associatedData: ByteVector32, packetPayloadLength_opt: Option[Int]): Either[OutgoingPaymentError, Sphinx.PacketAndSecrets] = {
    val nodeIds = payloads.map(_.nodeId)
    val payloadsBin = payloads
      .map(p => PaymentOnionCodecs.perHopPayloadCodec.encode(p.payload.records))
      .map {
        case Attempt.Successful(bits) => bits.bytes
        case Attempt.Failure(cause) => return Left(CannotCreateOnion(cause.message))
      }
    val packetPayloadLength = packetPayloadLength_opt.getOrElse(Sphinx.payloadsTotalSize(payloadsBin))
    Sphinx.create(sessionKey, packetPayloadLength, nodeIds, payloadsBin, Some(associatedData)) match {
      case Failure(f) => Left(CannotCreateOnion(f.getMessage))
      case Success(packet) => Right(packet)
    }
  }

  /** Build the command to add an HTLC for the given recipient using the provided route. */
  def buildOutgoingPayment(origin: Origin.Hot, paymentHash: ByteVector32, route: Route, recipient: Recipient, confidence: Double): Either[OutgoingPaymentError, OutgoingPaymentPacket] = {
    for {
      payment <- recipient.buildPayloads(paymentHash, route)
      onion <- buildOnion(payment.payloads, paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)) // BOLT 2 requires that associatedData == paymentHash
    } yield {
      val cmd = CMD_ADD_HTLC(origin.replyTo, payment.amount, paymentHash, payment.expiry, onion.packet, payment.outerPathKey_opt, confidence, fundingFee_opt = None, origin, commit = true)
      OutgoingPaymentPacket(cmd, route.hops.head.shortChannelId, onion.sharedSecrets)
    }
  }

  private def buildHtlcFailure(nodeSecret: PrivateKey, reason: FailureReason, add: UpdateAddHtlc): Either[CannotExtractSharedSecret, ByteVector] = {
    extractSharedSecret(nodeSecret, add).map(ss => {
      reason match {
        case FailureReason.EncryptedDownstreamFailure(packet) =>
          ss.trampolineOnionSecret_opt match {
            case Some(trampolineOnionSecret) =>
              // If we are unable to decrypt the downstream failure and the payment is using trampoline, the failure is
              // intended for the payer. We encrypt it with the trampoline secret first and then the outer secret.
              Sphinx.FailurePacket.wrap(Sphinx.FailurePacket.wrap(packet, trampolineOnionSecret), ss.outerOnionSecret)
            case None => Sphinx.FailurePacket.wrap(packet, ss.outerOnionSecret)
          }
        case FailureReason.LocalFailure(failure) =>
          // This isn't a trampoline failure, so we only encrypt it for the node who created the outer onion.
          Sphinx.FailurePacket.create(ss.outerOnionSecret, failure)
        case FailureReason.LocalTrampolineFailure(failure) =>
          // This is a trampoline failure: we try to encrypt it to the node who created the trampoline onion.
          ss.trampolineOnionSecret_opt match {
            case Some(trampolineOnionSecret) => Sphinx.FailurePacket.wrap(Sphinx.FailurePacket.create(trampolineOnionSecret, failure), ss.outerOnionSecret)
            case None => Sphinx.FailurePacket.create(ss.outerOnionSecret, failure) // this shouldn't happen, we only generate trampoline failures when there was a trampoline onion
          }
      }
    })
  }

  private case class HtlcSharedSecrets(outerOnionSecret: ByteVector32, trampolineOnionSecret_opt: Option[ByteVector32])

  /**
   * We decrypt the onion again to extract the shared secret used to encrypt onion failures.
   * We could avoid this by storing the shared secret after the initial onion decryption, but we would have to store it
   * in the database since we must be able to fail HTLCs after restarting our node.
   * It's simpler to extract it again from the encrypted onion.
   */
  private def extractSharedSecret(nodeSecret: PrivateKey, add: UpdateAddHtlc): Either[CannotExtractSharedSecret, HtlcSharedSecrets] = {
    Sphinx.peel(nodeSecret, Some(add.paymentHash), add.onionRoutingPacket) match {
      case Right(Sphinx.DecryptedPacket(payload, _, outerOnionSecret)) =>
        // Let's look at the onion payload to see if it contains a trampoline onion.
        PaymentOnionCodecs.perHopPayloadCodec.decode(payload.bits) match {
          case Attempt.Successful(DecodeResult(perHopPayload, _)) =>
            // We try to extract the trampoline shared secret, if we can find one.
            val trampolineOnionSecret_opt = perHopPayload.get[OnionPaymentPayloadTlv.TrampolineOnion].map(_.packet).flatMap(trampolinePacket => {
              val trampolinePathKey_opt = perHopPayload.get[OnionPaymentPayloadTlv.PathKey].map(_.publicKey)
              val trampolineOnionDecryptionKey = trampolinePathKey_opt.map(pathKey => Sphinx.RouteBlinding.derivePrivateKey(nodeSecret, pathKey)).getOrElse(nodeSecret)
              Sphinx.peel(trampolineOnionDecryptionKey, Some(add.paymentHash), trampolinePacket).toOption.map(_.sharedSecret)
            })
            Right(HtlcSharedSecrets(outerOnionSecret, trampolineOnionSecret_opt))
          case Attempt.Failure(_) => Right(HtlcSharedSecrets(outerOnionSecret, None))
        }
      case Left(_) => Left(CannotExtractSharedSecret(add.channelId, add))
    }
  }

  def buildHtlcFailure(nodeSecret: PrivateKey, cmd: CMD_FAIL_HTLC, add: UpdateAddHtlc): Either[CannotExtractSharedSecret, HtlcFailureMessage] = {
    add.pathKey_opt match {
      case Some(_) =>
        // We are part of a blinded route and we're not the introduction node.
        val failure = InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket))
        Right(UpdateFailMalformedHtlc(add.channelId, add.id, failure.onionHash, failure.code))
      case None =>
        buildHtlcFailure(nodeSecret, cmd.reason, add).map(encryptedReason => UpdateFailHtlc(add.channelId, cmd.id, encryptedReason))
    }
  }

}
