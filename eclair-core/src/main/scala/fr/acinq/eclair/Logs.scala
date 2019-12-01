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

package fr.acinq.eclair

import java.util.UUID

import akka.actor.Terminated
import akka.event.DiagnosticLoggingAdapter
import akka.event.Logging.MDC
import akka.io.Tcp
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.blockchain.ValidateResult
import fr.acinq.eclair.channel.{LocalChannelDown, LocalChannelUpdate}
import fr.acinq.eclair.crypto.TransportHandler.HandshakeCompleted
import fr.acinq.eclair.io.Peer.PeerRoutingMessage
import fr.acinq.eclair.io.{Authenticator, Peer}
import fr.acinq.eclair.router.{ExcludeChannel, GetRoutingState, LiftChannelExclusion, Rebroadcast, RouteRequest, SyncProgress, TickBroadcast, TickPruneStaleChannels}
import fr.acinq.eclair.wire.{ChannelReestablish, Ping, Pong, RoutingMessage, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFulfillHtlc}

object Logs {

  def mdc(category_opt: Option[LogCategory] = None, remoteNodeId_opt: Option[PublicKey] = None, channelId_opt: Option[ByteVector32] = None, parentPaymentId_opt: Option[UUID] = None, paymentId_opt: Option[UUID] = None, paymentHash_opt: Option[ByteVector32] = None): MDC =
    Seq(
      category_opt.map(l => "category" -> s" ${l.category}"),
      remoteNodeId_opt.map(n => "nodeId" -> s" n:$n"), // nb: we preformat MDC values so that there is no white spaces in logs when they are not defined
      channelId_opt.map(c => "channelId" -> s" c:$c"),
      parentPaymentId_opt.map(p => "parentPaymentId" -> s" p:$p"),
      paymentId_opt.map(i => "paymentId" -> s" i:$i"),
      paymentHash_opt.map(h => "paymentHash" -> s" h:$h")
    ).flatten.toMap

  /**
    * Temporarily add the provided MDC to the current one, and then restore the original one.
    *
    * This is useful in some cases where we can't rely on the `aroundReceive` trick to set the MDC before processing a
    * message because we don't have enough context. That's typically the case when handling `Terminated` messages.
    */
  def withMdc(log: DiagnosticLoggingAdapter)(mdc: MDC)(f: => Any) = {
    val mdc0 = log.mdc // backup the current mdc
    try {
      log.mdc(mdc0 ++ mdc) // add the new mdc to the current one
      f
    } finally {
      log.mdc(mdc0) // restore the original mdc
    }
  }

  // @formatter: off
  sealed trait LogCategory {
    def category: String
  }

  object LogCategory {

    case object CONNECTION extends LogCategory {
      override def category: String = "CON"
    }

    case object ROUTING_SYNC extends LogCategory {
      override def category: String = "SYN"
    }

    case object PAYMENT extends LogCategory {
      override def category: String = "PAY"
    }

    def apply(currentMessage: Any): Option[LogCategory] = {
      currentMessage match {
        case _: Tcp.Event => Some(Logs.LogCategory.CONNECTION)
        case _: Tcp.Message => Some(Logs.LogCategory.CONNECTION)
        case _: ChannelReestablish => Some(LogCategory.CONNECTION)

        case _: UpdateAddHtlc => Some(Logs.LogCategory.PAYMENT)
        case _: UpdateFulfillHtlc => Some(Logs.LogCategory.PAYMENT)
        case _: UpdateFailHtlc => Some(Logs.LogCategory.PAYMENT)
        case _: UpdateFailMalformedHtlc => Some(Logs.LogCategory.PAYMENT)

        case _: ExcludeChannel => Some(LogCategory.PAYMENT)
        case _: LiftChannelExclusion => Some(LogCategory.PAYMENT)
        case _: SyncProgress => Some(LogCategory.ROUTING_SYNC)
        case GetRoutingState => Some(LogCategory.ROUTING_SYNC)
        case _: LocalChannelUpdate => Some(LogCategory.ROUTING_SYNC)
        case _: LocalChannelDown => Some(LogCategory.ROUTING_SYNC)
        case _: ValidateResult => Some(LogCategory.ROUTING_SYNC)
        case _: RouteRequest => Some(LogCategory.PAYMENT)
        case _: PeerRoutingMessage => Some(LogCategory.ROUTING_SYNC)
        case _: RoutingMessage => Some(LogCategory.ROUTING_SYNC)
        case TickBroadcast => Some(LogCategory.ROUTING_SYNC)
        case TickPruneStaleChannels => Some(LogCategory.ROUTING_SYNC)

        case _: Authenticator.Authenticated => Some(LogCategory.CONNECTION)
        case _: Authenticator.PendingAuth => Some(LogCategory.CONNECTION)
        case _: HandshakeCompleted => Some(LogCategory.CONNECTION)
        case _: Peer.Connect => Some(LogCategory.CONNECTION)
        case _: Peer.Disconnect => Some(LogCategory.CONNECTION)
        case _: Peer.DelayedRebroadcast => Some(LogCategory.ROUTING_SYNC)
        case Peer.Reconnect => Some(LogCategory.CONNECTION)
        case _: Ping => Some(LogCategory.CONNECTION)
        case _: Pong => Some(LogCategory.CONNECTION)
        case _: wire.Init => Some(LogCategory.CONNECTION)
        case _: Rebroadcast => Some(LogCategory.ROUTING_SYNC)

        case _ => None
      }
    }
  }

  // @formatter: on
}

// we use a dedicated class so that the logging can be independently adjusted
case class Diagnostics()
