/*
 * Copyright 2024 ACINQ SAS
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

package fr.acinq.eclair.payment.send

import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.OutgoingPaymentPacket.{NodePayload, buildOnion}
import fr.acinq.eclair.payment.PaymentSent.PartialPayment
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Router.RouteParams
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, EncodedNodeId, Features, Logs, MilliSatoshi, MilliSatoshiLong, NodeParams, ShortChannelId, randomBytes32, randomKey}
import scodec.bits.ByteVector

import java.util.UUID

/**
 * Created by t-bast on 15/10/2024.
 */

/**
 * This actor is responsible for sending a trampoline payment, using a trampoline node from one of our peers.
 * This is only meant to be used for tests: eclair nodes need to be able to relay and receive trampoline payments from
 * mobile wallets, but they don't need to be able to send trampoline payments since they can always compute the route
 * themselves.
 *
 * This actor thus uses a very simplified state machine to support tests: this is not a robust implementation of what
 * a mobile wallet should do when sending trampoline payments.
 */
object TrampolinePaymentLifecycle {

  // @formatter:off
  sealed trait Command
  case class SendPayment(replyTo: ActorRef[PaymentEvent], paymentId: UUID, trampolineNodeId: PublicKey, invoice: Invoice, routeParams: RouteParams) extends Command {
    require(invoice.amount_opt.nonEmpty, "amount-less invoices are not supported in trampoline tests")
  }
  private case class TrampolinePeerNotFound(trampolineNodeId: PublicKey) extends Command
  private case class WrappedPeerChannels(channels: Seq[Peer.ChannelInfo]) extends Command
  private case class WrappedAddHtlcResponse(response: CommandResponse[CMD_ADD_HTLC]) extends Command
  private case class WrappedHtlcSettled(result: RES_ADD_SETTLED[Origin.Hot, HtlcResult]) extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams, register: ActorRef[Register.ForwardNodeId[Peer.GetPeerChannels]]): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial {
        case cmd: SendPayment =>
          val mdc = Logs.mdc(
            category_opt = Some(Logs.LogCategory.PAYMENT),
            remoteNodeId_opt = Some(cmd.trampolineNodeId),
            paymentHash_opt = Some(cmd.invoice.paymentHash),
            paymentId_opt = Some(cmd.paymentId)
          )
          Behaviors.withMdc(mdc) {
            new TrampolinePaymentLifecycle(nodeParams, register, cmd, context).start()
          }
      }
    }

}

