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

package fr.acinq.eclair.payment.offer

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.{ActorRef, typed}
import akka.util.Collections
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.payment.offer.OfferManager.InvoiceRequestActor
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivingRoute
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.BlindedRouteRequest
import fr.acinq.eclair.wire.protocol.OfferTypes
import fr.acinq.eclair.{CltvExpiryDelta, EncodedNodeId, MilliSatoshi, MilliSatoshiLong, NodeParams}
import scodec.bits.ByteVector
import scodec.codecs.{int32, uint32}

import scala.collection.immutable.{AbstractSeq, LinearSeq}

object DefaultHandler {
  def apply(nodeParams: NodeParams, router: ActorRef): Behavior[OfferManager.HandlerCommand] = {
    Behaviors.setup(context =>
      Behaviors.receiveMessage {
        case OfferManager.HandleInvoiceRequest(replyTo, invoiceRequest) =>
          val amount = invoiceRequest.amount.getOrElse(10_000_000.msat)
          invoiceRequest.offer.contactInfos.head match {
            case OfferTypes.RecipientNodeId(_) =>
              val route = InvoiceRequestActor.Route(Nil, nodeParams.channelConf.maxExpiryDelta)
              replyTo ! InvoiceRequestActor.ApproveRequest(amount, Seq(route), hideFees = true)
            case OfferTypes.BlindedPath(BlindedRoute(firstNodeId: EncodedNodeId.WithPublicKey, _, _)) =>
              val routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams
              router ! BlindedRouteRequest(context.spawnAnonymous(waitForRoute(nodeParams, replyTo, amount)), firstNodeId.publicKey, nodeParams.nodeId, amount, routeParams, pathsToFind = 2)
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

  def waitForRoute(nodeParams: NodeParams, replyTo: typed.ActorRef[InvoiceRequestActor.Command], amount: MilliSatoshi): Behavior[Router.PaymentRouteResponse] = {
    Behaviors.receiveMessage {
      case Router.RouteResponse(routes) =>
        val receivingRoutes = routes.map(route => {
          InvoiceRequestActor.Route(route.hops, nodeParams.channelConf.maxExpiryDelta)
        })
        replyTo ! InvoiceRequestActor.ApproveRequest(amount, receivingRoutes, hideFees = true)
        Behaviors.stopped
      case Router.PaymentRouteNotFound(error) =>
        // TODO: log error
        replyTo ! InvoiceRequestActor.RejectRequest("internal error")
        Behaviors.stopped
    }

  }
}
