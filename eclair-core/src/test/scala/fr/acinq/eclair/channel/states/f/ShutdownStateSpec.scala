/*
 * Copyright 2019 ACINQ SAS
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

package fr.acinq.eclair.channel.states.f

import akka.testkit.TestProbe
import com.softwaremill.quicklens.ModifyPimp
import fr.acinq.bitcoin.ScriptFlags
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, ByteVector64, Crypto, SatoshiLong, Script, Transaction}
import fr.acinq.eclair.blockchain.bitcoind.ZmqWatcher._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.blockchain.{CurrentBlockHeight, CurrentFeerates}
import fr.acinq.eclair.channel.ChannelSpendSignature.IndividualSignature
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.publish.TxPublisher.{PublishFinalTx, PublishTx, SetChannelId}
import fr.acinq.eclair.channel.states.ChannelStateTestsBase.PimpTestFSM
import fr.acinq.eclair.channel.states.{ChannelStateTestsBase, ChannelStateTestsTags}
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer._
import fr.acinq.eclair.payment.send.SpontaneousRecipient
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.testutils.PimpTestProbe.convert
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.transactions.Transactions._
import fr.acinq.eclair.wire.protocol.{AnnouncementSignatures, ChannelReestablish, ChannelUpdate, ClosingSigned, CommitSig, Error, FailureMessageCodecs, FailureReason, Init, PermanentChannelFailure, RevokeAndAck, Shutdown, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFee, UpdateFulfillHtlc}
import fr.acinq.eclair.{BlockHeight, CltvExpiry, CltvExpiryDelta, MilliSatoshiLong, TestConstants, TestKitBaseClass, randomBytes32, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class ShutdownStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with ChannelStateTestsBase {

  type FixtureParam = SetupFixture

  val r1: ByteVector32 = randomBytes32()
  val r2: ByteVector32 = randomBytes32()

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init(tags = test.tags)
    import setup._
    within(30 seconds) {
      reachNormal(setup, test.tags)
      val sender = TestProbe()
      // alice sends an HTLC to bob
      val h1 = Crypto.sha256(r1)
      val recipient1 = SpontaneousRecipient(TestConstants.Bob.nodeParams.nodeId, 300_000_000 msat, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), r1)
      val Right(cmd1) = OutgoingPaymentPacket.buildOutgoingPayment(localOrigin(sender.ref), h1, makeSingleHopRoute(recipient1.totalAmount, recipient1.nodeId), recipient1, Reputation.Score.max).map(_.cmd.copy(commit = false))
      alice ! cmd1
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
      val htlc1 = alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.remoteChanges.proposed == htlc1 :: Nil)
      // alice sends another HTLC to bob
      val h2 = Crypto.sha256(r2)
      val recipient2 = SpontaneousRecipient(TestConstants.Bob.nodeParams.nodeId, 200_000_000 msat, CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight), r2)
      val Right(cmd2) = OutgoingPaymentPacket.buildOutgoingPayment(localOrigin(sender.ref), h2, makeSingleHopRoute(recipient2.totalAmount, recipient2.nodeId), recipient2, Reputation.Score.max).map(_.cmd.copy(commit = false))
      alice ! cmd2
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
      val htlc2 = alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.changes.remoteChanges.proposed == htlc1 :: htlc2 :: Nil)
      // alice signs
      alice ! CMD_SIGN()
      alice2bob.expectMsgType[CommitSig]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[RevokeAndAck]
      bob2alice.forward(alice)
      // bob signs back
      bob2alice.expectMsgType[CommitSig]
      bob2alice.forward(alice)
      alice2bob.expectMsgType[RevokeAndAck]
      alice2bob.forward(bob)
      bob2relayer.expectMsgType[RelayForward]
      bob2relayer.expectMsgType[RelayForward]
      // alice initiates a closing
      alice ! CMD_CLOSE(sender.ref, None, None)
      alice2bob.expectMsgType[Shutdown]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[Shutdown]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == SHUTDOWN)
      awaitCond(bob.stateName == SHUTDOWN)
      channelUpdateListener.expectMsgType[LocalChannelDown]
      channelUpdateListener.expectMsgType[LocalChannelDown]
      withFixture(test.toNoArgTest(setup))
    }
  }

  test("emit disabled channel update", Tag(ChannelStateTestsTags.ChannelsPublic), Tag(ChannelStateTestsTags.DoNotInterceptGossip)) { () =>
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(setup, Set(ChannelStateTestsTags.ChannelsPublic, ChannelStateTestsTags.DoNotInterceptGossip))

      val aliceListener = TestProbe()
      systemA.eventStream.subscribe(aliceListener.ref, classOf[LocalChannelUpdate])
      val bobListener = TestProbe()
      systemB.eventStream.subscribe(bobListener.ref, classOf[LocalChannelUpdate])

      alice2bob.expectMsgType[AnnouncementSignatures]
      alice2bob.forward(bob)
      alice2bob.expectMsgType[ChannelUpdate]
      bob2alice.expectMsgType[AnnouncementSignatures]
      bob2alice.forward(alice)
      bob2alice.expectMsgType[ChannelUpdate]
      assert(aliceListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)
      assert(bobListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)

      addHtlc(50_000_000 msat, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)

      alice ! CMD_CLOSE(TestProbe().ref, None, None)
      alice2bob.expectMsgType[Shutdown]
      alice2bob.forward(bob)
      bob2alice.expectMsgType[Shutdown]
      bob2alice.forward(alice)
      awaitCond(alice.stateName == SHUTDOWN)
      awaitCond(bob.stateName == SHUTDOWN)

      assert(!aliceListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)
      assert(!bobListener.expectMsgType[LocalChannelUpdate].channelUpdate.channelFlags.isEnabled)
    }
  }

  test("recv CMD_ADD_HTLC") { f =>
    import f._
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(sender.ref, 500000000 msat, r1, cltvExpiry = CltvExpiry(300000), TestConstants.emptyOnionPacket, None, Reputation.Score.max, None, localOrigin(sender.ref))
    alice ! add
    val error = ChannelUnavailable(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add, error, None))
    alice2bob.expectNoMessage(200 millis)
  }

  test("recv CMD_FULFILL_HTLC") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    bob ! CMD_FULFILL_HTLC(0, r1, None)
    val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
    awaitCond(bob.stateData == initialState.modify(_.commitments.changes.localChanges.proposed).using(_ :+ fulfill)
    )
  }

  test("recv CMD_FULFILL_HTLC (taproot)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    bob ! CMD_FULFILL_HTLC(0, r1, None)
    val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
    awaitCond(bob.stateData == initialState.modify(_.commitments.changes.localChanges.proposed).using(_ :+ fulfill))
  }

  test("recv CMD_FULFILL_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    bob ! CMD_FULFILL_HTLC(42, randomBytes32(), None, replyTo_opt = Some(sender.ref))
    sender.expectMsgType[RES_FAILURE[CMD_FULFILL_HTLC, UnknownHtlcId]]
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FULFILL_HTLC (invalid preimage)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FULFILL_HTLC(1, ByteVector32.Zeroes, None, replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, InvalidHtlcPreimage(channelId(bob), 1)))

    assert(initialState == bob.stateData)
  }

  test("recv CMD_FULFILL_HTLC (acknowledge in case of success)") { f =>
    import f._
    val sender = TestProbe()

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FULFILL_HTLC(0, r1, None, replyTo_opt = Some(sender.ref))
    // this would be done automatically when the relayer calls safeSend
    bob.underlyingActor.nodeParams.db.pendingCommands.addSettlementCommand(initialState.channelId, c)
    bob ! c
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob ! CMD_SIGN(replyTo_opt = Some(sender.ref))
    bob2alice.expectMsgType[CommitSig]
    awaitCond(bob.underlyingActor.nodeParams.db.pendingCommands.listSettlementCommands(initialState.channelId).isEmpty)
  }

  test("recv CMD_FULFILL_HTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]

    val c = CMD_FULFILL_HTLC(42, randomBytes32(), None, replyTo_opt = Some(sender.ref))
    sender.send(bob, c) // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingCommands.listSettlementCommands(initialState.channelId).isEmpty)
  }

  test("recv UpdateFulfillHtlc") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    val fulfill = UpdateFulfillHtlc(ByteVector32.Zeroes, 0, r1)
    alice ! fulfill
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments == initialState.commitments.modify(_.changes.remoteChanges).setTo(initialState.commitments.changes.remoteChanges.copy(initialState.commitments.changes.remoteChanges.proposed :+ fulfill)))
  }

  test("recv UpdateFulfillHtlc (unknown htlc id)") { f =>
    import f._
    val tx = alice.signCommitTx()
    val fulfill = UpdateFulfillHtlc(ByteVector32.Zeroes, 42, ByteVector32.Zeroes)
    alice ! fulfill
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    alice2blockchain.expectMsgType[PublishTx] // main delayed
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 2
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFulfillHtlc (invalid preimage)") { f =>
    import f._
    val tx = alice.signCommitTx()
    alice ! UpdateFulfillHtlc(ByteVector32.Zeroes, 42, ByteVector32.Zeroes)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    alice2blockchain.expectMsgType[PublishTx] // main delayed
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 2
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv CMD_FAIL_HTLC") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    bob ! CMD_FAIL_HTLC(1, FailureReason.LocalFailure(PermanentChannelFailure()), None)
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]
    awaitCond(bob.stateData == initialState
      .modify(_.commitments.changes.localChanges.proposed).using(_ :+ fail)
    )
  }

  test("recv CMD_FAIL_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FAIL_HTLC(42, FailureReason.LocalFailure(PermanentChannelFailure()), None, replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FAIL_HTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FAIL_HTLC(42, FailureReason.LocalFailure(PermanentChannelFailure()), None, replyTo_opt = Some(sender.ref))
    sender.send(bob, c) // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingCommands.listSettlementCommands(initialState.channelId).isEmpty)
  }

  test("recv CMD_FAIL_MALFORMED_HTLC") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    bob ! CMD_FAIL_MALFORMED_HTLC(1, Crypto.sha256(ByteVector.empty), FailureMessageCodecs.BADONION)
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    awaitCond(bob.stateData == initialState
      .modify(_.commitments.changes.localChanges.proposed).using(_ :+ fail)
    )
  }

  test("recv CMD_FAIL_MALFORMED_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FAIL_MALFORMED_HTLC(42, randomBytes32(), FailureMessageCodecs.BADONION, replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FAIL_MALFORMED_HTLC (invalid failure_code)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FAIL_MALFORMED_HTLC(42, randomBytes32(), 42, replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, InvalidFailureCode(channelId(bob))))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FAIL_MALFORMED_HTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FAIL_MALFORMED_HTLC(42, randomBytes32(), FailureMessageCodecs.BADONION, replyTo_opt = Some(sender.ref))
    sender.send(bob, c) // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingCommands.listSettlementCommands(initialState.channelId).isEmpty)
  }

  test("recv UpdateFailHtlc") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    val fail = UpdateFailHtlc(ByteVector32.Zeroes, 1, ByteVector.fill(152)(0))
    alice ! fail
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments == initialState.commitments.modify(_.changes.remoteChanges).setTo(initialState.commitments.changes.remoteChanges.copy(initialState.commitments.changes.remoteChanges.proposed :+ fail)))
  }

  test("recv UpdateFailHtlc (unknown htlc id)") { f =>
    import f._
    val tx = alice.signCommitTx()
    alice ! UpdateFailHtlc(ByteVector32.Zeroes, 42, ByteVector.fill(152)(0))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    alice2blockchain.expectMsgType[PublishTx] // main delayed
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 2
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFailMalformedHtlc") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    val fail = UpdateFailMalformedHtlc(ByteVector32.Zeroes, 1, Crypto.sha256(ByteVector.empty), FailureMessageCodecs.BADONION)
    alice ! fail
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments == initialState.commitments.modify(_.changes.remoteChanges).setTo(initialState.commitments.changes.remoteChanges.copy(initialState.commitments.changes.remoteChanges.proposed :+ fail)))
  }

  test("recv UpdateFailMalformedHtlc (invalid failure_code)") { f =>
    import f._
    val tx = alice.signCommitTx()
    val fail = UpdateFailMalformedHtlc(ByteVector32.Zeroes, 1, Crypto.sha256(ByteVector.empty), 42)
    alice ! fail
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) == InvalidFailureCode(ByteVector32.Zeroes).getMessage)
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    alice2blockchain.expectMsgType[PublishTx] // main delayed
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 2
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv CMD_SIGN") { f =>
    import f._
    val sender = TestProbe()
    // we need to have something to sign so we first send a fulfill and acknowledge (=sign) it
    bob ! CMD_FULFILL_HTLC(0, r1, None)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN(replyTo_opt = Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isLeft)
  }

  test("recv CMD_SIGN (taproot)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    import f._
    val sender = TestProbe()
    bob ! CMD_FULFILL_HTLC(0, r1, None)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN(replyTo_opt = Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    assert(bob2alice.expectMsgType[CommitSig].partialSignature_opt.nonEmpty)
    bob2alice.forward(alice)
    assert(alice2bob.expectMsgType[RevokeAndAck].nextCommitNonces.contains(bob.commitments.latest.fundingTxId))
    alice2bob.forward(bob)
    assert(alice2bob.expectMsgType[CommitSig].partialSignature_opt.nonEmpty)
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isLeft)
  }

  test("recv CMD_SIGN (no changes)") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_SIGN(replyTo_opt = Some(sender.ref))
    sender.expectNoMessage(100 millis) // just ignored
    //sender.expectMsg("cannot sign when there are no changes")
  }

  test("recv CMD_SIGN (while waiting for RevokeAndAck)") { f =>
    import f._
    val sender = TestProbe()
    bob ! CMD_FULFILL_HTLC(0, r1, None)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob ! CMD_SIGN(replyTo_opt = Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]
    awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isLeft)
    val waitForRevocation = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.left.toOption.get

    // actual test starts here
    bob ! CMD_SIGN(replyTo_opt = Some(sender.ref))
    sender.expectNoMessage(300 millis)
    assert(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo == Left(waitForRevocation))
  }

  test("recv CommitSig") { f =>
    import f._
    bob ! CMD_FULFILL_HTLC(0, r1, None)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
  }

  test("recv CommitSig (no changes)") { f =>
    import f._
    val tx = bob.signCommitTx()
    // signature is invalid but it doesn't matter
    bob ! CommitSig(ByteVector32.Zeroes, IndividualSignature(ByteVector64.Zeroes), Nil)
    bob2alice.expectMsgType[Error]
    awaitCond(bob.stateName == CLOSING)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv CommitSig (invalid signature)") { f =>
    import f._
    val tx = bob.signCommitTx()
    bob ! CommitSig(ByteVector32.Zeroes, IndividualSignature(ByteVector64.Zeroes), Nil)
    bob2alice.expectMsgType[Error]
    awaitCond(bob.stateName == CLOSING)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv RevokeAndAck (with remaining htlcs on both sides)") { f =>
    import f._
    fulfillHtlc(1, r2, bob, alice, bob2alice, alice2bob)
    // this will cause alice and bob to receive RevokeAndAcks
    crossSign(bob, alice, bob2alice, alice2bob)
    // actual test starts here
    assert(alice.stateName == SHUTDOWN)
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.latest.localCommit.spec.htlcs.size == 1)
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.latest.remoteCommit.spec.htlcs.size == 1)
  }

  test("recv RevokeAndAck (with remaining htlcs on one side)") { f =>
    import f._
    fulfillHtlc(0, r1, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(1, r2, bob, alice, bob2alice, alice2bob)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    // actual test starts here
    alice2bob.forward(bob)
    assert(bob.stateName == SHUTDOWN)
    awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.latest.localCommit.spec.htlcs.size == 2)
    awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.latest.remoteCommit.spec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.latest.localCommit.spec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.latest.remoteCommit.spec.htlcs.size == 2)
  }

  test("recv RevokeAndAck (no more htlcs on either side)") { f =>
    import f._
    fulfillHtlc(0, r1, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(1, r2, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    // actual test starts here
    awaitCond(alice.stateName == NEGOTIATING)
  }

  test("recv RevokeAndAck (no more htlcs on either side, taproot)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    import f._
    // Bob fulfills the first HTLC.
    fulfillHtlc(0, r1, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    assert(alice.stateName == SHUTDOWN)
    // Bob fulfills the second HTLC.
    fulfillHtlc(1, r2, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    awaitCond(alice.stateName == NEGOTIATING_SIMPLE)
    awaitCond(bob.stateName == NEGOTIATING_SIMPLE)
  }

  test("recv RevokeAndAck (invalid preimage)") { f =>
    import f._
    val tx = bob.signCommitTx()
    bob ! CMD_FULFILL_HTLC(0, r1, None)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isLeft)
    bob ! RevokeAndAck(ByteVector32.Zeroes, PrivateKey(randomBytes32()), PrivateKey(randomBytes32()).publicKey)
    bob2alice.expectMsgType[Error]
    awaitCond(bob.stateName == CLOSING)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    bob2blockchain.expectMsgType[PublishTx] // htlc success
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv RevokeAndAck (unexpectedly)") { f =>
    import f._
    val tx = alice.signCommitTx()
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isRight)
    alice ! RevokeAndAck(ByteVector32.Zeroes, PrivateKey(randomBytes32()), PrivateKey(randomBytes32()).publicKey)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    alice2blockchain.expectMsgType[PublishTx] // main delayed
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 2
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv RevokeAndAck (forward UpdateFailHtlc)") { f =>
    import f._
    bob ! CMD_FAIL_HTLC(1, FailureReason.LocalFailure(PermanentChannelFailure()), None)
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // alice still hasn't forwarded the fail because it is not yet cross-signed
    alice2relayer.expectNoMessage()

    // actual test begins
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // alice will forward the fail upstream
    val forward = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFail]]
    assert(forward.result.fail == fail)
  }

  test("recv RevokeAndAck (forward UpdateFailMalformedHtlc)") { f =>
    import f._
    bob ! CMD_FAIL_MALFORMED_HTLC(1, Crypto.sha256(ByteVector.view("should be htlc.onionRoutingPacket".getBytes())), FailureMessageCodecs.BADONION)
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // alice still hasn't forwarded the fail because it is not yet cross-signed
    alice2relayer.expectNoMessage()

    // actual test begins
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // alice will forward the fail upstream
    val forward = alice2relayer.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFailMalformed]]
    assert(forward.result.fail == fail)
  }

  test("recv CMD_UPDATE_FEE") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    alice ! CMD_UPDATE_FEE(FeeratePerKw(20000 sat), replyTo_opt = Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_FEE]]
    val fee = alice2bob.expectMsgType[UpdateFee]
    awaitCond(alice.stateData == initialState
      .modify(_.commitments.changes.localChanges.proposed).using(_ :+ fee)
    )
  }

  test("recv CMD_UPDATE_FEE (when fundee)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    bob ! CMD_UPDATE_FEE(FeeratePerKw(20000 sat), replyTo_opt = Some(sender.ref))
    sender.expectMsgType[RES_FAILURE[CMD_UPDATE_FEE, NonInitiatorCannotSendUpdateFee]]
    assert(initialState == bob.stateData)
  }

  test("recv UpdateFee") { f =>
    import f._
    val initialData = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(12000 sat))
    bob ! fee
    awaitCond(bob.stateData == initialData
      .modify(_.commitments.changes.remoteChanges.proposed).using(_ :+ fee)
    )
  }

  test("recv UpdateFee (when sender is not funder)") { f =>
    import f._
    val tx = alice.signCommitTx()
    alice ! UpdateFee(ByteVector32.Zeroes, FeeratePerKw(12000 sat))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    alice2blockchain.expectMsgType[PublishTx] // main delayed
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 2
    alice2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFee (sender can't afford it)") { f =>
    import f._
    val tx = bob.signCommitTx()
    val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(100_000_000 sat))
    // we first update the feerates so that we don't trigger a 'fee too different' error
    bob.setBitcoinCoreFeerate(fee.feeratePerKw)
    bob ! fee
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == CannotAffordFees(channelId(bob), missing = 72120000L sat, reserve = 20000L sat, fees = 72400000L sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    bob2blockchain.expectFinalTxPublished(tx.txid)
    // even though the feerate is extremely high, we publish our main transaction with a feerate capped by our max-closing-feerate
    val mainTx = bob2blockchain.expectFinalTxPublished("local-main-delayed")
    assert(Transactions.fee2rate(mainTx.fee, mainTx.tx.weight()) <= bob.nodeParams.onChainFeeConf.maxClosingFeerate * 1.1)
    bob2blockchain.expectWatchTxConfirmed(tx.txid)
  }

  test("recv UpdateFee (local/remote feerates are too different)") { f =>
    import f._
    val tx = bob.signCommitTx()
    bob ! UpdateFee(ByteVector32.Zeroes, FeeratePerKw(65000 sat))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == "local/remote feerates are too different: remoteFeeratePerKw=65000 localFeeratePerKw=10000")
    awaitCond(bob.stateName == CLOSING)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv UpdateFee (remote feerate is too small)") { f =>
    import f._
    val tx = bob.signCommitTx()
    bob ! UpdateFee(ByteVector32.Zeroes, FeeratePerKw(252 sat))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) == "remote fee rate is too small: remoteFeeratePerKw=252")
    awaitCond(bob.stateName == CLOSING)
    assert(bob2blockchain.expectMsgType[PublishFinalTx].tx.txid == tx.txid) // commit tx
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    bob2blockchain.expectMsgType[WatchTxConfirmed]
  }

  test("recv CMD_UPDATE_RELAY_FEE ") { f =>
    import f._
    val sender = TestProbe()
    val newFeeBaseMsat = TestConstants.Alice.nodeParams.relayParams.publicChannelFees.feeBase * 2
    val newFeeProportionalMillionth = TestConstants.Alice.nodeParams.relayParams.publicChannelFees.feeProportionalMillionths * 2
    alice ! CMD_UPDATE_RELAY_FEE(sender.ref, newFeeBaseMsat, newFeeProportionalMillionth)
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_RELAY_FEE]]
    alice2relayer.expectNoMessage(100 millis)
  }

  test("recv CurrentBlockCount (no htlc timed out)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    alice ! CurrentBlockHeight(BlockHeight(400143))
    awaitCond(alice.stateData == initialState)
  }

  test("recv CurrentBlockCount (an htlc timed out)") { f =>
    import f._
    val aliceCommitTx = alice.signCommitTx()
    alice ! CurrentBlockHeight(BlockHeight(400145))
    assert(alice2blockchain.expectMsgType[PublishFinalTx].tx.txid == aliceCommitTx.txid) // commit tx
    alice2blockchain.expectMsgType[PublishTx] // main delayed
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishTx] // htlc timeout 2
    assert(alice2blockchain.expectMsgType[WatchTxConfirmed].txId == aliceCommitTx.txid)
  }

  test("recv CurrentFeerate (when funder, triggers an UpdateFee)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    val event = CurrentFeerates.BitcoinCore(FeeratesPerKw(minimum = FeeratePerKw(250 sat), fastest = FeeratePerKw(10_000 sat), fast = FeeratePerKw(5_000 sat), medium = FeeratePerKw(1000 sat), slow = FeeratePerKw(500 sat)))
    alice.setBitcoinCoreFeerates(event.feeratesPerKw)
    alice ! event
    alice2bob.expectMsg(UpdateFee(initialState.commitments.channelId, alice.underlyingActor.nodeParams.onChainFeeConf.getCommitmentFeerate(alice.underlyingActor.nodeParams.currentBitcoinCoreFeerates, alice.underlyingActor.remoteNodeId, initialState.commitments.latest.commitmentFormat)))
  }

  test("recv CurrentFeerate (when funder, triggers an UpdateFee, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    assert(initialState.commitments.latest.localCommit.spec.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw)
    val event = CurrentFeerates.BitcoinCore(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw / 2).copy(minimum = FeeratePerKw(250 sat)))
    alice.setBitcoinCoreFeerates(event.feeratesPerKw)
    alice ! event
    alice2bob.expectMsg(UpdateFee(initialState.commitments.channelId, TestConstants.anchorOutputsFeeratePerKw / 2))
  }

  test("recv CurrentFeerate (when funder, doesn't trigger an UpdateFee)") { f =>
    import f._
    val event = CurrentFeerates.BitcoinCore(FeeratesPerKw.single(FeeratePerKw(10010 sat)))
    alice.setBitcoinCoreFeerates(event.feeratesPerKw)
    alice ! event
    alice2bob.expectNoMessage(500 millis)
  }

  test("recv CurrentFeerate (when funder, doesn't trigger an UpdateFee, anchor outputs)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    assert(initialState.commitments.latest.localCommit.spec.commitTxFeerate == TestConstants.anchorOutputsFeeratePerKw)
    val event = CurrentFeerates.BitcoinCore(FeeratesPerKw.single(TestConstants.anchorOutputsFeeratePerKw * 2).copy(minimum = FeeratePerKw(250 sat)))
    alice.setBitcoinCoreFeerates(event.feeratesPerKw)
    alice ! event
    alice2bob.expectNoMessage(500 millis)
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are close)") { f =>
    import f._
    val event = CurrentFeerates.BitcoinCore(FeeratesPerKw.single(FeeratePerKw(11000 sat)))
    bob.setBitcoinCoreFeerates(event.feeratesPerKw)
    bob ! event
    bob2alice.expectNoMessage(500 millis)
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are very different)") { f =>
    import f._
    val event = CurrentFeerates.BitcoinCore(FeeratesPerKw.single(FeeratePerKw(25000 sat)))
    bob.setBitcoinCoreFeerates(event.feeratesPerKw)
    bob ! event
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishTx] // commit tx
    bob2blockchain.expectMsgType[PublishTx] // main delayed
    bob2blockchain.expectMsgType[WatchTxConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv WatchFundingSpentTriggered (their commit)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._
    // bob publishes his current commit tx, which contains two pending htlcs alice->bob
    val bobCommitTx = bob.signCommitTx()
    assert(bobCommitTx.txOut.size == 6) // two main outputs and 2 pending htlcs
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
    val rcp = alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get
    assert(rcp.htlcOutputs.size == 2)

    // in response to that, alice publishes her claim txs
    val anchorTx = alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimMain = alice2blockchain.expectFinalTxPublished("remote-main-delayed")
    // in addition to her main output, alice can only claim 2 out of 3 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the preimage
    val claimHtlcTxs = (1 to 2).map(_ => alice2blockchain.expectReplaceableTxPublished[ClaimHtlcTimeoutTx])
    val htlcAmountClaimed = claimHtlcTxs.map(claimHtlcTx => {
      assert(claimHtlcTx.tx.txIn.size == 1)
      assert(claimHtlcTx.tx.txOut.size == 1)
      Transaction.correctlySpends(claimHtlcTx.sign(), bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      claimHtlcTx.tx.txOut.head.amount
    }).sum
    // htlc will timeout and be eventually refunded so we have a little less than fundingSatoshis - pushMsat = 1000000 - 200000 = 800000 (because fees)
    val amountClaimed = htlcAmountClaimed + claimMain.tx.txOut.head.amount
    assert(amountClaimed == 790_974.sat)

    alice2blockchain.expectWatchTxConfirmed(bobCommitTx.txid)
    alice2blockchain.expectWatchOutputsSpent(Seq(anchorTx.input.outPoint, claimMain.input) ++ claimHtlcTxs.map(_.input.outPoint))
    alice2blockchain.expectNoMessage(100 millis)
  }

  test("recv WatchFundingSpentTriggered (their next commit)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._
    // bob fulfills the first htlc
    fulfillHtlc(0, r1, bob, alice, bob2alice, alice2bob)
    // then signs
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    // we intercept bob's revocation
    // as far as alice knows, bob currently has two valid unrevoked commitment transactions

    // bob publishes his current commit tx, which contains one pending htlc alice->bob
    val bobCommitTx = bob.signCommitTx()
    assert(bobCommitTx.txOut.size == 5) // two anchor outputs, two main outputs and 1 pending htlc
    alice ! WatchFundingSpentTriggered(bobCommitTx)
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.isDefined)
    val rcp = alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get
    assert(rcp.htlcOutputs.size == 1)

    // in response to that, alice publishes her claim txs
    val anchorTx = alice2blockchain.expectReplaceableTxPublished[ClaimRemoteAnchorTx]
    val claimTxs = Seq(
      alice2blockchain.expectFinalTxPublished("remote-main-delayed").tx,
      // there is only one htlc to claim in the commitment bob published
      alice2blockchain.expectReplaceableTxPublished[ClaimHtlcTimeoutTx].sign()
    )
    val amountClaimed = claimTxs.map(claimTx => {
      assert(claimTx.txIn.size == 1)
      assert(claimTx.txOut.size == 1)
      Transaction.correctlySpends(claimTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      claimTx.txOut.head.amount
    }).sum
    // htlc will timeout and be eventually refunded so we have a little less than fundingSatoshis - pushMsat - htlc1 = 1000000 - 200000 - 300 000 = 500000 (because fees)
    assert(amountClaimed == 491_542.sat)

    alice2blockchain.expectWatchTxConfirmed(bobCommitTx.txid)
    alice2blockchain.expectWatchOutputsSpent(anchorTx.input.outPoint +: claimTxs.flatMap(_.txIn.map(_.outPoint)))
    alice2blockchain.expectNoMessage(100 millis)
  }

  test("recv WatchFundingSpentTriggered (revoked tx)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._
    val revokedTx = bob.signCommitTx()
    // two main outputs + 2 htlc
    assert(revokedTx.txOut.size == 6)

    // bob fulfills one of the pending htlc (just so that he can have a new sig)
    fulfillHtlc(0, r1, bob, alice, bob2alice, alice2bob)
    // bob and alice sign
    crossSign(bob, alice, bob2alice, alice2bob)
    // bob now has a new commitment tx

    // bob published the revoked tx
    alice ! WatchFundingSpentTriggered(revokedTx)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)

    val mainTx = alice2blockchain.expectFinalTxPublished("remote-main-delayed").tx
    val mainPenaltyTx = alice2blockchain.expectFinalTxPublished("main-penalty").tx
    val htlc1PenaltyTx = alice2blockchain.expectFinalTxPublished("htlc-penalty").tx
    val htlc2PenaltyTx = alice2blockchain.expectFinalTxPublished("htlc-penalty").tx
    Seq(mainTx, mainPenaltyTx, htlc1PenaltyTx, htlc2PenaltyTx).foreach(tx => Transaction.correctlySpends(tx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    // two main outputs are 300 000 and 200 000, htlcs are 300 000 and 200 000
    assert(mainTx.txOut.head.amount == 291_250.sat)
    assert(mainPenaltyTx.txOut.head.amount == 195_170.sat)
    assert(htlc1PenaltyTx.txOut.head.amount == 194_200.sat)
    assert(htlc2PenaltyTx.txOut.head.amount == 294_200.sat)

    alice2blockchain.expectWatchTxConfirmed(revokedTx.txid)
    alice2blockchain.expectWatchOutputsSpent(Seq(mainTx, mainPenaltyTx, htlc1PenaltyTx, htlc2PenaltyTx).flatMap(_.txIn.map(_.outPoint)))
    alice2blockchain.expectNoMessage(100 millis)
  }

  test("recv WatchFundingSpentTriggered (revoked tx with updated commitment)", Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs), Tag(ChannelStateTestsTags.StaticRemoteKey)) { f =>
    import f._
    val initialCommitTx = bob.signCommitTx()
    assert(initialCommitTx.txOut.size == 6) // two main outputs + 2 htlc

    // bob fulfills one of the pending htlc (commitment update while in shutdown state)
    fulfillHtlc(0, r1, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    val revokedTx = bob.signCommitTx()
    assert(revokedTx.txOut.size == 5) // two anchor outputs, two main outputs + 1 htlc

    // bob fulfills the second pending htlc (and revokes the previous commitment)
    fulfillHtlc(1, r2, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    alice2bob.expectMsgType[ClosingSigned] // no more htlcs in the commitment

    // bob published the revoked tx
    alice ! WatchFundingSpentTriggered(revokedTx)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)

    val mainTx = alice2blockchain.expectFinalTxPublished("remote-main-delayed").tx
    val mainPenaltyTx = alice2blockchain.expectFinalTxPublished("main-penalty").tx
    val htlcPenaltyTx = alice2blockchain.expectFinalTxPublished("htlc-penalty").tx
    Seq(mainTx, mainPenaltyTx, htlcPenaltyTx).foreach(tx => Transaction.correctlySpends(tx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    // two main outputs are 300 000 and 200 000, htlcs are 300 000 and 200 000
    assert(mainTx.txOut(0).amount == 291_680.sat)
    assert(mainPenaltyTx.txOut(0).amount == 495_170.sat)
    assert(htlcPenaltyTx.txOut(0).amount == 194_200.sat)

    alice2blockchain.expectWatchTxConfirmed(revokedTx.txid)
    alice2blockchain.expectWatchOutputsSpent(Seq(mainTx, mainPenaltyTx, htlcPenaltyTx).flatMap(_.txIn.map(_.outPoint)))
    alice2blockchain.expectNoMessage(100 millis)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_CLOSE(sender.ref, None, None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, ClosingAlreadyInProgress]]
  }

  test("recv CMD_CLOSE with updated feerates") { f =>
    import f._
    val sender = TestProbe()
    val closingFeerates1 = ClosingFeerates(FeeratePerKw(500 sat), FeeratePerKw(250 sat), FeeratePerKw(2500 sat))
    alice ! CMD_CLOSE(sender.ref, None, Some(closingFeerates1))
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    assert(alice.stateData.asInstanceOf[DATA_SHUTDOWN].closeStatus.feerates_opt.contains(closingFeerates1))

    val closingScript = alice.stateData.asInstanceOf[DATA_SHUTDOWN].localShutdown.scriptPubKey
    val closingFeerates2 = ClosingFeerates(FeeratePerKw(600 sat), FeeratePerKw(300 sat), FeeratePerKw(2500 sat))
    alice ! CMD_CLOSE(sender.ref, Some(closingScript.reverse), Some(closingFeerates2))
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, ClosingAlreadyInProgress]]
    alice ! CMD_CLOSE(sender.ref, Some(closingScript), Some(closingFeerates2))
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    assert(alice.stateData.asInstanceOf[DATA_SHUTDOWN].closeStatus.feerates_opt.contains(closingFeerates2))
  }

  test("recv CMD_CLOSE with updated script") { f =>
    import f._
    val sender = TestProbe()
    val script = Script.write(Script.pay2wpkh(randomKey().publicKey))
    alice ! CMD_CLOSE(sender.ref, Some(script), None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, ClosingAlreadyInProgress]]
  }

  test("recv CMD_CLOSE with updated script (option_simple_close)", Tag(ChannelStateTestsTags.SimpleClose)) { f =>
    import f._
    val sender = TestProbe()
    val script = Script.write(Script.pay2wpkh(randomKey().publicKey))
    alice ! CMD_CLOSE(sender.ref, Some(script), None)
    sender.expectMsgType[RES_SUCCESS[CMD_CLOSE]]
    assert(alice2bob.expectMsgType[Shutdown].scriptPubKey == script)
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].remoteShutdown.scriptPubKey == script)
  }

  test("recv CMD_FORCECLOSE") { f =>
    import f._

    val aliceCommitTx = alice.signCommitTx()
    assert(aliceCommitTx.txOut.size == 4) // two main outputs and two htlcs

    val sender = TestProbe()
    alice ! CMD_FORCECLOSE(sender.ref)
    sender.expectMsgType[RES_SUCCESS[CMD_FORCECLOSE]]
    alice2blockchain.expectFinalTxPublished(aliceCommitTx.txid)
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)
    val lcp = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    assert(lcp.htlcOutputs.size == 2)

    val claimMain = alice2blockchain.expectFinalTxPublished("local-main-delayed")
    val htlc1 = alice2blockchain.expectFinalTxPublished("htlc-timeout")
    val htlc2 = alice2blockchain.expectFinalTxPublished("htlc-timeout")
    Seq(claimMain, htlc1, htlc2).foreach(tx => Transaction.correctlySpends(tx.tx, aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    alice2blockchain.expectWatchTxConfirmed(aliceCommitTx.txid)
    alice2blockchain.expectWatchOutputsSpent(Seq(claimMain, htlc1, htlc2).map(_.input))
    alice2blockchain.expectNoMessage(100 millis)

    // 3rd-stage txs are published when htlc txs confirm
    Seq(htlc1, htlc2).foreach(htlcTimeoutTx => {
      alice ! WatchOutputSpentTriggered(0 sat, htlcTimeoutTx.tx)
      alice2blockchain.expectWatchTxConfirmed(htlcTimeoutTx.tx.txid)
      alice ! WatchTxConfirmedTriggered(BlockHeight(2701), 3, htlcTimeoutTx.tx)
      val htlcDelayedTx = alice2blockchain.expectFinalTxPublished("htlc-delayed")
      Transaction.correctlySpends(htlcDelayedTx.tx, htlcTimeoutTx.tx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      alice2blockchain.expectWatchOutputSpent(htlcDelayedTx.input)
    })
    awaitCond(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get.htlcDelayedOutputs.size == 2)
    alice2blockchain.expectNoMessage(100 millis)
  }

  test("recv WatchFundingSpentTriggered (unrecognized commit)") { f =>
    import f._
    alice ! WatchFundingSpentTriggered(Transaction(0, Nil, Nil, 0))
    alice2blockchain.expectNoMessage(100 millis)
    assert(alice.stateName == SHUTDOWN)
  }

  def testInputRestored(f: FixtureParam, commitmentFormat: CommitmentFormat): Unit = {
    import f._
    // Alice and Bob restart.
    val aliceData = alice.underlyingActor.nodeParams.db.channels.getChannel(channelId(alice)).get
    alice.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    alice ! INPUT_RESTORED(aliceData)
    alice2blockchain.expectMsgType[SetChannelId]
    val fundingTxId = alice2blockchain.expectMsgType[WatchFundingSpent].txId
    awaitCond(alice.stateName == OFFLINE)
    val bobData = bob.underlyingActor.nodeParams.db.channels.getChannel(channelId(bob)).get
    bob.setState(WAIT_FOR_INIT_INTERNAL, Nothing)
    bob ! INPUT_RESTORED(bobData)
    bob2blockchain.expectMsgType[SetChannelId]
    bob2blockchain.expectMsgType[WatchFundingSpent]
    awaitCond(bob.stateName == OFFLINE)
    // They reconnect and provide nonces to resume HTLC settlement.
    val aliceInit = Init(alice.underlyingActor.nodeParams.features.initFeatures())
    val bobInit = Init(bob.underlyingActor.nodeParams.features.initFeatures())
    alice ! INPUT_RECONNECTED(bob, aliceInit, bobInit)
    bob ! INPUT_RECONNECTED(alice, bobInit, aliceInit)
    val channelReestablishAlice = alice2bob.expectMsgType[ChannelReestablish]
    val channelReestablishBob = bob2alice.expectMsgType[ChannelReestablish]
    Seq(channelReestablishAlice, channelReestablishBob).foreach(channelReestablish => commitmentFormat match {
      case _: SegwitV0CommitmentFormat =>
        assert(channelReestablish.currentCommitNonce_opt.isEmpty)
        assert(channelReestablish.nextCommitNonces.isEmpty)
      case _: TaprootCommitmentFormat =>
        assert(channelReestablish.currentCommitNonce_opt.isEmpty)
        assert(channelReestablish.nextCommitNonces.contains(fundingTxId))
    })
    alice2bob.forward(bob, channelReestablishAlice)
    bob2alice.forward(alice, channelReestablishBob)
    // They retransmit shutdown.
    alice2bob.expectMsgType[Shutdown]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    // They resume HTLC settlement.
    fulfillHtlc(0, r1, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    assert(alice.stateName == SHUTDOWN)
    fulfillHtlc(1, r2, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    awaitCond(alice.stateName == NEGOTIATING_SIMPLE)
    awaitCond(bob.stateName == NEGOTIATING_SIMPLE)
  }

  test("recv INPUT_RESTORED", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.AnchorOutputsZeroFeeHtlcTxs)) { f =>
    testInputRestored(f, ZeroFeeHtlcTxAnchorOutputsCommitmentFormat)
  }

  test("recv INPUT_RESTORED (taproot)", Tag(ChannelStateTestsTags.SimpleClose), Tag(ChannelStateTestsTags.OptionSimpleTaproot)) { f =>
    testInputRestored(f, ZeroFeeHtlcTxSimpleTaprootChannelCommitmentFormat)
  }

  test("recv Error") { f =>
    import f._
    val aliceCommitTx = alice.signCommitTx()
    alice ! Error(ByteVector32.Zeroes, "oops")
    alice2blockchain.expectFinalTxPublished(aliceCommitTx.txid)
    assert(aliceCommitTx.txOut.size == 4) // two main outputs and two htlcs
    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)

    // alice can claim both htlc after a timeout, so we expect 3 transactions:
    // - 1 tx to claim the main delayed output
    // - 2 txs for each htlc
    // NB: 3rd-stage txs will only be published once the htlc txs confirm
    val claimTxs = (0 until 3).map(_ => alice2blockchain.expectMsgType[PublishFinalTx].tx)
    // the main delayed output and htlc txs spend the commitment transaction
    claimTxs.foreach(tx => Transaction.correctlySpends(tx, aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
    alice2blockchain.expectWatchTxConfirmed(aliceCommitTx.txid)
    alice2blockchain.expectWatchOutputsSpent(claimTxs.flatMap(_.txIn.map(_.outPoint)))
    alice2blockchain.expectNoMessage(100 millis)
  }

}
