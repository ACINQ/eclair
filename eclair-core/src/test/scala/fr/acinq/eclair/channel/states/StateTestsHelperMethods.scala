package fr.acinq.eclair.channel.states

import akka.testkit.{TestFSMRef, TestKitBase, TestProbe}
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentLifecycle
import fr.acinq.eclair.router.Hop
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, NodeParams, TestConstants}

import scala.util.Random

/**
  * Created by PM on 23/08/2016.
  */
trait StateTestsHelperMethods extends TestKitBase {

  def defaultOnion: BinaryData = "00" * Sphinx.PacketLength

  case class Setup(alice: TestFSMRef[State, Data, Channel],
                   bob: TestFSMRef[State, Data, Channel],
                   alice2bob: TestProbe,
                   bob2alice: TestProbe,
                   alice2blockchain: TestProbe,
                   bob2blockchain: TestProbe,
                   router: TestProbe,
                   relayer: TestProbe)

  def init(nodeParamsA: NodeParams = TestConstants.Alice.nodeParams, nodeParamsB: NodeParams = TestConstants.Bob.nodeParams): Setup = {
    Globals.feeratesPerKw.set(FeeratesPerKw.single(TestConstants.feeratePerKw))
    val alice2bob = TestProbe()
    val bob2alice = TestProbe()
    val alice2blockchain = TestProbe()
    val bob2blockchain = TestProbe()
    val relayer = TestProbe()
    system.eventStream.subscribe(relayer.ref, classOf[LocalChannelUpdate])
    system.eventStream.subscribe(relayer.ref, classOf[LocalChannelDown])
    // hacky... publish a fake update and wait till we've received it
//    val fakeUpdate = LocalChannelUpdate(probe.ref, BinaryData("00" * 32), 0, PrivateKey("01" * 32).publicKey, None, ChannelUpdate(LightningMessageCodecsSpec.randomSignature, Block.RegtestGenesisBlock.hash, 1, 2, BinaryData("0202"), 3, 4, 5, 6))
//    system.eventStream.publish(fakeUpdate)
//    probe.expectMsg(fakeUpdate)
    val router = TestProbe()
    val wallet = new TestWallet
    val alice: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(nodeParamsA, wallet, Bob.nodeParams.nodeId, alice2blockchain.ref, router.ref, relayer.ref))
    val bob: TestFSMRef[State, Data, Channel] = TestFSMRef(new Channel(nodeParamsB, wallet, Alice.nodeParams.nodeId, bob2blockchain.ref, router.ref, relayer.ref))
    Setup(alice, bob, alice2bob, bob2alice, alice2blockchain, bob2blockchain, router, relayer)
  }

  def reachNormal(alice: TestFSMRef[State, Data, Channel],
                  bob: TestFSMRef[State, Data, Channel],
                  alice2bob: TestProbe,
                  bob2alice: TestProbe,
                  alice2blockchain: TestProbe,
                  bob2blockchain: TestProbe,
                  relayer: TestProbe,
                  tags: Set[String] = Set.empty): Unit = {
    val channelFlags = if (tags.contains("channels_public")) ChannelFlags.AnnounceChannel else ChannelFlags.Empty
    val (aliceParams, bobParams) = (Alice.channelParams, Bob.channelParams)
    val aliceInit = Init(aliceParams.globalFeatures, aliceParams.localFeatures)
    val bobInit = Init(bobParams.globalFeatures, bobParams.localFeatures)
    // no announcements
    alice ! INPUT_INIT_FUNDER("00" * 32, TestConstants.fundingSatoshis, TestConstants.pushMsat, TestConstants.feeratePerKw, TestConstants.feeratePerKw, aliceParams, alice2bob.ref, bobInit, channelFlags)
    bob ! INPUT_INIT_FUNDEE("00" * 32, bobParams, bob2alice.ref, aliceInit)
    alice2bob.expectMsgType[OpenChannel]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[AcceptChannel]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[FundingCreated]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[FundingSigned]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchSpent]
    alice2blockchain.expectMsgType[WatchConfirmed]
    bob2blockchain.expectMsgType[WatchSpent]
    bob2blockchain.expectMsgType[WatchConfirmed]
    alice ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
    bob ! WatchEventConfirmed(BITCOIN_FUNDING_DEPTHOK, 400000, 42)
    alice2blockchain.expectMsgType[WatchLost]
    bob2blockchain.expectMsgType[WatchLost]
    alice2bob.expectMsgType[FundingLocked]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[FundingLocked]
    bob2alice.forward(alice)
    alice2blockchain.expectMsgType[WatchConfirmed] // deeply buried
    bob2blockchain.expectMsgType[WatchConfirmed] // deeply buried
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    // x2 because alice and bob share the same relayer
    relayer.expectMsgType[LocalChannelUpdate]
    relayer.expectMsgType[LocalChannelUpdate]
  }

  def addHtlc(amountMsat: Int, s: TestFSMRef[State, Data, Channel], r: TestFSMRef[State, Data, Channel], s2r: TestProbe, r2s: TestProbe): (BinaryData, UpdateAddHtlc) = {
    val R: BinaryData = Array.fill[Byte](32)(0)
    Random.nextBytes(R)
    val H: BinaryData = Crypto.sha256(R)
    val sender = TestProbe()
    val receiverPubkey = r.underlyingActor.nodeParams.nodeId
    val expiry = 400144
    val cmd = PaymentLifecycle.buildCommand(amountMsat, expiry, H, Hop(null, receiverPubkey, null) :: Nil)._1.copy(commit = false)
    sender.send(s, cmd)
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

  def channelId(a: TestFSMRef[State, Data, Channel]) = Helpers.getChannelId(a.stateData)

}
