/*
 * Copyright 2021 ACINQ SAS
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

package fr.acinq.eclair.channel.publish

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.{ClassicActorSystemOps, actorRefAdapter}
import akka.pattern.pipe
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, OutPoint, SatoshiLong, Transaction, TxIn}
import fr.acinq.eclair.blockchain.WatcherSpec.{createSpendManyP2WPKH, createSpendP2WPKH}
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.publish.MempoolTxMonitor._
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.channel.publish.TxPublisher.TxRejectedReason._
import fr.acinq.eclair.channel.{TransactionConfirmed, TransactionPublished}
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, randomBytes32, randomKey}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class MempoolTxMonitorSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    startBitcoind()
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  case class Fixture(priv: PrivateKey, address: String, parentTx: Transaction, monitor: ActorRef[MempoolTxMonitor.Command], bitcoinClient: BitcoinCoreClient, probe: TestProbe, eventListener: TestProbe)

  def createFixture(): Fixture = {
    val probe = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[TransactionPublished])
    system.eventStream.subscribe(eventListener.ref, classOf[TransactionConfirmed])

    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    val monitor = system.spawnAnonymous(MempoolTxMonitor(TestConstants.Alice.nodeParams, bitcoinClient, TxPublishContext(UUID.randomUUID(), randomKey().publicKey, None)))

    val (priv, address) = createExternalAddress()
    val parentTx = sendToAddress(address, 125_000 sat, probe)
    Fixture(priv, address, parentTx, monitor, bitcoinClient, probe, eventListener)
  }

  def getMempool(bitcoinClient: BitcoinCoreClient, probe: TestProbe): Seq[Transaction] = {
    bitcoinClient.getMempool().pipeTo(probe.ref)
    probe.expectMsgType[Seq[Transaction]]
  }

  def waitTxInMempool(bitcoinClient: BitcoinCoreClient, txId: ByteVector32, probe: TestProbe): Unit = {
    awaitCond(getMempool(bitcoinClient, probe).exists(_.txid == txId))
  }

  test("transaction still in mempool with unconfirmed parent") {
    val f = createFixture()
    import f._

    val tx = createSpendP2WPKH(parentTx, priv, priv.publicKey, 1_000 sat, 0, 0)
    monitor ! Publish(probe.ref, tx, tx.txIn.head.outPoint, "test-tx", 50 sat)
    assert(eventListener.expectMsgType[TransactionPublished].tx == tx)
    waitTxInMempool(bitcoinClient, tx.txid, probe)

    // NB: we don't really generate a block, we're testing the case where both txs are still in the mempool.
    monitor ! WrappedCurrentBlockHeight(currentBlockHeight())
    probe.expectMsg(TxInMempool(tx.txid, currentBlockHeight(), parentConfirmed = false))
    probe.expectNoMessage(100 millis)
  }

  test("transaction confirmed") {
    val f = createFixture()
    import f._

    // Ensure parent tx is confirmed.
    generateBlocks(1)

    val tx = createSpendP2WPKH(parentTx, priv, priv.publicKey, 1_000 sat, 0, 0)
    monitor ! Publish(probe.ref, tx, tx.txIn.head.outPoint, "test-tx", 50 sat)
    assert(eventListener.expectMsgType[TransactionPublished].tx == tx)
    waitTxInMempool(bitcoinClient, tx.txid, probe)

    // NB: we don't really generate a block, we're testing the case where the tx is still in the mempool.
    monitor ! WrappedCurrentBlockHeight(currentBlockHeight())
    probe.expectMsg(TxInMempool(tx.txid, currentBlockHeight(), parentConfirmed = true))
    probe.expectNoMessage(100 millis)

    assert(TestConstants.Alice.nodeParams.channelConf.minDepthBlocks > 1)
    generateBlocks(1)
    monitor ! WrappedCurrentBlockHeight(currentBlockHeight())
    probe.expectMsg(TxRecentlyConfirmed(tx.txid, 1))
    probe.expectNoMessage(100 millis) // we wait for more than one confirmation to protect against reorgs

    generateBlocks(TestConstants.Alice.nodeParams.channelConf.minDepthBlocks - 1)
    monitor ! WrappedCurrentBlockHeight(currentBlockHeight())
    probe.expectMsg(TxDeeplyBuried(tx))
  }

  test("transaction confirmed after replacing existing mempool transaction") {
    val f = createFixture()
    import f._

    val tx1 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 1_000 sat, 0, 0)
    bitcoinClient.publishTransaction(tx1).pipeTo(probe.ref)
    probe.expectMsg(tx1.txid)

    val tx2 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 10_000 sat, 0, 0)
    monitor ! Publish(probe.ref, tx2, tx2.txIn.head.outPoint, "test-tx", 10 sat)
    waitTxInMempool(bitcoinClient, tx2.txid, probe)

    generateBlocks(TestConstants.Alice.nodeParams.channelConf.minDepthBlocks)
    monitor ! WrappedCurrentBlockHeight(currentBlockHeight())
    probe.expectMsg(TxDeeplyBuried(tx2))
  }

  test("publish failed (conflicting mempool transaction)") {
    val f = createFixture()
    import f._

    val tx1 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 10_000 sat, 0, 0)
    bitcoinClient.publishTransaction(tx1).pipeTo(probe.ref)
    probe.expectMsg(tx1.txid)

    val tx2 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 7_500 sat, 0, 0)
    monitor ! Publish(probe.ref, tx2, tx2.txIn.head.outPoint, "test-tx", 25 sat)
    probe.expectMsg(TxRejected(tx2.txid, ConflictingTxUnconfirmed))
  }

  test("publish failed (conflicting confirmed transaction)") {
    val f = createFixture()
    import f._

    val tx1 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 5_000 sat, 0, 0)
    bitcoinClient.publishTransaction(tx1).pipeTo(probe.ref)
    probe.expectMsg(tx1.txid)
    generateBlocks(1)

    val tx2 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 15_000 sat, 0, 0)
    monitor ! Publish(probe.ref, tx2, tx2.txIn.head.outPoint, "test-tx", 10 sat)
    probe.expectMsg(TxRejected(tx2.txid, ConflictingTxConfirmed))
  }

  test("publish failed (unconfirmed parent, wallet input doesn't exist)") {
    val f = createFixture()
    import f._

    val tx = createSpendP2WPKH(parentTx, priv, priv.publicKey, 5_000 sat, 0, 0)
    val txUnknownInput = tx.copy(txIn = tx.txIn ++ Seq(TxIn(OutPoint(randomBytes32(), 13), Nil, 0)))
    monitor ! Publish(probe.ref, txUnknownInput, txUnknownInput.txIn.head.outPoint, "test-tx", 10 sat)
    probe.expectMsg(TxRejected(txUnknownInput.txid, InputGone))
  }

  test("publish failed (confirmed parent, wallet input doesn't exist)") {
    val f = createFixture()
    import f._

    // Ensure parent tx is confirmed.
    generateBlocks(1)

    val tx = createSpendP2WPKH(parentTx, priv, priv.publicKey, 5_000 sat, 0, 0)
    val txUnknownInput = tx.copy(txIn = tx.txIn ++ Seq(TxIn(OutPoint(randomBytes32(), 13), Nil, 0)))
    monitor ! Publish(probe.ref, txUnknownInput, txUnknownInput.txIn.head.outPoint, "test-tx", 10 sat)
    probe.expectMsg(TxRejected(txUnknownInput.txid, InputGone))
  }

  test("publish failed (wallet input spent by conflicting confirmed transaction)") {
    val f = createFixture()
    import f._

    val walletTx = sendToAddress(address, 110_000 sat, probe)
    val walletSpendTx = createSpendP2WPKH(walletTx, priv, priv.publicKey, 1_500 sat, 0, 0)
    bitcoinClient.publishTransaction(walletSpendTx).pipeTo(probe.ref)
    probe.expectMsg(walletSpendTx.txid)
    generateBlocks(1) // we ensure the wallet input is already spent by a confirmed transaction

    val tx = createSpendManyP2WPKH(Seq(parentTx, walletTx), priv, priv.publicKey, 5_000 sat, 0, 0)
    monitor ! Publish(probe.ref, tx, tx.txIn.head.outPoint, "test-tx", 10 sat)
    probe.expectMsg(TxRejected(tx.txid, InputGone))
  }

  test("publish succeeds then transaction is replaced by an unconfirmed tx") {
    val f = createFixture()
    import f._

    val tx1 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 5_000 sat, 0, 0)
    monitor ! Publish(probe.ref, tx1, tx1.txIn.head.outPoint, "test-tx", 0 sat)
    waitTxInMempool(bitcoinClient, tx1.txid, probe)

    val tx2 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 15_000 sat, 0, 0)
    bitcoinClient.publishTransaction(tx2).pipeTo(probe.ref)
    probe.expectMsg(tx2.txid)

    // When a new block is found, we detect that the transaction has been replaced.
    monitor ! WrappedCurrentBlockHeight(currentBlockHeight())
    probe.expectMsg(TxRejected(tx1.txid, ConflictingTxUnconfirmed))
  }

  test("publish succeeds then transaction is replaced by a confirmed tx") {
    val f = createFixture()
    import f._

    val tx1 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 5_000 sat, 0, 0)
    monitor ! Publish(probe.ref, tx1, tx1.txIn.head.outPoint, "test-tx", 10 sat)
    waitTxInMempool(bitcoinClient, tx1.txid, probe)

    val tx2 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 15_000 sat, 0, 0)
    bitcoinClient.publishTransaction(tx2).pipeTo(probe.ref)
    probe.expectMsg(tx2.txid)

    // When a new block is found, we detect that the transaction has been replaced.
    generateBlocks(1)
    monitor ! WrappedCurrentBlockHeight(currentBlockHeight())
    probe.expectMsg(TxRejected(tx1.txid, ConflictingTxConfirmed))
  }

  test("publish succeeds then wallet input disappears") {
    val f = createFixture()
    import f._

    val walletParentTx = sendToAddress(address, 110_000 sat, probe)
    val walletTx = createSpendP2WPKH(walletParentTx, priv, priv.publicKey, 1_000 sat, 0, 0)
    bitcoinClient.publishTransaction(walletTx).pipeTo(probe.ref)
    probe.expectMsg(walletTx.txid)

    val tx = createSpendManyP2WPKH(Seq(parentTx, walletTx), priv, priv.publicKey, 1_000 sat, 0, 0)
    monitor ! Publish(probe.ref, tx, tx.txIn.head.outPoint, "test-tx", 10 sat)
    waitTxInMempool(bitcoinClient, tx.txid, probe)

    // A transaction replaces our unconfirmed wallet input.
    val walletTxConflict = createSpendP2WPKH(walletParentTx, priv, priv.publicKey, 10_000 sat, 0, 0)
    bitcoinClient.publishTransaction(walletTxConflict).pipeTo(probe.ref)
    probe.expectMsg(walletTxConflict.txid)

    // When a new block is found, we detect that the transaction has been evicted.
    generateBlocks(1)
    monitor ! WrappedCurrentBlockHeight(currentBlockHeight())
    probe.expectMsg(TxRejected(tx.txid, InputGone))
  }

  test("emit transaction events") {
    val f = createFixture()
    import f._

    // Ensure parent tx is confirmed.
    generateBlocks(1)

    val tx = createSpendP2WPKH(parentTx, priv, priv.publicKey, 1_000 sat, 0, 0)
    monitor ! Publish(probe.ref, tx, tx.txIn.head.outPoint, "test-tx", 15 sat)
    waitTxInMempool(bitcoinClient, tx.txid, probe)
    val txPublished = eventListener.expectMsgType[TransactionPublished]
    assert(txPublished.tx == tx)
    assert(txPublished.miningFee == 15.sat)
    assert(txPublished.desc == "test-tx")

    generateBlocks(TestConstants.Alice.nodeParams.channelConf.minDepthBlocks)
    monitor ! WrappedCurrentBlockHeight(currentBlockHeight())
    eventListener.expectMsg(TransactionConfirmed(txPublished.channelId, txPublished.remoteNodeId, tx))
  }

}
