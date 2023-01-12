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
import fr.acinq.bitcoin.scalacompat.{Block, Btc, MilliBtcDouble, OutPoint, SatoshiLong, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.OnChainWallet.{FundTransactionResponse, MakeFundingTxResponse, SignTransactionResponse}
import fr.acinq.eclair.blockchain.WatcherSpec._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient.FundTransactionOptions
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, NewTransaction}
import fr.acinq.eclair.{BlockHeight, RealShortChannelId, TestConstants, TestKitBaseClass, randomBytes32, randomKey}
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
  private def withWatcher(testFun: Fixture => Any): Unit = {
    val blockCount = new AtomicLong()
    val probe = TestProbe()
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[CurrentBlockHeight])
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    val nodeParams = TestConstants.Alice.nodeParams.copy(chainHash = Block.RegtestGenesisBlock.hash)
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
    val txid = randomBytes32()
    val outputIndex = 42
    val utxo = OutPoint(txid.reverse, outputIndex)

    val w1 = WatchFundingSpent(TestProbe().ref, txid, outputIndex, hints = Set.empty)
    val w2 = WatchFundingSpent(TestProbe().ref, txid, outputIndex, hints = Set.empty)
    val w3 = WatchExternalChannelSpent(TestProbe().ref, txid, outputIndex, RealShortChannelId(1))
    val w4 = WatchExternalChannelSpent(TestProbe().ref, randomBytes32(), 5, RealShortChannelId(1))
    val w5 = WatchFundingConfirmed(TestProbe().ref, txid, 3)

    // we test as if the collection was immutable
    val m1 = addWatchedUtxos(m0, w1)
    assert(m1.keySet == Set(utxo) && m1.size == 1)
    val m2 = addWatchedUtxos(m1, w2)
    assert(m2.keySet == Set(utxo) && m2(utxo).size == 2)
    val m3 = addWatchedUtxos(m2, w3)
    assert(m3.keySet == Set(utxo) && m3(utxo).size == 3)
    val m4 = addWatchedUtxos(m3, w4)
    assert(m4.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m3(utxo).size == 3)
    val m5 = addWatchedUtxos(m4, w5)
    assert(m5.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m5(utxo).size == 3)
    val m6 = removeWatchedUtxos(m5, w3)
    assert(m6.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m6(utxo).size == 2)
    val m7 = removeWatchedUtxos(m6, w3)
    assert(m7.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m7(utxo).size == 2)
    val m8 = removeWatchedUtxos(m7, w2)
    assert(m8.keySet == Set(utxo, OutPoint(w4.txId.reverse, w4.outputIndex)) && m8(utxo).size == 1)
    val m9 = removeWatchedUtxos(m8, w1)
    assert(m9.keySet == Set(OutPoint(w4.txId.reverse, w4.outputIndex)))
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
      val tx = sendToAddress(address, Btc(1), probe)

      watcher ! WatchFundingConfirmed(probe.ref, tx.txid, 1)
      watcher ! WatchFundingDeeplyBuried(probe.ref, tx.txid, 4)
      watcher ! WatchFundingDeeplyBuried(probe.ref, tx.txid, 4) // setting the watch multiple times should be a no-op
      probe.expectNoMessage(100 millis)

      watcher ! ListWatches(probe.ref)
      assert(probe.expectMsgType[Set[Watch[_]]].size == 2)

      generateBlocks(1)
      assert(probe.expectMsgType[WatchFundingConfirmedTriggered].tx.txid == tx.txid)
      probe.expectNoMessage(100 millis)

      watcher ! ListWatches(probe.ref)
      assert(probe.expectMsgType[Set[Watch[_]]].size == 1)

      generateBlocks(3)
      assert(probe.expectMsgType[WatchFundingDeeplyBuriedTriggered].tx.txid == tx.txid)
      probe.expectNoMessage(100 millis)

      watcher ! ListWatches(probe.ref)
      assert(probe.expectMsgType[Set[Watch[_]]].isEmpty)

      // If we try to watch a transaction that has already been confirmed, we should immediately receive a WatchEventConfirmed.
      watcher ! WatchFundingConfirmed(probe.ref, tx.txid, 1)
      assert(probe.expectMsgType[WatchFundingConfirmedTriggered].tx.txid == tx.txid)
      watcher ! WatchFundingConfirmed(probe.ref, tx.txid, 2)
      assert(probe.expectMsgType[WatchFundingConfirmedTriggered].tx.txid == tx.txid)
      watcher ! WatchFundingDeeplyBuried(probe.ref, tx.txid, 4)
      assert(probe.expectMsgType[WatchFundingDeeplyBuriedTriggered].tx.txid == tx.txid)
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
      // tx and tx1 aren't confirmed yet, but we trigger the WatchEventSpent when we see tx1 in the mempool.
      probe.expectMsgAllOf(
        WatchExternalChannelSpentTriggered(RealShortChannelId(5)),
        WatchFundingSpentTriggered(tx1)
      )
      // Let's confirm tx and tx1: seeing tx1 in a block should trigger WatchEventSpent again, but not WatchEventSpentBasic
      // (which only triggers once).
      bitcoinClient.getBlockHeight().pipeTo(probe.ref)
      val initialBlockHeight = probe.expectMsgType[BlockHeight]
      generateBlocks(1)
      probe.expectMsg(WatchFundingSpentTriggered(tx1))
      probe.expectNoMessage(100 millis)

      watcher ! ListWatches(probe.ref)
      val watches1 = probe.expectMsgType[Set[Watch[_]]]
      assert(watches1.size == 1)
      assert(watches1.forall(_.isInstanceOf[WatchFundingSpent]))

      // Let's submit tx2, and set a watch after it has been confirmed this time.
      bitcoinClient.publishTransaction(tx2)
      probe.expectNoMessage(100 millis)

      system.eventStream.subscribe(probe.ref, classOf[CurrentBlockHeight])
      generateBlocks(1)
      awaitCond(probe.expectMsgType[CurrentBlockHeight].blockHeight >= initialBlockHeight + 2)

      watcher ! ListWatches(probe.ref)
      val watches2 = probe.expectMsgType[Set[Watch[_]]]
      assert(watches2.size == 1)
      assert(watches2.forall(_.isInstanceOf[WatchFundingSpent]))
      watcher ! StopWatching(probe.ref)

      // We use hints and see if we can find tx2
      watcher ! WatchFundingSpent(probe.ref, tx1.txid, 0, Set(tx2.txid))
      probe.expectMsg(WatchFundingSpentTriggered(tx2))
      watcher ! StopWatching(probe.ref)

      // We should still find tx2 if the provided hint is wrong
      watcher ! WatchOutputSpent(probe.ref, tx1.txid, 0, Set(randomBytes32()))
      probe.fishForMessage() { case m: WatchOutputSpentTriggered => m.spendingTx.txid == tx2.txid }
      watcher ! StopWatching(probe.ref)

      // We should find txs that have already been confirmed
      watcher ! WatchOutputSpent(probe.ref, tx.txid, outputIndex, Set.empty)
      probe.fishForMessage() { case m: WatchOutputSpentTriggered => m.spendingTx.txid == tx1.txid }
      watcher ! StopWatching(probe.ref)

      watcher ! WatchExternalChannelSpent(probe.ref, tx1.txid, 0, RealShortChannelId(1))
      probe.expectMsg(WatchExternalChannelSpentTriggered(RealShortChannelId(1)))
      watcher ! WatchFundingSpent(probe.ref, tx1.txid, 0, Set.empty)
      probe.expectMsg(WatchFundingSpentTriggered(tx2))
    })
  }

  test("watch for unknown spent transactions") {
    withWatcher(f => {
      import f._

      // create a chain of transactions that we don't broadcast yet
      val priv = randomKey()
      val tx1 = {
        bitcoinClient.fundTransaction(Transaction(2, Nil, TxOut(150000 sat, Script.pay2wpkh(priv.publicKey)) :: Nil, 0), FundTransactionOptions(FeeratePerKw(250 sat))).pipeTo(probe.ref)
        val funded = probe.expectMsgType[FundTransactionResponse].tx
        bitcoinClient.signTransaction(funded).pipeTo(probe.ref)
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
      probe.expectMsg(WatchFundingSpentTriggered(tx2)) // tx2 is confirmed which triggers WatchEventSpent again
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
    // trigger `WatchSpent` / `WatchSpentBasic`.
    generateBlocks(1)
    val txs = Seq(
      listener.fishForMessage() { case m: NewTransaction => Set(tx1.txid, tx2.txid).contains(m.tx.txid) },
      listener.fishForMessage() { case m: NewTransaction => Set(tx1.txid, tx2.txid).contains(m.tx.txid) },
    ).map(_.asInstanceOf[NewTransaction].tx)
    assert(txs.toSet == Set(tx1, tx2))
  }

  test("stop watching when requesting actor dies") {
    withWatcher(f => {
      import f._

      val actor1 = TestProbe()
      val actor2 = TestProbe()

      val txid = randomBytes32()
      watcher ! WatchFundingConfirmed(actor1.ref, txid, 2)
      watcher ! WatchFundingConfirmed(actor1.ref, txid, 3)
      watcher ! WatchFundingDeeplyBuried(actor1.ref, txid, 3)
      watcher ! WatchFundingConfirmed(actor1.ref, txid.reverse, 3)
      watcher ! WatchOutputSpent(actor1.ref, txid, 0, Set.empty)
      watcher ! WatchOutputSpent(actor1.ref, txid, 1, Set.empty)
      watcher ! ListWatches(actor1.ref)
      val watches1 = actor1.expectMsgType[Set[Watch[_]]]
      assert(watches1.size == 6)

      watcher ! WatchFundingConfirmed(actor2.ref, txid, 2)
      watcher ! WatchFundingDeeplyBuried(actor2.ref, txid, 3)
      watcher ! WatchFundingConfirmed(actor2.ref, txid.reverse, 3)
      watcher ! WatchOutputSpent(actor2.ref, txid, 0, Set.empty)
      watcher ! WatchOutputSpent(actor2.ref, txid, 1, Set.empty)
      watcher ! ListWatches(actor2.ref)
      val watches2 = actor2.expectMsgType[Set[Watch[_]]]
      assert(watches2.size == 11)
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