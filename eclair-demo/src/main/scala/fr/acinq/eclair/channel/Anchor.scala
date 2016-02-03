package fr.acinq.eclair.channel

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.PollingWatcher
import fr.acinq.eclair.blockchain.PollingWatcher.{SignTransactionResponse, FundTransactionResponse}
import fr.acinq.eclair.channel
import org.bouncycastle.util.encoders.Hex

import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration._

/**
  * Created by fabrice on 03/02/16.
  */
object Anchor extends App {

  def makeAnchorTx(bitcoind: BitcoinJsonRPCClient, ourCommitPub: BinaryData, theirCommitPub: BinaryData, amount: Long)(implicit ec: ExecutionContext): Future[(Transaction, Int)] = {
    val anchorOutputScript = channel.Scripts.anchorPubkeyScript(ourCommitPub, theirCommitPub)
    val tx = Transaction(version = 1, txIn = Seq.empty[TxIn], txOut = TxOut(amount, anchorOutputScript) :: Nil, lockTime = 0)
    val future = for {
      FundTransactionResponse(tx1, changepos, fee) <- PollingWatcher.fundTransaction(bitcoind, tx)
      SignTransactionResponse(anchorTx, true) <- PollingWatcher.signTransaction(bitcoind, tx1)
      Some(pos) = Scripts.findPublicKeyScriptIndex(anchorTx, anchorOutputScript)
    } yield (anchorTx, pos)

    future
  }

  import scala.concurrent.ExecutionContext.Implicits.global

  object Alice {
    val (Base58.Prefix.SecretKeyTestnet, commitPriv) = Base58Check.decode("cQPmcNr6pwBQPyGfab3SksE9nTCtx9ism9T4dkS9dETNU2KKtJHk")
    val commitPub = Crypto.publicKeyFromPrivateKey(commitPriv)
    val (Base58.Prefix.SecretKeyTestnet, finalPriv) = Base58Check.decode("cUrAtLtV7GGddqdkhUxnbZVDWGJBTducpPoon3eKp9Vnr1zxs6BG")
    val finalPub = Crypto.publicKeyFromPrivateKey(finalPriv)
    val R: BinaryData = "secret".getBytes("UTF-8")
    val H: BinaryData = Crypto.sha256(R)
  }

  object Bob {
    val (Base58.Prefix.SecretKeyTestnet, commitPriv) = Base58Check.decode("cSUwLtdZ2tht9ZmHhdQue48pfe7tY2GT2TGWJDtjoZgo6FHrubGk")
    val commitPub = Crypto.publicKeyFromPrivateKey(commitPriv)
    val (Base58.Prefix.SecretKeyTestnet, finalPriv) = Base58Check.decode("cPR7ZgXpUaDPA3GwGceMDS5pfnSm955yvks3yELf3wMJwegsdGTg")
    val finalPub = Crypto.publicKeyFromPrivateKey(finalPriv)
  }

  val amount = (0.5 * Coin).asInstanceOf[Long]

  val config = ConfigFactory.load()
  val bitcoind = new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.address"),
    port = config.getInt("eclair.bitcoind.port"))

  val (anchorTx, pos) = Await.result(makeAnchorTx(bitcoind, Alice.commitPub, Bob.commitPub, amount), 10 seconds)
  println(anchorTx)
  println(Hex.toHexString(Transaction.write(anchorTx)))
  bitcoind.client.close()

  val spending = Transaction(version = 1,
    txIn = TxIn(OutPoint(anchorTx, pos), Array.emptyByteArray, 0xffffffffL) :: Nil,
    txOut = TxOut(10, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.commitPub)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
    lockTime = 0)

  val redeemScript = Scripts.multiSig2of2(Alice.commitPub, Bob.commitPub)
  val sig1 = Transaction.signInput(spending, 0, redeemScript, SIGHASH_ALL, Alice.commitPriv, randomize = false)
  val sig2 = Transaction.signInput(spending, 0, redeemScript, SIGHASH_ALL, Bob.commitPriv, randomize = false)
  val scriptSig = Scripts.sigScript2of2(sig1, sig2, Alice.commitPub, Bob.commitPub)
  val signedTx = spending.updateSigScript(0, scriptSig)
  Transaction.correctlySpends(signedTx, Seq(anchorTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
}
