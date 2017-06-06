package fr.acinq.eclair.blockchain

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import org.scalatest.FunSuite

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

// this test is not run automatically
class ExtendedBitcoinClientSpec extends FunSuite {

  implicit lazy val system = ActorSystem()

  val config = ConfigFactory.load()
  val client = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(
    user = config.getString("eclair.bitcoind.rpcuser"),
    password = config.getString("eclair.bitcoind.rpcpassword"),
    host = config.getString("eclair.bitcoind.host"),
    port = 18332))

  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global
  val (chain, blockCount) = Await.result(client.client.invoke("getblockchaininfo").map(json => ((json \ "chain").extract[String], (json \ "blocks").extract[Long])), 10 seconds)
  assert(chain == "test", "you should be on testnet")

  test("get transaction short id") {
    val txid = "7b2184f8539af648d51cc11d2a83630dd10fdf2a40a1824777d7f8da8e0d4b9e"
    val conf = Await.result(client.getTxConfirmations(txid), 5 seconds)
    val (height, index) = Await.result(client.getTransactionShortId(txid), 5 seconds)
    assert(height == 150002)
    assert(index == 7)
  }

  test("get transaction by short id") {
    val tx = Await.result(client.getTransaction(150002, 7), 5 seconds)
    assert(tx.txid.toString() == "7b2184f8539af648d51cc11d2a83630dd10fdf2a40a1824777d7f8da8e0d4b9e")
  }

  test("is tx output spendable") {
    val result = Await.result(client.isTransactionOuputSpendable("48ebfd0c0fe043b76eee09fcd8ea1e9248ffe1553fa30040fb7f7112ba3a202f", 0, true), 5 seconds)
    assert(result)
    val result1 = Await.result(client.isTransactionOuputSpendable("48ebfd0c0fe043b76eee09fcd8ea1e9248ffe1553fa30040fb7f7112ba3a202f", 5, true), 5 seconds)
    assert(!result1)
  }
}
