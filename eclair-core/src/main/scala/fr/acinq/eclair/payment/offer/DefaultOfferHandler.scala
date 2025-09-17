/*
 * Copyright 2025 ACINQ SAS
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

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.{ActorRef, typed}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.Logs.LogCategory
import fr.acinq.eclair.payment.offer.OfferManager.InvoiceRequestActor
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.{BlindedRouteRequest, ChannelHop}
import fr.acinq.eclair.wire.protocol.OfferTypes
import fr.acinq.eclair.wire.protocol.OfferTypes.InvoiceRequest
import fr.acinq.eclair.{CltvExpiryDelta, EncodedNodeId, Logs, MilliSatoshiLong, NodeParams}

case class OffersConfig(messagePathMinLength: Int, paymentPathCount: Int, paymentPathLength: Int, paymentPathCltvExpiryDelta: CltvExpiryDelta)

/**
 * This actor creates Bolt 12 invoices for offers that are managed by eclair.
 * It creates payment blinded paths whenever the corresponding offer is using a (message) blinded path.
 */
object DefaultOfferHandler {
  def apply(nodeParams: NodeParams, router: ActorRef): Behavior[OfferManager.HandlerCommand] = {
    Behaviors.setup(context =>
      Behaviors.receiveMessage {
        case OfferManager.HandleInvoiceRequest(replyTo, invoiceRequest) =>
          invoiceRequest.offer.contactInfos.head match {
            case OfferTypes.RecipientNodeId(_) =>
              val route = InvoiceRequestActor.Route(Nil, nodeParams.channelConf.maxExpiryDelta)
              replyTo ! InvoiceRequestActor.ApproveRequest(invoiceRequest.amount, Seq(route))
            case OfferTypes.BlindedPath(path) =>
              path.firstNodeId match {
                case firstNodeId: EncodedNodeId.WithPublicKey if firstNodeId.publicKey == nodeParams.nodeId =>
                  // We're using a fake blinded path starting at ourselves: we only need to add dummy hops.
                  val paths = PaymentPathsBuilder.finalizeRoutes(nodeParams, Seq(Nil))
                  replyTo ! InvoiceRequestActor.ApproveRequest(invoiceRequest.amount, paths)
                case firstNodeId: EncodedNodeId.WithPublicKey =>
                  val pathBuilder = context.spawnAnonymous(PaymentPathsBuilder(nodeParams, router, invoiceRequest))
                  pathBuilder ! PaymentPathsBuilder.GetPaymentPaths(replyTo, firstNodeId.publicKey)
                case _: EncodedNodeId.ShortChannelIdDir =>
                  context.log.error("unexpected managed offer with compact first node id")
                  replyTo ! InvoiceRequestActor.RejectRequest("internal error")
              }
          }
          Behaviors.same
        case OfferManager.HandlePayment(replyTo, _, _) =>
          replyTo ! OfferManager.PaymentActor.AcceptPayment()
          Behaviors.same
      }
    )
  }

  /**
   * Short-lived actor that creates payment blinded paths with help from the router.
   */
  private object PaymentPathsBuilder {

    // @formatter:off
    sealed trait Command
    case class GetPaymentPaths(replyTo: typed.ActorRef[InvoiceRequestActor.Command], blindedPathFirstNodeId: PublicKey) extends Command
    private case class WrappedRouteResponse(response: Router.PaymentRouteResponse) extends Command
    // @formatter:on

    def apply(nodeParams: NodeParams, router: ActorRef, invoiceRequest: InvoiceRequest): Behavior[Command] = {
      Behaviors.setup { context =>
        Behaviors.withMdc(Logs.mdc(category_opt = Some(LogCategory.PAYMENT), offerId_opt = Some(invoiceRequest.offer.offerId))) {
          Behaviors.receiveMessagePartial {
            case GetPaymentPaths(replyTo, blindedPathFirstNodeId) =>
              val defaultRouteParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams
              val routeParams = defaultRouteParams.copy(boundaries = Router.SearchBoundaries(
                maxFeeFlat = defaultRouteParams.boundaries.maxFeeFlat,
                maxFeeProportional = defaultRouteParams.boundaries.maxFeeProportional,
                maxRouteLength = nodeParams.offersConfig.paymentPathLength,
                maxCltv = nodeParams.offersConfig.paymentPathCltvExpiryDelta
              ))
              router ! BlindedRouteRequest(context.messageAdapter(WrappedRouteResponse), blindedPathFirstNodeId, nodeParams.nodeId, invoiceRequest.amount, routeParams, nodeParams.offersConfig.paymentPathCount, blip18InboundFees = nodeParams.routerConf.blip18InboundFees, excludePositiveInboundFees = nodeParams.routerConf.excludePositiveInboundFees)
              waitForRoute(nodeParams, replyTo, invoiceRequest, blindedPathFirstNodeId, context)
          }
        }
      }
    }

    private def waitForRoute(nodeParams: NodeParams, replyTo: typed.ActorRef[InvoiceRequestActor.Command], invoiceRequest: InvoiceRequest, blindedPathFirstNodeId: PublicKey, context: ActorContext[Command]): Behavior[Command] = {
      Behaviors.receiveMessagePartial {
        case WrappedRouteResponse(Router.RouteResponse(routes)) =>
          context.log.debug("found {} blinded paths starting at {} (amount={})", routes.size, blindedPathFirstNodeId, invoiceRequest.amount)
          replyTo ! InvoiceRequestActor.ApproveRequest(invoiceRequest.amount, finalizeRoutes(nodeParams, routes.map(_.hops)))
          Behaviors.stopped
        case WrappedRouteResponse(Router.PaymentRouteNotFound(error)) =>
          context.log.warn("couldn't find blinded paths to create invoice amount={} firstNodeId={}: {}", invoiceRequest.amount, blindedPathFirstNodeId, error.getMessage)
          replyTo ! InvoiceRequestActor.RejectRequest("internal error")
          Behaviors.stopped
      }
    }

    def finalizeRoutes(nodeParams: NodeParams, routes: Seq[Seq[Router.ChannelHop]]): Seq[InvoiceRequestActor.Route] = {
      (0 until nodeParams.offersConfig.paymentPathCount).map(i => {
        // We always return the number of routes configured, regardless of how many routes were actually found by the
        // router: this ensures that we don't leak information about our graph data.
        // However, payers may eagerly use MPP whereas we actually have a single route available, which could result in
        // a lower payment success rate.
        val hops = routes(i % routes.length)
        // We always pad blinded paths to the configured length, using dummy hops if necessary.
        val dummyHops = Seq.fill(nodeParams.offersConfig.paymentPathLength - hops.length)(ChannelHop.dummy(nodeParams.nodeId, 0 msat, 0, CltvExpiryDelta(0)))
        // We always override the fees of the payment path: the payer shouldn't be paying for our privacy.
        // Note that we told the router to only find paths with a lower cltv_expiry_delta than what we'll be using,
        // which ensures that we won't reject payments because of their expiry.
        InvoiceRequestActor.Route(hops ++ dummyHops, nodeParams.channelConf.maxExpiryDelta, feeOverride_opt = Some(RelayFees.zero), cltvOverride_opt = Some(nodeParams.offersConfig.paymentPathCltvExpiryDelta))
      })
    }
  }

}
