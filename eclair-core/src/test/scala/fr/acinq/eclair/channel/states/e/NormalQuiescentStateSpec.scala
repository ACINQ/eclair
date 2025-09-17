/*
 * Copyright 2023 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.channel.states.e

import akka.actor.ActorRef
import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{SatoshiLong, Script}
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.PimpTestFSM
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.relay.Relayer.RelayForward
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.Transactions.{UnsignedHtlcSuccessTx, UnsignedHtlcTimeoutTx}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{channel, _}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import scodec.bits.HexStringSyntax

class NormalQuiescentStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  implicit val log: akka.event.LoggingAdapter = akka.event.NoLogging

  override def withFixture(test: OneArgTest): Outcome = {
    val tags = test.tags + ChannelStateTestsTags.DualFunding
    val setup = init(tags = tags)
    import setup._
    reachNormal(setup, tags)
    alice2bob.ignoreMsg { case _: ChannelUpdate => true }
    bob2alice.ignoreMsg { case _: ChannelUpdate => true }
    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
    withFixture(test.toNoArgTest(setup))
  }

  private def disconnect(f: FixtureParam): Unit = {
    import f._
    alice ! INPUT_DISCONNECTED
    bob ! INPUT_DISCONNECTED
    awaitCond(alice.stateName == OFFLINE)
    awaitCond(bob.stateName == OFFLINE)
  }

  private def reconnect(f: FixtureParam): Unit = {
    import f._

    val aliceInit = Init(alice.commitments.localChannelParams.initFeatures)
    val bobInit = Init(bob.commitments.localChannelParams.initFeatures)
    alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    alice2bob.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)

    awaitCond(alice.stateName == NORMAL)
    awaitCond(bob.stateName == NORMAL)
  }

  private def safeSend(r: TestFSMRef[ChannelState, ChannelData, Channel], cmds: Seq[channel.Command]): Unit = {
    cmds.foreach {
      case cmd: channel.HtlcSettlementCommand =>
        // this would be done automatically when the relayer calls safeSend
        r.underlyingActor.nodeParams.db.pendingCommands.addSettlementCommand(r.stateData.channelId, cmd)
        r ! cmd
      case cmd: channel.Command => r ! cmd
    }
  }

  private def initiateQuiescence(f: FixtureParam, sendInitialStfu: Boolean): TestProbe = {
    import f._

    val sender = TestProbe()
    val scriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat)), spliceOut_opt = Some(SpliceOut(100_000 sat, scriptPubKey)), requestFunding_opt = None, channelType_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[Stfu]
    if (!sendInitialStfu) {
      // only alice is quiescent, we're holding the first stfu to pause the splice
    } else {
      alice2bob.forward(bob)
      bob2alice.expectMsgType[Stfu]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[SpliceInit]
      // both alice and bob are quiescent, we're holding the splice-init to pause the splice
    }

    sender
  }

  test("send stfu after pending local changes have been added") { f =>
    import f._
    // we have an unsigned htlc in our local changes
    addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    alice ! CMD_SPLICE(TestProbe().ref, spliceIn_opt = Some(SpliceIn(50_000 sat)), spliceOut_opt = None, requestFunding_opt = None, channelType_opt = None)
    alice2bob.expectNoMessage(100 millis)
    crossSign(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[Stfu]
    assert(alice.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.isQuiescent)
  }

  test("recv stfu when there are pending local changes") { f =>
    import f._
    initiateQuiescence(f, sendInitialStfu = false)
    // we're holding the stfu from alice so that bob can add a pending local change
    addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    // bob will not reply to alice's stfu until bob has no pending local commitment changes
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
    crossSign(bob, alice, bob2alice, alice2bob)
    bob2alice.expectMsgType[Stfu]
    assert(bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.isQuiescent)
    bob2alice.forward(alice)
    // when both nodes are quiescent, alice will start the splice
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
  }

  test("recv forbidden non-settlement commands while initiator awaiting stfu from remote") { f =>
    import f._
    // initiator should reject commands that change the commitment once it became quiescent
    val sender1, sender2, sender3 = TestProbe()
    val cmds = Seq(
      CMD_ADD_HTLC(sender1.ref, 1_000_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, Reputation.Score.max, None, localOrigin(sender1.ref)),
      CMD_UPDATE_FEE(FeeratePerKw(100 sat), replyTo_opt = Some(sender2.ref)),
      CMD_CLOSE(sender3.ref, None, None)
    )
    initiateQuiescence(f, sendInitialStfu = false)
    safeSend(alice, cmds)
    sender1.expectMsgType[RES_ADD_FAILED[ForbiddenDuringSplice]]
    sender2.expectMsgType[RES_FAILURE[CMD_UPDATE_FEE, ForbiddenDuringSplice]]
    sender3.expectMsgType[RES_FAILURE[CMD_CLOSE, ForbiddenDuringSplice]]
  }

  test("recv forbidden non-settlement commands while quiescent") { f =>
    import f._
    // both should reject commands that change the commitment while quiescent
    val sender1, sender2, sender3 = TestProbe()
    val cmds = Seq(
      CMD_ADD_HTLC(sender1.ref, 1_000_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, Reputation.Score.max, None, localOrigin(sender1.ref)),
      CMD_UPDATE_FEE(FeeratePerKw(100 sat), replyTo_opt = Some(sender2.ref)),
      CMD_CLOSE(sender3.ref, None, None)
    )
    initiateQuiescence(f, sendInitialStfu = true)
    safeSend(alice, cmds)
    sender1.expectMsgType[RES_ADD_FAILED[ForbiddenDuringSplice]]
    sender2.expectMsgType[RES_FAILURE[CMD_UPDATE_FEE, ForbiddenDuringSplice]]
    sender3.expectMsgType[RES_FAILURE[CMD_CLOSE, ForbiddenDuringSplice]]
    safeSend(bob, cmds)
    sender1.expectMsgType[RES_ADD_FAILED[ForbiddenDuringSplice]]
    sender2.expectMsgType[RES_FAILURE[CMD_UPDATE_FEE, ForbiddenDuringSplice]]
    sender3.expectMsgType[RES_FAILURE[CMD_CLOSE, ForbiddenDuringSplice]]
  }

  // @formatter:off
  sealed trait SettlementCommandEnum
  case object FulfillHtlc extends SettlementCommandEnum
  case object FailHtlc extends SettlementCommandEnum
  // @formatter:on

  private def receiveSettlementCommand(f: FixtureParam, c: SettlementCommandEnum, sendInitialStfu: Boolean, resetConnection: Boolean = false): Unit = {
    import f._

    val (preimage, add) = addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    val cmd = c match {
      case FulfillHtlc => CMD_FULFILL_HTLC(add.id, preimage, None)
      case FailHtlc => CMD_FAIL_HTLC(add.id, FailureReason.EncryptedDownstreamFailure(randomBytes(252), None), None)
    }
    crossSign(bob, alice, bob2alice, alice2bob)
    val sender = initiateQuiescence(f, sendInitialStfu)
    safeSend(alice, Seq(cmd))
    // alice will not forward settlement msg to bob while quiescent
    alice2bob.expectNoMessage(100 millis)
    if (resetConnection) {
      // both alice and bob leave quiescence when the channel is disconnected
      disconnect(f)
    } else if (sendInitialStfu) {
      // alice sends splice-init to bob
      alice2bob.forward(bob)
      bob2alice.expectMsgType[SpliceAck]
      // both alice and bob leave quiescence when the splice aborts
      bob2alice.forward(alice, TxAbort(channelId(bob), hex"deadbeef"))
      alice2bob.expectMsgType[TxAbort]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[TxAbort]
    } else {
      // alice sends a warning and disconnects if bob doesn't send stfu before the timeout
      alice ! Channel.QuiescenceTimeout(alicePeer.ref)
      alice2bob.expectMsgType[Warning]
      alice2bob.forward(bob)
      // we should disconnect after giving bob time to receive the warning
      alicePeer.fishForMessage(3 seconds) {
        case Peer.Disconnect(nodeId, _) if nodeId == alice.underlyingActor.remoteNodeId => true
        case _ => false
      }
      disconnect(f)
    }
    if (resetConnection || !sendInitialStfu) {
      // any failure during quiescence will cause alice to disconnect
      sender.expectMsgType[RES_FAILURE[_, _]]
      assert(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NoSplice)
      reconnect(f)
    }
    // alice sends pending settlement after quiescence ends
    c match {
      case FulfillHtlc => alice2bob.expectMsgType[UpdateFulfillHtlc]
      case FailHtlc => alice2bob.expectMsgType[UpdateFailHtlc]
    }
  }

  test("recv CMD_FULFILL_HTLC while initiator awaiting stfu from remote") { f =>
    receiveSettlementCommand(f, FulfillHtlc, sendInitialStfu = false)
  }

  test("recv CMD_FAIL_HTLC while initiator awaiting stfu from remote") { f =>
    receiveSettlementCommand(f, FailHtlc, sendInitialStfu = false)
  }

  test("recv CMD_FULFILL_HTLC while initiator awaiting stfu from remote and channel disconnects") { f =>
    receiveSettlementCommand(f, FulfillHtlc, sendInitialStfu = false, resetConnection = true)
  }

  test("recv CMD_FAIL_HTLC while initiator awaiting stfu from remote and channel disconnects") { f =>
    receiveSettlementCommand(f, FailHtlc, sendInitialStfu = false, resetConnection = true)
  }

  test("recv CMD_FULFILL_HTLC while quiescent") { f =>
    receiveSettlementCommand(f, FulfillHtlc, sendInitialStfu = true)
  }

  test("recv CMD_FAIL_HTLC while quiescent") { f =>
    receiveSettlementCommand(f, FailHtlc, sendInitialStfu = true)
  }

  test("recv CMD_FULFILL_HTLC while quiescent and channel disconnects") { f =>
    receiveSettlementCommand(f, FulfillHtlc, sendInitialStfu = true, resetConnection = true)
  }

  test("recv CMD_FAIL_HTLC while quiescent and channel disconnects") { f =>
    receiveSettlementCommand(f, FailHtlc, sendInitialStfu = true, resetConnection = true)
  }

  test("recv second stfu while non-initiator waiting for local commitment to be signed") { f =>
    import f._
    initiateQuiescence(f, sendInitialStfu = false)
    val (_, _) = addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    alice2bob.forward(bob)
    // second stfu to bob is ignored
    bob ! Stfu(channelId(bob), initiator = true)
    bob2alice.expectNoMessage(100 millis)
  }

  test("recv Shutdown message before initiator receives stfu from remote") { f =>
    import f._
    // Alice initiates quiescence.
    initiateQuiescence(f, sendInitialStfu = false)
    val stfuAlice = Stfu(channelId(alice), initiator = true)
    // But Bob is concurrently initiating a mutual close, which should "win".
    bob ! CMD_CLOSE(ActorRef.noSender, None, None)
    val shutdownBob = bob2alice.expectMsgType[Shutdown]
    bob ! stfuAlice
    bob2alice.expectNoMessage(100 millis)
    alice ! shutdownBob
    val shutdownAlice = alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NEGOTIATING)
    alice2bob.expectMsgType[ClosingSigned]
    bob ! shutdownAlice
    awaitCond(bob.stateName == NEGOTIATING)
  }

  test("recv (forbidden) Shutdown message while quiescent") { f =>
    import f._
    initiateQuiescence(f, sendInitialStfu = true)
    val bobData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val forbiddenMsg = Shutdown(channelId(bob), bob.underlyingActor.getOrGenerateFinalScriptPubKey(bobData))
    // both parties will respond to a forbidden msg while quiescent with a warning (and disconnect)
    bob2alice.forward(alice, forbiddenMsg)
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "Shutdown").getMessage))
    alice2bob.forward(bob, forbiddenMsg)
    bob2alice.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "Shutdown").getMessage))
  }

  test("recv (forbidden) UpdateFulfillHtlc message while quiescent") { f =>
    import f._
    val (preimage, add) = addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    alice2relayer.expectMsg(RelayForward(add, TestConstants.Bob.nodeParams.nodeId, 0.1))
    initiateQuiescence(f, sendInitialStfu = true)
    val forbiddenMsg = UpdateFulfillHtlc(channelId(bob), add.id, preimage)
    // both parties will respond to a forbidden msg while quiescent with a warning (and disconnect)
    bob2alice.forward(alice, forbiddenMsg)
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFulfillHtlc").getMessage))
    alice2bob.forward(bob, forbiddenMsg)
    bob2alice.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFulfillHtlc").getMessage))
    assert(bob2relayer.expectMsgType[RES_ADD_SETTLED[_, HtlcResult.RemoteFulfill]].result.paymentPreimage == preimage)
  }

  test("recv (forbidden) UpdateFailHtlc message while quiescent") { f =>
    import f._
    val (_, add) = addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    initiateQuiescence(f, sendInitialStfu = true)
    val forbiddenMsg = UpdateFailHtlc(channelId(bob), add.id, randomBytes32())
    // both parties will respond to a forbidden msg while quiescent with a warning (and disconnect)
    bob2alice.forward(alice, forbiddenMsg)
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFailHtlc").getMessage))
    alice2bob.forward(bob, forbiddenMsg)
    bob2alice.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFailHtlc").getMessage))
  }

  test("recv (forbidden) UpdateFee message while quiescent") { f =>
    import f._
    val (_, _) = addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    initiateQuiescence(f, sendInitialStfu = true)
    val forbiddenMsg = UpdateFee(channelId(bob), FeeratePerKw(500 sat))
    // both parties will respond to a forbidden msg while quiescent with a warning (and disconnect)
    bob2alice.forward(alice, forbiddenMsg)
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFee").getMessage))
    alice2bob.forward(bob, forbiddenMsg)
    bob2alice.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFee").getMessage))
  }

  test("recv (forbidden) UpdateAddHtlc message while quiescent") { f =>
    import f._
    initiateQuiescence(f, sendInitialStfu = true)
    // have to build a htlc manually because eclair would refuse to accept this command as it's forbidden
    val forbiddenMsg = UpdateAddHtlc(channelId = randomBytes32(), id = 5656, amountMsat = 50000000 msat, cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), paymentHash = randomBytes32(), onionRoutingPacket = TestConstants.emptyOnionPacket, pathKey_opt = None, endorsement = Reputation.maxEndorsement, fundingFee_opt = None)
    // both parties will respond to a forbidden msg while quiescent with a warning (and disconnect)
    bob2alice.forward(alice, forbiddenMsg)
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateAddHtlc").getMessage))
    alice2bob.forward(bob, forbiddenMsg)
    bob2alice.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateAddHtlc").getMessage))
  }

  test("recv stfu from splice initiator that is not quiescent") { f =>
    import f._
    addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    alice2bob.forward(bob, Stfu(channelId(alice), initiator = true))
    bob2alice.expectMsg(Warning(channelId(bob), InvalidSpliceNotQuiescent(channelId(bob)).getMessage))
    // we should disconnect after giving alice time to receive the warning
    bobPeer.fishForMessage(3 seconds) {
      case Peer.Disconnect(nodeId, _) if nodeId == bob.underlyingActor.remoteNodeId => true
      case _ => false
    }
  }

  test("recv stfu from splice non-initiator that is not quiescent") { f =>
    import f._
    addHtlc(50_000_000 msat, bob, alice, bob2alice, alice2bob)
    initiateQuiescence(f, sendInitialStfu = false)
    alice2bob.forward(bob)
    bob2alice.forward(alice, Stfu(channelId(bob), initiator = false))
    alice2bob.expectMsg(Warning(channelId(alice), InvalidSpliceNotQuiescent(channelId(alice)).getMessage))
    // we should disconnect after giving bob time to receive the warning
    alicePeer.fishForMessage(3 seconds) {
      case Peer.Disconnect(nodeId, _) if nodeId == alice.underlyingActor.remoteNodeId => true
      case _ => false
    }
  }

  test("initiate quiescence concurrently (no pending changes)") { f =>
    import f._

    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None, requestFunding_opt = None, channelType_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[Stfu]
    bob ! cmd
    bob2alice.expectMsgType[Stfu]
    bob2alice.forward(alice)
    alice2bob.forward(bob)
    alice2bob.expectMsgType[SpliceInit]
    eventually(assert(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NonInitiatorQuiescent))
    sender.expectMsgType[RES_FAILURE[CMD_SPLICE, ConcurrentRemoteSplice]]
  }

  test("initiate quiescence concurrently (pending changes on one side)") { f =>
    import f._

    addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None, requestFunding_opt = None, channelType_opt = None)
    alice ! cmd
    alice2bob.expectNoMessage(100 millis) // alice isn't quiescent yet
    bob ! cmd
    bob2alice.expectMsgType[Stfu]
    bob2alice.forward(alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    alice2bob.expectMsgType[Stfu]
    alice2bob.forward(bob)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NonInitiatorQuiescent)
    sender.expectMsgType[RES_FAILURE[CMD_SPLICE, ConcurrentRemoteSplice]]
    bob2alice.expectMsgType[SpliceInit]
  }

  test("initiate quiescence concurrently (pending changes on non-initiator side)") { f =>
    import f._

    addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)
    val sender = TestProbe()
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = None, requestFunding_opt = None, channelType_opt = None)
    alice ! cmd
    alice2bob.expectMsgType[Stfu]
    bob ! cmd
    bob2alice.expectNoMessage(100 millis) // bob isn't quiescent yet
    alice2bob.forward(bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    bob2alice.expectMsgType[Stfu]
    bob2alice.forward(alice)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NonInitiatorQuiescent)
    sender.expectMsgType[RES_FAILURE[CMD_SPLICE, ConcurrentRemoteSplice]]
    alice2bob.expectMsgType[SpliceInit]
  }

  test("outgoing htlc timeout during quiescence negotiation") { f =>
    import f._
    val (_, add) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    initiateQuiescence(f, sendInitialStfu = true)

    val commitTx = alice.signCommitTx()
    val htlcTxs = alice.htlcTxs()
    assert(htlcTxs.size == 1)
    val htlcTimeoutTx = htlcTxs.head
    assert(htlcTimeoutTx.isInstanceOf[UnsignedHtlcTimeoutTx])

    // the HTLC times out, alice needs to close the channel
    alice ! CurrentBlockHeight(add.cltvExpiry.blockHeight)
    alice2blockchain.expectFinalTxPublished(commitTx.txid)
    val mainDelayedTx = alice2blockchain.expectFinalTxPublished("local-main-delayed")
    assert(alice2blockchain.expectFinalTxPublished("htlc-timeout").input == htlcTimeoutTx.input.outPoint)
    alice2blockchain.expectWatchTxConfirmed(commitTx.txid)
    alice2blockchain.expectWatchOutputSpent(mainDelayedTx.input)
    alice2blockchain.expectWatchOutputSpent(htlcTimeoutTx.input.outPoint)
    alice2blockchain.expectNoMessage(100 millis)

    channelUpdateListener.expectMsgType[LocalChannelDown]
  }

  test("incoming htlc timeout during quiescence negotiation") { f =>
    import f._
    val (preimage, add) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    initiateQuiescence(f, sendInitialStfu = true)

    val commitTx = bob.signCommitTx()
    val htlcTxs = bob.htlcTxs()
    assert(htlcTxs.size == 1)
    val htlcSuccessTx = htlcTxs.head
    assert(htlcSuccessTx.isInstanceOf[UnsignedHtlcSuccessTx])

    // bob does not force-close unless there is a pending preimage for the incoming htlc
    bob ! CurrentBlockHeight(add.cltvExpiry.blockHeight - Bob.nodeParams.channelConf.fulfillSafetyBeforeTimeout.toInt)
    bob2blockchain.expectNoMessage(100 millis)

    // bob receives the fulfill for htlc, which is ignored because the channel is quiescent
    val fulfillHtlc = CMD_FULFILL_HTLC(add.id, preimage, None)
    safeSend(bob, Seq(fulfillHtlc))

    // the HTLC timeout from alice is near, bob needs to close the channel to avoid an on-chain race condition
    bob ! CurrentBlockHeight(add.cltvExpiry.blockHeight - Bob.nodeParams.channelConf.fulfillSafetyBeforeTimeout.toInt)
    // bob publishes a set of force-close transactions, including the HTLC-success using the received preimage
    bob2blockchain.expectFinalTxPublished(commitTx.txid)
    val mainDelayedTx = bob2blockchain.expectFinalTxPublished("local-main-delayed")
    bob2blockchain.expectWatchTxConfirmed(commitTx.txid)
    bob2blockchain.expectWatchOutputSpent(mainDelayedTx.input)
    bob2blockchain.expectWatchOutputSpent(htlcSuccessTx.input.outPoint)
    assert(bob2blockchain.expectFinalTxPublished("htlc-success").input == htlcSuccessTx.input.outPoint)
    bob2blockchain.expectNoMessage(100 millis)

    channelUpdateListener.expectMsgType[LocalChannelDown]
  }

  test("receive quiescence timeout while splice in progress") { f =>
    import f._
    initiateQuiescence(f, sendInitialStfu = true)

    // alice sends splice-init to bob, bob responds with splice-ack
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus.isInstanceOf[SpliceStatus.SpliceInProgress])

    // bob sends a warning and disconnects if the splice takes too long to complete
    bob ! Channel.QuiescenceTimeout(bobPeer.ref)
    bob2alice.expectMsg(Warning(channelId(bob), SpliceAttemptTimedOut(channelId(bob)).getMessage))
  }

  test("receive quiescence timeout while splice is waiting for tx_abort from peer") { f =>
    import f._
    initiateQuiescence(f, sendInitialStfu = true)

    // bob sends tx-abort to alice but does not receive a tx-abort back from alice
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus.isInstanceOf[SpliceStatus.SpliceInProgress])
    bob ! InteractiveTxBuilder.LocalFailure(new ChannelException(channelId(bob), "abort"))
    bob2alice.expectMsgType[TxAbort]
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.SpliceAborted)

    // bob sends a warning and disconnects if the splice takes too long to complete
    bob ! Channel.QuiescenceTimeout(bobPeer.ref)
    bob2alice.expectMsg(Warning(channelId(bob), SpliceAttemptTimedOut(channelId(bob)).getMessage))
  }

  test("receive SpliceInit when channel is not quiescent") { f =>
    import f._
    val spliceInit = SpliceInit(channelId(alice), 500_000.sat, FeeratePerKw(253.sat), 0, randomKey().publicKey)
    alice ! spliceInit
    // quiescence not negotiated
    alice2bob.expectMsgType[TxAbort]
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.SpliceAborted)
  }

}
