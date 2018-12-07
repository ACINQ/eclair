package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import scala.collection.JavaConversions._
import scala.collection.mutable
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.{GraphEdge, DirectedGraph}
import fr.acinq.eclair.wire.ChannelUpdate

object Graph {

  implicit object QueueComparator extends Ordering[NodeWithWeight] {
    override def compare(x: NodeWithWeight, y: NodeWithWeight): Int = x.weight.compareTo(y.weight)
  }

  case class NodeWithWeight(publicKey: PublicKey, weight: Long)

  def shortestPath(g: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long): Seq[Hop] = {
    shortestPathWithCostInfo(g, sourceNode, targetNode, amountMsat)._1
  }

  //TBD the cost for the neighbors of the sourceNode is always 0
  def shortestPathWithCostInfo(g: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long): (Seq[Hop], Long) = {

    val cost = new mutable.HashMap[PublicKey, Long]
    val prev = new mutable.HashMap[PublicKey, PublicKey]
    val vertexQueue = new java.util.PriorityQueue[NodeWithWeight](QueueComparator)

    //initialize the queue with the vertices having max distance
    g.vertexSet().foreach {
      case pk if pk == sourceNode =>
        cost += pk -> 0 // starting node has distance 0
        vertexQueue.add(NodeWithWeight(pk, 0))
      case pk                     =>
        cost += pk -> Long.MaxValue
        vertexQueue.add(NodeWithWeight(pk, Long.MaxValue))
    }

    var targetFound = false

    while(vertexQueue.nonEmpty && !targetFound){

      //(next) node with the smallest distance from the source
      val current = vertexQueue.poll()

      if(current.publicKey != targetNode) {

        //for each neighbor
        g.edgesOf(current.publicKey).toSet[GraphEdge].foreach { edge =>

          val neighbor = edge.desc.b

          val newMinimumKnownCost = cost(current.publicKey) + edgeWeightByAmount(edge, amountMsat)

          //if this neighbor has a shorter distance than previously known
          if (newMinimumKnownCost < cost(neighbor)) {

            //update the visiting tree
            prev.put(neighbor, current.publicKey)

            //update the queue, remove and insert
            vertexQueue.remove(NodeWithWeight(neighbor, cost(neighbor)))
            vertexQueue.add(NodeWithWeight(neighbor, newMinimumKnownCost))

            //update the minimum known distance array
            cost.update(neighbor, newMinimumKnownCost)
          }
        }
      } else { //we popped the target node from the queue, no need to search any further
        targetFound = true
      }
    }

    //transform the 'prev' map into a list of hops
    val resultPath = prev.flatMap { case (k,v) =>
      g.getEdgesBetween(v, k).map(edge => Hop(v, k, edge.update))
    }

    //build the resulting path traversing the hop list backward from the target
    val hopPath = new mutable.MutableList[(Hop, Long)]
    var current = targetNode
    while(resultPath.exists(_.nextNodeId == current) ) {

      val Some(temp) = resultPath.find(_.nextNodeId == current)
      hopPath += ((temp, cost(current)))
      current = temp.nodeId
    }

    //if there is a path source -> ... -> target then 'current' must be the source node at this point
    if(current != sourceNode)
      (List.empty, 0) //path not found
    else
      (hopPath.map(_._1).reverse, hopPath.foldLeft(0L)( (acc, hopAndCost) => acc + hopAndCost._2 ))

  }

  private def edgeWeightByAmount(edge: GraphEdge, amountMsat: Long): Long = {
    nodeFee(edge.update.feeBaseMsat, edge.update.feeProportionalMillionths, amountMsat)
  }


  /**
    * A graph data structure that uses the adjacency lists
    */
  object GraphStructure {

    /**
      * Representation of an edge of the graph
      * @param desc
      * @param update
      */
    case class GraphEdge(desc: ChannelDesc, update: ChannelUpdate)

