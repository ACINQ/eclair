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

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

import akka.actor.Props
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.scala.{ByteVector32, OutPoint, SIGHASH_ALL, Script, ScriptFlags, ScriptWitness, SigVersion, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.WatcherSpec._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService.BitcoinReq
import fr.acinq.eclair.blockchain.electrum.ElectrumClient.SSL
import fr.acinq.eclair.blockchain.electrum.ElectrumClientPool.ElectrumServerAddress
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, BITCOIN_FUNDING_SPENT}
import fr.acinq.eclair.{LongToBtcAmount, TestKitBaseClass, randomBytes32}
import grizzled.slf4j.Logging
import org.json4s.JsonAST.JValue
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits._

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
    TestKit.shutdownActorSystem(system)
  }

  val electrumAddress = ElectrumServerAddress(new InetSocketAddress("localhost", electrumPort), SSL.OFF)

  test("watch for confirmed transactions") {
    val probe = TestProbe()
    val blockCount = new AtomicLong()
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(electrumAddress))))
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))

    val (address, _) = getNewAddress(bitcoincli)
    val tx = sendToAddress(bitcoincli, address, 1.0)

    val listener = TestProbe()
    probe.send(watcher, WatchConfirmed(listener.ref, tx.txid, tx.txOut(0).publicKeyScript, 4, BITCOIN_FUNDING_DEPTHOK))
    generateBlocks(bitcoincli, 5)
    val confirmed = listener.expectMsgType[WatchEventConfirmed](20 seconds)
    assert(confirmed.tx.txid === tx.txid)
    system.stop(watcher)
  }

  test("watch for spent transactions") {
    val probe = TestProbe()
    val blockCount = new AtomicLong()
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(electrumAddress))))
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))

    val (address, priv) = getNewAddress(bitcoincli)
    val tx = sendToAddress(bitcoincli, address, 1.0)

    // find the output for the address we generated and create a tx that spends it
    val pos = tx.txOut.indexWhere(_.publicKeyScript == Script.write(Script.pay2wpkh(priv.publicKey)))
    assert(pos != -1)
    val spendingTx = {
      val tmp = Transaction(version = 2,
        txIn = TxIn(OutPoint(tx, pos), signatureScript = Nil, sequence = TxIn.SEQUENCE_FINAL) :: Nil,
        txOut = TxOut(tx.txOut(pos).amount - 1000.sat, publicKeyScript = Script.pay2wpkh(priv.publicKey)) :: Nil,
        lockTime = 0)
      val sig = Transaction.signInput(tmp, 0, Script.pay2pkh(priv.publicKey), SIGHASH_ALL, tx.txOut(pos).amount, SigVersion.SIGVERSION_WITNESS_V0, priv)
      val signedTx = tmp.updateWitness(0, ScriptWitness(sig :: priv.publicKey.value :: Nil))
      Transaction.correctlySpends(signedTx, Seq(tx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      signedTx
    }

    val listener = TestProbe()
    probe.send(watcher, WatchSpent(listener.ref, tx.txid, pos, tx.txOut(pos).publicKeyScript, BITCOIN_FUNDING_SPENT))
    listener.expectNoMsg(1 second)
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", spendingTx.toString))
    probe.expectMsgType[JValue]
    generateBlocks(bitcoincli, 2)
    listener.expectMsgType[WatchEventSpent](20 seconds)
    system.stop(watcher)
  }

  test("watch for mempool transactions (txs in mempool before we set the watch)") {
    val probe = TestProbe()
    val blockCount = new AtomicLong()
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(electrumAddress))))
    probe.send(electrumClient, ElectrumClient.AddStatusListener(probe.ref))
    probe.expectMsgType[ElectrumClient.ElectrumReady]
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))

    val (address, priv) = getNewAddress(bitcoincli)
    val tx = sendToAddress(bitcoincli, address, 1.0)
    val (tx1, tx2) = createUnspentTxChain(tx, priv)
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", tx1.toString()))
    probe.expectMsgType[JValue]
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", tx2.toString()))
    probe.expectMsgType[JValue]

    // wait until tx1 and tx2 are in the mempool (as seen by our ElectrumX server)
    awaitCond({
      probe.send(electrumClient, ElectrumClient.GetScriptHashHistory(ElectrumClient.computeScriptHash(tx2.txOut(0).publicKeyScript)))
      val ElectrumClient.GetScriptHashHistoryResponse(_, history) = probe.expectMsgType[ElectrumClient.GetScriptHashHistoryResponse]
      history.map(_.tx_hash).toSet == Set(tx.txid, tx1.txid, tx2.txid)
    }, max = 30 seconds, interval = 5 seconds)

    // then set a watch
    val listener = TestProbe()
    probe.send(watcher, WatchConfirmed(listener.ref, tx2.txid, tx2.txOut(0).publicKeyScript, 0, BITCOIN_FUNDING_DEPTHOK))
    val confirmed = listener.expectMsgType[WatchEventConfirmed](20 seconds)
    assert(confirmed.tx.txid === tx2.txid)
    system.stop(watcher)
  }

  test("watch for mempool transactions (txs not yet in the mempool when we set the watch)") {
    val probe = TestProbe()
    val blockCount = new AtomicLong()
    val electrumClient = system.actorOf(Props(new ElectrumClientPool(blockCount, Set(electrumAddress))))
    probe.send(electrumClient, ElectrumClient.AddStatusListener(probe.ref))
    probe.expectMsgType[ElectrumClient.ElectrumReady]
    val watcher = system.actorOf(Props(new ElectrumWatcher(blockCount, electrumClient)))

    val (address, priv) = getNewAddress(bitcoincli)
    val tx = sendToAddress(bitcoincli, address, 1.0)
    val (tx1, tx2) = createUnspentTxChain(tx, priv)

    // here we set the watch * before * we publish our transactions
    val listener = TestProbe()
    probe.send(watcher, WatchConfirmed(listener.ref, tx2.txid, tx2.txOut(0).publicKeyScript, 0, BITCOIN_FUNDING_DEPTHOK))
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", tx1.toString()))
    probe.expectMsgType[JValue]
    probe.send(bitcoincli, BitcoinReq("sendrawtransaction", tx2.toString()))
    probe.expectMsgType[JValue]

    val confirmed = listener.expectMsgType[WatchEventConfirmed](20 seconds)
    assert(confirmed.tx.txid === tx2.txid)
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
  }
}
