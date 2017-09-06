package fr.acinq.eclair.blockchain.wallet

import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}

import scala.concurrent.Future

/**
  * Created by PM on 06/07/2017.
  */
trait EclairWallet {

  def getBalance: Future[Satoshi]

  def getFinalAddress: Future[String]

  def makeFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponse]

  def commit(tx: Transaction): Future[Boolean]

}

final case class MakeFundingTxResponse(fundingTx: Transaction, fundingTxOutputIndex: Int)
