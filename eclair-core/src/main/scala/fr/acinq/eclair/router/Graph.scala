package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey

import scala.collection.mutable
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.wire.ChannelUpdate
import Router._
import fr.acinq.eclair.channel.Channel
import ShortChannelId._

object Graph {

  import DirectedGraph._

  case class WeightedNode(key: PublicKey, compoundWeight: CompoundWeight) {
    def weight(weightSettings: WeightRatios): Double = compoundWeight.totalWeight(weightSettings)
  }

  case class CompoundWeight(costMsat: Long, cltvDelta: Long, score: Long) {

    // TODO check for overflows
    def totalWeight(wr: WeightRatios): Double = {
      if(costMsat == Long.MaxValue)  Long.MaxValue
      else if(cltvDelta == Long.MaxValue)  Long.MaxValue
      else if(score == Long.MaxValue)  Long.MaxValue

      else costMsat * wr.costFactor + cltvDelta * wr.cltvDeltaFactor + score * wr.scoreFactor
    }

    def compare(x: CompoundWeight, wr: WeightRatios): Int = {
      totalWeight(wr).compareTo(x.totalWeight(wr))
    }
  }

  case class WeightRatios(costFactor: Double, cltvDeltaFactor: Double, scoreFactor: Double) {
    require(0 < costFactor + cltvDeltaFactor + scoreFactor && costFactor + cltvDeltaFactor + scoreFactor <= 1D, "Total weight ratio must be between 0 and 1")
  }

  /**
    * This comparator must be consistent with the "equals" behavior, thus for two weighted nodes with
    * the same weight we distinguish them by their public key. See https://docs.oracle.com/javase/8/docs/api/java/util/Comparator.html
    */
  class QueueComparator(wr: WeightRatios) extends Ordering[WeightedNode] {
    override def compare(x: WeightedNode, y: WeightedNode): Int = {
      val weightCmp = x.weight(wr).compareTo(y.weight(wr))
      if (weightCmp == 0) x.key.toString().compareTo(y.key.toString())
      else weightCmp
    }
  }

  /**
    * Finds the shortest path in the graph, uses a modified version of Dijsktra's algorithm that computes
    * the shortest path from the target to the source (this is because we want to calculate the weight of the
    * edges correctly). The graph @param g is optimized for querying the incoming edges given a vertex.
    *
    * @param g the graph on which will be performed the search
    * @param sourceNode the starting node of the path we're looking for
    * @param targetNode the destination node of the path
    * @param amountMsat the amount (in millisatoshis) we want to transmit
    * @param ignoredEdges a list of edges we do not want to consider
    * @param extraEdges a list of extra edges we want to consider but are not currently in the graph
    * @return
    */
  def shortestPath(g: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long, ignoredEdges: Set[ChannelDesc], extraEdges: Set[GraphEdge], wf: WeightRatios): Seq[Hop] = {
    dijkstraShortestPath(g, sourceNode, targetNode, amountMsat, ignoredEdges, extraEdges, wf).map(graphEdgeToHop)
  }

