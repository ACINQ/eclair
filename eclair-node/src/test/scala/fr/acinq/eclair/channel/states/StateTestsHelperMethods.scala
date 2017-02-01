package fr.acinq.eclair.channel.states

import akka.actor.ActorRef
import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.TestConstants
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire._

import scala.util.Random

/**
  * Created by PM on 23/08/2016.
  */
trait StateTestsHelperMethods extends TestKitBase {

  def reachNormal(alice: TestFSMRef[State, Data, Channel],
                  bob: TestFSMRef[State, Data, Channel],
                  alice2bob: TestProbe,
                  bob2alice: TestProbe,
                  blockchainA: ActorRef,
                  alice2blockchain: TestProbe,
                  bob2blockchain: TestProbe): Unit = {
    alice ! INPUT_INIT_FUNDER(TestConstants.fundingSatoshis, TestConstants.pushMsat)
    bob ! INPUT_INIT_FUNDEE()
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
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.forward(blockchainA)
    bob2blockchain.expectMsgType[WatchSpent]
    bob2blockchain.expectMsgType[WatchConfirmed]
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 42000, 42)
    alice2blockchain.expectMsgType[WatchLost]
    bob2blockchain.expectMsgType[WatchLost]
    alice2bob.expectMsgType[FundingLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  def addHtlc(amountMsat: Int, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): (BinaryData, UpdateAddHtlc) = {
    val rand = new Random()
    val R: BinaryData = Array.fill[Byte](32)(0)
    rand.nextBytes(R)
    val H: BinaryData = Crypto.sha256(R)
    val sender = TestProbe()
    sender.send(s, CMD_ADD_HTLC(amountMsat, H, 1440))
    sender.expectMsg("ok")
    val htlc = s2r.expectMsgType[UpdateAddHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(htlc))
    (R, htlc)
  }

  def fulfillHtlc(id: Long, R: BinaryData, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe) = {
    val sender = TestProbe()
    sender.send(s, CMD_FULFILL_HTLC(id, R))
    sender.expectMsg("ok")
    val fulfill = s2r.expectMsgType[UpdateFulfillHtlc]
    s2r.forward(r)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteChanges.proposed.contains(fulfill))
  }

  def sign(s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe) = {
    val sender = TestProbe()
    val rCommitIndex = r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index
    sender.send(s, CMD_SIGN)
    sender.expectMsg("ok")
    s2r.expectMsgType[CommitSig]
    s2r.forward(r)
    r2s.expectMsgType[RevokeAndAck]
    r2s.forward(s)
    awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == rCommitIndex + 1)
    awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == rCommitIndex + 1)
  }

}
