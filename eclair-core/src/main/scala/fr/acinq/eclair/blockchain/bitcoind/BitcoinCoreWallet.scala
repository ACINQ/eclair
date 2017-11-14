package fr.acinq.eclair.blockchain.bitcoind

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Base58Check, BinaryData, OP_PUSHDATA, OutPoint, SIGHASH_ALL, Satoshi, Script, ScriptFlags, ScriptWitness, SigVersion, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.channel.{BITCOIN_OUTPUT_SPENT, BITCOIN_TX_CONFIRMED}
import fr.acinq.eclair.transactions.Transactions
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JBool, JDouble, JInt, JString}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

/**
  * Created by PM on 06/07/2017.
  */
class BitcoinCoreWallet(rpcClient: BitcoinJsonRPCClient, watcher: ActorRef)(implicit system: ActorSystem, ec: ExecutionContext) extends EclairWallet with Logging {

  override def getBalance: Future[Satoshi] = ???

  override def getFinalAddress: Future[String] = rpcClient.invoke("getnewaddress").map(json => {
    val JString(address) = json
    address
  })

  case class FundTransactionResponse(tx: Transaction, changepos: Int, fee: Double)

  case class SignTransactionResponse(tx: Transaction, complete: Boolean)

  case class MakeFundingTxResponseWithParent(parentTx: Transaction, fundingTx: Transaction, fundingTxOutputIndex: Int, priv: PrivateKey)

  def fundTransaction(hex: String, lockUnspents: Boolean): Future[FundTransactionResponse] = {
    rpcClient.invoke("fundrawtransaction", hex, BitcoinCoreWallet.Options(lockUnspents)).map(json => {
      val JString(hex) = json \ "hex"
      val JInt(changepos) = json \ "changepos"
      val JDouble(fee) = json \ "fee"
      FundTransactionResponse(Transaction.read(hex), changepos.intValue(), fee)
    })
  }

  def fundTransaction(tx: Transaction, lockUnspents: Boolean): Future[FundTransactionResponse] =
    fundTransaction(Transaction.write(tx).toString(), lockUnspents)

  def signTransaction(hex: String): Future[SignTransactionResponse] =
    rpcClient.invoke("signrawtransaction", hex).map(json => {
      val JString(hex) = json \ "hex"
      val JBool(complete) = json \ "complete"
      SignTransactionResponse(Transaction.read(hex), complete)
    })

  def signTransaction(tx: Transaction): Future[SignTransactionResponse] =
    signTransaction(Transaction.write(tx).toString())

