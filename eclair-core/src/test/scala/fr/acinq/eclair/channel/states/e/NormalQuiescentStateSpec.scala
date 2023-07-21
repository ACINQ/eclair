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

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestFSMRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{SatoshiLong, Script, Transaction}
import fr.acinq.eclair.TestConstants.Bob
import fr.acinq.eclair.blockchain.CurrentBlockHeight
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fund.InteractiveTxBuilder
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, PublishTx}
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.relay.Relayer.RelayForward
import fr.acinq.eclair.transactions.Transactions.HtlcSuccessTx
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
    val tags = test.tags + ChannelStateTestsTags.DualFunding + ChannelStateTestsTags.Splicing + ChannelStateTestsTags.Quiescence
    val setup = init(tags = tags)
    import setup._
    reachNormal(setup, tags)
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

    val aliceInit = Init(alice.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.params.localParams.initFeatures)
    val bobInit = Init(bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.params.localParams.initFeatures)
    alice ! INPUT_RECONNECTED(alice2bob.ref, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(bob2alice.ref, bobInit, aliceInit)
    alice2bob.expectMsgType[ChannelReestablish]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[ChannelReestablish]
    bob2alice.forward(alice)

    alice2blockchain.expectMsgType[WatchFundingDeeplyBuried]
    bob2blockchain.expectMsgType[WatchFundingDeeplyBuried]
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

  private def initiateQuiescence(f: FixtureParam, sendInitialStfu: Boolean = true): TestProbe = {
    import f._

    val sender = TestProbe()
    val scriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = Some(SpliceOut(100_000 sat, scriptPubKey)))
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

  private def assertPublished(probe: TestProbe, desc: String): Transaction = {
    val p = probe.expectMsgType[PublishTx]
    assert(desc == p.desc)
    p match {
      case p: PublishFinalTx => p.tx
      case p: PublishReplaceableTx => p.txInfo.tx
    }
  }

  private def sendErrorAndClose(s: TestFSMRef[ChannelState, ChannelData, Channel], s2r: TestProbe, s2blockchain: TestProbe): Unit = {
    s2r.expectMsgType[Error]
    assertPublished(s2blockchain, "commit-tx")
    assertPublished(s2blockchain, "local-main-delayed")
    s2blockchain.expectMsgType[WatchTxConfirmed]
    s2blockchain.expectMsgType[WatchTxConfirmed]
    s2blockchain.expectNoMessage(100 millis)
    awaitCond(s.stateName == CLOSING)
  }

  sealed trait SettlementCommandEnum
  case object FulfillHtlc extends SettlementCommandEnum
  case object FailHtlc extends SettlementCommandEnum
  case object FailMalformedHtlc extends SettlementCommandEnum

  private def receiveSettlementCommand(f: FixtureParam, c: SettlementCommandEnum, sendInitialStfu: Boolean, resetConnection: Boolean = false): Unit = {
    import f._

    val (preimage, add) = addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)
    val cmd = c match {
      case FulfillHtlc => CMD_FULFILL_HTLC(add.id, preimage)
      case FailHtlc => CMD_FAIL_HTLC(add.id, Left(randomBytes32()))
      case FailMalformedHtlc => CMD_FAIL_MALFORMED_HTLC(add.id, randomBytes32(), FailureMessageCodecs.BADONION)
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
      // alice sends a warning and disconnects if an error occurs while waiting for stfu from bob
      alice ! Channel.QuiescenceTimeout(alicePeer.ref)
      alice2bob.expectMsgType[Warning]
      alice2bob.forward(bob)
      // we should disconnect after giving bob time to receive the warning
      alicePeer.fishForMessage(3 seconds) {
        case Peer.Disconnect(nodeId, _) if nodeId == alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.remoteParams.nodeId => true
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
    eventually {
      c match {
        case FulfillHtlc => alice2bob.expectMsgType[UpdateFulfillHtlc]
        case FailHtlc => alice2bob.expectMsgType[UpdateFailHtlc]
        case FailMalformedHtlc => alice2bob.expectMsgType[UpdateFailMalformedHtlc]
      }
    }
    alice2bob.forward(bob)
  }

  test("recv stfu when there are pending local changes") { f =>
    import f._
    initiateQuiescence(f, sendInitialStfu = false)
    // we're holding the stfu from alice so that bob can add a pending local change
    addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)
    // bob will not reply to alice's stfu until bob has no pending local commitment changes
    alice2bob.forward(bob)
    bob2alice.expectNoMessage(100 millis)
    crossSign(bob, alice, bob2alice, alice2bob)
    eventually(assert(bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.isQuiescent))
    bob2alice.expectMsgType[Stfu]
    bob2alice.forward(alice)
    // when both nodes are quiescent, alice will start the splice
    alice2bob.expectMsgType[SpliceInit]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    bob2alice.forward(alice)
  }

  test("recv forbidden commands while quiescent") { f =>
    import f._
    // both should reject commands that change the commitment while quiescent
    val sender1, sender2, sender3 = TestProbe()
    val cmds = Seq(CMD_ADD_HTLC(sender1.ref, 1_000_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender1.ref)),
      CMD_UPDATE_FEE(FeeratePerKw(100 sat), replyTo_opt = Some(sender2.ref)),
      CMD_CLOSE(sender3.ref, None, None))
    initiateQuiescence(f)
    safeSend(alice, cmds)
    sender1.expectMsgType[RES_ADD_FAILED[ForbiddenDuringSplice]]
    sender2.expectMsgType[RES_FAILURE[CMD_UPDATE_FEE, ForbiddenDuringSplice]]
    sender3.expectMsgType[RES_FAILURE[CMD_CLOSE, ForbiddenDuringSplice]]
    safeSend(bob, cmds)
    sender1.expectMsgType[RES_ADD_FAILED[ForbiddenDuringSplice]]
    sender2.expectMsgType[RES_FAILURE[CMD_UPDATE_FEE, ForbiddenDuringSplice]]
    sender3.expectMsgType[RES_FAILURE[CMD_CLOSE, ForbiddenDuringSplice]]
  }

  test("recv fulfill htlc command while initiator awaiting stfu from remote") { f =>
    receiveSettlementCommand(f, FulfillHtlc, sendInitialStfu = false)
  }

  test("recv fail htlc command while initiator awaiting stfu from remote") { f =>
    receiveSettlementCommand(f, FailHtlc, sendInitialStfu = false)
  }

  test("recv fulfill htlc command while initiator awaiting stfu from remote and channel disconnects") { f =>
    receiveSettlementCommand(f, FulfillHtlc, sendInitialStfu = false, resetConnection = true)
  }

  test("recv fail htlc command while initiator awaiting stfu from remote and channel disconnects") { f =>
    receiveSettlementCommand(f, FailHtlc, sendInitialStfu = false, resetConnection = true)
  }

  test("recv fulfill htlc command while quiescent") { f =>
    receiveSettlementCommand(f, FulfillHtlc, sendInitialStfu = true)
  }

  test("recv fail htlc command while quiescent") { f =>
    receiveSettlementCommand(f, FailHtlc, sendInitialStfu = true)
  }

  test("recv fulfill htlc command while quiescent and channel disconnects") { f =>
    receiveSettlementCommand(f, FulfillHtlc, sendInitialStfu = true, resetConnection = true)
  }

  test("recv fail htlc command  while quiescent and channel disconnects") { f =>
    receiveSettlementCommand(f, FailHtlc, sendInitialStfu = true, resetConnection = true)
  }

  test("recv settlement commands while initiator awaiting stfu from remote") { f =>
    import f._

    // initiator should reject commands that change the commitment until the splice is complete
    val sender1, sender2, sender3 = TestProbe()
    val cmds = Seq(CMD_ADD_HTLC(sender1.ref, 1_000_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender1.ref)),
      CMD_UPDATE_FEE(FeeratePerKw(100 sat), replyTo_opt = Some(sender2.ref)),
      CMD_CLOSE(sender3.ref, None, None))
    initiateQuiescence(f, sendInitialStfu = false)
    safeSend(alice, cmds)
    sender1.expectMsgType[RES_ADD_FAILED[ForbiddenDuringSplice]]
    sender2.expectMsgType[RES_FAILURE[CMD_UPDATE_FEE, ForbiddenDuringSplice]]
    sender3.expectMsgType[RES_FAILURE[CMD_CLOSE, ForbiddenDuringSplice]]
  }

  test("recv second stfu while non-initiator waiting for local commitment to be signed") { f =>
    import f._
    initiateQuiescence(f, sendInitialStfu = false)
    val (_, _) = addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)
    alice2bob.forward(bob)
    // second stfu to bob is ignored
    bob ! Stfu(channelId(bob), initiator = true)
    bob2alice.expectNoMessage(100 millis)
  }

  test("recv Shutdown message before initiator receives stfu from remote") { f =>
    import f._
    initiateQuiescence(f, sendInitialStfu = false)
    val bobData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val forbiddenMsg = Shutdown(channelId(bob), bob.underlyingActor.getOrGenerateFinalScriptPubKey(bobData))
    bob2alice.forward(alice, forbiddenMsg)
    // handle Shutdown normally
    alice2bob.expectMsgType[Shutdown]
  }

  test("recv (forbidden) Shutdown message while quiescent") { f =>
    import f._
    initiateQuiescence(f)
    val bobData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val forbiddenMsg = Shutdown(channelId(bob), bob.underlyingActor.getOrGenerateFinalScriptPubKey(bobData))
    // both parties will respond to a forbidden msg while quiescent with a warning (and disconnect)
    bob2alice.forward(alice, forbiddenMsg)
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "Shutdown").getMessage))
    alice2bob.forward(bob, forbiddenMsg)
    bob2alice.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "Shutdown").getMessage))
  }

  test("recv (forbidden) UpdateFulfillHtlc messages while quiescent") { f =>
    import f._
    val (preimage, add) = addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    initiateQuiescence(f)
    val forbiddenMsg = UpdateFulfillHtlc(channelId(bob), add.id, preimage)
    // both parties will respond to a forbidden msg while quiescent with a warning (and disconnect)
    bob2alice.forward(alice, forbiddenMsg)
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFulfillHtlc").getMessage))
    alice2bob.forward(bob, forbiddenMsg)
    bob2alice.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFulfillHtlc").getMessage))
    // alice will forward the valid UpdateFulfilHtlc msg to their relayer
    alice2relayer.expectMsg(RelayForward(add))
  }

  test("recv (forbidden) UpdateFailHtlc messages while quiescent") { f =>
    import f._
    val (_, add) = addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    initiateQuiescence(f)
    val forbiddenMsg = UpdateFailHtlc(channelId(bob), add.id, randomBytes32())
    // both parties will respond to a forbidden msg while quiescent with a warning (and disconnect)
    bob2alice.forward(alice, forbiddenMsg)
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFailHtlc").getMessage))
    alice2bob.forward(bob, forbiddenMsg)
    bob2alice.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFailHtlc").getMessage))
  }

  test("recv (forbidden) UpdateFee messages while quiescent") { f =>
    import f._
    val (_, _) = addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    initiateQuiescence(f)
    val forbiddenMsg = UpdateFee(channelId(bob), FeeratePerKw(1 sat))
    // both parties will respond to a forbidden msg while quiescent with a warning (and disconnect)
    bob2alice.forward(alice, forbiddenMsg)
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFee").getMessage))
    alice2bob.forward(bob, forbiddenMsg)
    bob2alice.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateFee").getMessage))
  }

  test("recv (forbidden) UpdateAddHtlc message while quiescent") { f =>
    import f._
    initiateQuiescence(f)

    // have to build a htlc manually because eclair would refuse to accept this command as it's forbidden
    val forbiddenMsg = UpdateAddHtlc(channelId = randomBytes32(), id = 5656, amountMsat = 50000000 msat, cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), paymentHash = randomBytes32(), onionRoutingPacket = TestConstants.emptyOnionPacket, blinding_opt = None)
    // both parties will respond to a forbidden msg while quiescent with a warning (and disconnect)
    bob2alice.forward(alice, forbiddenMsg)
    alice2bob.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateAddHtlc").getMessage))
    alice2bob.forward(bob, forbiddenMsg)
    bob2alice.expectMsg(Warning(channelId(alice), ForbiddenDuringSplice(channelId(alice), "UpdateAddHtlc").getMessage))
  }

  test("recv stfu from splice initiator that is not quiescent") { f =>
    import f._
    addHtlc(10_000 msat, alice, bob, alice2bob, bob2alice)

    // alice has a pending add htlc
    val sender = TestProbe()
    alice ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    alice2bob.expectMsgType[CommitSig]

    val scriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = Some(SpliceOut(100_000 sat, scriptPubKey)))
    alice ! cmd
    alice2bob.forward(bob, Stfu(channelId(bob), initiator = true))
    bob2alice.expectMsg(Warning(channelId(bob), InvalidSpliceNotQuiescent(channelId(bob)).getMessage))
    // we should disconnect after giving bob time to receive the warning
    bobPeer.fishForMessage(3 seconds) {
      case Peer.Disconnect(nodeId, _) if nodeId == bob.stateData.asInstanceOf[DATA_NORMAL].commitments.params.remoteParams.nodeId => true
      case _ => false
    }
  }

  test("recv stfu from splice non-initiator that is not quiescent") { f =>
    import f._
    addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)

    // bob has a pending add htlc
    val sender = TestProbe()
    bob ! CMD_SIGN(Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]

    val scriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val cmd = CMD_SPLICE(sender.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = Some(SpliceOut(100_000 sat, scriptPubKey)))
    alice ! cmd
    alice2bob.expectMsgType[Stfu]
    alice2bob.forward(bob)
    bob2alice.forward(alice, Stfu(channelId(alice), initiator = false))
    alice2bob.expectMsg(Warning(channelId(alice), InvalidSpliceNotQuiescent(channelId(alice)).getMessage))
    // we should disconnect after giving bob time to receive the warning
    alicePeer.fishForMessage(3 seconds) {
      case Peer.Disconnect(nodeId, _) if nodeId == alice.stateData.asInstanceOf[DATA_NORMAL].commitments.params.remoteParams.nodeId => true
      case _ => false
    }
  }

  test("initiate quiescence concurrently") { f =>
    import f._

    val (sender1, sender2) = (TestProbe(), TestProbe())
    val scriptPubKey = Script.write(Script.pay2wpkh(randomKey().publicKey))
    val cmd1 = CMD_SPLICE(sender1.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = Some(SpliceOut(100_000 sat, scriptPubKey)))
    val cmd2 = CMD_SPLICE(sender2.ref, spliceIn_opt = Some(SpliceIn(500_000 sat, pushAmount = 0 msat)), spliceOut_opt = Some(SpliceOut(100_000 sat, scriptPubKey)))
    alice ! cmd1
    alice2bob.expectMsgType[Stfu]
    bob ! cmd2
    bob2alice.expectMsgType[Stfu]
    bob2alice.forward(alice)
    alice2bob.forward(bob)
    alice2bob.expectMsgType[SpliceInit]
    eventually(assert(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.NonInitiatorQuiescent))
  }

  test("htlc timeout during quiescence negotiation with pending preimage") { f =>
    import f._
    val (preimage, add) = addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    initiateQuiescence(f)

    // bob receives the fulfill for htlc, which is ignored because the channel is quiescent
    val fulfillHtlc = CMD_FULFILL_HTLC(add.id, preimage)
    safeSend(bob, Seq(fulfillHtlc))

    // the HTLC timeout from alice/upstream is near, bob needs to close the channel to avoid an on-chain race condition
    val listener = TestProbe()
    bob.underlying.system.eventStream.subscribe(listener.ref, classOf[ChannelErrorOccurred])
    bob ! CurrentBlockHeight(add.cltvExpiry.blockHeight - Bob.nodeParams.channelConf.fulfillSafetyBeforeTimeout.toInt)
    val ChannelErrorOccurred(_, _, _, LocalError(err), isFatal) = listener.expectMsgType[ChannelErrorOccurred]
    assert(isFatal)
    assert(err.isInstanceOf[HtlcsWillTimeoutUpstream])

    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val initialCommitTx = initialState.commitments.latest.localCommit.commitTxAndRemoteSig.commitTx.tx
    val HtlcSuccessTx(_, htlcSuccessTx, _, _, _) = initialState.commitments.latest.localCommit.htlcTxsAndRemoteSigs.head.htlcTx

    // TODO: why is HtlcSuccessTx not published initially?
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == initialCommitTx.txid)
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    //assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txOut == htlcSuccessTx.txOut)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == initialCommitTx.txid)
    bob2blockchain.expectMsgType[WatchTxConfirmed]
    bob2blockchain.expectMsgType[WatchOutputSpent]

    // TODO: why is HtlcSuccessTx now published during closing?
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == initialCommitTx.txid)
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txOut == htlcSuccessTx.txOut)
    assert(bob2blockchain.expectMsgType[WatchTxConfirmed].txId == initialCommitTx.txid)

    channelUpdateListener.expectMsgType[LocalChannelDown]
    alice2blockchain.expectNoMessage(500 millis)
  }

  test("receive quiescence timeout while splice in progress") { f =>
    import f._
    initiateQuiescence(f)

    // alice sends splice-init to bob, bob responds with splice-ack
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    eventually(assert(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus.isInstanceOf[SpliceStatus.SpliceInProgress]))

    // bob sends a warning and disconnects if the splice takes too long to complete
    bob ! Channel.QuiescenceTimeout(bobPeer.ref)
    bob2alice.expectMsg(Warning(channelId(bob), SpliceAttemptTimedOut(channelId(bob)).getMessage))
  }

  test("receive quiescence timeout while splice is waiting for tx_abort from peer") { f =>
    import f._
    initiateQuiescence(f)

    // bob sends tx-abort to alice but does not receive a tx-abort back from alice
    alice2bob.forward(bob)
    bob2alice.expectMsgType[SpliceAck]
    eventually(assert(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus.isInstanceOf[SpliceStatus.SpliceInProgress]))
    alice2bob.forward(bob, InteractiveTxBuilder.LocalFailure(new ChannelException(channelId(bob), "abort")))
    bob2alice.expectMsgType[TxAbort]
    eventually(assert(bob.stateData.asInstanceOf[DATA_NORMAL].spliceStatus == SpliceStatus.SpliceAborted))

    // bob sends a warning and disconnects if the splice takes too long to complete
    bob ! Channel.QuiescenceTimeout(bobPeer.ref)
    bob2alice.expectMsg(Warning(channelId(bob), SpliceAttemptTimedOut(channelId(bob)).getMessage))
  }

}
