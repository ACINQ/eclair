package fr.acinq.eclair.blockchain.fee
import scala.concurrent.Future

/**
  * Created by PM on 09/07/2017.
  */
class ConstantFeeProvider(feeratePerKB: Long) extends FeeProvider {

  override def getFeeratePerKB: Future[Long] = Future.successful(feeratePerKB)
}
