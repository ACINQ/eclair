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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, SatoshiLong, Script, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishReplaceableTx, PublishTx}
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment.relay.Relayer.RelayForward
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
    eventually(assert(bob.stateData.asInstanceOf[ChannelDataWithCommitments].commitments.isIdle))
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
    // alice should reject commands that change the commitment while quiescent
    val sender1, sender2, sender3 = TestProbe()
    val cmds = Seq(CMD_ADD_HTLC(sender1.ref, 1_000_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender1.ref)),
      CMD_UPDATE_FEE(FeeratePerKw(100 sat), replyTo_opt = Some(sender2.ref)),
      CMD_CLOSE(sender3.ref, None, None))
    initiateQuiescence(f)
    safeSend(alice, cmds)
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

  test("recv fail malformed htlc command while initiator awaiting stfu from remote") { f =>
    receiveSettlementCommand(f, FailMalformedHtlc, sendInitialStfu = false)
  }

  test("recv fulfill htlc command while initiator awaiting stfu from remote and channel disconnects") { f =>
    receiveSettlementCommand(f, FulfillHtlc, sendInitialStfu = false, resetConnection = true)
  }

  test("recv fail htlc command while initiator awaiting stfu from remote and channel disconnects") { f =>
    receiveSettlementCommand(f, FailHtlc, sendInitialStfu = false, resetConnection = true)
  }

  test("recv fail malformed htlc command while initiator awaiting stfu from remote and channel disconnects") { f =>
    receiveSettlementCommand(f, FailMalformedHtlc, sendInitialStfu = false, resetConnection = true)
  }

  test("recv fulfill htlc command after initiator receives stfu from remote") { f =>
    receiveSettlementCommand(f, FulfillHtlc, sendInitialStfu = true)
  }

  test("recv fail htlc command after initiator receives stfu from remote") { f =>
    receiveSettlementCommand(f, FailHtlc, sendInitialStfu = true)
  }

  test("recv fail malformed htlc command after initiator receives stfu from remote") { f =>
    receiveSettlementCommand(f, FailMalformedHtlc, sendInitialStfu = true)
  }

  test("recv fulfill htlc command when initiator receives stfu from remote and channel disconnects") { f =>
    receiveSettlementCommand(f, FulfillHtlc, sendInitialStfu = true, resetConnection = true)
  }

  test("recv fail htlc command after initiator receives stfu from remote and channel disconnects") { f =>
    receiveSettlementCommand(f, FailHtlc, sendInitialStfu = true, resetConnection = true)
  }

  test("recv fail malformed htlc command after initiator receives stfu from remote and channel disconnects") { f =>
    receiveSettlementCommand(f, FailMalformedHtlc, sendInitialStfu = true, resetConnection = true)
  }

  test("recv settlement commands while initiator awaiting stfu from remote") { f =>
    import f._

    // alice should reject commands that change the commitment until the splice is complete
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

  test("recv settlement commands after initiator receives stfu from remote") { f =>
    import f._

    // alice should reject commands that change the commitment until the splice is complete
    val sender1, sender2, sender3 = TestProbe()
    val cmds = Seq(CMD_ADD_HTLC(sender1.ref, 1_000_000 msat, randomBytes32(), CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), TestConstants.emptyOnionPacket, None, localOrigin(sender1.ref)),
      CMD_UPDATE_FEE(FeeratePerKw(100 sat), replyTo_opt = Some(sender2.ref)),
      CMD_CLOSE(sender3.ref, None, None))
    initiateQuiescence(f)
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
    // send a second stfu to bob
    bob ! Stfu(channelId(bob), 1)
    bob2alice.expectMsgType[Warning]
    // we should disconnect after giving alice time to receive the warning
    bobPeer.fishForMessage(3 seconds) {
      case Peer.Disconnect(nodeId, _) if nodeId == bob.stateData.asInstanceOf[DATA_NORMAL].commitments.params.remoteParams.nodeId => true
      case _ => false
    }
  }

  test("recv Shutdown message before initiator receives stfu from remote") { f =>
    import f._
    initiateQuiescence(f, sendInitialStfu = false)
    val bobData = bob.stateData.asInstanceOf[DATA_NORMAL]
    alice ! Shutdown(channelId(bob), bob.underlyingActor.getOrGenerateFinalScriptPubKey(bobData))
    alice2bob.expectMsgType[Shutdown]
  }

  test("recv (forbidden) Shutdown message after initiator receives stfu from remote") { f =>
    import f._
    initiateQuiescence(f)
    val bobData = bob.stateData.asInstanceOf[DATA_NORMAL]
    alice ! Shutdown(channelId(bob), bob.underlyingActor.getOrGenerateFinalScriptPubKey(bobData))
    sendErrorAndClose(alice, alice2bob, alice2blockchain)
  }

  test("recv (forbidden) UpdateFulfillHtlc messages after initiator receives stfu from remote") { f =>
    import f._
    val (preimage, add) = addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    initiateQuiescence(f)
    alice ! UpdateFulfillHtlc(channelId(bob), add.id, preimage)
    alice2relayer.expectMsg(RelayForward(add))
    sendErrorAndClose(alice, alice2bob, alice2blockchain)
  }

  test("recv (forbidden) UpdateFailHtlc messages after initiator receives stfu from remote") { f =>
    import f._
    val (_, add) = addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    initiateQuiescence(f)
    alice ! UpdateFailHtlc(channelId(bob), add.id, randomBytes32())
    sendErrorAndClose(alice, alice2bob, alice2blockchain)
  }

  test("recv (forbidden) UpdateFailMalformedHtlc messages after initiator receives stfu from remote") { f =>
    import f._
    val (_, add) = addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    initiateQuiescence(f)
    alice ! UpdateFailMalformedHtlc(channelId(bob), add.id, randomBytes32(), FailureMessageCodecs.BADONION)
    sendErrorAndClose(alice, alice2bob, alice2blockchain)
  }

  test("recv (forbidden) UpdateFee messages after initiator receives stfu from remote") { f =>
    import f._
    val (_, _) = addHtlc(10_000 msat, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    initiateQuiescence(f)
    alice ! UpdateFee(channelId(bob), FeeratePerKw(1 sat))
    sendErrorAndClose(alice, alice2bob, alice2blockchain)
  }

  test("recv UpdateAddHtlc message after initiator receives stfu from remote") { f =>
    import f._
    initiateQuiescence(f)

    // have to build a htlc manually because eclair would refuse to accept this command as it's forbidden
    val fakeHtlc = UpdateAddHtlc(channelId = randomBytes32(), id = 5656, amountMsat = 50000000 msat, cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), paymentHash = randomBytes32(), onionRoutingPacket = TestConstants.emptyOnionPacket, blinding_opt = None)
    bob2alice.forward(alice, fakeHtlc)
    sendErrorAndClose(alice, alice2bob, alice2blockchain)
  }

  test("recv (forbidden) Shutdown message after non-initiator receives stfu from initiator") { f =>
    import f._
    initiateQuiescence(f)
    val bobData = bob.stateData.asInstanceOf[DATA_NORMAL]
    bob ! Shutdown(ByteVector32.Zeroes, bob.underlyingActor.getOrGenerateFinalScriptPubKey(bobData))
    sendErrorAndClose(bob, bob2alice, bob2blockchain)
  }

  test("recv (forbidden) UpdateFulfillHtlc messages after non-initiator receives stfu from initiator") { f =>
    import f._
    val (preimage, add) = addHtlc(10_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    initiateQuiescence(f)
    bob ! UpdateFulfillHtlc(channelId(bob), add.id, preimage)
    bob2relayer.expectMsg(RelayForward(add))
    sendErrorAndClose(bob, bob2alice, bob2blockchain)
  }

  test("recv (forbidden) UpdateFailHtlc messages after non-initiator receives stfu from initiator") { f =>
    import f._
    val (_, add) = addHtlc(10_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    initiateQuiescence(f)
    bob ! UpdateFailHtlc(channelId(bob), add.id, randomBytes32())
    sendErrorAndClose(bob, bob2alice, bob2blockchain)
  }

  test("recv (forbidden) UpdateFailMalformedHtlc messages after non-initiator receives stfu from initiator") { f =>
    import f._
    val (_, add) = addHtlc(10_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    initiateQuiescence(f)
    bob ! UpdateFailMalformedHtlc(channelId(bob), add.id, randomBytes32(), FailureMessageCodecs.BADONION)
    sendErrorAndClose(bob, bob2alice, bob2blockchain)
  }

  test("recv (forbidden) UpdateFee messages after non-initiator receives stfu from initiator") { f =>
    import f._
    val (_, _) = addHtlc(10_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    initiateQuiescence(f)
    bob ! UpdateFee(channelId(bob), FeeratePerKw(1 sat))
    sendErrorAndClose(bob, bob2alice, bob2blockchain)
  }

  test("recv UpdateAddHtlc message after non-initiator receives stfu from initiator") { f =>
    import f._
    val (_, _) = addHtlc(10_000 msat, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    initiateQuiescence(f)
    val fakeHtlc = UpdateAddHtlc(channelId = randomBytes32(), id = 5656, amountMsat = 50000000 msat, cltvExpiry = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), paymentHash = randomBytes32(), onionRoutingPacket = TestConstants.emptyOnionPacket, blinding_opt = None)
    alice2bob.forward(bob, fakeHtlc)
    sendErrorAndClose(bob, bob2alice, bob2blockchain)
  }

}
