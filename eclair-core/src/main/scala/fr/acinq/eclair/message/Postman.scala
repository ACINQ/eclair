/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.message

import akka.actor.typed
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding.BlindedRoute
import fr.acinq.eclair.io.MessageRelay
import fr.acinq.eclair.message.OnionMessages.{Destination, RoutingStrategy}
import fr.acinq.eclair.payment.offer.OfferManager
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.{MessageRoute, MessageRouteNotFound, MessageRouteResponse}
import fr.acinq.eclair.wire.protocol.MessageOnion.{FinalPayload, InvoiceRequestPayload}
import fr.acinq.eclair.wire.protocol.OfferTypes.{CompactBlindedPath, ContactInfo}
import fr.acinq.eclair.wire.protocol.{OfferTypes, OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{EncodedNodeId, NodeParams, randomBytes32, randomKey}

import scala.collection.mutable

object Postman {
  // @formatter:off
  sealed trait Command
  /**
   * Builds a message packet and send it to the destination using the provided path.
   *
   * @param contactInfo     Recipient of the message
   * @param routingStrategy How to reach the destination (recipient or blinded path introduction node).
   * @param message         Content of the message to send
   * @param expectsReply    Whether the message expects a reply
   * @param replyTo         Actor to send the status and reply to
   */
  case class SendMessage(contactInfo: ContactInfo,
                         routingStrategy: RoutingStrategy,
                         message: TlvStream[OnionMessagePayloadTlv],
                         expectsReply: Boolean,
                         replyTo: ActorRef[OnionMessageResponse]) extends Command
  case class Subscribe(pathId: ByteVector32, replyTo: ActorRef[OnionMessageResponse]) extends Command
  private case class Unsubscribe(pathId: ByteVector32) extends Command
  case class WrappedMessage(finalPayload: FinalPayload) extends Command

  sealed trait OnionMessageResponse
  case object NoReply extends OnionMessageResponse
  case class Response(payload: FinalPayload) extends OnionMessageResponse
  sealed trait MessageStatus extends OnionMessageResponse
  case object MessageSent extends MessageStatus
  case class MessageFailed(reason: String) extends MessageStatus
  // @formatter:on

  def apply(nodeParams: NodeParams, switchboard: akka.actor.ActorRef, router: ActorRef[Router.PostmanRequest], register: akka.actor.ActorRef, offerManager: typed.ActorRef[OfferManager.RequestInvoice]): Behavior[Command] = {
    Behaviors.setup(context => {
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[OnionMessages.ReceiveMessage](r => WrappedMessage(r.finalPayload)))

      // For messages expecting a reply, send reply or failure to send
      val subscribed = new mutable.HashMap[ByteVector32, ActorRef[OnionMessageResponse]]()

      Behaviors.receiveMessage {
        case WrappedMessage(invoiceRequestPayload: InvoiceRequestPayload) =>
          offerManager ! OfferManager.RequestInvoice(invoiceRequestPayload, context.self)
          Behaviors.same
        case WrappedMessage(finalPayload) =>
          finalPayload.pathId_opt match {
            case Some(pathId) if pathId.length == 32 =>
              val id = ByteVector32(pathId)
              subscribed.get(id).foreach(ref => {
                subscribed -= id
                ref ! Response(finalPayload)
              })
            case _ => // ignoring message with invalid or missing pathId
          }
          Behaviors.same
        case SendMessage(destination, routingStrategy, messageContent, expectsReply, replyTo) =>
          val child = context.spawnAnonymous(SendingMessage(nodeParams, router, context.self, switchboard, register, destination, messageContent, routingStrategy, expectsReply, replyTo))
          child ! SendingMessage.SendMessage
          Behaviors.same
        case Subscribe(pathId, replyTo) =>
          subscribed += (pathId -> replyTo)
          context.scheduleOnce(nodeParams.onionMessageConfig.timeout, context.self, Unsubscribe(pathId))
          Behaviors.same
        case Unsubscribe(pathId) =>
          subscribed.get(pathId).foreach(ref => {
            subscribed -= pathId
            ref ! NoReply
          })
          Behaviors.same
      }
    })
  }
}

object SendingMessage {
  // @formatter:off
  sealed trait Command
  case object SendMessage extends Command
  private case class SendingStatus(status: MessageRelay.Status) extends Command
  private case class WrappedMessageRouteResponse(response: MessageRouteResponse) extends Command
  private case class WrappedNodeIdResponse(nodeId_opt: Option[PublicKey]) extends Command
  // @formatter:on

  def apply(nodeParams: NodeParams,
            router: ActorRef[Router.PostmanRequest],
            postman: ActorRef[Postman.Command],
            switchboard: akka.actor.ActorRef,
            register: akka.actor.ActorRef,
            contactInfo: ContactInfo,
            message: TlvStream[OnionMessagePayloadTlv],
            routingStrategy: RoutingStrategy,
            expectsReply: Boolean,
            replyTo: ActorRef[Postman.OnionMessageResponse]): Behavior[Command] = {
    Behaviors.setup(context => {
      val actor = new SendingMessage(nodeParams, router, postman, switchboard, register, contactInfo, message, routingStrategy, expectsReply, replyTo, context)
      actor.start()
    })
  }
}

