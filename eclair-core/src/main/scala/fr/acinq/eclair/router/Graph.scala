package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import org.jgrapht.graph.DirectedWeightedPseudograph
import scala.collection.JavaConversions._
import scala.collection.mutable

object Graph {


  implicit object QueueComparator extends Ordering[NodeWithWeight] {
    override def compare(x: NodeWithWeight, y: NodeWithWeight): Int = x.weight.compareTo(y.weight)
  }

  case class NodeWithWeight(publicKey: PublicKey, weight: Long)

  def shortestPath(g: DirectedWeightedPseudograph[PublicKey, DescEdge], sourceNode: PublicKey, targetNode: PublicKey, amount: Long):Seq[Hop] = {

    val distance = new mutable.HashMap[PublicKey, Long]
    val foundEdges = new mutable.HashMap[PublicKey, DescEdge]
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

    while(vertexQueue.nonEmpty){

      //(next) node with the smallest distance from the source
      val current = vertexQueue.poll()

      //for each neighbor
      g.edgesOf(current.publicKey).toSet[DescEdge].foreach { edge =>

        val neighbor = edge.desc.b

        //if this neighbor has a shorter distance than previously known
        if(distance(current.publicKey) + edgeWeightByAmount(edge, amount) < distance(neighbor)) {

          val newMinimumKnownDistance = distance(current.publicKey) + edgeWeightByAmount(edge, amount)

          //update the visiting tree
          foundEdges.update(current.publicKey, edge)

          //update the queue, remove and insert
          vertexQueue.remove(NodeWithWeight(neighbor, distance(neighbor)))
          vertexQueue.add(NodeWithWeight(neighbor, newMinimumKnownDistance))

          //update the minimum known distance array
          distance.update(neighbor, newMinimumKnownDistance)

        }

      }

    }


    //build the result backward path from the visiting map
    val resultPath = foundEdges.values.map(edge => Hop(edge.desc.a, edge.desc.b, edge.u)).toSeq
    val hopPath = new mutable.MutableList[Hop]

    var current = targetNode

    while(resultPath.exists(_.nextNodeId == current)) {

      val Some(temp) = resultPath.find(_.nextNodeId == current)
      hopPath += temp
      current = temp.nodeId

    }

    hopPath.reverse

  }

  def edgeWeightByAmount(edge: DescEdge, amount: Long): Long = {
    edge.u.feeBaseMsat + (edge.u.feeProportionalMillionths * amount)
  }



}
