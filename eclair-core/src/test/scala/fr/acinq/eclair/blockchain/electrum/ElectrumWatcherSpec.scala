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

package fr.acinq.eclair.blockchain.electrum

import akka.actor.Props
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{Btc, ByteVector32, SatoshiLong, Transaction, TxIn}
import fr.acinq.eclair.blockchain.WatcherSpec._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.bitcoind.rpc.ExtendedBitcoinClient
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, BITCOIN_FUNDING_SPENT}
import fr.acinq.eclair.{TestKitBaseClass, randomBytes32}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JValue
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits._

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._

class ElectrumWatcherSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with ElectrumxService with BeforeAndAfterAll with Logging {

  override def beforeAll(): Unit = {
    logger.info("starting bitcoind")
    startBitcoind()
    waitForBitcoindReady()
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    logger.info("stopping bitcoind")
    stopBitcoind()
    super.afterAll()
  }

  val electrumAddress = ElectrumServerAddress(new InetSocketAddress("localhost", electrumPort), SSL.OFF)

  test("watch for confirmed transactions") {
    val (probe, listener) = (TestProbe(), TestProbe())
    val blockCount = new AtomicLong()
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(electrumAddress))))
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))

    val address = getNewAddress(probe)
    val tx = sendToAddress(address, Btc(1), probe)

    probe.send(watcher, WatchConfirmed(listener.ref, tx.txid, tx.txOut.head.publicKeyScript, 2, BITCOIN_FUNDING_DEPTHOK))
    generateBlocks(2)
    assert(listener.expectMsgType[WatchEventConfirmed].tx === tx)
    probe.send(watcher, WatchConfirmed(listener.ref, tx, 4, BITCOIN_FUNDING_DEPTHOK))
    generateBlocks(2)
    assert(listener.expectMsgType[WatchEventConfirmed].tx === tx)
    system.stop(watcher)
  }

  test("watch for spent transactions") {
    val (probe, listener) = (TestProbe(), TestProbe())
    val blockCount = new AtomicLong()
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(electrumAddress))))
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))

    val address = getNewAddress(probe)
    val priv = dumpPrivateKey(address, probe)
    val tx = sendToAddress(address, Btc(1))

    // create a tx that spends the previous output
    val spendingTx = createSpendP2WPKH(tx, priv, priv.publicKey, 1000 sat, TxIn.SEQUENCE_FINAL, 0)
    val outpointIndex = spendingTx.txIn.head.outPoint.index.toInt
    probe.send(watcher, WatchSpent(listener.ref, tx.txid, outpointIndex, tx.txOut(outpointIndex).publicKeyScript, BITCOIN_FUNDING_SPENT, hints = Set.empty))
    listener.expectNoMsg(1 second)
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", spendingTx.toString))
    probe.expectMsgType[JValue]
    generateBlocks(2)
    assert(listener.expectMsgType[WatchEventSpent].tx === spendingTx)
    system.stop(watcher)
  }

  test("watch for mempool transactions (txs in mempool before we set the watch)") {
    val (probe, listener) = (TestProbe(), TestProbe())
    val blockCount = new AtomicLong()
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(electrumAddress))))
    probe.send(electrumClient, ElectrumClient.AddStatusListener(probe.ref))
    probe.expectMsgType[ElectrumClient.ElectrumReady]
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))

    val address = getNewAddress(probe)
    val priv1 = dumpPrivateKey(address, probe)
    val tx = sendToAddress(address, Btc(1))
    val priv2 = dumpPrivateKey(getNewAddress(probe), probe)
    val priv3 = dumpPrivateKey(getNewAddress(probe), probe)
    val tx1 = createSpendP2WPKH(tx, priv1, priv2.publicKey, 10000 sat, TxIn.SEQUENCE_FINAL, 0)
    val tx2 = createSpendP2WPKH(tx1, priv2, priv3.publicKey, 10000 sat, TxIn.SEQUENCE_FINAL, 0)
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", tx1.toString()))
    probe.expectMsgType[JValue]
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", tx2.toString()))
    probe.expectMsgType[JValue]

    // wait until tx1 and tx2 are in the mempool (as seen by our ElectrumX server)
    awaitCond({
      probe.send(electrumClient, ElectrumClient.GetScriptHashHistory(ElectrumClient.computeScriptHash(tx2.txOut.head.publicKeyScript)))
      val ElectrumClient.GetScriptHashHistoryResponse(_, history) = probe.expectMsgType[ElectrumClient.GetScriptHashHistoryResponse]
      history.map(_.tx_hash).toSet == Set(tx2.txid)
    }, max = 30 seconds, interval = 5 seconds)

    // then set watches
    probe.send(watcher, WatchConfirmed(listener.ref, tx2, 0, BITCOIN_FUNDING_DEPTHOK))
    assert(listener.expectMsgType[WatchEventConfirmed].tx === tx2)
    probe.send(watcher, WatchSpent(listener.ref, tx1, 0, BITCOIN_FUNDING_SPENT, hints = Set.empty))
    listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2))
    system.stop(watcher)
  }

  test("watch for mempool transactions (txs not yet in the mempool when we set the watch)") {
    val (probe, listener) = (TestProbe(), TestProbe())
    val blockCount = new AtomicLong()
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(electrumAddress))))
    probe.send(electrumClient, ElectrumClient.AddStatusListener(probe.ref))
    probe.expectMsgType[ElectrumClient.ElectrumReady]
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))

    val address = getNewAddress(probe)
    val priv = dumpPrivateKey(address, probe)
    val tx = sendToAddress(address, Btc(1))
    val (tx1, tx2) = createUnspentTxChain(tx, priv)

    // here we set watches * before * we publish our transactions
    probe.send(watcher, WatchSpent(listener.ref, tx1, 0, BITCOIN_FUNDING_SPENT, hints = Set.empty))
    probe.send(watcher, WatchConfirmed(listener.ref, tx1, 0, BITCOIN_FUNDING_DEPTHOK))
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", tx1.toString()))
    probe.expectMsgType[JValue]
    assert(listener.expectMsgType[WatchEventConfirmed].tx === tx1)
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", tx2.toString()))
    probe.expectMsgType[JValue]
    listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, tx2))
    system.stop(watcher)
  }

  test("publish transactions with relative or absolute delays") {
    import akka.pattern.pipe

    val (probe, listener) = (TestProbe(), TestProbe())
    val blockCount = new AtomicLong()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(electrumAddress))))
    bitcoinClient.getBlockCount.pipeTo(probe.ref)
    val initialBlockCount = probe.expectMsgType[Long]
    probe.send(electrumClient, ElectrumClient.AddStatusListener(probe.ref))
    awaitCond(probe.expectMsgType[ElectrumClient.ElectrumReady].height >= initialBlockCount, message = s"waiting for tip at $initialBlockCount")
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))
    val recipient = dumpPrivateKey(getNewAddress(probe), probe).publicKey

    val address1 = getNewAddress(probe)
    val priv1 = dumpPrivateKey(address1, probe)
    val tx1 = sendToAddress(address1, Btc(0.2))
    val address2 = getNewAddress(probe)
    val priv2 = dumpPrivateKey(address2, probe)
    val tx2 = sendToAddress(address2, Btc(0.2))
    generateBlocks(1)
    for (tx <- Seq(tx1, tx2)) {
      probe.send(watcher, WatchConfirmed(listener.ref, tx, 1, BITCOIN_FUNDING_DEPTHOK))
      assert(listener.expectMsgType[WatchEventConfirmed].tx === tx)
    }

    // spend tx1 with an absolute delay but no relative delay
    val spend1 = createSpendP2WPKH(tx1, priv1, recipient, 5000 sat, sequence = 0, lockTime = blockCount.get + 1)
    probe.send(watcher, WatchSpent(listener.ref, tx1, spend1.txIn.head.outPoint.index.toInt, BITCOIN_FUNDING_SPENT, hints = Set.empty))
    probe.send(watcher, PublishAsap(spend1, PublishStrategy.JustPublish))
    // spend tx2 with a relative delay but no absolute delay
    val spend2 = createSpendP2WPKH(tx2, priv2, recipient, 3000 sat, sequence = 1, lockTime = 0)
    probe.send(watcher, WatchSpent(listener.ref, tx2, spend2.txIn.head.outPoint.index.toInt, BITCOIN_FUNDING_SPENT, hints = Set.empty))
    probe.send(watcher, PublishAsap(spend2, PublishStrategy.JustPublish))

    generateBlocks(1)
    listener.expectMsgAllOf(WatchEventSpent(BITCOIN_FUNDING_SPENT, spend1), WatchEventSpent(BITCOIN_FUNDING_SPENT, spend2))

    system.stop(watcher)
  }

  test("publish transactions with relative and absolute delays") {
    import akka.pattern.pipe

    val (probe, listener) = (TestProbe(), TestProbe())
    val blockCount = new AtomicLong()
    val bitcoinClient = new ExtendedBitcoinClient(bitcoinrpcclient)
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(electrumAddress))))
    bitcoinClient.getBlockCount.pipeTo(probe.ref)
    val initialBlockCount = probe.expectMsgType[Long]
    probe.send(electrumClient, ElectrumClient.AddStatusListener(probe.ref))
    awaitCond(probe.expectMsgType[ElectrumClient.ElectrumReady].height >= initialBlockCount, message = s"waiting for tip at $initialBlockCount")
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))
    val recipient = dumpPrivateKey(getNewAddress(probe), probe).publicKey

    val address = getNewAddress(probe)
    val priv = dumpPrivateKey(address, probe)
    val tx = sendToAddress(address, Btc(0.2))
    generateBlocks(1)
    probe.send(watcher, WatchConfirmed(listener.ref, tx, 1, BITCOIN_FUNDING_DEPTHOK))
    assert(listener.expectMsgType[WatchEventConfirmed].tx === tx)

    // spend tx with both relative and absolute delays
    val spend = createSpendP2WPKH(tx, priv, recipient, 6000 sat, sequence = 1, lockTime = blockCount.get + 2)
    probe.send(watcher, WatchSpent(listener.ref, tx, spend.txIn.head.outPoint.index.toInt, BITCOIN_FUNDING_SPENT, hints = Set.empty))
    probe.send(watcher, PublishAsap(spend, PublishStrategy.JustPublish))
    generateBlocks(2)
    listener.expectMsg(WatchEventSpent(BITCOIN_FUNDING_SPENT, spend))

    system.stop(watcher)
  }

  test("generate unique dummy scids") {
    // generate 1000 dummy ids
    val dummies = (0 until 20).map { _ =>
      ElectrumWatcher.makeDummyShortChannelId(randomBytes32)
    } toSet

    // make sure that they are unique (we allow for 1 collision here, actual probability of a collision with the current impl. is 1%
    // but that could change and we don't want to make this test impl. dependent)
    // if this test fails it's very likely that the code that generates dummy scids is broken
    assert(dummies.size >= 19)
  }

  test("get transaction") {
    val blockCount = new AtomicLong()
    val mainnetAddress = ElectrumServerAddress(new InetSocketAddress("electrum.acinq.co", 50002), SSL.STRICT)
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(mainnetAddress))))
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))
    val probe = TestProbe()

    {
      // tx is in the blockchain
      val txid = ByteVector32(hex"c0b18008713360d7c30dae0940d88152a4bbb10faef5a69fefca5f7a7e1a06cc")
      probe.send(watcher, GetTxWithMeta(txid))
      val res = probe.expectMsgType[GetTxWithMetaResponse]
      assert(res.txid === txid)
      assert(res.tx_opt === Some(Transaction.read("0100000001b5cbd7615a7494f60304695c180eb255113bd5effcf54aec6c7dfbca67f533a1010000006a473044022042115a5d1a489bbc9bd4348521b098025625c9b6c6474f84b96b11301da17a0602203ccb684b1d133ff87265a6017ef0fdd2d22dd6eef0725c57826f8aaadcc16d9d012103629aa3df53cad290078bbad26491f1e11f9c01697c65db0967561f6f142c993cffffffff02801015000000000017a914b8984d6344eed24689cdbc77adaf73c66c4fdd688734e9e818000000001976a91404607585722760691867b42d43701905736be47d88ac00000000")))
      assert(res.lastBlockTimestamp > System.currentTimeMillis().millis.toSeconds - 7200) // this server should be in sync
    }

    {
      // tx doesn't exist
      val txid = ByteVector32(hex"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
      probe.send(watcher, GetTxWithMeta(txid))
      val res = probe.expectMsgType[GetTxWithMetaResponse]
      assert(res.txid === txid)
      assert(res.tx_opt === None)
      assert(res.lastBlockTimestamp > System.currentTimeMillis().millis.toSeconds - 7200) // this server should be in sync
    }
    system.stop(watcher)
  }
}
