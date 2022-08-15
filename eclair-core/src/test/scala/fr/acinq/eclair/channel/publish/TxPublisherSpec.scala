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
import fr.acinq.bitcoin.scalacompat.{OutPoint, SatoshiLong, Transaction, TxIn, TxOut}
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.channel.publish
import fr.acinq.eclair.channel.publish.TxPublisher.TxRejectedReason._
import fr.acinq.eclair.channel.publish.TxPublisher._
import fr.acinq.eclair.transactions.Transactions.{ClaimLocalAnchorOutputTx, HtlcSuccessTx, InputInfo}
import fr.acinq.eclair.{BlockHeight, NodeParams, TestConstants, TestKitBaseClass, randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike

import java.util.UUID
import scala.concurrent.duration.DurationInt

class TxPublisherSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, txPublisher: ActorRef[TxPublisher.Command], factory: TestProbe, probe: TestProbe)

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
  case class FinalTxPublisherSpawned(id: UUID, actor: TestProbe)
  case class ReplaceableTxPublisherSpawned(id: UUID, actor: TestProbe)
  case class FakeChildFactory(factoryProbe: TestProbe) extends TxPublisher.ChildFactory {
    override def spawnFinalTxPublisher(context: ActorContext[TxPublisher.Command], txPublishContext: TxPublisher.TxPublishContext): ActorRef[FinalTxPublisher.Command] = {
      val actor = TestProbe()
      factoryProbe.ref ! FinalTxPublisherSpawned(txPublishContext.id, actor)
      actor.ref
    }
    override def spawnReplaceableTxPublisher(context: ActorContext[TxPublisher.Command], txPublishContext: TxPublisher.TxPublishContext): ActorRef[ReplaceableTxPublisher.Command] = {
      val actor = TestProbe()
      factoryProbe.ref ! ReplaceableTxPublisherSpawned(txPublishContext.id, actor)
      actor.ref
    }
  }
  // @formatter:on

  test("publish final tx") { f =>
    import f._

    val tx = Transaction(2, TxIn(OutPoint(randomBytes32(), 1), Nil, 0) :: Nil, Nil, 0)
    val cmd = PublishFinalTx(tx, tx.txIn.head.outPoint, "final-tx", 5 sat, None)
    txPublisher ! cmd
    val child = factory.expectMsgType[FinalTxPublisherSpawned].actor
    assert(child.expectMsgType[FinalTxPublisher.Publish].cmd == cmd)
  }

  test("publish final tx duplicate") { f =>
    import f._

    val input = OutPoint(randomBytes32(), 1)
    val tx1 = Transaction(2, TxIn(input, Nil, 0) :: Nil, Nil, 0)
    val cmd1 = PublishFinalTx(tx1, input, "final-tx", 10 sat, None)
    txPublisher ! cmd1
    factory.expectMsgType[FinalTxPublisherSpawned]

    // We ignore duplicates:
    txPublisher ! cmd1.copy(desc = "final-tx-second-attempt")
    factory.expectNoMessage(100 millis)

    // But a different tx spending the same main input is allowed:
    val tx2 = tx1.copy(txIn = tx1.txIn ++ Seq(TxIn(OutPoint(randomBytes32(), 0), Nil, 0)))
    val cmd2 = PublishFinalTx(tx2, input, "another-final-tx", 0 sat, None)
    txPublisher ! cmd2
    factory.expectMsgType[FinalTxPublisherSpawned]
  }

  test("publish replaceable tx") { f =>
    import f._

    val confirmBefore = nodeParams.currentBlockHeight + 12
    val input = OutPoint(randomBytes32(), 3)
    val cmd = PublishReplaceableTx(ClaimLocalAnchorOutputTx(InputInfo(input, TxOut(25_000 sat, Nil), Nil), Transaction(2, TxIn(input, Nil, 0) :: Nil, Nil, 0), confirmBefore), null)
    txPublisher ! cmd
    val child = factory.expectMsgType[ReplaceableTxPublisherSpawned].actor
    val p = child.expectMsgType[ReplaceableTxPublisher.Publish]
    assert(p.cmd == cmd)
  }

  test("publish replaceable tx duplicate") { f =>
    import f._

    val confirmBefore = nodeParams.currentBlockHeight + 12
    val input = OutPoint(randomBytes32(), 3)
    val anchorTx = ClaimLocalAnchorOutputTx(InputInfo(input, TxOut(25_000 sat, Nil), Nil), Transaction(2, TxIn(input, Nil, 0) :: Nil, Nil, 0), confirmBefore)
    val cmd = PublishReplaceableTx(anchorTx, null)
    txPublisher ! cmd
    val child = factory.expectMsgType[ReplaceableTxPublisherSpawned].actor
    assert(child.expectMsgType[ReplaceableTxPublisher.Publish].cmd == cmd)

    // We ignore duplicates that don't use a more aggressive confirmation target:
    txPublisher ! PublishReplaceableTx(anchorTx, null)
    child.expectNoMessage(100 millis)
    factory.expectNoMessage(100 millis)
    val cmdHigherTarget = cmd.copy(txInfo = anchorTx.copy(confirmBefore = confirmBefore + 1))
    txPublisher ! cmdHigherTarget
    child.expectNoMessage(100 millis)
    factory.expectNoMessage(100 millis)

    // But we update the confirmation target when it is more aggressive than previous attempts:
    val cmdLowerTarget = cmd.copy(txInfo = anchorTx.copy(confirmBefore = confirmBefore - 6))
    txPublisher ! cmdLowerTarget
    child.expectMsg(ReplaceableTxPublisher.UpdateConfirmationTarget(confirmBefore - 6))
    factory.expectNoMessage(100 millis)

    // And we update our internal threshold accordingly:
    val cmdInBetween = cmd.copy(txInfo = anchorTx.copy(confirmBefore = confirmBefore - 3))
    txPublisher ! cmdInBetween
    child.expectNoMessage(100 millis)
    factory.expectNoMessage(100 millis)
  }

  test("stop publishing attempts when transaction confirms") { f =>
    import f._

    val input = OutPoint(randomBytes32(), 3)
    val tx1 = Transaction(2, TxIn(input, Nil, 0) :: Nil, Nil, 0)
    val cmd1 = PublishFinalTx(tx1, input, "final-tx-1", 5 sat, None)
    txPublisher ! cmd1
    val attempt1 = factory.expectMsgType[FinalTxPublisherSpawned].actor
    attempt1.expectMsgType[FinalTxPublisher.Publish]

    val tx2 = Transaction(2, TxIn(input, Nil, 0) :: TxIn(OutPoint(randomBytes32(), 0), Nil, 3) :: Nil, Nil, 0)
    val cmd2 = PublishFinalTx(tx2, input, "final-tx-2", 15 sat, None)
    txPublisher ! cmd2
    val attempt2 = factory.expectMsgType[FinalTxPublisherSpawned].actor
    attempt2.expectMsgType[FinalTxPublisher.Publish]

    val cmd3 = PublishReplaceableTx(ClaimLocalAnchorOutputTx(InputInfo(input, TxOut(25_000 sat, Nil), Nil), Transaction(2, TxIn(input, Nil, 0) :: Nil, TxOut(20_000 sat, Nil) :: Nil, 0), nodeParams.currentBlockHeight), null)
    txPublisher ! cmd3
    val attempt3 = factory.expectMsgType[ReplaceableTxPublisherSpawned].actor
    attempt3.expectMsgType[ReplaceableTxPublisher.Publish]

    txPublisher ! TxConfirmed(cmd2, tx2)
    attempt1.expectMsg(FinalTxPublisher.Stop)
    attempt2.expectMsg(FinalTxPublisher.Stop)
    attempt3.expectMsg(ReplaceableTxPublisher.Stop)
    factory.expectNoMessage(100 millis)
  }

  test("publishing attempt fails (wallet input gone)") { f =>
    import f._

    val input = OutPoint(randomBytes32(), 3)
    val tx1 = Transaction(2, TxIn(input, Nil, 0) :: Nil, Nil, 0)
    val cmd1 = PublishFinalTx(tx1, input, "final-tx-1", 0 sat, None)
    txPublisher ! cmd1
    val attempt1 = factory.expectMsgType[FinalTxPublisherSpawned]
    attempt1.actor.expectMsgType[FinalTxPublisher.Publish]

    val cmd2 = PublishReplaceableTx(ClaimLocalAnchorOutputTx(InputInfo(input, TxOut(25_000 sat, Nil), Nil), Transaction(2, TxIn(input, Nil, 0) :: Nil, TxOut(20_000 sat, Nil) :: Nil, 0), nodeParams.currentBlockHeight), null)
    txPublisher ! cmd2
    val attempt2 = factory.expectMsgType[ReplaceableTxPublisherSpawned]
    attempt2.actor.expectMsgType[ReplaceableTxPublisher.Publish]

    txPublisher ! TxRejected(attempt2.id, cmd2, InputGone)
    attempt2.actor.expectMsg(ReplaceableTxPublisher.Stop)
    attempt1.actor.expectNoMessage(100 millis) // this error doesn't impact other publishing attempts

    // We automatically retry the failed attempt with new wallet inputs:
    val attempt3 = factory.expectMsgType[ReplaceableTxPublisherSpawned]
    assert(attempt3.actor.expectMsgType[ReplaceableTxPublisher.Publish].cmd == cmd2)
  }

  test("publishing attempt fails (main input gone)") { f =>
    import f._

    val input = OutPoint(randomBytes32(), 3)
    val tx = Transaction(2, TxIn(input, Nil, 0) :: Nil, Nil, 0)
    val cmd = PublishFinalTx(tx, input, "final-tx", 0 sat, None)
    txPublisher ! cmd
    val attempt1 = factory.expectMsgType[FinalTxPublisherSpawned]
    attempt1.actor.expectMsgType[FinalTxPublisher.Publish]

    txPublisher ! TxRejected(attempt1.id, cmd, InputGone)
    attempt1.actor.expectMsg(FinalTxPublisher.Stop)

    // We don't retry until a new block is found.
    factory.expectNoMessage(100 millis)
    system.eventStream.publish(CurrentBlockHeight(BlockHeight(8200)))
    val attempt2 = factory.expectMsgType[FinalTxPublisherSpawned]
    assert(attempt2.actor.expectMsgType[FinalTxPublisher.Publish].cmd == cmd)
  }

  test("publishing attempt fails (not enough funds)") { f =>
    import f._

    val target = nodeParams.currentBlockHeight + 12
    val input = OutPoint(randomBytes32(), 7)
    val paymentHash = randomBytes32()
    val cmd = PublishReplaceableTx(HtlcSuccessTx(InputInfo(input, TxOut(25_000 sat, Nil), Nil), Transaction(2, TxIn(input, Nil, 0) :: Nil, Nil, 0), paymentHash, 3, target), null)
    txPublisher ! cmd
    val attempt1 = factory.expectMsgType[ReplaceableTxPublisherSpawned]
    attempt1.actor.expectMsgType[ReplaceableTxPublisher.Publish]

    txPublisher ! TxRejected(attempt1.id, cmd, CouldNotFund)
    attempt1.actor.expectMsg(ReplaceableTxPublisher.Stop)

    // We automatically retry the failed attempt once a new block is found (we may have more funds now):
    factory.expectNoMessage(100 millis)
    system.eventStream.publish(CurrentBlockHeight(BlockHeight(8200)))
    val attempt2 = factory.expectMsgType[ReplaceableTxPublisherSpawned]
    assert(attempt2.actor.expectMsgType[ReplaceableTxPublisher.Publish].cmd == cmd)
  }

  test("publishing attempt fails (transaction skipped)") { f =>
    import f._

    val tx1 = Transaction(2, TxIn(OutPoint(randomBytes32(), 1), Nil, 0) :: Nil, Nil, 0)
    val cmd1 = PublishFinalTx(tx1, tx1.txIn.head.outPoint, "final-tx-1", 0 sat, None)
    txPublisher ! cmd1
    val attempt1 = factory.expectMsgType[FinalTxPublisherSpawned]
    attempt1.actor.expectMsgType[FinalTxPublisher.Publish]

    val tx2 = Transaction(2, TxIn(OutPoint(randomBytes32(), 0), Nil, 0) :: Nil, Nil, 0)
    val cmd2 = PublishFinalTx(tx2, tx2.txIn.head.outPoint, "final-tx-2", 5 sat, None)
    txPublisher ! cmd2
    val attempt2 = factory.expectMsgType[FinalTxPublisherSpawned]
    attempt2.actor.expectMsgType[FinalTxPublisher.Publish]

    txPublisher ! TxRejected(attempt1.id, cmd1, TxSkipped(retryNextBlock = false))
    attempt1.actor.expectMsg(FinalTxPublisher.Stop)
    txPublisher ! TxRejected(attempt2.id, cmd2, TxSkipped(retryNextBlock = true))
    attempt2.actor.expectMsg(FinalTxPublisher.Stop)
    factory.expectNoMessage(100 millis)

    system.eventStream.publish(CurrentBlockHeight(BlockHeight(8200)))
    val attempt3 = factory.expectMsgType[FinalTxPublisherSpawned]
    assert(attempt3.actor.expectMsgType[publish.FinalTxPublisher.Publish].cmd == cmd2)
    factory.expectNoMessage(100 millis)
  }

  test("publishing attempt fails (unconfirmed conflicting raw transaction)") { f =>
    import f._

    val tx = Transaction(2, TxIn(OutPoint(randomBytes32(), 1), Nil, 0) :: Nil, Nil, 0)
    val cmd = PublishFinalTx(tx, tx.txIn.head.outPoint, "final-tx", 5 sat, None)
    txPublisher ! cmd
    val attempt = factory.expectMsgType[FinalTxPublisherSpawned]
    attempt.actor.expectMsgType[FinalTxPublisher.Publish]

    txPublisher ! TxRejected(attempt.id, cmd, ConflictingTxUnconfirmed)
    attempt.actor.expectMsg(FinalTxPublisher.Stop)

    // We don't retry, even after a new block has been found:
    system.eventStream.publish(CurrentBlockHeight(BlockHeight(8200)))
    factory.expectNoMessage(100 millis)
  }

  test("publishing attempt fails (unconfirmed conflicting replaceable transaction)") { f =>
    import f._

    val input = OutPoint(randomBytes32(), 7)
    val paymentHash = randomBytes32()
    val cmd = PublishReplaceableTx(HtlcSuccessTx(InputInfo(input, TxOut(25_000 sat, Nil), Nil), Transaction(2, TxIn(input, Nil, 0) :: Nil, Nil, 0), paymentHash, 3, nodeParams.currentBlockHeight), null)
    txPublisher ! cmd
    val attempt1 = factory.expectMsgType[ReplaceableTxPublisherSpawned]
    attempt1.actor.expectMsgType[ReplaceableTxPublisher.Publish]

    txPublisher ! TxRejected(attempt1.id, cmd, ConflictingTxUnconfirmed)
    attempt1.actor.expectMsg(ReplaceableTxPublisher.Stop)
    factory.expectNoMessage(100 millis)

    // We retry when a new block is found:
    system.eventStream.publish(CurrentBlockHeight(nodeParams.currentBlockHeight + 1))
    val attempt2 = factory.expectMsgType[ReplaceableTxPublisherSpawned]
    assert(attempt2.actor.expectMsgType[ReplaceableTxPublisher.Publish].cmd == cmd)
  }

  test("publishing attempt fails (confirmed conflicting transaction)") { f =>
    import f._

    val tx = Transaction(2, TxIn(OutPoint(randomBytes32(), 1), Nil, 0) :: Nil, Nil, 0)
    val cmd = PublishFinalTx(tx, tx.txIn.head.outPoint, "final-tx", 5 sat, None)
    txPublisher ! cmd
    val attempt = factory.expectMsgType[FinalTxPublisherSpawned]
    attempt.actor.expectMsgType[FinalTxPublisher.Publish]

    txPublisher ! TxRejected(attempt.id, cmd, ConflictingTxConfirmed)
    attempt.actor.expectMsg(FinalTxPublisher.Stop)

    // We don't retry, even after a new block has been found:
    system.eventStream.publish(CurrentBlockHeight(BlockHeight(8200)))
    factory.expectNoMessage(100 millis)
  }

  test("publishing attempt fails (unknown failure)") { f =>
    import f._

    val tx = Transaction(2, TxIn(OutPoint(randomBytes32(), 1), Nil, 0) :: Nil, Nil, 0)
    val cmd = PublishFinalTx(tx, tx.txIn.head.outPoint, "final-tx", 5 sat, None)
    txPublisher ! cmd
    val attempt = factory.expectMsgType[FinalTxPublisherSpawned]
    attempt.actor.expectMsgType[FinalTxPublisher.Publish]

    txPublisher ! TxRejected(attempt.id, cmd, UnknownTxFailure)
    attempt.actor.expectMsg(FinalTxPublisher.Stop)

    // We don't retry, even after a new block has been found:
    system.eventStream.publish(CurrentBlockHeight(BlockHeight(8200)))
    factory.expectNoMessage(100 millis)
  }

  test("update publishing attempts") { () =>
    {
      // No attempts.
      val attempts = PublishAttempts.empty
      assert(attempts.isEmpty)
      assert(attempts.count == 0)
      assert(attempts.attempts.isEmpty)
      assert(attempts.remove(UUID.randomUUID()) == (Nil, attempts))
    }
    {
      // Only final attempts.
      val attempt1 = FinalAttempt(UUID.randomUUID(), null, null)
      val attempt2 = FinalAttempt(UUID.randomUUID(), null, null)
      val attempts = PublishAttempts.empty.add(attempt1).add(attempt2)
      assert(!attempts.isEmpty)
      assert(attempts.count == 2)
      assert(attempts.replaceableAttempt_opt.isEmpty)
      assert(attempts.remove(UUID.randomUUID()) == (Nil, attempts))
      assert(attempts.remove(attempt1.id) == (Seq(attempt1), PublishAttempts(Seq(attempt2), None)))
    }
    {
      // Only replaceable attempts.
      val attempt = ReplaceableAttempt(UUID.randomUUID(), null, BlockHeight(0), null)
      val attempts = PublishAttempts(Nil, Some(attempt))
      assert(!attempts.isEmpty)
      assert(attempts.count == 1)
      assert(attempts.remove(UUID.randomUUID()) == (Nil, attempts))
      assert(attempts.remove(attempt.id) == (Seq(attempt), PublishAttempts.empty))
    }
    {
      // Mix of final and replaceable attempts with the same id.
      val attempt1 = ReplaceableAttempt(UUID.randomUUID(), null, BlockHeight(0), null)
      val attempt2 = FinalAttempt(attempt1.id, null, null)
      val attempt3 = FinalAttempt(UUID.randomUUID(), null, null)
      val attempts = PublishAttempts(Seq(attempt2), Some(attempt1)).add(attempt3)
      assert(!attempts.isEmpty)
      assert(attempts.count == 3)
      assert(attempts.remove(attempt3.id) == (Seq(attempt3), PublishAttempts(Seq(attempt2), Some(attempt1))))
      assert(attempts.remove(attempt1.id) == (Seq(attempt2, attempt1), PublishAttempts(Seq(attempt3), None)))
    }
  }

}
