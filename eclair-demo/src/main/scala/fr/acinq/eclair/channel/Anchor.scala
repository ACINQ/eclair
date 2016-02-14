package fr.acinq.eclair.channel

import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.PollingWatcher
import fr.acinq.eclair.blockchain.PollingWatcher.{SignTransactionResponse, FundTransactionResponse}
import fr.acinq.eclair.channel
import org.bouncycastle.util.encoders.Hex

import scala.concurrent.{Future, ExecutionContext, Await}
import scala.concurrent.duration._

object Anchor extends App {
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

  val amount = (0.06 * Coin).asInstanceOf[Long]

  val config = ConfigFactory.load()
  val bitcoind = new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.address"),
    port = config.getInt("eclair.bitcoind.port"))

  val (anchorTx, pos) = (Transaction.read("010000000164345d5e5f6d3f8489740bc4e3f8bf8686b2ac221af48f0a2e88f601496182f1010000006a47304402204db8a977f275e74c92d28b18d82f09b5291111d0435cb3e653268a1d35dbbe02022074ada818ff9ea49ec613132423c42a5eecc223dbaa6ec1719a8de75539f659ca012103b8e06b059d35f1a3447ed834d265cb194f0f67dc50da30d18e569a40af697565feffffff02808d5b000000000017a91408bc5c0400edc31cbca9204fe1b8463b8c912f0187d99f0602000000001976a9148cda43313910281fe08a9d1659249e1f97152f8588ac00000000"), 0) //Await.result(makeAnchorTx(bitcoind, Alice.commitPub, Bob.commitPub, amount), 10 seconds)
  println(anchorTx)
  println(s"anchor tx: ${Hex.toHexString(Transaction.write(anchorTx))}")
  bitcoind.client.close()

  val spending = Transaction(version = 1,
    txIn = TxIn(OutPoint(anchorTx, pos), Array.emptyByteArray, 0xffffffffL) :: Nil,
    txOut = TxOut(amount - 10, OP_DUP :: OP_HASH160 :: OP_PUSHDATA(Crypto.hash160(Alice.commitPub)) :: OP_EQUALVERIFY :: OP_CHECKSIG :: Nil) :: Nil,
    lockTime = 0)

  val redeemScript = Scripts.multiSig2of2(Alice.commitPub, Bob.commitPub)
  val sig1 = Transaction.signInput(spending, 0, redeemScript, SIGHASH_ALL, Alice.commitPriv, randomize = false)
  val sig2 = Transaction.signInput(spending, 0, redeemScript, SIGHASH_ALL, Bob.commitPriv, randomize = false)
  val scriptSig = Scripts.sigScript2of2(sig1, sig2, Alice.commitPub, Bob.commitPub)
  val signedTx = spending.updateSigScript(0, scriptSig)
  Transaction.correctlySpends(signedTx, Seq(anchorTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
  println(s"spending tx: ${Hex.toHexString(Transaction.write(signedTx))}")
}
