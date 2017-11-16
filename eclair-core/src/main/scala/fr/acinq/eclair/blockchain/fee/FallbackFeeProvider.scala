package fr.acinq.eclair.blockchain.fee

import scala.concurrent.{ExecutionContext, Future}

/**
  * This provider will try all child providers in sequence, until one of them works
  */
class FallbackFeeProvider(providers: Seq[FeeProvider])(implicit ec: ExecutionContext) extends FeeProvider {

  require(providers.size >= 1, "need at least one fee provider")

  def getFeerates(fallbacks: Seq[FeeProvider]): Future[FeeratesPerByte] =
    fallbacks match {
      case last +: Nil => last.getFeerates
      case head +: remaining => head.getFeerates.recoverWith { case _ => getFeerates(remaining) }
    }

  override def getFeerates: Future[FeeratesPerByte] = getFeerates(providers)

}
