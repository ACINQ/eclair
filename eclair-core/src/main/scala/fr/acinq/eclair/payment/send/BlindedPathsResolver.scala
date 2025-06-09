package fr.acinq.eclair.payment.send

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.channel.Helpers.getRelayFees
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedHop
import fr.acinq.eclair.payment.PaymentBlindedRoute
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.OfferTypes.PaymentInfo
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.RouteBlindingDecryptedData
import fr.acinq.eclair.wire.protocol.{BlindedRouteData, OfferTypes, RouteBlindingEncryptedDataCodecs}
import fr.acinq.eclair.{EncodedNodeId, Logs, NodeParams, ShortChannelId}
import scodec.bits.ByteVector

import scala.annotation.tailrec

/**
 * When paying a recipient that is using blinded paths, we pre-process the blinded paths provided to:
 *  - resolve the introduction node_id if not provided
 *  - if we are the introduction node, resolve the next node
 */
object BlindedPathsResolver {

  /**
   * Once resolved, a blinded path contains the ID of the next node we must reach.
   * We can now use the graph to find a route to that node and send a payment.
   */
  case class ResolvedPath(route: ResolvedBlindedRoute, paymentInfo: PaymentInfo)

  // @formatter:off
  sealed trait ResolvedBlindedRoute {
    /** The resolved (non-blinded) node_id of the first node in the route. */
    def firstNodeId: PublicKey
    def blindedHops: Seq[BlindedHop]
    def blindedNodeIds: Seq[PublicKey] = blindedHops.map(_.blindedPublicKey)
    def encryptedPayloads: Seq[ByteVector] = blindedHops.map(_.encryptedPayload)
  }
  /** A blinded route that starts at a remote node that we were able to identify. */
  case class FullBlindedRoute(firstNodeId: PublicKey, firstpathKey: PublicKey, blindedHops: Seq[BlindedHop]) extends ResolvedBlindedRoute {
  }
  /** A partially unwrapped blinded route that started at our node: it only contains the part of the route after our node. */
  case class PartialBlindedRoute(nextNodeId: EncodedNodeId.WithPublicKey, nextPathKey: PublicKey, blindedHops: Seq[BlindedHop]) extends ResolvedBlindedRoute {
    override val firstNodeId: PublicKey = nextNodeId.publicKey
  }
  // @formatter:on

  // @formatter:off
  sealed trait Command
  case class Resolve(replyTo: typed.ActorRef[Seq[ResolvedPath]], blindedPaths: Seq[PaymentBlindedRoute]) extends Command
  private case class WrappedNodeId(nodeId_opt: Option[PublicKey]) extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams, paymentHash: ByteVector32, router: ActorRef, register: ActorRef): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT), paymentHash_opt = Some(paymentHash))) {
        Behaviors.receiveMessagePartial {
          case Resolve(replyTo, blindedPaths) =>
            val resolver = new BlindedPathsResolver(nodeParams, replyTo, router, register, context)
            resolver.resolveBlindedPaths(blindedPaths, Nil)
        }
      }
    }
  }
}

