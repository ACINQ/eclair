package fr.acinq.eclair.router

import akka.actor.ActorSystem
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import fr.acinq.eclair.blockchain.{ExtendedBitcoinClient, MakeFundingTxResponse}
import fr.acinq.eclair.blockchain.rpc.BitcoinJsonRPCClient
import fr.acinq.eclair.wire.ChannelAnnouncement
import fr.acinq.eclair.{randomKey, toShortId}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

/**
  * Created by PM on 31/05/2016.
  */
@RunWith(classOf[JUnitRunner])
class AnnouncementsBatchValidationSpec extends FunSuite {

  import AnnouncementsBatchValidationSpec._

  ignore("validate a batch of announcements") {
    import scala.concurrent.ExecutionContext.Implicits.global

    implicit val system = ActorSystem()
    implicit val extendedBitcoinClient = new ExtendedBitcoinClient(new BitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 18332))

    val channels = for (i <- 0 until 50) yield {
      // let's generate a block every 10 txs so that we can compute short ids
      if (i % 10 == 0) generateBlocks(1)
      simulateChannel()
    }
    generateBlocks(6)
    val announcements = channels.map(makeChannelAnnouncement)

    val alteredAnnouncements = announcements.zipWithIndex map {
      case (ann, 3) => ann.copy(shortChannelId = Long.MaxValue) // invalid block height
      case (ann, 7) => ann.copy(shortChannelId = toShortId(500, 1000, 0)) // invalid tx index
      case (ann, _) => ann
    }

    val res = Await.result(extendedBitcoinClient.getParallel(alteredAnnouncements), 10 seconds)

    assert(res.r(3).tx == None)
    assert(res.r(7).tx == None)

  }

}

object AnnouncementsBatchValidationSpec {

  case class SimulatedChannel(node1Key: PrivateKey, node2Key: PrivateKey, node1FundingKey: PrivateKey, node2FundingKey: PrivateKey, amount: Satoshi, fundingTx: Transaction, fundingOutputIndex: Int)

  def generateBlocks(numBlocks: Int)(implicit extendedBitcoinClient: ExtendedBitcoinClient, ec: ExecutionContext) =
    Await.result(extendedBitcoinClient.client.invoke("generate", numBlocks), 10 seconds)

  def simulateChannel()(implicit extendedBitcoinClient: ExtendedBitcoinClient, ec: ExecutionContext): SimulatedChannel = {
    val node1Key = randomKey
    val node2Key = randomKey
    val node1BitcoinKey = randomKey
    val node2BitcoinKey = randomKey
    val amount = Satoshi(1000000)
    // first we publish the funding tx
    val fundingTxFuture = extendedBitcoinClient.makeFundingTx(node1BitcoinKey.publicKey, node2BitcoinKey.publicKey, amount, 10000)
    val MakeFundingTxResponse(parentTx, fundingTx, fundingOutputIndex, _) = Await.result(fundingTxFuture, 10 seconds)
    Await.result(extendedBitcoinClient.publishTransaction(parentTx), 10 seconds)
    Await.result(extendedBitcoinClient.publishTransaction(fundingTx), 10 seconds)
    SimulatedChannel(node1Key, node2Key, node1BitcoinKey, node2BitcoinKey, amount, fundingTx, fundingOutputIndex)
  }

  def makeChannelAnnouncement(c: SimulatedChannel)(implicit extendedBitcoinClient: ExtendedBitcoinClient, ec: ExecutionContext): ChannelAnnouncement = {
    val (blockHeight, txIndex) = Await.result(extendedBitcoinClient.getTransactionShortId(c.fundingTx.txid.toString()), 10 seconds)
    val shortChannelId = toShortId(blockHeight, txIndex, c.fundingOutputIndex)
    val (channelAnnNodeSig1, channelAnnBitcoinSig1) = Announcements.signChannelAnnouncement(shortChannelId, c.node1Key, c.node2Key.publicKey, c.node1FundingKey, c.node2FundingKey.publicKey, BinaryData(""))
    val (channelAnnNodeSig2, channelAnnBitcoinSig2) = Announcements.signChannelAnnouncement(shortChannelId, c.node2Key, c.node1Key.publicKey, c.node2FundingKey, c.node1FundingKey.publicKey, BinaryData(""))
    val channelAnnouncement = Announcements.makeChannelAnnouncement(shortChannelId, c.node1Key.publicKey, c.node2Key.publicKey, c.node1FundingKey.publicKey, c.node2FundingKey.publicKey, channelAnnNodeSig1, channelAnnNodeSig2, channelAnnBitcoinSig1, channelAnnBitcoinSig2)
    channelAnnouncement
  }
}