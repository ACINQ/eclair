package fr.acinq.eclair.blockchain

import fr.acinq.bitcoin._
import fr.acinq.eclair.channel
import fr.acinq.eclair.channel.Scripts
import org.bouncycastle.util.encoders.Hex
import org.json4s.JsonAST._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Created by PM on 26/04/2016.
  */
class ExtendedBitcoinClient(val client: BitcoinJsonRPCClient) {

  implicit val formats = org.json4s.DefaultFormats

  // TODO: this will probably not be needed once segwit is merged into core
  val protocolVersion = Protocol.PROTOCOL_VERSION

  def tx2Hex(tx: Transaction): String = Hex.toHexString(Transaction.write(tx, protocolVersion))

  def hex2tx(hex: String): Transaction = Transaction.read(hex, protocolVersion)

  def getTxConfirmations(txId: String)(implicit ec: ExecutionContext): Future[Option[Int]] =
    client.invoke("getrawtransaction", txId, 1) // we choose verbose output to get the number of confirmations
      .map(json => Some((json \ "confirmations").extract[Int]))
      .recover {
        case t: JsonRPCError if t.code == -5 => None
      }

  /**
    * *used in interop test*
    * tell bitcoind to sent bitcoins from a specific local account
    *
    * @param account     name of the local account to send bitcoins from
    * @param destination destination address
    * @param amount      amount in BTC (not milliBTC, not Satoshis !!)
    * @param ec          execution context
    * @return a Future[txid] where txid (a String) is the is of the tx that sends the bitcoins
    */
  def sendFromAccount(account: String, destination: String, amount: Double)(implicit ec: ExecutionContext): Future[String] =
  client.invoke("sendfrom", account, destination, amount) collect {
    case JString(txid) => txid
  }

  /**
    * @param txId
    * @param ec
    * @return
    */
  def getRawTransaction(txId: String)(implicit ec: ExecutionContext): Future[String] =
    client.invoke("getrawtransaction", txId) collect {
      case JString(raw) => raw
    }

  def getTransaction(txId: String)(implicit ec: ExecutionContext): Future[Transaction] =
    getRawTransaction(txId).map(raw => Transaction.read(raw))

  case class FundTransactionResponse(tx: Transaction, changepos: Int, fee: Double)

  def fundTransaction(hex: String)(implicit ec: ExecutionContext): Future[FundTransactionResponse] = {
    client.invoke("fundrawtransaction", hex /*hex.take(4) + "0000" + hex.drop(4)*/).map(json => {
      val JString(hex) = json \ "hex"
      val JInt(changepos) = json \ "changepos"
      val JDouble(fee) = json \ "fee"
      FundTransactionResponse(Transaction.read(hex), changepos.intValue(), fee)
    })
  }

  def fundTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[FundTransactionResponse] =
    fundTransaction(tx2Hex(tx))

  case class SignTransactionResponse(tx: Transaction, complete: Boolean)

  def signTransaction(hex: String)(implicit ec: ExecutionContext): Future[SignTransactionResponse] =
    client.invoke("signrawtransaction", hex).map(json => {
      val JString(hex) = json \ "hex"
      val JBool(complete) = json \ "complete"
      SignTransactionResponse(Transaction.read(hex), complete)
    })

  def signTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[SignTransactionResponse] =
    signTransaction(tx2Hex(tx))

  def publishTransaction(hex: String)(implicit ec: ExecutionContext): Future[String] =
    client.invoke("sendrawtransaction", hex) collect {
      case JString(txid) => txid
    }

  def publishTransaction(tx: Transaction)(implicit ec: ExecutionContext): Future[String] =
    publishTransaction(tx2Hex(tx))

  def makeAnchorTx(ourCommitPub: BinaryData, theirCommitPub: BinaryData, amount: Satoshi)(implicit ec: ExecutionContext): Future[(Transaction, Int)] = {
    val anchorOutputScript = channel.Scripts.anchorPubkeyScript(ourCommitPub, theirCommitPub)
    val tx = Transaction(version = 2, txIn = Seq.empty[TxIn], txOut = TxOut(amount, anchorOutputScript) :: Nil, lockTime = 0)
    val future = for {
      FundTransactionResponse(tx1, changepos, fee) <- fundTransaction(tx)
      SignTransactionResponse(anchorTx, true) <- signTransaction(tx1)
      Some(pos) = Scripts.findPublicKeyScriptIndex(anchorTx, anchorOutputScript)
    } yield (anchorTx, pos)

    future
  }

  def makeAnchorTx(fundingPriv: BinaryData, ourCommitPub: BinaryData, theirCommitPub: BinaryData, amount: Btc)(implicit ec: ExecutionContext): Future[(Transaction, Int)] = {
    val pub = Crypto.publicKeyFromPrivateKey(fundingPriv)
    val script = Script.write(Scripts.pay2sh(Scripts.pay2wpkh(pub)))
    val address = Base58Check.encode(Base58.Prefix.ScriptAddressTestnet, script)
    val future = for {
      id <- sendFromAccount("", address, amount.amount.toDouble)
      tx <- getTransaction(id)
      Some(pos) = Scripts.findPublicKeyScriptIndex(tx, script)
      output = tx.txOut(pos)
      anchorOutputScript = channel.Scripts.anchorPubkeyScript(ourCommitPub, theirCommitPub)
      tx1 = Transaction(version = 2, txIn = TxIn(OutPoint(tx, pos), Nil, 0xffffffffL) :: Nil, txOut = TxOut(amount, anchorOutputScript) :: Nil, lockTime = 0)
      pubKeyScript = Script.write(OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(pub)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil)
      sig = Transaction.signInput(tx1, 0, pubKeyScript, SIGHASH_ALL, output.amount, 1, fundingPriv)
      witness = ScriptWitness(Seq(sig, pub))
      tx2 = tx1.updateWitness(0, witness)
      Some(pos1) = Scripts.findPublicKeyScriptIndex(tx2, anchorOutputScript)
    } yield (tx2, pos1)

    future
  }

  /**
    * We need this to compute absolute timeouts expressed in number of blocks (where getBlockCount would be equivalent
    * to time.now())
    *
    * @param ec
    * @return the current number of blocks in the active chain
    */
  def getBlockCount(implicit ec: ExecutionContext): Future[Long] =
  client.invoke("getblockcount") collect {
    case JInt(count) => count.toLong
  }
}
