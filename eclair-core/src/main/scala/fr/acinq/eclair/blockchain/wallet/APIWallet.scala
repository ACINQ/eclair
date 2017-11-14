package fr.acinq.eclair.blockchain.wallet

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Base58Check, BinaryData, Satoshi, Transaction, TxIn, TxOut}
import fr.acinq.eclair.HttpHelper.get
import fr.acinq.eclair.blockchain.{EclairWallet, MakeFundingTxResponse}
import org.json4s.JsonAST.{JField, JInt, JObject, JString}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Created by PM on 06/07/2017.
  */
class APIWallet(implicit ec: ExecutionContext) extends EclairWallet {

  val priv = PrivateKey(Base58Check.decode("cVa6PtdYqbfpM6oH1zhz8TnDaRTCdA4okv6x6SGxZhDcCztpPh6e")._2, compressed = true)
  val addr = "2MviVGDzjXmxaZNYYm12F6HfUDs19HH3YxZ"

  def getBalance: Future[Satoshi] = {
    for {
      JInt(balance) <- get(s"https://test-insight.bitpay.com/api/addr/$addr/balance")
    } yield Satoshi(balance.toLong)
  }

  override def getFinalAddress: Future[String] = Future.successful(addr)

  override def makeFundingTx(pubkeyScript: BinaryData, amount: Satoshi, feeRatePerKw: Long): Future[MakeFundingTxResponse] =
    for {
      address <- get(s"https://testnet-api.smartbit.com.au/v1/blockchain/address/$addr/unspent")
      utxos = for {
        JObject(utxo) <- address \ "unspent"
        JField("txid", JString(txid)) <- utxo
        JField("value_int", JInt(value_int)) <- utxo
        JField("n", JInt(n)) <- utxo
      } yield Utxo(txid = txid, n = n.toInt, value = value_int.toInt)
      // now we create the funding tx
      partialFundingTx = Transaction(
        version = 2,
        txIn = Seq.empty[TxIn],
        txOut = TxOut(amount, pubkeyScript) :: Nil,
        lockTime = 0)
    } yield {
      val fundingTx = MiniWallet.fundTransaction(partialFundingTx, utxos, Satoshi(1000000), priv)
      MakeFundingTxResponse(fundingTx, 0)
    }

  override def commit(tx: Transaction): Future[Boolean] = Future.successful(true) // not implemented
}
