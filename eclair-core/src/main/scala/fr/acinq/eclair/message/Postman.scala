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
import fr.acinq.eclair.io.{MessageRelay, Switchboard}
import fr.acinq.eclair.message.OnionMessages.{Destination, RoutingStrategy}
import fr.acinq.eclair.payment.offer.OfferManager
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.{MessageRoute, MessageRouteNotFound, MessageRouteResponse}
import fr.acinq.eclair.wire.protocol.MessageOnion.{FinalPayload, InvoiceRequestPayload}
import fr.acinq.eclair.wire.protocol.{OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{NodeParams, randomBytes32, randomKey}

import scala.collection.mutable

object Postman {
  // @formatter:off
  sealed trait Command
  /**
   * Builds a message packet and send it to the destination using the provided path.
   *
   * @param destination     Recipient of the message
   * @param routingStrategy How to reach the destination (recipient or blinded path introduction node).
   * @param message         Content of the message to send
   * @param expectsReply    Whether the message expects a reply
   * @param replyTo         Actor to send the status and reply to
   */
  case class SendMessage(destination: Destination,
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

  def apply(nodeParams: NodeParams, switchboard: ActorRef[Switchboard.RelayMessage], router: ActorRef[Router.MessageRouteRequest], offerManager: typed.ActorRef[OfferManager.RequestInvoice]): Behavior[Command] = {
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
          val child = context.spawnAnonymous(SendingMessage(nodeParams, switchboard, router, context.self, destination, messageContent, routingStrategy, expectsReply, replyTo))
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
  // @formatter:on

  def apply(nodeParams: NodeParams,
            switchboard: ActorRef[Switchboard.RelayMessage],
            router: ActorRef[Router.MessageRouteRequest],
            postman: ActorRef[Postman.Command],
            destination: Destination,
            message: TlvStream[OnionMessagePayloadTlv],
            routingStrategy: RoutingStrategy,
            expectsReply: Boolean,
            replyTo: ActorRef[Postman.OnionMessageResponse]): Behavior[Command] = {
    Behaviors.setup(context => {
      val actor = new SendingMessage(nodeParams, switchboard, router, postman, destination, message, routingStrategy, expectsReply, replyTo, context)
      actor.start()
    })
  }
}

private class SendingMessage(nodeParams: NodeParams,
                             switchboard: ActorRef[Switchboard.RelayMessage],
                             router: ActorRef[Router.MessageRouteRequest],
                             postman: ActorRef[Postman.Command],
                             destination: Destination,
                             message: TlvStream[OnionMessagePayloadTlv],
                             routingStrategy: RoutingStrategy,
                             expectsReply: Boolean,
                             replyTo: ActorRef[Postman.OnionMessageResponse],
                             context: ActorContext[SendingMessage.Command]) {

  import SendingMessage._

  def start(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case SendMessage =>
        val targetNodeId = destination match {
          case OnionMessages.BlindedPath(route) => route.introductionNodeId
          case OnionMessages.Recipient(nodeId, _, _, _) => nodeId
        }
        routingStrategy match {
          case RoutingStrategy.UseRoute(intermediateNodes) => sendToRoute(intermediateNodes, targetNodeId)
          case RoutingStrategy.FindRoute if targetNodeId == nodeParams.nodeId =>
            context.self ! WrappedMessageRouteResponse(MessageRoute(Nil, targetNodeId))
            waitForRouteFromRouter()
          case RoutingStrategy.FindRoute =>
            router ! Router.MessageRouteRequest(context.messageAdapter(WrappedMessageRouteResponse), nodeParams.nodeId, targetNodeId, Set.empty)
            waitForRouteFromRouter()
        }
    }
  }

  private def waitForRouteFromRouter(): Behavior[Command] = {
    Behaviors.receiveMessagePartial {
      case WrappedMessageRouteResponse(MessageRoute(intermediateNodes, targetNodeId)) =>
        context.log.debug("Found route: {}", (intermediateNodes :+ targetNodeId).mkString(" -> "))
        sendToRoute(intermediateNodes, targetNodeId)
      case WrappedMessageRouteResponse(MessageRouteNotFound(targetNodeId)) =>
        context.log.debug("No route found to {}", targetNodeId)
        replyTo ! Postman.MessageFailed("No route found")
        Behaviors.stopped
    }
  }

  private def sendToRoute(intermediateNodes: Seq[PublicKey], targetNodeId: PublicKey): Behavior[Command] = {
    val messageId = randomBytes32()
    val replyRoute =
      if (expectsReply) {
        val numHopsToAdd = 0.max(nodeParams.onionMessageConfig.minIntermediateHops - intermediateNodes.length - 1)
        val intermediateHops = (Seq(targetNodeId) ++ intermediateNodes.reverse ++ Seq.fill(numHopsToAdd)(nodeParams.nodeId)).map(OnionMessages.IntermediateNode(_))
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
        switchboard ! Switchboard.RelayMessage(messageId, None, nextNodeId, message, MessageRelay.RelayAll, Some(context.messageAdapter[MessageRelay.Status](SendingStatus)))
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