class TrampolinePaymentLifecycle private(nodeParams: NodeParams,
                                         register: ActorRef[Register.ForwardNodeId[Peer.GetPeerChannels]],
                                         cmd: TrampolinePaymentLifecycle.SendPayment,
                                         context: ActorContext[TrampolinePaymentLifecycle.Command]) {

  import TrampolinePayment._
  import TrampolinePaymentLifecycle._

  private val paymentHash = cmd.invoice.paymentHash
  private val totalAmount = cmd.invoice.amount_opt.get

  private val forwardNodeIdFailureAdapter = context.messageAdapter[Register.ForwardNodeIdFailure[Peer.GetPeerChannels]](_ => TrampolinePeerNotFound(cmd.trampolineNodeId))
  private val peerChannelsResponseAdapter = context.messageAdapter[Peer.PeerChannels](c => WrappedPeerChannels(c.channels))
  private val addHtlcAdapter = context.messageAdapter[CommandResponse[CMD_ADD_HTLC]](WrappedAddHtlcResponse)
  private val htlcSettledAdapter = context.messageAdapter[RES_ADD_SETTLED[Origin.Hot, HtlcResult]](WrappedHtlcSettled)

  def start(): Behavior[Command] = listChannels(attemptNumber = 0)

  private def listChannels(attemptNumber: Int): Behavior[Command] = {
    register ! Register.ForwardNodeId(forwardNodeIdFailureAdapter, cmd.trampolineNodeId, Peer.GetPeerChannels(peerChannelsResponseAdapter))
    Behaviors.receiveMessagePartial {
      case TrampolinePeerNotFound(nodeId) =>
        context.log.warn("could not send trampoline payment: we don't have channels with trampoline node {}", nodeId)
        cmd.replyTo ! PaymentFailed(cmd.paymentId, paymentHash, LocalFailure(totalAmount, Nil, new RuntimeException("no channels with trampoline node")) :: Nil)
        Behaviors.stopped
      case WrappedPeerChannels(channels) =>
        sendPayment(channels, attemptNumber)
    }
  }

  private def sendPayment(channels: Seq[Peer.ChannelInfo], attemptNumber: Int): Behavior[Command] = {
    val trampolineAmount = computeTrampolineAmount(totalAmount, attemptNumber, cmd.invoice)
    // We always use MPP to verify that the trampoline node is able to handle it.
    // This is a very naive way of doing MPP that simply splits the payment in two HTLCs.
    val filtered = channels.flatMap(c => {
      c.data match {
        case d: DATA_NORMAL if d.commitments.availableBalanceForSend > (trampolineAmount / 2) => Some(c)
        case _ => None
      }
    })
    val origin = Origin.Hot(htlcSettledAdapter.toClassic, Upstream.Local(cmd.paymentId))
    val expiry = CltvExpiry(nodeParams.currentBlockHeight) + CltvExpiryDelta(36)
    if (filtered.isEmpty) {
      context.log.warn("no usable channel with trampoline node {}", cmd.trampolineNodeId)
      cmd.replyTo ! PaymentFailed(cmd.paymentId, paymentHash, LocalFailure(totalAmount, Nil, new RuntimeException("no usable channel with trampoline node")) :: Nil)
      Behaviors.stopped
    } else {
      val amount1 = totalAmount / 2
      val channel1 = filtered.head
      val amount2 = totalAmount - amount1
      val channel2 = filtered.last
      // We generate a random secret to avoid leaking the invoice secret to the trampoline node.
      val trampolinePaymentSecret = randomBytes32()
      context.log.info("sending trampoline payment parts: {}->{}, {}->{}", channel1.data.channelId, amount1, channel2.data.channelId, amount2)
      val parts = Seq((amount1, channel1), (amount2, channel2)).map { case (amount, channelInfo) =>
        val outgoing = buildOutgoingPayment(cmd.trampolineNodeId, cmd.invoice, amount, expiry, Some(trampolinePaymentSecret), attemptNumber)
        val add = CMD_ADD_HTLC(addHtlcAdapter.toClassic, outgoing.trampolineAmount, paymentHash, outgoing.trampolineExpiry, outgoing.onion.packet, None, 1.0, None, origin, commit = true)
        channelInfo.channel ! add
        val channelId = channelInfo.data.asInstanceOf[DATA_NORMAL].channelId
        PartialPayment(cmd.paymentId, amount, computeFees(amount, attemptNumber, cmd.invoice), channelId, None)
      }
      waitForSettlement(remaining = 2, attemptNumber, parts)
    }
  }

  private def waitForSettlement(remaining: Int, attemptNumber: Int, parts: Seq[PartialPayment]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedAddHtlcResponse(response) => response match {
        case _: CommandSuccess[_] =>
          // HTLC was correctly sent out.
          Behaviors.same
        case failure: CommandFailure[_, Throwable] =>
          context.log.warn("HTLC could not be sent: {}", failure.t.getMessage)
          if (remaining > 1) {
            context.log.info("waiting for remaining HTLCs to complete")
            waitForSettlement(remaining - 1, attemptNumber, parts)
          } else {
            context.log.warn("trampoline payment failed")
            cmd.replyTo ! PaymentFailed(cmd.paymentId, paymentHash, LocalFailure(totalAmount, Nil, failure.t) :: Nil)
            Behaviors.stopped
          }
      }
      case WrappedHtlcSettled(result) => result.result match {
        case fulfill: HtlcResult.Fulfill =>
          context.log.info("HTLC was fulfilled")
          if (remaining > 1) {
            context.log.info("waiting for remaining HTLCs to be fulfilled")
            waitForSettlement(remaining - 1, attemptNumber, parts)
          } else {
            context.log.info("trampoline payment succeeded")
            cmd.replyTo ! PaymentSent(cmd.paymentId, paymentHash, fulfill.paymentPreimage, totalAmount, cmd.invoice.nodeId, parts)
            Behaviors.stopped
          }
        case fail: HtlcResult.Fail =>
          context.log.warn("received HTLC failure: {}", fail)
          if (remaining > 1) {
            context.log.info("waiting for remaining HTLCs to be failed")
            waitForSettlement(remaining - 1, attemptNumber, parts)
          } else {
            retryOrStop(attemptNumber + 1)
          }
      }
    }
  }

  private def retryOrStop(attemptNumber: Int): Behavior[Command] = {
    val nextFees = computeFees(totalAmount, attemptNumber, cmd.invoice)
    if (attemptNumber > 3) {
      context.log.warn("cannot retry trampoline payment: retries exceeded")
      cmd.replyTo ! PaymentFailed(cmd.paymentId, paymentHash, LocalFailure(totalAmount, Nil, new RuntimeException("maximum trampoline retries exceeded")) :: Nil)
      Behaviors.stopped
    } else if (cmd.routeParams.getMaxFee(totalAmount) < nextFees) {
      context.log.warn("cannot retry trampoline payment: maximum fees exceeded ({} > {})", nextFees, cmd.routeParams.getMaxFee(totalAmount))
      cmd.replyTo ! PaymentFailed(cmd.paymentId, paymentHash, LocalFailure(totalAmount, Nil, new RuntimeException("maximum trampoline fees exceeded")) :: Nil)
      Behaviors.stopped
    } else {
      context.log.info("retrying trampoline payment with fees={}", nextFees)
      listChannels(attemptNumber)
    }
  }

}

