package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import org.jgrapht.graph.DirectedWeightedPseudograph
import scala.collection.JavaConversions._
import scala.collection.mutable
import fr.acinq.eclair._

object Graph {


  implicit object QueueComparator extends Ordering[NodeWithWeight] {
    override def compare(x: NodeWithWeight, y: NodeWithWeight): Int = x.weight.compareTo(y.weight)
  }

  case class NodeWithWeight(publicKey: PublicKey, weight: Long)

  def shortestPath(g: DirectedWeightedPseudograph[PublicKey, DescEdge], sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long):Seq[Hop] = {

    val distance = new mutable.HashMap[PublicKey, Long]
    val prev = new mutable.HashMap[PublicKey, PublicKey]
    val vertexQueue = new java.util.PriorityQueue[NodeWithWeight](QueueComparator)

    //initialize the queue with the vertices having max distance
    g.vertexSet().toSet[PublicKey].foreach {
      case pk if pk == sourceNode =>
        distance += pk -> 0 // starting node has distance 0
        vertexQueue.add(NodeWithWeight(pk, 0))
      case pk                     =>
        distance += pk -> Long.MaxValue
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

          val newMinimumKnownDistance = distance(current.publicKey) + edgeWeightByAmount(edge, amountMsat)

          //if this neighbor has a shorter distance than previously known
          if (newMinimumKnownDistance < distance(neighbor)) {

            //update the visiting tree
            prev.put(neighbor, current.publicKey)

            //update the queue, remove and insert
            vertexQueue.remove(NodeWithWeight(neighbor, distance(neighbor)))
            vertexQueue.add(NodeWithWeight(neighbor, newMinimumKnownDistance))

            //update the minimum known distance array
            distance.update(neighbor, newMinimumKnownDistance)
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
    val hopPath = new mutable.MutableList[Hop]
    var current = targetNode
    while(resultPath.exists(_.nextNodeId == current) ) {

      val Some(temp) = resultPath.find(_.nextNodeId == current)
      hopPath += temp
      current = temp.nodeId
    }

    //if there is a path source -> ... -> target then 'current' must be the source node at this point
    if(current != sourceNode)
      List.empty //path not found
    else
      hopPath.reverse

  }

  private def edgeWeightByAmount(edge: DescEdge, amountMsat: Long): Long = {
    nodeFee(edge.u.feeBaseMsat, edge.u.feeProportionalMillionths, amountMsat)
  }
}
