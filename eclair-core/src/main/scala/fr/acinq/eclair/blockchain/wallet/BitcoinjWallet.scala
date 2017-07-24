package fr.acinq.eclair.blockchain.wallet

import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, Satoshi, Transaction}
import grizzled.slf4j.Logging
import org.bitcoinj.core.{Coin, Transaction => BitcoinjTransaction}
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.KeyChain.KeyPurpose
import org.bitcoinj.wallet.{SendRequest, Wallet}

import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 08/07/2017.
  */
class BitcoinjWallet(fWallet: Future[Wallet])(implicit ec: ExecutionContext) extends EclairWallet with Logging {

  fWallet.map(wallet => wallet.allowSpendingUnconfirmedTransactions())

  override def getFinalAddress: Future[String] = for {
    wallet <- fWallet
  } yield Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, wallet.freshSegwitAddress(KeyPurpose.RECEIVE_FUNDS).getHash160)

  override def makeFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponse] = for {
    wallet <- fWallet
  } yield {
    logger.info(s"building funding tx")
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
      bitcoinjTx = new org.bitcoinj.core.Transaction(wallet.getParams(), serializedTx)
      canCommit = wallet.maybeCommitTx(bitcoinjTx)
      _ = logger.info(s"commit txid=${tx.txid} result=$canCommit")
    } yield canCommit
  }
}
