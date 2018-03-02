package fr.acinq.eclair.channel.states.g

import akka.actor.Status.Failure
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.Satoshi
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.channel.Helpers.Closing
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.payment.Local
import fr.acinq.eclair.wire.{ClosingSigned, CommitSig, Error, Shutdown}
import fr.acinq.eclair.{Globals, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.Tag
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._
import scala.util.Success

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class NegotiatingStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple6[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayer)
      val sender = TestProbe()
      // alice initiates a closing
      if (test.tags.contains("fee2")) Globals.feeratesPerKw.set(FeeratesPerKw.single(4319)) else Globals.feeratesPerKw.set(FeeratesPerKw.single(10000))
      sender.send(bob, CMD_CLOSE(None))
      bob2alice.expectMsgType[Shutdown]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[Shutdown]
      awaitCond(alice.stateName == NEGOTIATING)
      // NB: at this point, alice has already computed and sent the first ClosingSigned message
      // In order to force a fee negotiation, we will change the current fee before forwarding
      // the Shutdown message to alice, so that alice computes a different initial closing fee.
      if (test.tags.contains("fee2")) Globals.feeratesPerKw.set(FeeratesPerKw.single(4316)) else Globals.feeratesPerKw.set(FeeratesPerKw.single(5000))
      alice2bob.forward(bob)
      awaitCond(bob.stateName == NEGOTIATING)
      test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain))
    }
  }

  test("recv CMD_ADD_HTLC") { case (alice, _, alice2bob, _, _, _) =>
    within(30 seconds) {
      alice2bob.expectMsgType[ClosingSigned]
      val sender = TestProbe()
      val add = CMD_ADD_HTLC(500000000, "11" * 32, expiry = 300000)
      sender.send(alice, add)
      val error = ChannelUnavailable(channelId(alice))
      sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), None)))
      alice2bob.expectNoMsg(200 millis)
    }
  }

  test("recv ClosingSigned (theirCloseFee != ourCloseFee)") { case (alice, bob, alice2bob, bob2alice, _, _) =>
    within(30 seconds) {
      // alice initiates the negotiation
      val aliceCloseSig1 = alice2bob.expectMsgType[ClosingSigned]
      alice2bob.forward(bob)
      // bob answers with a counter proposition
      val bobCloseSig1 = bob2alice.expectMsgType[ClosingSigned]
      assert(aliceCloseSig1.feeSatoshis > bobCloseSig1.feeSatoshis)
      // actual test starts here
      val initialState = alice.stateData.asInstanceOf[DATA_NEGOTIATING]
      bob2alice.forward(alice)
      val aliceCloseSig2 = alice2bob.expectMsgType[ClosingSigned]
      // BOLT 2: If the receiver [doesn't agree with the fee] it SHOULD propose a value strictly between the received fee-satoshis and its previously-sent fee-satoshis
      assert(aliceCloseSig2.feeSatoshis < aliceCloseSig1.feeSatoshis && aliceCloseSig2.feeSatoshis > bobCloseSig1.feeSatoshis)
      awaitCond(alice.stateData.asInstanceOf[DATA_NEGOTIATING].closingTxProposed.last.map(_.localClosingSigned) == initialState.closingTxProposed.last.map(_.localClosingSigned) :+ aliceCloseSig2)
    }
  }

  def testFeeConverge(alice: TestFSMRef[State, Data, Channel],
                      bob: TestFSMRef[State, Data, Channel],
                      alice2bob: TestProbe,
                      bob2alice: TestProbe,
                      alice2blockchain: TestProbe,
                      bob2blockchain: TestProbe) = {
    within(30 seconds) {
      var aliceCloseFee, bobCloseFee = 0L
      do {
        aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
        alice2bob.forward(bob)
        if (!bob2blockchain.msgAvailable) {
          bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
          bob2alice.forward(alice)
        }
      } while (!alice2blockchain.msgAvailable && !bob2blockchain.msgAvailable)
    }
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee) (fee 1)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain) =>
    testFeeConverge(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
  }

  test("recv ClosingSigned (theirCloseFee == ourCloseFee) (fee 2)", Tag("fee2")) { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain) =>
    testFeeConverge(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
  }

  test("recv ClosingSigned (fee too high)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
      val sender = TestProbe()
      val tx = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
      sender.send(bob, aliceCloseSig.copy(feeSatoshis = 99000)) // sig doesn't matter, it is checked later
      val error = bob2alice.expectMsgType[Error]
      assert(new String(error.data).startsWith("invalid close fee: fee_satoshis=99000"))
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[PublishAsap]
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv ClosingSigned (invalid sig)") { case (_, bob, alice2bob, bob2alice, _, bob2blockchain) =>
    within(30 seconds) {
      val aliceCloseSig = alice2bob.expectMsgType[ClosingSigned]
      val sender = TestProbe()
      val tx = bob.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
      sender.send(bob, aliceCloseSig.copy(signature = "00" * 64))
      val error = bob2alice.expectMsgType[Error]
      assert(new String(error.data).startsWith("invalid close signature"))
      bob2blockchain.expectMsg(PublishAsap(tx))
      bob2blockchain.expectMsgType[PublishAsap]
      bob2blockchain.expectMsgType[WatchConfirmed]
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (counterparty's mutual close)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain) =>
    within(30 seconds) {
      var aliceCloseFee, bobCloseFee = 0L
      do {
        aliceCloseFee = alice2bob.expectMsgType[ClosingSigned].feeSatoshis
        alice2bob.forward(bob)
        if (!bob2blockchain.msgAvailable) {
          bobCloseFee = bob2alice.expectMsgType[ClosingSigned].feeSatoshis
          if (aliceCloseFee != bobCloseFee) {
            bob2alice.forward(alice)
          }
        }
      } while (!alice2blockchain.msgAvailable && !bob2blockchain.msgAvailable)
      // at this point alice and bob have converged on closing fees, but alice has not yet received the final signature whereas bob has
      // bob publishes the mutual close and alice is notified that the funding tx has been spent
      // actual test starts here
      assert(alice.stateName == NEGOTIATING)
      val mutualCloseTx = bob2blockchain.expectMsgType[PublishAsap].tx
      assert(bob2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(mutualCloseTx))
      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, mutualCloseTx)
      alice2blockchain.expectMsgType[PublishAsap]
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.expectNoMsg(100 millis)
      assert(alice.stateName == CLOSING)
    }
  }

  test("recv BITCOIN_FUNDING_SPENT (an older mutual close)") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain) =>
    within(30 seconds) {
      val aliceClose1 = alice2bob.expectMsgType[ClosingSigned]
      alice2bob.forward(bob)
      val bobClose1 = bob2alice.expectMsgType[ClosingSigned]
      bob2alice.forward(alice)
      val aliceClose2 = alice2bob.expectMsgType[ClosingSigned]
      assert(aliceClose2.feeSatoshis != bobClose1.feeSatoshis)
      // at this point alice and bob have not yet converged on closing fees, but bob decides to publish a mutual close with one of the previous sigs
      val d = bob.stateData.asInstanceOf[DATA_NEGOTIATING]
      implicit val log = bob.underlyingActor.implicitLog
      val Success(bobClosingTx) = Closing.checkClosingSignature(Bob.keyManager, d.commitments, d.localShutdown.scriptPubKey, d.remoteShutdown.scriptPubKey, Satoshi(aliceClose1.feeSatoshis), aliceClose1.signature)

      alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobClosingTx)
      alice2blockchain.expectMsgType[PublishAsap]
      alice2blockchain.expectMsgType[WatchConfirmed]
      alice2blockchain.expectNoMsg(100 millis)
      assert(alice.stateName == CLOSING)
    }
  }


  test("recv CMD_CLOSE") { case (alice, _, _, _, _, _) =>
    within(30 seconds) {
      val sender = TestProbe()
      sender.send(alice, CMD_CLOSE(None))
      sender.expectMsg(Failure(ClosingAlreadyInProgress(channelId(alice))))
    }
  }

  test("recv Error") { case (alice, _, _, _, alice2blockchain, _) =>
    within(30 seconds) {
      val tx = alice.stateData.asInstanceOf[DATA_NEGOTIATING].commitments.localCommit.publishableTxs.commitTx.tx
      alice ! Error("00" * 32, "oops".getBytes())
      awaitCond(alice.stateName == CLOSING)
      alice2blockchain.expectMsg(PublishAsap(tx))
      alice2blockchain.expectMsgType[PublishAsap]
      assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(tx))
    }
  }

}
