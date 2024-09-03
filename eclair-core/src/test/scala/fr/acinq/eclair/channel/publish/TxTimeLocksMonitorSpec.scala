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
import akka.testkit.TestProbe
import fr.acinq.bitcoin.scalacompat.{OutPoint, SatoshiLong, Script, Transaction, TxId, TxIn, TxOut}
import fr.acinq.eclair.blockchain.NoOpOnChainWallet
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.channel.publish.TxTimeLocksMonitor.{CheckTx, TimeLocksOk, WrappedCurrentBlockHeight}
import fr.acinq.eclair.{NodeParams, TestConstants, TestKitBaseClass, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import java.util.UUID
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Success

class TxTimeLocksMonitorSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, monitor: ActorRef[TxTimeLocksMonitor.Command], bitcoinClient: BitcoinTestClient, probe: TestProbe)

  case class BitcoinTestClient() extends NoOpOnChainWallet {
    private val requests = collection.concurrent.TrieMap.empty[TxId, Promise[Option[Int]]]

    override def getTxConfirmations(txId: TxId)(implicit ec: ExecutionContext): Future[Option[Int]] = {
      val p = Promise[Option[Int]]()
      requests += (txId -> p)
      p.future
    }

    def hasRequest(txId: TxId): Boolean = requests.contains(txId)

    def completeRequest(txId: TxId, confirmations_opt: Option[Int]): Unit = {
      requests.get(txId).map(_.complete(Success(confirmations_opt)))
      requests -= txId
    }
  }

  override def withFixture(test: OneArgTest): Outcome = {
    within(max = 30 seconds) {
      val nodeParams = TestConstants.Alice.nodeParams
      val probe = TestProbe()
      val bitcoinClient = BitcoinTestClient()
      val monitor = system.spawnAnonymous(TxTimeLocksMonitor(nodeParams, bitcoinClient, TxPublishContext(UUID.randomUUID(), randomKey().publicKey, None)))
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, monitor, bitcoinClient, probe)))
    }
  }

  test("transaction with absolute delay") { f =>
    import f._

    val tx = Transaction(2, Nil, TxOut(150_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, nodeParams.currentBlockHeight.toLong + 3)
    monitor ! CheckTx(probe.ref, tx, "absolute-delay")
    probe.expectNoMessage(100 millis)

    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 1)
    probe.expectNoMessage(100 millis)

    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 3)
    probe.expectMsg(TimeLocksOk())
  }

  test("transaction with relative delay") { f =>
    import f._

    val parentTx = Transaction(2, Nil, TxOut(30_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
    val tx = Transaction(2, TxIn(OutPoint(parentTx, 0), Nil, 3) :: Nil, TxOut(25_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
    monitor ! CheckTx(probe.ref, tx, "relative-delay")

    // The parent transaction is unconfirmed: we will check again 3 blocks later (the value of the CSV timeout).
    awaitCond(bitcoinClient.hasRequest(parentTx.txid), interval = 100 millis)
    bitcoinClient.completeRequest(parentTx.txid, None)
    probe.expectNoMessage(100 millis)

    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 1)
    assert(!bitcoinClient.hasRequest(parentTx.txid))
    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 2)
    assert(!bitcoinClient.hasRequest(parentTx.txid))
    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 3)
    awaitCond(bitcoinClient.hasRequest(parentTx.txid), interval = 100 millis)
    // This time the parent transaction has 1 confirmation: we will check again in two more blocks.
    bitcoinClient.completeRequest(parentTx.txid, Some(1))
    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 1)
    probe.expectNoMessage(100 millis)
    assert(!bitcoinClient.hasRequest(parentTx.txid))
    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 2)
    awaitCond(bitcoinClient.hasRequest(parentTx.txid), interval = 100 millis)
    bitcoinClient.completeRequest(parentTx.txid, Some(3))

    probe.expectMsg(TimeLocksOk())
  }

  test("transaction with multiple relative delays") { f =>
    import f._

    val parentTx1 = Transaction(2, Nil, TxOut(30_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: TxOut(35_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
    val parentTx2 = Transaction(2, Nil, TxOut(45_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
    val tx = Transaction(
      2,
      TxIn(OutPoint(parentTx1, 0), Nil, 3) :: TxIn(OutPoint(parentTx1, 1), Nil, 1) :: TxIn(OutPoint(parentTx2, 0), Nil, 2) :: Nil,
      TxOut(50_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil,
      0
    )
    monitor ! CheckTx(probe.ref, tx, "many-relative-delays")

    // The first parent transaction is unconfirmed.
    awaitCond(bitcoinClient.hasRequest(parentTx1.txid), interval = 100 millis)
    bitcoinClient.completeRequest(parentTx1.txid, None)
    // The second parent transaction is confirmed, but is still missing one confirmation.
    awaitCond(bitcoinClient.hasRequest(parentTx2.txid), interval = 100 millis)
    bitcoinClient.completeRequest(parentTx2.txid, Some(1))
    probe.expectNoMessage(100 millis)

    // A new block is found: the second parent transaction has enough confirmations.
    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 1)
    awaitCond(bitcoinClient.hasRequest(parentTx2.txid), interval = 100 millis)
    assert(!bitcoinClient.hasRequest(parentTx1.txid))
    bitcoinClient.completeRequest(parentTx2.txid, Some(2))
    probe.expectNoMessage(100 millis)

    // Two more blocks are found: the first parent transaction now has enough confirmations.
    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 3)
    awaitCond(bitcoinClient.hasRequest(parentTx1.txid), interval = 100 millis)
    assert(!bitcoinClient.hasRequest(parentTx2.txid))
    bitcoinClient.completeRequest(parentTx1.txid, Some(3))

    probe.expectMsg(TimeLocksOk())
  }

  test("transaction with absolute and relative delay") { f =>
    import f._

    val parentTx1 = Transaction(2, Nil, TxOut(30_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
    val parentTx2 = Transaction(2, Nil, TxOut(45_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
    val tx = Transaction(
      2,
      TxIn(OutPoint(parentTx1, 0), Nil, 3) :: TxIn(OutPoint(parentTx2, 0), Nil, 6) :: Nil,
      TxOut(50_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil,
      nodeParams.currentBlockHeight.toLong + 3
    )
    monitor ! CheckTx(probe.ref, tx, "absolute-and-relative-delays")

    // We watch parent txs only once the absolute delay is over.
    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 2)
    assert(!bitcoinClient.hasRequest(parentTx1.txid))
    assert(!bitcoinClient.hasRequest(parentTx2.txid))

    // When the absolute delay is over, we check parent transactions.
    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 3)
    awaitCond(bitcoinClient.hasRequest(parentTx1.txid), interval = 100 millis)
    bitcoinClient.completeRequest(parentTx1.txid, Some(5))
    probe.expectNoMessage(100 millis)
    awaitCond(bitcoinClient.hasRequest(parentTx2.txid), interval = 100 millis)
    bitcoinClient.completeRequest(parentTx2.txid, Some(10))
    probe.expectMsg(TimeLocksOk())
  }

}
