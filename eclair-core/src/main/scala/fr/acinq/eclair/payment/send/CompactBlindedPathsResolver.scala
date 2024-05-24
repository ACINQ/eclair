package fr.acinq.eclair.payment.send

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.Helpers.getRelayFees
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.payment.PaymentBlindedRoute
import fr.acinq.eclair.payment.send.CompactBlindedPathsResolver._
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.RouteBlindingDecryptedData
import fr.acinq.eclair.wire.protocol.{BlindedRouteData, OfferTypes, RouteBlindingEncryptedDataCodecs}
import fr.acinq.eclair.{EncodedNodeId, NodeParams}

import scala.annotation.tailrec

object CompactBlindedPathsResolver {
  // @formatter:off
  sealed trait Command
  case class Resolve(replyTo: typed.ActorRef[Seq[ResolvedPath]], blindedPaths: Seq[PaymentBlindedRoute]) extends Command
  private case class WrappedNodeId(nodeId_opt: Option[PublicKey]) extends Command

  case class ResolvedPath(blindedPath: PaymentBlindedRoute, introductionNodeId: PublicKey)
  // @formatter:on

  def apply(nodeParams: NodeParams, router: ActorRef, register: ActorRef): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, Resolve(replyTo, blindedPaths)) =>
        val resolver = new CompactBlindedPathsResolver(nodeParams, replyTo, router, register, context)
        resolver.resolveCompactBlindedPaths(blindedPaths, Nil)
    }
  }
}

private class CompactBlindedPathsResolver(nodeParams: NodeParams,
                                          replyTo: typed.ActorRef[Seq[ResolvedPath]],
                                          router: ActorRef,
                                          register: ActorRef,
                                          context: ActorContext[Command]) {
  @tailrec
  private def resolveCompactBlindedPaths(toResolve: Seq[PaymentBlindedRoute],
                                         resolved: Seq[ResolvedPath]): Behavior[Command] = {
    toResolve.headOption match {
      case Some(PaymentBlindedRoute(BlindedRoute(EncodedNodeId.Plain(publicKey), _, blindedNodes), _)) if publicKey == nodeParams.nodeId && blindedNodes.length == 1 =>
        context.log.warn("trying to send a blinded payment to ourselves")
        resolveCompactBlindedPaths(toResolve.tail, resolved)
      case Some(PaymentBlindedRoute(BlindedRoute(EncodedNodeId.Plain(publicKey), blindingKey, blindedNodes), paymentInfo)) if publicKey == nodeParams.nodeId =>
        RouteBlindingEncryptedDataCodecs.decode(nodeParams.privateKey, blindingKey, blindedNodes.head.encryptedPayload) match {
          case Left(_) => resolveCompactBlindedPaths(toResolve.tail, resolved)
          case Right(RouteBlindingDecryptedData(decrypted, nextBlinding)) =>
            BlindedRouteData.validatePaymentRelayData(decrypted) match {
              case Left(_) => resolveCompactBlindedPaths(toResolve.tail, resolved)
              case Right(paymentRelayData) =>
                val nextFeeBase = paymentInfo.feeBase - paymentRelayData.paymentRelay.feeBase
                val nextFeeProportionalMillionths = paymentInfo.feeProportionalMillionths - paymentRelayData.paymentRelay.feeProportionalMillionths
                val nextCltvExpiryDelta = paymentInfo.cltvExpiryDelta - paymentRelayData.paymentRelay.cltvExpiryDelta
                val nextPaymentInfo = paymentInfo.copy(
                  feeBase = nextFeeBase,
                  feeProportionalMillionths = nextFeeProportionalMillionths,
                  cltvExpiryDelta = nextCltvExpiryDelta
                )
                register ! Register.GetNextNodeId(context.messageAdapter(WrappedNodeId), paymentRelayData.outgoingChannelId)
                waitForNextNodeId(nextPaymentInfo, paymentRelayData, nextBlinding, blindedNodes.tail, toResolve.tail, resolved)
            }
        }
      case Some(paymentRoute@PaymentBlindedRoute(BlindedRoute(EncodedNodeId.Plain(publicKey), _, _), _)) =>
        resolveCompactBlindedPaths(toResolve.tail, resolved :+ ResolvedPath(paymentRoute, publicKey))
      case Some(paymentRoute@PaymentBlindedRoute(BlindedRoute(EncodedNodeId.ShortChannelIdDir(isNode1, scid), _, _), _)) =>
        router ! Router.GetNodeId(context.messageAdapter(WrappedNodeId), scid, isNode1)
        waitForNodeId(paymentRoute, toResolve.tail, resolved)
      case None =>
        replyTo ! resolved
        Behaviors.stopped
    }
  }

  private def waitForNextNodeId(nextPaymentInfo: OfferTypes.PaymentInfo,
                                paymentRelayData: BlindedRouteData.PaymentRelayData,
                                nextBlinding: PublicKey,
                                nextBlindedNodes: Seq[RouteBlinding.BlindedNode],
                                toResolve: Seq[PaymentBlindedRoute],
                                resolved: Seq[ResolvedPath]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedNodeId(None) =>
        resolveCompactBlindedPaths(toResolve, resolved)
      case WrappedNodeId(Some(nodeId)) =>
        val nextRoute = BlindedRoute(EncodedNodeId.Plain(nodeId), nextBlinding, nextBlindedNodes)
        if (nodeId == nodeParams.nodeId) {
          resolveCompactBlindedPaths(PaymentBlindedRoute(nextRoute, nextPaymentInfo) +: toResolve, resolved)
        } else {
          val relayFees = getRelayFees(nodeParams, nodeId, announceChannel = false) // We use unannounced but we don't know if the channel is announced or not.
          if (paymentRelayData.paymentRelay.feeBase >= relayFees.feeBase
            && paymentRelayData.paymentRelay.feeProportionalMillionths >= relayFees.feeProportionalMillionths
            && paymentRelayData.paymentRelay.cltvExpiryDelta >= nodeParams.channelConf.expiryDelta) {
            resolveCompactBlindedPaths(toResolve, resolved :+ ResolvedPath(PaymentBlindedRoute(nextRoute, nextPaymentInfo), nodeId))
          } else {
            resolveCompactBlindedPaths(toResolve, resolved)
          }
        }
    }

  private def waitForNodeId(paymentRoute: PaymentBlindedRoute,
                            toResolve: Seq[PaymentBlindedRoute],
                            resolved: Seq[ResolvedPath]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedNodeId(None) =>
        resolveCompactBlindedPaths(toResolve, resolved)
      case WrappedNodeId(Some(nodeId)) if nodeId == nodeParams.nodeId =>
        resolveCompactBlindedPaths(paymentRoute.copy(route = paymentRoute.route.copy(introductionNodeId = EncodedNodeId.Plain(nodeId))) +: toResolve, resolved)
      case WrappedNodeId(Some(nodeId)) =>
        resolveCompactBlindedPaths(toResolve, resolved :+ ResolvedPath(paymentRoute, nodeId))
    }
}
