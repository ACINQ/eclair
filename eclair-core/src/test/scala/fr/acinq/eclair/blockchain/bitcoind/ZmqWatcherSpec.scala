/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.blockchain.bitcoind

import akka.Done
import akka.actor.typed.scaladsl.adapter.{ClassicActorSystemOps, TypedActorRefOps, actorRefAdapter}
import akka.actor.{ActorRef, Props, typed}
import akka.pattern.pipe
import akka.testkit.TestProbe
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.scalacompat.{Block, Btc, MilliBtcDouble, OutPoint, SatoshiLong, Script, Transaction, TxId, TxOut}
import fr.acinq.eclair.TestUtils.randomTxId
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, MakeFundingTxResponse}
import fr.acinq.eclair.blockchain.WatcherSpec._
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.SignTransactionResponse
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, NewTransaction}
import fr.acinq.eclair.{BlockHeight, RealShortChannelId, TestConstants, TestKitBaseClass, randomKey}
import grizzled.slf4j.Logging
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Promise
import scala.concurrent.duration._

class ZmqWatcherSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with BeforeAndAfterAll with Logging {

  var zmqBlock: ActorRef = _
  var zmqTx: ActorRef = _

  override def beforeAll(): Unit = {
    logger.info("starting bitcoind")
    startBitcoind()
    waitForBitcoindReady()
    logger.info("starting zmq actors")
    val (zmqBlockConnected, zmqTxConnected) = (Promise[Done](), Promise[Done]())
    zmqBlock = system.actorOf(Props(new ZMQActor(s"tcp://127.0.0.1:$bitcoindZmqBlockPort", ZMQActor.Topics.HashBlock, Some(zmqBlockConnected))))
    zmqTx = system.actorOf(Props(new ZMQActor(s"tcp://127.0.0.1:$bitcoindZmqTxPort", ZMQActor.Topics.RawTx, Some(zmqTxConnected))))
    awaitCond(zmqBlockConnected.isCompleted && zmqTxConnected.isCompleted)
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    logger.info("stopping zmq actors")
    system.stop(zmqBlock)
    system.stop(zmqTx)
    logger.info("stopping bitcoind")
    stopBitcoind()
    super.afterAll()
  }

  case class Fixture(blockHeight: AtomicLong, bitcoinClient: BitcoinCoreClient, watcher: typed.ActorRef[ZmqWatcher.Command], probe: TestProbe, listener: TestProbe)

