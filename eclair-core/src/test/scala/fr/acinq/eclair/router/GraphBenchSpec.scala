/*
 * Copyright 2023 ACINQ SAS
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

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.db.pg.PgNetworkDb
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.router.Graph.{WeightRatios, yenKshortestPaths}
import fr.acinq.eclair.wire.protocol.NodeAnnouncement
import fr.acinq.eclair.{BlockHeight, MilliSatoshiLong, RealShortChannelId, TimestampMilli}
import org.scalatest.funsuite.AnyFunSuite
import scodec.bits.HexStringSyntax

import scala.collection.immutable.SortedMap

class GraphBenchSpec extends AnyFunSuite {

  val host = ""
  val port = 0
  val database = "eclair"

  val hikariConfig = new HikariConfig()
  hikariConfig.setJdbcUrl(s"jdbc:postgresql://$host:$port/$database")
  hikariConfig.setUsername("")
  hikariConfig.setPassword("")

  implicit val ds: HikariDataSource = new HikariDataSource(hikariConfig)

  val db = new PgNetworkDb

  val channels: SortedMap[RealShortChannelId, Router.PublicChannel] = db.listChannels()
  val nodes: Seq[NodeAnnouncement] = db.listNodes()

  val acinq = PublicKey(hex"03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f")
  val currentBlockHeight = BlockHeight(795000)

  test("makeGraph") {
    val t0 = TimestampMilli.now()
    val graph = DirectedGraph.makeGraph(channels)
    val t1 = TimestampMilli.now()
    println(s"makeGraph: ${t1 - t0}")

    val top1000 = graph.vertexSet().map(v => (v, graph.getIncomingEdgesOf(v).map(edge => edge.capacity.toLong).sum)).toSeq.sortBy(_._2).takeRight(1000).map(_._1)
    val wr = Left(WeightRatios(baseFactor = 0.5, cltvDeltaFactor = 0.3, ageFactor = 0.1, capacityFactor = 0.1, hopCost = RelayFees(100 msat, 888)))
    val t2 = TimestampMilli.now()
    top1000.foreach(node =>
      yenKshortestPaths(graph, acinq, node, 1000000000 msat, Set.empty, Set.empty, Set.empty, pathsToFind = 3, wr, currentBlockHeight, _ => true, includeLocalChannelCost = true)
    )
    val t3 = TimestampMilli.now()
    println(s"yenKshortestPaths: ${t3 - t2}")
  }

  test("addEdges") {
    val t0 = TimestampMilli.now()
    val edges = channels.values.flatMap(channel => Seq(channel.update_1_opt.map(GraphEdge(_, channel)), channel.update_2_opt.map(GraphEdge(_, channel))).flatten)
    val graph = DirectedGraph().addEdges(edges)
    val t1 = TimestampMilli.now()
    println(s"addEdges: ${t1 - t0}")

    val top1000 = graph.vertexSet().map(v => (v, graph.getIncomingEdgesOf(v).map(edge => edge.capacity.toLong).sum)).toSeq.sortBy(_._2).takeRight(1000).map(_._1)
    val wr = Left(WeightRatios(baseFactor = 0.5, cltvDeltaFactor = 0.3, ageFactor = 0.1, capacityFactor = 0.1, hopCost = RelayFees(100 msat, 888)))
    val t2 = TimestampMilli.now()
    top1000.foreach(node =>
      yenKshortestPaths(graph, acinq, node, 1000000000 msat, Set.empty, Set.empty, Set.empty, pathsToFind = 3, wr, currentBlockHeight, _ => true, includeLocalChannelCost = true)
    )
    val t3 = TimestampMilli.now()
    println(s"yenKshortestPaths: ${t3 - t2}")

  }
}
