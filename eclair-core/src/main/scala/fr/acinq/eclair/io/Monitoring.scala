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

import kamon.Kamon

object Monitoring {

  object Metrics {
    val PeerConnectionsConnecting = Kamon.counter("peerconnections.connecting")
    val PeerConnectionsConnected = Kamon.gauge("peerconnections.connected")

    val PeersConnected = Kamon.gauge("peers.connected")

    val ReconnectionsAttempts = Kamon.counter("reconnections.attempts")

    val OnionMessagesReceived = Kamon.counter("onionmessages.received")
    val OnionMessagesSent = Kamon.counter("onionmessages.sent")
    val OnionMessagesThrottled = Kamon.counter("onionmessages.throttled")

    val OpenChannelRequestsPending = Kamon.gauge("openchannelrequests.pending")
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

  }

}
