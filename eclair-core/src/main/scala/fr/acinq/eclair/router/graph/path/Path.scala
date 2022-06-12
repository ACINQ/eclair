package fr.acinq.eclair.router.graph.path

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.eclair.router.graph.structure.GraphEdge
import fr.acinq.eclair.{BlockHeight, CltvExpiryDelta, MilliSatoshi}
import fr.acinq.eclair._

import scala.annotation.tailrec

object Path {

  case class WeightedNode(key: PublicKey, weight: RichWeight)

  case class WeightedPath(path: Seq[GraphEdge], weight: RichWeight)

  /**
   * This comparator must be consistent with the "equals" behavior, thus for two weighted nodes with
   * the same weight we distinguish them by their public key.
   * See https://docs.oracle.com/javase/8/docs/api/java/util/Comparator.html
   */
  object NodeComparator extends Ordering[WeightedNode] {
    override def compare(x: WeightedNode, y: WeightedNode): Int = {
      val weightCmp = x.weight.compareTo(y.weight)
      if (weightCmp == 0) x.key.toString().compareTo(y.key.toString())
      else weightCmp
    }
  }

  implicit object PathComparator extends Ordering[WeightedPath] {
    override def compare(x: WeightedPath, y: WeightedPath): Int = y.weight.compare(x.weight)
  }

  case class InfiniteLoop(path: Seq[GraphEdge]) extends Exception

  case class NegativeProbability(edge: GraphEdge, weight: RichWeight, heuristicsConstants: HeuristicsConstants) extends Exception

  /**
   * Add the given edge to the path and compute the new weight.
   *
   * @param sender                  node sending the payment
   * @param edge                    the edge we want to cross
   * @param prev                    weight of the rest of the path
   * @param currentBlockHeight      the height of the chain tip (latest block).
   * @param weightRatios            ratios used to 'weight' edges when searching for the shortest path
   * @param includeLocalChannelCost if the path is for relaying and we need to include the cost of the local channel
   */
  def addEdgeWeight(sender: PublicKey, edge: GraphEdge, prev: RichWeight, currentBlockHeight: BlockHeight,
                    weightRatios: Either[WeightRatios, HeuristicsConstants], includeLocalChannelCost: Boolean): RichWeight = {
    val totalAmount = if (edge.desc.a == sender && !includeLocalChannelCost) prev.amount else addEdgeFees(edge, prev.amount)
    val fee = totalAmount - prev.amount
    val totalFees = prev.fees + fee
    val cltv = if (edge.desc.a == sender && !includeLocalChannelCost) CltvExpiryDelta(0) else edge.params.cltvExpiryDelta
    val totalCltv = prev.cltv + cltv

    weightRatios match {
      case Left(weightRatios) =>
        RichWeight(sender, edge, prev, currentBlockHeight, totalAmount, fee, totalFees, totalCltv, weightRatios)
      case Right(heuristicsConstants) =>
        RichWeight(edge, prev, totalAmount, fee, totalFees, cltv, totalCltv, heuristicsConstants)
    }
  }

  /**
   * Calculate the minimum amount that the start node needs to receive to be able to forward @amountWithFees to the end
   * node.
   *
   * @param edge            the edge we want to cross
   * @param amountToForward the value that this edge will have to carry along
   * @return the new amount updated with the necessary fees for this edge
   */
  private def addEdgeFees(edge: GraphEdge, amountToForward: MilliSatoshi): MilliSatoshi = {
    amountToForward + edge.params.fee(amountToForward)
  }

  /** Validate that all edges along the path can relay the amount with fees. */
  def validatePath(path: Seq[GraphEdge], amount: MilliSatoshi): Boolean = validateReversePath(path.reverse, amount)

  @tailrec
  private def validateReversePath(path: Seq[GraphEdge], amount: MilliSatoshi): Boolean = path.headOption match {
    case None => true
    case Some(edge) =>
      val canRelayAmount = amount <= edge.capacity &&
        edge.balance_opt.forall(amount <= _) &&
        edge.params.htlcMaximum_opt.forall(amount <= _) &&
        edge.params.htlcMinimum <= amount
      if (canRelayAmount) validateReversePath(path.tail, addEdgeFees(edge, amount)) else false
  }

  /**
   * Calculates the total weighted cost of a path.
   * Note that the first hop from the sender is ignored: we don't pay a routing fee to ourselves.
   *
   * @param sender                  node sending the payment
   * @param path                    candidate path.
   * @param amount                  amount to send to the last node.
   * @param currentBlockHeight      the height of the chain tip (latest block).
   * @param wr                      ratios used to 'weight' edges when searching for the shortest path
   * @param includeLocalChannelCost if the path is for relaying and we need to include the cost of the local channel
   */
  def pathWeight(sender: PublicKey, path: Seq[GraphEdge], amount: MilliSatoshi, currentBlockHeight: BlockHeight,
                 wr: Either[WeightRatios, HeuristicsConstants], includeLocalChannelCost: Boolean): RichWeight = {
    path.foldRight(RichWeight(amount, 0, CltvExpiryDelta(0), 1.0, 0 msat, 0 msat, 0.0)) { (edge, prev) =>
      addEdgeWeight(sender, edge, prev, currentBlockHeight, wr, includeLocalChannelCost)
    }
  }

}
