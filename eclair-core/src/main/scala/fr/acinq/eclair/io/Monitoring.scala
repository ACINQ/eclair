/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.io

import fr.acinq.eclair.payment.relay.OnTheFlyFunding
import kamon.Kamon

object Monitoring {

  object Metrics {
    val PeerConnectionsConnecting = Kamon.counter("peerconnections.connecting")
    val PeerConnectionsConnected = Kamon.gauge("peerconnections.connected")

    val PeersConnected = Kamon.gauge("peers.connected")

    val ReconnectionsAttempts = Kamon.counter("reconnections.attempts")

    val OnionMessagesProcessed = Kamon.counter("onionmessages.processed")
    val OnionMessagesThrottled = Kamon.counter("onionmessages.throttled")
    val OnionMessagesNotRelayed = Kamon.counter("onionmessages.not-relayed")

    val OpenChannelRequestsPending = Kamon.gauge("openchannelrequests.pending")

    val IncomingConnectionsNoChannels = Kamon.gauge("incomingconnections.nochannels")
    val IncomingConnectionsDisconnected = Kamon.counter("incomingconnections.disconnected")

    val OnTheFlyFunding = Kamon.counter("on-the-fly-funding.attempts")
    val OnTheFlyFundingFees = Kamon.histogram("on-the-fly-funding.fees-msat")
  }

  object Tags {
    val ConnectionState = "state"

    object ConnectionStates {
      val Connected = "connected"
      val Authenticating = "authenticating"
      val Authenticated = "authenticated"
      val Initializing = "initializing"
      val Initialized = "initialized"
    }

    val PublicPeers = "public"

    val Direction = "direction"
    object Directions {
      val Incoming = "IN"
      val Outgoing = "OUT"
    }

    val Reason = "reason"
    object Reasons {
      val UnknownNextNodeId = "UnknownNextNodeId"
      val NoChannelWithPreviousPeer = "NoChannelWithPreviousPeer"
      val NoChannelWithNextPeer = "NoChannelWithNextPeer"
      val ConnectionFailure = "ConnectionFailure"
    }

    val OnTheFlyFundingState = "state"
    object OnTheFlyFundingStates {
      val Proposed = "proposed"
      val Rejected = "rejected"
      val Expired = "expired"
      val Timeout = "timeout"
      val AddedToFeeCredit = "added-to-fee-credit"
      val Funded = "funded"
      val RelaySucceeded = "relay-succeeded"

      def relayFailed(failure: OnTheFlyFunding.PaymentRelayer.RelayFailure) = s"relay-failed-${failure.getClass.getPrettySimpleName}"
    }
  }

}
