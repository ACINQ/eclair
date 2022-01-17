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
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.io.{MessageRelay, Switchboard}
import fr.acinq.eclair.message.OnionMessages.ReceiveMessage
import fr.acinq.eclair.randomBytes32
import fr.acinq.eclair.wire.protocol.MessageOnion.FinalPayload
import fr.acinq.eclair.wire.protocol.OnionMessage
import scodec.bits.ByteVector

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

object Postman {
  // @formatter:off
  sealed trait Command
  case class SendMessage(nextNodeId: PublicKey,
                         message: OnionMessage,
                         replyPathId: Option[ByteVector32],
                         replyTo: ActorRef[OnionMessageResponse],
                         timeout: FiniteDuration) extends Command
  private case class Unsubscribe(pathId: ByteVector32) extends Command
  private case class WrappedMessage(finalPayload: FinalPayload, pathId: Option[ByteVector]) extends Command
  sealed trait OnionMessageResponse
  case object NoReply extends OnionMessageResponse
  case class Response(payload: FinalPayload) extends OnionMessageResponse
  case class SendingStatus(status: MessageRelay.Status) extends OnionMessageResponse with Command
  // @formatter:on

  def apply(switchboard: ActorRef[Switchboard.RelayMessage]): Behavior[Command] = {
    Behaviors.setup(context => {
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ReceiveMessage](r => WrappedMessage(r.finalPayload, r.pathId)))

      val relayMessageStatusAdapter = context.messageAdapter[MessageRelay.Status](SendingStatus)

      val subscribed = new mutable.HashMap[ByteVector32, ActorRef[OnionMessageResponse]]()
      val sendStatusTo = new mutable.HashMap[ByteVector32, ActorRef[OnionMessageResponse]]()
      val sendFailureTo = new mutable.HashMap[ByteVector32, ActorRef[OnionMessageResponse]]()

      Behaviors.receiveMessagePartial {
        case WrappedMessage(finalPayload, Some(pathId)) if pathId.length == 32 =>
          subscribed.get(ByteVector32(pathId)) match {
            case Some(ref) =>
              subscribed -= ByteVector32(pathId)
              ref ! Response(finalPayload)
            case None => () // ignoring message with unknown pathId
          }
          Behaviors.same
        case WrappedMessage(_, _) =>
          // ignoring message with invalid or missing pathId
          Behaviors.same
        case SendMessage(nextNodeId, message, None, ref, _) =>
          val messageId = randomBytes32()
          sendStatusTo += (messageId -> ref)
          switchboard ! Switchboard.RelayMessage(messageId, None, nextNodeId, message, MessageRelay.RelayAll, Some(relayMessageStatusAdapter))
          Behaviors.same
        case SendMessage(nextNodeId, message, Some(pathId), ref, timeout) =>
          val messageId = randomBytes32()
          sendFailureTo += (messageId -> ref)
          subscribed += (pathId -> ref)
          context.scheduleOnce(timeout, context.self, Unsubscribe(pathId))
          switchboard ! Switchboard.RelayMessage(messageId, None, nextNodeId, message, MessageRelay.RelayAll, Some(relayMessageStatusAdapter))
          Behaviors.same
        case Unsubscribe(pathId) =>
          subscribed.get(pathId).foreach(_ ! NoReply)
          subscribed -= pathId
          Behaviors.same
        case status@SendingStatus(MessageRelay.Sent(messageId)) =>
          sendStatusTo.get(messageId) match {
            case Some(ref) =>
              sendStatusTo -= messageId
              ref ! status
            case None => ()
          }
          Behaviors.same
        case SendingStatus(status: MessageRelay.Failure) =>
          sendStatusTo.get(status.messageId) match {
            case Some(ref) =>
              sendStatusTo -= status.messageId
              ref ! SendingStatus(status)
            case None => ()
          }
          sendFailureTo.get(status.messageId) match {
            case Some(ref) =>
              sendFailureTo -= status.messageId
              ref ! SendingStatus(status)
            case None => ()
          }
          Behaviors.same
      }
    })
  }
}
