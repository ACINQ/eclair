package fr.acinq.eclair.blockchain.fee

trait FeeEstimator {

  def getFeeratePerKb(target: Int) : Long
  def getFeeratePerKw(target: Int) : Long

}