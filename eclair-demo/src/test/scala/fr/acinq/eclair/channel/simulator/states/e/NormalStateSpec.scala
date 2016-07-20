package fr.acinq.eclair.channel.simulator.states.e

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair._
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.{BITCOIN_ANCHOR_DEPTHOK, _}
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

  test("recv CMD_ADD_HTLC") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val h = sha256_hash(1, 2, 3, 4)
      alice ! CMD_ADD_HTLC(500000, h, locktime(Blocks(3)))
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      assert(htlc.id == 1 && htlc.rHash == h)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].htlcIdx == 1 && alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.proposed == htlc :: Nil)
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
      val r = sha256_hash(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)
      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_add_htlc]
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
      val r = sha256_hash(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)
      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_add_htlc]
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
      val r = sha256_hash(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc :: Nil)
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
      val r = sha256_hash(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc :: Nil)
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
      val r = sha256_hash(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc :: Nil)
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
      val r = sha256_hash(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc :: Nil)

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
      val r: rval = rval(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc :: Nil)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[update_revocation]
      bob2alice.forward(alice)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == Nil && bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.acked == htlc :: Nil)

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
      val r: rval = rval(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc :: Nil)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[update_revocation]
      bob2alice.forward(alice)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == Nil && bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.acked == htlc :: Nil)

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
      val r: rval = rval(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.proposed == htlc :: Nil)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[update_revocation]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.proposed == Nil && alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.acked == htlc :: Nil)

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
      val r: rval = rval(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.proposed == htlc :: Nil)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[update_revocation]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.proposed == Nil && alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.acked == htlc :: Nil)

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
      val r: rval = rval(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == htlc :: Nil)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[update_revocation]
      bob2alice.forward(alice)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed == Nil && bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.acked == htlc :: Nil)

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
      val r: rval = rval(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.proposed == htlc :: Nil)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[update_revocation]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.proposed == Nil && alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.acked == htlc :: Nil)

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

  ignore("recv close_clearing (with unacked received htlcs)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val r: rval = rval(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)
      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      // actual test begins
      sender.send(alice, close_clearing(ByteString.EMPTY))
      alice2bob.expectMsgType[close_clearing]
      awaitCond(alice.stateName == CLEARING)
    }
  }

  ignore("recv close_clearing (with unacked sent htlcs)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val r: rval = rval(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)
      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      // actual test begins
      sender.send(alice, close_clearing(ByteString.EMPTY))
      alice2bob.expectMsgType[close_clearing]
      awaitCond(alice.stateName == CLEARING)
    }
  }

  test("recv close_clearing (with signed htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val r: rval = rval(1, 2, 3, 4)
      val h: sha256_hash = Crypto.sha256(r)
      sender.send(alice, CMD_ADD_HTLC(500000, h, locktime(Blocks(3))))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[update_add_htlc]
      alice2bob.forward(bob)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.proposed == htlc :: Nil)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[update_commit]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[update_revocation]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.proposed == Nil && alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourChanges.acked == htlc :: Nil)

      // actual test begins
      sender.send(alice, close_clearing(ByteString.EMPTY))
      alice2bob.expectMsgType[close_clearing]
      awaitCond(alice.stateName == CLEARING)
    }
  }

  ignore("recv BITCOIN_ANCHOR_SPENT") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.ourCommit.publishableTx
      alice ! (BITCOIN_ANCHOR_SPENT, null)
      alice2bob.expectMsgType[error]
      // TODO
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
