package fr.acinq.eclair.channel.states.e

import akka.actor.Props
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.{Point, Scalar}
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi, Script, ScriptFlags, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.states.{StateSpecBaseClass, StateTestsHelperMethods}
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.transactions.{IN, OUT}
import fr.acinq.eclair.wire.{ClosingSigned, CommitSig, Error, RevokeAndAck, Shutdown, UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
import fr.acinq.eclair.{TestBitcoinClient, TestConstants}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class NormalStateSpec extends StateSpecBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple6[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient(), 300)))
    val bob2blockchain = TestProbe()
    val paymentHandler = TestProbe()
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "0B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams, "0A"))
    within(30 seconds) {
      reachNormal(alice, bob, alice2bob, bob2alice, blockchainA, alice2blockchain, bob2blockchain)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain))
    }
  }

  test("recv CMD_ADD_HTLC") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      val sender = TestProbe()
      val h = BinaryData("00112233445566778899aabbccddeeff")
      sender.send(alice, CMD_ADD_HTLC(50000000, h, 144))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
      assert(htlc.id == 1 && htlc.paymentHash == h)
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(localCurrentHtlcId = 1, localChanges = initialState.commitments.localChanges.copy(proposed = htlc :: Nil)),
        downstreams = Map(htlc.id -> None)))
    }
  }

  test("recv CMD_ADD_HTLC (insufficient funds)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_ADD_HTLC(Int.MaxValue, "11" * 32, 144))
      sender.expectMsg("insufficient funds (available=800000000 msat)")
    }
  }

  test("recv CMD_ADD_HTLC (insufficient funds w/ pending htlcs 1/2)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_ADD_HTLC(500000000, "11" * 32, 144))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(300000000, "22" * 32, 144))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(100000000, "33" * 32, 144))
      sender.expectMsg("insufficient funds (available=0 msat)")
    }
  }

  test("recv CMD_ADD_HTLC (insufficient funds w/ pending htlcs 2/2)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_ADD_HTLC(300000000, "11" * 32, 144))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(300000000, "22" * 32, 144))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(500000000, "33" * 32, 144))
      sender.expectMsg("insufficient funds (available=200000000 msat)")
    }
  }

  test("recv CMD_ADD_HTLC (while waiting for Shutdown)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg("ok")
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)

      // actual test starts here
      sender.send(alice, CMD_ADD_HTLC(300000000, "11" * 32, 144))
      sender.expectMsg("cannot send new htlcs, closing in progress")
    }
  }

  test("recv UpdateAddHtlc") { case (_, bob, alice2bob, _, _, _) =>
    within(30 seconds) {
      val initialData = bob.stateData.asInstanceOf[DATA_NORMAL]
      val htlc = UpdateAddHtlc(0, 42, 150, 144, BinaryData("00112233445566778899aabbccddeeff"), "")
      bob ! htlc
      awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(remoteChanges = initialData.commitments.remoteChanges.copy(proposed = initialData.commitments.remoteChanges.proposed :+ htlc))))
    }
  }

  test("recv UpdateAddHtlc (insufficient funds)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      val htlc = UpdateAddHtlc(0, 42, Long.MaxValue, 144, BinaryData("00112233445566778899aabbccddeeff"), "")
      alice2bob.forward(bob, htlc)
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv UpdateAddHtlc (insufficient funds w/ pending htlcs 1/2)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice2bob.forward(bob, UpdateAddHtlc(0, 42, 500000000, 144, "11" * 32, ""))
      alice2bob.forward(bob, UpdateAddHtlc(0, 43, 500000000, 144, "22" * 32, ""))
      alice2bob.forward(bob, UpdateAddHtlc(0, 44, 500000000, 144, "33" * 32, ""))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv UpdateAddHtlc (insufficient funds w/ pending htlcs 2/2)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice2bob.forward(bob, UpdateAddHtlc(0, 42, 300000000, 144, "11" * 32, ""))
      alice2bob.forward(bob, UpdateAddHtlc(0, 43, 300000000, 144, "22" * 32, ""))
      alice2bob.forward(bob, UpdateAddHtlc(0, 44, 500000000, 144, "33" * 32, ""))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_SIGN") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    }
  }

  test("recv CMD_SIGN (no changes)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_SIGN)
      sender.expectNoMsg(1 second) // just ignored
      //sender.expectMsg("cannot sign when there are no changes")
    }
  }

  ignore("recv CMD_SIGN (while waiting for RevokeAndAck)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("cannot sign until next revocation hash is received")
    }
  }

  test("recv CommitSig (one htlc received)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()

      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")

      // actual test begins
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)

      bob2alice.expectMsgType[RevokeAndAck]
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.exists(h => h.add.id == htlc.id && h.direction == IN))
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 1)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.toLocalMsat == initialState.commitments.localCommit.spec.toLocalMsat)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.acked.size == 1)
    }
  }

  test("recv CommitSig (one htlc sent)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()

      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.forward(alice)
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")

      // actual test begins
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)

      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.exists(h => h.add.id == htlc.id && h.direction == OUT))
      assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 1)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.toLocalMsat == initialState.commitments.localCommit.spec.toLocalMsat)
    }
  }

  test("recv CommitSig (multiple htlcs in both directions)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()

      val (r1, htlc1) = addHtlc(50000000, alice, bob, alice2bob, bob2alice) // a->b (regular)

      val (r2, htlc2) = addHtlc(8000000, alice, bob, alice2bob, bob2alice) //  a->b (regular)

      val (r3, htlc3) = addHtlc(300000, bob, alice, bob2alice, alice2bob) //   b->a (dust)

      val (r4, htlc4) = addHtlc(1000000, alice, bob, alice2bob, bob2alice) //  a->b (regular)

      val (r5, htlc5) = addHtlc(50000000, bob, alice, bob2alice, alice2bob) // b->a (regular)

      val (r6, htlc6) = addHtlc(500000, alice, bob, alice2bob, bob2alice) //   a->b (dust)

      val (r7, htlc7) = addHtlc(4000000, bob, alice, bob2alice, alice2bob) //  b->a (regular)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.forward(alice)
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")

      // actual test begins
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)

      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.index == 1)
      assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 3)
    }
  }

  // TODO: maybe should be illegal?
  ignore("recv CommitSig (two htlcs received with same r)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val r = BinaryData("00112233445566778899aabbccddeeff")
      val h: BinaryData = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(50000000, h, 144))
      sender.expectMsg("ok")
      val htlc1 = alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)

      sender.send(alice, CMD_ADD_HTLC(50000000, h, 144))
      sender.expectMsg("ok")
      val htlc2 = alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)

      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.proposed == htlc1 :: htlc2 :: Nil)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sign(alice, bob, alice2bob, bob2alice)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.exists(h => h.add.id == htlc1.id && h.direction == IN))
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 2)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.toLocalMsat == initialState.commitments.localCommit.spec.toLocalMsat)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx.txOut.count(_.amount == Satoshi(50000)) == 2)
    }
  }

  test("recv CommitSig (no changes)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      // signature is invalid but it doesn't matter
      sender.send(bob, CommitSig(0, "00" * 64, Nil))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CommitSig (invalid signature)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

      // actual test begins
      sender.send(bob, CommitSig(0, "00" * 64, Nil))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv RevokeAndAck (one htlc sent)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)

      // actual test begins
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localChanges.acked.size == 1)
    }
  }

  test("recv RevokeAndAck (one htlc received)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)

      // actual test begins
      alice2bob.expectMsgType[RevokeAndAck]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    }
  }

  test("recv RevokeAndAck (multiple htlcs in both directions)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r1, htlc1) = addHtlc(50000000, alice, bob, alice2bob, bob2alice) // a->b (regular)

      val (r2, htlc2) = addHtlc(8000000, alice, bob, alice2bob, bob2alice) //  a->b (regular)

      val (r3, htlc3) = addHtlc(300000, bob, alice, bob2alice, alice2bob) //   b->a (dust)

      val (r4, htlc4) = addHtlc(1000000, alice, bob, alice2bob, bob2alice) //  a->b (regular)

      val (r5, htlc5) = addHtlc(50000000, bob, alice, bob2alice, alice2bob) // b->a (regular)

      val (r6, htlc6) = addHtlc(500000, alice, bob, alice2bob, bob2alice) //   a->b (dust)

      val (r7, htlc7) = addHtlc(4000000, bob, alice, bob2alice, alice2bob) //  b->a (regular)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)

      // actual test begins
      alice2bob.expectMsgType[RevokeAndAck]
      alice2bob.forward(bob)

      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommit.index == 1)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommit.spec.htlcs.size == 7)
    }
  }

  test("recv RevokeAndAck (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)

      // actual test begins
      bob2alice.expectMsgType[RevokeAndAck]
      sender.send(alice, RevokeAndAck(0, Scalar("11" * 32), Scalar("22" * 32).toPoint, Nil))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv RevokeAndAck (unexpectedly)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
      sender.send(alice, RevokeAndAck(0, Scalar("11" * 32), Scalar("22" * 32).toPoint, Nil))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_FULFILL_HTLC") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
      sender.send(bob, CMD_FULFILL_HTLC(htlc.id, r))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
      awaitCond(bob.stateData == initialState.copy(commitments = initialState.commitments.copy(localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fulfill))))
    }
  }

  test("recv CMD_FULFILL_HTLC (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val r: BinaryData = "11" * 32
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sender.send(bob, CMD_FULFILL_HTLC(42, r))
      sender.expectMsg("unknown htlc id=42")
      assert(initialState == bob.stateData)
    }
  }

  test("recv CMD_FULFILL_HTLC (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
      sender.send(bob, CMD_FULFILL_HTLC(htlc.id, "00" * 32))
      sender.expectMsg("invalid htlc preimage for htlc id=1")
      assert(initialState == bob.stateData)
    }
  }

  test("recv UpdateFulfillHtlc (sender has not signed)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      sender.send(bob, CMD_FULFILL_HTLC(htlc.id, r))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]

      // actual test begins
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      bob2alice.forward(alice)
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fulfill)),
        downstreams = Map()))
    }
  }

  test("recv UpdateFulfillHtlc (sender has signed)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)
      sign(bob, alice, bob2alice, alice2bob)
      sender.send(bob, CMD_FULFILL_HTLC(htlc.id, r))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]

      // actual test begins
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      bob2alice.forward(alice)
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fulfill)),
        downstreams = Map()))
    }
  }

  test("recv UpdateFulfillHtlc (unknown htlc id)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      sender.send(alice, UpdateFulfillHtlc(0, 42, "00" * 32))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv UpdateFulfillHtlc (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
      sender.send(alice, UpdateFulfillHtlc(0, 42, "00" * 32))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_FAIL_HTLC") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
      sender.send(bob, CMD_FAIL_HTLC(htlc.id, "some reason"))
      sender.expectMsg("ok")
      val fail = bob2alice.expectMsgType[UpdateFailHtlc]
      awaitCond(bob.stateData == initialState.copy(commitments = initialState.commitments.copy(localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fail))))
    }
  }

  test("recv CMD_FAIL_HTLC (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val r: BinaryData = "11" * 32
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sender.send(bob, CMD_FAIL_HTLC(42, "some reason"))
      sender.expectMsg("unknown htlc id=42")
      assert(initialState == bob.stateData)
    }
  }

  test("recv UpdateFailHtlc (sender has not signed)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      sender.send(bob, CMD_FAIL_HTLC(htlc.id, "some reason"))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[UpdateFailHtlc]

      // actual test begins
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      bob2alice.forward(alice)
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fulfill)),
        downstreams = Map()))
    }
  }

  test("recv UpdateFailHtlc (sender has signed") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)
      sign(bob, alice, bob2alice, alice2bob)

      sender.send(bob, CMD_FAIL_HTLC(htlc.id, "some reason"))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[UpdateFailHtlc]

      // actual test begins
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      bob2alice.forward(alice)
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fulfill)),
        downstreams = Map()))
    }
  }

  test("recv UpdateFailHtlc (unknown htlc id)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      sender.send(alice, UpdateFailHtlc(0, 42, "some reason".getBytes()))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_CLOSE (no pending htlcs)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isEmpty)
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg("ok")
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
    }
  }

  test("recv CMD_CLOSE (with unacked sent htlcs)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg("cannot close when there are pending changes")
    }
  }

  test("recv CMD_CLOSE (with invalid final script)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_CLOSE(Some(BinaryData("00112233445566778899"))))
      sender.expectMsg("invalid final script")
    }
  }

  test("recv CMD_CLOSE (with signed sent htlcs)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg("ok")
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
    }
  }

  test("recv CMD_CLOSE (two in a row)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isEmpty)
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg("ok")
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg("closing already in progress")
    }
  }

  test("recv CMD_CLOSE (while waiting for a RevokeAndAck)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      // actual test begins
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg("ok")
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == NORMAL)
    }
  }

  test("recv Shutdown (no pending htlcs)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, Shutdown(0, Script.write(Bob.channelParams.defaultFinalScriptPubKey)))
      alice2bob.expectMsgType[Shutdown]
      alice2bob.expectMsgType[ClosingSigned]
      awaitCond(alice.stateName == NEGOTIATING)
    }
  }

  test("recv Shutdown (with unacked sent htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sender.send(bob, CMD_CLOSE(None))
      bob2alice.expectMsgType[Shutdown]
      // actual test begins
      bob2alice.forward(alice)
      alice2bob.expectMsgType[CommitSig]
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == SHUTDOWN)
    }
  }

  test("recv Shutdown (with unacked received htlcs)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      // actual test begins
      sender.send(bob, Shutdown(0, Script.write(TestConstants.Alice.channelParams.defaultFinalScriptPubKey)))
      bob2alice.expectMsgType[Error]
      bob2blockchain.expectMsgType[PublishAsap]
      bob2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(bob.stateName == CLOSING)
    }
  }

  test("recv Shutdown (with invalid final script)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(bob, Shutdown(0, BinaryData("00112233445566778899")))
      bob2alice.expectMsgType[Error]
      bob2blockchain.expectMsgType[PublishAsap]
      bob2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(bob.stateName == CLOSING)
    }
  }

  test("recv Shutdown (with signed htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)
      sign(bob, alice, bob2alice, alice2bob)

      // actual test begins
      sender.send(bob, Shutdown(0, Script.write(TestConstants.Alice.channelParams.defaultFinalScriptPubKey)))
      bob2alice.expectMsgType[Shutdown]
      awaitCond(bob.stateName == SHUTDOWN)
    }
  }

  test("recv Shutdown (while waiting for a RevokeAndAck)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      sender.send(bob, CMD_CLOSE(None))
      bob2alice.expectMsgType[Shutdown]
      // actual test begins
      bob2alice.forward(alice)
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == SHUTDOWN)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (their commit w/ htlc)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()

      val (ra1, htlca1) = addHtlc(250000000, alice, bob, alice2bob, bob2alice)
      val (ra2, htlca2) = addHtlc(100000000, alice, bob, alice2bob, bob2alice)
      val (rb1, htlcb1) = addHtlc(50000000, bob, alice, bob2alice, alice2bob)
      val (rb2, htlcb2) = addHtlc(55000000, bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      fulfillHtlc(2, ra2, bob, alice, bob2alice, alice2bob)
      fulfillHtlc(1, rb1, alice, bob, alice2bob, bob2alice)

      // at this point here is the situation from alice pov and what she should do when bob publishes his commit tx:
      // balances :
      //    alice's balance : 450 000 000                             => nothing to do
      //    bob's balance   :  95 000 000                             => nothing to do
      // htlcs :
      //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
      //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
      //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage
      //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

      // bob publishes his current commit tx
      val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      assert(bobCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs
      alice ! (BITCOIN_FUNDING_SPENT, bobCommitTx)

      alice2blockchain.expectMsgType[WatchConfirmed].txId == bobCommitTx.txid

      // in addition to its main output, alice can only claim 3 out of 4 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the preimage
      val amountClaimed = (for (i <- 0 until 4) yield {
        val claimHtlcTx = alice2blockchain.expectMsgType[PublishAsap].tx
        assert(claimHtlcTx.txIn.size == 1)
        assert(claimHtlcTx.txOut.size == 1)
        Transaction.correctlySpends(claimHtlcTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        claimHtlcTx.txOut(0).amount
      }).sum
      alice2blockchain.expectNoMsg(1 second)
      // at best we have a little less than 450 000 + 250 000 + 100 000 + 50 000 = 850000 (because fees)
      assert(amountClaimed == Satoshi(828240))

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimHtlcSuccessTxs.size == 1)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimHtlcTimeoutTxs.size == 2)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (revoked commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()

      // initally we have :
      // alice = 800 000
      //   bob = 200 000
      def send(): Transaction = {
        // alice sends 10 000 sat
        val (r, htlc) = addHtlc(10000000, alice, bob, alice2bob, bob2alice)
        sign(alice, bob, alice2bob, bob2alice)

        bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      }

      val txs = for (i <- 0 until 10) yield send()
      // bob now has 10 spendable tx, 9 of them being revoked

      // let's say that bob published this tx
      val revokedTx = txs(3)
      // channel state for this revoked tx is as follows:
      // alice = 760 000
      //   bob = 200 000
      //  a->b =  10 000
      //  a->b =  10 000
      //  a->b =  10 000
      //  a->b =  10 000
      // two main outputs + 4 htlc
      assert(revokedTx.txOut.size == 6)
      alice ! (BITCOIN_FUNDING_SPENT, revokedTx)
      alice2bob.expectMsgType[Error]
      alice2blockchain.expectMsgType[WatchConfirmed]

      val mainTx = alice2blockchain.expectMsgType[PublishAsap].tx
      val punishTx = alice2blockchain.expectMsgType[PublishAsap].tx
      alice2blockchain.expectNoMsg(1 second)

      Transaction.correctlySpends(mainTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      Transaction.correctlySpends(punishTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      // two main outputs are 760 000 and 200 000
      assert(mainTx.txOut(0).amount == Satoshi(743400))
      assert(punishTx.txOut(0).amount == Satoshi(195170))

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)

    }
  }

  test("recv Error") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val (ra1, htlca1) = addHtlc(250000000, alice, bob, alice2bob, bob2alice)
      val (ra2, htlca2) = addHtlc(100000000, alice, bob, alice2bob, bob2alice)
      val (rb1, htlcb1) = addHtlc(50000000, bob, alice, bob2alice, alice2bob)
      val (rb2, htlcb2) = addHtlc(55000000, bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      fulfillHtlc(2, ra2, bob, alice, bob2alice, alice2bob)
      fulfillHtlc(1, rb1, alice, bob, alice2bob, bob2alice)

      // at this point here is the situation from alice pov and what she should do when she publishes his commit tx:
      // balances :
      //    alice's balance : 450 000 000                             => nothing to do
      //    bob's balance   :  95 000 000                             => nothing to do
      // htlcs :
      //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend using 2nd stage htlc-timeout
      //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend using 2nd stage htlc-timeout
      //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage using htlc-success
      //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

      // an error occurs and alice publishes her commit tx
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error(0, "oops".getBytes())
      alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))
      assert(aliceCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs

      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid

      // alice can only claim 3 out of 4 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the htlc
      // so we expect 7 transactions:
      // - 1 tx to claim the main delayed output
      // - 3 txes for each htlc
      // - 3 txes for each delayed output of the claimed htlc
      val claimTxs = for (i <- 0 until 7) yield alice2blockchain.expectMsgType[PublishAsap].tx
      alice2blockchain.expectNoMsg(1 second)

      // the main delayed output spends the commitment transaction
      Transaction.correctlySpends(claimTxs(0), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      // 2nd stage transactions spend the commitment transaction
      Transaction.correctlySpends(claimTxs(1), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      Transaction.correctlySpends(claimTxs(2), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      Transaction.correctlySpends(claimTxs(3), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      // 3rd stage transactions spend their respective HTLC-Success/HTLC-Timeout transactions
      Transaction.correctlySpends(claimTxs(4), claimTxs(1) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      Transaction.correctlySpends(claimTxs(5), claimTxs(2) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      Transaction.correctlySpends(claimTxs(6), claimTxs(3) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)
      val localCommitPublished = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
      assert(localCommitPublished.commitTx == aliceCommitTx)
      assert(localCommitPublished.htlcSuccessTxs.size == 1)
      assert(localCommitPublished.htlcTimeoutTxs.size == 2)
      assert(localCommitPublished.claimHtlcDelayedTx.size == 3)
    }
  }

}
