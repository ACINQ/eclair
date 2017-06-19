package fr.acinq.eclair.channel.states.e

import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.TestkitBaseClass
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.wire.{CommitSig, RevokeAndAck, UpdateAddHtlc}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */
@RunWith(classOf[JUnitRunner])
class OfflineStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = Tuple7[TestFSMRef[State, Data, Channel], TestFSMRef[State, Data, Channel], TestProbe, TestProbe, TestProbe, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      test((alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, relayer))
    }
  }

  test("simple offline test") { case (alice, bob, alice2bob, bob2alice, alice2blockchain, _, _) =>
    val sender = TestProbe()

    sender.send(alice, CMD_ADD_HTLC(1000000, BinaryData("42" * 32), 400144))
    val ab_add_0 = alice2bob.expectMsgType[UpdateAddHtlc]
    // add ->b
    alice2bob.forward(bob, ab_add_0)

    sender.send(alice, CMD_SIGN)
    val ab_sig_0 = alice2bob.expectMsgType[CommitSig]
    // sig ->b
    alice2bob.forward(bob, ab_sig_0)

    val ba_rev_0 = bob2alice.expectMsgType[RevokeAndAck]
    val ba_sig_0 = bob2alice.expectMsgType[CommitSig]

    bob2alice.expectNoMsg(500 millis)

    sender.send(alice, INPUT_DISCONNECTED)
    sender.send(bob, INPUT_DISCONNECTED)
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
    sender.send(alice, INPUT_RECONNECTED(alice2bob.ref))
    sender.send(bob, INPUT_RECONNECTED(bob2alice.ref))

    val ab_add_0_re = alice2bob.expectMsg(ab_add_0)
    // add ->b
    alice2bob.forward(bob, ab_add_0_re)

    val ab_sig_0_re = alice2bob.expectMsg(ab_sig_0)
    // sig ->b
    alice2bob.forward(bob, ab_sig_0_re)

    val ba_rev_0_re = bob2alice.expectMsg(ba_rev_0)
    // rev ->a
    bob2alice.forward(alice, ba_rev_0)

    alice2bob.expectNoMsg(500 millis)

    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)

  }

  /*test("sig1-rev1-sig2 and counterparty replies with rev1") { case (alice, bob, alice2bob, bob2alice, _, _, _) =>
    val sender = TestProbe()
    sender.send(alice, CMD_ADD_HTLC(1000000, BinaryData("42" * 32), 400144))
    val ab_add_0 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, ab_add_0)

    sender.send(alice, CMD_SIGN)
    val ab_sig_1 = alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob, ab_sig_1)

    sender.send(alice, CMD_ADD_HTLC(1000000, BinaryData("42" * 32), 400144))
    val ab_add_1 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob, ab_add_1)

    val ba_rev_1 = bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice, ba_rev_1)
    // autosig
    val ba_sig_3 = bob2alice.expectMsgType[CommitSig]

    sender.send(alice, CMD_SIGN)
    val ab_sig_2 = alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob, ab_sig_2)
    val ba_rev_2 = bob2alice.expectMsgType[RevokeAndAck]

    val comm_a = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(comm_a.unackedMessages.map(Commitments.msg2String(_)).mkString(" ") === "add-1 sig") // this is seg2

    val comm_b = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(comm_b.unackedMessages.map(Commitments.msg2String(_)).mkString(" ") === "sig rev") // this is sig3

    // let's assume there is a disconnection here

    // SCENARIO A1: B did not receive sig2 (it did above, but A can't tell)
    // => A expects rev2 but it will first receive rev1
    val comm_a1_1 = comm_a
    // A ignores rev1
    assert(Commitments.receiveRevocation(comm_a1_1, ba_rev_1).isLeft)
    // since A sent back sig2 so b replies with rev2
    val comm_a1_2 = Commitments.receiveRevocation(comm_a1_1, ba_rev_2).right.get
    assert(comm_a1_2.unackedMessages.map(Commitments.msg2String(_)).mkString(" ") === "")

    // SCENARIO A2: B did receive sig2
    // => A expects rev2 and will receive it
    val comm_a2_1 = comm_a
    // a will first receive sig2
    val comm_a2_2 = Commitments.receiveRevocation(comm_a2_1, ba_rev_2).right.get
    assert(comm_a2_2.unackedMessages.map(Commitments.msg2String(_)).mkString(" ") === "")

    // SCENARIO B1: B did receive sig2
    // => B will receive sig2 again
    val comm_b2_1 = comm_b
    // B ignores add-1
    assert(Commitments.isOldAdd(comm_b2_1, ab_add_1) === true)
    // B ignores sig2
    assert(Commitments.isOldCommit(comm_b2_1, ab_sig_2) === true)

  }*/

  /*test("both parties rev-sig") { case (alice, bob, alice2bob, bob2alice, _, _, _) =>
    val sender = TestProbe()

    sender.send(alice, CMD_ADD_HTLC(1000000, BinaryData("42" * 32), 400144))
    val ab_add_0 = alice2bob.expectMsgType[UpdateAddHtlc]
    sender.send(bob, CMD_ADD_HTLC(1000000, BinaryData("42" * 32), 400144))
    val ba_add_0 = bob2alice.expectMsgType[UpdateAddHtlc]

    alice2bob.forward(bob, ab_add_0)
    bob2alice.forward(alice, ba_add_0)

    sender.send(alice, CMD_SIGN)
    val ab_sig_1 = alice2bob.expectMsgType[CommitSig]

    sender.send(bob, CMD_SIGN)
    val ba_sig_1 = bob2alice.expectMsgType[CommitSig]

    alice2bob.forward(bob, ab_sig_1)
    bob2alice.forward(alice, ba_sig_1)

    val ba_rev_1 = bob2alice.expectMsgType[RevokeAndAck]
    val ab_rev_1 = alice2bob.expectMsgType[RevokeAndAck]

    bob2alice.forward(alice, ba_rev_1)
    alice2bob.forward(bob, ab_rev_1)

    val ba_sig_2 = bob2alice.expectMsgType[CommitSig]
    val ab_sig_2 = alice2bob.expectMsgType[CommitSig]

    val comm_a = alice.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(comm_a.unackedMessages.map(Commitments.msg2String(_)).mkString(" ") === "rev sig")
    val comm_b = bob.stateData.asInstanceOf[DATA_NORMAL].commitments
    assert(comm_b.unackedMessages.map(Commitments.msg2String(_)).mkString(" ") === "rev sig")

    // on reconnection A will receive rev and sig
    assert(Commitments.isOldRevocation(comm_a, ba_rev_1) === true)
    Commitments.receiveCommit(comm_a, ba_sig_2)

  }*/

}
