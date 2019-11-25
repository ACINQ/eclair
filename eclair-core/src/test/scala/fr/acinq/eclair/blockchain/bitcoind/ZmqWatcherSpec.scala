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

  test("zmq watcher should detect if a non wallet tx is being spent") {
    implicit val ec = system.dispatcher
    val probe = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(), bitcoinClient))

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

    Await.ready(bitcoinClient.importAddress(watchAddress), 30 seconds) // import the address manually
    addressImported = false // resetting the flag to perform the check later

    watcher ! WatchConfirmed(probe.ref, tx, minDepth = 6, BITCOIN_FUNDING_DEPTHOK, ShortChannelId.coordinates(shortId).blockHeight)

    generateBlocks(bitcoincli, 5)

    assert(!addressImported) // assert the watcher did not import the address
  }

  test("the watcher should handle rescans") {
    val probe = TestProbe()
    implicit val ec = system.dispatcher
    implicit val timeout = Timeout(10 seconds)

    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(), bitcoinClient))

    // tx and tx1 are unspent and unconfirmed non wallet transactions
    val (nonWalletTx, _) = ExternalWalletHelper.nonWalletTransaction(system)
    val (nonWalletTx1, _) = ExternalWalletHelper.nonWalletTransaction(system)
    val tx = ExternalWalletHelper.spendNonWalletTx(nonWalletTx)
    val tx1 = ExternalWalletHelper.spendNonWalletTx(nonWalletTx1, receivingKeyIndex = 21) // changing receiving key tweaks the address

    // broadcast the transactions
    bitcoinrpcclient.invoke("sendrawtransaction", Transaction.write(tx).toHex).pipeTo(probe.ref)
    probe.expectMsgType[JString]
    bitcoinrpcclient.invoke("sendrawtransaction", Transaction.write(tx1).toHex).pipeTo(probe.ref)
    probe.expectMsgType[JString]

    bitcoinClient.getBlockCount.pipeTo(probe.ref)
    val blockHeight = probe.expectMsgType[Long]

    // add the watcher for a non confirmed non wallet transaction "tx"
    watcher ! WatchConfirmed(probe.ref, tx, minDepth = 1, BITCOIN_FUNDING_DEPTHOK, blockHeight)
    watcher ! WatchConfirmed(probe.ref, tx1, minDepth = 1, BITCOIN_FUNDING_DEPTHOK, blockHeight)

    // generate a new block, this will contain the transactions
    val List(blockId) = generateBlocks(bitcoincli, 1)
    bitcoinrpcclient.invoke("getblock", blockId, 0).pipeTo(probe.ref)
    val block = Block.read(probe.expectMsgType[JString].s)

    // forward the new block event to the watcher
    watcher ! NewBlock(block)

    // assert the watcher detected the "tx" confirm
    probe.expectMsgType[WatchEventConfirmed]
    probe.expectMsgType[WatchEventConfirmed]

    val watches1 = Await.result((watcher ? 'watches).mapTo[Set[Watch]], 10 seconds)
    // assert the watch has been removed
    assert(watches1.isEmpty)

    // reset the imported addresses and repeat the test
    ExternalWalletHelper.swapWallet()

    // let's mine 100 + 1 blocks to get us some funds (used to generate non-wallet txs)
    generateBlocks(bitcoincli, 101)

    val (nonWalletTx2, _) = ExternalWalletHelper.nonWalletTransaction(system)
    val (nonWalletTx3, _) = ExternalWalletHelper.nonWalletTransaction(system)
    val tx2 = ExternalWalletHelper.spendNonWalletTx(nonWalletTx2, receivingKeyIndex = 22)
    val tx3 = ExternalWalletHelper.spendNonWalletTx(nonWalletTx3, receivingKeyIndex = 23)

    // broadcast the transactions
    bitcoinrpcclient.invoke("sendrawtransaction", Transaction.write(tx2).toHex).pipeTo(probe.ref)
    probe.expectMsgType[JString]
    bitcoinrpcclient.invoke("sendrawtransaction", Transaction.write(tx3).toHex).pipeTo(probe.ref)
    probe.expectMsgType[JString]

    bitcoinClient.getBlockCount.pipeTo(probe.ref)
    val blockHeight1 = probe.expectMsgType[Long]

    watcher ! WatchConfirmed(probe.ref, tx2, minDepth = 1, BITCOIN_FUNDING_DEPTHOK, blockHeight1)
    watcher ! WatchConfirmed(probe.ref, tx3, minDepth = 2, BITCOIN_FUNDING_DEPTHOK, blockHeight1) // we want to be notified of tx3 after 2 confirms!

    // mine a block and forward it to the watcher
    val List(blockId1) = generateBlocks(bitcoincli, 1)
    bitcoinrpcclient.invoke("getblock", blockId1, 0).pipeTo(probe.ref)
    val block1 = Block.read(probe.expectMsgType[JString].s)
    watcher ! NewBlock(block1)

    // assert the watcher acknowledged the confirm event
    val wec = probe.expectMsgType[WatchEventConfirmed]
    assert(wec.tx.txid == tx2.txid)

    // assert there is only one watch left to confirm
    val watches2 = Await.result((watcher ? 'watches).mapTo[Set[Watch]], 10 seconds)
    assert(watches2.size == 1)

    // generate another block to trigger the second WatchConfirmed
    val List(blockId2) = generateBlocks(bitcoincli, 1)
    bitcoinrpcclient.invoke("getblock", blockId2, 0).pipeTo(probe.ref)
    val block2 = Block.read(probe.expectMsgType[JString].s)
    watcher ! NewBlock(block2)

    val wec1 = probe.expectMsgType[WatchEventConfirmed]
    assert(wec1.tx.txid == tx3.txid)
  }

  test("the watcher should use the minimum rescan height of all the pending WatchConfirmed") {
    val probe = TestProbe()
    implicit val ec = system.dispatcher
    implicit val timeout = Timeout(10 seconds)
    var rescannedAt: Option[Long] = None

    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient) {
      override def rescanBlockChain(rescanSinceHeight: Long)(implicit ec: ExecutionContext): Future[Unit] = {
        rescannedAt = Some(rescanSinceHeight)
        super.rescanBlockChain(rescanSinceHeight)
      }
    }
    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(), bitcoinClient))

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
  }

}