  // NB: we can't use ScalaTest's fixtures, they would see uninitialized bitcoind fields because they sandbox each test.
  private def withWatcher(testFun: Fixture => Any, scanPastBlock: Boolean = false): Unit = {
    val blockCount = new AtomicLong()
    val probe = TestProbe()
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[CurrentBlockHeight])
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    val nodeParams = TestConstants.Alice.nodeParams
      .modify(_.chainHash).setTo(Block.RegtestGenesisBlock.hash)
      // We disable previous block scan by default.
      .modify(_.channelConf.scanPreviousBlocksDepth).setToIf(!scanPastBlock)(0)
      // We enable it and use a faster (randomized) delay when requested.
      .modify(_.channelConf.scanPreviousBlocksDepth).setToIf(scanPastBlock)(6)
      .modify(_.channelConf.maxBlockProcessingDelay).setToIf(scanPastBlock)(10 millis)
    val watcher = system.spawn(ZmqWatcher(nodeParams, blockCount, bitcoinClient), UUID.randomUUID().toString)
    try {
      testFun(Fixture(blockCount, bitcoinClient, watcher, probe, listener))
    } finally {
      system.stop(watcher.ref.toClassic)
    }
  }

  test("reconnect ZMQ automatically") {
    withWatcher(f => {
      import f._

      // When the watcher starts, it broadcasts the current height.
      val block1 = listener.expectMsgType[CurrentBlockHeight]
      listener.expectNoMessage(100 millis)

      restartBitcoind(probe)
      generateBlocks(1)
      val block2 = listener.expectMsgType[CurrentBlockHeight]
      assert(block2.blockHeight == block1.blockHeight + 1)
      listener.expectNoMessage(100 millis)
    })
  }

  test("add/remove watches from/to utxo map") {
    val m0 = Map.empty[OutPoint, Set[Watch[_ <: WatchTriggered]]]
    val txid = randomTxId()
    val outputIndex = 42
    val utxo = OutPoint(txid, outputIndex)

    val w1 = WatchFundingSpent(TestProbe().ref, txid, outputIndex, hints = Set.empty)
    val w2 = WatchFundingSpent(TestProbe().ref, txid, outputIndex, hints = Set.empty)
    val w3 = WatchExternalChannelSpent(TestProbe().ref, txid, outputIndex, RealShortChannelId(1))
    val w4 = WatchExternalChannelSpent(TestProbe().ref, randomTxId(), 5, RealShortChannelId(1))
    val w5 = WatchFundingConfirmed(TestProbe().ref, txid, 3)

    // we test as if the collection was immutable
    val m1 = addWatchedUtxos(m0, w1)
    assert(m1.keySet == Set(utxo) && m1.size == 1)
    val m2 = addWatchedUtxos(m1, w2)
    assert(m2.keySet == Set(utxo) && m2(utxo).size == 2)
    val m3 = addWatchedUtxos(m2, w3)
    assert(m3.keySet == Set(utxo) && m3(utxo).size == 3)
    val m4 = addWatchedUtxos(m3, w4)
    assert(m4.keySet == Set(utxo, OutPoint(w4.txId, w4.outputIndex)) && m3(utxo).size == 3)
    val m5 = addWatchedUtxos(m4, w5)
    assert(m5.keySet == Set(utxo, OutPoint(w4.txId, w4.outputIndex)) && m5(utxo).size == 3)
    val m6 = removeWatchedUtxos(m5, w3)
    assert(m6.keySet == Set(utxo, OutPoint(w4.txId, w4.outputIndex)) && m6(utxo).size == 2)
    val m7 = removeWatchedUtxos(m6, w3)
    assert(m7.keySet == Set(utxo, OutPoint(w4.txId, w4.outputIndex)) && m7(utxo).size == 2)
    val m8 = removeWatchedUtxos(m7, w2)
    assert(m8.keySet == Set(utxo, OutPoint(w4.txId, w4.outputIndex)) && m8(utxo).size == 1)
    val m9 = removeWatchedUtxos(m8, w1)
    assert(m9.keySet == Set(OutPoint(w4.txId, w4.outputIndex)))
    val m10 = removeWatchedUtxos(m9, w4)
    assert(m10.isEmpty)
  }

  test("send event when new block is found") {
    withWatcher(f => {
      import f._

      // When the watcher starts, it broadcasts the current height.
      val block1 = listener.expectMsgType[CurrentBlockHeight]
      assert(blockHeight.get() == block1.blockHeight.toLong)
      listener.expectNoMessage(100 millis)

      generateBlocks(1)
      assert(listener.expectMsgType[CurrentBlockHeight].blockHeight == block1.blockHeight + 1)
      assert(blockHeight.get() == block1.blockHeight.toLong + 1)
      listener.expectNoMessage(100 millis)

      generateBlocks(5)
      assert(listener.expectMsgType[CurrentBlockHeight].blockHeight == block1.blockHeight + 6)
      assert(blockHeight.get() == block1.blockHeight.toLong + 6)
      listener.expectNoMessage(100 millis)
    })
  }

  test("watch for published transactions") {
    withWatcher(f => {
      import f._

      val address = getNewAddress(probe)
      val confirmedTx = sendToAddress(address, 250_000 sat, probe)
      generateBlocks(1)

      watcher ! WatchPublished(probe.ref, confirmedTx.txid)
      probe.expectMsg(WatchPublishedTriggered(confirmedTx))

      val publishedTx = sendToAddress(address, 100_000 sat, probe)
      watcher ! WatchPublished(probe.ref, publishedTx.txid)
      probe.expectMsg(WatchPublishedTriggered(publishedTx))

      bitcoinClient.makeFundingTx(Script.write(Script.pay2wpkh(randomKey().publicKey)), 150_000 sat, FeeratePerKw(2500 sat)).pipeTo(probe.ref)
      val unpublishedTx = probe.expectMsgType[MakeFundingTxResponse].fundingTx
      watcher ! WatchPublished(probe.ref, unpublishedTx.txid)
      probe.expectNoMessage(100 millis)

      bitcoinClient.publishTransaction(unpublishedTx)
      probe.expectMsg(WatchPublishedTriggered(unpublishedTx))
    })
  }

  test("watch for confirmed transactions") {
    withWatcher(f => {
      import f._

      val address = getNewAddress(probe)
      val tx1 = sendToAddress(address, Btc(0.7), probe)
      val tx2 = sendToAddress(address, Btc(0.5), probe)

      watcher ! WatchFundingConfirmed(probe.ref, tx1.txid, 1)
      watcher ! WatchFundingConfirmed(probe.ref, tx1.txid, 4)
      watcher ! WatchFundingConfirmed(probe.ref, tx1.txid, 4) // setting the watch multiple times should be a no-op
      watcher ! WatchFundingConfirmed(probe.ref, tx2.txid, 3)
      watcher ! WatchFundingConfirmed(probe.ref, tx2.txid, 6)
      probe.expectNoMessage(100 millis)

      watcher ! ListWatches(probe.ref)
      assert(probe.expectMsgType[Set[Watch[_]]].size == 4)

      generateBlocks(1)
      assert(probe.expectMsgType[WatchFundingConfirmedTriggered].tx.txid == tx1.txid)
      probe.expectNoMessage(100 millis)

      watcher ! ListWatches(probe.ref)
      assert(probe.expectMsgType[Set[Watch[_]]].size == 3)

      generateBlocks(2)
      assert(probe.expectMsgType[WatchFundingConfirmedTriggered].tx.txid == tx2.txid)
      probe.expectNoMessage(100 millis)

      watcher ! ListWatches(probe.ref)
      assert(probe.expectMsgType[Set[Watch[_]]].size == 2)

      watcher ! UnwatchTxConfirmed(tx2.txid)
      watcher ! ListWatches(probe.ref)
      assert(probe.expectMsgType[Set[Watch[_]]].size == 1)

      generateBlocks(1)
      assert(probe.expectMsgType[WatchFundingConfirmedTriggered].tx.txid == tx1.txid)
      probe.expectNoMessage(100 millis)

      watcher ! ListWatches(probe.ref)
      assert(probe.expectMsgType[Set[Watch[_]]].isEmpty)

      // If we try to watch a transaction that has already been confirmed, we should immediately receive a WatchConfirmedTriggered event.
      watcher ! WatchFundingConfirmed(probe.ref, tx1.txid, 1)
      assert(probe.expectMsgType[WatchFundingConfirmedTriggered].tx.txid == tx1.txid)
      watcher ! WatchFundingConfirmed(probe.ref, tx2.txid, 2)
      assert(probe.expectMsgType[WatchFundingConfirmedTriggered].tx.txid == tx2.txid)
      probe.expectNoMessage(100 millis)

      watcher ! ListWatches(probe.ref)
      assert(probe.expectMsgType[Set[Watch[_]]].isEmpty)
    })
  }

  test("watch for spent transactions") {
    withWatcher(f => {
      import f._

      val (priv, address) = createExternalAddress()
      val tx = sendToAddress(address, Btc(1), probe)
      val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
      val (tx1, tx2) = createUnspentTxChain(tx, priv)

      watcher ! WatchExternalChannelSpent(probe.ref, tx.txid, outputIndex, RealShortChannelId(5))
      watcher ! WatchFundingSpent(probe.ref, tx.txid, outputIndex, Set.empty)
      probe.expectNoMessage(100 millis)

      watcher ! ListWatches(probe.ref)
      assert(probe.expectMsgType[Set[Watch[_]]].size == 2)

      bitcoinClient.publishTransaction(tx1)
      // tx and tx1 aren't confirmed yet, but we trigger the WatchSpentTriggered event when we see tx1 in the mempool.
      probe.expectMsgAllOf(
        WatchExternalChannelSpentTriggered(RealShortChannelId(5), tx1),
        WatchFundingSpentTriggered(tx1)
      )
      // Let's confirm tx and tx1: seeing tx1 in a block should trigger both WatchSpentTriggered events again.
      bitcoinClient.getBlockHeight().pipeTo(probe.ref)
      val initialBlockHeight = probe.expectMsgType[BlockHeight]
      generateBlocks(1)
      probe.expectMsgAllOf(
        WatchExternalChannelSpentTriggered(RealShortChannelId(5), tx1),
        WatchFundingSpentTriggered(tx1)
      )
      probe.expectNoMessage(100 millis)

      watcher ! ListWatches(probe.ref)
      val watches1 = probe.expectMsgType[Set[Watch[_]]]
      assert(watches1.size == 2)
      assert(watches1.forall(_.isInstanceOf[WatchSpent[_]]))

      // Let's submit tx2, and set a watch after it has been confirmed this time.
      bitcoinClient.publishTransaction(tx2)
      probe.expectNoMessage(100 millis)

      system.eventStream.subscribe(probe.ref, classOf[CurrentBlockHeight])
      generateBlocks(1)
      awaitCond(probe.expectMsgType[CurrentBlockHeight].blockHeight >= initialBlockHeight + 2)

      watcher ! ListWatches(probe.ref)
      val watches2 = probe.expectMsgType[Set[Watch[_]]]
      assert(watches2.size == 2)
      assert(watches2.forall(_.isInstanceOf[WatchSpent[_]]))
      watcher ! StopWatching(probe.ref)

      // We use hints and see if we can find tx2
      watcher ! WatchFundingSpent(probe.ref, tx1.txid, 0, Set(tx2.txid))
      probe.expectMsg(WatchFundingSpentTriggered(tx2))
      watcher ! StopWatching(probe.ref)

      // We should still find tx2 if the provided hint is wrong
      watcher ! WatchOutputSpent(probe.ref, tx1.txid, 0, tx1.txOut(0).amount, Set(randomTxId()))
      probe.fishForMessage() { case m: WatchOutputSpentTriggered => m.spendingTx.txid == tx2.txid }
      watcher ! StopWatching(probe.ref)

      // We should find txs that have already been confirmed
      watcher ! WatchOutputSpent(probe.ref, tx.txid, outputIndex, tx.txOut(outputIndex).amount, Set.empty)
      probe.fishForMessage() { case m: WatchOutputSpentTriggered => m.spendingTx.txid == tx1.txid }
      watcher ! StopWatching(probe.ref)

      watcher ! WatchExternalChannelSpent(probe.ref, tx1.txid, 0, RealShortChannelId(1))
      probe.expectMsg(WatchExternalChannelSpentTriggered(RealShortChannelId(1), tx2))
      watcher ! StopWatching(probe.ref)
      watcher ! WatchFundingSpent(probe.ref, tx1.txid, 0, Set.empty)
      probe.expectMsg(WatchFundingSpentTriggered(tx2))
    })
  }

  test("unwatch external channel") {
    withWatcher(f => {
      import f._

      val sender = TestProbe()
      val (priv, address) = createExternalAddress()
      val tx1 = sendToAddress(address, 250_000 sat, probe)
      val outputIndex1 = tx1.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
      val spendingTx1 = createSpendP2WPKH(tx1, priv, priv.publicKey, 500 sat, 0, 0)
      val tx2 = sendToAddress(address, 200_000 sat, probe)
      val outputIndex2 = tx2.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
      val spendingTx2 = createSpendP2WPKH(tx2, priv, priv.publicKey, 500 sat, 0, 0)

      watcher ! WatchExternalChannelSpent(probe.ref, tx1.txid, outputIndex1, RealShortChannelId(3))
      watcher ! WatchExternalChannelSpent(probe.ref, tx2.txid, outputIndex2, RealShortChannelId(5))
      watcher ! UnwatchExternalChannelSpent(tx1.txid, outputIndex1 + 1) // ignored
      watcher ! UnwatchExternalChannelSpent(randomTxId(), outputIndex1) // ignored

      // When publishing the transaction, the watch triggers immediately.
      bitcoinClient.publishTransaction(spendingTx1).pipeTo(sender.ref)
      sender.expectMsg(spendingTx1.txid)
      probe.expectMsg(WatchExternalChannelSpentTriggered(RealShortChannelId(3), spendingTx1))
      probe.expectNoMessage(100 millis)

      // If we unwatch the transaction, we will ignore when it's published.
      watcher ! UnwatchExternalChannelSpent(tx2.txid, outputIndex2)
      bitcoinClient.publishTransaction(spendingTx2).pipeTo(sender.ref)
      sender.expectMsg(spendingTx2.txid)
      probe.expectNoMessage(100 millis)

      // If we watch again, this will trigger immediately because the transaction is in the mempool.
      watcher ! WatchExternalChannelSpent(probe.ref, tx2.txid, outputIndex2, RealShortChannelId(5))
      probe.expectMsg(WatchExternalChannelSpentTriggered(RealShortChannelId(5), spendingTx2))
      probe.expectNoMessage(100 millis)

      // We make the transactions confirm while we're not watching.
      watcher ! UnwatchExternalChannelSpent(tx1.txid, outputIndex1)
      watcher ! UnwatchExternalChannelSpent(tx2.txid, outputIndex2)
      bitcoinClient.getBlockHeight().pipeTo(sender.ref)
      val initialBlockHeight = sender.expectMsgType[BlockHeight]
      system.eventStream.subscribe(sender.ref, classOf[CurrentBlockHeight])
      generateBlocks(1)
      awaitCond(sender.expectMsgType[CurrentBlockHeight].blockHeight >= initialBlockHeight + 1)

      // If we watch again after confirmation, the watch instantly triggers.
      watcher ! WatchExternalChannelSpent(probe.ref, tx1.txid, outputIndex1, RealShortChannelId(3))
      probe.expectMsg(WatchExternalChannelSpentTriggered(RealShortChannelId(3), spendingTx1))
      probe.expectNoMessage(100 millis)
    })
  }

  test("watch for unknown spent transactions") {
    withWatcher(f => {
      import f._

      // create a chain of transactions that we don't broadcast yet
      val priv = randomKey()
      val tx1 = {
        bitcoinClient.fundTransaction(Transaction(2, Nil, TxOut(150000 sat, Script.pay2wpkh(priv.publicKey)) :: Nil, 0), FeeratePerKw(250 sat)).pipeTo(probe.ref)
        val funded = probe.expectMsgType[FundTransactionResponse].tx
        signTransaction(bitcoinClient, funded).pipeTo(probe.ref)
        probe.expectMsgType[SignTransactionResponse].tx
      }
      val outputIndex = tx1.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
      val tx2 = createSpendP2WPKH(tx1, priv, priv.publicKey, 10000 sat, 1, 0)

      // setup watches before we publish transactions
      watcher ! WatchFundingSpent(probe.ref, tx1.txid, outputIndex, Set.empty)
      watcher ! WatchFundingConfirmed(probe.ref, tx1.txid, 3)
      bitcoinClient.publishTransaction(tx1).pipeTo(probe.ref)
      probe.expectMsg(tx1.txid)
      generateBlocks(1)
      probe.expectNoMessage(100 millis)
      bitcoinClient.publishTransaction(tx2).pipeTo(probe.ref)
      probe.expectMsgAllOf(tx2.txid, WatchFundingSpentTriggered(tx2))
      probe.expectNoMessage(100 millis)
      generateBlocks(1)
      probe.expectMsg(WatchFundingSpentTriggered(tx2)) // tx2 is confirmed which triggers a WatchSpentTriggered event again
      generateBlocks(1)
      assert(probe.expectMsgType[WatchFundingConfirmedTriggered].tx == tx1) // tx1 now has 3 confirmations
    })
  }

  test("receive every transaction from new blocks through ZMQ") {
    val probe = TestProbe()
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[NewTransaction])

    // we receive txs when they enter the mempool
    val tx1 = sendToAddress(getNewAddress(probe), 50 millibtc, probe)
    listener.fishForMessage() { case m: NewTransaction => m.tx.txid == tx1.txid }
    val tx2 = sendToAddress(getNewAddress(probe), 25 millibtc, probe)
    listener.fishForMessage() { case m: NewTransaction => m.tx.txid == tx2.txid }

    // It may happen that transactions get included in a block without getting into our mempool first (e.g. a miner could
    // try to hide a revoked commit tx from the network until it gets confirmed, in an attempt to steal funds).
    // When we receive that block, we must send an event for every transaction inside it to analyze them and potentially
    // trigger `WatchSpent`.
    generateBlocks(1)
    val txs = Seq(
      listener.fishForMessage() { case m: NewTransaction => Set(tx1.txid, tx2.txid).contains(m.tx.txid) },
      listener.fishForMessage() { case m: NewTransaction => Set(tx1.txid, tx2.txid).contains(m.tx.txid) },
    ).map(_.asInstanceOf[NewTransaction].tx)
    assert(txs.toSet == Set(tx1, tx2))
  }

  test("scan transactions from previous blocks") {
    withWatcher(f => {
      import f._

      val (priv, address) = createExternalAddress()
      val tx = sendToAddress(address, Btc(1), probe)
      val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
      watcher ! WatchFundingSpent(probe.ref, tx.txid, outputIndex, Set.empty)
      probe.expectNoMessage(100 millis)

      // The watch triggers when we see the spending transaction in the mempool.
      val spendingTx = createSpendP2WPKH(tx, priv, priv.publicKey, 3_000 sat, 0xffffffffL, 0)
      bitcoinClient.publishTransaction(spendingTx)
      assert(probe.expectMsgType[WatchFundingSpentTriggered].spendingTx == spendingTx)

      // But we may not have received it in our mempool, and may discover it directly in a confirmed block.
      // We trigger the watch again when we receive the confirmed block.
      // Note that we trigger it twice, because we have two mechanisms for redundancy:
      //  - we should receive confirmed transactions through ZMQ, but this can be unreliable when using a remote bitcoind
      //  - when we receive a new block, we iterate through a few past blocks to analyze their transactions
      generateBlocks(3)
      assert(probe.expectMsgType[WatchFundingSpentTriggered].spendingTx == spendingTx)
      assert(probe.expectMsgType[WatchFundingSpentTriggered].spendingTx == spendingTx)
      probe.expectNoMessage(100 millis)

      // When receiving the next block, we ignore past blocks that we already analyzed to avoid needlessly firing watches.
      generateBlocks(1)
      probe.expectNoMessage(100 millis)
    }, scanPastBlock = true)
  }

  test("stop watching when requesting actor dies") {
    withWatcher(f => {
      import f._

      val actor1 = TestProbe()
      val actor2 = TestProbe()

      val txid = randomTxId()
      watcher ! WatchFundingConfirmed(actor1.ref, txid, 2)
      watcher ! WatchFundingConfirmed(actor1.ref, txid, 3)
      watcher ! WatchFundingConfirmed(actor1.ref, TxId(txid.value.reverse), 3)
      watcher ! WatchOutputSpent(actor1.ref, txid, 0, 0 sat, Set.empty)
      watcher ! WatchOutputSpent(actor1.ref, txid, 1, 0 sat, Set.empty)
      watcher ! ListWatches(actor1.ref)
      val watches1 = actor1.expectMsgType[Set[Watch[_]]]
      assert(watches1.size == 5)

      watcher ! WatchFundingConfirmed(actor2.ref, txid, 2)
      watcher ! WatchFundingConfirmed(actor2.ref, TxId(txid.value.reverse), 3)
      watcher ! WatchOutputSpent(actor2.ref, txid, 0, 0 sat, Set.empty)
      watcher ! WatchOutputSpent(actor2.ref, txid, 1, 0 sat, Set.empty)
      watcher ! ListWatches(actor2.ref)
      val watches2 = actor2.expectMsgType[Set[Watch[_]]]
      assert(watches2.size == 9)
      assert(watches1.forall(w => watches2.contains(w)))

      watcher ! StopWatching(actor2.ref)
      watcher ! ListWatches(actor1.ref)
      actor1.expectMsg(watches1)

      watcher ! StopWatching(actor1.ref)
      watcher ! ListWatches(actor1.ref)
      assert(actor1.expectMsgType[Set[Watch[_]]].isEmpty)
    })
  }

}