  def dijkstraShortestPath(g: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long, ignoredEdges: Set[ChannelDesc], extraEdges: Set[GraphEdge], wr: WeightRatios): Seq[GraphEdge] = {

    // optionally add the extra edges to the graph
    val graphVerticesWithExtra = extraEdges.nonEmpty match {
      case true => g.vertexSet() ++ extraEdges.map(_.desc.a) ++ extraEdges.map(_.desc.b)
      case false => g.vertexSet()
    }

    //  the graph does not contain source/destination nodes
    if (!graphVerticesWithExtra.contains(sourceNode)) return Seq.empty
    if (!graphVerticesWithExtra.contains(targetNode)) return Seq.empty

    val maxMapSize = graphVerticesWithExtra.size + 1

    // this is not the actual optimal size for the maps, because we only put in there all the vertices in the worst case scenario.
    val weight = new java.util.HashMap[PublicKey, CompoundWeight](maxMapSize)
    val prev = new java.util.HashMap[PublicKey, GraphEdge](maxMapSize)
    val vertexQueue = new org.jheaps.tree.SimpleFibonacciHeap[WeightedNode, Short](new QueueComparator(wr))
    val pathLength = new java.util.HashMap[PublicKey, Int](maxMapSize)

    // initialize the queue and weight array with the base cost (amount to be routed)
    val startingWeight = CompoundWeight(amountMsat, 0, 0)
    weight.put(targetNode, startingWeight)
    vertexQueue.insert(WeightedNode(targetNode, startingWeight))
    pathLength.put(targetNode, 0) // the source node has distance 0

    var targetFound = false

    while (!vertexQueue.isEmpty && !targetFound) {

      // node with the smallest distance from the source
      val current = vertexQueue.deleteMin().getKey // O(log(n))

      if (current.key != sourceNode) {

        // build the neighbors with optional extra edges
        val currentNeighbors = extraEdges.isEmpty match {
          case true => g.getIncomingEdgesOf(current.key)
          case false => g.getIncomingEdgesOf(current.key) ++ extraEdges.filter(_.desc.b == current.key)
        }

        // for each neighbor
        currentNeighbors.foreach { edge =>

          val neighbor = edge.desc.a

          // calculate the length of the partial path given the new edge (current -> neighbor)
          val neighborPathLength = pathLength.get(current.key) + 1

          // note: 'cost' contains the smallest known cumulative cost (amount + fees) necessary to reach 'current' so far
          // note: there is always an entry for the current in the 'cost' map
          val newMinimumCompoundWeight = edgeWeightCompound(edge, weight.get(current.key), neighbor == sourceNode)

          // test for ignored edges
          if (edge.update.htlcMaximumMsat.forall(newMinimumCompoundWeight.costMsat <= _) &&
            newMinimumCompoundWeight.costMsat >= edge.update.htlcMinimumMsat &&
            neighborPathLength <= ROUTE_MAX_LENGTH && // ignore this edge if it would make the path too long
            !ignoredEdges.contains(edge.desc)
          ) {

            // we call containsKey first because "getOrDefault" is not available in JDK7
            val neighborCost = weight.containsKey(neighbor) match {
              case false => CompoundWeight(Long.MaxValue, Long.MaxValue, Long.MaxValue)
              case true => weight.get(neighbor)
            }

            // if this neighbor has a shorter distance than previously known
            if (newMinimumCompoundWeight.totalWeight(wr) < neighborCost.totalWeight(wr)) {

              // update the total length of this partial path
              pathLength.put(neighbor, neighborPathLength)

              // update the visiting tree
              prev.put(neighbor, edge)

              // update the queue
              vertexQueue.insert(WeightedNode(neighbor, newMinimumCompoundWeight)) // O(1)

              // update the minimum known distance array
              weight.put(neighbor, newMinimumCompoundWeight)
            }
          }
        }
      } else { // we popped the target node from the queue, no need to search any further
        targetFound = true
      }
    }

    targetFound match {
      case false => Seq.empty[GraphEdge]
      case true =>
        // we traverse the list of "previous" backward building the final list of edges that make the shortest path
        val edgePath = new mutable.ArrayBuffer[GraphEdge](ROUTE_MAX_LENGTH)
        var current = prev.get(sourceNode)

        while (current != null) {

          edgePath += current
          current = prev.get(current.desc.b)
        }

        edgePath
    }
  }

  val MAX_FUNDING_MSAT: Long = Channel.MAX_FUNDING_SATOSHIS * 1000L

  private def edgeWeightCompound(edge: GraphEdge, compoundCostSoFar: CompoundWeight, isNeighborTarget: Boolean): CompoundWeight = {

    // Every edge is weighted down by funding block height, but older blocks add less weight
    val blockFactor = coordinates(edge.desc.shortChannelId).blockHeight

    // Every edge is weighted down by channel capacity, but larger channels add less weight
    val capFactor = edge.update.htlcMaximumMsat.map(MAX_FUNDING_MSAT - _).getOrElse(MAX_FUNDING_MSAT)

    // the 'score' is an aggregate of all factors beside the feeCost and CLTV
    val scoreFactor = capFactor + blockFactor

    val costFactor = edgeCost(edge, compoundCostSoFar.costMsat, isNeighborTarget)

    val cltvFactor = compoundCostSoFar.cltvDelta + edge.update.cltvExpiryDelta

    CompoundWeight(costFactor, cltvFactor, scoreFactor)
  }

