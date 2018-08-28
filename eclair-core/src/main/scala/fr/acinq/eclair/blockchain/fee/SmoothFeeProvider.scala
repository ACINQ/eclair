package fr.acinq.eclair.blockchain.fee
import scala.concurrent.{ExecutionContext, Future}

class SmoothFeeProvider(provider: FeeProvider, windowSize: Int)(implicit ec: ExecutionContext) extends FeeProvider {
  require(windowSize > 0)

  var queue = List.empty[FeeratesPerKB]

  def add(rate: FeeratesPerKB) : Unit = synchronized {
    queue = queue :+ rate
    if (queue.length > windowSize) queue = queue.drop(1)
  }

  override def getFeerates: Future[FeeratesPerKB] = {
    for {
      rate <- provider.getFeerates
      _ = add(rate)
    } yield SmoothFeeProvider.avg(queue)
  }
}

object SmoothFeeProvider {

  def add(a: FeeratesPerKB, b: FeeratesPerKB) = a.copy(
    block_1 = a.block_1 + b.block_1,
    blocks_2 = a.blocks_2 + b.blocks_2,
    blocks_6 = a.blocks_6 + b.blocks_6,
    blocks_12 = a.blocks_12 + b.blocks_12,
    blocks_36 = a.blocks_36 + b.blocks_36,
    blocks_72 = a.blocks_72 + b.blocks_72)

  def avg(rates: Seq[FeeratesPerKB]) : FeeratesPerKB = {
    val s = rates.tail.foldLeft(rates.head)(add)
    s.copy(s.block_1 / rates.size, s.blocks_2 / rates.size, s.blocks_6 / rates.size, s.blocks_12 / rates.size, s.blocks_36 / rates.size, s.blocks_72 / rates.size)
  }
}
