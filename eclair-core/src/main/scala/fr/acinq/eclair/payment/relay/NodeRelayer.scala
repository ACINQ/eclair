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

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{Logs, NodeParams}

import java.util.UUID

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
  case class RelayComplete(childHandler: ActorRef[NodeRelay.Command], paymentHash: ByteVector32) extends Command
  private[relay] case class GetPendingPayments(replyTo: akka.actor.ActorRef) extends Command
  // @formatter:on

  def mdc: Command => Map[String, String] = {
    case c: Relay => Logs.mdc(paymentHash_opt = Some(c.nodeRelayPacket.add.paymentHash))
    case c: RelayComplete => Logs.mdc(paymentHash_opt = Some(c.paymentHash))
    case _: GetPendingPayments => Logs.mdc()
  }

  /**
   * @param children a map of current in-process payments, indexed by payment hash and purposefully *not* by payment id,
   *                 because that is how we aggregate payment parts (when the incoming payment uses MPP).
   */
  def apply(nodeParams: NodeParams, router: akka.actor.ActorRef, register: akka.actor.ActorRef, children: Map[ByteVector32, ActorRef[NodeRelay.Command]] = Map.empty): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT)), mdc) {
        Behaviors.receiveMessage {
          case Relay(nodeRelayPacket) =>
            import nodeRelayPacket.add.paymentHash
            children.get(paymentHash) match {
              case Some(handler) =>
                context.log.debug("forwarding incoming htlc to existing handler")
                handler ! NodeRelay.Relay(nodeRelayPacket)
                Behaviors.same
              case None =>
                val relayId = UUID.randomUUID()
                context.log.debug(s"spawning a new handler with relayId=$relayId")
                val handler = context.spawn(NodeRelay.apply(nodeParams, context.self, router, register, relayId, paymentHash), relayId.toString)
                context.log.debug("forwarding incoming htlc to new handler")
                handler ! NodeRelay.Relay(nodeRelayPacket)
                apply(nodeParams, router, register, children + (paymentHash -> handler))
            }
          case RelayComplete(childHandler, paymentHash) =>
            // we do a back-and-forth between parent and child before stopping the child to prevent a race condition
            childHandler ! NodeRelay.Stop
            apply(nodeParams, router, register, children - paymentHash)
          case GetPendingPayments(replyTo) =>
            replyTo ! children
            Behaviors.same
        }
      }
    }
}
