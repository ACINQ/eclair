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

import java.util.concurrent.atomic.AtomicLong

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import akka.pattern._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, OutPoint, Transaction}
import fr.acinq.eclair
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.TickInitialRescan
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.{NewBlock, NewTransaction, RescanFrom, Watch, WatchConfirmed, WatchEvent, WatchEventConfirmed, WatchEventSpent, WatchEventSpentBasic, WatchSpent, WatchSpentBasic}
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, BITCOIN_FUNDING_SPENT, BITCOIN_TX_CONFIRMED, BitcoinEvent}
import fr.acinq.eclair.{ShortChannelId, randomBytes32}
import grizzled.slf4j.Logging
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.{BeforeAndAfterAll, Outcome, fixture}

import scala.collection.JavaConversions._
import scala.compat.Platform
import scala.concurrent.duration._

class ZmqWatcherSpec extends TestKit(ActorSystem("test")) with BitcoindService with fixture.FunSuiteLike with BeforeAndAfterAll with Logging with IdiomaticMockito {

  implicit val ec = system.dispatcher

  case class FixtureParam(probe: TestProbe, receiveKey: PrivateKey, sendKey: PrivateKey, bitcoinClient: ExtendedBitcoinClient)

  override def withFixture(test: OneArgTest): Outcome = {
    val probe = TestProbe()
    val bitcoinClient = spy(new ExtendedBitcoinClient(bitcoinrpcclient))
    val (receiveKey, sendKey) = (PrivateKey(randomBytes32), PrivateKey(randomBytes32))

    withFixture(test.toNoArgTest(FixtureParam(probe, receiveKey, sendKey, bitcoinClient)))
  }

  val commonConfig = ConfigFactory.parseMap(Map(
    "eclair.chain" -> "regtest",
    "eclair.spv" -> false,
    "eclair.server.public-ips.1" -> "localhost",
    "eclair.bitcoind.port" -> bitcoindPort,
    "eclair.bitcoind.rpcport" -> bitcoindRpcPort,
    "eclair.router-broadcast-interval" -> "2 second",
    "eclair.auto-reconnect" -> false))

  val config = ConfigFactory.load(commonConfig).getConfig("eclair")

  override def beforeAll(): Unit = {
    startBitcoind()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  test("wait bitcoind ready") { f =>
    waitForBitcoindReady()
  }

  test("watcher should trigger watch spent event when it receives the spending transaction") { f =>
    import f._

    bitcoinClient.getBlockCount.pipeTo(probe.ref)
    val currentHeight = probe.expectMsgType[Long]

    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(currentHeight), bitcoinClient))
    watcher ! TickInitialRescan // force the watcher to transition to "watching"

    // tx spends from our wallet to an external address, NOT YET PUBLISHED
    val tx = ExternalWalletHelper.nonWalletTransaction(receiveKey, sendKey)(system)

    // we now add the watch 'tx' is not yet published
    watcher ! WatchSpent(probe.ref, tx, 0, BITCOIN_FUNDING_SPENT, RescanFrom(rescanHeight = Some(currentHeight)))

    // the watcher will acknowledge the watch only after it's done importing its address
    awaitCond({
      probe.send(watcher, 'watches)
      probe.expectMsgType[Set[Watch]].size == 1
    })

    // now let's spend 'tx'
    val tx1 = ExternalWalletHelper.spendNonWalletTx(tx, receiveKey, sendKey)(system)

    // forward the spending transaction to the watcher
    watcher ! NewTransaction(tx1)

    val ws = probe.expectMsgType[WatchEventSpent]
    assert(ws.tx.txid == tx1.txid)
  }

