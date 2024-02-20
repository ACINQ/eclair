package fr.acinq.eclair.payment.send

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.EncodedNodeId
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.payment.PaymentBlindedRoute
import fr.acinq.eclair.payment.send.CompactBlindedPathsResolver._
import fr.acinq.eclair.router.Router

import scala.annotation.tailrec

object CompactBlindedPathsResolver {
  // @formatter:off
  sealed trait Command
  case class Resolve(replyTo: typed.ActorRef[Seq[ResolvedPath]], blindedPaths: Seq[PaymentBlindedRoute]) extends Command
  private case class WrappedNodeId(nodeId_opt: Option[PublicKey]) extends Command

  case class ResolvedPath(blindedPath: PaymentBlindedRoute, introductionNodeId: PublicKey)
  // @formatter:on

  def apply(router: ActorRef): Behavior[Command] = {
    Behaviors.receivePartial {
      case (context, Resolve(replyTo, blindedPaths)) =>
        val resolver = new CompactBlindedPathsResolver(replyTo, router, context)
        resolver.resolveCompactBlindedPaths(blindedPaths, Nil)
    }
  }
}

private class CompactBlindedPathsResolver(replyTo: typed.ActorRef[Seq[ResolvedPath]],
                                          router: ActorRef,
                                          context: ActorContext[Command]) {
  @tailrec
  private def resolveCompactBlindedPaths(toResolve: Seq[PaymentBlindedRoute],
                                         resolved: Seq[ResolvedPath]): Behavior[Command] = {
    toResolve.headOption match {
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

  private def waitForNodeId(paymentRoute: PaymentBlindedRoute,
                            toResolve: Seq[PaymentBlindedRoute],
                            resolved: Seq[ResolvedPath]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedNodeId(None) =>
        resolveCompactBlindedPaths(toResolve, resolved)
      case WrappedNodeId(Some(nodeId)) =>
        resolveCompactBlindedPaths(toResolve, resolved :+ ResolvedPath(paymentRoute, nodeId))
    }
}
