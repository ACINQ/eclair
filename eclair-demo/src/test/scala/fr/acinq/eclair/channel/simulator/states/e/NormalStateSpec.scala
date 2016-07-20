package fr.acinq.eclair.channel.simulator.states.e

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin.{Crypto, Satoshi, Script, ScriptFlags, Transaction, TxOut}
import fr.acinq.eclair._
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.{BITCOIN_ANCHOR_DEPTHOK, Data, State, _}
import lightning._
import lightning.locktime.Locktime.Blocks
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, fixture}

import scala.concurrent.duration._
import scala.util.Random

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class NormalStateSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike with BeforeAndAfterAll {

  type FixtureParam = Tuple6[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val bob2blockchain = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, Bob.channelParams, "A"))
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

  def addHtlc(amountMsat: Int, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): (rval, update_add_htlc) = {
    val rand = new Random()
    val R = rval(rand.nextInt(), rand.nextInt(), rand.nextInt(), rand.nextInt())
    val H: sha256_hash = Crypto.sha256(R)
    val sender = TestProbe()
    sender.send(s, CMD_ADD_HTLC(amountMsat, H, locktime(Blocks(3))))
    sender.expectMsg("ok")
    val htlc = s2r.expectMsgType[update_add_htlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed.contains(htlc))
    (R, htlc)
  }

  def sign(s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe) = {
    val sender = TestProbe()
    val rCommitIndex = r.stateData.asInstanceOf[HasCommitments].commitments.ourCommit.index
    sender.send(s, CMD_SIGN)
    sender.expectMsg("ok")
    s2r.expectMsgType[update_commit]
    s2r.forward(r)
    r2s.expectMsgType[update_revocation]
    r2s.forward(s)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.ourCommit.index == rCommitIndex + 1)
  }

  test("recv CMD_ADD_HTLC") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val h = sha256_hash(1, 2, 3, 4)
      alice ! CMD_ADD_HTLC(500000, h, locktime(Blocks(3)))
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      assert(htlc.id == 1 && htlc.rHash == h)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCurrentHtlcId == 1 && alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.proposed == htlc :: Nil)
    }
  }

  test("recv CMD_ADD_HTLC (insufficient funds)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_ADD_HTLC(Int.MaxValue, sha256_hash(1, 1, 1, 1), locktime(Blocks(3))))
      sender.expectMsg("insufficient funds (available=1000000000 msat)")
    }
  }

  test("recv CMD_ADD_HTLC (insufficient funds w/ pending htlcs 1/2)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_ADD_HTLC(500000000, sha256_hash(1, 1, 1, 1), locktime(Blocks(3))))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(500000000, sha256_hash(2, 2, 2, 2), locktime(Blocks(3))))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(500000000, sha256_hash(3, 3, 3, 3), locktime(Blocks(3))))
      sender.expectMsg("insufficient funds (available=0 msat)")
    }
  }

  test("recv CMD_ADD_HTLC (insufficient funds w/ pending htlcs 2/2)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_ADD_HTLC(300000000, sha256_hash(1, 1, 1, 1), locktime(Blocks(3))))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(300000000, sha256_hash(2, 2, 2, 2), locktime(Blocks(3))))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(500000000, sha256_hash(3, 3, 3, 3), locktime(Blocks(3))))
      sender.expectMsg("insufficient funds (available=400000000 msat)")
    }
  }

  test("recv update_add_htlc") { case (_, bob, alice2bob, _, _, _) =>
    within(30 seconds) {
      val initialData = bob.stateData.asInstanceOf[DATA_NORMAL]
      val htlc = update_add_htlc(42, 150, sha256_hash(1, 2, 3, 4), locktime(Blocks(3)), routing(ByteString.EMPTY))
      bob ! htlc
      awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(theirChanges = initialData.commitments.theirChanges.copy(proposed = initialData.commitments.theirChanges.proposed :+ htlc))))
    }
  }

  test("recv update_add_htlc (insufficient funds)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      val htlc = update_add_htlc(42, Int.MaxValue, sha256_hash(1, 2, 3, 4), locktime(Blocks(3)), routing(ByteString.EMPTY))
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
      alice2bob.forward(bob, update_add_htlc(42, 500000000, sha256_hash(1, 1, 1, 1), locktime(Blocks(3)), routing(ByteString.EMPTY)))
      alice2bob.forward(bob, update_add_htlc(43, 500000000, sha256_hash(2, 2, 2, 2), locktime(Blocks(3)), routing(ByteString.EMPTY)))
      alice2bob.forward(bob, update_add_htlc(44, 500000000, sha256_hash(3, 3, 3, 3), locktime(Blocks(3)), routing(ByteString.EMPTY)))
      bob2alice.expectMsgType[error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv update_add_htlc (insufficient funds w/ pending htlcs 2/2)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      alice2bob.forward(bob, update_add_htlc(42, 300000000, sha256_hash(1, 1, 1, 1), locktime(Blocks(3)), routing(ByteString.EMPTY)))
      alice2bob.forward(bob, update_add_htlc(43, 300000000, sha256_hash(2, 2, 2, 2), locktime(Blocks(3)), routing(ByteString.EMPTY)))
      alice2bob.forward(bob, update_add_htlc(44, 500000000, sha256_hash(3, 3, 3, 3), locktime(Blocks(3)), routing(ByteString.EMPTY)))
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

  ignore("recv CMD_SIGN (no changes)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("cannot sign when there are no changes")
    }
  }

  test("recv CMD_SIGN (while waiting for update_revocation)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
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
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.spec.htlcs.exists(h => h.id == htlc.id && h.direction == IN))
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.spec.amount_us_msat == initialState.commitments.ourCommit.spec.amount_us_msat)
    }
  }

  test("recv update_commit (two htlcs with same r)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val r = sha256_hash(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(5000000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc1 = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)

      sender.send(alice, CMD_ADD_HTLC(5000000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc2 = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)

      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc1 :: htlc2 :: Nil)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sign(alice, bob, alice2bob, bob2alice)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.spec.htlcs.exists(h => h.id == htlc1.id && h.direction == IN))
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.spec.htlcs.exists(h => h.id == htlc2.id && h.direction == IN))
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.spec.amount_us_msat == initialState.commitments.ourCommit.spec.amount_us_msat)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx.txOut.count(_.amount == Satoshi(5000)) == 2)
    }
  }

  ignore("recv update_commit (no changes)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
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
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx

      // actual test begins
      sender.send(bob, update_commit(signature(0, 0, 0, 0, 0, 0, 0, 0)))
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

  test("recv update_fulfill_htlc") { case (alice, bob, alice2bob, bob2alice, _, _) =>
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
      awaitCond(alice.stateData == initialState.copy(commitments = initialState.commitments.copy(theirChanges = initialState.commitments.theirChanges.copy(initialState.commitments.theirChanges.proposed :+ fulfill))))
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

  test("recv update_fail_htlc") { case (alice, bob, alice2bob, bob2alice, _, _) =>
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
      awaitCond(alice.stateData == initialState.copy(commitments = initialState.commitments.copy(theirChanges = initialState.commitments.theirChanges.copy(initialState.commitments.theirChanges.proposed :+ fulfill))))
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
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourClearing.isEmpty)
      sender.send(alice, CMD_CLOSE(None))
      alice2bob.expectMsgType[close_clearing]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourClearing.isDefined)
    }
  }

  test("recv CMD_CLOSE (two in a row)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourClearing.isEmpty)
      sender.send(alice, CMD_CLOSE(None))
      alice2bob.expectMsgType[close_clearing]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourClearing.isDefined)
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg("closing already in progress")
    }
  }

  test("recv close_clearing (no pending htlcs)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, close_clearing(ByteString.EMPTY))
      alice2bob.expectMsgType[close_clearing]
      alice2bob.expectMsgType[close_signature]
      awaitCond(alice.stateName == NEGOTIATING)
    }
  }

  ignore("recv close_clearing (with unacked received htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      // actual test begins
      sender.send(alice, close_clearing(ByteString.EMPTY))
      alice2bob.expectMsgType[close_clearing]
      awaitCond(alice.stateName == CLEARING)
    }
  }

  ignore("recv close_clearing (with unacked sent htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      // actual test begins
      sender.send(alice, close_clearing(ByteString.EMPTY))
      alice2bob.expectMsgType[close_clearing]
      awaitCond(alice.stateName == CLEARING)
    }
  }

  test("recv close_clearing (with signed htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
      sender.send(alice, close_clearing(ByteString.EMPTY))
      alice2bob.expectMsgType[close_clearing]
      awaitCond(alice.stateName == CLEARING)
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

  test("recv error") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      alice ! error(Some("oops"))
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

}
