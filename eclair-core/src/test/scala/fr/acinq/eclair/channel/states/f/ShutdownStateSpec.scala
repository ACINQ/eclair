package fr.acinq.eclair.channel.states.f

import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Crypto.Scalar
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi, ScriptFlags, Transaction}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.payment.PaymentLifecycle
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire.{CommitSig, Error, FailureMessageCodecs, PermanentChannelFailure, RevokeAndAck, Shutdown, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFee, UpdateFulfillHtlc}
import fr.acinq.eclair.{Globals, TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class ShutdownStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple6[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      val sender = TestProbe()
      // alice sends an HTLC to bob
      val r1: BinaryData = "11" * 32
      val h1: BinaryData = Crypto.sha256(r1)
      val amount1 = 300000000
      val expiry1 = 400144
      val cmd1 = PaymentLifecycle.buildCommand(amount1, expiry1, h1, Hop(null, TestConstants.Bob.nodeParams.privateKey.publicKey.toBin, null) :: Nil)._1.copy(commit = false)
      sender.send(alice, cmd1)
      sender.expectMsg("ok")
      val htlc1 = alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.proposed == htlc1 :: Nil)
      // alice sends another HTLC to bob
      val r2: BinaryData = "22" * 32
      val h2: BinaryData = Crypto.sha256(r2)
      val amount2 = 200000000
      val expiry2 = 400144
      val cmd2 = PaymentLifecycle.buildCommand(amount2, expiry2, h2, Hop(null, TestConstants.Bob.nodeParams.privateKey.publicKey.toBin, null) :: Nil)._1.copy(commit = false)
      sender.send(alice, cmd2)
      sender.expectMsg("ok")
      val htlc2 = alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.proposed == htlc1 :: htlc2 :: Nil)
      // alice signs
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.forward(alice)
      // alice initiates a closing
      sender.send(alice, CMD_CLOSE(None))
      alice2bob.expectMsgType[Shutdown]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
      bob2alice.expectMsgType[Shutdown]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[RevokeAndAck]
      alice2bob.forward(bob)
      awaitCond(alice.stateName == SHUTDOWN)
      awaitCond(bob.stateName == SHUTDOWN)
      test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain))
    }
  }

  test("recv CMD_FULFILL_HTLC") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_FULFILL_HTLC(0, "11" * 32))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
      awaitCond(bob.stateData == initialState.copy(
        commitments = initialState.commitments.copy(
          localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fulfill))))
    }
  }

  test("recv CMD_FULFILL_HTLC (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_FULFILL_HTLC(42, "12" * 32))
      sender.expectMsg(Failure(UnknownHtlcId(42)))
      assert(initialState == bob.stateData)
    }
  }

  test("recv CMD_FULFILL_HTLC (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_FULFILL_HTLC(1, "00" * 32))
      sender.expectMsg(Failure(InvalidHtlcPreimage(1)))
      assert(initialState == bob.stateData)
    }
  }

  test("recv UpdateFulfillHtlc") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
      val fulfill = UpdateFulfillHtlc("00" * 32, 0, "11" * 32)
      sender.send(alice, fulfill)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments == initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fulfill)))
    }
  }

  test("recv UpdateFulfillHtlc (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      val fulfill = UpdateFulfillHtlc("00" * 32, 42, "00" * 32)
      sender.send(alice, fulfill)
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv UpdateFulfillHtlc (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      sender.send(alice, UpdateFulfillHtlc("00" * 32, 42, "00" * 32))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_FAIL_HTLC") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_FAIL_HTLC(1, Right(PermanentChannelFailure)))
      sender.expectMsg("ok")
      val fail = bob2alice.expectMsgType[UpdateFailHtlc]
      awaitCond(bob.stateData == initialState.copy(
        commitments = initialState.commitments.copy(
          localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fail))))
    }
  }

  test("recv CMD_FAIL_MALFORMED_HTLC") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_FAIL_MALFORMED_HTLC(1, Crypto.sha256(BinaryData.empty), FailureMessageCodecs.BADONION))
      sender.expectMsg("ok")
      val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
      awaitCond(bob.stateData == initialState.copy(
        commitments = initialState.commitments.copy(
          localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fail))))
    }
  }

  test("recv CMD_FAIL_HTLC (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_FAIL_HTLC(42, Right(PermanentChannelFailure)))
      sender.expectMsg(Failure(UnknownHtlcId(42)))
      assert(initialState == bob.stateData)
    }
  }

  test("recv UpdateFailHtlc") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
      val fail = UpdateFailHtlc("00" * 32, 1, "00" * 152)
      sender.send(alice, fail)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments == initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fail)))
    }
  }

  test("recv UpdateFailHtlc (unknown htlc id)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      sender.send(alice, UpdateFailHtlc("00" * 32, 42, "00" * 152))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv UpdateFailMalformedHtlc") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
      val fail = UpdateFailMalformedHtlc("00" * 32, 1, Crypto.sha256(BinaryData.empty), FailureMessageCodecs.BADONION)
      sender.send(alice, fail)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments == initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fail)))
    }
  }

  test("recv CMD_SIGN") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      // we need to have something to sign so we first send a fulfill and acknowledge (=sign) it
      sender.send(bob, CMD_FULFILL_HTLC(0, "11" * 32))
      bob2alice.expectMsgType[UpdateFulfillHtlc]
      bob2alice.forward(alice)
      sender.send(bob, CMD_SIGN)
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[RevokeAndAck]
      alice2bob.forward(bob)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isLeft)
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

  test("recv CMD_SIGN (while waiting for RevokeAndAck)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(bob, CMD_FULFILL_HTLC(0, "11" * 32))
      sender.expectMsg("ok")
      bob2alice.expectMsgType[UpdateFulfillHtlc]
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[CommitSig]
      awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isLeft)
      val waitForRevocation = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.left.toOption.get
      assert(waitForRevocation.reSignAsap === false)

      // actual test starts here
      sender.send(bob, CMD_SIGN)
      sender.expectNoMsg(300 millis)
      assert(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo === Left(waitForRevocation))
    }
  }

  test("recv CommitSig") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(bob, CMD_FULFILL_HTLC(0, "11" * 32))
      sender.expectMsg("ok")
      bob2alice.expectMsgType[UpdateFulfillHtlc]
      bob2alice.forward(alice)
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[RevokeAndAck]
    }
  }

  test("recv CommitSig (no changes)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      // signature is invalid but it doesn't matter
      sender.send(bob, CommitSig("00" * 32, "00" * 64, Nil))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CommitSig (invalid signature)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      sender.send(bob, CommitSig("00" * 32, "00" * 64, Nil))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv RevokeAndAck (with remaining htlcs on both sides)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      fulfillHtlc(1, "22" * 32, bob, alice, bob2alice, alice2bob)
      // this will cause alice and bob to receive RevokeAndAcks
      crossSign(bob, alice, bob2alice, alice2bob)
      // actual test starts here
      assert(alice.stateName == SHUTDOWN)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.spec.htlcs.size == 1)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteCommit.spec.htlcs.size == 1)
    }
  }

  test("recv RevokeAndAck (with remaining htlcs on one side)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      fulfillHtlc(0, "11" * 32, bob, alice, bob2alice, alice2bob)
      fulfillHtlc(1, "22" * 32, bob, alice, bob2alice, alice2bob)
      val sender = TestProbe()
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[RevokeAndAck]
      // actual test starts here
      bob2alice.forward(bob)
      assert(alice.stateName == SHUTDOWN)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.spec.htlcs.isEmpty)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteCommit.spec.htlcs.size == 2)
    }
  }

  test("recv RevokeAndAck (no more htlcs on either side)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      fulfillHtlc(0, "11" * 32, bob, alice, bob2alice, alice2bob)
      fulfillHtlc(1, "22" * 32, bob, alice, bob2alice, alice2bob)
      crossSign(bob, alice, bob2alice, alice2bob)
      // actual test starts here
      awaitCond(alice.stateName == NEGOTIATING)
    }
  }

  test("recv RevokeAndAck (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      sender.send(bob, CMD_FULFILL_HTLC(0, "11" * 32))
      sender.expectMsg("ok")
      bob2alice.expectMsgType[UpdateFulfillHtlc]
      bob2alice.forward(alice)
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[RevokeAndAck]
      awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isLeft)
      sender.send(bob, RevokeAndAck("00" * 32, Scalar("11" * 32), Scalar("22" * 32).toPoint))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv RevokeAndAck (unexpectedly)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isRight)
      sender.send(alice, RevokeAndAck("00" * 32, Scalar("11" * 32), Scalar("22" * 32).toPoint))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_UPDATE_FEE") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(alice, CMD_UPDATE_FEE(20000))
      sender.expectMsg("ok")
      val fee = alice2bob.expectMsgType[UpdateFee]
      awaitCond(alice.stateData == initialState.copy(
        commitments = initialState.commitments.copy(
          localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fee))))
    }
  }

  test("recv CMD_UPDATE_FEE (when fundee)") { case (_, bob, _, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_UPDATE_FEE(20000))
      sender.expectMsg(Failure(FundeeCannotSendUpdateFee))
      assert(initialState == bob.stateData)
    }
  }

  test("recv UpdateFee") { case (_, bob, _, _, _, _) =>
    within(30 seconds) {
      val initialData = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      val fee = UpdateFee("00" * 32, 12000)
      bob ! fee
      awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(remoteChanges = initialData.commitments.remoteChanges.copy(proposed = initialData.commitments.remoteChanges.proposed :+ fee))))
    }
  }

  test("recv UpdateFee (when sender is not funder)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      sender.send(alice, UpdateFee("00" * 32, 12000))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv UpdateFee (sender can't afford it)") { case (_, bob, _, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      val fee = UpdateFee("00" * 32, 100000000)
      // we first update the global variable so that we don't trigger a 'fee too different' error
      Globals.feeratePerKw.set(fee.feeratePerKw)
      sender.send(bob, fee)
      val error = bob2alice.expectMsgType[Error]
      assert(new String(error.data) === CannotAffordFees(missingSatoshis = 72120000L, reserveSatoshis = 20000L, feesSatoshis = 72400000L).getMessage)
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv UpdateFee (local/remote feerates are too different)") { case (_, bob, _, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      val sender = TestProbe()
      sender.send(bob, UpdateFee("00" * 32, 65000))
      val error = bob2alice.expectMsgType[Error]
      assert(new String(error.data) === "local/remote feerates are too different: remoteFeeratePerKw=65000 localFeeratePerKw=10000")
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CurrentBlockCount (no htlc timed out)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(alice, CurrentBlockCount(400143))
      awaitCond(alice.stateData == initialState)
    }
  }

  test("recv CurrentBlockCount (an htlc timed out)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
      val aliceCommitTx = initialState.commitments.localCommit.publishableTxs.commitTx.tx
      sender.send(alice, CurrentBlockCount(400145))
      alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))

      val watch = alice2blockchain.expectMsgType[WatchConfirmed]
      assert(watch.txId === aliceCommitTx.txid)
      assert(watch.event === BITCOIN_LOCALCOMMIT_DONE)
    }
  }

  test("recv CurrentFeerate (when funder, triggers an UpdateFee)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
      val event = CurrentFeerate(20000)
      sender.send(alice, event)
      alice2bob.expectMsg(UpdateFee(initialState.commitments.channelId, event.feeratePerKw))
    }
  }

  test("recv CurrentFeerate (when funder, doesn't trigger an UpdateFee)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val event = CurrentFeerate(10010)
      sender.send(alice, event)
      alice2bob.expectNoMsg(500 millis)
    }
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are close)") { case (_, bob, _, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val event = CurrentFeerate(11000)
      sender.send(bob, event)
      bob2alice.expectNoMsg(500 millis)
    }
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are very different)") { case (_, bob, _, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val sender = TestProbe()
      val event = CurrentFeerate(1000)
      sender.send(bob, event)
      bob2alice.expectMsgType[Error]
      bob2blockchain.expectMsgType[PublishAsap]
      bob2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(bob.stateName == CLOSING)
    }
  }

  test("recv CurrentFeerate (ignore negative feerate)") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      // this happens when in regtest mode
      val event = CurrentFeerate(-1)
      sender.send(alice, event)
      alice2bob.expectNoMsg(500 millis)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (their commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      // bob publishes his current commit tx, which contains two pending htlcs alice->bob
      val bobCommitTx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      assert(bobCommitTx.txOut.size == 4) // two main outputs and 2 pending htlcs
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)

      val watch = alice2blockchain.expectMsgType[WatchConfirmed]
      assert(watch.txId === bobCommitTx.txid)
      assert(watch.event === BITCOIN_REMOTECOMMIT_DONE)

      val amountClaimed = (for (i <- 0 until 3) yield {
        val claimHtlcTx = alice2blockchain.expectMsgType[PublishAsap].tx
        assert(claimHtlcTx.txIn.size == 1)
        assert(claimHtlcTx.txOut.size == 1)
        Transaction.correctlySpends(claimHtlcTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        claimHtlcTx.txOut(0).amount
      }).sum
      // htlc will timeout and be eventually refunded so we have a little less than fundingSatoshis - pushMsat = 1000000 - 200000 = 800000 (because fees)
      assert(amountClaimed == Satoshi(774070))

      assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_HTLC_SPENT)
      assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_HTLC_SPENT)
      alice2blockchain.expectNoMsg(1 second)

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimHtlcSuccessTxs.size == 0)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimHtlcTimeoutTxs.size == 2)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (their next commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      // bob fulfills the first htlc
      fulfillHtlc(0, "11" * 32, bob, alice, bob2alice, alice2bob)
      // then signs
      val sender = TestProbe()
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[RevokeAndAck]
      alice2bob.forward(bob)
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      // we intercept bob's revocation
      // as far as alice knows, bob currently has two valid unrevoked commitment transactions

      // bob publishes his current commit tx, which contains one pending htlc alice->bob
      val bobCommitTx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      assert(bobCommitTx.txOut.size == 3) // two main outputs and 1 pending htlc
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)

      val watch = alice2blockchain.expectMsgType[WatchConfirmed]
      assert(watch.txId === bobCommitTx.txid)
      assert(watch.event === BITCOIN_NEXTREMOTECOMMIT_DONE)

      val amountClaimed = (for (i <- 0 until 2) yield {
        val claimHtlcTx = alice2blockchain.expectMsgType[PublishAsap].tx
        assert(claimHtlcTx.txIn.size == 1)
        assert(claimHtlcTx.txOut.size == 1)
        Transaction.correctlySpends(claimHtlcTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
        claimHtlcTx.txOut(0).amount
      }).sum
      // htlc will timeout and be eventually refunded so we have a little less than fundingSatoshis - pushMsat - htlc1 = 1000000 - 200000 - 300 000 = 500000 (because fees)
      assert(amountClaimed == Satoshi(481230))

      assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_HTLC_SPENT)
      alice2blockchain.expectNoMsg(1 second)

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.isDefined)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get.claimHtlcSuccessTxs.size == 0)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get.claimHtlcTimeoutTxs.size == 1)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (revoked tx)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val revokedTx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      // two main outputs + 2 htlc
      assert(revokedTx.txOut.size == 4)

      // bob fulfills one of the pending htlc (just so that he can have a new sig)
      fulfillHtlc(0, "11" * 32, bob, alice, bob2alice, alice2bob)
      // bob and alice sign
      crossSign(bob, alice, bob2alice, alice2bob)
      // bob now has a new commitment tx

      // bob published the revoked tx
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, revokedTx)
      alice2bob.expectMsgType[Error]

      val watch = alice2blockchain.expectMsgType[WatchConfirmed]
      assert(watch.txId === revokedTx.txid)
      assert(watch.event === BITCOIN_PENALTY_DONE)

      val mainTx = alice2blockchain.expectMsgType[PublishAsap].tx
      val penaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx
      alice2blockchain.expectNoMsg(1 second)

      Transaction.correctlySpends(mainTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      Transaction.correctlySpends(penaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      // two main outputs are 300 000 and 200 000
      assert(mainTx.txOut(0).amount == Satoshi(284950))
      assert(penaltyTx.txOut(0).amount == Satoshi(195170))

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
    }
  }

  test("recv Error") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error("00" * 32, "oops".getBytes)
      alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))
      assert(aliceCommitTx.txOut.size == 4) // two main outputs and two htlcs

      val watch = alice2blockchain.expectMsgType[WatchConfirmed]
      assert(watch.txId === aliceCommitTx.txid)
      assert(watch.event === BITCOIN_LOCALCOMMIT_DONE)

      // alice can claim both htlc after a timeout
      // so we expect 5 transactions:
      // - 1 tx to claim the main delayed output
      // - 2 txes for each htlc
      // - 2 txes for each delayed output of the claimed htlc
      val claimTxs = for (i <- 0 until 5) yield alice2blockchain.expectMsgType[PublishAsap].tx

      // the main delayed output spends the commitment transaction
      Transaction.correctlySpends(claimTxs(0), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      // 2nd stage transactions spend the commitment transaction
      Transaction.correctlySpends(claimTxs(1), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      Transaction.correctlySpends(claimTxs(2), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      // 3rd stage transactions spend their respective HTLC-Timeout transactions
      Transaction.correctlySpends(claimTxs(3), claimTxs(1) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      Transaction.correctlySpends(claimTxs(4), claimTxs(2) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

      assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_HTLC_SPENT)
      assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_HTLC_SPENT)
      alice2blockchain.expectNoMsg(1 second)

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)
    }
  }

}
