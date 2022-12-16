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
import fr.acinq.bitcoin.scalacompat.{OutPoint, SatoshiLong, Script, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher.{WatchParentTxConfirmed, WatchParentTxConfirmedTriggered}
import fr.acinq.eclair.channel.publish.TxPublisher.TxPublishContext
import fr.acinq.eclair.channel.publish.TxTimeLocksMonitor.{CheckTx, TimeLocksOk, WrappedCurrentBlockHeight}
import fr.acinq.eclair.{BlockHeight, NodeParams, TestConstants, TestKitBaseClass, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import java.util.UUID
import scala.concurrent.duration.DurationInt

class TxTimeLocksMonitorSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, monitor: ActorRef[TxTimeLocksMonitor.Command], watcher: TestProbe, probe: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    within(max = 30 seconds) {
      val nodeParams = TestConstants.Alice.nodeParams
      val probe = TestProbe()
      val watcher = TestProbe()
      val monitor = system.spawnAnonymous(TxTimeLocksMonitor(nodeParams, watcher.ref, TxPublishContext(UUID.randomUUID(), randomKey().publicKey, None)))
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, monitor, watcher, probe)))
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

    val w = watcher.expectMsgType[WatchParentTxConfirmed]
    assert(w.txId == parentTx.txid)
    assert(w.minDepth == 3)
    probe.expectNoMessage(100 millis)

    w.replyTo ! WatchParentTxConfirmedTriggered(BlockHeight(651), 0, parentTx)
    probe.expectMsg(TimeLocksOk())
  }

  test("transaction with multiple relative delays") { f =>
    import f._

    val parentTx1 = Transaction(2, Nil, TxOut(30_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: TxOut(35_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
    val parentTx2 = Transaction(2, Nil, TxOut(45_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil, 0)
    val tx = Transaction(
      2,
      TxIn(OutPoint(parentTx1, 0), Nil, 3) :: TxIn(OutPoint(parentTx1, 1), Nil, 1) :: TxIn(OutPoint(parentTx2, 0), Nil, 1) :: Nil,
      TxOut(50_000 sat, Script.pay2wpkh(randomKey().publicKey)) :: Nil,
      0
    )
    monitor ! CheckTx(probe.ref, tx, "many-relative-delays")

    // We send a single watch for parentTx1, with the max of the two delays.
    val w1 = watcher.expectMsgType[WatchParentTxConfirmed]
    val w2 = watcher.expectMsgType[WatchParentTxConfirmed]
    watcher.expectNoMessage(100 millis)
    assert(Seq(w1, w2).map(w => (w.txId, w.minDepth)).toSet == Set((parentTx1.txid, 3), (parentTx2.txid, 1)))
    probe.expectNoMessage(100 millis)

    w1.replyTo ! WatchParentTxConfirmedTriggered(BlockHeight(651), 0, parentTx1)
    probe.expectNoMessage(100 millis)

    w2.replyTo ! WatchParentTxConfirmedTriggered(BlockHeight(1105), 0, parentTx2)
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

    // We set watches on parent txs only once the absolute delay is over.
    watcher.expectNoMessage(100 millis)
    monitor ! WrappedCurrentBlockHeight(nodeParams.currentBlockHeight + 3)
    val w1 = watcher.expectMsgType[WatchParentTxConfirmed]
    val w2 = watcher.expectMsgType[WatchParentTxConfirmed]
    watcher.expectNoMessage(100 millis)
    assert(Seq(w1, w2).map(w => (w.txId, w.minDepth)).toSet == Set((parentTx1.txid, 3), (parentTx2.txid, 6)))
    probe.expectNoMessage(100 millis)

    w1.replyTo ! WatchParentTxConfirmedTriggered(BlockHeight(651), 0, parentTx1)
    probe.expectNoMessage(100 millis)

    w2.replyTo ! WatchParentTxConfirmedTriggered(BlockHeight(1105), 0, parentTx2)
    probe.expectMsg(TimeLocksOk())
  }

}
