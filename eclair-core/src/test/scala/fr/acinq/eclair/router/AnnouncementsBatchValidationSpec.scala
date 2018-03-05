package fr.acinq.eclair.router

import akka.actor.ActorSystem
import akka.testkit.TestProbe
import akka.pattern.pipe
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, Block, Satoshi, Script, Transaction}
import fr.acinq.eclair.blockchain.ValidateResult
import fr.acinq.eclair.blockchain.bitcoind.BitcoinCoreWallet
import fr.acinq.eclair.blockchain.bitcoind.rpc.{BasicBitcoinJsonRPCClient, ExtendedBitcoinClient}
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire.{ChannelAnnouncement, ChannelUpdate}
import fr.acinq.eclair.{ShortChannelId, randomKey}
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
    implicit val extendedBitcoinClient = new ExtendedBitcoinClient(new BasicBitcoinJsonRPCClient(user = "foo", password = "bar", host = "localhost", port = 18332))

    val channels = for (i <- 0 until 50) yield {
      // let's generate a block every 10 txs so that we can compute short ids
      if (i % 10 == 0) generateBlocks(1)
      simulateChannel()
    }
    generateBlocks(6)
    val announcements = channels.map(makeChannelAnnouncement)

    val sender = TestProbe()

    extendedBitcoinClient.validate(announcements(0)).pipeTo(sender.ref)
    sender.expectMsgType[ValidateResult].tx.isDefined

    extendedBitcoinClient.validate(announcements(1).copy(shortChannelId = ShortChannelId(Long.MaxValue))).pipeTo(sender.ref) // invalid block height
    sender.expectMsgType[ValidateResult].tx.isEmpty

    extendedBitcoinClient.validate(announcements(2).copy(shortChannelId = ShortChannelId(500, 1000, 0))).pipeTo(sender.ref) // invalid tx index
    sender.expectMsgType[ValidateResult].tx.isEmpty

  }

}

object AnnouncementsBatchValidationSpec {

  case class SimulatedChannel(node1Key: PrivateKey, node2Key: PrivateKey, node1FundingKey: PrivateKey, node2FundingKey: PrivateKey, amount: Satoshi, fundingTx: Transaction, fundingOutputIndex: Int)

  def generateBlocks(numBlocks: Int)(implicit extendedBitcoinClient: ExtendedBitcoinClient, ec: ExecutionContext) =
    Await.result(extendedBitcoinClient.rpcClient.invoke("generate", numBlocks), 10 seconds)

  def simulateChannel()(implicit extendedBitcoinClient: ExtendedBitcoinClient, ec: ExecutionContext, system: ActorSystem): SimulatedChannel = {
    val node1Key = randomKey
    val node2Key = randomKey
    val node1BitcoinKey = randomKey
    val node2BitcoinKey = randomKey
    val amount = Satoshi(1000000)
    // first we publish the funding tx
    val wallet = new BitcoinCoreWallet(extendedBitcoinClient.rpcClient)
    val fundingPubkeyScript = Script.write(Script.pay2wsh(Scripts.multiSig2of2(node1BitcoinKey.publicKey, node2BitcoinKey.publicKey)))
    val fundingTxFuture = wallet.makeFundingTx(fundingPubkeyScript, amount, 10000)
    val res = Await.result(fundingTxFuture, 10 seconds)
    Await.result(extendedBitcoinClient.publishTransaction(res.fundingTx), 10 seconds)
    SimulatedChannel(node1Key, node2Key, node1BitcoinKey, node2BitcoinKey, amount, res.fundingTx, res.fundingTxOutputIndex)
  }

  def makeChannelAnnouncement(c: SimulatedChannel)(implicit extendedBitcoinClient: ExtendedBitcoinClient, ec: ExecutionContext): ChannelAnnouncement = {
    val (blockHeight, txIndex) = Await.result(extendedBitcoinClient.getTransactionShortId(c.fundingTx.txid.toString()), 10 seconds)
    val shortChannelId = ShortChannelId(blockHeight, txIndex, c.fundingOutputIndex)
    val (channelAnnNodeSig1, channelAnnBitcoinSig1) = Announcements.signChannelAnnouncement(Block.RegtestGenesisBlock.hash, shortChannelId, c.node1Key, c.node2Key.publicKey, c.node1FundingKey, c.node2FundingKey.publicKey, BinaryData(""))
    val (channelAnnNodeSig2, channelAnnBitcoinSig2) = Announcements.signChannelAnnouncement(Block.RegtestGenesisBlock.hash, shortChannelId, c.node2Key, c.node1Key.publicKey, c.node2FundingKey, c.node1FundingKey.publicKey, BinaryData(""))
    val channelAnnouncement = Announcements.makeChannelAnnouncement(Block.RegtestGenesisBlock.hash, shortChannelId, c.node1Key.publicKey, c.node2Key.publicKey, c.node1FundingKey.publicKey, c.node2FundingKey.publicKey, channelAnnNodeSig1, channelAnnNodeSig2, channelAnnBitcoinSig1, channelAnnBitcoinSig2)
    channelAnnouncement
  }

  def makeChannelUpdate(c: SimulatedChannel, shortChannelId: ShortChannelId): ChannelUpdate =
    Announcements.makeChannelUpdate(Block.RegtestGenesisBlock.hash, c.node1Key, c.node2Key.publicKey, shortChannelId, 10, 1000, 10, 100)

}