private class BlindedPathsResolver(nodeParams: NodeParams,
                                   replyTo: typed.ActorRef[Seq[BlindedPathsResolver.ResolvedPath]],
                                   router: ActorRef,
                                   register: ActorRef,
                                   context: ActorContext[BlindedPathsResolver.Command]) {

  import BlindedPathsResolver._

  @tailrec
  private def resolveBlindedPaths(toResolve: Seq[PaymentBlindedRoute], resolved: Seq[ResolvedPath]): Behavior[Command] = {
    toResolve.headOption match {
      case Some(paymentRoute) => paymentRoute.route.firstNodeId match {
        case EncodedNodeId.WithPublicKey.Plain(ourNodeId) if ourNodeId == nodeParams.nodeId && paymentRoute.route.length == 0 =>
          context.log.warn("ignoring blinded path (empty route with ourselves as the introduction node)")
          resolveBlindedPaths(toResolve.tail, resolved)
        case EncodedNodeId.WithPublicKey.Plain(ourNodeId) if ourNodeId == nodeParams.nodeId =>
          // We are the introduction node of the blinded route: we need to decrypt the first payload.
          val firstPathKey = paymentRoute.route.firstNode.pathKey
          val firstEncryptedPayload = paymentRoute.route.firstNode.encryptedPayload
          RouteBlindingEncryptedDataCodecs.decode(nodeParams.privateKey, firstPathKey, firstEncryptedPayload) match {
            case Left(f) =>
              context.log.warn("ignoring blinded path starting at our node that we cannot decrypt: {}", f.message)
              resolveBlindedPaths(toResolve.tail, resolved)
            case Right(RouteBlindingDecryptedData(decrypted, nextPathKey)) =>
              BlindedRouteData.validatePaymentRelayData(decrypted) match {
                case Left(f) =>
                  context.log.warn("ignoring blinded path starting at our node with invalid payment relay: {}", f.failureMessage.message)
                  resolveBlindedPaths(toResolve.tail, resolved)
                case Right(paymentRelayData) =>
                  // Note that since fee aggregation iterates from the recipient to the blinded path's introduction node,
                  // the fee_base and fee_proportional computed below are not exactly what should be used for the next node.
                  // But we cannot compute those exact values, and this simple calculation always allocates slightly more
                  // fees to the next nodes than what they expect, so they should relay the payment. We will collect slightly
                  // less relay fees than expected, but it's ok.
                  val nextFeeBase = paymentRoute.paymentInfo.feeBase - paymentRelayData.paymentRelay.feeBase
                  val nextFeeProportionalMillionths = paymentRoute.paymentInfo.feeProportionalMillionths - paymentRelayData.paymentRelay.feeProportionalMillionths
                  val nextCltvExpiryDelta = paymentRoute.paymentInfo.cltvExpiryDelta - paymentRelayData.paymentRelay.cltvExpiryDelta
                  val nextPaymentInfo = paymentRoute.paymentInfo.copy(
                    feeBase = nextFeeBase,
                    feeProportionalMillionths = nextFeeProportionalMillionths,
                    cltvExpiryDelta = nextCltvExpiryDelta
                  )
                  paymentRelayData.outgoing match {
                    case Left(outgoingNodeId) =>
                      validateRelay(outgoingNodeId, nextPaymentInfo, paymentRelayData, nextPathKey, paymentRoute.route.subsequentNodes, toResolve.tail, resolved)
                    case Right(outgoingChannelId) =>
                      register ! Register.GetNextNodeId(context.messageAdapter(WrappedNodeId), outgoingChannelId)
                      waitForNextNodeId(outgoingChannelId, nextPaymentInfo, paymentRelayData, nextPathKey, paymentRoute.route.subsequentNodes, toResolve.tail, resolved)
                  }
              }
          }
        case encodedNodeId: EncodedNodeId.WithPublicKey =>
          val path = ResolvedPath(FullBlindedRoute(encodedNodeId.publicKey, paymentRoute.route.firstPathKey, paymentRoute.route.blindedHops), paymentRoute.paymentInfo)
          resolveBlindedPaths(toResolve.tail, resolved :+ path)
        case EncodedNodeId.ShortChannelIdDir(isNode1, scid) =>
          router ! Router.GetNodeId(context.messageAdapter(WrappedNodeId), scid, isNode1)
          waitForNodeId(paymentRoute, toResolve.tail, resolved)
      }
      case None =>
        replyTo ! resolved
        Behaviors.stopped
    }
  }

  /** Resolve the next node in the blinded path when we are the introduction node. */
  private def waitForNextNodeId(outgoingChannelId: ShortChannelId,
                                nextPaymentInfo: OfferTypes.PaymentInfo,
                                paymentRelayData: BlindedRouteData.PaymentRelayData,
                                nextPathKey: PublicKey,
                                nextBlindedNodes: Seq[RouteBlinding.BlindedHop],
                                toResolve: Seq[PaymentBlindedRoute],
                                resolved: Seq[ResolvedPath]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedNodeId(None) =>
        context.log.warn("ignoring blinded path starting at our node: could not resolve outgoingChannelId={}", outgoingChannelId)
        resolveBlindedPaths(toResolve, resolved)
      case WrappedNodeId(Some(nodeId)) if nodeId == nodeParams.nodeId =>
        // The next node in the route is also our node: this is fishy, there is not reason to include us in the route twice.
        context.log.warn("ignoring blinded path starting at our node relaying to ourselves")
        resolveBlindedPaths(toResolve, resolved)
      case WrappedNodeId(Some(nodeId)) =>
        validateRelay(EncodedNodeId.WithPublicKey.Plain(nodeId), nextPaymentInfo, paymentRelayData, nextPathKey, nextBlindedNodes, toResolve, resolved)
    }

  private def validateRelay(nextNodeId: EncodedNodeId.WithPublicKey,
                            nextPaymentInfo: OfferTypes.PaymentInfo,
                            paymentRelayData: BlindedRouteData.PaymentRelayData,
                            nextPathKey: PublicKey,
                            nextBlindedNodes: Seq[RouteBlinding.BlindedHop],
                            toResolve: Seq[PaymentBlindedRoute],
                            resolved: Seq[ResolvedPath]): Behavior[Command] = {
    // Note that we default to private fees if we don't have a channel yet with that node.
    // The announceChannel parameter is ignored if we already have a channel.
    val (relayFees, inboundFees_opt) = getRelayFees(nodeParams, nextNodeId.publicKey, announceChannel = false)
    val shouldRelay = paymentRelayData.paymentRelay.feeBase >= relayFees.feeBase &&
      paymentRelayData.paymentRelay.feeProportionalMillionths >= relayFees.feeProportionalMillionths &&
      paymentRelayData.paymentRelay.cltvExpiryDelta >= nodeParams.channelConf.expiryDelta
    if (shouldRelay) {
      context.log.debug("unwrapped blinded path starting at our node: next_node={}", nextNodeId.publicKey)
      val path = ResolvedPath(PartialBlindedRoute(nextNodeId, nextPathKey, nextBlindedNodes), nextPaymentInfo)
      resolveBlindedPaths(toResolve, resolved :+ path)
    } else {
      context.log.warn("ignoring blinded path starting at our node: allocated fees are too low (base={}, proportional={}, expiryDelta={})", paymentRelayData.paymentRelay.feeBase, paymentRelayData.paymentRelay.feeProportionalMillionths, paymentRelayData.paymentRelay.cltvExpiryDelta)
      resolveBlindedPaths(toResolve, resolved)
    }
  }

  /** Resolve the introduction node's [[EncodedNodeId.ShortChannelIdDir]] to the corresponding [[EncodedNodeId.WithPublicKey]]. */
  private def waitForNodeId(paymentRoute: PaymentBlindedRoute, toResolve: Seq[PaymentBlindedRoute], resolved: Seq[ResolvedPath]): Behavior[Command] =
    Behaviors.receiveMessagePartial {
      case WrappedNodeId(None) =>
        context.log.warn("ignoring blinded path with unknown scid_dir={}", paymentRoute.route.firstNodeId)
        resolveBlindedPaths(toResolve, resolved)
      case WrappedNodeId(Some(nodeId)) =>
        context.log.debug("successfully resolved scid_dir={} to node_id={}", paymentRoute.route.firstNodeId, nodeId)
        // We've identified the node matching this scid_dir, we retry resolving with that node_id.
        val paymentRouteWithNodeId = paymentRoute.copy(route = paymentRoute.route.copy(firstNodeId = EncodedNodeId.WithPublicKey.Plain(nodeId)))
        resolveBlindedPaths(paymentRouteWithNodeId +: toResolve, resolved)
    }
}
