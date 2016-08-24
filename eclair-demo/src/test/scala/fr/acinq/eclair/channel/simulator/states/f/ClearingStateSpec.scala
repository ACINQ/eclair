package fr.acinq.eclair.channel.simulator.states.f

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin.{Crypto, Satoshi, Script, ScriptFlags, Transaction, TxOut}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.{TestBitcoinClient, _}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.simulator.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{BITCOIN_ANCHOR_DEPTHOK, Data, State, _}
import lightning._
import lightning.locktime.Locktime.Blocks
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, fixture}

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class ClearingStateSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike with BeforeAndAfterAll with StateTestsHelperMethods {

  type FixtureParam = Tuple6[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val bob2blockchain = TestProbe()
    val paymentHandler = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams, "A"))
    alice2bob.expectMsgType[open_channel]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[open_channel]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[MakeAnchor]
    alice2blockchain.forward(blockchainA)
    alice2bob.expectMsgType[open_anchor]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[open_commit_sig]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchConfirmed]
    alice2blockchain.forward(blockchainA)
    alice2blockchain.expectMsgType[WatchSpent]
    alice2blockchain.forward(blockchainA)
    alice2blockchain.expectMsgType[Publish]
    alice2blockchain.forward(blockchainA)
    bob2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[WatchSpent]
    bob ! BITCOIN_ANCHOR_DEPTHOK
    bob2blockchain.expectMsgType[WatchLost]
    bob2alice.expectMsgType[open_complete]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchLost]
    alice2blockchain.forward(blockchainA)
    alice2bob.expectMsgType[open_complete]
    alice2bob.forward(bob)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    // note : alice is funder and bob is fundee, so alice has all the money
    val sender = TestProbe()
    // alice sends an HTLC to bob
    val r1: rval = rval(1, 1, 1, 1)
    val h1: sha256_hash = Crypto.sha256(r1)
    val amount1 = 300000000
    sender.send(alice, CMD_ADD_HTLC(amount1, h1, locktime(Blocks(1440))))
    sender.expectMsg("ok")
    val htlc1 = alice2bob.expectMsgType[update_add_htlc]
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc1 :: Nil)
    val r2: rval = rval(2, 2, 2, 2)
    val h2: sha256_hash = Crypto.sha256(r2)
    val amount2 = 200000000
    sender.send(alice, CMD_ADD_HTLC(amount2, h2, locktime(Blocks(1440))))
    sender.expectMsg("ok")
    val htlc2 = alice2bob.expectMsgType[update_add_htlc]
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc1 :: htlc2 :: Nil)
    // alice signs
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[update_commit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[update_revocation]
    bob2alice.forward(alice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == Nil && bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.acked == htlc1 :: htlc2 :: Nil)
    // alice initiates a closing
    sender.send(alice, CMD_CLOSE(None))
    alice2bob.expectMsgType[close_clearing]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[close_clearing]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == CLEARING)
    awaitCond(bob.stateName == CLEARING)
    test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  test("recv CMD_FULFILL_HTLC") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_CLEARING]
      sender.send(bob, CMD_FULFILL_HTLC(1, rval(1, 1, 1, 1)))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[update_fulfill_htlc]
      awaitCond(bob.stateData == initialState.copy(commitments = initialState.commitments.copy(ourChanges = initialState.commitments.ourChanges.copy(initialState.commitments.ourChanges.proposed :+ fulfill))))
    }
  }

  test("recv CMD_FULFILL_HTLC (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_CLEARING]
      sender.send(bob, CMD_FULFILL_HTLC(42, rval(1, 2, 3, 4)))
      sender.expectMsg("unknown htlc id=42")
      assert(initialState == bob.stateData)
    }
  }

  test("recv CMD_FULFILL_HTLC (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_CLEARING]
      sender.send(bob, CMD_FULFILL_HTLC(1, rval(0, 0, 0, 0)))
      sender.expectMsg("invalid htlc preimage for htlc id=1")
      assert(initialState == bob.stateData)
    }
  }

  test("recv update_fulfill_htlc") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = alice.stateData.asInstanceOf[DATA_CLEARING]
      val fulfill = update_fulfill_htlc(1, rval(1, 1, 1, 1))
      sender.send(alice, fulfill)
      awaitCond(alice.stateData.asInstanceOf[DATA_CLEARING].commitments == initialState.commitments.copy(theirChanges = initialState.commitments.theirChanges.copy(initialState.commitments.theirChanges.proposed :+ fulfill)))
    }
  }

  test("recv update_fulfill_htlc (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      val fulfill = update_fulfill_htlc(42, rval(0, 0, 0, 0))
      sender.send(alice, fulfill)
      alice2bob.expectMsgType[error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv update_fulfill_htlc (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      sender.send(alice, update_fulfill_htlc(42, rval(0, 0, 0, 0)))
      alice2bob.expectMsgType[error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_FAIL_HTLC") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_CLEARING]
      sender.send(bob, CMD_FAIL_HTLC(1, "some reason"))
      sender.expectMsg("ok")
      val fail = bob2alice.expectMsgType[update_fail_htlc]
      awaitCond(bob.stateData == initialState.copy(commitments = initialState.commitments.copy(ourChanges = initialState.commitments.ourChanges.copy(initialState.commitments.ourChanges.proposed :+ fail))))
    }
  }

  test("recv CMD_FAIL_HTLC (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_CLEARING]
      sender.send(bob, CMD_FAIL_HTLC(42, "some reason"))
      sender.expectMsg("unknown htlc id=42")
      assert(initialState == bob.stateData)
    }
  }

  test("recv update_fail_htlc") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = alice.stateData.asInstanceOf[DATA_CLEARING]
      val fail = update_fail_htlc(1, fail_reason(ByteString.copyFromUtf8("some reason")))
      sender.send(alice, fail)
      awaitCond(alice.stateData.asInstanceOf[DATA_CLEARING].commitments == initialState.commitments.copy(theirChanges = initialState.commitments.theirChanges.copy(initialState.commitments.theirChanges.proposed :+ fail)))
    }
  }

  test("recv update_fail_htlc (unknown htlc id)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      sender.send(alice, update_fail_htlc(42, fail_reason(ByteString.copyFromUtf8("some reason"))))
      alice2bob.expectMsgType[error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_SIGN") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      // we need to have something to sign so we first send a fulfill and acknowledge (=sign) it
      sender.send(bob, CMD_FULFILL_HTLC(1, rval(1, 1, 1, 1)))
      bob2alice.expectMsgType[update_fulfill_htlc]
      bob2alice.forward(alice)
      sender.send(bob, CMD_SIGN)
      bob2alice.expectMsgType[update_commit]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[update_revocation]
      alice2bob.forward(bob)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      awaitCond(alice.stateData.asInstanceOf[DATA_CLEARING].commitments.theirNextCommitInfo.isLeft)
    }
  }

  test("recv CMD_SIGN (no changes)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_SIGN)
      sender.expectNoMsg() // just ignored
      //sender.expectMsg("cannot sign when there are no changes")
    }
  }

  ignore("recv CMD_SIGN (while waiting for update_revocation)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      sender.send(alice, update_fulfill_htlc(1, rval(1, 2, 3, 4)))
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      awaitCond(alice.stateData.asInstanceOf[DATA_CLEARING].commitments.theirNextCommitInfo.isLeft)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("cannot sign until next revocation hash is received")
    }
  }

  test("recv update_commit") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(bob, CMD_FULFILL_HTLC(1, rval(1, 1, 1, 1)))
      sender.expectMsg("ok")
      bob2alice.expectMsgType[update_fulfill_htlc]
      bob2alice.forward(alice)
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[update_commit]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[update_revocation]
    }
  }

  test("recv update_commit (no changes)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      // signature is invalid but it doesn't matter
      sender.send(bob, update_commit(signature(0, 0, 0, 0, 0, 0, 0, 0)))
      bob2alice.expectMsgType[error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv update_commit (invalid signature)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      sender.send(bob, update_commit(signature(0, 0, 0, 0, 0, 0, 0, 0)))
      bob2alice.expectMsgType[error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv update_revocation (with remaining htlcs on both sides)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      fulfillHtlc(2, rval(2, 2, 2, 2), bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      val sender = TestProbe()
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[update_revocation]
      // actual test starts here
      bob2alice.forward(bob)
      assert(alice.stateName == CLEARING)
      awaitCond(alice.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.spec.htlcs.size == 1)
      awaitCond(alice.stateData.asInstanceOf[DATA_CLEARING].commitments.theirCommit.spec.htlcs.size == 2)
    }
  }

  test("recv update_revocation (with remaining htlcs on one side)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      fulfillHtlc(1, rval(1, 1, 1, 1), bob, alice, bob2alice, alice2bob)
      fulfillHtlc(2, rval(2, 2, 2, 2), bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      val sender = TestProbe()
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[update_revocation]
      // actual test starts here
      bob2alice.forward(bob)
      assert(alice.stateName == CLEARING)
      awaitCond(alice.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.spec.htlcs.isEmpty)
      awaitCond(alice.stateData.asInstanceOf[DATA_CLEARING].commitments.theirCommit.spec.htlcs.size == 2)
    }
  }

  test("recv update_revocation (no more htlcs on either side)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      fulfillHtlc(1, rval(1, 1, 1, 1), bob, alice, bob2alice, alice2bob)
      fulfillHtlc(2, rval(2, 2, 2, 2), bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      val sender = TestProbe()
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[update_revocation]
      // actual test starts here
      bob2alice.forward(alice)
      awaitCond(alice.stateName == NEGOTIATING)
    }
  }

  test("recv update_revocation (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      sender.send(bob, CMD_FULFILL_HTLC(1, rval(1, 1, 1, 1)))
      sender.expectMsg("ok")
      bob2alice.expectMsgType[update_fulfill_htlc]
      bob2alice.forward(alice)
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[update_commit]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[update_revocation]
      awaitCond(bob.stateData.asInstanceOf[DATA_CLEARING].commitments.theirNextCommitInfo.isLeft)
      sender.send(bob, update_revocation(sha256_hash(0, 0, 0, 0), sha256_hash(1, 1, 1, 1)))
      bob2alice.expectMsgType[error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv update_revocation (unexpectedly)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_CLEARING].commitments.theirNextCommitInfo.isRight)
      sender.send(alice, update_revocation(sha256_hash(0, 0, 0, 0), sha256_hash(1, 1, 1, 1)))
      alice2bob.expectMsgType[error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT (their commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      // bob publishes his current commit tx, which contains two pending htlcs alice->bob
      val bobCommitTx = bob.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      assert(bobCommitTx.txOut.size == 3) // one main outputs (bob has zero) and 2 pending htlcs
      alice ! (BITCOIN_ANCHOR_SPENT, bobCommitTx)

      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid

      val amountClaimed = (for (i <- 0 until 2) yield {
      val claimHtlcTx = alice2blockchain.expectMsgType[PublishAsap].tx
        assert(claimHtlcTx.txIn.size == 1)
        val previousOutputs = Map(claimHtlcTx.txIn(0).outPoint -> bobCommitTx.txOut(claimHtlcTx.txIn(0).outPoint.index.toInt))
        Transaction.correctlySpends(claimHtlcTx, previousOutputs, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assert(claimHtlcTx.txOut.size == 1)
        claimHtlcTx.txOut(0).amount
      }).sum
      assert(amountClaimed == Satoshi(500000))

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].theirCommitPublished == Some(bobCommitTx))
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT (revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val revokedTx = bob.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx

      // bob fulfills one of the pending htlc (just so that he can have a new sig)
      fulfillHtlc(1, rval(1, 1, 1, 1), bob, alice, bob2alice, alice2bob)
      // alice signs
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      // bob now has a new commitment tx

      // bob published the revoked tx
      alice ! (BITCOIN_ANCHOR_SPENT, revokedTx)
      alice2bob.expectMsgType[error]
      val punishTx = alice2blockchain.expectMsgType[Publish].tx
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == CLOSING)
      Transaction.correctlySpends(punishTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      // one main output + 2 htlc
      assert(revokedTx.txOut.size == 3)
      // the punishment tx consumes all output but ours (which already goes to our final key)
      assert(punishTx.txIn.size == 2)
      assert(punishTx.txOut == Seq(TxOut(Satoshi(500000), Script.write(Scripts.pay2wpkh(Alice.finalPubKey)))))
    }
  }

  test("recv error") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      alice ! error(Some("oops"))
      alice2blockchain.expectMsg(Publish(aliceCommitTx))
      assert(aliceCommitTx.txOut.size == 1) // only one main output

      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid
      alice2blockchain.expectNoMsg()

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].ourCommitPublished == Some(aliceCommitTx))
    }
  }

}
