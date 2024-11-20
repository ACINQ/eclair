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

import akka.actor.{ActorRef, typed}
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32}
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.TlvStream
import fr.acinq.eclair.{MilliSatoshi, NodeParams, TimestampSecond, randomBytes32, randomKey}

object OfferCreator {
  sealed trait Command

  case class Create(replyTo: typed.ActorRef[Either[String, Offer]],
                    description_opt: Option[String],
                    amount_opt: Option[MilliSatoshi],
                    expiry_opt: Option[TimestampSecond],
                    issuer_opt: Option[String],
                    firstNodeId_opt: Option[PublicKey]) extends Command

  case class RouteResponseWrapper(response: Router.MessageRouteResponse) extends Command

  def apply(nodeParams: NodeParams, router: ActorRef, offerManager: typed.ActorRef[OfferManager.Command], defaultOfferHandler: typed.ActorRef[OfferManager.HandlerCommand]): Behavior[Command] =
    Behaviors.receivePartial {
      case (context, Create(replyTo, description_opt, amount_opt, expiry_opt, issuer_opt, firstNodeId_opt)) =>
        new OfferCreator(context, replyTo, nodeParams, router, offerManager, defaultOfferHandler).init(description_opt, amount_opt, expiry_opt, issuer_opt, firstNodeId_opt)
    }
}

private class OfferCreator(context: ActorContext[OfferCreator.Command],
                           replyTo: typed.ActorRef[Either[String, Offer]],
                           nodeParams: NodeParams, router: ActorRef,
                           offerManager: typed.ActorRef[OfferManager.Command],
                           defaultOfferHandler: typed.ActorRef[OfferManager.HandlerCommand]) {

  import OfferCreator._

  private def init(description_opt: Option[String],
                   amount_opt: Option[MilliSatoshi],
                   expiry_opt: Option[TimestampSecond],
                   issuer_opt: Option[String],
                   firstNodeId_opt: Option[PublicKey]): Behavior[Command] = {
    if (amount_opt.nonEmpty && description_opt.isEmpty) {
      replyTo ! Left("Description is mandatory for offers with set amount.")
      Behaviors.stopped
    } else {
      val tlvs: Set[OfferTlv] = Set(
        if (nodeParams.chainHash != Block.LivenetGenesisBlock.hash) Some(OfferChains(Seq(nodeParams.chainHash))) else None,
        amount_opt.map(OfferAmount),
        description_opt.map(OfferDescription),
        expiry_opt.map(OfferAbsoluteExpiry),
        issuer_opt.map(OfferIssuer),
      ).flatten
      firstNodeId_opt match {
        case Some(firstNodeId) =>
          router ! Router.MessageRouteRequest(context.messageAdapter(RouteResponseWrapper(_)), firstNodeId, nodeParams.nodeId, Set.empty)
          waitForRoute(firstNodeId, tlvs)
        case None =>
          val offer = Offer(TlvStream(tlvs + OfferNodeId(nodeParams.nodeId)))
          registerOffer(offer, Some(nodeParams.privateKey), None)
      }
    }
  }

  private def waitForRoute(firstNode: PublicKey, tlvs: Set[OfferTlv]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case RouteResponseWrapper(Router.MessageRoute(intermediateNodes, _)) =>
        val pathId = randomBytes32()
        val nodes = firstNode +: (intermediateNodes ++ Seq.fill(nodeParams.offersConfig.messagePathMinLength - intermediateNodes.length - 1)(nodeParams.nodeId))
        val paths = Seq(OnionMessages.buildRoute(randomKey(), nodes.map(IntermediateNode(_)), Recipient(nodeParams.nodeId, Some(pathId))).route)
        val offer = Offer(TlvStream(tlvs + OfferPaths(paths)))
        registerOffer(offer, None, Some(pathId))
      case RouteResponseWrapper(Router.MessageRouteNotFound(_)) =>
        replyTo ! Left("No route found")
        Behaviors.stopped
    }
  }

  private def registerOffer(offer: Offer, nodeKey: Option[PrivateKey], pathId_opt: Option[ByteVector32]): Behavior[Command] = {
    nodeParams.db.managedOffers.addOffer(offer, pathId_opt)
    offerManager ! OfferManager.RegisterOffer(offer, nodeKey, pathId_opt, defaultOfferHandler)
    replyTo ! Right(offer)
    Behaviors.stopped
  }
}
