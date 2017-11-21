package fr.acinq.eclair.blockchain.fee
import scala.concurrent.Future

/**
  * Created by PM on 09/07/2017.
  */
class ConstantFeeProvider(feerates: FeeratesPerByte) extends FeeProvider {

  override def getFeerates: Future[FeeratesPerByte] = Future.successful(feerates)

}
