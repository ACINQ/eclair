package fr.acinq.eclair.blockchain.bitcoind.rpc

import com.ashbornelabs.bitcoin.xbt.psbt.Psbt
import com.ashbornelabs.bitcoin.xbt.scalacompat.{Block, KotlinUtils, Transaction}
import com.ashbornelabs.bitcoin.xbt.SigHash
import fr.acinq.eclair.blockchain.OnChainWallet
import org.json4s.JsonAST.{JBool, JObject, JString, JValue}
import org.scalatest.funsuite.AnyFunSuite

import java.util.Base64
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

class BitcoinCoreClientSighashSpec extends AnyFunSuite {

  implicit val ec: ExecutionContext = ExecutionContext.global

  test("map all supported XBT unified sighash types to Knots RPC names") {
    val psbt = new Psbt(KotlinUtils.scala2kmp(Transaction(version = 2, txIn = Nil, txOut = Nil, lockTime = 0)))
    var capturedParams = Seq.empty[Any]
    val rpcClient = new BitcoinJsonRPCClient {
      override def bitcoinGenesisHash = Block.RegtestGenesisBlock.hash
      override def wallet: Option[String] = Some("eclair")
      override def invoke(method: String, params: Any*)(implicit ec: ExecutionContext): Future[JValue] = {
        assert(method == "walletprocesspsbt")
        capturedParams = params
        val encoded = Base64.getEncoder.encodeToString(Psbt.write(psbt).toByteArray)
        Future.successful(JObject("psbt" -> JString(encoded), "complete" -> JBool(false)))
      }
    }
    val client = new BitcoinCoreClient(rpcClient)
    val processPsbt = client.getClass.getDeclaredMethods.find(_.getName == "processPsbt").get
    processPsbt.setAccessible(true)

    val cases = Seq(
      (SigHash.SIGHASH_ALL | SigHash.SIGHASH_UNIFIED, "ALL|UNIFIED"),
      (SigHash.SIGHASH_ALL | SigHash.SIGHASH_ANYONECANPAY | SigHash.SIGHASH_UNIFIED, "ALL|ANYONECANPAY|UNIFIED"),
      (SigHash.SIGHASH_NONE | SigHash.SIGHASH_UNIFIED, "NONE|UNIFIED"),
      (SigHash.SIGHASH_NONE | SigHash.SIGHASH_ANYONECANPAY | SigHash.SIGHASH_UNIFIED, "NONE|ANYONECANPAY|UNIFIED"),
      (SigHash.SIGHASH_SINGLE | SigHash.SIGHASH_UNIFIED, "SINGLE|UNIFIED"),
      (SigHash.SIGHASH_SINGLE | SigHash.SIGHASH_ANYONECANPAY | SigHash.SIGHASH_UNIFIED, "SINGLE|ANYONECANPAY|UNIFIED"),
    )

    cases.foreach { case (sighashType, expectedRpcName) =>
      val future = processPsbt.invoke(client, psbt, Boolean.box(true), Some(sighashType), ec).asInstanceOf[Future[OnChainWallet.ProcessPsbtResponse]]
      Await.result(future, 5.seconds)
      assert(capturedParams.last == expectedRpcName)
    }
  }
}
