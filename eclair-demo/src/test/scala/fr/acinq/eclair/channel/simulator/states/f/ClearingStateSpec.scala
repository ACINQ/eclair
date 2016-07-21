package fr.acinq.eclair.channel.simulator.states.f

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.{TestBitcoinClient, _}
import fr.acinq.eclair.blockchain._
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
class ClearingStateSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike with BeforeAndAfterAll {

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
    val r: rval = rval(1, 2, 3, 4)
    val h: sha256_hash = Crypto.sha256(r)
    val amount = 500000
    sender.send(alice, CMD_ADD_HTLC(amount, h, locktime(Blocks(3))))
    sender.expectMsg("ok")
    val htlc = alice2bob.expectMsgType[update_add_htlc]
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc :: Nil)
    // alice signs
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[update_commit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[update_revocation]
    bob2alice.forward(alice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == Nil && bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.acked == htlc :: Nil)
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
      sender.send(bob, CMD_FULFILL_HTLC(1, rval(1, 2, 3, 4)))
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
      val fulfill = update_fulfill_htlc(1, rval(1, 2, 3, 4))
      sender.send(alice, fulfill)
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(theirChanges = initialState.commitments.theirChanges.copy(initialState.commitments.theirChanges.proposed :+ fulfill)),
        downstreams = Map()))
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
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(theirChanges = initialState.commitments.theirChanges.copy(initialState.commitments.theirChanges.proposed :+ fail)),
        downstreams = Map()))
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
      sender.send(alice, update_fulfill_htlc(1, rval(1, 2, 3, 4)))
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      awaitCond(alice.stateData.asInstanceOf[DATA_CLEARING].commitments.theirNextCommitInfo.isLeft)
    }
  }

  ignore("recv CMD_SIGN (no changes)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("cannot sign when there are no changes")
    }
  }

  test("recv CMD_SIGN (while waiting for update_revocation)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
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
      sender.send(bob, CMD_FULFILL_HTLC(1, rval(1, 2, 3, 4)))
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

  ignore("recv update_commit (no changes)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
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

  ignore("recv update_revocation (no more htlcs)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(bob, CMD_FULFILL_HTLC(1, rval(1, 2, 3, 4)))
      sender.expectMsg("ok")
      bob2alice.expectMsgType[update_fulfill_htlc]
      bob2alice.forward(alice)
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[update_commit]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[update_revocation]
      awaitCond(bob.stateData.asInstanceOf[DATA_CLEARING].commitments.theirNextCommitInfo.isLeft)
      alice2bob.forward(bob)
      awaitCond(bob.stateName == DATA_NEGOTIATING)
    }
  }

  test("recv update_revocation (with remaining htlcs)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(bob, CMD_FULFILL_HTLC(1, rval(1, 2, 3, 4)))
      sender.expectMsg("ok")
      bob2alice.expectMsgType[update_fulfill_htlc]
      bob2alice.forward(alice)
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[update_commit]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[update_revocation]
      awaitCond(bob.stateData.asInstanceOf[DATA_CLEARING].commitments.theirNextCommitInfo.isLeft)
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_CLEARING].commitments.theirNextCommitInfo.isRight)
    }
  }

  test("recv update_revocation (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      sender.send(bob, CMD_FULFILL_HTLC(1, rval(1, 2, 3, 4)))
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

  ignore("recv BITCOIN_ANCHOR_SPENT") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      alice ! (BITCOIN_ANCHOR_SPENT, null)
      alice2bob.expectMsgType[error]
      // TODO
    }
  }

  test("recv error") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_CLEARING].commitments.ourCommit.publishableTx
      alice ! error(Some("oops"))
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

}