    case class DirectedGraph(private val vertices: Map[PublicKey, Seq[GraphEdge]]) {

      def addEdge(d: ChannelDesc, u: ChannelUpdate): DirectedGraph = addEdge(GraphEdge(d, u))

      def addEdges( edges: Seq[(ChannelDesc, ChannelUpdate)]): DirectedGraph = {
        edges.foldLeft(this)( (acc, edge) => acc.addEdge(edge._1, edge._2))
      }

      /**
        * Adds and edge to the graph, if one of the two vertices is not found, it will be created
        *
        * @param d the channel desc
        * @param u the channel update
        * @param weight the weight of this edge
        * @return a new graph containing this edge
        */
      def addEdge(edge: GraphEdge): DirectedGraph = {
        val vertexIn = edge.desc.a
        val vertexOut = edge.desc.b

        //the graph is allowed to have multiple edges between the same vertices but only one per channel
        if(containsEdge(edge.desc)) {
          return removeEdge(edge.desc).addEdge(edge)
        }

        //if vertices does not contain vertexIn/vertexOut, we create it first
        (vertices.contains(vertexIn), vertices.contains(vertexOut)) match {
          case (false, false) =>  //none of the vertices of this edge exists, create them and add the edge
            addVertex(vertexIn).addVertex(vertexOut).addEdge(edge)
          case (false, true) =>
            addVertex(vertexIn).addEdge(edge)
          case (true, false) =>
            addVertex(vertexOut).addEdge(edge)
          case (true, true) =>
            DirectedGraph(vertices.updated(vertexIn, vertices(vertexIn) :+ edge))
        }
      }

      /**
        * Removes the edge corresponding to the given pair channel-desc/channel-update
        * @param d
        * @return
        */
      def removeEdge(desc: ChannelDesc): DirectedGraph = {
        containsEdge(desc) match {
          case true => DirectedGraph(vertices.updated(desc.a, vertices(desc.a).filterNot { neighbor =>
            neighbor.desc == desc && neighbor.desc.shortChannelId == desc.shortChannelId
          }))
          case false => this
        }
      }

      def removeEdge(edge: GraphEdge): DirectedGraph = removeEdge(edge.desc)

      def removeEdgesByGraphEdgesList( edgeList: Seq[GraphEdge] ): DirectedGraph = removeEdges(edgeList.map(_.desc))

      def removeEdges(descList: Seq[ChannelDesc]): DirectedGraph = {
        descList.foldLeft(this)( (acc, edge ) => acc.removeEdge(edge) )
      }

      /**
        * @param edge
        * @return For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
        */
      def getEdge(edge: GraphEdge): Option[GraphEdge] = getEdge(edge.desc)

      def getEdge(desc: ChannelDesc): Option[GraphEdge] = vertices.get(desc.a).flatMap { adj =>
        adj.find(e => e.desc.b == desc.b && e.desc.shortChannelId == desc.shortChannelId)
      }

      /**
        * @param keyA
        * @param keyB
        * @return all the edges going from keyA --> keyB (there might be more than one if it refers to different shortChannelId)
        */
      def getEdgesBetween(keyA: PublicKey, keyB: PublicKey): Seq[GraphEdge] = {
        vertices.get(keyA) match {
          case None => Seq.empty
          case Some(adj) => adj.filter(e => e.desc.b == keyB)
        }
      }

      /**
        * Adds a new vertex to the graph, starting with no edges
        * @param key
        * @return
        */
      def addVertex(key: PublicKey): DirectedGraph = DirectedGraph(vertices + (key -> Seq.empty))

      /**
        * @param key
        * @return a list of the outgoing edges of vertex @param key, if the edge doesn't exists an empty list is returned
        */
      def edgesOf(key: PublicKey): Seq[GraphEdge] = vertices.getOrElse(key, Seq.empty)

      /**
        * @return the set of all the vertices in this graph
        */
      def vertexSet(): Set[PublicKey] = vertices.keySet

      /**
        * @return the set of all the vertices in this graph
        */
      def edgeSet(): Set[GraphEdge] = vertices.values.flatten.toSet

      /**
        * @param key
        * @return true if this graph contain a vertex with this key, false otherwise
        */
      def containsVertex(key: PublicKey): Boolean = vertices.contains(key)

      /**
        * @param edge
        * @return true if this edge is in the graph. For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
        */
      def containsEdge(edge: GraphEdge): Boolean = containsEdge(edge.desc)

      def containsEdge(desc: ChannelDesc): Boolean = vertices.exists { case (key, adj) =>
         key == desc.a && adj.exists(neighbor => neighbor.desc.b == desc.b && neighbor.desc.shortChannelId == desc.shortChannelId)
      }

      /**
        * Checks for the existence of at least one edge between the given two vertices
        * @param vertexA
        * @param vertexB
        * @return
        */
      def containsEdge(vertexA: PublicKey, vertexB: PublicKey): Boolean = {
        vertices.exists { case (key, adj) => key == vertexA && adj.exists(_.desc.b == vertexB) }
      }

      /**
        * @param predicate
        * @return a subset of this graph with only edges satisfying the predicate
        */
      def filterBy( predicate: GraphEdge => Boolean ): DirectedGraph = {
        removeEdgesByGraphEdgesList(edgeSet().filter(predicate).toSeq)
      }
    }

    object DirectedGraph {

      //convenience constructors
      def apply(): DirectedGraph = new DirectedGraph( Map( ))
      def apply(key: PublicKey): DirectedGraph = new DirectedGraph( Map((key -> Seq.empty)) )
      def apply(edge: GraphEdge): DirectedGraph = new DirectedGraph( Map() ).addEdge(edge.desc, edge.update)
      def apply(edges: Seq[GraphEdge]): DirectedGraph = {
        edges.foldLeft(new DirectedGraph( Map() ))((acc, edge) => acc.addEdge(edge.desc, edge.update))
      }
    }
  }
}
