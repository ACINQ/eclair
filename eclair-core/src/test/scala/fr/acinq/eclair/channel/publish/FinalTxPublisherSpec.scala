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
import akka.actor.typed.scaladsl.adapter.{ClassicActorSystemOps, TypedActorRefOps, actorRefAdapter}
import akka.pattern.pipe
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Transaction}
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.blockchain.WatcherSpec.createSpendP2WPKH
import fr.acinq.eclair.blockchain.bitcoind.BitcoindService
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchParentTxConfirmed, WatchParentTxConfirmedTriggered}
import fr.acinq.eclair.blockchain.bitcoind.rpc.BitcoinCoreClient
import fr.acinq.eclair.channel.publish.FinalTxPublisher.{Publish, Stop}
import fr.acinq.eclair.channel.publish.TxPublisher.TxRejectedReason.ConflictingTxConfirmed
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, TxConfirmed, TxPublishContext, TxRejected}
import fr.acinq.eclair.{TestConstants, TestKitBaseClass, randomKey}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuiteLike

import java.util.UUID
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt

class FinalTxPublisherSpec extends TestKitBaseClass with AnyFunSuiteLike with BitcoindService with BeforeAndAfterAll {

  override def beforeAll(): Unit = {
    startBitcoind()
    waitForBitcoindReady()
  }

  override def afterAll(): Unit = {
    stopBitcoind()
  }

  case class Fixture(bitcoinClient: BitcoinCoreClient, publisher: ActorRef[FinalTxPublisher.Command], watcher: TestProbe, probe: TestProbe)

  def createFixture(): Fixture = {
    val probe = TestProbe()
    val watcher = TestProbe()
    val bitcoinClient = new BitcoinCoreClient(bitcoinrpcclient)
    val publisher = system.spawnAnonymous(FinalTxPublisher(TestConstants.Alice.nodeParams, bitcoinClient, watcher.ref, TxPublishContext(UUID.randomUUID(), randomKey().publicKey, None)))
    Fixture(bitcoinClient, publisher, watcher, probe)
  }

  def getMempool(bitcoinClient: BitcoinCoreClient, probe: TestProbe): Seq[Transaction] = {
    bitcoinClient.getMempool().pipeTo(probe.ref)
    probe.expectMsgType[Seq[Transaction]]
  }

  def waitTxInMempool(bitcoinClient: BitcoinCoreClient, txId: ByteVector32, probe: TestProbe): Unit = {
    awaitCond(getMempool(bitcoinClient, probe).exists(_.txid == txId))
  }

  def createBlocks(blockCount: Int, probe: TestProbe): Unit = {
    generateBlocks(blockCount)
    system.eventStream.publish(CurrentBlockHeight(currentBlockHeight(probe)))
  }

  test("publish transaction with time locks") {
    val f = createFixture()
    import f._

    val (priv, address) = createExternalAddress()
    val parentTx = sendToAddress(address, 125_000 sat, probe)
    val tx = createSpendP2WPKH(parentTx, priv, priv.publicKey, 2_500 sat, sequence = 5, lockTime = 0)
    val cmd = PublishFinalTx(tx, tx.txIn.head.outPoint, "tx-time-locks", 0 sat, None)
    publisher ! Publish(probe.ref, cmd)

    val w = watcher.expectMsgType[WatchParentTxConfirmed]
    assert(w.txId == parentTx.txid)
    assert(w.minDepth == 5)
    createBlocks(5, probe)
    w.replyTo ! WatchParentTxConfirmedTriggered(currentBlockHeight(probe), 0, parentTx)

    // Once time locks are satisfied, the transaction should be published:
    waitTxInMempool(bitcoinClient, tx.txid, probe)
    createBlocks(1, probe)
    probe.expectNoMessage(100 millis) // we don't notify the sender until min depth has been reached
    createBlocks(3, probe)
    probe.expectMsg(TxConfirmed(cmd, tx))

    // The actor should stop when requested:
    probe.watch(publisher.toClassic)
    publisher ! Stop
    probe.expectTerminated(publisher.toClassic, 5 seconds)
  }

  test("publish transaction with parent") {
    val f = createFixture()
    import f._

    val (priv, address) = createExternalAddress()
    val ancestorTx = sendToAddress(address, 125_000 sat, probe)
    val parentTx = createSpendP2WPKH(ancestorTx, priv, priv.publicKey, 2_500 sat, 0, 0)
    val tx = createSpendP2WPKH(parentTx, priv, priv.publicKey, 2_000 sat, 0, 0)
    val cmd = PublishFinalTx(tx, tx.txIn.head.outPoint, "tx-with-parent", 10 sat, Some(parentTx.txid))
    publisher ! Publish(probe.ref, cmd)

    // Since the parent is not published yet, we can't publish the child tx either:
    watcher.expectNoMessage(100 millis)
    assert(!getMempool(bitcoinClient, probe).map(_.txid).contains(tx.txid))

    // Once the parent tx is published, it will unblock publication of the child tx:
    bitcoinClient.publishTransaction(parentTx).pipeTo(probe.ref)
    probe.expectMsg(parentTx.txid)
    waitTxInMempool(bitcoinClient, tx.txid, probe)

    createBlocks(5, probe)
    probe.expectMsg(TxConfirmed(cmd, tx))
  }

  test("publish transaction that fails to confirm") {
    val f = createFixture()
    import f._

    val (priv, address) = createExternalAddress()
    val parentTx = sendToAddress(address, 125_000 sat, probe)
    val tx1 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 2_500 sat, 0, 0)
    val cmd = PublishFinalTx(tx1, tx1.txIn.head.outPoint, "tx-time-locks", 10 sat, None)
    publisher ! Publish(probe.ref, cmd)
    waitTxInMempool(bitcoinClient, tx1.txid, probe)

    // A conflicting transaction replaces our transaction.
    val tx2 = createSpendP2WPKH(parentTx, priv, priv.publicKey, 7_500 sat, 0, 0)
    bitcoinClient.publishTransaction(tx2).pipeTo(probe.ref)
    probe.expectMsg(tx2.txid)

    createBlocks(1, probe)
    val response = probe.expectMsgType[TxRejected]
    assert(response.cmd == cmd)
    assert(response.reason == ConflictingTxConfirmed)

    // The actor should stop when requested:
    probe.watch(publisher.toClassic)
    publisher ! Stop
    probe.expectTerminated(publisher.toClassic, 5 seconds)
  }

  test("stop actor before transaction confirms") {
    val f = createFixture()
    import f._

    val tx = sendToAddress(getNewAddress(probe), 125_000 sat, probe)
    val cmd = PublishFinalTx(tx, tx.txIn.head.outPoint, "final-tx", 10 sat, None)
    publisher ! Publish(probe.ref, cmd)

    probe.watch(publisher.toClassic)
    probe.expectNoMessage(100 millis)
    publisher ! Stop
    probe.expectTerminated(publisher.toClassic, 5 seconds)

    // The transaction confirms, but we've already stopped the actor so the sender is not notified:
    createBlocks(5, probe)
    probe.expectNoMessage(100 millis)
  }

}
