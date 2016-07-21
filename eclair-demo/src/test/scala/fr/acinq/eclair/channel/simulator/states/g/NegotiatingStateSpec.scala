package fr.acinq.eclair.channel.simulator.states.g

import akka.actor.ActorSystem
import akka.testkit.{TestActorRef, TestFSMRef, TestKit, TestProbe}
import com.google.protobuf.ByteString
import fr.acinq.bitcoin.Crypto
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel.{BITCOIN_ANCHOR_DEPTHOK, Data, State, _}
import fr.acinq.eclair.{TestBitcoinClient, _}
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
class NegotiatingStateSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike with BeforeAndAfterAll {

  type FixtureParam = Tuple6[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = TestActorRef(new PollingWatcher(new TestBitcoinClient()))
    val bob2blockchain = TestProbe()
    val paymentHandler = TestProbe()
    // note that alice.initialFeeRate != bob.initialFeeRate
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(alice2bob.ref, alice2blockchain.ref, paymentHandler.ref, Alice.channelParams, "B"))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(bob2alice.ref, bob2blockchain.ref, paymentHandler.ref, Bob.channelParams.copy(initialFeeRate = 20000), "A"))
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
    // bob fulfills
    sender.send(bob, CMD_FULFILL_HTLC(1, r))
    sender.expectMsg("ok")
    bob2alice.expectMsgType[update_fulfill_htlc]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.theirChanges.proposed.size == 1)
    // bob signs
    sender.send(bob, CMD_SIGN)
    sender.expectMsg("ok")
    bob2alice.expectMsgType[update_commit]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[update_revocation]
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.theirCommit.spec.htlcs.isEmpty)
    // alice signs
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[update_commit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[update_revocation]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.theirCommit.spec.htlcs.isEmpty)
    // alice initiates a closing
    sender.send(alice, CMD_CLOSE(None))
    alice2bob.expectMsgType[close_clearing]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[close_clearing]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == NEGOTIATING)
    awaitCond(bob.stateName == NEGOTIATING)
    test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  test("recv close_signature (theirCloseFee != ourCloseFee") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      val aliceCloseSig = alice2bob.expectMsgType[close_signature]
      alice2bob.forward(bob)
      val bob2aliceCloseSig = bob2alice.expectMsgType[close_signature]
      assert(2 * aliceCloseSig.closeFee == bob2aliceCloseSig.closeFee)
    }
  }

  test("recv close_signature (theirCloseFee == ourCloseFee") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain) =>
    within(30 seconds) {
      var aliceCloseFee, bobCloseFee = 0L
      do {
        aliceCloseFee = alice2bob.expectMsgType[close_signature].closeFee
        alice2bob.forward(bob)
        bobCloseFee = bob2alice.expectMsgType[close_signature].closeFee
        bob2alice.forward(alice)
      } while (aliceCloseFee != bobCloseFee)
      alice2blockchain.expectMsgType[Publish]
      alice2blockchain.expectMsgType[WatchConfirmed]
      bob2blockchain.expectMsgType[Publish]
      bob2blockchain.expectMsgType[WatchConfirmed]
      awaitCond(alice.stateName == CLOSING)
      awaitCond(bob.stateName == CLOSING)
    }
  }

  test("recv error") { case (alice, _, alice2bob, bob2alice, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.ourCommit.publishableTx
      alice ! error(Some("oops"))
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(Publish(tx))
      alice2blockchain.expectMsgType[WatchConfirmed]
    }
  }

}
