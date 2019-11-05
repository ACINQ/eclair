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
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.OutPoint
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.{Watch, WatchConfirmed, WatchEventSpent, WatchSpent, WatchSpentBasic}
import fr.acinq.eclair.channel.BITCOIN_FUNDING_SPENT
import fr.acinq.eclair.randomBytes32
import grizzled.slf4j.Logging
import org.scalatest.{BeforeAndAfterAll, FunSuite, FunSuiteLike}

import scala.collection.JavaConversions._
import scala.concurrent.Await
import scala.concurrent.duration._

class ZmqWatcherSpec extends TestKit(ActorSystem("test")) with BitcoindService with FunSuiteLike with BeforeAndAfterAll with Logging  {

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
    val probe = TestProbe()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    val watcher = system.actorOf(ZmqWatcher.props(new AtomicLong(), bitcoinClient))

    // tx is an unspent and confirmed non wallet transaction
    val (tx, _) = ExternalWalletHelper.nonWalletTransaction(system)
    val isSpendable = Await.result(bitcoinClient.isTransactionOutputSpendable(tx.txid.toHex, 0, false)(system.dispatcher), 10 seconds)
    assert(isSpendable)

    // now tx is spent by tx1
    val (tx1, _) = ExternalWalletHelper.spendNonWalletTx(tx)(system)
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