private class SendingMessage(nodeParams: NodeParams,
                             router: ActorRef[Router.PostmanRequest],
                             postman: ActorRef[Postman.Command],
                             switchboard: akka.actor.ActorRef,
                             register: akka.actor.ActorRef,
                             contactInfo: ContactInfo,
                             message: TlvStream[OnionMessagePayloadTlv],
                             routingStrategy: RoutingStrategy,
                             expectsReply: Boolean,
                             replyTo: ActorRef[Postman.OnionMessageResponse],
                             context: ActorContext[SendingMessage.Command]) {

  import SendingMessage._

  def start(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case SendMessage =>
        contactInfo match {
          case compact: OfferTypes.CompactBlindedPath =>
            router ! Router.GetNodeId(context.messageAdapter(WrappedNodeIdResponse), compact.introductionNode.scid, compact.introductionNode.isNode1)
            waitForNodeId(compact)
          case OfferTypes.BlindedPath(route) => sendToDestination(OnionMessages.BlindedPath(route))
          case OfferTypes.RecipientNodeId(nodeId) => sendToDestination(OnionMessages.Recipient(nodeId, None))
        }
    }
  }

  private def waitForNodeId(compactBlindedPath: CompactBlindedPath): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedNodeIdResponse(None) =>
        replyTo ! Postman.MessageFailed(s"Could not resolve introduction node for compact blinded path (scid=${compactBlindedPath.introductionNode.scid.toCoordinatesString})")
        Behaviors.stopped
      case WrappedNodeIdResponse(Some(nodeId)) =>
        sendToDestination(OnionMessages.BlindedPath(BlindedRoute(nodeId, compactBlindedPath.blindingKey, compactBlindedPath.blindedNodes)))
    }
  }

  private def sendToDestination(destination: Destination): Behavior[Command] = {
    routingStrategy match {
      case RoutingStrategy.UseRoute(intermediateNodes) => sendToRoute(intermediateNodes, destination)
      case RoutingStrategy.FindRoute if destination.nodeId == nodeParams.nodeId =>
        context.self ! WrappedMessageRouteResponse(MessageRoute(Nil, destination.nodeId))
        waitForRouteFromRouter(destination)
      case RoutingStrategy.FindRoute =>
        router ! Router.MessageRouteRequest(context.messageAdapter(WrappedMessageRouteResponse), nodeParams.nodeId, destination.nodeId, Set.empty)
        waitForRouteFromRouter(destination)
    }
  }

  private def waitForRouteFromRouter(destination: Destination): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedMessageRouteResponse(MessageRoute(intermediateNodes, targetNodeId)) =>
        context.log.debug("Found route: {}", (intermediateNodes :+ targetNodeId).mkString(" -> "))
        sendToRoute(intermediateNodes, destination)
      case WrappedMessageRouteResponse(MessageRouteNotFound(targetNodeId)) =>
        context.log.debug("No route found to {}", targetNodeId)
        replyTo ! Postman.MessageFailed("No route found")
        Behaviors.stopped
    }
  }

  private def sendToRoute(intermediateNodes: Seq[PublicKey], destination: Destination): Behavior[Command] = {
    val messageId = randomBytes32()
    val replyRoute =
      if (expectsReply) {
        val numHopsToAdd = 0.max(nodeParams.onionMessageConfig.minIntermediateHops - intermediateNodes.length - 1)
        val intermediateHops = (Seq(destination.nodeId) ++ intermediateNodes.reverse ++ Seq.fill(numHopsToAdd)(nodeParams.nodeId)).map(OnionMessages.IntermediateNode(_))
        val lastHop = OnionMessages.Recipient(nodeParams.nodeId, Some(messageId))
        Some(OnionMessages.buildRoute(randomKey(), intermediateHops, lastHop))
      } else {
        None
      }
    OnionMessages.buildMessage(
      nodeParams.privateKey,
      randomKey(),
      randomKey(),
      intermediateNodes.map(OnionMessages.IntermediateNode(_)),
      destination,
      TlvStream(message.records ++ replyRoute.map(OnionMessagePayloadTlv.ReplyPath).toSet, message.unknown)) match {
      case Left(failure) =>
        replyTo ! Postman.MessageFailed(failure.toString)
        Behaviors.stopped
      case Right((nextNodeId, message)) =>
        val relay = context.spawn(Behaviors.supervise(MessageRelay(nodeParams, switchboard, register, router)).onFailure(typed.SupervisorStrategy.stop), s"relay-message-$messageId")
        relay ! MessageRelay.RelayMessage(messageId, nodeParams.nodeId, Right(EncodedNodeId(nextNodeId)), message, MessageRelay.RelayAll, Some(context.messageAdapter[MessageRelay.Status](SendingStatus)))
        waitForSent()
    }
  }

  private def waitForSent(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case SendingStatus(MessageRelay.Sent(messageId)) =>
        if (expectsReply) {
          postman ! Postman.Subscribe(messageId, replyTo)
        } else {
          replyTo ! Postman.MessageSent
        }
        Behaviors.stopped
      case SendingStatus(status: MessageRelay.Failure) =>
        replyTo ! Postman.MessageFailed(status.toString)
        Behaviors.stopped
    }
  }

}
