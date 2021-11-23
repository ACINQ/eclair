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

package fr.acinq.eclair.payment.send

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.crypto.Sphinx.DecryptedFailurePacket
import fr.acinq.eclair.payment.{Bolt11Invoice, PaymentEvent, PaymentFailed, RemoteFailure}
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.wire.protocol.IncorrectOrUnknownPaymentDetails
import fr.acinq.eclair.{MilliSatoshiLong, NodeParams, TimestampSecond, randomBytes32, randomLong}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * This actor periodically probes the network by sending payments to random nodes. The payments will eventually fail
 * because the recipient doesn't know the preimage, but it allows us to test channels and improve routing for real payments.
 */
class Autoprobe(nodeParams: NodeParams, router: ActorRef, paymentInitiator: ActorRef) extends Actor with ActorLogging {

  import Autoprobe._

  import scala.concurrent.ExecutionContext.Implicits.global

  // refresh our map of channel_updates regularly from the router
  context.system.scheduler.scheduleWithFixedDelay(0 seconds, ROUTING_TABLE_REFRESH_INTERVAL, router, Router.GetRouterData)

  override def receive: Receive = {
    case routingData: Router.Data =>
      scheduleProbe()
      context become main(routingData)
  }

  def main(routingData: Router.Data): Receive = {
    case routingData: Router.Data =>
      context become main(routingData)

    case TickProbe =>
      pickPaymentDestination(nodeParams.nodeId, routingData) match {
        case Some(targetNodeId) =>
          val fakeInvoice = Bolt11Invoice(
            Bolt11Invoice.prefixes(nodeParams.chainHash),
            Some(PAYMENT_AMOUNT_MSAT),
            TimestampSecond.now(),
            targetNodeId,
            List(
              Bolt11Invoice.PaymentHash(randomBytes32()), // we don't even know the preimage (this needs to be a secure random!)
              Bolt11Invoice.Description("ignored"),
            ),
            ByteVector.empty)
          log.info(s"sending payment probe to node=$targetNodeId payment_hash=${fakeInvoice.paymentHash}")
          val routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams
          paymentInitiator ! PaymentInitiator.SendPaymentToNode(self, PAYMENT_AMOUNT_MSAT, fakeInvoice, maxAttempts = 1, routeParams = routeParams)
        case None =>
          log.info(s"could not find a destination, re-scheduling")
          scheduleProbe()
      }

    case paymentResult: PaymentEvent =>
      paymentResult match {
        case PaymentFailed(_, _, _ :+ RemoteFailure(_, _, DecryptedFailurePacket(targetNodeId, _: IncorrectOrUnknownPaymentDetails)), _) =>
          log.info(s"payment probe successful to node=$targetNodeId")
        case _ =>
          log.info(s"payment probe failed with paymentResult=$paymentResult")
      }
      scheduleProbe()
  }

  def scheduleProbe() = context.system.scheduler.scheduleOnce(PROBING_INTERVAL, self, TickProbe)

}

object Autoprobe {

  def props(nodeParams: NodeParams, router: ActorRef, paymentInitiator: ActorRef) = Props(new Autoprobe(nodeParams, router, paymentInitiator))

  val ROUTING_TABLE_REFRESH_INTERVAL = 10 minutes

  val PROBING_INTERVAL = 20 seconds

  val PAYMENT_AMOUNT_MSAT = (100 * 1000) msat // this is below dust_limit so there won't be an output in the commitment tx

  object TickProbe

  def pickPaymentDestination(nodeId: PublicKey, routingData: Router.Data): Option[PublicKey] = {
    // we only pick direct peers with enabled public channels
    val peers = routingData.channels
      .collect {
        case (shortChannelId, c@Router.PublicChannel(ann, _, _, Some(u1), _, _))
          if c.getNodeIdSameSideAs(u1) == nodeId && u1.channelFlags.isEnabled && routingData.channels.exists(_._1 == shortChannelId) => ann.nodeId2 // we only consider outgoing channels that are enabled and announced
      }
    if (peers.isEmpty) {
      None
    } else {
      peers.drop(randomLong().toInt % peers.size).headOption
    }
  }

}
