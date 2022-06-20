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

package fr.acinq.eclair.router.graph.structure

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.RealShortChannelId
import fr.acinq.eclair.router.Router.{ChannelDesc, ChannelHop, PublicChannel}

import scala.collection.immutable.SortedMap
import scala.collection.mutable

/**
 * A graph data structure that uses an adjacency list to store the incoming edges of the neighbors
 */
case class DirectedGraph(private val vertices: Map[PublicKey, List[GraphEdge]]) {

  def addEdges(edges: Iterable[GraphEdge]): DirectedGraph = edges.foldLeft(this)((acc, edge) => acc.addEdge(edge))

  /**
   * Adds an edge to the graph. If one of the two vertices is not found it will be created.
   *
   * @param edge the edge that is going to be added to the graph
   * @return a new graph containing this edge
   */
  def addEdge(edge: GraphEdge): DirectedGraph = {
    val vertexIn = edge.desc.a
    val vertexOut = edge.desc.b
    // the graph is allowed to have multiple edges between the same vertices but only one per channel
    if (containsEdge(edge.desc)) {
      removeEdge(edge.desc).addEdge(edge) // the recursive call will have the original params
    } else {
      val withVertices = addVertex(vertexIn).addVertex(vertexOut)
      DirectedGraph(withVertices.vertices.updated(vertexOut, edge +: withVertices.vertices(vertexOut)))
    }
  }

  /**
   * Removes the edge corresponding to the given pair channel-desc/channel-update,
   * NB: this operation does NOT remove any vertex
   *
   * @param desc the channel description associated to the edge that will be removed
   * @return a new graph without this edge
   */
  def removeEdge(desc: ChannelDesc): DirectedGraph = {
    if (containsEdge(desc)) {
      DirectedGraph(vertices.updated(desc.b, vertices(desc.b).filterNot(_.desc == desc)))
    } else {
      this
    }
  }

  def removeEdges(descList: Iterable[ChannelDesc]): DirectedGraph = {
    descList.foldLeft(this)((acc, edge) => acc.removeEdge(edge))
  }

  /**
   * @return For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
   */
  def getEdge(edge: GraphEdge): Option[GraphEdge] = getEdge(edge.desc)

  def getEdge(desc: ChannelDesc): Option[GraphEdge] = {
    vertices.get(desc.b).flatMap { adj =>
      adj.find(e => e.desc.shortChannelId == desc.shortChannelId && e.desc.a == desc.a)
    }
  }

  /**
   * @param keyA the key associated with the starting vertex
   * @param keyB the key associated with the ending vertex
   * @return all the edges going from keyA --> keyB (there might be more than one if there are multiple channels)
   */
  def getEdgesBetween(keyA: PublicKey, keyB: PublicKey): Seq[GraphEdge] = {
    vertices.get(keyB) match {
      case None => Seq.empty
      case Some(adj) => adj.filter(e => e.desc.a == keyA)
    }
  }

  /**
   * @param keyB the key associated with the target vertex
   * @return all edges incoming to that vertex
   */
  def getIncomingEdgesOf(keyB: PublicKey): Seq[GraphEdge] = {
    vertices.getOrElse(keyB, List.empty)
  }

  /**
   * Removes a vertex and all its associated edges (both incoming and outgoing)
   */
  def removeVertex(key: PublicKey): DirectedGraph = {
    DirectedGraph(removeEdges(getIncomingEdgesOf(key).map(_.desc)).vertices - key)
  }

  /**
   * Adds a new vertex to the graph, starting with no edges
   */
  def addVertex(key: PublicKey): DirectedGraph = {
    vertices.get(key) match {
      case None => DirectedGraph(vertices + (key -> List.empty))
      case _ => this
    }
  }

  /**
   * Note this operation will traverse all edges in the graph (expensive)
   *
   * @return a list of the outgoing edges of the given vertex. If the vertex doesn't exists an empty list is returned.
   */
  def edgesOf(key: PublicKey): Seq[GraphEdge] = {
    edgeSet().filter(_.desc.a == key).toSeq
  }

  /**
   * @return the set of all the vertices in this graph
   */
  def vertexSet(): Set[PublicKey] = vertices.keySet

  /**
   * @return an iterator of all the edges in this graph
   */
  def edgeSet(): Iterable[GraphEdge] = vertices.values.flatten

  /**
   * @return true if this graph contain a vertex with this key, false otherwise
   */
  def containsVertex(key: PublicKey): Boolean = vertices.contains(key)

  /**
   * @return true if this edge desc is in the graph. For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
   */
  def containsEdge(desc: ChannelDesc): Boolean = {
    vertices.get(desc.b) match {
      case None => false
      case Some(adj) => adj.exists(neighbor => neighbor.desc.shortChannelId == desc.shortChannelId && neighbor.desc.a == desc.a)
    }
  }

  def prettyPrint(): String = {
    vertices.foldLeft("") { case (acc, (vertex, adj)) =>
      acc + s"[${vertex.toString().take(5)}]: ${adj.map("-> " + _.desc.b.toString().take(5))} \n"
    }
  }
}

object DirectedGraph {

  // @formatter:off
  def apply(): DirectedGraph = new DirectedGraph(Map())

  def apply(key: PublicKey): DirectedGraph = new DirectedGraph(Map(key -> List.empty))

  def apply(edge: GraphEdge): DirectedGraph = DirectedGraph().addEdge(edge)

  def apply(edges: Seq[GraphEdge]): DirectedGraph = DirectedGraph().addEdges(edges)
  // @formatter:on

  /**
   * This is the recommended way of initializing the network graph (from a public network DB).
   * We only use public channels at first; private channels will be added one by one as they come online, and removed
   * as they go offline.
   * Private channels may be used to route payments, but most of the time, they will be the first or last hop.
   *
   * @param channels map of all known public channels in the network.
   */
  def makeGraph(channels: SortedMap[RealShortChannelId, PublicChannel]): DirectedGraph = {
    // initialize the map with the appropriate size to avoid resizing during the graph initialization
    val mutableMap = new mutable.HashMap[PublicKey, List[GraphEdge]](initialCapacity = channels.size + 1, mutable.HashMap.defaultLoadFactor)

    // add all the vertices and edges in one go
    channels.values.foreach { channel =>
      channel.update_1_opt.foreach(u1 => addToMap(GraphEdge(u1, channel)))
      channel.update_2_opt.foreach(u2 => addToMap(GraphEdge(u2, channel)))
    }

    def addToMap(edge: GraphEdge): Unit = {
      mutableMap.put(edge.desc.b, edge +: mutableMap.getOrElse(edge.desc.b, List.empty[GraphEdge]))
      if (!mutableMap.contains(edge.desc.a)) {
        mutableMap += edge.desc.a -> List.empty[GraphEdge]
      }
    }

    new DirectedGraph(mutableMap.toMap)
  }

  def graphEdgeToHop(graphEdge: GraphEdge): ChannelHop = ChannelHop(graphEdge.desc.shortChannelId, graphEdge.desc.a, graphEdge.desc.b, graphEdge.params)
}
