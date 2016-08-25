package fr.acinq.eclair.channel.simulator.states.e

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin.{Crypto, Satoshi, Script, ScriptFlags, Transaction, TxOut}
import fr.acinq.eclair._
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
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
class NormalStateSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike with BeforeAndAfterAll with StateTestsHelperMethods {

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
    test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  test("recv CMD_ADD_HTLC") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      val sender = TestProbe()
      val h = sha256_hash(1, 2, 3, 4)
      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(144))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      assert(htlc.id == 1 && htlc.rHash == h)
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(ourCurrentHtlcId = 1, ourChanges = initialState.commitments.ourChanges.copy(proposed = htlc :: Nil)),
        downstreams = Map(htlc.id -> None)))
    }
  }

  test("recv CMD_ADD_HTLC (insufficient funds)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_ADD_HTLC(Int.MaxValue, sha256_hash(1, 1, 1, 1), locktime(Blocks(144))))
      sender.expectMsg("insufficient funds (available=1000000000 msat)")
    }
  }

  test("recv CMD_ADD_HTLC (insufficient funds w/ pending htlcs 1/2)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_ADD_HTLC(500000000, sha256_hash(1, 1, 1, 1), locktime(Blocks(144))))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(500000000, sha256_hash(2, 2, 2, 2), locktime(Blocks(144))))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(500000000, sha256_hash(3, 3, 3, 3), locktime(Blocks(144))))
      sender.expectMsg("insufficient funds (available=0 msat)")
    }
  }

  test("recv CMD_ADD_HTLC (insufficient funds w/ pending htlcs 2/2)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_ADD_HTLC(300000000, sha256_hash(1, 1, 1, 1), locktime(Blocks(144))))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(300000000, sha256_hash(2, 2, 2, 2), locktime(Blocks(144))))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(500000000, sha256_hash(3, 3, 3, 3), locktime(Blocks(144))))
      sender.expectMsg("insufficient funds (available=400000000 msat)")
    }
  }

  test("recv CMD_ADD_HTLC (while waiting for close_shutdown)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_CLOSE(None))
      alice2bob.expectMsgType[close_shutdown]
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourShutdown.isDefined)

      // actual test starts here
      sender.send(alice, CMD_ADD_HTLC(300000000, sha256_hash(1, 1, 1, 1), locktime(Blocks(144))))
      sender.expectMsg("cannot send new htlcs, closing in progress")
    }
  }

  test("recv update_add_htlc") { case (_, bob, alice2bob, _, _, _) =>
    within(30 seconds) {
      val initialData = bob.stateData.asInstanceOf[DATA_NORMAL]
      val htlc = update_add_htlc(42, 150, sha256_hash(1, 2, 3, 4), locktime(Blocks(144)), routing(ByteString.EMPTY))
      bob ! htlc
      awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(theirChanges = initialData.commitments.theirChanges.copy(proposed = initialData.commitments.theirChanges.proposed :+ htlc))))
    }
  }

  test("recv update_add_htlc (insufficient funds)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      val htlc = update_add_htlc(42, Int.MaxValue, sha256_hash(1, 2, 3, 4), locktime(Blocks(144)), routing(ByteString.EMPTY))
      alice2bob.forward(bob, htlc)
      bob2alice.expectMsgType[error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv update_add_htlc (insufficient funds w/ pending htlcs 1/2)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      alice2bob.forward(bob, update_add_htlc(42, 500000000, sha256_hash(1, 1, 1, 1), locktime(Blocks(144)), routing(ByteString.EMPTY)))
      alice2bob.forward(bob, update_add_htlc(43, 500000000, sha256_hash(2, 2, 2, 2), locktime(Blocks(144)), routing(ByteString.EMPTY)))
      alice2bob.forward(bob, update_add_htlc(44, 500000000, sha256_hash(3, 3, 3, 3), locktime(Blocks(144)), routing(ByteString.EMPTY)))
      bob2alice.expectMsgType[error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv update_add_htlc (insufficient funds w/ pending htlcs 2/2)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      alice2bob.forward(bob, update_add_htlc(42, 300000000, sha256_hash(1, 1, 1, 1), locktime(Blocks(144)), routing(ByteString.EMPTY)))
      alice2bob.forward(bob, update_add_htlc(43, 300000000, sha256_hash(2, 2, 2, 2), locktime(Blocks(144)), routing(ByteString.EMPTY)))
      alice2bob.forward(bob, update_add_htlc(44, 500000000, sha256_hash(3, 3, 3, 3), locktime(Blocks(144)), routing(ByteString.EMPTY)))
      bob2alice.expectMsgType[error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_SIGN") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.theirNextCommitInfo.isLeft)
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
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.theirNextCommitInfo.isRight)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.theirNextCommitInfo.isLeft)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("cannot sign until next revocation hash is received")
    }
  }

  test("recv update_commit") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()

      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      // actual test begins
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)

      bob2alice.expectMsgType[update_revocation]
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.spec.htlcs.exists(h => h.add.id == htlc.id && h.direction == IN))
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.spec.amount_us_msat == initialState.commitments.ourCommit.spec.amount_us_msat)
    }
  }

  test("recv update_commit (two htlcs with same r)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val r = sha256_hash(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(5000000, h, locktime(Blocks(144))))
      sender.expectMsg("ok")
      val htlc1 = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)

      sender.send(alice, CMD_ADD_HTLC(5000000, h, locktime(Blocks(144))))
      sender.expectMsg("ok")
      val htlc2 = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)

      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc1 :: htlc2 :: Nil)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sign(alice, bob, alice2bob, bob2alice)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.spec.htlcs.exists(h => h.add.id == htlc1.id && h.direction == IN))
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.spec.htlcs.exists(h => h.add.id == htlc2.id && h.direction == IN))
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.spec.amount_us_msat == initialState.commitments.ourCommit.spec.amount_us_msat)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx.txOut.count(_.amount == Satoshi(5000)) == 2)
    }
  }

  test("recv update_commit (no changes)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      // signature is invalid but it doesn't matter
      sender.send(bob, update_commit(Some(signature(0, 0, 0, 0, 0, 0, 0, 0))))
      bob2alice.expectMsgType[error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv update_commit (invalid signature)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx

      // actual test begins
      sender.send(bob, update_commit(Some(signature(0, 0, 0, 0, 0, 0, 0, 0))))
      bob2alice.expectMsgType[error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv update_revocation") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)

      // actual test begins
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.theirNextCommitInfo.isLeft)
      bob2alice.expectMsgType[update_revocation]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.theirNextCommitInfo.isRight)
    }
  }

  test("recv update_revocation (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)

      // actual test begins
      bob2alice.expectMsgType[update_revocation]
      sender.send(alice, update_revocation(sha256_hash(0, 0, 0, 0), sha256_hash(1, 1, 1, 1)))
      alice2bob.expectMsgType[error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv update_revocation (unexpectedly)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.theirNextCommitInfo.isRight)
      sender.send(alice, update_revocation(sha256_hash(0, 0, 0, 0), sha256_hash(1, 1, 1, 1)))
      alice2bob.expectMsgType[error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_FULFILL_HTLC") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
      sender.send(bob, CMD_FULFILL_HTLC(htlc.id, r))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[update_fulfill_htlc]
      awaitCond(bob.stateData == initialState.copy(commitments = initialState.commitments.copy(ourChanges = initialState.commitments.ourChanges.copy(initialState.commitments.ourChanges.proposed :+ fulfill))))
    }
  }

  test("recv CMD_FULFILL_HTLC (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val r: rval = rval(1, 2, 3, 4)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sender.send(bob, CMD_FULFILL_HTLC(42, r))
      sender.expectMsg("unknown htlc id=42")
      assert(initialState == bob.stateData)
    }
  }

  test("recv CMD_FULFILL_HTLC (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
      sender.send(bob, CMD_FULFILL_HTLC(htlc.id, rval(0, 0, 0, 0)))
      sender.expectMsg("invalid htlc preimage for htlc id=1")
      assert(initialState == bob.stateData)
    }
  }

  test("recv update_fulfill_htlc (sender has signed, receiver has not)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      sender.send(bob, CMD_FULFILL_HTLC(htlc.id, r))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[update_fulfill_htlc]

      // actual test begins
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      bob2alice.forward(alice)
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(theirChanges = initialState.commitments.theirChanges.copy(initialState.commitments.theirChanges.proposed :+ fulfill)),
        downstreams = Map()))
    }
  }

  test("recv update_fulfill_htlc (sender and receiver have signed)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)
      sign(bob, alice, bob2alice, alice2bob)
      sender.send(bob, CMD_FULFILL_HTLC(htlc.id, r))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[update_fulfill_htlc]

      // actual test begins
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      bob2alice.forward(alice)
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(theirChanges = initialState.commitments.theirChanges.copy(initialState.commitments.theirChanges.proposed :+ fulfill)),
        downstreams = Map()))
    }
  }

  test("recv update_fulfill_htlc (unknown htlc id)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      sender.send(alice, update_fulfill_htlc(42, rval(0, 0, 0, 0)))
      alice2bob.expectMsgType[error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv update_fulfill_htlc (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
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
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
      sender.send(bob, CMD_FAIL_HTLC(htlc.id, "some reason"))
      sender.expectMsg("ok")
      val fail = bob2alice.expectMsgType[update_fail_htlc]
      awaitCond(bob.stateData == initialState.copy(commitments = initialState.commitments.copy(ourChanges = initialState.commitments.ourChanges.copy(initialState.commitments.ourChanges.proposed :+ fail))))
    }
  }

  test("recv CMD_FAIL_HTLC (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val r: rval = rval(1, 2, 3, 4)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sender.send(bob, CMD_FAIL_HTLC(42, "some reason"))
      sender.expectMsg("unknown htlc id=42")
      assert(initialState == bob.stateData)
    }
  }

  test("recv update_fail_htlc (sender has signed, receiver has not)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      sender.send(bob, CMD_FAIL_HTLC(htlc.id, "some reason"))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[update_fail_htlc]

      // actual test begins
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      bob2alice.forward(alice)
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(theirChanges = initialState.commitments.theirChanges.copy(initialState.commitments.theirChanges.proposed :+ fulfill)),
        downstreams = Map()))
    }
  }

  test("recv update_fail_htlc (sender and receiver have signed") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)
      sign(bob, alice, bob2alice, alice2bob)

      sender.send(bob, CMD_FAIL_HTLC(htlc.id, "some reason"))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[update_fail_htlc]

      // actual test begins
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      bob2alice.forward(alice)
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(theirChanges = initialState.commitments.theirChanges.copy(initialState.commitments.theirChanges.proposed :+ fulfill)),
        downstreams = Map()))
    }
  }

  test("recv update_fail_htlc (unknown htlc id)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      val sender = TestProbe()
      sender.send(alice, update_fail_htlc(42, fail_reason(ByteString.copyFromUtf8("some reason"))))
      alice2bob.expectMsgType[error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_CLOSE (no pending htlcs)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourShutdown.isEmpty)
      sender.send(alice, CMD_CLOSE(None))
      alice2bob.expectMsgType[close_shutdown]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourShutdown.isDefined)
    }
  }

  test("recv CMD_CLOSE (two in a row)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourShutdown.isEmpty)
      sender.send(alice, CMD_CLOSE(None))
      alice2bob.expectMsgType[close_shutdown]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourShutdown.isDefined)
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg("closing already in progress")
    }
  }

  test("recv close_shutdown (no pending htlcs)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, close_shutdown(ByteString.EMPTY))
      alice2bob.expectMsgType[close_shutdown]
      alice2bob.expectMsgType[close_signature]
      awaitCond(alice.stateName == NEGOTIATING)
    }
  }

  /**
    * see https://github.com/ElementsProject/lightning/issues/29
    */
  ignore("recv close_shutdown (with unacked received htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      // actual test begins
      sender.send(alice, close_shutdown(ByteString.EMPTY))
      alice2bob.expectMsgType[close_shutdown]
      awaitCond(alice.stateName == SHUTDOWN)
    }
  }

  /**
    * see https://github.com/ElementsProject/lightning/issues/29
    */
  ignore("recv close_shutdown (with unacked sent htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      // actual test begins
      sender.send(alice, close_shutdown(ByteString.EMPTY))
      alice2bob.expectMsgType[close_shutdown]
      awaitCond(alice.stateName == SHUTDOWN)
    }
  }

  test("recv close_shutdown (with signed htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
      sender.send(alice, close_shutdown(ByteString.EMPTY))
      alice2bob.expectMsgType[close_shutdown]
      awaitCond(alice.stateName == SHUTDOWN)
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT (their commit w/ htlc)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()

      val (r1, htlc1) = addHtlc(300000000, alice, bob, alice2bob, bob2alice) // id 1
      val (r2, htlc2) = addHtlc(200000000, alice, bob, alice2bob, bob2alice) // id 2
      val (r3, htlc3) = addHtlc(100000000, alice, bob, alice2bob, bob2alice) // id 3
      sign(alice, bob, alice2bob, bob2alice)
      fulfillHtlc(1, r1, bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      val (r4, htlc4) = addHtlc(150000000, bob, alice, bob2alice, alice2bob) // id 1
      val (r5, htlc5) = addHtlc(120000000, bob, alice, bob2alice, alice2bob) // id 2
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      fulfillHtlc(2, r2, bob, alice, bob2alice, alice2bob)
      fulfillHtlc(1, r4, alice, bob, alice2bob, bob2alice)

      // at this point here is the situation from alice pov and what she should do :
      // balances :
      //    alice's balance : 400 000 000                             => nothing to do
      //    bob's balance   :  30 000 000                             => nothing to do
      // htlcs :
      //    alice -> bob    : 200 000 000 (bob has the r)             => if bob does not use the r, wait for the timeout and spend
      //    alice -> bob    : 100 000 000 (bob does not have the r)   => wait for the timeout and spend
      //    bob -> alice    : 150 000 000 (alice has the r)           => spend immediately using the r
      //    bob -> alice    : 120 000 000 (alice does not have the r) => nothing to do, bob will get his money back after the timeout

      // bob publishes his current commit tx
      val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      assert(bobCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs
      alice ! (BITCOIN_ANCHOR_SPENT, bobCommitTx)

      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid

      val amountClaimed = (for (i <- 0 until 3) yield {
        // alice can only claim 3 out of 4 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the htlc
        val claimHtlcTx = alice2blockchain.expectMsgType[PublishAsap].tx
        assert(claimHtlcTx.txIn.size == 1)
        val previousOutputs = Map(claimHtlcTx.txIn(0).outPoint -> bobCommitTx.txOut(claimHtlcTx.txIn(0).outPoint.index.toInt))
        Transaction.correctlySpends(claimHtlcTx, previousOutputs, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assert(claimHtlcTx.txOut.size == 1)
        claimHtlcTx.txOut(0).amount
      }).sum
      assert(amountClaimed == Satoshi(450000))

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].theirCommitPublished == Some(bobCommitTx))
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT (revoked commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()

      // alice sends 300 000 sat and bob fulfills
      // we reuse the same r (it doesn't matter here)
      val (r, htlc) = addHtlc(300000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      sender.send(bob, CMD_FULFILL_HTLC(1, r))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[update_fulfill_htlc]
      bob2alice.forward(alice)

      sign(bob, alice, bob2alice, alice2bob)

      // at this point we have :
      // alice = 700 000
      //   bob = 300 000
      def send(): Transaction = {
        // alice sends 1 000 sat
        // we reuse the same r (it doesn't matter here)
        val (r, htlc) = addHtlc(1000000, alice, bob, alice2bob, bob2alice)
        sign(alice, bob, alice2bob, bob2alice)

        bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      }
      val txs = for (i <- 0 until 10) yield send()
      // bob now has 10 spendable tx, 9 of them being revoked

      // let's say that bob published this tx
      val revokedTx = txs(3)
      // channel state for this revoked tx is as follows:
      // alice = 696 000
      //   bob = 300 000
      //  a->b =   4 000
      alice ! (BITCOIN_ANCHOR_SPENT, revokedTx)
      alice2bob.expectMsgType[error]
      val punishTx = alice2blockchain.expectMsgType[Publish].tx
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == CLOSING)
      Transaction.correctlySpends(punishTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      // two main outputs + 4 htlc
      assert(revokedTx.txOut.size == 6)
      // the punishment tx consumes all output but ours (which already goes to our final key)
      assert(punishTx.txIn.size == 5)
      // TODO : when changefee is implemented we should set fee = 0 and check against 304 000
      assert(punishTx.txOut == Seq(TxOut(Satoshi(301670), Script.write(Scripts.pay2wpkh(Alice.finalPubKey)))))
    }
  }

  test("recv error") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val (r1, htlc1) = addHtlc(300000000, alice, bob, alice2bob, bob2alice) // id 1
      val (r2, htlc2) = addHtlc(200000000, alice, bob, alice2bob, bob2alice) // id 2
      val (r3, htlc3) = addHtlc(100000000, alice, bob, alice2bob, bob2alice) // id 3
      sign(alice, bob, alice2bob, bob2alice)
      fulfillHtlc(1, r1, bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      val (r4, htlc4) = addHtlc(150000000, bob, alice, bob2alice, alice2bob) // id 1
      val (r5, htlc5) = addHtlc(120000000, bob, alice, bob2alice, alice2bob) // id 2
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      fulfillHtlc(2, r2, bob, alice, bob2alice, alice2bob)
      fulfillHtlc(1, r4, alice, bob, alice2bob, bob2alice)

      // at this point here is the situation from alice pov and what she should do :
      // balances :
      //    alice's balance : 400 000 000                             => nothing to do
      //    bob's balance   :  30 000 000                             => nothing to do
      // htlcs :
      //    alice -> bob    : 200 000 000 (bob has the r)             => if bob does not use the r, wait for the timeout and spend
      //    alice -> bob    : 100 000 000 (bob does not have the r)   => wait for the timeout and spend
      //    bob -> alice    : 150 000 000 (alice has the r)           => spend immediately using the r
      //    bob -> alice    : 120 000 000 (alice does not have the r) => nothing to do, bob will get his money back after the timeout

      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      alice ! error(Some("oops"))
      alice2blockchain.expectMsg(Publish(aliceCommitTx))
      assert(aliceCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs

      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid
      val amountClaimed = (for (i <- 0 until 3) yield {
        // alice can only claim 3 out of 4 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the htlc
        val claimHtlcTx = alice2blockchain.expectMsgType[PublishAsap].tx
        assert(claimHtlcTx.txIn.size == 1)
        val previousOutputs = Map(claimHtlcTx.txIn(0).outPoint -> aliceCommitTx.txOut(claimHtlcTx.txIn(0).outPoint.index.toInt))
        Transaction.correctlySpends(claimHtlcTx, previousOutputs, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        assert(claimHtlcTx.txOut.size == 1)
        claimHtlcTx.txOut(0).amount
      }).sum
      assert(amountClaimed == Satoshi(450000))

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].ourCommitPublished == Some(aliceCommitTx))
    }
  }

}
