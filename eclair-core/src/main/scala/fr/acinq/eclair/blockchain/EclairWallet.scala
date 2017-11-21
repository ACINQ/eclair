package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}

import scala.concurrent.Future

/**
  * Created by PM on 06/07/2017.
  */
trait EclairWallet {

  def getBalance: Future[Satoshi]

  def getFinalAddress: Future[String]

  def makeFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponse]

  /**
    * Committing *must* include publishing the transaction on the network.
    *
    * We need to be very careful here, we don't want to consider a commit 'failed' if we are not absolutely sure that the
    * funding tx won't end up on the blockchain: if that happens and we have cancelled the channel, then we would lose our
    * funds!
    *
    * @param tx
    * @return true if success
    *         false IF AND ONLY IF *HAS NOT BEEN PUBLISHED* otherwise funds are at risk!!!
    */
  def commit(tx: Transaction): Future[Boolean]

  /**
    * Cancels this transaction: this probably translates to "release locks on utxos".
    * @param tx
    * @return
    */
  def rollback(tx: Transaction): Future[Boolean]

}

final case class MakeFundingTxResponse(fundingTx: Transaction, fundingTxOutputIndex: Int)
