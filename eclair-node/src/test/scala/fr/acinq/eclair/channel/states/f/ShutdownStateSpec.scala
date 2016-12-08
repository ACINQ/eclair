package fr.acinq.eclair.channel.states.f

import akka.actor.Props
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi, Script, ScriptFlags, Transaction, TxOut}
import fr.acinq.eclair.TestBitcoinClient
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.states.{StateSpecBaseClass, StateTestsHelperMethods}
import fr.acinq.eclair.channel.{BITCOIN_FUNDING_DEPTHOK, Data, State, _}
import fr.acinq.eclair.transactions.OldScripts
import fr.acinq.eclair.wire.{AcceptChannel, CommitSig, Error, FundingCreated, FundingLocked, FundingSigned, OpenChannel, RevokeAndAck, Shutdown, UpdateAddHtlc, UpdateFailHtlc, UpdateFulfillHtlc}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class ShutdownStateSpec extends StateSpecBaseClass with StateTestsHelperMethods {

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
    alice2blockchain.expectMsgType[WatchConfirmed]
    alice2blockchain.forward(blockchainA)
    alice2blockchain.expectMsgType[WatchSpent]
    alice2blockchain.forward(blockchainA)
    alice2blockchain.expectMsgType[Publish]
    alice2blockchain.forward(blockchainA)
    bob2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[WatchSpent]
    bob ! BITCOIN_FUNDING_DEPTHOK
    bob2blockchain.expectMsgType[WatchLost]
    bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchLost]
    alice2blockchain.forward(blockchainA)
    alice2bob.expectMsgType[FundingLocked]
    alice2bob.forward(bob)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    // note : alice is funder and bob is fundee, so alice has all the money
    val sender = TestProbe()
    // alice sends an HTLC to bob
    val r1: BinaryData = "11" * 32
    val h1: BinaryData = Crypto.sha256(r1)
    val amount1 = 300000000
    sender.send(alice, CMD_ADD_HTLC(amount1, h1, 1440))
    sender.expectMsg("ok")
    val htlc1 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.proposed == htlc1 :: Nil)
    val r2: BinaryData = "22" * 32
    val h2: BinaryData = Crypto.sha256(r1)
    val amount2 = 200000000
    sender.send(alice, CMD_ADD_HTLC(amount2, h2, 1440))
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
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.proposed == Nil && bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.acked == htlc1 :: htlc2 :: Nil)
    // alice initiates a closing
    sender.send(alice, CMD_CLOSE(None))
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == SHUTDOWN)
    awaitCond(bob.stateName == SHUTDOWN)
    test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain))
  }

  test("recv CMD_FULFILL_HTLC") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_FULFILL_HTLC(1, "11" * 32))
      sender.expectMsg("ok")
      val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
      awaitCond(bob.stateData == initialState.copy(commitments = initialState.commitments.copy(localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fulfill))))
    }
  }

  test("recv CMD_FULFILL_HTLC (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_FULFILL_HTLC(42, "12" * 32))
      sender.expectMsg("unknown htlc id=42")
      assert(initialState == bob.stateData)
    }
  }

  test("recv CMD_FULFILL_HTLC (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_FULFILL_HTLC(1, "00" * 32))
      sender.expectMsg("invalid htlc preimage for htlc id=1")
      assert(initialState == bob.stateData)
    }
  }

  test("recv UpdateFulfillHtlc") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
      val fulfill = UpdateFulfillHtlc(0, 1, "11" * 32)
      sender.send(alice, fulfill)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments == initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fulfill)))
    }
  }

  test("recv UpdateFulfillHtlc (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTx
      val sender = TestProbe()
      val fulfill = UpdateFulfillHtlc(0, 42, "00" * 32)
      sender.send(alice, fulfill)
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv UpdateFulfillHtlc (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTx
      val sender = TestProbe()
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
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_FAIL_HTLC(1, "some reason"))
      sender.expectMsg("ok")
      val fail = bob2alice.expectMsgType[UpdateFailHtlc]
      awaitCond(bob.stateData == initialState.copy(commitments = initialState.commitments.copy(localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fail))))
    }
  }

  test("recv CMD_FAIL_HTLC (unknown htlc id)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
      sender.send(bob, CMD_FAIL_HTLC(42, "some reason"))
      sender.expectMsg("unknown htlc id=42")
      assert(initialState == bob.stateData)
    }
  }

  test("recv UpdateFailHtlc") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
      val fail = UpdateFailHtlc(0, 1, "some reason".getBytes())
      sender.send(alice, fail)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments == initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fail)))
    }
  }

  test("recv UpdateFailHtlc (unknown htlc id)") { case (alice, _, alice2bob, _, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTx
      val sender = TestProbe()
      sender.send(alice, UpdateFailHtlc(0, 42, "some reason".getBytes()))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv CMD_SIGN") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      // we need to have something to sign so we first send a fulfill and acknowledge (=sign) it
      sender.send(bob, CMD_FULFILL_HTLC(1, "11" * 32))
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
      sender.expectNoMsg() // just ignored
      //sender.expectMsg("cannot sign when there are no changes")
    }
  }

  ignore("recv CMD_SIGN (while waiting for RevokeAndAck)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTx
      val sender = TestProbe()
      sender.send(alice, UpdateFulfillHtlc(0, 1, "12" * 32))
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isLeft)
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("cannot sign until next revocation hash is received")
    }
  }

  test("recv CommitSig") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(bob, CMD_FULFILL_HTLC(1, "11" * 32))
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
      val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTx
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
      val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTx
      val sender = TestProbe()
      sender.send(bob, CommitSig(0, "00" * 64, Nil))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv RevokeAndAck (with remaining htlcs on both sides)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      fulfillHtlc(2, "22" * 32, bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      val sender = TestProbe()
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      // actual test starts here
      bob2alice.forward(bob)
      assert(alice.stateName == SHUTDOWN)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.spec.htlcs.size == 1)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteCommit.spec.htlcs.size == 2)
    }
  }

  test("recv RevokeAndAck (with remaining htlcs on one side)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      fulfillHtlc(1, "11" * 32, bob, alice, bob2alice, alice2bob)
      fulfillHtlc(2, "22" * 32, bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      val sender = TestProbe()
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      // actual test starts here
      bob2alice.forward(bob)
      assert(alice.stateName == SHUTDOWN)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.spec.htlcs.isEmpty)
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteCommit.spec.htlcs.size == 2)
    }
  }

  test("recv RevokeAndAck (no more htlcs on either side)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      fulfillHtlc(1, "11" * 32, bob, alice, bob2alice, alice2bob)
      fulfillHtlc(2, "22" * 32, bob, alice, bob2alice, alice2bob)
      sign(bob, alice, bob2alice, alice2bob)
      val sender = TestProbe()
      sender.send(alice, CMD_SIGN)
      sender.expectMsg("ok")
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      // actual test starts here
      bob2alice.forward(alice)
      awaitCond(alice.stateName == NEGOTIATING)
    }
  }

  test("recv RevokeAndAck (invalid preimage)") { case (alice, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTx
      val sender = TestProbe()
      sender.send(bob, CMD_FULFILL_HTLC(1, "11" * 32))
      sender.expectMsg("ok")
      bob2alice.expectMsgType[UpdateFulfillHtlc]
      bob2alice.forward(alice)
      sender.send(bob, CMD_SIGN)
      sender.expectMsg("ok")
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[RevokeAndAck]
      awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isLeft)
      sender.send(bob, RevokeAndAck(0, "11" * 32, "22" * 32, Nil))
      bob2alice.expectMsgType[Error]
      awaitCond(bob.stateName == CLOSING)
      bob2blockchain.expectMsg(Publish(tx))
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv RevokeAndAck (unexpectedly)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTx
      val sender = TestProbe()
      awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isRight)
      sender.send(alice, RevokeAndAck(0, "11" * 32, "22" * 32, Nil))
      alice2bob.expectMsgType[Error]
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv BITCOIN_ANCHOR_SPENT (their commit)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      // bob publishes his current commit tx, which contains two pending htlcs alice->bob
      val bobCommitTx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTx
      assert(bobCommitTx.txOut.size == 3) // one main outputs (bob has zero) and 2 pending htlcs
      alice ! (BITCOIN_FUNDING_SPENT, bobCommitTx)

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
      val revokedTx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTx

      // bob fulfills one of the pending htlc (just so that he can have a new sig)
      fulfillHtlc(1, "11" * 32, bob, alice, bob2alice, alice2bob)
      // alice signs
      sign(bob, alice, bob2alice, alice2bob)
      sign(alice, bob, alice2bob, bob2alice)
      // bob now has a new commitment tx

      // bob published the revoked tx
      alice ! (BITCOIN_FUNDING_SPENT, revokedTx)
      alice2bob.expectMsgType[Error]
      val punishTx = alice2blockchain.expectMsgType[Publish].tx
      alice2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == CLOSING)
      Transaction.correctlySpends(punishTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      // one main output + 2 htlc
      assert(revokedTx.txOut.size == 3)
      // the punishment tx consumes all output but ours (which already goes to our final key)
      assert(punishTx.txIn.size == 2)
      assert(punishTx.txOut == Seq(TxOut(Satoshi(500000), Script.write(OldScripts.pay2wpkh(Alice.channelParams.finalPrivKey.point)))))
    }
  }

  test("recv error") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val aliceCommitTx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTx
      alice ! Error(0, "oops".getBytes)
      alice2blockchain.expectMsg(Publish(aliceCommitTx))
      assert(aliceCommitTx.txOut.size == 1) // only one main output

      alice2blockchain.expectMsgType[WatchConfirmed].txId == aliceCommitTx.txid
      alice2blockchain.expectNoMsg()

      awaitCond(alice.stateName == CLOSING)
      assert(alice.stateData.asInstanceOf[DATA_CLOSING].ourCommitPublished == Some(aliceCommitTx))
    }
  }

}
