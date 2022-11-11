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

import akka.actor.typed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{Logs, NodeParams}

import java.util.UUID

/**
 * Created by t-bast on 10/10/2019.
 */

/**
 * The [[NodeRelayer]] relays an upstream payment to a downstream remote node (which is not necessarily a direct peer).
 * It doesn't do the job itself, instead it dispatches each individual payment (which can be multi-in, multi-out) to a
 * child actor of type [[NodeRelay]].
 */
object NodeRelayer {

  // @formatter:off
  sealed trait Command
  case class Relay(nodeRelayPacket: IncomingPaymentPacket.NodeRelayPacket) extends Command
  case class RelayComplete(childHandler: ActorRef[NodeRelay.Command], paymentHash: ByteVector32, paymentSecret: ByteVector32) extends Command
  private[relay] case class GetPendingPayments(replyTo: akka.actor.ActorRef) extends Command
  // @formatter:on

  def mdc: Command => Map[String, String] = {
    case c: Relay => Logs.mdc(paymentHash_opt = Some(c.nodeRelayPacket.add.paymentHash))
    case c: RelayComplete => Logs.mdc(paymentHash_opt = Some(c.paymentHash))
    case _: GetPendingPayments => Logs.mdc()
  }

  case class PaymentKey(paymentHash: ByteVector32, paymentSecret: ByteVector32)

  /**
   * @param children a map of pending payments. We must index by both payment hash and payment secret because we may
   *                 need to independently relay multiple parts of the same payment using distinct payment secrets.
   *                 NB: the payment secret used here is different from the invoice's payment secret and ensures we can
   *                 group together HTLCs that the previous trampoline node sent in the same MPP.
   */
  def apply(nodeParams: NodeParams, register: akka.actor.ActorRef, outgoingPaymentFactory: NodeRelay.OutgoingPaymentFactory, triggerer: typed.ActorRef[AsyncPaymentTriggerer.Command], children: Map[PaymentKey, ActorRef[NodeRelay.Command]] = Map.empty): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withMdc(Logs.mdc(category_opt = Some(Logs.LogCategory.PAYMENT)), mdc) {
        Behaviors.receiveMessage {
          case Relay(nodeRelayPacket) =>
            val htlcIn = nodeRelayPacket.add
            val childKey = PaymentKey(htlcIn.paymentHash, nodeRelayPacket.outerPayload.paymentSecret)
            children.get(childKey) match {
              case Some(handler) =>
                context.log.debug("forwarding incoming htlc #{} from channel {} to existing handler", htlcIn.id, htlcIn.channelId)
                handler ! NodeRelay.Relay(nodeRelayPacket)
                Behaviors.same
              case None =>
                val relayId = UUID.randomUUID()
                context.log.debug(s"spawning a new handler with relayId=$relayId")
                val handler = context.spawn(NodeRelay.apply(nodeParams, context.self, register, relayId, nodeRelayPacket, outgoingPaymentFactory, triggerer), relayId.toString)
                context.log.debug("forwarding incoming htlc #{} from channel {} to new handler", htlcIn.id, htlcIn.channelId)
                handler ! NodeRelay.Relay(nodeRelayPacket)
                apply(nodeParams, register, outgoingPaymentFactory, triggerer, children + (childKey -> handler))
            }
          case RelayComplete(childHandler, paymentHash, paymentSecret) =>
            // we do a back-and-forth between parent and child before stopping the child to prevent a race condition
            childHandler ! NodeRelay.Stop
            apply(nodeParams, register, outgoingPaymentFactory, triggerer, children - PaymentKey(paymentHash, paymentSecret))
          case GetPendingPayments(replyTo) =>
            replyTo ! children
            Behaviors.same
        }
      }
    }
}
