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
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.{ActorRef, typed}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.payment.offer.OfferManager.InvoiceRequestActor
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.{BlindedRouteRequest, ChannelHop}
import fr.acinq.eclair.wire.protocol.OfferTypes
import fr.acinq.eclair.{CltvExpiryDelta, EncodedNodeId, MilliSatoshi, MilliSatoshiLong, NodeParams}

object DefaultHandler {
  def apply(nodeParams: NodeParams, router: ActorRef): Behavior[OfferManager.HandlerCommand] = {
    Behaviors.setup(context =>
      Behaviors.receiveMessage {
        case OfferManager.HandleInvoiceRequest(replyTo, invoiceRequest) =>
          val amount = invoiceRequest.amount.getOrElse(10_000_000 msat)
          invoiceRequest.offer.contactInfos.head match {
            case OfferTypes.RecipientNodeId(_) =>
              val route = InvoiceRequestActor.Route(Nil, nodeParams.channelConf.maxExpiryDelta)
              replyTo ! InvoiceRequestActor.ApproveRequest(amount, Seq(route))
            case OfferTypes.BlindedPath(BlindedRoute(firstNodeId: EncodedNodeId.WithPublicKey, _, _)) if firstNodeId.publicKey == nodeParams.nodeId =>
              replyTo ! InvoiceRequestActor.ApproveRequest(amount, makeRoutes(nodeParams, Seq(Nil)))
            case OfferTypes.BlindedPath(BlindedRoute(firstNodeId: EncodedNodeId.WithPublicKey, _, _)) =>
              val baseParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams
              val routeParams = baseParams.copy(boundaries = baseParams.boundaries.copy(maxRouteLength = nodeParams.offersConfig.paymentPathLength, maxCltv = nodeParams.offersConfig.paymentPathCltvExpiryDelta))
              router ! BlindedRouteRequest(context.spawnAnonymous(waitForRoute(nodeParams, replyTo, invoiceRequest.offer, amount)), firstNodeId.publicKey, nodeParams.nodeId, amount, routeParams, pathsToFind = 2)
            case OfferTypes.BlindedPath(BlindedRoute(_: EncodedNodeId.ShortChannelIdDir, _, _)) =>
              context.log.error("unexpected managed offer with compact first node id")
              replyTo ! InvoiceRequestActor.RejectRequest("internal error")
          }
          Behaviors.same
        case OfferManager.HandlePayment(replyTo, _, _) =>
          replyTo ! OfferManager.PaymentActor.AcceptPayment()
          Behaviors.same
      }
    )
  }

  def waitForRoute(nodeParams: NodeParams, replyTo: typed.ActorRef[InvoiceRequestActor.Command], offer: OfferTypes.Offer, amount: MilliSatoshi): Behavior[Router.PaymentRouteResponse] = {
    Behaviors.receive {
      case (_, Router.RouteResponse(routes)) =>
        replyTo ! InvoiceRequestActor.ApproveRequest(amount, makeRoutes(nodeParams, routes.map(_.hops)))
        Behaviors.stopped
      case (context, Router.PaymentRouteNotFound(error)) =>
        context.log.error("Couldn't find blinded route for creating invoice offer={} amount={} : {}", offer, amount, error.getMessage)
        replyTo ! InvoiceRequestActor.RejectRequest("internal error")
        Behaviors.stopped
    }
  }

  def makeRoutes(nodeParams: NodeParams, routes: Seq[Seq[Router.ChannelHop]]): Seq[InvoiceRequestActor.Route] = {
    (0 until nodeParams.offersConfig.paymentPathCount).map(i => {
      val hops = routes(i % routes.length)
      val dummyHops = Seq.fill(nodeParams.offersConfig.paymentPathLength - hops.length)(ChannelHop.dummy(nodeParams.nodeId, 0 msat, 0, CltvExpiryDelta(0)))
      InvoiceRequestActor.Route(hops ++ dummyHops, nodeParams.channelConf.maxExpiryDelta, feeOverride = Some(RelayFees.zero), cltvOverride = Some(nodeParams.offersConfig.paymentPathCltvExpiryDelta))
    })
  }
}

case class OffersConfig(messagePathMinLength: Int, paymentPathCount: Int, paymentPathLength: Int, paymentPathCltvExpiryDelta: CltvExpiryDelta)