  private def normalize(value: Long, min: Long, max: Long) = value - min / max - min

  /**
    *
    * @param edge the edge for which we want to compute the weight
    * @param amountWithFees the value that this edge will have to carry along
    * @param isNeighborTarget true if the receiving vertex of this edge is the target node (source in a reversed graph), which has cost 0
    * @return the new amount updated with the necessary fees for this edge
    */
  private def edgeCost(edge: GraphEdge, amountWithFees: Long, isNeighborTarget: Boolean): Long = isNeighborTarget match {
    case false => amountWithFees + nodeFee(edge.update.feeBaseMsat, edge.update.feeProportionalMillionths, amountWithFees)
    case true => amountWithFees
  }

  /**
    * A graph data structure that uses the adjacency lists, stores the incoming edges of the neighbors
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
        * @return
        */
      def removeEdge(desc: ChannelDesc): DirectedGraph = {
        containsEdge(desc) match {
          case true => DirectedGraph(vertices.updated(desc.b, vertices(desc.b).filterNot(_.desc == desc)))
          case false => this
        }
      }

      def removeEdges(descList: Seq[ChannelDesc]): DirectedGraph = {
        descList.foldLeft(this)((acc, edge) => acc.removeEdge(edge))
      }

      /**
        * @param edge
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
        * @return all the edges going from keyA --> keyB (there might be more than one if it refers to different shortChannelId)
        */
      def getEdgesBetween(keyA: PublicKey, keyB: PublicKey): Seq[GraphEdge] = {
        vertices.get(keyB) match {
          case None => Seq.empty
          case Some(adj) => adj.filter(e => e.desc.a == keyA)
        }
      }

      /**
        * The the incoming edges for vertex @param keyB
        * @param keyB
        * @return
        */
      def getIncomingEdgesOf(keyB: PublicKey): Seq[GraphEdge] = {
        vertices.getOrElse(keyB, List.empty)
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
        * Note this operation will traverse all edges in the graph (expensive)
        * @param key
        * @return a list of the outgoing edges of vertex @param key, if the edge doesn't exists an empty list is returned
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
        * @param key
        * @return true if this graph contain a vertex with this key, false otherwise
        */
      def containsVertex(key: PublicKey): Boolean = vertices.contains(key)

      /**
        * @param desc
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

      // convenience constructors
      def apply(): DirectedGraph = new DirectedGraph(Map())

      def apply(key: PublicKey): DirectedGraph = new DirectedGraph(Map(key -> List.empty))

      def apply(edge: GraphEdge): DirectedGraph = new DirectedGraph(Map()).addEdge(edge.desc, edge.update)

      def apply(edges: Seq[GraphEdge]): DirectedGraph = {
        makeGraph(edges.map(e => e.desc -> e.update).toMap)
      }

      // optimized constructor
      def makeGraph(descAndUpdates: Map[ChannelDesc, ChannelUpdate]): DirectedGraph = {

        // initialize the map with the appropriate size to avoid resizing during the graph initialization
        val mutableMap = new {} with mutable.HashMap[PublicKey, List[GraphEdge]] {
          override def initialSize: Int = descAndUpdates.size + 1
        }

        // add all the vertices and edges in one go
        descAndUpdates.foreach { case (desc, update) =>
          // create or update vertex (desc.b) and update its neighbor
          mutableMap.put(desc.b, GraphEdge(desc, update) +: mutableMap.getOrElse(desc.b, List.empty[GraphEdge]))
          mutableMap.get(desc.a) match {
            case None => mutableMap += desc.a -> List.empty[GraphEdge]
            case _ =>
          }
        }

        new DirectedGraph(mutableMap.toMap)
      }

      def graphEdgeToHop(graphEdge: GraphEdge): Hop = Hop(graphEdge.desc.a, graphEdge.desc.b, graphEdge.update)
    }

  }
}