  def getTransaction(txid: BinaryData): Future[Transaction] = {
    rpcClient.invoke("getrawtransaction", txid.toString()).map(json => {
      val JString(hex) = json
      Transaction.read(hex)
    })
  }

  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] =
    publishTransaction(Transaction.write(tx).toString())

  def publishTransaction(hex: String)(implicit ec: ExecutionContext): Future[String] =
    rpcClient.invoke("sendrawtransaction", hex) collect {
      case JString(txid) => txid
    }

  /**
    *
    * @param fundingTxResponse a funding tx response
    * @return an updated funding tx response that is properly sign
    */
  def sign(fundingTxResponse: MakeFundingTxResponseWithParent): MakeFundingTxResponseWithParent = {
    // find the output that we are spending from
    val utxo = fundingTxResponse.parentTx.txOut(fundingTxResponse.fundingTx.txIn(0).outPoint.index.toInt)

    val pub = fundingTxResponse.priv.publicKey
    val pubKeyScript = Script.pay2pkh(pub)
    val sig = Transaction.signInput(fundingTxResponse.fundingTx, 0, pubKeyScript, SIGHASH_ALL, utxo.amount, SigVersion.SIGVERSION_WITNESS_V0, fundingTxResponse.priv)
    val witness = ScriptWitness(Seq(sig, pub.toBin))
    val fundingTx1 = fundingTxResponse.fundingTx.updateSigScript(0, OP_PUSHDATA(Script.write(Script.pay2wpkh(pub))) :: Nil).updateWitness(0, witness)

    Transaction.correctlySpends(fundingTx1, fundingTxResponse.parentTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    fundingTxResponse.copy(fundingTx = fundingTx1)
  }

  /**
    *
    * @param fundingTxResponse funding transaction response, which includes a funding tx, its parent, and the private key
    *                          that we need to re-sign the funding
    * @param newParentTx       new parent tx
    * @return an updated funding transaction response where the funding tx now spends from newParentTx
    */
  def replaceParent(fundingTxResponse: MakeFundingTxResponseWithParent, newParentTx: Transaction): MakeFundingTxResponseWithParent = {
    // find the output that we are spending from
    val utxo = newParentTx.txOut(fundingTxResponse.fundingTx.txIn(0).outPoint.index.toInt)

    // check that it matches what we expect, which is a P2WPKH output to our public key
    require(utxo.publicKeyScript == Script.write(Script.pay2sh(Script.pay2wpkh(fundingTxResponse.priv.publicKey))))

    // update our tx input we the hash of the new parent
    val input = fundingTxResponse.fundingTx.txIn(0)
    val input1 = input.copy(outPoint = input.outPoint.copy(hash = newParentTx.hash))
    val unsignedFundingTx = fundingTxResponse.fundingTx.copy(txIn = Seq(input1))

    // and re-sign it
    sign(MakeFundingTxResponseWithParent(newParentTx, unsignedFundingTx, fundingTxResponse.fundingTxOutputIndex, fundingTxResponse.priv))
  }

  def makeParentAndFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponseWithParent] =
    for {
    // ask for a new address and the corresponding private key
      JString(address) <- rpcClient.invoke("getnewaddress")
      JString(wif) <- rpcClient.invoke("dumpprivkey", address)
      JString(segwitAddress) <- rpcClient.invoke("addwitnessaddress", address)
      (prefix, raw) = Base58Check.decode(wif)
      priv = PrivateKey(raw, compressed = true)
      pub = priv.publicKey
      // create a tx that sends money to a P2SH(WPKH) output that matches our private key
      parentFee = Satoshi(250 * 2 * 2 * feeRatePerKw / 1024)
      partialParentTx = Transaction(
        version = 2,
        txIn = Nil,
        txOut = TxOut(amount + parentFee, Script.pay2sh(Script.pay2wpkh(pub))) :: Nil,
        lockTime = 0L)
      FundTransactionResponse(unsignedParentTx, _, _) <- fundTransaction(partialParentTx, lockUnspents = true)
      // this is the first tx that we will publish, a standard tx which send money to our p2wpkh address
      SignTransactionResponse(parentTx, true) <- signTransaction(unsignedParentTx)
      // now we create the funding tx
      partialFundingTx = Transaction(
        version = 2,
        txIn = Seq.empty[TxIn],
        txOut = TxOut(amount, pubkeyScript) :: Nil,
        lockTime = 0)
      // and update it to spend from our segwit tx
      pos = Transactions.findPubKeyScriptIndex(parentTx, Script.pay2sh(Script.pay2wpkh(pub)))
      unsignedFundingTx = partialFundingTx.copy(txIn = TxIn(OutPoint(parentTx, pos), sequence = TxIn.SEQUENCE_FINAL, signatureScript = Nil) :: Nil)
    } yield sign(MakeFundingTxResponseWithParent(parentTx, unsignedFundingTx, 0, priv))

  /**
    * This is a workaround for malleability
    *
    * @param pubkeyScript
    * @param amount
    * @param feeRatePerKw
    * @return
    */
  override def makeFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponse] = {
    val promise = Promise[MakeFundingTxResponse]()
    (for {
      fundingTxResponse@MakeFundingTxResponseWithParent(parentTx, _, _, _) <- makeParentAndFundingTx(pubkeyScript, amount, feeRatePerKw)
      input0 = parentTx.txIn.head
      parentOfParentTx <- getTransaction(input0.outPoint.txid)
      _ = logger.debug(s"built parentTxid=${parentTx.txid}, initializing temporary actor")
      tempActor = system.actorOf(Props(new Actor {
        override def receive: Receive = {
          case WatchEventSpent(BITCOIN_OUTPUT_SPENT, spendingTx) =>
            if (parentTx.txid != spendingTx.txid) {
              // an input of our parent tx was spent by a tx that we're not aware of (i.e. a malleated version of our parent tx)
              // set a new watch; if it is confirmed, we'll use it as the new parent for our funding tx
              logger.warn(s"parent tx has been malleated: originalParentTxid=${parentTx.txid} malleated=${spendingTx.txid}")
            }
            watcher ! WatchConfirmed(self, spendingTx.txid, spendingTx.txOut(0).publicKeyScript, minDepth = 1, BITCOIN_TX_CONFIRMED(spendingTx))

          case WatchEventConfirmed(BITCOIN_TX_CONFIRMED(tx), _, _) =>
            // a potential parent for our funding tx has been confirmed, let's update our funding tx
            val finalFundingTx = replaceParent(fundingTxResponse, tx)
            promise.success(MakeFundingTxResponse(finalFundingTx.fundingTx, finalFundingTx.fundingTxOutputIndex))
        }
      }))
      // we watch the first input of the parent tx, so that we can detect when it is spent by a malleated avatar
      _ = watcher ! WatchSpent(tempActor, input0.outPoint.txid, input0.outPoint.index.toInt, parentOfParentTx.txOut(input0.outPoint.index.toInt).publicKeyScript, BITCOIN_OUTPUT_SPENT)
      // and we publish the parent tx
      _ = logger.info(s"publishing parent tx: txid=${parentTx.txid} tx=${Transaction.write(parentTx)}")
      // we use a small delay so that we are sure Publish doesn't race with WatchSpent (which is ok but generates unnecessary warnings)
      _ = system.scheduler.scheduleOnce(100 milliseconds, watcher, PublishAsap(parentTx))
    } yield {}) onFailure {
      case t: Throwable => promise.failure(t)
    }
    promise.future
  }

  /**
    * We don't manage double spends yet
    * @param tx
    * @return
    */
  override def commit(tx: Transaction): Future[Boolean] = publishTransaction(tx).map(_ => true)

}

object BitcoinCoreWallet {
  case class Options(lockUnspents: Boolean)
}