package fr.acinq.eclair.blockchain.wallet

import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import grizzled.slf4j.Logging
import org.bitcoinj.core.{Coin, Context, Transaction => BitcoinjTransaction}
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.{SendRequest, Wallet}

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 08/07/2017.
  */
class BitcoinjWallet(val fWallet: Future[Wallet])(implicit ec: ExecutionContext) extends EclairWallet with Logging {

  fWallet.map(wallet => wallet.allowSpendingUnconfirmedTransactions())

  override def getBalance: Future[Satoshi] = for {
    wallet <- fWallet
  } yield {
    Context.propagate(wallet.getContext)
    Satoshi(wallet.getBalance.longValue())
  }

  override def getFinalAddress: Future[String] = for {
    wallet <- fWallet
  } yield {
    Context.propagate(wallet.getContext)
    wallet.currentReceiveAddress().toBase58
  }

  override def makeFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponse] = for {
    wallet <- fWallet
  } yield {
    logger.info(s"building funding tx")
    Context.propagate(wallet.getContext)
    val script = new Script(pubkeyScript)
    val tx = new BitcoinjTransaction(wallet.getParams)
    tx.addOutput(Coin.valueOf(amount.amount), script)
    val req = SendRequest.forTx(tx)
    wallet.completeTx(req)
    val txOutputIndex = tx.getOutputs.find(_.getScriptPubKey.equals(script)).get.getIndex
    MakeFundingTxResponse(Transaction.read(tx.bitcoinSerialize()), txOutputIndex)
  }

  override def commit(tx: Transaction): Future[Boolean] = {
    // we make sure that we haven't double spent our own tx (eg by opening 2 channels at the same time)
    val serializedTx = Transaction.write(tx)
    logger.info(s"committing tx: txid=${tx.txid} tx=$serializedTx")
    for {
      wallet <- fWallet
      _ = Context.propagate(wallet.getContext)
      bitcoinjTx = new org.bitcoinj.core.Transaction(wallet.getParams(), serializedTx)
      canCommit = wallet.maybeCommitTx(bitcoinjTx)
      _ = logger.info(s"commit txid=${tx.txid} result=$canCommit")
    } yield canCommit
  }
}
