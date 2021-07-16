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

package fr.acinq.eclair.channel

import fr.acinq.eclair.transactions.{CommitmentSpec, DirectedHtlc}
import kamon.Kamon

object Monitoring {

  object Metrics {
    val ChannelsCount = Kamon.gauge("channels.count")
    val ChannelErrors = Kamon.counter("channels.errors")
    val ChannelLifecycleEvents = Kamon.counter("channels.lifecycle")
    val HtlcsInFlight = Kamon.histogram("channels.htlc-in-flight", "Per-channel HTLCs in flight")
    val HtlcsInFlightGlobal = Kamon.gauge("channels.htlc-in-flight-global", "Global HTLCs in flight across all channels")
    val HtlcValueInFlight = Kamon.histogram("channels.htlc-value-in-flight", "Per-channel HTLC value in flight")
    val HtlcValueInFlightGlobal = Kamon.gauge("channels.htlc-value-in-flight-global", "Global HTLC value in flight across all channels")
    val LocalFeeratePerKw = Kamon.gauge("channels.local-feerate-per-kw")
    val RemoteFeeratePerKw = Kamon.histogram("channels.remote-feerate-per-kw")
    val ProcessMessage = Kamon.timer("channels.messages-processed")

    def recordHtlcsInFlight(remoteSpec: CommitmentSpec, previousRemoteSpec: CommitmentSpec): Unit = {
      for (direction <- Tags.Directions.Incoming :: Tags.Directions.Outgoing :: Nil) {
        // NB: IN/OUT htlcs are inverted because this is the remote commit
        val filter = if (direction == Tags.Directions.Incoming) DirectedHtlc.outgoing else DirectedHtlc.incoming
        // NB: we need the `toSeq` because otherwise duplicate amounts would be removed (since htlcs are sets)
        val htlcs = remoteSpec.htlcs.collect(filter).toSeq.map(_.amountMsat)
        val previousHtlcs = previousRemoteSpec.htlcs.collect(filter).toSeq.map(_.amountMsat)
        HtlcsInFlight.withTag(Tags.Direction, direction).record(htlcs.length)
        HtlcsInFlightGlobal.withTag(Tags.Direction, direction).increment(htlcs.length - previousHtlcs.length)
        val (value, previousValue) = (htlcs.sum.truncateToSatoshi.toLong, previousHtlcs.sum.truncateToSatoshi.toLong)
        HtlcValueInFlight.withTag(Tags.Direction, direction).record(value)
        HtlcValueInFlightGlobal.withTag(Tags.Direction, direction).increment((value - previousValue).toDouble)
      }
    }
  }

  object Tags {
    val Direction = "direction"
    val Event = "event"
    val Fatal = "fatal"
    val Origin = "origin"
    val State = "state"

    object Events {
      val Created = "created"
      val Closing = "closing"
      val Closed = "closed"
    }

    object Origins {
      val Local = "local"
      val Remote = "remote"
    }

    object Directions {
      val Incoming = "incoming"
      val Outgoing = "outgoing"
    }

  }

}
