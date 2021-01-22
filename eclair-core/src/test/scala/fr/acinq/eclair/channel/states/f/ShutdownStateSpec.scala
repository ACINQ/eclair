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
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{ByteVector32, ByteVector64, Crypto, SatoshiLong, ScriptFlags, Transaction}
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.{FeeratePerKw, FeeratesPerKw}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.payment.OutgoingPacket.Upstream
import fr.acinq.eclair.payment._
import fr.acinq.eclair.payment.relay.Relayer._
import fr.acinq.eclair.router.Router.ChannelHop
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire.{CommitSig, Error, FailureMessageCodecs, PermanentChannelFailure, RevokeAndAck, Shutdown, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFee, UpdateFulfillHtlc}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, MilliSatoshiLong, TestConstants, TestKitBaseClass, randomBytes32}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.ByteVector

import java.util.UUID
import scala.concurrent.duration._

/**
 * Created by PM on 05/07/2016.
 */

class ShutdownStateSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with StateTestsHelperMethods {

  type FixtureParam = SetupFixture

  val r1 = randomBytes32
  val r2 = randomBytes32

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(setup)
      val sender = TestProbe()
      // alice sends an HTLC to bob
      val h1 = Crypto.sha256(r1)
      val amount1 = 300000000 msat
      val expiry1 = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
      val cmd1 = OutgoingPacket.buildCommand(sender.ref, Upstream.Local(UUID.randomUUID), h1, ChannelHop(null, TestConstants.Bob.nodeParams.nodeId, null) :: Nil, FinalLegacyPayload(amount1, expiry1))._1.copy(commit = false)
      alice ! cmd1
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
      val htlc1 = alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.proposed == htlc1 :: Nil)
      // alice sends another HTLC to bob
      val h2 = Crypto.sha256(r2)
      val amount2 = 200000000 msat
      val expiry2 = CltvExpiryDelta(144).toCltvExpiry(currentBlockHeight)
      val cmd2 = OutgoingPacket.buildCommand(sender.ref, Upstream.Local(UUID.randomUUID), h2, ChannelHop(null, TestConstants.Bob.nodeParams.nodeId, null) :: Nil, FinalLegacyPayload(amount2, expiry2))._1.copy(commit = false)
      alice ! cmd2
      sender.expectMsgType[RES_SUCCESS[CMD_ADD_HTLC]]
      val htlc2 = alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)
      awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.proposed == htlc1 :: htlc2 :: Nil)
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
      relayerB.expectMsgType[RelayForward]
      relayerB.expectMsgType[RelayForward]
      // alice initiates a closing
      alice ! CMD_CLOSE(sender.ref, None)
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

  test("recv CMD_ADD_HTLC") { f =>
    import f._
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(sender.ref, 500000000 msat, r1, cltvExpiry = CltvExpiry(300000), TestConstants.emptyOnionPacket, localOrigin(sender.ref))
    alice ! add
    val error = ChannelUnavailable(channelId(alice))
    sender.expectMsg(RES_ADD_FAILED(add, error, None))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_FULFILL_HTLC") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    bob ! CMD_FULFILL_HTLC(0, r1)
    val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
    awaitCond(bob.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fulfill))))
  }

  test("recv CMD_FULFILL_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    bob ! CMD_FULFILL_HTLC(42, randomBytes32, replyTo_opt = Some(sender.ref))
    sender.expectMsgType[RES_FAILURE[CMD_FULFILL_HTLC, UnknownHtlcId]]
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FULFILL_HTLC (invalid preimage)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FULFILL_HTLC(1, ByteVector32.Zeroes, replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, InvalidHtlcPreimage(channelId(bob), 1)))

    assert(initialState == bob.stateData)
  }

  test("recv CMD_FULFILL_HTLC (acknowledge in case of success)") { f =>
    import f._
    val sender = TestProbe()

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FULFILL_HTLC(0, r1, replyTo_opt = Some(sender.ref))
    // this would be done automatically when the relayer calls safeSend
    bob.underlyingActor.nodeParams.db.pendingRelay.addPendingRelay(initialState.channelId, c)
    bob ! c
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob ! CMD_SIGN(replyTo_opt = Some(sender.ref))
    bob2alice.expectMsgType[CommitSig]
    awaitCond(bob.underlyingActor.nodeParams.db.pendingRelay.listPendingRelay(initialState.channelId).isEmpty)
  }

  test("recv CMD_FULFILL_HTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]

    val c = CMD_FULFILL_HTLC(42, randomBytes32, replyTo_opt = Some(sender.ref))
    sender.send(bob, c) // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingRelay.listPendingRelay(initialState.channelId).isEmpty)
  }

  test("recv UpdateFulfillHtlc") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    val fulfill = UpdateFulfillHtlc(ByteVector32.Zeroes, 0, r1)
    alice ! fulfill
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments == initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fulfill)))
  }

  test("recv UpdateFulfillHtlc (unknown htlc id)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    val fulfill = UpdateFulfillHtlc(ByteVector32.Zeroes, 42, ByteVector32.Zeroes)
    alice ! fulfill
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 2
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 2
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFulfillHtlc (invalid preimage)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! UpdateFulfillHtlc(ByteVector32.Zeroes, 42, ByteVector32.Zeroes)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 2
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 2
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CMD_FAIL_HTLC") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    bob ! CMD_FAIL_HTLC(1, Right(PermanentChannelFailure))
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]
    awaitCond(bob.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fail))))
  }

  test("recv CMD_FAIL_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FAIL_HTLC(42, Right(PermanentChannelFailure), replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FAIL_HTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FAIL_HTLC(42, Right(PermanentChannelFailure), replyTo_opt = Some(sender.ref))
    sender.send(bob, c) // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingRelay.listPendingRelay(initialState.channelId).isEmpty)
  }

  test("recv CMD_FAIL_MALFORMED_HTLC") { f =>
    import f._
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    bob ! CMD_FAIL_MALFORMED_HTLC(1, Crypto.sha256(ByteVector.empty), FailureMessageCodecs.BADONION)
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    awaitCond(bob.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fail))))
  }

  test("recv CMD_FAIL_MALFORMED_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FAIL_MALFORMED_HTLC(42, randomBytes32, FailureMessageCodecs.BADONION, replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FAIL_MALFORMED_HTLC (invalid failure_code)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FAIL_MALFORMED_HTLC(42, randomBytes32, 42, replyTo_opt = Some(sender.ref))
    bob ! c
    sender.expectMsg(RES_FAILURE(c, InvalidFailureCode(channelId(bob))))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FAIL_MALFORMED_HTLC (acknowledge in case of failure)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val c = CMD_FAIL_MALFORMED_HTLC(42, randomBytes32, FailureMessageCodecs.BADONION, replyTo_opt = Some(sender.ref))
    sender.send(bob, c) // this will fail
    sender.expectMsg(RES_FAILURE(c, UnknownHtlcId(channelId(bob), 42)))
    awaitCond(bob.underlyingActor.nodeParams.db.pendingRelay.listPendingRelay(initialState.channelId).isEmpty)
  }

  test("recv UpdateFailHtlc") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    val fail = UpdateFailHtlc(ByteVector32.Zeroes, 1, ByteVector.fill(152)(0))
    alice ! fail
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments == initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fail)))
  }

  test("recv UpdateFailHtlc (unknown htlc id)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! UpdateFailHtlc(ByteVector32.Zeroes, 42, ByteVector.fill(152)(0))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 2
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 2
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFailMalformedHtlc") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    val fail = UpdateFailMalformedHtlc(ByteVector32.Zeroes, 1, Crypto.sha256(ByteVector.empty), FailureMessageCodecs.BADONION)
    alice ! fail
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments == initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fail)))
  }

  test("recv UpdateFailMalformedHtlc (invalid failure_code)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    val fail = UpdateFailMalformedHtlc(ByteVector32.Zeroes, 1, Crypto.sha256(ByteVector.empty), 42)
    alice ! fail
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data.toArray) === InvalidFailureCode(ByteVector32.Zeroes).getMessage)
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 2
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 2
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CMD_SIGN") { f =>
    import f._
    val sender = TestProbe()
    // we need to have something to sign so we first send a fulfill and acknowledge (=sign) it
    bob ! CMD_FULFILL_HTLC(0, r1)
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

  test("recv CMD_SIGN (no changes)") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_SIGN(replyTo_opt = Some(sender.ref))
    sender.expectNoMsg(1 second) // just ignored
    //sender.expectMsg("cannot sign when there are no changes")
  }

  test("recv CMD_SIGN (while waiting for RevokeAndAck)") { f =>
    import f._
    val sender = TestProbe()
    bob ! CMD_FULFILL_HTLC(0, r1)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob ! CMD_SIGN(replyTo_opt = Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_SIGN]]
    bob2alice.expectMsgType[CommitSig]
    awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isLeft)
    val waitForRevocation = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.left.toOption.get
    assert(waitForRevocation.reSignAsap === false)

    // actual test starts here
    bob ! CMD_SIGN(replyTo_opt = Some(sender.ref))
    sender.expectNoMsg(300 millis)
    assert(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo === Left(waitForRevocation))
  }

  test("recv CommitSig") { f =>
    import f._
    bob ! CMD_FULFILL_HTLC(0, r1)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
  }

  test("recv CommitSig (no changes)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    // signature is invalid but it doesn't matter
    bob ! CommitSig(ByteVector32.Zeroes, ByteVector64.Zeroes, Nil)
    bob2alice.expectMsgType[Error]
    awaitCond(bob.stateName == CLOSING)
    bob2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CommitSig (invalid signature)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    bob ! CommitSig(ByteVector32.Zeroes, ByteVector64.Zeroes, Nil)
    bob2alice.expectMsgType[Error]
    awaitCond(bob.stateName == CLOSING)
    bob2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv RevokeAndAck (with remaining htlcs on both sides)") { f =>
    import f._
    fulfillHtlc(1, r2, bob, alice, bob2alice, alice2bob)
    // this will cause alice and bob to receive RevokeAndAcks
    crossSign(bob, alice, bob2alice, alice2bob)
    // actual test starts here
    assert(alice.stateName == SHUTDOWN)
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.spec.htlcs.size == 1)
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteCommit.spec.htlcs.size == 1)
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
    awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.spec.htlcs.size == 2)
    awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteCommit.spec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.spec.htlcs.isEmpty)
    assert(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteCommit.spec.htlcs.size == 2)
  }

  test("recv RevokeAndAck (no more htlcs on either side)") { f =>
    import f._
    fulfillHtlc(0, r1, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(1, r2, bob, alice, bob2alice, alice2bob)
    crossSign(bob, alice, bob2alice, alice2bob)
    // actual test starts here
    awaitCond(alice.stateName == NEGOTIATING)
  }

  test("recv RevokeAndAck (invalid preimage)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    bob ! CMD_FULFILL_HTLC(0, r1)
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    bob2alice.forward(alice)
    bob ! CMD_SIGN()
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
    awaitCond(bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isLeft)
    bob ! RevokeAndAck(ByteVector32.Zeroes, PrivateKey(randomBytes32), PrivateKey(randomBytes32).publicKey)
    bob2alice.expectMsgType[Error]
    awaitCond(bob.stateName == CLOSING)
    bob2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    bob2blockchain.expectMsgType[PublishAsap] // htlc success
    bob2blockchain.expectMsgType[PublishAsap] // htlc delayed
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv RevokeAndAck (unexpectedly)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    awaitCond(alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.remoteNextCommitInfo.isRight)
    alice ! RevokeAndAck(ByteVector32.Zeroes, PrivateKey(randomBytes32), PrivateKey(randomBytes32).publicKey)
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 2
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 2
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv RevokeAndAck (forward UpdateFailHtlc)") { f =>
    import f._
    bob ! CMD_FAIL_HTLC(1, Right(PermanentChannelFailure))
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
    relayerA.expectNoMsg()

    // actual test begins
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // alice will forward the fail upstream
    val forward = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFail]]
    assert(forward.result.fail === fail)
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
    relayerA.expectNoMsg()

    // actual test begins
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // alice will forward the fail upstream
    val forward = relayerA.expectMsgType[RES_ADD_SETTLED[Origin, HtlcResult.RemoteFailMalformed]]
    assert(forward.result.fail === fail)
  }

  test("recv CMD_UPDATE_FEE") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    alice ! CMD_UPDATE_FEE(FeeratePerKw(20000 sat), replyTo_opt = Some(sender.ref))
    sender.expectMsgType[RES_SUCCESS[CMD_UPDATE_FEE]]
    val fee = alice2bob.expectMsgType[UpdateFee]
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fee))))
  }

  test("recv CMD_UPDATE_FEE (when fundee)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    bob ! CMD_UPDATE_FEE(FeeratePerKw(20000 sat), replyTo_opt = Some(sender.ref))
    sender.expectMsgType[RES_FAILURE[CMD_UPDATE_FEE, FundeeCannotSendUpdateFee]]
    assert(initialState == bob.stateData)
  }

  test("recv UpdateFee") { f =>
    import f._
    val initialData = bob.stateData.asInstanceOf[DATA_SHUTDOWN]
    val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(12000 sat))
    bob ! fee
    awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(remoteChanges = initialData.commitments.remoteChanges.copy(proposed = initialData.commitments.remoteChanges.proposed :+ fee))))
  }

  test("recv UpdateFee (when sender is not funder)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! UpdateFee(ByteVector32.Zeroes, FeeratePerKw(12000 sat))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 2
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 2
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFee (sender can't afford it)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    val fee = UpdateFee(ByteVector32.Zeroes, FeeratePerKw(100000000 sat))
    // we first update the feerates so that we don't trigger a 'fee too different' error
    bob.feeEstimator.setFeerate(FeeratesPerKw.single(fee.feeratePerKw))
    bob ! fee
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === CannotAffordFees(channelId(bob), missing = 72120000L sat, reserve = 20000L sat, fees = 72400000L sat).getMessage)
    awaitCond(bob.stateName == CLOSING)
    bob2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    //bob2blockchain.expectMsgType[PublishAsap] // main delayed (removed because of the high fees)
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFee (local/remote feerates are too different)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    bob ! UpdateFee(ByteVector32.Zeroes, FeeratePerKw(65000 sat))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === "local/remote feerates are too different: remoteFeeratePerKw=65000 localFeeratePerKw=10000")
    awaitCond(bob.stateName == CLOSING)
    bob2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFee (remote feerate is too small)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    bob ! UpdateFee(ByteVector32.Zeroes, FeeratePerKw(252 sat))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data.toArray) === "remote fee rate is too small: remoteFeeratePerKw=252")
    awaitCond(bob.stateName == CLOSING)
    bob2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CMD_UPDATE_RELAY_FEE ") { f =>
    import f._
    val sender = TestProbe()
    val newFeeBaseMsat = TestConstants.Alice.nodeParams.feeBase * 2
    val newFeeProportionalMillionth = TestConstants.Alice.nodeParams.feeProportionalMillionth * 2
    alice ! CMD_UPDATE_RELAY_FEE(sender.ref, newFeeBaseMsat, newFeeProportionalMillionth)
    sender.expectMsgType[RES_FAILURE[CMD_UPDATE_RELAY_FEE, _]]
    relayerA.expectNoMsg(1 seconds)
  }

  test("recv CurrentBlockCount (no htlc timed out)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    alice ! CurrentBlockCount(400143)
    awaitCond(alice.stateData == initialState)
  }

  test("recv CurrentBlockCount (an htlc timed out)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    val aliceCommitTx = initialState.commitments.localCommit.publishableTxs.commitTx.tx
    alice ! CurrentBlockCount(400145)
    alice2blockchain.expectMsg(PublishAsap(aliceCommitTx)) // commit tx
    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout 2
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 1
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed 2
    val watch = alice2blockchain.expectMsgType[WatchConfirmed]
    assert(watch.event === BITCOIN_TX_CONFIRMED(aliceCommitTx))
  }

  test("recv CurrentFeerate (when funder, triggers an UpdateFee)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_SHUTDOWN]
    val event = CurrentFeerates(FeeratesPerKw(FeeratePerKw(50 sat), FeeratePerKw(100 sat), FeeratePerKw(200 sat), FeeratePerKw(600 sat), FeeratePerKw(1200 sat), FeeratePerKw(3600 sat), FeeratePerKw(7200 sat), FeeratePerKw(14400 sat), FeeratePerKw(100800 sat)))
    alice ! event
    alice2bob.expectMsg(UpdateFee(initialState.commitments.channelId, event.feeratesPerKw.feePerBlock(alice.feeTargets.commitmentBlockTarget)))
  }

  test("recv CurrentFeerate (when funder, doesn't trigger an UpdateFee)") { f =>
    import f._
    val event = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(10010 sat)))
    alice ! event
    alice2bob.expectNoMsg(500 millis)
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are close)") { f =>
    import f._
    val event = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(11000 sat)))
    bob ! event
    bob2alice.expectNoMsg(500 millis)
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are very different)") { f =>
    import f._
    val event = CurrentFeerates(FeeratesPerKw.single(FeeratePerKw(1000 sat)))
    bob ! event
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap] // commit tx
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv BITCOIN_FUNDING_SPENT (their commit)") { f =>
    import f._
    // bob publishes his current commit tx, which contains two pending htlcs alice->bob
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    assert(bobCommitTx.txOut.size == 4) // two main outputs and 2 pending htlcs
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)

    // in response to that, alice publishes its claim txes
    val claimTxes = for (i <- 0 until 3) yield alice2blockchain.expectMsgType[PublishAsap].tx
    // in addition to its main output, alice can only claim 2 out of 3 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the preimage
    val amountClaimed = (for (claimHtlcTx <- claimTxes) yield {
      assert(claimHtlcTx.txIn.size == 1)
      assert(claimHtlcTx.txOut.size == 1)
      Transaction.correctlySpends(claimHtlcTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      claimHtlcTx.txOut(0).amount
    }).sum
    // htlc will timeout and be eventually refunded so we have a little less than fundingSatoshis - pushMsat = 1000000 - 200000 = 800000 (because fees)
    assert(amountClaimed === 774040.sat)

    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(bobCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxes(0)))
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    alice2blockchain.expectNoMsg(1 second)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimHtlcSuccessTxs.size == 0)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimHtlcTimeoutTxs.size == 2)
  }

  test("recv BITCOIN_FUNDING_SPENT (their next commit)") { f =>
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
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    assert(bobCommitTx.txOut.size == 3) // two main outputs and 1 pending htlc
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)

    // in response to that, alice publishes its claim txes
    val claimTxes = for (i <- 0 until 2) yield alice2blockchain.expectMsgType[PublishAsap].tx
    // in addition to its main output, alice can only claim 2 out of 3 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the preimage
    val amountClaimed = (for (claimHtlcTx <- claimTxes) yield {
      assert(claimHtlcTx.txIn.size == 1)
      assert(claimHtlcTx.txOut.size == 1)
      Transaction.correctlySpends(claimHtlcTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      claimHtlcTx.txOut(0).amount
    }).sum
    // htlc will timeout and be eventually refunded so we have a little less than fundingSatoshis - pushMsat - htlc1 = 1000000 - 200000 - 300 000 = 500000 (because fees)
    assert(amountClaimed === 481210.sat)

    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(bobCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxes(0)))
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    alice2blockchain.expectNoMsg(1 second)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.isDefined)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get.claimHtlcSuccessTxs.size == 0)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get.claimHtlcTimeoutTxs.size == 1)
  }

  test("recv BITCOIN_FUNDING_SPENT (revoked tx)") { f =>
    import f._
    val revokedTx = bob.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    // two main outputs + 2 htlc
    assert(revokedTx.txOut.size == 4)

    // bob fulfills one of the pending htlc (just so that he can have a new sig)
    fulfillHtlc(0, r1, bob, alice, bob2alice, alice2bob)
    // bob and alice sign
    crossSign(bob, alice, bob2alice, alice2bob)
    // bob now has a new commitment tx

    // bob published the revoked tx
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, revokedTx)
    alice2bob.expectMsgType[Error]

    val mainTx = alice2blockchain.expectMsgType[PublishAsap].tx
    val mainPenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx
    val htlc1PenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx
    val htlc2PenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event == BITCOIN_TX_CONFIRMED(revokedTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event == BITCOIN_TX_CONFIRMED(mainTx))
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // main-penalty
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // htlc1-penalty
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // htlc2-penalty
    alice2blockchain.expectNoMsg(1 second)

    Transaction.correctlySpends(mainTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(mainPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(htlc1PenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(htlc2PenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    // two main outputs are 300 000 and 200 000, htlcs are 300 000 and 200 000
    assert(mainTx.txOut(0).amount === 284940.sat)
    assert(mainPenaltyTx.txOut(0).amount === 195160.sat)
    assert(htlc1PenaltyTx.txOut(0).amount === 194540.sat)
    assert(htlc2PenaltyTx.txOut(0).amount === 294540.sat)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)
  }

  test("recv CMD_CLOSE") { f =>
    import f._
    val sender = TestProbe()
    alice ! CMD_CLOSE(sender.ref, None)
    sender.expectMsgType[RES_FAILURE[CMD_CLOSE, ClosingAlreadyInProgress]]
  }

  test("recv Error") { f =>
    import f._
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_SHUTDOWN].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! Error(ByteVector32.Zeroes, "oops")
    alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))
    assert(aliceCommitTx.txOut.size == 4) // two main outputs and two htlcs

    // alice can claim both htlc after a timeout
    // so we expect 5 transactions:
    // - 1 tx to claim the main delayed output
    // - 2 txes for each htlc
    // - 2 txes for each delayed output of the claimed htlc
    val claimTxs = for (i <- 0 until 5) yield alice2blockchain.expectMsgType[PublishAsap].tx

    // the main delayed output spends the commitment transaction
    Transaction.correctlySpends(claimTxs(0), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    // 2nd stage transactions spend the commitment transaction
    Transaction.correctlySpends(claimTxs(1), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(claimTxs(2), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    // 3rd stage transactions spend their respective HTLC-Timeout transactions
    Transaction.correctlySpends(claimTxs(3), claimTxs(1) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(claimTxs(4), claimTxs(2) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(aliceCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxs(0))) // main-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxs(3))) // htlc-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxs(4))) // htlc-delayed
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    alice2blockchain.expectNoMsg(1 second)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)
  }

}
