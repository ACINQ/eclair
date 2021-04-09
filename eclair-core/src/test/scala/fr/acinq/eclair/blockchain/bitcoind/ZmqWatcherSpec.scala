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
import akka.actor.typed.{ActorRef => TypedActorRef}
import akka.actor.{ActorRef, Props}
import akka.pattern.pipe
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{Block, Btc, MilliBtcDouble, OutPoint, SatoshiLong, Script, Transaction, TxOut}
import fr.acinq.eclair.blockchain.{CurrentBlockCount, NewTransaction}
import fr.acinq.eclair.blockchain.WatcherSpec._
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient.{FundTransactionResponse, SignTransactionResponse}
import fr.acinq.eclair.blockchain.bitcoind.zmq.ZMQActor
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.{TestKitBaseClass, randomBytes32}
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
    zmqBlock = system.actorOf(Props(new ZMQActor(s"tcp://127.0.0.1:$bitcoindZmqBlockPort", Some(zmqBlockConnected))))
    zmqTx = system.actorOf(Props(new ZMQActor(s"tcp://127.0.0.1:$bitcoindZmqTxPort", Some(zmqTxConnected))))
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

  case class Fixture(blockCount: AtomicLong, bitcoinClient: ExtendedBitcoinClient, bitcoinWallet: BitcoinCoreWallet, watcher: TypedActorRef[ZmqWatcher.Command], probe: TestProbe, listener: TestProbe)

  // NB: we can't use ScalaTest's fixtures, they would see uninitialized bitcoind fields because they sandbox each test.
  private def withWatcher(testFun: Fixture => Any): Unit = {
    val blockCount = new AtomicLong()
    val probe = TestProbe()
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[CurrentBlockCount])
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    val bitcoinWallet = new BitcoinCoreWallet(bitcoinrpcclient)
    val watcher = system.spawn(ZmqWatcher(Block.RegtestGenesisBlock.hash, blockCount, bitcoinClient), UUID.randomUUID().toString)
    try {
      testFun(Fixture(blockCount, bitcoinClient, bitcoinWallet, watcher, probe, listener))
    } finally {
      system.stop(watcher.ref.toClassic)
    }
  }

  test("add/remove watches from/to utxo map") {
    val m0 = Map.empty[OutPoint, Set[Watch]]
    val txid = randomBytes32
    val outputIndex = 42
    val utxo = OutPoint(txid.reverse, outputIndex)

    val w1 = WatchSpent(TestProbe().ref, txid, outputIndex, BITCOIN_FUNDING_SPENT, hints = Set.empty)
    val w2 = WatchSpent(TestProbe().ref, txid, outputIndex, BITCOIN_FUNDING_SPENT, hints = Set.empty)
    val w3 = WatchSpentBasic(TestProbe().ref, txid, outputIndex, BITCOIN_FUNDING_SPENT)
    val w4 = WatchSpentBasic(TestProbe().ref, randomBytes32, 5, BITCOIN_FUNDING_SPENT)
    val w5 = WatchConfirmed(TestProbe().ref, txid, 3, BITCOIN_FUNDING_SPENT)

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
      val block1 = listener.expectMsgType[CurrentBlockCount]
      assert(blockCount.get() === block1.blockCount)
      listener.expectNoMessage(100 millis)

      generateBlocks(1)
      assert(listener.expectMsgType[CurrentBlockCount].blockCount === block1.blockCount + 1)
      assert(blockCount.get() === block1.blockCount + 1)
      listener.expectNoMessage(100 millis)

      generateBlocks(5)
      assert(listener.expectMsgType[CurrentBlockCount].blockCount === block1.blockCount + 6)
      assert(blockCount.get() === block1.blockCount + 6)
      listener.expectNoMessage(100 millis)
    })
  }

  test("watch for confirmed transactions") {
    withWatcher(f => {
      import f._

      val address = getNewAddress(probe)
      val tx = sendToAddress(address, Btc(1), probe)

      val listener = TestProbe()
      watcher ! WatchConfirmed(listener.ref, tx.txid, 1, BITCOIN_FUNDING_DEPTHOK)
      watcher ! WatchConfirmed(listener.ref, tx.txid, 4, BITCOIN_FUNDING_DEEPLYBURIED)
      watcher ! WatchConfirmed(listener.ref, tx.txid, 4, BITCOIN_FUNDING_DEEPLYBURIED) // setting the watch multiple times should be a no-op
      listener.expectNoMsg(1 second)

      watcher ! Watches(listener.ref)
      assert(listener.expectMsgType[Set[Watch]].size === 2)

      generateBlocks(1)
      val w1 = listener.expectMsgType[WatchEventConfirmed]
      assert(w1.tx.txid === tx.txid)
      assert(w1.event === BITCOIN_FUNDING_DEPTHOK)
      listener.expectNoMsg(1 second)

      watcher ! Watches(listener.ref)
      assert(listener.expectMsgType[Set[Watch]].size === 1)

      generateBlocks(3)
      val w2 = listener.expectMsgType[WatchEventConfirmed]
      assert(w2.tx.txid === tx.txid)
      assert(w2.event === BITCOIN_FUNDING_DEEPLYBURIED)
      listener.expectNoMsg(1 second)

      watcher ! Watches(listener.ref)
      assert(listener.expectMsgType[Set[Watch]].isEmpty)

      // If we try to watch a transaction that has already been confirmed, we should immediately receive a WatchEventConfirmed.
      watcher ! WatchConfirmed(listener.ref, tx.txid, 1, BITCOIN_FUNDING_DEPTHOK)
      assert(listener.expectMsgType[WatchEventConfirmed].tx.txid === tx.txid)
      watcher ! WatchConfirmed(listener.ref, tx.txid, 2, BITCOIN_FUNDING_DEPTHOK)
      assert(listener.expectMsgType[WatchEventConfirmed].tx.txid === tx.txid)
      watcher ! WatchConfirmed(listener.ref, tx.txid, 4, BITCOIN_FUNDING_DEEPLYBURIED)
      assert(listener.expectMsgType[WatchEventConfirmed].tx.txid === tx.txid)
      listener.expectNoMsg(1 second)

      watcher ! Watches(listener.ref)
      assert(listener.expectMsgType[Set[Watch]].isEmpty)
    })
  }

  test("watch for spent transactions") {
    withWatcher(f => {
      import f._

      val address = getNewAddress(probe)
      val priv = dumpPrivateKey(address, probe)
      val tx = sendToAddress(address, Btc(1), probe)
      val outputIndex = tx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
      val (tx1, tx2) = createUnspentTxChain(tx, priv)

      val listener = TestProbe()
      watcher ! WatchSpentBasic(listener.ref, tx.txid, outputIndex, BITCOIN_FUNDING_SPENT)
      watcher ! WatchSpent(listener.ref, tx.txid, outputIndex, BITCOIN_FUNDING_SPENT, hints = Set.empty)
      listener.expectNoMsg(1 second)

      watcher ! Watches(listener.ref)
      assert(listener.expectMsgType[Set[Watch]].size === 2)

      bitcoinClient.publishTransaction(tx1).pipeTo(probe.ref)
      probe.expectMsg(tx1.txid)
      // tx and tx1 aren't confirmed yet, but we trigger the WatchEventSpent when we see tx1 in the mempool.
      listener.expectMsgAllOf(
        WatchEventSpentBasic(BITCOIN_FUNDING_SPENT),
        WatchEventSpent(BITCOIN_FUNDING_SPENT, tx1)
      )
      // Let's confirm tx and tx1: seeing tx1 in a block should trigger WatchEventSpent again, but not WatchEventSpentBasic
      // (which only triggers once).
      generateBlocks(2)
      listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx1))

      watcher ! Watches(listener.ref)
      val watches1 = listener.expectMsgType[Set[Watch]]
      assert(watches1.size === 1)
      assert(watches1.forall(_.isInstanceOf[WatchSpent]))

      // Let's submit tx2, and set a watch after it has been confirmed this time.
      bitcoinClient.publishTransaction(tx2).pipeTo(probe.ref)
      probe.expectMsg(tx2.txid)
      listener.expectNoMessage(1 second)
      system.eventStream.subscribe(probe.ref, classOf[CurrentBlockCount])
      generateBlocks(1)
      probe.expectMsgType[CurrentBlockCount]
      watcher ! WatchSpentBasic(listener.ref, tx1.txid, 0, BITCOIN_FUNDING_SPENT)
      listener.expectMsg(WatchEventSpentBasic(BITCOIN_FUNDING_SPENT))
      watcher ! WatchSpent(listener.ref, tx1.txid, 0, BITCOIN_FUNDING_SPENT, hints = Set.empty)
      listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2))
      listener.expectNoMessage(1 second)

      watcher ! Watches(listener.ref)
      val watches2 = listener.expectMsgType[Set[Watch]]
      assert(watches2.size === 2)
      assert(watches2.forall(_.isInstanceOf[WatchSpent]))
      watcher ! StopWatching(listener.ref)

      // We use hints and see if we can find tx2
      watcher ! WatchSpent(listener.ref, tx1.txid, 0, BITCOIN_FUNDING_SPENT, hints = Set(tx2.txid))
      listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2))
      watcher ! StopWatching(listener.ref)

      // We should still find tx2 if the provided hint is wrong
      watcher ! WatchSpent(listener.ref, tx1.txid, 0, BITCOIN_OUTPUT_SPENT, hints = Set(randomBytes32))
      listener.expectMsg(WatchEventSpent(BITCOIN_OUTPUT_SPENT, tx2))
      watcher ! StopWatching(listener.ref)

      // We should find txs that have already been confirmed
      watcher ! WatchSpent(listener.ref, tx.txid, outputIndex, BITCOIN_OUTPUT_SPENT, hints = Set.empty)
      listener.expectMsg(WatchEventSpent(BITCOIN_OUTPUT_SPENT, tx1))
    })
  }

  test("watch for unknown spent transactions") {
    withWatcher(f => {
      import f._

      // create a chain of transactions that we don't broadcast yet
      val priv = dumpPrivateKey(getNewAddress(probe), probe)
      val tx1 = {
        bitcoinWallet.fundTransaction(Transaction(2, Nil, TxOut(150000 sat, Script.pay2wpkh(priv.publicKey)) :: Nil, 0), lockUtxos = true, FeeratePerKw(250 sat)).pipeTo(probe.ref)
        val funded = probe.expectMsgType[FundTransactionResponse].tx
        bitcoinWallet.signTransaction(funded).pipeTo(probe.ref)
        probe.expectMsgType[SignTransactionResponse].tx
      }
      val outputIndex = tx1.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
      val tx2 = createSpendP2WPKH(tx1, priv, priv.publicKey, 10000 sat, 1, 0)

      // setup watches before we publish transactions
      watcher ! WatchSpent(probe.ref, tx1.txid, outputIndex, BITCOIN_FUNDING_SPENT, hints = Set.empty)
      watcher ! WatchConfirmed(probe.ref, tx1.txid, 3, BITCOIN_FUNDING_SPENT)
      bitcoinClient.publishTransaction(tx1).pipeTo(probe.ref)
      probe.expectMsg(tx1.txid)
      generateBlocks(1)
      probe.expectNoMsg(1 second)
      bitcoinClient.publishTransaction(tx2).pipeTo(probe.ref)
      probe.expectMsgAllOf(tx2.txid, WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2))
      probe.expectNoMsg(1 second)
      generateBlocks(1)
      probe.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2)) // tx2 is confirmed which triggers WatchEventSpent again
      generateBlocks(1)
      assert(probe.expectMsgType[WatchEventConfirmed].tx === tx1) // tx1 now has 3 confirmations
    })
  }

  test("receive every transaction from new blocks through ZMQ") {
    val probe = TestProbe()
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[NewTransaction])

    // we receive txs when they enter the mempool
    val tx1 = sendToAddress(getNewAddress(probe), 50 millibtc, probe)
    listener.expectMsg(NewTransaction(tx1))
    val tx2 = sendToAddress(getNewAddress(probe), 25 millibtc, probe)
    listener.expectMsg(NewTransaction(tx2))
    listener.expectNoMessage(100 millis)

    // It may happen that transactions get included in a block without getting into our mempool first (e.g. a miner could
    // try to hide a revoked commit tx from the network until it gets confirmed, in an attempt to steal funds).
    // When we receive that block, we must send an event for every transaction inside it to analyze them and potentially
    // trigger `WatchSpent` / `WatchSpentBasic`.
    generateBlocks(1)
    // NB: a miner coinbase transaction is also included in the block
    val txs = Set(
      listener.expectMsgType[NewTransaction],
      listener.expectMsgType[NewTransaction],
      listener.expectMsgType[NewTransaction]
    ).map(_.tx)
    listener.expectNoMessage(100 millis)
    assert(txs.contains(tx1))
    assert(txs.contains(tx2))
  }

  test("stop watching when requesting actor dies") {
    withWatcher(f => {
      import f._

      val actor1 = TestProbe()
      val actor2 = TestProbe()

      val txid = randomBytes32
      watcher ! WatchConfirmed(actor1.ref, txid, 2, BITCOIN_FUNDING_DEPTHOK)
      watcher ! WatchConfirmed(actor1.ref, txid, 3, BITCOIN_FUNDING_DEPTHOK)
      watcher ! WatchConfirmed(actor1.ref, txid, 3, BITCOIN_FUNDING_DEEPLYBURIED)
      watcher ! WatchConfirmed(actor1.ref, txid.reverse, 3, BITCOIN_FUNDING_DEPTHOK)
      watcher ! WatchSpent(actor1.ref, txid, 0, BITCOIN_OUTPUT_SPENT, Set.empty)
      watcher ! WatchSpent(actor1.ref, txid, 1, BITCOIN_OUTPUT_SPENT, Set.empty)
      watcher ! Watches(actor1.ref)
      val watches1 = actor1.expectMsgType[Set[Watch]]
      assert(watches1.size === 6)

      watcher ! WatchConfirmed(actor2.ref, txid, 2, BITCOIN_FUNDING_DEPTHOK)
      watcher ! WatchConfirmed(actor2.ref, txid, 3, BITCOIN_FUNDING_DEEPLYBURIED)
      watcher ! WatchConfirmed(actor2.ref, txid.reverse, 3, BITCOIN_FUNDING_DEPTHOK)
      watcher ! WatchSpent(actor2.ref, txid, 0, BITCOIN_OUTPUT_SPENT, Set.empty)
      watcher ! WatchSpent(actor2.ref, txid, 1, BITCOIN_OUTPUT_SPENT, Set.empty)
      watcher ! Watches(actor2.ref)
      val watches2 = actor2.expectMsgType[Set[Watch]]
      assert(watches2.size === 11)
      assert(watches1.forall(w => watches2.contains(w)))

      watcher ! StopWatching(actor2.ref)
      watcher ! Watches(actor1.ref)
      actor1.expectMsg(watches1)

      watcher ! StopWatching(actor1.ref)
      watcher ! Watches(actor1.ref)
      assert(actor1.expectMsgType[Set[Watch]].isEmpty)
    })
  }

}