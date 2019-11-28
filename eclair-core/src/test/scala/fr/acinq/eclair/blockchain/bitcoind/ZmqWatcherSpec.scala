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

import akka.actor.ActorSystem
import akka.testkit.{TestKit, TestProbe}
import akka.pattern._
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.{Block, OutPoint, Transaction}
import fr.acinq.eclair
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.TickInitialRescan
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.{NewBlock, Watch, WatchConfirmed, WatchEventConfirmed, WatchEventSpent, WatchSpent, WatchSpentBasic}
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, BITCOIN_FUNDING_SPENT, BITCOIN_TX_CONFIRMED, BitcoinEvent}
import fr.acinq.eclair.{ShortChannelId, randomBytes32}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.{JLong, JString}
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.{BeforeAndAfterAll, FunSuite, FunSuiteLike}

import scala.collection.JavaConversions._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._

class ZmqWatcherSpec extends TestKit(ActorSystem("test")) with BitcoindService with FunSuiteLike with BeforeAndAfterAll with Logging with IdiomaticMockito {

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

  test("wait bitcoind ready") {
    waitForBitcoindReady()
  }

  // TODO: extend to test watch spent with non-exsisting or non published txs
  test("zmq watcher should detect if a non wallet tx is being spent") {
    implicit val ec = system.dispatcher
    val probe = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(), bitcoinClient))
    watcher ! TickInitialRescan // force the watcher to transition to "watching"

    // tx is an unspent and confirmed non wallet transaction
    val (tx, _) = ExternalWalletHelper.nonWalletTransaction(system)
    val isSpendable = Await.result(bitcoinClient.isTransactionOutputSpendable(tx.txid.toHex, 0, false)(system.dispatcher), 10 seconds)
    assert(isSpendable)

    // now tx is spent by tx1
    val tx1 = ExternalWalletHelper.spendNonWalletTx(tx)(system)
    bitcoinrpcclient.invoke("sendrawtransaction", Transaction.write(tx1).toHex).pipeTo(probe.ref)
    probe.expectMsgType[JString]
    generateBlocks(bitcoincli, 1)
    val isSpendable1 = Await.result(bitcoinClient.isTransactionOutputSpendable(tx.txid.toHex, 0, false)(system.dispatcher), 10 seconds)
    assert(!isSpendable1)

    probe.send(watcher, WatchSpent(probe.ref, tx, 0, BITCOIN_FUNDING_SPENT))
    val ws = probe.expectMsgType[WatchEventSpent]
    assert(ws.tx.txid == tx1.txid)
  }

  test("add/remove watches from/to utxo map") {
    import ZmqWatcher._

    val m0 = Map.empty[OutPoint, Set[Watch]]

    val txid = randomBytes32
    val outputIndex = 42

    val utxo = OutPoint(txid.reverse, outputIndex)

    val w1 = WatchSpent(null, txid, outputIndex, randomBytes32, BITCOIN_FUNDING_SPENT)
    val w2 = WatchSpent(null, txid, outputIndex, randomBytes32, BITCOIN_FUNDING_SPENT)
    val w3 = WatchSpentBasic(null, txid, outputIndex, randomBytes32, BITCOIN_FUNDING_SPENT)
    val w4 = WatchSpentBasic(null, randomBytes32, 5, randomBytes32, BITCOIN_FUNDING_SPENT)
    val w5 = WatchConfirmed(null, txid, randomBytes32, 3, BITCOIN_FUNDING_SPENT, 0L)

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

  test("the watcher should not import addresses if they're already being watched") {
    val probe = TestProbe()
    implicit val ec = system.dispatcher

    // tx is an unspent and confirmed non wallet transaction
    val (tx, shortId) = ExternalWalletHelper.nonWalletTransaction(system)
    val watchAddress = eclair.scriptPubKeyToAddress(tx.txOut.head.publicKeyScript)

    var addressImported = false
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient) {
      override def importAddress(script: String)(implicit ec: ExecutionContext): Future[Unit] = {
        if(script == watchAddress) addressImported = true
        super.importAddress(script)
      }
    }

    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(), bitcoinClient))
    watcher ! TickInitialRescan // force the watcher to transition to "watching"


    Await.ready(bitcoinClient.importAddress(watchAddress), 30 seconds) // import the address manually
    addressImported = false // resetting the flag to perform the check later

    watcher ! WatchConfirmed(probe.ref, tx, minDepth = 6, BITCOIN_FUNDING_DEPTHOK, ShortChannelId.coordinates(shortId).blockHeight)

    generateBlocks(bitcoincli, 5)

    assert(!addressImported) // assert the watcher did not import the address
  }

  test("the watcher should use the minimum rescan height of all the pending WatchConfirmed") {
    val probe = TestProbe()
    implicit val ec = system.dispatcher
    implicit val timeout = Timeout(10 seconds)
    var numRescans = 0
    var rescannedAt: Option[Long] = None

    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient) {
      override def rescanBlockChain(rescanSinceHeight: Long)(implicit ec: ExecutionContext): Future[Unit] = {
        rescannedAt = Some(rescanSinceHeight)
        numRescans += 1
        super.rescanBlockChain(rescanSinceHeight)
      }
    }
    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(), bitcoinClient))
    watcher ! TickInitialRescan // force the watcher to transition to "watching"

    // mine a block and forward it to the watcher
    val List(blockId1) = generateBlocks(bitcoincli, 1)
    bitcoinrpcclient.invoke("getblock", blockId1, 0).pipeTo(probe.ref)
    val block1 = Block.read(probe.expectMsgType[JString].s)
    watcher ! NewBlock(block1)

    // if there is no WatchConfirmed we should not rescan
    assert(rescannedAt.isEmpty)

    // tx and tx1 are unspent and unconfirmed non wallet transactions
    val (nonWalletTx, _) = ExternalWalletHelper.nonWalletTransaction(system)
    val (nonWalletTx1, _) = ExternalWalletHelper.nonWalletTransaction(system)
    val tx = ExternalWalletHelper.spendNonWalletTx(nonWalletTx)
    val tx1 = ExternalWalletHelper.spendNonWalletTx(nonWalletTx1, receivingKeyIndex = 2) // changing receiving key tweaks the address

    // broadcast the first transaction
    bitcoinrpcclient.invoke("sendrawtransaction", Transaction.write(tx).toHex).pipeTo(probe.ref)
    probe.expectMsgType[JString]

    generateBlocks(bitcoincli, 1)

    // broadcast the second transaction, this will end up in a block after the first
    bitcoinrpcclient.invoke("sendrawtransaction", Transaction.write(tx1).toHex).pipeTo(probe.ref)
    probe.expectMsgType[JString]

    bitcoinClient.getBlockCount.pipeTo(probe.ref)
    val blockHeightTx1 = probe.expectMsgType[Long]
    val blockHeightTx = blockHeightTx1 - 1 // "tx" was included one block before "tx1", we should rescan from there

    // add the watcher for a non confirmed non wallet transaction "tx"
    watcher ! WatchConfirmed(probe.ref, tx1, minDepth = 1, BITCOIN_FUNDING_DEPTHOK, blockHeightTx1)
    watcher ! WatchConfirmed(probe.ref, tx, minDepth = 1, BITCOIN_FUNDING_DEPTHOK, blockHeightTx)

    // rescans are queued until a new block is found
    assert(rescannedAt.isEmpty)

    // generate a new block, this will contain the transactions
    val List(blockId) = generateBlocks(bitcoincli, 1)
    bitcoinrpcclient.invoke("getblock", blockId, 0).pipeTo(probe.ref)
    val block = Block.read(probe.expectMsgType[JString].s)

    // forward the new block event to the watcher
    watcher ! NewBlock(block)

    probe.expectMsgType[WatchEventConfirmed]
    probe.expectMsgType[WatchEventConfirmed]

    // assert the rescan used the earliest block height of all the watchers
    assert(rescannedAt.contains(Math.min(blockHeightTx, blockHeightTx1)))
    assert(numRescans == 1)
  }

  test("on startup the watcher should queue events until the scan is completed") {
    implicit val ec = system.dispatcher
    implicit val timeout = Timeout(30 seconds)
    val probe = TestProbe()
    var numRescans = 0
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient) {
      override def rescanBlockChain(rescanSinceHeight: Long)(implicit ec: ExecutionContext): Future[Unit] = {
        numRescans += 1
        super.rescanBlockChain(rescanSinceHeight)
      }
    }
    bitcoinClient.getBlockCount.pipeTo(probe.ref)
    val currentBlockHeight = probe.expectMsgType[Long]

    // create an external tx and let's be notified when it's spent
    val (nonWalletTx, _) = ExternalWalletHelper.nonWalletTransaction(system)
    val watchSpent = WatchSpent(probe.ref, nonWalletTx, 0, BITCOIN_FUNDING_SPENT)

    // tx spending the external one, we want to be notified by the watcher when it's confirmed
    val spendingTx = ExternalWalletHelper.spendNonWalletTx(nonWalletTx)
    bitcoinClient.publishTransaction(spendingTx).pipeTo(probe.ref)
    probe.expectMsgType[String]
    val watchConfirmed = WatchConfirmed(probe.ref, spendingTx, 1L, BITCOIN_TX_CONFIRMED(spendingTx), currentBlockHeight)

    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(currentBlockHeight), bitcoinClient))
    watcher ! watchSpent
    watcher ! watchConfirmed

    // generate a few blocks to enable an actual scan
    generateBlocks(bitcoincli, 2)

    // even if 'nonWalletTx' has been spent its 'WatchSpent' is on hold until we do the initial rescan
    // the event will be processed later
    probe.expectNoMsg(1 seconds)

    // at this point the watcher has a watch spent and a watch confirmed, until it receives
    // initial scan trigger it should not scan.
    assert(numRescans == 0)

    // trigger the initial rescan
    watcher ! TickInitialRescan

    // assert the rescan has been done
    awaitCond(numRescans == 1)

    // assert the watchers worked correctly
    probe.expectMsgType[WatchEventConfirmed] // 'spendingTx' has been confirmed
    probe.expectMsgType[WatchEventSpent] // 'nonWalletTx' has been spent
  }
}