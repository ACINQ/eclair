package fr.acinq.eclair.blockchain.wallet

import java.util.concurrent.{Executor, TimeUnit}

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import com.google.common.util.concurrent.ListenableFuture
import fr.acinq.bitcoin.Crypto.hash160
import fr.acinq.bitcoin.{Base58, Base58Check, BinaryData, OP_EQUAL, OP_HASH160, OP_PUSHDATA, Satoshi, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.{BroadcastTransaction, BroadcastTransactionResponse}
import fr.acinq.eclair.blockchain.electrum.ElectrumWallet._

import scala.concurrent.{ExecutionContext, Future}

class ElectrumWallet(val wallet: ActorRef)(implicit system: ActorSystem, ec: ExecutionContext, timeout: akka.util.Timeout)  extends EclairWallet {

  override def getBalance = (wallet ? GetBalance).mapTo[GetBalanceResponse].map(_.confirmed)

  override def getFinalAddress = (wallet ? GetCurrentReceiveAddress).mapTo[GetCurrentReceiveAddressResponse].map(_.address)

  override def makeFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long) = {
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, pubkeyScript) :: Nil, lockTime = 0)
    (wallet ? CompleteTransaction(tx, false)).mapTo[CompleteTransactionResponse].map(response => response match {
      case CompleteTransactionResponse(tx1, None) => MakeFundingTxResponse(tx1, 0)
      case CompleteTransactionResponse(_, Some(error)) => throw error
    })
  }

  def sendPayment(amount: Satoshi, address: String) : Future[Boolean] = {
    val publicKeyScript = Base58Check.decode(address) match {
      case (Base58.Prefix.PubkeyAddressTestnet, pubKeyHash) => Script.pay2pkh(pubKeyHash)
      case (Base58.Prefix.ScriptAddressTestnet, scriptHash) => OP_HASH160 :: OP_PUSHDATA(scriptHash) :: OP_EQUAL :: Nil
    }
    val tx = Transaction(version = 2, txIn = Nil, txOut = TxOut(amount, publicKeyScript) :: Nil, lockTime = 0)
    val future = for {
      CompleteTransactionResponse(tx1, None) <- (wallet ? CompleteTransaction(tx, false)).mapTo[CompleteTransactionResponse]
      result <- commit(tx1)
    } yield result

    future
  }

  def getMnemonics: Future[Seq[String]] = (wallet ? GetMnemonicCode).mapTo[GetMnemonicCodeResponse].map(_.mnemonics)

  override def commit(tx: Transaction) = (wallet ? BroadcastTransaction(tx)).mapTo[BroadcastTransactionResponse].map(_.error.isEmpty)
}
