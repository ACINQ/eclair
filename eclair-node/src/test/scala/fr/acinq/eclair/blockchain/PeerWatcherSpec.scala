package fr.acinq.eclair.blockchain

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.Timeout
import fr.acinq.bitcoin.Script._
import fr.acinq.bitcoin.SigVersion._
import fr.acinq.bitcoin._
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.transactions.Scripts._
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.{TestConstants, randomKey}
import org.junit.runner.RunWith
import org.scalatest.FunSuiteLike
import org.scalatest.junit.JUnitRunner

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Created by PM on 22/02/2017.
  */
@RunWith(classOf[JUnitRunner])
class PeerWatcherSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  ignore("publish a csv tx") {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val formats = org.json4s.DefaultFormats
    implicit val timeout = Timeout(30 seconds)

    val bitcoin_client = new ExtendedBitcoinClient(new BitcoinJsonRPCClient("foo", "bar", port = 18332))
    val (chain, blockCount, progress) = Await.result(bitcoin_client.client.invoke("getblockchaininfo").map(json => ((json \ "chain").extract[String], (json \ "blocks").extract[Long], (json \ "verificationprogress").extract[Double])), 10 seconds)
    assert(chain == "regtest")

    val watcher = system.actorOf(PeerWatcher.props(TestConstants.Alice.nodeParams, bitcoin_client))

    // first we pick a random key
    val localDelayedKey = randomKey
    val revocationKey = randomKey
    // then a delayed script
    val delay = 10
    val redeemScript = write(toLocalDelayed(revocationKey.publicKey, delay, localDelayedKey.publicKey))
    val pubKeyScript = write(pay2wsh(redeemScript))
    // and we generate a tx which pays to the delayed script
    val amount = Satoshi(1000000)
    val partialParentTx = Transaction(
      version = 2,
      txIn = Seq.empty[TxIn],
      txOut = TxOut(amount, pubKeyScript) :: Nil,
      lockTime = 0)
    // we ask bitcoind to fund the tx
    val futureParentTx = for {
      funded <- bitcoin_client.fundTransaction(partialParentTx).map(_.tx)
      signed <- bitcoin_client.signTransaction(funded)
    } yield signed.tx
    val parentTx = Await.result(futureParentTx, 10 seconds)
    val outputIndex = Transactions.findPubKeyScriptIndex(parentTx, pubKeyScript)
    // we build a tx spending the parent tx
    val finalPubKeyHash = Base58Check.decode("mkmJFtGN5QvVyYz2NLXPGW1p2SABo2LV9y")._2
    val unsignedTx = Transaction(
      version = 2,
      txIn = TxIn(OutPoint(parentTx.hash, outputIndex), Array.emptyByteArray, delay) :: Nil,
      txOut = TxOut(Satoshi(900000), Script.pay2pkh(finalPubKeyHash)) :: Nil,
      lockTime = 0)
    val sig = Transaction.signInput(unsignedTx, 0, redeemScript, SIGHASH_ALL, amount, SIGVERSION_WITNESS_V0, localDelayedKey)
    val witness = witnessToLocalDelayedAfterDelay(sig, redeemScript)
    val tx = unsignedTx.updateWitness(0, witness)

    watcher ! NewBlock(Block(null, Nil))
    watcher ! PublishAsap(tx)
    Thread.sleep(5000)
    watcher ! PublishAsap(parentTx)
    // tester should manually generate blocks
    while(true) {
      Thread.sleep(5000)
      watcher ! NewBlock(Block(null, Nil))
    }

  }

}
