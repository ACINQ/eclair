package fr.acinq.eclair.channel.states.e

import akka.actor.Props
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi, Script, ScriptFlags, Transaction, TxOut}
import fr.acinq.eclair.{TestBitcoinClient, TestConstants}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.states.{StateSpecBaseClass, StateTestsHelperMethods}
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, Data, State, _}
import fr.acinq.eclair.transactions.{IN, OldScripts}
import fr.acinq.eclair.wire.{AcceptChannel, ClosingSigned, CommitSig, Error, FundingCreated, FundingLocked, FundingSigned, OpenChannel, RevokeAndAck, Shutdown, UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
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
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams, "A"))
    alice ! INPUT_INIT_FUNDER(TestConstants.anchorAmount, 0)
    bob ! INPUT_INIT_FUNDEE()
    within(30 seconds) {
      alice2bob.expectMsgType[OpenChannel]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[AcceptChannel]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[MakeFundingTx]
      alice2blockchain.forward(blockchainA)
      alice2bob.expectMsgType[FundingCreated]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingSigned]
      bob2alice.forward(alice)
      alice2blockchain.expectMsgType[WatchSpent]
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.forward(blockchainA)
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.forward(blockchainA)
      bob2blockchain.expectMsgType[WatchSpent]
      bob2blockchain.expectMsgType[WatchConfirmed]
      bob ! BITCOIN_FUNDING_DEPTHOK
      alice2blockchain.expectMsgType[WatchLost]
      bob2blockchain.expectMsgType[WatchLost]
      alice2bob.expectMsgType[FundingLocked]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[FundingLocked]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
    }
    // note : alice is funder and bob is fundee, so alice has all the money
    test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain))
  }

  test("recv CMD_ADD_HTLC") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
      val sender = TestProbe()
      val h = BinaryData("00112233445566778899aabbccddeeff")
      sender.send(alice, CMD_ADD_HTLC(500000, h, 144))
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
      sender.expectMsg("insufficient funds (available=1000000000 msat)")
    }
  }

  test("recv CMD_ADD_HTLC (insufficient funds w/ pending htlcs 1/2)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_ADD_HTLC(500000000, "11" * 32, 144))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(500000000, "22" * 32, 144))
      sender.expectMsg("ok")
      sender.send(alice, CMD_ADD_HTLC(500000000, "33" * 32, 144))
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
      sender.expectMsg("insufficient funds (available=400000000 msat)")
    }
  }

  test("recv CMD_ADD_HTLC (while waiting for Shutdown)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_CLOSE(None))
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourShutdown.isDefined)

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
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      val htlc = UpdateAddHtlc(0, 42, Int.MaxValue, 144, BinaryData("00112233445566778899aabbccddeeff"), "")
      alice2bob.forward(bob, htlc)
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv UpdateAddHtlc (insufficient funds w/ pending htlcs 1/2)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      alice2bob.forward(bob, UpdateAddHtlc(0, 42, 500000000, 144, "11" * 32, ""))
      alice2bob.forward(bob, UpdateAddHtlc(0, 43, 500000000, 144, "22" * 32, ""))
      alice2bob.forward(bob, UpdateAddHtlc(0, 44, 500000000, 144, "33" * 32, ""))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv UpdateAddHtlc (insufficient funds w/ pending htlcs 2/2)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      alice2bob.forward(bob, UpdateAddHtlc(0, 42, 300000000, 144, "11" * 32, ""))
      alice2bob.forward(bob, UpdateAddHtlc(0, 43, 300000000, 144, "22" * 32, ""))
      alice2bob.forward(bob, UpdateAddHtlc(0, 44, 500000000, 144, "33" * 32, ""))
      bob2alice.expectMsgType[Error]
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
      alice2bob.expectMsgType[CommitSig]
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
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

  ignore("recv CMD_SIGN (while waiting for RevokeAndAck)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("cannot sign until next revocation hash is received")
    }
  }

  test("recv CommitSig") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()

      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      // actual test begins
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)

      bob2alice.expectMsgType[RevokeAndAck]
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.exists(h => h.add.id == htlc.id && h.direction == IN))
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.to_local_msat == initialState.commitments.localCommit.spec.to_local_msat)
    }
  }

  test("recv CommitSig (two htlcs with same r)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val r = BinaryData("00112233445566778899aabbccddeeff")
      val h: BinaryData = Crypto.sha256(r)

      sender.send(alice, CMD_ADD_HTLC(5000000, h, 144))
      sender.expectMsg("ok")
      val htlc1 = alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)

      sender.send(alice, CMD_ADD_HTLC(5000000, h, 144))
      sender.expectMsg("ok")
      val htlc2 = alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)

      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.proposed == htlc1 :: htlc2 :: Nil)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sign(alice, bob, alice2bob, bob2alice)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.exists(h => h.add.id == htlc1.id && h.direction == IN))
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.exists(h => h.add.id == htlc2.id && h.direction == IN))
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.to_local_msat == initialState.commitments.localCommit.spec.to_local_msat)
      assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx.txOut.count(_.amount == Satoshi(5000)) == 2)
    }
  }

  test("recv CommitSig (no changes)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      val sender = TestProbe()
      // signature is invalid but it doesn't matter
      sender.send(bob, CommitSig(0, "00" * 64, Nil))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CommitSig (invalid signature)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx

      // actual test begins
      sender.send(bob, CommitSig(0, "00" * 64, Nil))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv RevokeAndAck") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)

      // actual test begins
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.forward(alice)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    }
  }

  test("recv RevokeAndAck (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)

      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)

      // actual test begins
      bob2alice.expectMsgType[RevokeAndAck]
      sender.send(alice, RevokeAndAck(0, "11" * 32, "22" * 32, Nil))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv RevokeAndAck (unexpectedly)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
      sender.send(alice, RevokeAndAck(0, "11" * 32, "22" * 32, Nil))
      alice2bob.expectMsgType[Error]
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
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
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
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
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
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
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
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      val sender = TestProbe()
      sender.send(alice, UpdateFulfillHtlc(0, 42, "00" * 32))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv UpdateFulfillHtlc (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
      sender.send(alice, UpdateFulfillHtlc(0, 42, "00" * 32))
      alice2bob.expectMsgType[Error]
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
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
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
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
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
      val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      val sender = TestProbe()
      sender.send(alice, UpdateFailHtlc(0, 42, "some reason".getBytes()))
      alice2bob.expectMsgType[Error]
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
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourShutdown.isDefined)
    }
  }

  test("recv CMD_CLOSE (two in a row)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourShutdown.isEmpty)
      sender.send(alice, CMD_CLOSE(None))
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == NORMAL)
      awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].ourShutdown.isDefined)
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg("closing already in progress")
    }
  }

  test("recv Shutdown (no pending htlcs)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, Shutdown(0, "00" * 25))
      alice2bob.expectMsgType[Shutdown]
      alice2bob.expectMsgType[ClosingSigned]
      awaitCond(alice.stateName == NEGOTIATING)
    }
  }

  /**
    * see https://github.com/ElementsProject/lightning/issues/29
    */
  ignore("recv Shutdown (with unacked received htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      // actual test begins
      sender.send(alice, Shutdown(0, "00" * 25))
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == SHUTDOWN)
    }
  }

  /**
    * see https://github.com/ElementsProject/lightning/issues/29
    */
  ignore("recv Shutdown (with unacked sent htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      // actual test begins
      sender.send(alice, Shutdown(0, "00" * 25))
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == SHUTDOWN)
    }
  }

  test("recv Shutdown (with signed htlcs)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val (r, htlc) = addHtlc(500000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      // actual test begins
      sender.send(alice, Shutdown(0, "00" * 25))
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == SHUTDOWN)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (their commit w/ htlc)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()

      val (r1, htlc1) = addHtlc(300000000, alice, bob, alice2bob, bob2alice)
      // id 1
      val (r2, htlc2) = addHtlc(200000000, alice, bob, alice2bob, bob2alice)
      // id 2
      val (r3, htlc3) = addHtlc(100000000, alice, bob, alice2bob, bob2alice) // id 3
      sign(alice, bob, alice2bob, bob2alice)
      fulfillHtlc(1, r1, bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      val (r4, htlc4) = addHtlc(150000000, bob, alice, bob2alice, alice2bob)
      // id 1
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
      val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      assert(bobCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs
      alice ! (BITCOIN_FUNDING_SPENT, bobCommitTx)

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

  test("recv BITCOIN_FUNDING_SPENT (revoked commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()

      // alice sends 300 000 sat and bob fulfills
      // we reuse the same r (it doesn't matter here)
      val (r, htlc) = addHtlc(300000000, alice, bob, alice2bob, bob2alice)
      sign(alice, bob, alice2bob, bob2alice)

      sender.send(bob, CMD_FULFILL_HTLC(1, r))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
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

        bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      }

      val txs = for (i <- 0 until 10) yield send()
      // bob now has 10 spendable tx, 9 of them being revoked

      // let's say that bob published this tx
      val revokedTx = txs(3)
      // channel state for this revoked tx is as follows:
      // alice = 696 000
      //   bob = 300 000
      //  a->b =   4 000
      alice ! (BITCOIN_FUNDING_SPENT, revokedTx)
      alice2bob.expectMsgType[Error]
      val punishTx = alice2blockchain.expectMsgType[Publish].tx
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == CLOSING)
      Transaction.correctlySpends(punishTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      // two main outputs + 4 htlc
      assert(revokedTx.txOut.size == 6)
      // the punishment tx consumes all output but ours (which already goes to our final key)
      assert(punishTx.txIn.size == 5)
      // TODO: when changefee is implemented we should set fee = 0 and check against 304 000
      assert(punishTx.txOut == Seq(TxOut(Satoshi(301670), Script.write(OldScripts.pay2wpkh(Alice.channelParams.finalPrivKey.point)))))
    }
  }

  test("recv Error") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val (r1, htlc1) = addHtlc(300000000, alice, bob, alice2bob, bob2alice)
      // id 1
      val (r2, htlc2) = addHtlc(200000000, alice, bob, alice2bob, bob2alice)
      // id 2
      val (r3, htlc3) = addHtlc(100000000, alice, bob, alice2bob, bob2alice) // id 3
      sign(alice, bob, alice2bob, bob2alice)
      fulfillHtlc(1, r1, bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      val (r4, htlc4) = addHtlc(150000000, bob, alice, bob2alice, alice2bob)
      // id 1
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
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTx
      alice ! Error(0, "oops".getBytes())
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
