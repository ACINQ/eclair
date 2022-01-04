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

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.message.OnionMessages.{OnionMessageResponse, ReceiveMessage}
import fr.acinq.eclair.wire.protocol.MessageOnion.FinalPayload
import scodec.bits.ByteVector

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

object Postman {
  sealed trait Command

  case class SubscribeOnce(pathId: ByteVector32, ref: ActorRef[OnionMessageResponse], timeout: FiniteDuration) extends Command
  case class Unsubscribe(pathId: ByteVector32) extends Command
  case class WrappedMessage(finalPayload: FinalPayload, pathId: Option[ByteVector]) extends Command

  case object NoReply extends OnionMessageResponse

  def apply(): Behavior[Command] = {
    Behaviors.setup(context => {
      context.system.eventStream ! EventStream.Subscribe(context.messageAdapter[ReceiveMessage](r => WrappedMessage(r.finalPayload, r.pathId)))

      val subscribed = new mutable.HashMap[ByteVector32, ActorRef[OnionMessageResponse]]()

      Behaviors.receiveMessagePartial {
        case WrappedMessage(finalPayload, Some(pathId)) if pathId.length == 32 =>
          subscribed.get(ByteVector32(pathId)) match {
            case Some(ref) =>
              subscribed -= ByteVector32(pathId)
              ref ! finalPayload
            case None => () // ignoring message with unknown pathId
          }
          Behaviors.same
        case WrappedMessage(_, _) =>
          // ignoring message with invalid pathId
          Behaviors.same
        case SubscribeOnce(pathId, ref, timeout) =>
          subscribed += (pathId -> ref)
          context.scheduleOnce(timeout, context.self, Unsubscribe(pathId))
          Behaviors.same
        case Unsubscribe(pathId) =>
          subscribed.get(pathId).foreach(_ ! NoReply)
          subscribed -= pathId
          Behaviors.same
      }
    })
  }
}
