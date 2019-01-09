package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey

import scala.collection.mutable
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.wire.ChannelUpdate

object Graph {

  import DirectedGraph._

  case class WeightedNode(key: PublicKey, weight: Long)

  /**
    * This comparator must be consistent with the "equals" behavior, thus for two weighted nodes with
    * the same weight we distinguish them by their public key. See https://docs.oracle.com/javase/8/docs/api/java/util/Comparator.html
    */
  object QueueComparator extends Ordering[WeightedNode] {
    override def compare(x: WeightedNode, y: WeightedNode): Int = {
      val weightCmp = x.weight.compareTo(y.weight)
      if (weightCmp == 0) x.key.toString().compareTo(y.key.toString())
      else weightCmp
    }
  }

  /**
    * Finds the shortest path in the graph, Dijsktra's algorithm
    *
    * @param g the graph on which will be performed the search
    * @param sourceNode the starting node of the path we're looking for
    * @param targetNode the destination node of the path
    * @param amountMsat the amount (in millisatoshis) we want to transmit
    * @param ignoredEdges a list of edges we do not want to consider
    * @param extraEdges a list of extra edges we want to consider but are not currently in the graph
    * @return
    */
  def shortestPath(g: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long, ignoredEdges: Set[ChannelDesc], extraEdges: Set[GraphEdge]): Seq[Hop] = {
    dijkstraShortestPath(g, sourceNode, targetNode, amountMsat, ignoredEdges, extraEdges).map(graphEdgeToHop)
  }

  def dijkstraShortestPath(g: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long, ignoredEdges: Set[ChannelDesc], extraEdges: Set[GraphEdge]): Seq[GraphEdge] = {

    // optionally add the extra edges to the graph
    val graphVerticesWithExtra = extraEdges.nonEmpty match {
      case true => g.vertexSet() ++ extraEdges.map(_.desc.a) ++ extraEdges.map(_.desc.b)
      case false => g.vertexSet()
    }

    //  the graph does not contain source/destination nodes
    if (!graphVerticesWithExtra.contains(sourceNode)) return Seq.empty
    if (!graphVerticesWithExtra.contains(targetNode)) return Seq.empty

    val maxMapSize = graphVerticesWithExtra.size + 1

    //  this is not the actual optimal size for the maps, because we only put in there all the vertices in the worst case scenario.
    val cost = new java.util.HashMap[PublicKey, Long](maxMapSize)
    val prev = new java.util.HashMap[PublicKey, GraphEdge](maxMapSize)
    val vertexQueue = new org.jheaps.tree.SimpleFibonacciHeap[WeightedNode, Short](QueueComparator)

    //  initialize the queue and cost array
    cost.put(sourceNode, amountMsat)
    vertexQueue.insert(WeightedNode(sourceNode, amountMsat))

    var targetFound = false

    while (!vertexQueue.isEmpty && !targetFound) {

      // node with the smallest distance from the source
      val current = vertexQueue.deleteMin().getKey // O(log(n))

      if (current.key != targetNode) {

        // build the neighbors with optional extra edges
        val currentNeighbors = extraEdges.isEmpty match {
          case true => g.edgesOf(current.key)
          case false => g.edgesOf(current.key) ++ extraEdges.filter(_.desc.a == current.key)
        }

        // for each neighbor
        currentNeighbors.foreach { edge =>

          val neighbor = edge.desc.b

          // note: 'cost' contains the smallest known cumulative cost (amount + fees) necessary to reach 'current' so far
          // note: there is always an entry for the current in the 'cost' map
          val newMinimumKnownCost = edgeWeight(edge, cost.get(current.key), neighbor == targetNode)

          // test for ignored edges
          if (!(edge.update.htlcMaximumMsat.exists(_ < newMinimumKnownCost) ||
            newMinimumKnownCost < edge.update.htlcMinimumMsat ||
            ignoredEdges.contains(edge.desc))
          ) {

            // we call containsKey first because "getOrDefault" is not available in JDK7
            val neighborCost = cost.containsKey(neighbor) match {
              case false => Long.MaxValue
              case true => cost.get(neighbor)
            }

            // if this neighbor has a shorter distance than previously known
            if (newMinimumKnownCost < neighborCost) {

              // update the visiting tree
              prev.put(neighbor, edge)

              // update the queue
              vertexQueue.insert(WeightedNode(neighbor, newMinimumKnownCost)) // O(1)

              // update the minimum known distance array
              cost.put(neighbor, newMinimumKnownCost)
            }
          }
        }
      } else { // we popped the target node from the queue, no need to search any further
        targetFound = true
      }
    }

    targetFound match {
      case false => Seq.empty[GraphEdge]
      case true => {
        // we traverse the list of "previous" backward building the final list of edges that make the shortest path
        val edgePath = new mutable.ArrayBuffer[GraphEdge](21) // max path length is 20! https://github.com/lightningnetwork/lightning-rfc/blob/master/04-onion-routing.md#clarifications
        var current = prev.get(targetNode)

        while (current != null) {

          edgePath += current
          current = prev.get(current.desc.a)
        }

        edgePath
      }
    }
  }

