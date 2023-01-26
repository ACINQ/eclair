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
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.eclair.channel.{CMD_ADD_HTLC, CMD_FAIL_HTLC, CannotExtractSharedSecret, Origin}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.send.Recipient
import fr.acinq.eclair.router.Router.{BlindedHop, ChannelHop, Route}
import fr.acinq.eclair.wire.protocol.PaymentOnion.{FinalPayload, IntermediatePayload, PerHopPayload}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Feature, Features, MilliSatoshi, ShortChannelId, UInt64, randomKey}
import scodec.bits.ByteVector
import scodec.{Attempt, DecodeResult}

import java.util.UUID
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
  def decrypt(add: UpdateAddHtlc, privateKey: PrivateKey, features: Features[Feature]): Either[FailureMessage, IncomingPaymentPacket] = {
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
                    decrypt(add.copy(onionRoutingPacket = nextPacket, tlvStream = add.tlvStream.copy(records = Set(UpdateAddHtlcTlv.BlindingPoint(nextBlinding)))), privateKey, features)
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
      case payload if add.amountMsat < payload.amount => Left(FinalIncorrectHtlcAmount(add.amountMsat))
      case payload if add.cltvExpiry < payload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
      case payload => Right(FinalPacket(add, payload))
    }
  }

  private def validateBlindedFinalPayload(add: UpdateAddHtlc, payload: TlvStream[OnionPaymentPayloadTlv], blindedPayload: TlvStream[RouteBlindingEncryptedDataTlv]): Either[FailureMessage, FinalPacket] = {
    FinalPayload.Blinded.validate(payload, blindedPayload).left.map(_.failureMessage).flatMap {
      case payload if add.amountMsat < payload.paymentConstraints.minAmount => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if add.cltvExpiry > payload.paymentConstraints.maxCltvExpiry => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if !Features.areCompatible(Features.empty, payload.allowedFeatures) => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if add.amountMsat < payload.amount => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload if add.cltvExpiry < payload.expiry => Left(InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket)))
      case payload => Right(FinalPacket(add, payload))
    }
  }

  private def validateTrampolineFinalPayload(add: UpdateAddHtlc, outerPayload: TlvStream[OnionPaymentPayloadTlv], innerPayload: TlvStream[OnionPaymentPayloadTlv]): Either[FailureMessage, FinalPacket] = {
    // The outer payload cannot use route blinding, but the inner payload may (but it's not supported yet).
    FinalPayload.Standard.validate(outerPayload).left.map(_.failureMessage).flatMap { outerPayload =>
      FinalPayload.Standard.validate(innerPayload).left.map(_.failureMessage).flatMap {
        case _ if add.amountMsat < outerPayload.amount => Left(FinalIncorrectHtlcAmount(add.amountMsat))
        case _ if add.cltvExpiry < outerPayload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry))
        case innerPayload if outerPayload.expiry < innerPayload.expiry => Left(FinalIncorrectCltvExpiry(add.cltvExpiry)) // previous trampoline didn't forward the right expiry
        case innerPayload if outerPayload.totalAmount < innerPayload.amount => Left(FinalIncorrectHtlcAmount(outerPayload.totalAmount)) // previous trampoline didn't forward the right amount
        case innerPayload =>
          // We merge contents from the outer and inner payloads.
          // We must use the inner payload's total amount and payment secret because the payment may be split between multiple trampoline payments (#reckless).
          Right(FinalPacket(add, FinalPayload.Standard.createPayload(outerPayload.amount, innerPayload.totalAmount, innerPayload.expiry, innerPayload.paymentSecret, innerPayload.paymentMetadata)))
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

/**
 * @param cmd             command to send the HTLC for this payment.
 * @param outgoingChannel channel to send the HTLC to.
 * @param sharedSecrets   shared secrets (used to decrypt the error in case of payment failure).
 */
case class OutgoingPaymentPacket(cmd: CMD_ADD_HTLC, outgoingChannel: ShortChannelId, sharedSecrets: Seq[(ByteVector32, PublicKey)])

/** Helpers to create outgoing payment packets. */
object OutgoingPaymentPacket {

  // @formatter:off
  case class NodePayload(nodeId: PublicKey, payload: PerHopPayload)
  case class PaymentPayloads(amount: MilliSatoshi, expiry: CltvExpiry, payloads: Seq[NodePayload])

  sealed trait OutgoingPaymentError extends Throwable
  case class CannotCreateOnion(message: String) extends OutgoingPaymentError { override def getMessage: String = message }
  case class CannotDecryptBlindedRoute(message: String) extends OutgoingPaymentError { override def getMessage: String = message }
  case class InvalidRouteRecipient(expected: PublicKey, actual: PublicKey) extends OutgoingPaymentError { override def getMessage: String = s"expected route to $expected, got route to $actual" }
  case class MissingTrampolineHop(trampolineNodeId: PublicKey) extends OutgoingPaymentError { override def getMessage: String = s"expected route to trampoline node $trampolineNodeId" }
  case class MissingBlindedHop(introductionNodeIds: Set[PublicKey]) extends OutgoingPaymentError { override def getMessage: String = s"expected blinded route using one of the following introduction nodes: ${introductionNodeIds.mkString(", ")}" }
  case object EmptyRoute extends OutgoingPaymentError { override def getMessage: String = "route cannot be empty" }

  sealed trait Upstream
  object Upstream {
    case class Local(id: UUID) extends Upstream
    case class Trampoline(adds: Seq[UpdateAddHtlc]) extends Upstream {
      val amountIn: MilliSatoshi = adds.map(_.amountMsat).sum
      val expiryIn: CltvExpiry = adds.map(_.cltvExpiry).min
    }
  }
  // @formatter:on

  /** Build an encrypted onion packet from onion payloads and node public keys. */
  def buildOnion(packetPayloadLength: Int, payloads: Seq[NodePayload], associatedData: ByteVector32): Either[OutgoingPaymentError, Sphinx.PacketAndSecrets] = {
    val sessionKey = randomKey()
    val nodeIds = payloads.map(_.nodeId)
    val payloadsBin = payloads
      .map(p => PaymentOnionCodecs.perHopPayloadCodec.encode(p.payload.records))
      .map {
        case Attempt.Successful(bits) => bits.bytes
        case Attempt.Failure(cause) => return Left(CannotCreateOnion(cause.message))
      }
    Sphinx.create(sessionKey, packetPayloadLength, nodeIds, payloadsBin, Some(associatedData)) match {
      case Failure(f) => Left(CannotCreateOnion(f.getMessage))
      case Success(packet) => Right(packet)
    }
  }

  private case class OutgoingPaymentWithChannel(shortChannelId: ShortChannelId, nextBlinding_opt: Option[PublicKey], payment: PaymentPayloads)

  private def getOutgoingChannel(privateKey: PrivateKey, payment: PaymentPayloads, route: Route): Either[OutgoingPaymentError, OutgoingPaymentWithChannel] = {
    route.hops.headOption match {
      case Some(hop) => Right(OutgoingPaymentWithChannel(hop.shortChannelId, None, payment))
      case None => route.finalHop_opt match {
        case Some(hop: BlindedHop) =>
          // We are the introduction node of the blinded route: we need to decrypt the first payload.
          val firstBlinding = hop.route.introductionNode.blindingEphemeralKey
          val firstEncryptedPayload = hop.route.introductionNode.encryptedPayload
          RouteBlindingEncryptedDataCodecs.decode(privateKey, firstBlinding, firstEncryptedPayload) match {
            case Left(e) => Left(CannotDecryptBlindedRoute(e.message))
            case Right(decoded) =>
              val tlvs = TlvStream[OnionPaymentPayloadTlv](OnionPaymentPayloadTlv.EncryptedRecipientData(firstEncryptedPayload), OnionPaymentPayloadTlv.BlindingPoint(firstBlinding))
              IntermediatePayload.ChannelRelay.Blinded.validate(tlvs, decoded.tlvs, decoded.nextBlinding) match {
                case Left(e) => Left(CannotDecryptBlindedRoute(e.failureMessage.message))
                case Right(payload) =>
                  val payment1 = PaymentPayloads(payload.amountToForward(payment.amount), payload.outgoingCltv(payment.expiry), payment.payloads.tail)
                  Right(OutgoingPaymentWithChannel(payload.outgoingChannelId, Some(decoded.nextBlinding), payment1))
              }
          }
        case _ => Left(EmptyRoute)
      }
    }
  }

  /** Build the command to add an HTLC for the given recipient using the provided route. */
  def buildOutgoingPayment(replyTo: ActorRef, privateKey: PrivateKey, upstream: Upstream, paymentHash: ByteVector32, route: Route, recipient: Recipient): Either[OutgoingPaymentError, OutgoingPaymentPacket] = {
    for {
      paymentTmp <- recipient.buildPayloads(paymentHash, route)
      outgoing <- getOutgoingChannel(privateKey, paymentTmp, route)
      onion <- buildOnion(PaymentOnionCodecs.paymentOnionPayloadLength, outgoing.payment.payloads, paymentHash) // BOLT 2 requires that associatedData == paymentHash
    } yield {
      val cmd = CMD_ADD_HTLC(replyTo, outgoing.payment.amount, paymentHash, outgoing.payment.expiry, onion.packet, outgoing.nextBlinding_opt, Origin.Hot(replyTo, upstream), commit = true)
      OutgoingPaymentPacket(cmd, outgoing.shortChannelId, onion.sharedSecrets)
    }
  }

  private def buildHtlcFailure(nodeSecret: PrivateKey, reason: Either[ByteVector, FailureMessage], add: UpdateAddHtlc): Either[CannotExtractSharedSecret, ByteVector] = {
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

  def buildHtlcFailure(nodeSecret: PrivateKey, cmd: CMD_FAIL_HTLC, add: UpdateAddHtlc): Either[CannotExtractSharedSecret, HtlcFailureMessage] = {
    add.blinding_opt match {
      case Some(_) =>
        // We are part of a blinded route and we're not the introduction node.
        val failure = InvalidOnionBlinding(Sphinx.hash(add.onionRoutingPacket))
        Right(UpdateFailMalformedHtlc(add.channelId, add.id, failure.onionHash, failure.code))
      case None =>
        buildHtlcFailure(nodeSecret, cmd.reason, add).map(encryptedReason => UpdateFailHtlc(add.channelId, cmd.id, encryptedReason))
    }
  }

}
