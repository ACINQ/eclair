package fr.acinq.eclair.blockchain.fee

import scala.concurrent.Future

/**
  * Created by PM on 09/07/2017.
  */
trait FeeProvider {

  def getFeeratePerKB: Future[Long]

}
