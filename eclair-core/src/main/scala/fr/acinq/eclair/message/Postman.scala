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

import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.io.{MessageRelay, Switchboard}
import fr.acinq.eclair.message.OnionMessages.Destination
import fr.acinq.eclair.wire.protocol.MessageOnion.FinalPayload
import fr.acinq.eclair.wire.protocol.{OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{NodeParams, randomBytes32, randomKey}

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

object Postman {
  // @formatter:off
  sealed trait Command

  /** Builds a message packet and send it to the destination using the provided path.
   *
   * @param intermediateNodes Extra hops to use between us and the destination
   * @param destination       Recipient of the message
   * @param replyPath         Hops to use for the reply (including our own node as the last hop) or None if not expecting a reply
   * @param message           Content of the message to send
   * @param replyTo           Actor to send the status and reply to
   * @param timeout           When expecting a reply, maximum delay to wait for it
   */
  case class SendMessage(intermediateNodes: Seq[PublicKey],
                         destination: Destination,
                         replyPath: Option[Seq[PublicKey]],
                         message: TlvStream[OnionMessagePayloadTlv],
                         replyTo: ActorRef[OnionMessageResponse],
                         timeout: FiniteDuration) extends Command
  private case class Unsubscribe(pathId: ByteVector32) extends Command
  private case class WrappedMessage(finalPayload: FinalPayload) extends Command
  case class SendingStatus(status: MessageRelay.Status) extends Command

  sealed trait OnionMessageResponse
  case object NoReply extends OnionMessageResponse
  case class Response(payload: FinalPayload) extends OnionMessageResponse
  sealed trait MessageStatus extends OnionMessageResponse
  case object MessageSent extends MessageStatus
  case class MessageFailed(reason: String) extends MessageStatus
  // @formatter:on

  def apply(nodeParams: NodeParams, switchboard: ActorRef[Switchboard.RelayMessage]): Behavior[Command] = {
    Behaviors.setup(context => {
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[OnionMessages.ReceiveMessage](r => WrappedMessage(r.finalPayload)))

      val relayMessageStatusAdapter = context.messageAdapter[MessageRelay.Status](SendingStatus)

      // For messages expecting a reply, send reply or failure to send
      val subscribed = new mutable.HashMap[ByteVector32, ActorRef[OnionMessageResponse]]()

      // For messages not expecting a reply, send success or failure to send
      val sendStatusTo = new mutable.HashMap[ByteVector32, ActorRef[OnionMessageResponse]]()

      Behaviors.receiveMessagePartial {
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
        case SendMessage(intermediateNodes, destination, replyPath, messageContent, replyTo, timeout) =>
          val messageId = randomBytes32()
          val replyRoute = replyPath.map(replyHops => {
            val intermediateHops = replyHops.dropRight(1).map(OnionMessages.IntermediateNode(_))
            val lastHop = OnionMessages.Recipient(replyHops.last, Some(messageId))
            OnionMessages.buildRoute(randomKey(), intermediateHops, lastHop)
          })
          OnionMessages.buildMessage(
            nodeParams.privateKey,
            randomKey(),
            randomKey(),
            intermediateNodes.map(OnionMessages.IntermediateNode(_)),
            destination,
            TlvStream(replyRoute.map(OnionMessagePayloadTlv.ReplyPath).toSet ++ messageContent.records, messageContent.unknown)) match {
            case Left(failure) =>
              replyTo ! MessageFailed(failure.toString)
            case Right((nextNodeId, message)) =>
              if (replyPath.isEmpty) { // not expecting reply
                sendStatusTo += (messageId -> replyTo)
              } else { // expecting reply
                subscribed += (messageId -> replyTo)
                context.scheduleOnce(timeout, context.self, Unsubscribe(messageId))
              }
              switchboard ! Switchboard.RelayMessage(messageId, None, nextNodeId, message, MessageRelay.RelayAll, Some(relayMessageStatusAdapter))
          }
          Behaviors.same
        case Unsubscribe(pathId) =>
          subscribed.get(pathId).foreach(ref => {
            subscribed -= pathId
            ref ! NoReply
          })
          Behaviors.same
        case SendingStatus(MessageRelay.Sent(messageId)) =>
          sendStatusTo.get(messageId).foreach(ref => {
            sendStatusTo -= messageId
            ref ! MessageSent
          })
          Behaviors.same
        case SendingStatus(status: MessageRelay.Failure) =>
          sendStatusTo.get(status.messageId).foreach(ref => {
            sendStatusTo -= status.messageId
            ref ! MessageFailed(status.toString)
          })
          subscribed.get(status.messageId).foreach(ref => {
            subscribed -= status.messageId
            ref ! MessageFailed(status.toString)
          })
          Behaviors.same
      }
    })
  }
}
