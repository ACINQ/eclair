package fr.acinq.eclair.blockchain.fee

import scala.concurrent.{ExecutionContext, Future}

class SmoothFeeProvider(provider: FeeProvider, windowSize: Int)(implicit ec: ExecutionContext) extends FeeProvider {
  require(windowSize > 0)

  var queue = List.empty[FeeratesPerKB]

  def append(rate: FeeratesPerKB): Unit = synchronized {
    queue = queue :+ rate
    if (queue.length > windowSize) queue = queue.drop(1)
  }

  override def getFeerates: Future[FeeratesPerKB] = {
    for {
      rate <- provider.getFeerates
      _ = append(rate)
    } yield SmoothFeeProvider.smooth(queue)
  }
}

object SmoothFeeProvider {

  def avg(i: Seq[Long]): Long = i.sum / i.size

  def smooth(rates: Seq[FeeratesPerKB]): FeeratesPerKB =
    FeeratesPerKB(
      block_1 = avg(rates.map(_.block_1)),
      blocks_2 = avg(rates.map(_.blocks_2)),
      blocks_6 = avg(rates.map(_.blocks_6)),
      blocks_12 = avg(rates.map(_.blocks_12)),
      blocks_36 = avg(rates.map(_.blocks_36)),
      blocks_72 = avg(rates.map(_.blocks_72)))
}
