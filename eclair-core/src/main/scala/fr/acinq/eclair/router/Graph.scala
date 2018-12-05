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
}
