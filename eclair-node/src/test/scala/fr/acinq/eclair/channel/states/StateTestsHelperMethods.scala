package fr.acinq.eclair.channel.states

import akka.actor.{ActorRef, Props}
import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestBitcoinClient, TestConstants}

import scala.util.Random

/**
  * Created by PM on 23/08/2016.
  */
trait StateTestsHelperMethods extends TestKitBase {

  case class Setup(alice: TestFSMRef[State, Data, Channel],
                   bob: TestFSMRef[State, Data, Channel],
                   alice2bob: TestProbe,
                   bob2alice: TestProbe,
                   blockchainA: ActorRef,
                   alice2blockchain: TestProbe,
                   bob2blockchain: TestProbe,
                   router: TestProbe,
                   relayer: TestProbe)

  def init(): Setup = {
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val blockchainA = system.actorOf(Props(new PeerWatcher(new TestBitcoinClient())))
    val bob2blockchain = TestProbe()
    val relayer = TestProbe()
    val router = TestProbe()
    val nodeParamsA = TestConstants.Alice.nodeParams
    val nodeParamsB = TestConstants.Bob.nodeParams
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(nodeParamsA, Bob.id, alice2blockchain.ref, router.ref, relayer.ref))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(nodeParamsB, Alice.id, bob2blockchain.ref, router.ref, relayer.ref))
    Setup(alice, bob, alice2bob, bob2alice, blockchainA, alice2blockchain, bob2blockchain, router, relayer)
  }

  def reachNormal(alice: TestFSMRef[State, Data, Channel],
                  bob: TestFSMRef[State, Data, Channel],
                  alice2bob: TestProbe,
                  bob2alice: TestProbe,
                  blockchainA: ActorRef,
                  alice2blockchain: TestProbe,
                  bob2blockchain: TestProbe): Unit = {
    val aliceInit = Init(Alice.channelParams.globalFeatures, Alice.channelParams.localFeatures)
    val bobInit = Init(Bob.channelParams.globalFeatures, Bob.channelParams.localFeatures)
    alice ! INPUT_INIT_FUNDER(0, TestConstants.fundingSatoshis, TestConstants.pushMsat, Alice.channelParams, alice2bob.ref, bobInit)
    bob ! INPUT_INIT_FUNDEE(0, Bob.channelParams, bob2alice.ref, aliceInit)
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
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
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
    sender.send(s, CMD_ADD_HTLC(amountMsat, H, 400144))
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

  def crossSign(s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe) = {
    val sender = TestProbe()
    val sCommitIndex = s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index
    val rCommitIndex = r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index
    val rHasChanges = Commitments.localHasChanges(r.stateData.asInstanceOf[HasCommitments].commitments)
    sender.send(s, CMD_SIGN)
    sender.expectMsg("ok")
    s2r.expectMsgType[CommitSig]
    s2r.forward(r)
    r2s.expectMsgType[RevokeAndAck]
    r2s.forward(s)
    r2s.expectMsgType[CommitSig]
    r2s.forward(s)
    s2r.expectMsgType[RevokeAndAck]
    s2r.forward(r)
    if (rHasChanges) {
      s2r.expectMsgType[CommitSig]
      s2r.forward(r)
      r2s.expectMsgType[RevokeAndAck]
      r2s.forward(s)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == sCommitIndex + 1)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == sCommitIndex + 2)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == rCommitIndex + 2)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == rCommitIndex + 1)
    } else {
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == sCommitIndex + 1)
      awaitCond(s.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == sCommitIndex + 1)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.localCommit.index == rCommitIndex + 1)
      awaitCond(r.stateData.asInstanceOf[HasCommitments].commitments.remoteCommit.index == rCommitIndex + 1)
    }

  }

}
