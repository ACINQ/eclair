package fr.acinq.eclair.blockchain.wallet

import akka.actor.ActorRef
import akka.pattern.ask
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction, TxOut}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{BroadcastTransaction, BroadcastTransactionResponse}
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet._

import scala.concurrent.ExecutionContext

class ElectrumWallet(wallet :ActorRef)(implicit ec: ExecutionContext, timeout: akka.util.Timeout)  extends EclairWallet {
  override def getBalance = (wallet ? GetBalance).mapTo[GetBalanceResponse].map(_.balance)

  override def getFinalAddress = (wallet ? GetCurrentReceiveAddress).mapTo[GetCurrentReceiveAddressResponse].map(_.address)

  override def makeFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long) = {
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, pubkeyScript) :: Nil, lockTime = 0)
    (wallet ? CompleteTransaction(tx)).mapTo[CompleteTransactionResponse].map(response => response match {
      case CompleteTransactionResponse(tx1, None) => MakeFundingTxResponse(tx1, 0)
      case CompleteTransactionResponse(_, Some(error)) => throw error
    })
  }

  override def commit(tx: Transaction) = (wallet ? BroadcastTransaction(tx)).mapTo[BroadcastTransactionResponse].map(_.error.isEmpty)
}
