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
import akka.pattern.pipe
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.OutPoint
import fr.acinq.eclair
import fr.acinq.eclair.randomBytes32
import fr.acinq.eclair.blockchain.{NewTransaction, Watch, WatchAddressItem, WatchConfirmed, WatchEventSpent, WatchSpent, WatchSpentBasic}
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.channel.BITCOIN_FUNDING_SPENT
import grizzled.slf4j.Logging
import org.mockito.scalatest.IdiomaticMockito
import org.scalatest.{BeforeAndAfterAll, FunSuiteLike}

import scala.collection.JavaConversions._

class ZmqWatcherSpec extends TestKit(ActorSystem("test")) with BitcoindService with FunSuiteLike with BeforeAndAfterAll with Logging with IdiomaticMockito {

  implicit val ec = system.dispatcher

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

  test("watcher should trigger watch spent event when it receives the spending transaction") {
    val probe = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    val (receiveKey, sendKey) = (PrivateKey(randomBytes32), PrivateKey(randomBytes32))

    bitcoinClient.getBlockCount.pipeTo(probe.ref)
    val currentHeight = probe.expectMsgType[Long]

    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(currentHeight), bitcoinClient))

    // tx spends from our wallet to an external address, NOT YET PUBLISHED
    val tx = ExternalWalletHelper.nonWalletTransaction(receiveKey, sendKey)(system)
    val address = eclair.scriptPubKeyToAddress(tx.txOut.head.publicKeyScript)

    // we now add the watch 'tx' is not yet published
    watcher ! WatchSpent(probe.ref, tx, 0, BITCOIN_FUNDING_SPENT)

    // the watcher will acknowledge the watch only after it's done importing its address
    awaitCond({
      probe.send(watcher, 'watches)
      probe.expectMsgType[Set[Watch]].size == 1
    })

    // assert the imported watch becomes a watch-only address with IMPORTED label
    bitcoinClient.listReceivedByAddress().pipeTo(probe.ref)
    probe.expectMsgType[List[WatchAddressItem]] == List(WatchAddressItem(address, "IMPORTED"))

    // now let's spend 'tx'
    val tx1 = ExternalWalletHelper.spendNonWalletTx(tx, receiveKey, sendKey)(system)

    // forward the spending transaction to the watcher
    watcher ! NewTransaction(tx1)

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
    val w5 = WatchConfirmed(null, txid, randomBytes32, 3, BITCOIN_FUNDING_SPENT)

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


}