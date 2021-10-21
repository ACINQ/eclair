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
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.adapter.{ClassicActorSystemOps, TypedActorRefOps, actorRefAdapter}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{OutPoint, SatoshiLong, Transaction, TxIn, TxOut}
import fr.acinq.eclair.TestConstants.TestFeeEstimator
import fr.acinq.eclair.blockchain.CurrentBlockCount
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel.publish
import fr.acinq.eclair.channel.publish.TxPublisher.TxRejectedReason._
import fr.acinq.eclair.channel.publish.TxPublisher._
import fr.acinq.eclair.transactions.Transactions.{ClaimLocalAnchorOutputTx, HtlcSuccessTx, InputInfo}
import fr.acinq.eclair.{NodeParams, TestConstants, TestKitBaseClass, randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import fr.acinq.eclair.KotlinUtils._

import java.util.UUID
import scala.concurrent.duration.DurationInt

class TxPublisherSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, txPublisher: ActorRef[TxPublisher.Command], factory: TestProbe, probe: TestProbe) {
    def setFeerate(feerate: FeeratePerKw): Unit = {
      nodeParams.onChainFeeConf.feeEstimator.asInstanceOf[TestFeeEstimator].setFeerate(FeeratesPerKw.single(feerate))
    }
  }

  override def withFixture(test: OneArgTest): Outcome = {
    within(max = 30 seconds) {
      val nodeParams = TestConstants.Alice.nodeParams
      val factory = TestProbe()
      val probe = TestProbe()
      val txPublisher = system.spawnAnonymous(TxPublisher(nodeParams, randomKey().publicKey, FakeChildFactory(factory)))
      try {
        withFixture(test.toNoArgTest(FixtureParam(nodeParams, txPublisher, factory, probe)))
      } finally {
        system.stop(txPublisher.toClassic)
      }
    }
  }

  // @formatter:off
  case class RawTxPublisherSpawned(id: UUID, actor: TestProbe)
  case class ReplaceableTxPublisherSpawned(id: UUID, actor: TestProbe)
  case class FakeChildFactory(factoryProbe: TestProbe) extends TxPublisher.ChildFactory {
    override def spawnRawTxPublisher(context: ActorContext[TxPublisher.Command], loggingInfo: TxPublisher.TxPublishLogContext): ActorRef[RawTxPublisher.Command] = {
      val actor = TestProbe()
      factoryProbe.ref ! RawTxPublisherSpawned(loggingInfo.id, actor)
      actor.ref
    }
    override def spawnReplaceableTxPublisher(context: ActorContext[TxPublisher.Command], loggingInfo: TxPublisher.TxPublishLogContext): ActorRef[ReplaceableTxPublisher.Command] = {
      val actor = TestProbe()
      factoryProbe.ref ! ReplaceableTxPublisherSpawned(loggingInfo.id, actor)
      actor.ref
    }
  }
  // @formatter:on

  test("publish raw tx") { f =>
    import f._

    val tx = new Transaction(2, new TxIn(new OutPoint(randomBytes32(), 1), Nil, 0) :: Nil, Nil, 0)
    val cmd = PublishRawTx(tx, tx.txIn.head.outPoint, "raw-tx", 5 sat, None)
    txPublisher ! cmd
    val child = factory.expectMsgType[RawTxPublisherSpawned].actor
    assert(child.expectMsgType[RawTxPublisher.Publish].cmd === cmd)
  }

  test("publish raw tx duplicate") { f =>
    import f._

    val input = new OutPoint(randomBytes32(), 1)
    val tx1 = new Transaction(2, new TxIn(input, Nil, 0) :: Nil, Nil, 0)
    val cmd1 = PublishRawTx(tx1, input, "raw-tx", 10 sat, None)
    txPublisher ! cmd1
    factory.expectMsgType[RawTxPublisherSpawned]

    // We ignore duplicates:
    txPublisher ! cmd1.copy(desc = "raw-tx-second-attempt")
    factory.expectNoMessage(100 millis)

    // But a different tx spending the same main input is allowed:
    val tx2 = tx1.updateInputs(tx1.txIn ++ Seq(new TxIn(new OutPoint(randomBytes32(), 0), Nil, 0)))
    val cmd2 = PublishRawTx(tx2, input, "another-raw-tx", 0 sat, None)
    txPublisher ! cmd2
    factory.expectMsgType[RawTxPublisherSpawned]
  }

  test("publish replaceable tx") { f =>
    import f._

    f.setFeerate(FeeratePerKw(750 sat))
    val input = new OutPoint(randomBytes32(), 3)
    val cmd = PublishReplaceableTx(ClaimLocalAnchorOutputTx(InputInfo(input, new TxOut(25_000 sat, Nil), Nil), new Transaction(2, new TxIn(input, Nil, 0) :: Nil, Nil, 0)), null)
    txPublisher ! cmd
    val child = factory.expectMsgType[ReplaceableTxPublisherSpawned].actor
    val p = child.expectMsgType[ReplaceableTxPublisher.Publish]
    assert(p.cmd === cmd)
    assert(p.targetFeerate === FeeratePerKw(750 sat))
  }

  test("publish replaceable tx duplicate") { f =>
    import f._

    f.setFeerate(FeeratePerKw(750 sat))
    val input = new OutPoint(randomBytes32(), 3)
    val cmd = PublishReplaceableTx(ClaimLocalAnchorOutputTx(InputInfo(input, new TxOut(25_000 sat, Nil), Nil), new Transaction(2, new TxIn(input, Nil, 0) :: Nil, Nil, 0)), null)
    txPublisher ! cmd
    val child1 = factory.expectMsgType[ReplaceableTxPublisherSpawned].actor
    val p1 = child1.expectMsgType[ReplaceableTxPublisher.Publish]
    assert(p1.cmd === cmd)
    assert(p1.targetFeerate === FeeratePerKw(750 sat))

    // We ignore duplicates that use a lower feerate:
    f.setFeerate(FeeratePerKw(700 sat))
    txPublisher ! cmd
    factory.expectNoMessage(100 millis)

    // But we retry publishing if the feerate is greater than previous attempts:
    f.setFeerate(FeeratePerKw(1000 sat))
    txPublisher ! cmd
    val child2 = factory.expectMsgType[ReplaceableTxPublisherSpawned].actor
    val p2 = child2.expectMsgType[ReplaceableTxPublisher.Publish]
    assert(p2.cmd === cmd)
    assert(p2.targetFeerate === FeeratePerKw(1000 sat))
  }

  test("stop publishing attempts when transaction confirms") { f =>
    import f._

    val input = new OutPoint(randomBytes32(), 3)
    val tx1 = new Transaction(2, new TxIn(input, Nil, 0) :: Nil, Nil, 0)
    val cmd1 = PublishRawTx(tx1, input, "raw-tx-1", 5 sat, None)
    txPublisher ! cmd1
    val attempt1 = factory.expectMsgType[RawTxPublisherSpawned].actor
    attempt1.expectMsgType[RawTxPublisher.Publish]

    val tx2 = new Transaction(2, new TxIn(input, Nil, 0) :: new TxIn(new OutPoint(randomBytes32(), 0), Nil, 3) :: Nil, Nil, 0)
    val cmd2 = PublishRawTx(tx2, input, "raw-tx-2", 15 sat, None)
    txPublisher ! cmd2
    val attempt2 = factory.expectMsgType[RawTxPublisherSpawned].actor
    attempt2.expectMsgType[RawTxPublisher.Publish]

    val cmd3 = PublishReplaceableTx(ClaimLocalAnchorOutputTx(InputInfo(input, new TxOut(25_000 sat, Nil), Nil), new Transaction(2, new TxIn(input, Nil, 0) :: Nil, new TxOut(20_000 sat, Nil) :: Nil, 0)), null)
    txPublisher ! cmd3
    val attempt3 = factory.expectMsgType[ReplaceableTxPublisherSpawned].actor
    attempt3.expectMsgType[ReplaceableTxPublisher.Publish]

    txPublisher ! TxConfirmed(cmd2, tx2)
    attempt1.expectMsg(RawTxPublisher.Stop)
    attempt2.expectMsg(RawTxPublisher.Stop)
    attempt3.expectMsg(ReplaceableTxPublisher.Stop)
    factory.expectNoMessage(100 millis)
  }

  test("publishing attempt fails (wallet input gone)") { f =>
    import f._

    val input = new OutPoint(randomBytes32(), 3)
    val tx1 = new Transaction(2, new TxIn(input, Nil, 0) :: Nil, Nil, 0)
    val cmd1 = PublishRawTx(tx1, input, "raw-tx-1", 0 sat, None)
    txPublisher ! cmd1
    val attempt1 = factory.expectMsgType[RawTxPublisherSpawned]
    attempt1.actor.expectMsgType[RawTxPublisher.Publish]

    val cmd2 = PublishReplaceableTx(ClaimLocalAnchorOutputTx(InputInfo(input, new TxOut(25_000 sat, Nil), Nil), new Transaction(2, new TxIn(input, Nil, 0) :: Nil, new TxOut(20_000 sat, Nil) :: Nil, 0)), null)
    txPublisher ! cmd2
    val attempt2 = factory.expectMsgType[ReplaceableTxPublisherSpawned]
    attempt2.actor.expectMsgType[ReplaceableTxPublisher.Publish]

    txPublisher ! TxRejected(attempt2.id, cmd2, WalletInputGone)
    attempt2.actor.expectMsg(ReplaceableTxPublisher.Stop)
    attempt1.actor.expectNoMessage(100 millis) // this error doesn't impact other publishing attempts

    // We automatically retry the failed attempt with new wallet inputs:
    val attempt3 = factory.expectMsgType[ReplaceableTxPublisherSpawned]
    assert(attempt3.actor.expectMsgType[ReplaceableTxPublisher.Publish].cmd === cmd2)
  }

  test("publishing attempt fails (not enough funds)") { f =>
    import f._

    f.setFeerate(FeeratePerKw(600 sat))
    val input = new OutPoint(randomBytes32(), 7)
    val paymentHash = randomBytes32()
    val cmd1 = PublishReplaceableTx(HtlcSuccessTx(InputInfo(input, new TxOut(25_000 sat, Nil), Nil), new Transaction(2, new TxIn(input, Nil, 0) :: Nil, Nil, 0), paymentHash, 3), null)
    txPublisher ! cmd1
    val attempt1 = factory.expectMsgType[ReplaceableTxPublisherSpawned]
    attempt1.actor.expectMsgType[ReplaceableTxPublisher.Publish]

    f.setFeerate(FeeratePerKw(750 sat))
    val cmd2 = PublishReplaceableTx(HtlcSuccessTx(InputInfo(input, new TxOut(25_000 sat, Nil), Nil), new Transaction(2, new TxIn(input, Nil, 0) :: Nil, Nil, 0), paymentHash, 3), null)
    txPublisher ! cmd2
    val attempt2 = factory.expectMsgType[ReplaceableTxPublisherSpawned]
    attempt2.actor.expectMsgType[ReplaceableTxPublisher.Publish]

    txPublisher ! TxRejected(attempt2.id, cmd2, CouldNotFund)
    attempt2.actor.expectMsg(ReplaceableTxPublisher.Stop)
    attempt1.actor.expectNoMessage(100 millis) // this error doesn't impact other publishing attempts

    // We automatically retry the failed attempt once a new block is found (we may have more funds now):
    factory.expectNoMessage(100 millis)
    system.eventStream.publish(CurrentBlockCount(8200))
    val attempt3 = factory.expectMsgType[ReplaceableTxPublisherSpawned]
    assert(attempt3.actor.expectMsgType[ReplaceableTxPublisher.Publish].cmd === cmd2)
  }

  test("publishing attempt fails (transaction skipped)") { f =>
    import f._

    val tx1 = new Transaction(2, new TxIn(new OutPoint(randomBytes32(), 1), Nil, 0) :: Nil, Nil, 0)
    val cmd1 = PublishRawTx(tx1, tx1.txIn.head.outPoint, "raw-tx-1", 0 sat, None)
    txPublisher ! cmd1
    val attempt1 = factory.expectMsgType[RawTxPublisherSpawned]
    attempt1.actor.expectMsgType[RawTxPublisher.Publish]

    val tx2 = new Transaction(2, new TxIn(new OutPoint(randomBytes32(), 0), Nil, 0) :: Nil, Nil, 0)
    val cmd2 = PublishRawTx(tx2, tx2.txIn.head.outPoint, "raw-tx-2", 5 sat, None)
    txPublisher ! cmd2
    val attempt2 = factory.expectMsgType[RawTxPublisherSpawned]
    attempt2.actor.expectMsgType[RawTxPublisher.Publish]

    txPublisher ! TxRejected(attempt1.id, cmd1, TxSkipped(retryNextBlock = false))
    attempt1.actor.expectMsg(RawTxPublisher.Stop)
    txPublisher ! TxRejected(attempt2.id, cmd2, TxSkipped(retryNextBlock = true))
    attempt2.actor.expectMsg(RawTxPublisher.Stop)
    factory.expectNoMessage(100 millis)

    system.eventStream.publish(CurrentBlockCount(8200))
    val attempt3 = factory.expectMsgType[RawTxPublisherSpawned]
    assert(attempt3.actor.expectMsgType[publish.RawTxPublisher.Publish].cmd === cmd2)
    factory.expectNoMessage(100 millis)
  }

  test("publishing attempt fails (unconfirmed conflicting transaction)") { f =>
    import f._

    val tx = new Transaction(2, new TxIn(new OutPoint(randomBytes32(), 1), Nil, 0) :: Nil, Nil, 0)
    val cmd = PublishRawTx(tx, tx.txIn.head.outPoint, "raw-tx", 5 sat, None)
    txPublisher ! cmd
    val attempt = factory.expectMsgType[RawTxPublisherSpawned]
    attempt.actor.expectMsgType[RawTxPublisher.Publish]

    txPublisher ! TxRejected(attempt.id, cmd, ConflictingTxUnconfirmed)
    attempt.actor.expectMsg(RawTxPublisher.Stop)

    // We don't retry, even after a new block has been found:
    system.eventStream.publish(CurrentBlockCount(8200))
    factory.expectNoMessage(100 millis)
  }

  test("publishing attempt fails (confirmed conflicting transaction)") { f =>
    import f._

    val tx = new Transaction(2, new TxIn(new OutPoint(randomBytes32(), 1), Nil, 0) :: Nil, Nil, 0)
    val cmd = PublishRawTx(tx, tx.txIn.head.outPoint, "raw-tx", 5 sat, None)
    txPublisher ! cmd
    val attempt = factory.expectMsgType[RawTxPublisherSpawned]
    attempt.actor.expectMsgType[RawTxPublisher.Publish]

    txPublisher ! TxRejected(attempt.id, cmd, ConflictingTxConfirmed)
    attempt.actor.expectMsg(RawTxPublisher.Stop)

    // We don't retry, even after a new block has been found:
    system.eventStream.publish(CurrentBlockCount(8200))
    factory.expectNoMessage(100 millis)
  }

  test("publishing attempt fails (unknown failure)") { f =>
    import f._

    val tx = new Transaction(2, new TxIn(new OutPoint(randomBytes32(), 1), Nil, 0) :: Nil, Nil, 0)
    val cmd = PublishRawTx(tx, tx.txIn.head.outPoint, "raw-tx", 5 sat, None)
    txPublisher ! cmd
    val attempt = factory.expectMsgType[RawTxPublisherSpawned]
    attempt.actor.expectMsgType[RawTxPublisher.Publish]

    txPublisher ! TxRejected(attempt.id, cmd, UnknownTxFailure)
    attempt.actor.expectMsg(RawTxPublisher.Stop)

    // We don't retry, even after a new block has been found:
    system.eventStream.publish(CurrentBlockCount(8200))
    factory.expectNoMessage(100 millis)
  }

}
