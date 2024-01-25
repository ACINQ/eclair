package fr.acinq.eclair.payment.send

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.payment.send.CompactBlindedPathsResolver._
import fr.acinq.eclair.payment.{PaymentBlindedContactInfo, PaymentBlindedRoute}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.OfferTypes.{BlindedPath, CompactBlindedPath, PaymentInfo}

import scala.annotation.tailrec

object CompactBlindedPathsResolver {
  // @formatter:off
  sealed trait Command
  case class Resolve(replyTo: typed.ActorRef[Seq[PaymentBlindedRoute]], blindedPaths: Seq[PaymentBlindedContactInfo]) extends Command
  private case class WrappedNodeId(nodeId_opt: Option[PublicKey]) extends Command
  // @formatter:on

  def apply(router: ActorRef): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, Resolve(replyTo, blindedPaths)) =>
        val resolver = new CompactBlindedPathsResolver(replyTo, router, context)
        resolver.resolveCompactBlindedPaths(blindedPaths, Nil)
    }
  }
}

private class CompactBlindedPathsResolver(replyTo: typed.ActorRef[Seq[PaymentBlindedRoute]],
                                          router: ActorRef,
                                          context: ActorContext[Command]) {
  @tailrec
  private def resolveCompactBlindedPaths(toResolve: Seq[PaymentBlindedContactInfo],
                                         resolved: Seq[PaymentBlindedRoute]): Behavior[Command] = {
    toResolve.headOption match {
      case Some(PaymentBlindedContactInfo(BlindedPath(route), paymentInfo)) =>
        resolveCompactBlindedPaths(toResolve.tail, resolved :+ PaymentBlindedRoute(route, paymentInfo))
      case Some(PaymentBlindedContactInfo(route: CompactBlindedPath, paymentInfo)) =>
        router ! Router.GetNodeId(context.messageAdapter(WrappedNodeId), route.introductionNode.scid, route.introductionNode.isNode1)
        waitForNodeId(route, paymentInfo, toResolve.tail, resolved)
      case None =>
        replyTo ! resolved
        Behaviors.stopped
    }
  }

  private def waitForNodeId(compactRoute: CompactBlindedPath,
                            paymentInfo: PaymentInfo,
                            toResolve: Seq[PaymentBlindedContactInfo],
                            resolved: Seq[PaymentBlindedRoute]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedNodeId(None) =>
        resolveCompactBlindedPaths(toResolve, resolved)
      case WrappedNodeId(Some(nodeId)) =>
        val resolvedPaymentBlindedRoute = PaymentBlindedRoute(BlindedRoute(nodeId, compactRoute.blindingKey, compactRoute.blindedNodes), paymentInfo)
        resolveCompactBlindedPaths(toResolve, resolved :+ resolvedPaymentBlindedRoute)
    }
}