  /**
    *
    * @param edge the edge for which we want to compute the weight
    * @param amountWithFees the value that this edge will have to carry along
    * @param isNeighborTarget true if the receiving vertex of this edge is the target node (source in a reversed graph), which has cost 0
    * @return the new amount updated with the necessary fees for this edge
    */
  private def edgeWeight(edge: GraphEdge, amountWithFees: Long, isNeighborTarget: Boolean): Long = isNeighborTarget match {
    case false => amountWithFees + nodeFee(edge.update.feeBaseMsat, edge.update.feeProportionalMillionths, amountWithFees)
    case true => amountWithFees
  }

  /**
    * A graph data structure that uses the adjacency lists, stores the edges in reversed order
    */
  object GraphStructure {

    /**
      * Representation of an edge of the graph
      *
      * @param desc channel description
      * @param update channel info
      */
    case class GraphEdge(desc: ChannelDesc, update: ChannelUpdate)

    case class DirectedGraph(private val vertices: Map[PublicKey, List[GraphEdge]]) {

      def addEdge(d: ChannelDesc, u: ChannelUpdate): DirectedGraph = addEdge(GraphEdge(d, u))

      def addEdges(edges: Seq[(ChannelDesc, ChannelUpdate)]): DirectedGraph = {
        edges.foldLeft(this)((acc, edge) => acc.addEdge(edge._1, edge._2))
      }

      /**
        * Adds and edge to the graph, if one of the two vertices is not found, it will be created.
        * Edges are added as "reversed" swapping the starting vertex with the final.
        *
        * @param edge the edge that is going to be added to the graph
        * @return a new graph containing this edge
        */
      def addEdge(edge: GraphEdge): DirectedGraph = {

        val finalEdge = edge.copy(desc = edge.desc.reverse())

        val vertexIn = finalEdge.desc.a
        val vertexOut = finalEdge.desc.b

        // the graph is allowed to have multiple edges between the same vertices but only one per channel
        if (containsEdge(edge.desc)) {
          removeEdge(edge.desc).addEdge(edge) // the recursive call will have the original params
        } else {
          val withVertices = addVertex(vertexIn).addVertex(vertexOut)
          DirectedGraph(withVertices.vertices.updated(vertexIn, finalEdge +: withVertices.vertices(vertexIn)))
        }
      }

      /**
        * Removes the edge corresponding to the given pair channel-desc/channel-update,
        * NB: this operation does NOT remove any vertex
        *
        * Edges are removed as "reversed" swapping the starting vertex with the final.
        *
        * @param desc the channel description associated to the edge that will be removed
        * @return
        */
      def removeEdge(desc: ChannelDesc): DirectedGraph = {
        val someDesc = desc.reverse()

        containsEdge(desc) match {
          case true => DirectedGraph(vertices.updated(someDesc.a, vertices(someDesc.a).filterNot(_.desc == someDesc)))
          case false => this
        }
      }

      def removeEdges(descList: Seq[ChannelDesc]): DirectedGraph = {
        descList.foldLeft(this)((acc, edge) => acc.removeEdge(edge))
      }

      /**
        * Edges are NOT reversed when performing this operation
        *
        * @param edge
        * @return For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
        */
      def getEdge(edge: GraphEdge): Option[GraphEdge] = getEdge(edge.desc)

      def getEdge(desc: ChannelDesc): Option[GraphEdge] = {
        val someDesc = desc.reverse()
        vertices.get(someDesc.a).flatMap { adj =>
          adj.find(e => e.desc.shortChannelId == someDesc.shortChannelId && e.desc.b == someDesc.b)
        }
      }

      /**
        * @param keyA the key associated with the starting vertex
        * @param keyB the key associated with the ending vertex
        * @return all the edges going from keyA --> keyB (there might be more than one if it refers to different shortChannelId)
        */
      def getEdgesBetween(keyA: PublicKey, keyB: PublicKey): Seq[GraphEdge] = {
        vertices.get(keyA) match {
          case None => Seq.empty
          case Some(adj) => adj.filter(e => e.desc.b == keyB)
        }
      }

      def getIncomingEdgesOf(keyA: PublicKey): Seq[GraphEdge] = {
        edgeSet().filter(_.desc.b == keyA).toSeq
      }

      /**
        * Removes a vertex and all it's associated edges (both incoming and outgoing)
        *
        * @param key
        * @return
        */
      def removeVertex(key: PublicKey): DirectedGraph = {
        DirectedGraph(removeEdges(getIncomingEdgesOf(key).map(_.desc)).vertices - key)
      }

      /**
        * Adds a new vertex to the graph, starting with no edges
        *
        * @param key
        * @return
        */
      def addVertex(key: PublicKey): DirectedGraph = {
        vertices.get(key) match {
          case None => DirectedGraph(vertices + (key -> List.empty))
          case _ => this
        }
      }

      /**
        * @param key
        * @return a list of the outgoing edges of vertex @param key, if the edge doesn't exists an empty list is returned
        */
      def edgesOf(key: PublicKey): Seq[GraphEdge] = vertices.getOrElse(key, List.empty)

      /**
        * @return the set of all the vertices in this graph
        */
      def vertexSet(): Set[PublicKey] = vertices.keySet

      /**
        * @return an iterator of all the edges in this graph
        */
      def edgeSet(): Iterable[GraphEdge] = vertices.values.flatten

      /**
        * @param key
        * @return true if this graph contain a vertex with this key, false otherwise
        */
      def containsVertex(key: PublicKey): Boolean = vertices.contains(key)

      /**
        * Edges are checked in as "reversed" swapping the starting vertex with the final.
        *
        * @param desc
        * @return true if this edge desc is in the graph. For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
        */
      def containsEdge(desc: ChannelDesc): Boolean = {
        val someDesc = desc.reverse()

        vertices.get(someDesc.a) match {
          case None => false
          case Some(adj) => adj.exists(neighbor => neighbor.desc.shortChannelId == someDesc.shortChannelId && neighbor.desc.b == someDesc.b)
        }
      }

      def prettyPrint(): String = {
        vertices.foldLeft("") { case (acc, (vertex, adj)) =>
          acc + s"[${vertex.toString().take(5)}]: ${adj.map("-> " + _.desc.b.toString().take(5))} \n"
        }
      }
    }

