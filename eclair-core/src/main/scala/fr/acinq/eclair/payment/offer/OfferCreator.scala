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
import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32}
import fr.acinq.eclair.db.OfferData
import fr.acinq.eclair.message.OnionMessages
import fr.acinq.eclair.message.OnionMessages.{IntermediateNode, Recipient}
import fr.acinq.eclair.payment.offer.OfferCreator.CreateOfferResult
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.OfferTypes._
import fr.acinq.eclair.wire.protocol.TlvStream
import fr.acinq.eclair.{MilliSatoshi, NodeParams, TimestampSecond, randomBytes32, randomKey}

/**
 * A short-lived actor that creates an offer based on the parameters provided.
 * It will ask the router for a blinded path when [[OfferCreator.Create.blindedPathsFirstNodeId_opt]] is provided.
 */
object OfferCreator {

  // @formatter:off
  sealed trait Command
  case class Create(replyTo: typed.ActorRef[CreateOfferResult],
                    description_opt: Option[String],
                    amount_opt: Option[MilliSatoshi],
                    expiry_opt: Option[TimestampSecond],
                    issuer_opt: Option[String],
                    blindedPathsFirstNodeId_opt: Option[PublicKey]) extends Command
  private case class WrappedRouterResponse(response: Router.MessageRouteResponse) extends Command
  // @formatter:on

  // @formatter:off
  sealed trait CreateOfferResult
  case class CreatedOffer(offerData: OfferData) extends CreateOfferResult
  case class CreateOfferError(reason: String) extends CreateOfferResult
  // @formatter:on

  def apply(nodeParams: NodeParams, router: ActorRef, offerManager: typed.ActorRef[OfferManager.Command], defaultOfferHandler: typed.ActorRef[OfferManager.HandlerCommand]): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessagePartial {
        case Create(replyTo, description_opt, amount_opt, expiry_opt, issuer_opt, blindedPathsFirstNodeId_opt) =>
          val actor = new OfferCreator(context, replyTo, nodeParams, router, offerManager, defaultOfferHandler)
          actor.createOffer(description_opt, amount_opt, expiry_opt, issuer_opt, blindedPathsFirstNodeId_opt)
      }
    }
  }
}

private class OfferCreator(context: ActorContext[OfferCreator.Command],
                           replyTo: typed.ActorRef[CreateOfferResult],
                           nodeParams: NodeParams, router: ActorRef,
                           offerManager: typed.ActorRef[OfferManager.Command],
                           defaultOfferHandler: typed.ActorRef[OfferManager.HandlerCommand]) {

  import OfferCreator._

  private def createOffer(description_opt: Option[String],
                          amount_opt: Option[MilliSatoshi],
                          expiry_opt: Option[TimestampSecond],
                          issuer_opt: Option[String],
                          blindedPathsFirstNodeId_opt: Option[PublicKey]): Behavior[Command] = {
    if (amount_opt.nonEmpty && description_opt.isEmpty) {
      replyTo ! CreateOfferError("Description is mandatory for offers with set amount.")
      Behaviors.stopped
    } else {
      val tlvs: Set[OfferTlv] = Set(
        if (nodeParams.chainHash != Block.LivenetGenesisBlock.hash) Some(OfferChains(Seq(nodeParams.chainHash))) else None,
        amount_opt.map(_.toLong).map(OfferAmount),
        description_opt.map(OfferDescription),
        expiry_opt.map(OfferAbsoluteExpiry),
        issuer_opt.map(OfferIssuer),
      ).flatten
      blindedPathsFirstNodeId_opt match {
        case Some(firstNodeId) =>
          router ! Router.MessageRouteRequest(context.messageAdapter(WrappedRouterResponse(_)), firstNodeId, nodeParams.nodeId, Set.empty)
          waitForRoute(firstNodeId, tlvs)
        case None =>
          // When not using a blinded path, we use our public nodeId for the offer (no privacy).
          val offer = Offer(TlvStream(tlvs + OfferNodeId(nodeParams.nodeId)))
          registerOffer(offer, Some(nodeParams.privateKey), None)
      }
    }
  }

  private def waitForRoute(firstNode: PublicKey, tlvs: Set[OfferTlv]): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedRouterResponse(Router.MessageRoute(intermediateNodes, _)) =>
        val pathId = randomBytes32()
        // We add dummy hops to the route if it is too short, which provides better privacy.
        val nodes = firstNode +: (intermediateNodes ++ Seq.fill(nodeParams.offersConfig.messagePathMinLength - intermediateNodes.length - 1)(nodeParams.nodeId))
        val paths = Seq(OnionMessages.buildRoute(randomKey(), nodes.map(IntermediateNode(_)), Recipient(nodeParams.nodeId, Some(pathId))).route)
        val offer = Offer(TlvStream(tlvs + OfferPaths(paths)))
        registerOffer(offer, None, Some(pathId))
      case WrappedRouterResponse(Router.MessageRouteNotFound(_)) =>
        replyTo ! CreateOfferError("No route found")
        Behaviors.stopped
    }
  }

  private def registerOffer(offer: Offer, nodeKey_opt: Option[PrivateKey], pathId_opt: Option[ByteVector32]): Behavior[Command] = {
    nodeParams.db.offers.addOffer(offer, pathId_opt) match {
      case Some(offerData) =>
        offerManager ! OfferManager.RegisterOffer(offer, nodeKey_opt, pathId_opt, defaultOfferHandler)
        replyTo ! CreatedOffer(offerData)
      case None =>
        replyTo ! CreateOfferError("This offer is already registered")
    }
    Behaviors.stopped
  }
}
