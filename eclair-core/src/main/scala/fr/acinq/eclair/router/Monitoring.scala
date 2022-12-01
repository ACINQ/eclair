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

package fr.acinq.eclair.router

import fr.acinq.bitcoin.scalacompat.{BtcDouble, MilliBtcDouble, SatoshiLong}
import fr.acinq.eclair.router.Router.GossipDecision
import fr.acinq.eclair.wire.protocol.ChannelUpdate
import fr.acinq.eclair.{MilliSatoshi, getSimpleClassName}
import kamon.Kamon
import kamon.metric.{Counter, MeasurementUnit}

object Monitoring {

  object Metrics {
    val FindRouteDuration = Kamon.timer("router.find-route.duration", "Path-finding duration")
    val FindRouteErrors = Kamon.counter("router.find-route.errors", "Path-finding errors")
    val RouteLength = Kamon.histogram("router.find-route.length", "Path-finding result length")
    val RouteResults = Kamon.histogram("router.find-route.results", "Path-finding number of routes found")

    object QueryChannelRange {
      val Blocks = Kamon.histogram("router.gossip.query-channel-range.blocks", "Number of blocks requested in query-channel-range")
      val Replies = Kamon.histogram("router.gossip.query-channel-range.replies", "Number of reply-channel-range replies sent")
    }

    object ReplyChannelRange {
      val ShortChannelIds = Kamon.histogram("router.gossip.reply-channel-range.ids", "Number of short channel ids in reply-channel-range")
      val Blocks = Kamon.histogram("router.gossip.reply-channel-range.blocks", "Number of blocks in reply-channel-range")
      val NewChannelAnnouncements = Kamon.histogram("router.gossip.reply-channel-range.new-channel-announcements", "Number of new channel announcements discovered in reply-channel-range")
      val NewChannelUpdates = Kamon.histogram("router.gossip.reply-channel-range.new-channel-updates", "Number of new channel updates discovered in reply-channel-range")
    }

    object QueryShortChannelIds {
      val Nodes = Kamon.histogram("router.gossip.query-short-channel-ids.node-announcements", "Number of node announcements sent in response to a query-short-channel-ids")
      val ChannelAnnouncements = Kamon.histogram("router.gossip.query-short-channel-ids.channel-announcements", "Number of channel announcements sent in response to a query-short-channel-ids")
      val ChannelUpdates = Kamon.histogram("router.gossip.query-short-channel-ids.channel-updates", "Number of channel updates sent in response to a query-short-channel-ids")
    }

    val Nodes = Kamon.gauge("router.gossip.nodes", "Number of known nodes in the network")
    val Channels = Kamon.gauge("router.gossip.channels", "Number of known channels in the network")
    val SyncProgress = Kamon.gauge("router.gossip.sync-progress", "Routing table sync progress (%)", MeasurementUnit.percentage)

    private val ChannelUpdateRefreshRate = Kamon.histogram("router.gossip.channel-update-refresh-rate", "Rate at which channels update their fee policy (minutes)")

    def channelUpdateRefreshed(update: ChannelUpdate, previous: ChannelUpdate, public: Boolean): Unit = {
      val elapsed = update.timestamp - previous.timestamp
      ChannelUpdateRefreshRate.withTag(Tags.Announced, public).record(elapsed.toMinutes)
    }

    private val GossipResult = Kamon.counter("router.gossip.result")

    def gossipResult(decision: GossipDecision): Counter = decision match {
      case _: GossipDecision.Accepted => GossipResult.withTag("result", "accepted")
      case rejected: GossipDecision.Rejected => GossipResult.withTag("result", "rejected").withTag("reason", getSimpleClassName(rejected))
    }

    private val RelayProbabilityEstimate = Kamon.histogram("router.balance-estimates.remote-edge-relay", "Estimated probability (in percent) that the relay will be successful")

    def remoteEdgeRelaySuccess(estimatedProbability: Double) = RelayProbabilityEstimate.withTag("status", "success").record((estimatedProbability * 100).toLong)

    def remoteEdgeRelayFailure(estimatedProbability: Double) = RelayProbabilityEstimate.withTag("status", "failure").record((estimatedProbability * 100).toLong)
  }

  object Tags {
    val Amount = "amount"
    val Announced = "announced"
    val Direction = "direction"
    val Error = "error"
    val MultiPart = "multiPart"
    val NumberOfRoutes = "numRoutes"

    object Directions {
      val Incoming = "incoming"
      val Outgoing = "outgoing"
    }

    /**
     * We split amounts in buckets that can be used to tag metrics.
     * The goal is to detect if some amount buckets end up killing the performance of the path-finding.
     */
    def amountBucket(amount: MilliSatoshi): String = {
      val tiny = "0 sat < 1000 sat"
      val small = "1000 sat < 1 mBTC"
      val medium = "1 mBTC < 100 mBTC"
      val big = "100 mBTC < 1 BTC"
      val reckless = "1 BTC < ???"
      amount match {
        case amount if amount < 1000.sat => tiny
        case amount if amount < 1.millibtc => small
        case amount if amount < 100.millibtc => medium
        case amount if amount < 1.btc => big
        case _ => reckless
      }
    }
  }

}