  test("watcher should trigger watch spent event if the transaction has already been spent in a block") { f =>
    import f._

    bitcoinClient.getBlockCount.pipeTo(probe.ref)
    val currentHeight = probe.expectMsgType[Long]

    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(currentHeight), bitcoinClient))
    watcher ! TickInitialRescan // force the watcher to transition to "watching"

    // tx spends from our wallet to an external address
    val tx = ExternalWalletHelper.nonWalletTransaction(receiveKey, sendKey)(system)
    // tx1 spends tx
    val tx1 = ExternalWalletHelper.spendNonWalletTx(tx, receiveKey, sendKey)(system)

    // publish both
    bitcoinClient.publishTransaction(tx).pipeTo(probe.ref)
    bitcoinClient.publishTransaction(tx1).pipeTo(probe.ref)
    probe.expectMsgType[String]
    probe.expectMsgType[String]

    // mine them in a few blocks
    generateBlocks(bitcoincli, 2)

    bitcoinClient.isTransactionOutputSpendable(tx.txid.toHex, 0, false).pipeTo(probe.ref)
    assert(!probe.expectMsgType[Boolean])

    // we now add the watch, it should fire immediately
    watcher ! WatchSpent(probe.ref, tx, 0, BITCOIN_FUNDING_SPENT, RescanFrom(rescanHeight = Some(currentHeight)))
    assert(probe.expectMsgType[WatchEventSpent].tx.txid == tx1.txid)
  }


  test("add/remove watches from/to utxo map") { f =>
    import ZmqWatcher._

    val m0 = Map.empty[OutPoint, Set[Watch]]

    val txid = randomBytes32
    val outputIndex = 42

    val utxo = OutPoint(txid.reverse, outputIndex)

    val w1 = WatchSpent(null, txid, outputIndex, randomBytes32, BITCOIN_FUNDING_SPENT, RescanFrom(rescanTimestamp = Some(Platform.currentTime.millisecond.toSeconds)))
    val w2 = WatchSpent(null, txid, outputIndex, randomBytes32, BITCOIN_FUNDING_SPENT, RescanFrom(rescanTimestamp = Some(Platform.currentTime.millisecond.toSeconds)))
    val w3 = WatchSpentBasic(null, txid, outputIndex, randomBytes32, BITCOIN_FUNDING_SPENT,RescanFrom(rescanTimestamp = Some(Platform.currentTime.millisecond.toSeconds)))
    val w4 = WatchSpentBasic(null, randomBytes32, 5, randomBytes32, BITCOIN_FUNDING_SPENT,RescanFrom(rescanTimestamp = Some(Platform.currentTime.millisecond.toSeconds)))
    val w5 = WatchConfirmed(null, txid, randomBytes32, 3, BITCOIN_FUNDING_SPENT, RescanFrom(rescanTimestamp = Some(Platform.currentTime.millisecond.toSeconds)))

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

  test("on startup the watcher should queue events until the scan is completed") { f =>
    import f._

    bitcoinClient.getBlockCount.pipeTo(probe.ref)
    val currentBlockHeight = probe.expectMsgType[Long]

    // create an external tx and let's be notified when it's spent
    val nonWalletTx = ExternalWalletHelper.nonWalletTransaction(receiveKey, sendKey)(system)
    // tx spending the external one, we want to be notified by the watcher when it's confirmed
    val spendingTx = ExternalWalletHelper.spendNonWalletTx(nonWalletTx, receiveKey, sendKey)

    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(currentBlockHeight), bitcoinClient))
    watcher ! WatchConfirmed(probe.ref, nonWalletTx, 1L, BITCOIN_TX_CONFIRMED(nonWalletTx), RescanFrom(rescanHeight = Some(currentBlockHeight)))
    watcher ! WatchConfirmed(probe.ref, spendingTx, 1L, BITCOIN_TX_CONFIRMED(spendingTx), RescanFrom(rescanHeight = Some(currentBlockHeight)))
    watcher ! WatchSpent(probe.ref, nonWalletTx, 0, BITCOIN_FUNDING_SPENT, RescanFrom(rescanHeight = Some(currentBlockHeight)))

    // assert we haven't imported anything yet
    bitcoinClient.importMulti(any, any).wasNever(called)

    bitcoinClient.publishTransaction(nonWalletTx).pipeTo(probe.ref)
    probe.expectMsgType[String]

    bitcoinClient.publishTransaction(spendingTx).pipeTo(probe.ref)
    probe.expectMsgType[String]

    // generate a few blocks to bury the transactions
    generateBlocks(bitcoincli, 2)

    // even if 'nonWalletTx' has been spent its 'WatchSpent' is on hold until we do the initial rescan
    probe.expectNoMsg(1 seconds)

    // trigger the initial rescan
    watcher ! TickInitialRescan

    // assert the watchers worked correctly
    val we1 = probe.expectMsgType[WatchEvent] // 'nonWalletTx' has been confirmed
    val we2 = probe.expectMsgType[WatchEvent] // 'spendingTx' has been confirmed
    val we3 = probe.expectMsgType[WatchEvent] // 'nonWalletTx' has been spent
    assert(Set(we1, we2, we3).collect { case w: WatchEventSpent => w }.size == 1)
    assert(Set(we1, we2, we3).collect { case w: WatchEventConfirmed => w }.size == 2)

    // assert we imported exactly once
    bitcoinClient.importMulti(any, any).wasCalled(once)
  }
}