    object DirectedGraph {

      // convenience constructors
      def apply(): DirectedGraph = new DirectedGraph(Map())

      def apply(key: PublicKey): DirectedGraph = new DirectedGraph(Map(key -> List.empty))

      def apply(edge: GraphEdge): DirectedGraph = new DirectedGraph(Map()).addEdge(edge.desc, edge.update)

      def apply(edges: Seq[GraphEdge]): DirectedGraph = {
        makeGraph(edges.map(e => e.desc -> e.update).toMap)
      }

      // optimized constructor, builds the graph with reversed edges
      def makeGraph(descAndUpdates: Map[ChannelDesc, ChannelUpdate]): DirectedGraph = {

        // initialize the map with the appropriate size to avoid resizing during the graph initialization
        val mutableMap = new {} with mutable.HashMap[PublicKey, List[GraphEdge]] {
          override def initialSize: Int = descAndUpdates.size + 1
        }

        // add all the vertices and edges in one go
        descAndUpdates.foreach { case (desc, update) =>
          val someDesc = desc.reverse()

          // create or update vertex (desc.a) and update its neighbor
          mutableMap.put(someDesc.a, GraphEdge(someDesc, update) +: mutableMap.getOrElse(someDesc.a, List.empty[GraphEdge]))
          mutableMap.get(someDesc.b) match {
            case None => mutableMap += someDesc.b -> List.empty[GraphEdge]
            case _ =>
          }
        }

        new DirectedGraph(mutableMap.toMap)
      }

      def graphEdgeToHop(graphEdge: GraphEdge): Hop = Hop(graphEdge.desc.b, graphEdge.desc.a, graphEdge.update)
    }

  }
}
