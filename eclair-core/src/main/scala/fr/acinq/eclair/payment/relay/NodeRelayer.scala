/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.payment.relay

import java.util.UUID

import akka.actor.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{Logs, NodeParams}

/**
 * Created by t-bast on 10/10/2019.
 */

/**
 * The [[NodeRelayer]] relays an upstream payment to a downstream remote node (which is not necessarily a direct peer). It
 * doesn't do the job itself, instead it dispatches each individual payment (which can be multi-in, multi-out) to a child
 * actor of type [[NodeRelay]].
 */
object NodeRelayer {

  // @formatter:off
  sealed trait Command
  case class Relay(nodeRelayPacket: IncomingPacket.NodeRelayPacket) extends Command
  // @formatter:on

  def mdc: Command => Map[String, String] = {
    case c: Relay => Logs.mdc(
      paymentHash_opt = Some(c.nodeRelayPacket.add.paymentHash))
  }

  def apply(nodeParams: NodeParams, router: ActorRef, register: ActorRef): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT)), mdc) {
          Behaviors.receiveMessage {
            case Relay(nodeRelayPacket) =>
              import nodeRelayPacket.add.paymentHash
              val handler = context.child(paymentHash.toString) match {
                case Some(handler) =>
                  // NB: we could also maintain a list of children
                  handler.unsafeUpcast[NodeRelay.Command] // we know that all children are of type NodeRelay
                case None =>
                  val relayId = UUID.randomUUID()
                  context.log.debug(s"spawning a new handler with relayId=$relayId")
                  // we index children by paymentHash, not relayId, because there is no concept of individual payment on LN
                  context.spawn(NodeRelay.apply(nodeParams, router, register, relayId, paymentHash), name = paymentHash.toString)
              }
              context.log.debug("forwarding incoming htlc to handler")
              handler ! NodeRelay.Relay(nodeRelayPacket)
              Behaviors.same
          }
      }
    }
}