object TrampolinePayment {

  case class OutgoingPayment(trampolineAmount: MilliSatoshi, trampolineExpiry: CltvExpiry, onion: Sphinx.PacketAndSecrets, trampolineOnion: Sphinx.PacketAndSecrets)

  def buildOutgoingPayment(trampolineNodeId: PublicKey, invoice: Invoice, expiry: CltvExpiry): OutgoingPayment = {
    buildOutgoingPayment(trampolineNodeId, invoice, invoice.amount_opt.get, expiry, trampolinePaymentSecret_opt = Some(randomBytes32()), attemptNumber = 0)
  }

  def buildOutgoingPayment(trampolineNodeId: PublicKey, invoice: Invoice, amount: MilliSatoshi, expiry: CltvExpiry, trampolinePaymentSecret_opt: Option[ByteVector32], attemptNumber: Int): OutgoingPayment = {
    val totalAmount = invoice.amount_opt.get
    val trampolineOnion = invoice match {
      case invoice: Bolt11Invoice if invoice.features.hasFeature(Features.TrampolinePayment) =>
        val finalPayload = PaymentOnion.FinalPayload.Standard.createPayload(amount, totalAmount, expiry, invoice.paymentSecret, invoice.paymentMetadata)
        val trampolinePayload = PaymentOnion.IntermediatePayload.NodeRelay.Standard(totalAmount, expiry, invoice.nodeId)
        buildOnion(NodePayload(trampolineNodeId, trampolinePayload) :: NodePayload(invoice.nodeId, finalPayload) :: Nil, invoice.paymentHash, None).toOption.get
      case invoice: Bolt11Invoice =>
        val trampolinePayload = PaymentOnion.IntermediatePayload.NodeRelay.ToNonTrampoline(totalAmount, totalAmount, expiry, invoice.nodeId, invoice)
        buildOnion(NodePayload(trampolineNodeId, trampolinePayload) :: Nil, invoice.paymentHash, None).toOption.get
      case invoice: Bolt12Invoice if invoice.features.hasFeature(Features.TrampolinePayment) =>
        val path = invoice.blindedPaths.head
        require(path.route.firstNodeId.isInstanceOf[EncodedNodeId.WithPublicKey], "blinded path must provide the introduction node_id")
        val introductionNodeId = path.route.firstNodeId.asInstanceOf[EncodedNodeId.WithPublicKey].publicKey
        // We're creating blinded payloads for an outgoing payment: we don't have access to the decrypted data, so we use dummy data.
        val dummyPathId = RouteBlindingEncryptedDataTlv.PathId(ByteVector.empty)
        val dummyPaymentRelayData = BlindedRouteData.PaymentRelayData(TlvStream(
          RouteBlindingEncryptedDataTlv.OutgoingChannelId(ShortChannelId(0)),
          RouteBlindingEncryptedDataTlv.PaymentRelay(CltvExpiryDelta(0), 0, 0 msat),
          RouteBlindingEncryptedDataTlv.PaymentConstraints(CltvExpiry(0), 0 msat)
        ))
        val finalPayload = NodePayload(path.route.blindedHops.last.blindedPublicKey, PaymentOnion.FinalPayload.Blinded(
          records = TlvStream(
            OnionPaymentPayloadTlv.AmountToForward(totalAmount),
            OnionPaymentPayloadTlv.OutgoingCltv(expiry),
            OnionPaymentPayloadTlv.TotalAmount(totalAmount),
            OnionPaymentPayloadTlv.EncryptedRecipientData(path.route.blindedHops.last.encryptedPayload),
          ),
          blindedRecords = TlvStream(dummyPathId),
        ))
        val intermediatePayloads = path.route.blindedHops.drop(1).dropRight(1).map { b =>
          NodePayload(b.blindedPublicKey, PaymentOnion.IntermediatePayload.ChannelRelay.Blinded(
            records = TlvStream(OnionPaymentPayloadTlv.EncryptedRecipientData(b.encryptedPayload)),
            paymentRelayData = dummyPaymentRelayData,
            nextPathKey = randomKey().publicKey,
          ))
        }
        val introductionPayload = NodePayload(introductionNodeId, PaymentOnion.IntermediatePayload.ChannelRelay.Blinded(
          records = TlvStream(OnionPaymentPayloadTlv.EncryptedRecipientData(path.route.encryptedPayloads.head), OnionPaymentPayloadTlv.PathKey(path.route.firstPathKey)),
          paymentRelayData = dummyPaymentRelayData,
          nextPathKey = randomKey().publicKey,
        ))
        // We use our trampoline node to reach the introduction node of the blinded path.
        val blindedAmount = totalAmount + path.paymentInfo.fee(totalAmount)
        val blindedExpiry = expiry + path.paymentInfo.cltvExpiryDelta
        val trampolinePayload = NodePayload(trampolineNodeId, PaymentOnion.IntermediatePayload.NodeRelay.Standard(blindedAmount, blindedExpiry, introductionNodeId))
        buildOnion(trampolinePayload +: introductionPayload +: intermediatePayloads :+ finalPayload, invoice.paymentHash, None).toOption.get
      case invoice: Bolt12Invoice =>
        val trampolinePayload = PaymentOnion.IntermediatePayload.NodeRelay.ToBlindedPaths(totalAmount, expiry, invoice)
        buildOnion(NodePayload(trampolineNodeId, trampolinePayload) :: Nil, invoice.paymentHash, None).toOption.get
    }
    val trampolineAmount = computeTrampolineAmount(amount, attemptNumber, invoice)
    val trampolineTotalAmount = computeTrampolineAmount(totalAmount, attemptNumber, invoice)
    val trampolineExpiry = computeTrampolineExpiry(expiry, attemptNumber, invoice)
    val payload = trampolinePaymentSecret_opt match {
      case Some(trampolinePaymentSecret) => PaymentOnion.FinalPayload.Standard.createTrampolinePayload(trampolineAmount, trampolineTotalAmount, trampolineExpiry, trampolinePaymentSecret, trampolineOnion.packet, trampolinePathKey_opt = None)
      case None => PaymentOnion.TrampolineWithoutMppPayload.create(trampolineAmount, trampolineExpiry, trampolineOnion.packet)
    }
    val paymentOnion = buildOnion(NodePayload(trampolineNodeId, payload) :: Nil, invoice.paymentHash, Some(PaymentOnionCodecs.paymentOnionPayloadLength)).toOption.get
    OutgoingPayment(trampolineAmount, trampolineExpiry, paymentOnion, trampolineOnion)
  }

  def computeFees(amount: MilliSatoshi, attemptNumber: Int, invoice: Invoice): MilliSatoshi = invoice match {
    case _: Bolt11Invoice =>
      // We increase the fees paid by 0.2% of the amount sent at each attempt.
      amount * (attemptNumber + 1) * 0.002
    case _ =>
      // We increase the fees paid by 1% of the amount sent at each attempt.
      // That's because integration tests are much easier to write if the first attempt uses large enough fees and
      // expiry because we must inject a plugin actor to respond to HandleInvoiceRequest and HandlePayment.
      amount * (attemptNumber + 1) * 0.01
  }

  def computeTrampolineAmount(amount: MilliSatoshi, attemptNumber: Int, invoice: Invoice): MilliSatoshi = amount + computeFees(amount, attemptNumber, invoice)

  // We increase the trampoline expiry delta at each attempt.
  private def computeTrampolineExpiry(expiry: CltvExpiry, attemptNumber: Int, invoice: Invoice): CltvExpiry = invoice match {
    case _: Bolt11Invoice => expiry + CltvExpiryDelta(144) * (attemptNumber + 1)
    case _ => expiry + CltvExpiryDelta(432) + CltvExpiryDelta(144) * (attemptNumber + 1)
  }

}
