package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import org.jgrapht.graph.DirectedWeightedPseudograph
import scala.collection.JavaConversions._
import scala.collection.mutable
import fr.acinq.eclair._
import fr.acinq.eclair.wire.ChannelUpdate

object Graph {


  implicit object QueueComparator extends Ordering[NodeWithWeight] {
    override def compare(x: NodeWithWeight, y: NodeWithWeight): Int = x.weight.compareTo(y.weight)
  }

  case class NodeWithWeight(publicKey: PublicKey, weight: Long)

  def shortestPath(g: DirectedWeightedPseudograph[PublicKey, DescEdge], sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long): Seq[Hop] = {
    shortestPathWithCostInfo(g, sourceNode, targetNode, amountMsat)._1
  }

  //TBD the cost for the neighbors of the sourceNode is always 0
  def shortestPathWithCostInfo(g: DirectedWeightedPseudograph[PublicKey, DescEdge], sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long): (Seq[Hop], Long) = {

    val cost = new mutable.HashMap[PublicKey, Long]
    val prev = new mutable.HashMap[PublicKey, PublicKey]
    val vertexQueue = new java.util.PriorityQueue[NodeWithWeight](QueueComparator)

    //initialize the queue with the vertices having max distance
    g.vertexSet().toSet[PublicKey].foreach {
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
        g.edgesOf(current.publicKey).toSet[DescEdge].foreach { edge =>

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
    val resultPath = prev.map { case (k,v) =>
      g.containsEdge(v, k) match {
        case true   => Some(Hop(v, k, g.getEdge(v, k).u))
        case false  => None
      }
    }.filter(_.isDefined).map(_.get)

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

  private def edgeWeightByAmount(edge: DescEdge, amountMsat: Long): Long = {
    nodeFee(edge.u.feeBaseMsat, edge.u.feeProportionalMillionths, amountMsat)
  }


  /**
    * A graph data structure that uses the adjacency lists
    */
  object UndirectedWeightedGraph {

    case class GraphEdge(desc: ChannelDesc, u: ChannelUpdate)

    case class UndirectedWeightedGraph(vertices: Map[PublicKey, Seq[GraphEdge]]) {

      /**
        * Adds and edge to the graph, if one of the two vertices is not found, it will be created
        *
        * @param d the channel desc
        * @param u the channel update
        * @param weight the weight of this edge
        * @return a new graph containing this edge
        */
      def addEdge(d: ChannelDesc, u: ChannelUpdate): UndirectedWeightedGraph = {
        val vertexIn = d.b
        val vertexOut = d.a

        val edge = GraphEdge(d, u)

        val addedA = vertices.contains(vertexIn) match {
          case true => vertices.updated(vertexIn, vertices(vertexIn) :+ edge)
          //if the key (vertex) wasn't found we add it first and the operate on the new map
          case false =>
            val newVertices = addVertex(vertexIn)
            newVertices.vertices.updated(vertexIn, newVertices.vertices(vertexIn) :+ edge)
        }

        val addedB = addedA.contains(vertexOut) match {
          case true => addedA.updated(vertexOut, addedA(vertexOut) :+ edge)
          case false =>
            val newVertices = UndirectedWeightedGraph(addedA).addVertex(vertexOut)
            newVertices.vertices.updated(vertexOut, newVertices.vertices(vertexOut) :+ edge)
        }

        UndirectedWeightedGraph(addedB)
      }

      def removeEdge(d: ChannelDesc): UndirectedWeightedGraph = {
        val vertexIn = d.b
        val vertexOut = d.a

        val removedA = vertices.updated(vertexIn, vertices(vertexIn).filterNot(_.desc == d))
        val removedB = removedA.updated(vertexOut, removedA(vertexOut).filterNot(_.desc == d))

        UndirectedWeightedGraph(removedB)
      }

      def addVertex(key: PublicKey): UndirectedWeightedGraph = UndirectedWeightedGraph(vertices + (key -> Seq.empty))

      def edgesOf(key: PublicKey): Seq[GraphEdge] = vertices.getOrElse(key, Seq.empty)

      def vertexSet() = vertices.keySet

      def edgeSet() = vertices.values.flatten.toSet

      def containsVertex(key: PublicKey) = vertices.contains(key)

      def existEdge(vertexA: PublicKey, vertexB: PublicKey): Boolean = {
        vertices.exists { case (key, adj) => key == vertexA && adj.exists(_.desc.a == vertexB) } &&
        vertices.exists { case (key, adj) => key == vertexB && adj.exists(_.desc.b == vertexA) }
      }

    }

    //convenience constructor
    def apply(key: PublicKey): UndirectedWeightedGraph = new UndirectedWeightedGraph( Map((key -> Seq.empty)) )
    def apply(edge: GraphEdge): UndirectedWeightedGraph = new UndirectedWeightedGraph( Map() ).addEdge(edge.desc, edge.u)

  }

}
