/*
 * Copyright 2018 ACINQ SAS
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

import akka.actor.Status
import akka.actor.Status.Failure
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.Scalar
import fr.acinq.bitcoin.{BinaryData, Crypto, Satoshi, ScriptFlags, Transaction}
import fr.acinq.eclair.TestConstants.{Alice, Bob}
import fr.acinq.eclair.UInt64.Conversions._
import fr.acinq.eclair.blockchain._
import fr.acinq.eclair.blockchain.fee.FeeratesPerKw
import fr.acinq.eclair.channel.Channel.TickRefreshChannelUpdate
import fr.acinq.eclair.channel._
import fr.acinq.eclair.channel.Channel.{RevocationTimeout, TickRefreshChannelUpdate}
import fr.acinq.eclair.channel.states.StateTestsHelperMethods
import fr.acinq.eclair.channel.{Data, State, _}
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.payment._
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.Transactions.{htlcSuccessWeight, htlcTimeoutWeight, weight2fee}
import fr.acinq.eclair.transactions.{IN, OUT}
import fr.acinq.eclair.wire.{AnnouncementSignatures, ChannelUpdate, ClosingSigned, CommitSig, Error, FailureMessageCodecs, PermanentChannelFailure, RevokeAndAck, Shutdown, UpdateAddHtlc, UpdateFailHtlc, UpdateFailMalformedHtlc, UpdateFee, UpdateFulfillHtlc}
import fr.acinq.eclair.{Globals, TestConstants, TestkitBaseClass}
import org.scalatest.{Outcome, Tag}

import scala.concurrent.duration._

/**
  * Created by PM on 05/07/2016.
  */

class NormalStateSpec extends TestkitBaseClass with StateTestsHelperMethods {

  type FixtureParam = SetupFixture

  override def withFixture(test: OneArgTest): Outcome = {
    val setup = init()
    import setup._
    within(30 seconds) {
      reachNormal(setup, test.tags)
      awaitCond(alice.stateName == NORMAL)
      awaitCond(bob.stateName == NORMAL)
      withFixture(test.toNoArgTest(setup))
    }
  }

  test("recv CMD_ADD_HTLC (empty origin)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    val h = BinaryData("42" * 32)
    sender.send(alice, CMD_ADD_HTLC(50000000, h, 400144))
    sender.expectMsg("ok")
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    assert(htlc.id == 0 && htlc.paymentHash == h)
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localNextHtlcId = 1,
        localChanges = initialState.commitments.localChanges.copy(proposed = htlc :: Nil),
        originChannels = Map(0L -> Local(Some(sender.ref)))
      )))
  }

  test("recv CMD_ADD_HTLC (incrementing ids)") { f =>
    import f._
    val sender = TestProbe()
    val h = BinaryData("42" * 32)
    for (i <- 0 until 10) {
      sender.send(alice, CMD_ADD_HTLC(50000000, h, 400144))
      sender.expectMsg("ok")
      val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
      assert(htlc.id == i && htlc.paymentHash == h)
    }
  }

  test("recv CMD_ADD_HTLC (relayed htlc)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    val h = BinaryData("42" * 32)
    val originHtlc = UpdateAddHtlc(channelId = "42" * 32, id = 5656, amountMsat = 50000000, cltvExpiry = 400144, paymentHash = h, onionRoutingPacket = "00" * 1254)
    val cmd = CMD_ADD_HTLC(originHtlc.amountMsat - 10000, h, originHtlc.cltvExpiry - 7, upstream_opt = Some(originHtlc))
    sender.send(alice, cmd)
    sender.expectMsg("ok")
    val htlc = alice2bob.expectMsgType[UpdateAddHtlc]
    assert(htlc.id == 0 && htlc.paymentHash == h)
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localNextHtlcId = 1,
        localChanges = initialState.commitments.localChanges.copy(proposed = htlc :: Nil),
        originChannels = Map(0L -> Relayed(originHtlc.channelId, originHtlc.id, originHtlc.amountMsat, htlc.amountMsat))
      )))
  }

  test("recv CMD_ADD_HTLC (invalid payment hash)") { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(500000000, "11" * 42, cltvExpiry = 400144)
    sender.send(alice, add)
    val error = InvalidPaymentHash(channelId(alice))
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add))))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_HTLC (expiry too small)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val currentBlockCount = Globals.blockCount.get
    val expiryTooSmall = currentBlockCount + 3
    val add = CMD_ADD_HTLC(500000000, "11" * 32, cltvExpiry = expiryTooSmall)
    sender.send(alice, add)
    val error = ExpiryTooSmall(channelId(alice), currentBlockCount + Channel.MIN_CLTV_EXPIRY, expiryTooSmall, currentBlockCount)
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add))))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_HTLC (expiry too big)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val currentBlockCount = Globals.blockCount.get
    val expiryTooBig = currentBlockCount + Channel.MAX_CLTV_EXPIRY + 1
    val add = CMD_ADD_HTLC(500000000, "11" * 32, cltvExpiry = expiryTooBig)
    sender.send(alice, add)
    val error = ExpiryTooBig(channelId(alice), maximum = currentBlockCount + Channel.MAX_CLTV_EXPIRY, actual = expiryTooBig, blockCount = currentBlockCount)
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add))))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_HTLC (value too small)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val add = CMD_ADD_HTLC(50, "11" * 32, 400144)
    sender.send(alice, add)
    val error = HtlcValueTooSmall(channelId(alice), 1000, 50)
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add))))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_HTLC (insufficient funds)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val add = CMD_ADD_HTLC(Int.MaxValue, "11" * 32, 400144)
    sender.send(alice, add)
    val error = InsufficientFunds(channelId(alice), amountMsat = Int.MaxValue, missingSatoshis = 1376443, reserveSatoshis = 20000, feesSatoshis = 8960)
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add))))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_HTLC (insufficient funds w/ pending htlcs and 0 balance)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(alice, CMD_ADD_HTLC(500000000, "11" * 32, 400144))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    sender.send(alice, CMD_ADD_HTLC(200000000, "22" * 32, 400144))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    sender.send(alice, CMD_ADD_HTLC(67600000, "33" * 32, 400144))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    val add = CMD_ADD_HTLC(1000000, "44" * 32, 400144)
    sender.send(alice, add)
    val error = InsufficientFunds(channelId(alice), amountMsat = 1000000, missingSatoshis = 1000, reserveSatoshis = 20000, feesSatoshis = 12400)
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add))))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_HTLC (insufficient funds w/ pending htlcs 2/2)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(alice, CMD_ADD_HTLC(300000000, "11" * 32, 400144))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    sender.send(alice, CMD_ADD_HTLC(300000000, "22" * 32, 400144))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    val add = CMD_ADD_HTLC(500000000, "33" * 32, 400144)
    sender.send(alice, add)
    val error = InsufficientFunds(channelId(alice), amountMsat = 500000000, missingSatoshis = 332400, reserveSatoshis = 20000, feesSatoshis = 12400)
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add))))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_HTLC (over max inflight htlc value)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    val add = CMD_ADD_HTLC(151000000, "11" * 32, 400144)
    sender.send(bob, add)
    val error = HtlcValueTooHighInFlight(channelId(bob), maximum = 150000000, actual = 151000000)
    sender.expectMsg(Failure(AddHtlcFailed(channelId(bob), add.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add))))
    bob2alice.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_HTLC (over max accepted htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    // Bob accepts a maximum of 30 htlcs
    for (i <- 0 until 30) {
      sender.send(alice, CMD_ADD_HTLC(10000000, "11" * 32, 400144))
      sender.expectMsg("ok")
      alice2bob.expectMsgType[UpdateAddHtlc]
    }
    val add = CMD_ADD_HTLC(10000000, "33" * 32, 400144)
    sender.send(alice, add)
    val error = TooManyAcceptedHtlcs(channelId(alice), maximum = 30)
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add))))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_HTLC (over capacity)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val add1 = CMD_ADD_HTLC(TestConstants.fundingSatoshis * 2 / 3 * 1000, "11" * 32, 400144)
    sender.send(alice, add1)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    // this is over channel-capacity
    val add2 = CMD_ADD_HTLC(TestConstants.fundingSatoshis * 2 / 3 * 1000, "22" * 32, 400144)
    sender.send(alice, add2)
    val error = InsufficientFunds(channelId(alice), add2.amountMsat, 564012, 20000, 10680)
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add2.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add2))))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_HTLC (after having sent Shutdown)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined && !alice.stateData.asInstanceOf[DATA_NORMAL].remoteShutdown.isDefined)

    // actual test starts here
    val add = CMD_ADD_HTLC(500000000, "11" * 32, cltvExpiry = 400144)
    sender.send(alice, add)
    val error = NoMoreHtlcsClosingInProgress(channelId(alice))
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add))))
    alice2bob.expectNoMsg(200 millis)
  }

  test("recv CMD_ADD_HTLC (after having received Shutdown)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    // let's make alice send an htlc
    val add1 = CMD_ADD_HTLC(500000000, "11" * 32, cltvExpiry = 400144)
    sender.send(alice, add1)
    sender.expectMsg("ok")
    // at the same time bob initiates a closing
    sender.send(bob, CMD_CLOSE(None))
    sender.expectMsg("ok")
    // this command will be received by alice right after having received the shutdown
    val add2 = CMD_ADD_HTLC(100000000, "22" * 32, cltvExpiry = 300000)
    // messages cross
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[Shutdown]
    bob2alice.forward(alice)
    sender.send(alice, add2)
    val error = NoMoreHtlcsClosingInProgress(channelId(alice))
    sender.expectMsg(Failure(AddHtlcFailed(channelId(alice), add2.paymentHash, error, Local(Some(sender.ref)), Some(initialState.channelUpdate), Some(add2))))
  }

  test("recv UpdateAddHtlc") { f =>
    import f._
    val initialData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val htlc = UpdateAddHtlc("00" * 32, 0, 150000, BinaryData("42" * 32), 400144, defaultOnion)
    bob ! htlc
    awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(remoteChanges = initialData.commitments.remoteChanges.copy(proposed = initialData.commitments.remoteChanges.proposed :+ htlc), remoteNextHtlcId = 1)))
    // bob won't forward the add before it is cross-signed
    relayerB.expectNoMsg()
  }

  test("recv UpdateAddHtlc (unexpected id)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val htlc = UpdateAddHtlc("00" * 32, 42, 150000, BinaryData("42" * 32), 400144, defaultOnion)
    bob ! htlc.copy(id = 0)
    bob ! htlc.copy(id = 1)
    bob ! htlc.copy(id = 2)
    bob ! htlc.copy(id = 3)
    bob ! htlc.copy(id = 42)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data) === UnexpectedHtlcId(channelId(bob), expected = 4, actual = 42).getMessage)
    awaitCond(bob.stateName == CLOSING)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddHtlc (invalid payment hash)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val htlc = UpdateAddHtlc("00" * 32, 0, 150000, "11" * 42, 400144, defaultOnion)
    alice2bob.forward(bob, htlc)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data) === InvalidPaymentHash(channelId(bob)).getMessage)
    awaitCond(bob.stateName == CLOSING)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddHtlc (value too small)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val htlc = UpdateAddHtlc("00" * 32, 0, 150, BinaryData("42" * 32), cltvExpiry = 400144, defaultOnion)
    alice2bob.forward(bob, htlc)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data) === HtlcValueTooSmall(channelId(bob), minimum = 1000, actual = 150).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddHtlc (insufficient funds)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val htlc = UpdateAddHtlc("00" * 32, 0, Long.MaxValue, BinaryData("42" * 32), 400144, defaultOnion)
    alice2bob.forward(bob, htlc)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data) === InsufficientFunds(channelId(bob), amountMsat = Long.MaxValue, missingSatoshis = 9223372036083735L, reserveSatoshis = 20000, feesSatoshis = 8960).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddHtlc (insufficient funds w/ pending htlcs 1/2)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice2bob.forward(bob, UpdateAddHtlc("00" * 32, 0, 400000000, "11" * 32, 400144, defaultOnion))
    alice2bob.forward(bob, UpdateAddHtlc("00" * 32, 1, 200000000, "22" * 32, 400144, defaultOnion))
    alice2bob.forward(bob, UpdateAddHtlc("00" * 32, 2, 167600000, "33" * 32, 400144, defaultOnion))
    alice2bob.forward(bob, UpdateAddHtlc("00" * 32, 3, 10000000, "44" * 32, 400144, defaultOnion))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data) === InsufficientFunds(channelId(bob), amountMsat = 10000000, missingSatoshis = 11720, reserveSatoshis = 20000, feesSatoshis = 14120).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddHtlc (insufficient funds w/ pending htlcs 2/2)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice2bob.forward(bob, UpdateAddHtlc("00" * 32, 0, 300000000, "11" * 32, 400144, defaultOnion))
    alice2bob.forward(bob, UpdateAddHtlc("00" * 32, 1, 300000000, "22" * 32, 400144, defaultOnion))
    alice2bob.forward(bob, UpdateAddHtlc("00" * 32, 2, 500000000, "33" * 32, 400144, defaultOnion))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data) === InsufficientFunds(channelId(bob), amountMsat = 500000000, missingSatoshis = 332400, reserveSatoshis = 20000, feesSatoshis = 12400).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddHtlc (over max inflight htlc value)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice2bob.forward(alice, UpdateAddHtlc("00" * 32, 0, 151000000, "11" * 32, 400144, defaultOnion))
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data) === HtlcValueTooHighInFlight(channelId(alice), maximum = 150000000, actual = 151000000).getMessage)
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateAddHtlc (over max accepted htlcs)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    // Bob accepts a maximum of 30 htlcs
    for (i <- 0 until 30) {
      alice2bob.forward(bob, UpdateAddHtlc("00" * 32, i, 1000000, "11" * 32, 400144, defaultOnion))
    }
    alice2bob.forward(bob, UpdateAddHtlc("00" * 32, 30, 1000000, "11" * 32, 400144, defaultOnion))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data) === TooManyAcceptedHtlcs(channelId(bob), maximum = 30).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CMD_SIGN") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    val commitSig = alice2bob.expectMsgType[CommitSig]
    assert(commitSig.htlcSignatures.size == 1)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
  }

  test("recv CMD_SIGN (two identical htlcs in each direction)") { f =>
    import f._
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(10000000, "11" * 32, 400144)
    sender.send(alice, add)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    sender.send(alice, add)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    crossSign(alice, bob, alice2bob, bob2alice)

    sender.send(bob, add)
    sender.expectMsg("ok")
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)
    sender.send(bob, add)
    sender.expectMsg("ok")
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)

    // actual test starts here
    sender.send(bob, CMD_SIGN)
    sender.expectMsg("ok")
    val commitSig = bob2alice.expectMsgType[CommitSig]
    assert(commitSig.htlcSignatures.toSet.size == 4)
  }

  test("recv CMD_SIGN (check htlc info are persisted)") { f =>
    import f._
    val sender = TestProbe()
    // for the test to be really useful we have constraint on parameters
    assert(Alice.nodeParams.dustLimitSatoshis > Bob.nodeParams.dustLimitSatoshis)
    // we're gonna exchange two htlcs in each direction, the goal is to have bob's commitment have 4 htlcs, and alice's
    // commitment only have 3. We will then check that alice indeed persisted 4 htlcs, and bob only 3.
    val aliceMinReceive = Alice.nodeParams.dustLimitSatoshis + weight2fee(TestConstants.feeratePerKw, htlcSuccessWeight).toLong
    val aliceMinOffer = Alice.nodeParams.dustLimitSatoshis + weight2fee(TestConstants.feeratePerKw, htlcTimeoutWeight).toLong
    val bobMinReceive = Bob.nodeParams.dustLimitSatoshis + weight2fee(TestConstants.feeratePerKw, htlcSuccessWeight).toLong
    val bobMinOffer = Bob.nodeParams.dustLimitSatoshis + weight2fee(TestConstants.feeratePerKw, htlcTimeoutWeight).toLong
    val a2b_1 = bobMinReceive + 10 // will be in alice and bob tx
  val a2b_2 = bobMinReceive + 20 // will be in alice and bob tx
  val b2a_1 = aliceMinReceive + 10 // will be in alice and bob tx
  val b2a_2 = bobMinOffer + 10 // will be only be in bob tx
    assert(a2b_1 > aliceMinOffer && a2b_1 > bobMinReceive)
    assert(a2b_2 > aliceMinOffer && a2b_2 > bobMinReceive)
    assert(b2a_1 > aliceMinReceive && b2a_1 > bobMinOffer)
    assert(b2a_2 < aliceMinReceive && b2a_2 > bobMinOffer)
    sender.send(alice, CMD_ADD_HTLC(a2b_1 * 1000, "11" * 32, 400144))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    sender.send(alice, CMD_ADD_HTLC(a2b_2 * 1000, "22" * 32, 400144))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    sender.send(bob, CMD_ADD_HTLC(b2a_1 * 1000, "33" * 32, 400144))
    sender.expectMsg("ok")
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)
    sender.send(bob, CMD_ADD_HTLC(b2a_2 * 1000, "44" * 32, 400144))
    sender.expectMsg("ok")
    bob2alice.expectMsgType[UpdateAddHtlc]
    bob2alice.forward(alice)

    // actual test starts here
    crossSign(alice, bob, alice2bob, bob2alice)
    // depending on who starts signing first, there will be one or two commitments because both sides have changes
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.index === 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.index === 2)
    assert(alice.underlyingActor.nodeParams.channelsDb.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 0).size == 0)
    assert(alice.underlyingActor.nodeParams.channelsDb.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 1).size == 2)
    assert(alice.underlyingActor.nodeParams.channelsDb.listHtlcInfos(alice.stateData.asInstanceOf[DATA_NORMAL].channelId, 2).size == 4)
    assert(bob.underlyingActor.nodeParams.channelsDb.listHtlcInfos(bob.stateData.asInstanceOf[DATA_NORMAL].channelId, 0).size == 0)
    assert(bob.underlyingActor.nodeParams.channelsDb.listHtlcInfos(bob.stateData.asInstanceOf[DATA_NORMAL].channelId, 1).size == 3)
  }

  test("recv CMD_SIGN (htlcs with same pubkeyScript but different amounts)") { f =>
    import f._
    val sender = TestProbe()
    val add = CMD_ADD_HTLC(10000000, "11" * 32, 400144)
    val epsilons = List(3, 1, 5, 7, 6) // unordered on purpose
  val htlcCount = epsilons.size
    for (i <- epsilons) {
      sender.send(alice, add.copy(amountMsat = add.amountMsat + i * 1000))
      sender.expectMsg("ok")
      alice2bob.expectMsgType[UpdateAddHtlc]
      alice2bob.forward(bob)
    }
    // actual test starts here
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    val commitSig = alice2bob.expectMsgType[CommitSig]
    assert(commitSig.htlcSignatures.toSet.size == htlcCount)
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == htlcCount)
    val htlcTxs = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs
    val amounts = htlcTxs.map(_.txinfo.tx.txOut.head.amount.toLong)
    assert(amounts === amounts.sorted)
  }

  test("recv CMD_SIGN (no changes)") { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, CMD_SIGN)
    sender.expectNoMsg(1 second) // just ignored
    //sender.expectMsg("cannot sign when there are no changes")
  }

  test("recv CMD_SIGN (while waiting for RevokeAndAck (no pending changes)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    val waitForRevocation = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.left.toOption.get
    assert(waitForRevocation.reSignAsap === false)

    // actual test starts here
    sender.send(alice, CMD_SIGN)
    sender.expectNoMsg(300 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo === Left(waitForRevocation))
  }

  test("recv CMD_SIGN (while waiting for RevokeAndAck (with pending changes)") { f =>
    import f._
    val sender = TestProbe()
    val (r1, htlc1) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    val waitForRevocation = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.left.toOption.get
    assert(waitForRevocation.reSignAsap === false)

    // actual test starts here
    val (r2, htlc2) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectNoMsg(300 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo === Left(waitForRevocation.copy(reSignAsap = true)))
  }

  test("recv CMD_SIGN (going above reserve)", Tag("no_push_msat")) { f =>
    import f._
    val sender = TestProbe()
    // channel starts with all funds on alice's side, so channel will be initially disabled on bob's side
    assert(Announcements.isEnabled(bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.channelFlags) === false)
    // alice will send enough funds to bob to make it go above reserve
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    sender.send(bob, CMD_FULFILL_HTLC(htlc.id, r))
    sender.expectMsg("ok")
    bob2alice.expectMsgType[UpdateFulfillHtlc]
    // we listen to channel_update events
    val listener = TestProbe()
    system.eventStream.subscribe(listener.ref, classOf[LocalChannelUpdate])

    // actual test starts here
    // when signing the fulfill, bob will have its main output go above reserve in alice's commitment tx
    sender.send(bob, CMD_SIGN)
    sender.expectMsg("ok")
    bob2alice.expectMsgType[CommitSig]
    // it should update its channel_update
    awaitCond(Announcements.isEnabled(bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate.channelFlags) == true)
    // and broadcast it
    assert(listener.expectMsgType[LocalChannelUpdate].channelUpdate === bob.stateData.asInstanceOf[DATA_NORMAL].channelUpdate)
  }

  test("recv CommitSig (one htlc received)") { f =>
    import f._
    val sender = TestProbe()

    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")

    // actual test begins
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    bob2alice.expectMsgType[RevokeAndAck]
    // bob replies immediately with a signature
    bob2alice.expectMsgType[CommitSig]

    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.exists(h => h.add.id == htlc.id && h.direction == IN))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.toLocalMsat == initialState.commitments.localCommit.spec.toLocalMsat)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.acked.size == 0)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.signed.size == 1)
  }

  test("recv CommitSig (one htlc sent)") { f =>
    import f._
    val sender = TestProbe()

    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)

    // actual test begins (note that channel sends a CMD_SIGN to itself when it receives RevokeAndAck and there are changes)
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.exists(h => h.add.id == htlc.id && h.direction == OUT))
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.toLocalMsat == initialState.commitments.localCommit.spec.toLocalMsat)
  }

  test("recv CommitSig (multiple htlcs in both directions)") { f =>
    import f._
    val sender = TestProbe()

    val (r1, htlc1) = addHtlc(50000000, alice, bob, alice2bob, bob2alice) // a->b (regular)

    val (r2, htlc2) = addHtlc(8000000, alice, bob, alice2bob, bob2alice) //  a->b (regular)

    val (r3, htlc3) = addHtlc(300000, bob, alice, bob2alice, alice2bob) //   b->a (dust)

    val (r4, htlc4) = addHtlc(1000000, alice, bob, alice2bob, bob2alice) //  a->b (regular)

    val (r5, htlc5) = addHtlc(50000000, bob, alice, bob2alice, alice2bob) // b->a (regular)

    val (r6, htlc6) = addHtlc(500000, alice, bob, alice2bob, bob2alice) //   a->b (dust)

    val (r7, htlc7) = addHtlc(4000000, bob, alice, bob2alice, alice2bob) //  b->a (regular)

    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)

    // actual test begins
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.index == 1)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 3)
  }

  test("recv CommitSig (only fee update)") { f =>
    import f._
    val sender = TestProbe()

    sender.send(alice, CMD_UPDATE_FEE(TestConstants.feeratePerKw + 1000, commit = false))
    sender.expectMsg("ok")
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")

    // actual test begins (note that channel sends a CMD_SIGN to itself when it receives RevokeAndAck and there are changes)
    alice2bob.expectMsgType[UpdateFee]
    alice2bob.forward(bob)
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
  }

  test("recv CommitSig (two htlcs received with same r)") { f =>
    import f._
    val sender = TestProbe()
    val r = BinaryData("42" * 32)
    val h: BinaryData = Crypto.sha256(r)

    sender.send(alice, CMD_ADD_HTLC(50000000, h, 400144))
    sender.expectMsg("ok")
    val htlc1 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    sender.send(alice, CMD_ADD_HTLC(50000000, h, 400144))
    sender.expectMsg("ok")
    val htlc2 = alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteChanges.proposed == htlc1 :: htlc2 :: Nil)
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    crossSign(alice, bob, alice2bob, bob2alice)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.htlcs.exists(h => h.add.id == htlc1.id && h.direction == IN))
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.htlcTxsAndSigs.size == 2)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.spec.toLocalMsat == initialState.commitments.localCommit.spec.toLocalMsat)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx.txOut.count(_.amount == Satoshi(50000)) == 2)
  }

  test("recv CommitSig (no changes)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    // signature is invalid but it doesn't matter
    sender.send(bob, CommitSig("00" * 32, "00" * 64, Nil))
    bob2alice.expectMsgType[Error]
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CommitSig (invalid signature)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    // actual test begins
    sender.send(bob, CommitSig("00" * 32, "00" * 64, Nil))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data).startsWith("invalid commitment signature"))
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CommitSig (bad htlc sig count)") { f =>
    import f._
    val sender = TestProbe()

    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    val commitSig = alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val badCommitSig = commitSig.copy(htlcSignatures = commitSig.htlcSignatures ::: commitSig.htlcSignatures)
    sender.send(bob, badCommitSig)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data) === HtlcSigCountMismatch(channelId(bob), expected = 1, actual = 2).getMessage)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CommitSig (invalid htlc sig)") { f =>
    import f._
    val sender = TestProbe()

    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    val commitSig = alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val badCommitSig = commitSig.copy(htlcSignatures = commitSig.signature :: Nil)
    sender.send(bob, badCommitSig)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data).startsWith("invalid htlc signature"))
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }


  test("recv RevokeAndAck (one htlc sent)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)

    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // actual test begins
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localChanges.acked.size == 1)
  }

  test("recv RevokeAndAck (one htlc received)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)

    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    // at this point bob still hasn't forwarded the htlc downstream
    relayerB.expectNoMsg()

    // actual test begins
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    // now bob will forward the htlc downstream
    val forward = relayerB.expectMsgType[ForwardAdd]
    assert(forward.add === htlc)

  }

  test("recv RevokeAndAck (multiple htlcs in both directions)") { f =>
    import f._
    val sender = TestProbe()
    val (r1, htlc1) = addHtlc(50000000, alice, bob, alice2bob, bob2alice) // a->b (regular)

    val (r2, htlc2) = addHtlc(8000000, alice, bob, alice2bob, bob2alice) //  a->b (regular)

    val (r3, htlc3) = addHtlc(300000, bob, alice, bob2alice, alice2bob) //   b->a (dust)

    val (r4, htlc4) = addHtlc(1000000, alice, bob, alice2bob, bob2alice) //  a->b (regular)

    val (r5, htlc5) = addHtlc(50000000, bob, alice, bob2alice, alice2bob) // b->a (regular)

    val (r6, htlc6) = addHtlc(500000, alice, bob, alice2bob, bob2alice) //   a->b (dust)

    val (r7, htlc7) = addHtlc(4000000, bob, alice, bob2alice, alice2bob) //  b->a (regular)

    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)

    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)

    // actual test begins
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)

    awaitCond(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommit.index == 1)
    assert(bob.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommit.spec.htlcs.size == 7)
  }

  test("recv RevokeAndAck (with reSignAsap=true)") { f =>
    import f._
    val sender = TestProbe()
    val (r1, htlc1) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    val (r2, htlc2) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectNoMsg(300 millis)
    assert(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.left.toOption.get.reSignAsap === true)

    // actual test starts here
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    alice2bob.expectMsgType[CommitSig]
  }

  test("recv RevokeAndAck (invalid preimage)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)

    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // actual test begins
    bob2alice.expectMsgType[RevokeAndAck]
    sender.send(alice, RevokeAndAck("00" * 32, Scalar("11" * 32), Scalar("22" * 32).toPoint))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv RevokeAndAck (unexpectedly)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isRight)
    sender.send(alice, RevokeAndAck("00" * 32, Scalar("11" * 32), Scalar("22" * 32).toPoint))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv RevokeAndAck (forward UpdateFailHtlc)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    sender.send(bob, CMD_FAIL_HTLC(htlc.id, Right(PermanentChannelFailure)))
    sender.expectMsg("ok")
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]
    bob2alice.forward(alice)
    sender.send(bob, CMD_SIGN)
    sender.expectMsg("ok")
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
    val forward = relayerA.expectMsgType[ForwardFail]
    assert(forward.fail === fail)
    assert(forward.htlc === htlc)
  }

  test("recv RevokeAndAck (forward UpdateFailMalformedHtlc)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    sender.send(bob, CMD_FAIL_MALFORMED_HTLC(htlc.id, Crypto.sha256(htlc.onionRoutingPacket), FailureMessageCodecs.BADONION))
    sender.expectMsg("ok")
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    bob2alice.forward(alice)
    sender.send(bob, CMD_SIGN)
    sender.expectMsg("ok")
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
    val forward = relayerA.expectMsgType[ForwardFailMalformed]
    assert(forward.fail === fail)
    assert(forward.htlc === htlc)
  }

  test("recv RevocationTimeout") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)

    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)

    // actual test begins
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteNextCommitInfo.isLeft)
    val peer = TestProbe()
    sender.send(alice, RevocationTimeout(alice.stateData.asInstanceOf[DATA_NORMAL].commitments.remoteCommit.index, peer.ref))
    peer.expectMsg(Peer.Disconnect)
  }

  test("recv CMD_FULFILL_HTLC") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(bob, CMD_FULFILL_HTLC(htlc.id, r))
    sender.expectMsg("ok")
    val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]
    awaitCond(bob.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fulfill))))
  }

  test("recv CMD_FULFILL_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val r: BinaryData = "11" * 32
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    sender.send(bob, CMD_FULFILL_HTLC(42, r))
    sender.expectMsg(Failure(UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FULFILL_HTLC (invalid preimage)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(bob, CMD_FULFILL_HTLC(htlc.id, "00" * 32))
    sender.expectMsg(Failure(InvalidHtlcPreimage(channelId(bob), 0)))
    assert(initialState == bob.stateData)
  }

  test("recv UpdateFulfillHtlc") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    sender.send(bob, CMD_FULFILL_HTLC(htlc.id, r))
    sender.expectMsg("ok")
    val fulfill = bob2alice.expectMsgType[UpdateFulfillHtlc]

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    bob2alice.forward(alice)
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fulfill))))
    // alice immediately propagates the fulfill upstream
    val forward = relayerA.expectMsgType[ForwardFulfill]
    assert(forward.fulfill === fulfill)
    assert(forward.htlc === htlc)
  }

  test("recv UpdateFulfillHtlc (sender has not signed htlc)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    sender.send(alice, UpdateFulfillHtlc("00" * 32, htlc.id, r))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFulfillHtlc (unknown htlc id)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    sender.send(alice, UpdateFulfillHtlc("00" * 32, 42, "00" * 32))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFulfillHtlc (invalid preimage)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    relayerB.expectMsgType[ForwardAdd]
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    // actual test begins
    sender.send(alice, UpdateFulfillHtlc("00" * 32, htlc.id, "00" * 32))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CMD_FAIL_HTLC") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(bob, CMD_FAIL_HTLC(htlc.id, Right(PermanentChannelFailure)))
    sender.expectMsg("ok")
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]
    awaitCond(bob.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fail))))
  }

  test("recv CMD_FAIL_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val r: BinaryData = "11" * 32
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]

    sender.send(bob, CMD_FAIL_HTLC(42, Right(PermanentChannelFailure)))
    sender.expectMsg(Failure(UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FAIL_MALFORMED_HTLC") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(bob, CMD_FAIL_MALFORMED_HTLC(htlc.id, Crypto.sha256(htlc.onionRoutingPacket), FailureMessageCodecs.BADONION))
    sender.expectMsg("ok")
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    awaitCond(bob.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fail))))
  }

  test("recv CMD_FAIL_MALFORMED_HTLC (unknown htlc id)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(bob, CMD_FAIL_MALFORMED_HTLC(42, "00" * 32, FailureMessageCodecs.BADONION))
    sender.expectMsg(Failure(UnknownHtlcId(channelId(bob), 42)))
    assert(initialState == bob.stateData)
  }

  test("recv CMD_FAIL_HTLC (invalid failure_code)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(bob, CMD_FAIL_MALFORMED_HTLC(42, "00" * 32, 42))
    sender.expectMsg(Failure(InvalidFailureCode(channelId(bob))))
    assert(initialState == bob.stateData)
  }

  test("recv UpdateFailHtlc") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    sender.send(bob, CMD_FAIL_HTLC(htlc.id, Right(PermanentChannelFailure)))
    sender.expectMsg("ok")
    val fail = bob2alice.expectMsgType[UpdateFailHtlc]

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    bob2alice.forward(alice)
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fail))))
    // alice won't forward the fail before it is cross-signed
    relayerA.expectNoMsg()
  }

  test("recv UpdateFailMalformedHtlc") { f =>
    import f._
    val sender = TestProbe()

    // Alice sends an HTLC to Bob, which they both sign
    val (_, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    // Bob fails the HTLC because he cannot parse it
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(bob, CMD_FAIL_MALFORMED_HTLC(htlc.id, Crypto.sha256(htlc.onionRoutingPacket), FailureMessageCodecs.BADONION))
    sender.expectMsg("ok")
    val fail = bob2alice.expectMsgType[UpdateFailMalformedHtlc]
    bob2alice.forward(alice)

    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(remoteChanges = initialState.commitments.remoteChanges.copy(initialState.commitments.remoteChanges.proposed :+ fail))))
    // alice won't forward the fail before it is cross-signed
    relayerA.expectNoMsg()

    sender.send(bob, CMD_SIGN)
    val sig = bob2alice.expectMsgType[CommitSig]
    // Bob should not have the htlc in its remote commit anymore
    assert(sig.htlcSignatures.isEmpty)

    // and Alice should accept this signature
    bob2alice.forward(alice)
    alice2bob.expectMsgType[RevokeAndAck]
  }

  test("recv UpdateFailMalformedHtlc (invalid failure_code)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val fail = UpdateFailMalformedHtlc("00" * 32, htlc.id, Crypto.sha256(htlc.onionRoutingPacket), 42)
    sender.send(alice, fail)
    val error = alice2bob.expectMsgType[Error]
    assert(new String(error.data) === InvalidFailureCode("00" * 32).getMessage)
    awaitCond(alice.stateName == CLOSING)
    alice2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFailHtlc (sender has not signed htlc)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]

    // actual test begins
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    sender.send(alice, UpdateFailHtlc("00" * 32, htlc.id, "00" * 152))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFailHtlc (unknown htlc id)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    sender.send(alice, UpdateFailHtlc("00" * 32, 42, "00" * 152))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CMD_UPDATE_FEE") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(alice, CMD_UPDATE_FEE(20000))
    sender.expectMsg("ok")
    val fee = alice2bob.expectMsgType[UpdateFee]
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fee))))
  }

  test("recv CMD_UPDATE_FEE (two in a row)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(alice, CMD_UPDATE_FEE(20000))
    sender.expectMsg("ok")
    val fee1 = alice2bob.expectMsgType[UpdateFee]
    sender.send(alice, CMD_UPDATE_FEE(30000))
    sender.expectMsg("ok")
    val fee2 = alice2bob.expectMsgType[UpdateFee]
    awaitCond(alice.stateData == initialState.copy(
      commitments = initialState.commitments.copy(
        localChanges = initialState.commitments.localChanges.copy(initialState.commitments.localChanges.proposed :+ fee2))))
  }

  test("recv CMD_UPDATE_FEE (when fundee)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = bob.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(bob, CMD_UPDATE_FEE(20000))
    sender.expectMsg(Failure(FundeeCannotSendUpdateFee(channelId(bob))))
    assert(initialState == bob.stateData)
  }

  test("recv UpdateFee") { f =>
    import f._
    val initialData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val fee1 = UpdateFee("00" * 32, 12000)
    bob ! fee1
    val fee2 = UpdateFee("00" * 32, 14000)
    bob ! fee2
    awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(remoteChanges = initialData.commitments.remoteChanges.copy(proposed = initialData.commitments.remoteChanges.proposed :+ fee2), remoteNextHtlcId = 0)))
  }

  test("recv UpdateFee (two in a row)") { f =>
    import f._
    val initialData = bob.stateData.asInstanceOf[DATA_NORMAL]
    val fee = UpdateFee("00" * 32, 12000)
    bob ! fee
    awaitCond(bob.stateData == initialData.copy(commitments = initialData.commitments.copy(remoteChanges = initialData.commitments.remoteChanges.copy(proposed = initialData.commitments.remoteChanges.proposed :+ fee), remoteNextHtlcId = 0)))
  }

  test("recv UpdateFee (when sender is not funder)") { f =>
    import f._
    val tx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    sender.send(alice, UpdateFee("00" * 32, 12000))
    alice2bob.expectMsgType[Error]
    awaitCond(alice.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_CLOSING].channelId)
    alice2blockchain.expectMsg(PublishAsap(tx))
    alice2blockchain.expectMsgType[PublishAsap]
    alice2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFee (sender can't afford it)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    val fee = UpdateFee("00" * 32, 100000000)
    // we first update the global variable so that we don't trigger a 'fee too different' error
    Globals.feeratesPerKw.set(FeeratesPerKw.single(fee.feeratePerKw))
    sender.send(bob, fee)
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data) === CannotAffordFees(channelId(bob), missingSatoshis = 71620000L, reserveSatoshis = 20000L, feesSatoshis = 72400000L).getMessage)
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx)) // commit tx
    //bob2blockchain.expectMsgType[PublishAsap] // main delayed (removed because of the high fees)
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFee (local/remote feerates are too different)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    sender.send(bob, UpdateFee("00" * 32, 85000))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data) === "local/remote feerates are too different: remoteFeeratePerKw=85000 localFeeratePerKw=10000")
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv UpdateFee (remote feerate is too small)") { f =>
    import f._
    val tx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    val sender = TestProbe()
    sender.send(bob, UpdateFee("00" * 32, 252))
    val error = bob2alice.expectMsgType[Error]
    assert(new String(error.data) === "remote fee rate is too small: remoteFeeratePerKw=252")
    awaitCond(bob.stateName == CLOSING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === bob.stateData.asInstanceOf[DATA_CLOSING].channelId)
    bob2blockchain.expectMsg(PublishAsap(tx))
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
  }

  test("recv CMD_UPDATE_RELAY_FEE ") { f =>
    import f._
    val sender = TestProbe()
    val newFeeBaseMsat = TestConstants.Alice.nodeParams.feeBaseMsat * 2
    val newFeeProportionalMillionth = TestConstants.Alice.nodeParams.feeProportionalMillionth * 2
    sender.send(alice, CMD_UPDATE_RELAY_FEE(newFeeBaseMsat, newFeeProportionalMillionth))
    sender.expectMsg("ok")

    val localUpdate = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(localUpdate.channelUpdate.feeBaseMsat == newFeeBaseMsat)
    assert(localUpdate.channelUpdate.feeProportionalMillionths == newFeeProportionalMillionth)
    relayerA.expectNoMsg(1 seconds)
  }

  test("recv CMD_CLOSE (no pending htlcs)") { f =>
    import f._
    val sender = TestProbe()
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isEmpty)
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
  }

  test("recv CMD_CLOSE (with unacked sent htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg(Failure(CannotCloseWithUnsignedOutgoingHtlcs(channelId(bob))))
  }

  test("recv CMD_CLOSE (with invalid final script)") { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, CMD_CLOSE(Some(BinaryData("00112233445566778899"))))
    sender.expectMsg(Failure(InvalidFinalScript(channelId(alice))))
  }

  test("recv CMD_CLOSE (with signed sent htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
  }

  test("recv CMD_CLOSE (two in a row)") { f =>
    import f._
    val sender = TestProbe()
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isEmpty)
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].localShutdown.isDefined)
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg(Failure(ClosingAlreadyInProgress(channelId(alice))))
  }

  test("recv CMD_CLOSE (while waiting for a RevokeAndAck)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    // actual test begins
    sender.send(alice, CMD_CLOSE(None))
    sender.expectMsg("ok")
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == NORMAL)
  }

  test("recv Shutdown (no pending htlcs)") { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, Shutdown("00" * 32, Bob.channelParams.defaultFinalScriptPubKey))
    alice2bob.expectMsgType[Shutdown]
    alice2bob.expectMsgType[ClosingSigned]
    awaitCond(alice.stateName == NEGOTIATING)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_NEGOTIATING].channelId)
  }

  test("recv Shutdown (with unacked sent htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    sender.send(bob, CMD_CLOSE(None))
    bob2alice.expectMsgType[Shutdown]
    // actual test begins
    bob2alice.forward(alice)
    // alice sends a new sig
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // bob replies with a revocation
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // as soon as alice as received the revocation, she will send her shutdown message
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == SHUTDOWN)
    // channel should be advertised as down
    assert(channelUpdateListener.expectMsgType[LocalChannelDown].channelId === alice.stateData.asInstanceOf[DATA_SHUTDOWN].channelId)
  }

  test("recv Shutdown (with unacked received htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    // actual test begins
    sender.send(bob, Shutdown("00" * 32, TestConstants.Alice.channelParams.defaultFinalScriptPubKey))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv Shutdown (with invalid final script)") { f =>
    import f._
    val sender = TestProbe()
    sender.send(bob, Shutdown("00" * 32, BinaryData("00112233445566778899")))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv Shutdown (with invalid final script and signed htlcs, in response to a Shutdown)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)
    sender.send(bob, CMD_CLOSE(None))
    bob2alice.expectMsgType[Shutdown]
    // actual test begins
    sender.send(bob, Shutdown("00" * 32, BinaryData("00112233445566778899")))
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[PublishAsap]
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv Shutdown (with signed htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    sender.send(bob, Shutdown("00" * 32, TestConstants.Alice.channelParams.defaultFinalScriptPubKey))
    bob2alice.expectMsgType[Shutdown]
    awaitCond(bob.stateName == SHUTDOWN)
  }

  test("recv Shutdown (while waiting for a RevokeAndAck)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    sender.send(bob, CMD_CLOSE(None))
    bob2alice.expectMsgType[Shutdown]
    // actual test begins
    bob2alice.forward(alice)
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == SHUTDOWN)
  }

  test("recv Shutdown (while waiting for a RevokeAndAck with pending outgoing htlc)") { f =>
    import f._
    val sender = TestProbe()
    // let's make bob send a Shutdown message
    sender.send(bob, CMD_CLOSE(None))
    bob2alice.expectMsgType[Shutdown]
    // this is just so we have something to sign
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    // now we can sign
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // adding an outgoing pending htlc
    val (r1, htlc1) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    // actual test begins
    // alice eventually gets bob's shutdown
    bob2alice.forward(alice)
    // alice can't do anything for now other than waiting for bob to send the revocation
    alice2bob.expectNoMsg()
    // bob sends the revocation
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // bob will also sign back
    bob2alice.expectMsgType[CommitSig]
    bob2alice.forward(alice)
    // then alice can sign the 2nd htlc
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    // and reply to bob's first signature
    alice2bob.expectMsgType[RevokeAndAck]
    alice2bob.forward(bob)
    // bob replies with the 2nd revocation
    bob2alice.expectMsgType[RevokeAndAck]
    bob2alice.forward(alice)
    // then alice can send her shutdown
    alice2bob.expectMsgType[Shutdown]
    awaitCond(alice.stateName == SHUTDOWN)
    // note: bob will sign back a second time, but that is out of our scope
  }

  test("recv CurrentBlockCount (no htlc timed out)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    sender.send(alice, CurrentBlockCount(400143))
    awaitCond(alice.stateData == initialState)
  }

  test("recv CurrentBlockCount (an htlc timed out)") { f =>
    import f._
    val sender = TestProbe()
    val (r, htlc) = addHtlc(50000000, alice, bob, alice2bob, bob2alice)
    crossSign(alice, bob, alice2bob, bob2alice)

    // actual test begins
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val aliceCommitTx = initialState.commitments.localCommit.publishableTxs.commitTx.tx
    sender.send(alice, CurrentBlockCount(400145))
    alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))

    alice2blockchain.expectMsgType[PublishAsap] // main delayed
    alice2blockchain.expectMsgType[PublishAsap] // htlc timeout
    alice2blockchain.expectMsgType[PublishAsap] // htlc delayed
  val watch = alice2blockchain.expectMsgType[WatchConfirmed]
    assert(watch.event === BITCOIN_TX_CONFIRMED(aliceCommitTx))
  }

  test("recv CurrentFeerate (when funder, triggers an UpdateFee)") { f =>
    import f._
    val sender = TestProbe()
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val event = CurrentFeerates(FeeratesPerKw.single(20000))
    sender.send(alice, event)
    alice2bob.expectMsg(UpdateFee(initialState.commitments.channelId, event.feeratesPerKw.blocks_2))
  }

  test("recv CurrentFeerate (when funder, doesn't trigger an UpdateFee)") { f =>
    import f._
    val sender = TestProbe()
    val event = CurrentFeerates(FeeratesPerKw.single(10010))
    sender.send(alice, event)
    alice2bob.expectNoMsg(500 millis)
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are close)") { f =>
    import f._
    val sender = TestProbe()
    val event = CurrentFeerates(FeeratesPerKw.single(11000))
    sender.send(bob, event)
    bob2alice.expectNoMsg(500 millis)
  }

  test("recv CurrentFeerate (when fundee, commit-fee/network-fee are very different)") { f =>
    import f._
    val sender = TestProbe()
    val event = CurrentFeerates(FeeratesPerKw.single(100))
    sender.send(bob, event)
    bob2alice.expectMsgType[Error]
    bob2blockchain.expectMsgType[PublishAsap] // commit tx
    bob2blockchain.expectMsgType[PublishAsap] // main delayed
    bob2blockchain.expectMsgType[WatchConfirmed]
    awaitCond(bob.stateName == CLOSING)
  }

  test("recv BITCOIN_FUNDING_SPENT (their commit w/ htlc)") { f =>
    import f._
    val sender = TestProbe()

    val (ra1, htlca1) = addHtlc(250000000, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000, alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(1, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(0, rb1, alice, bob, alice2bob, bob2alice)

    // at this point here is the situation from alice pov and what she should do when bob publishes his commit tx:
    // balances :
    //    alice's balance : 449 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    assert(bobCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, bobCommitTx)

    // in response to that, alice publishes its claim txes
    val claimTxes = for (i <- 0 until 4) yield alice2blockchain.expectMsgType[PublishAsap].tx
    // in addition to its main output, alice can only claim 3 out of 4 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the preimage
    val amountClaimed = (for (claimHtlcTx <- claimTxes) yield {
      assert(claimHtlcTx.txIn.size == 1)
      assert(claimHtlcTx.txOut.size == 1)
      Transaction.correctlySpends(claimHtlcTx, bobCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
      claimHtlcTx.txOut(0).amount
    }).sum
    // at best we have a little less than 450 000 + 250 000 + 100 000 + 50 000 = 850 000 (because fees)
    assert(amountClaimed == Satoshi(814840))

    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(bobCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxes(0))) // claim-main
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    alice2blockchain.expectNoMsg(1 second)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.isDefined)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimHtlcSuccessTxs.size == 1)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].remoteCommitPublished.get.claimHtlcTimeoutTxs.size == 2)
  }

  test("recv BITCOIN_FUNDING_SPENT (their *next* commit w/ htlc)") { f =>
    import f._
    val sender = TestProbe()

    val (ra1, htlca1) = addHtlc(250000000, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000, alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(1, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(0, rb1, alice, bob, alice2bob, bob2alice)
    // alice sign but we intercept bob's revocation
    sender.send(alice, CMD_SIGN)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[CommitSig]
    alice2bob.forward(bob)
    bob2alice.expectMsgType[RevokeAndAck]

    // as far as alice knows, bob currently has two valid unrevoked commitment transactions

    // at this point here is the situation from bob's pov with the latest sig received from alice,
    // and what alice should do when bob publishes his commit tx:
    // balances :
    //    alice's balance : 499 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // bob publishes his current commit tx
    val bobCommitTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    assert(bobCommitTx.txOut.size == 5) // two main outputs and 3 pending htlcs
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
    // at best we have a little less than 500 000 + 250 000 + 100 000 = 850 000 (because fees)
    assert(amountClaimed == Satoshi(822280))

    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(bobCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxes(0))) // claim-main
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    alice2blockchain.expectNoMsg(1 second)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.isDefined)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get.claimHtlcSuccessTxs.size == 0)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].nextRemoteCommitPublished.get.claimHtlcTimeoutTxs.size == 2)
  }

  test("recv BITCOIN_FUNDING_SPENT (revoked commit)") { f =>
    import f._
    val sender = TestProbe()

    // initially we have :
    // alice = 800 000
    //   bob = 200 000
    def send(): Transaction = {
      // alice sends 8 000 sat
      val (r, htlc) = addHtlc(10000000, alice, bob, alice2bob, bob2alice)
      crossSign(alice, bob, alice2bob, bob2alice)

      bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    }

    val txs = for (i <- 0 until 10) yield send()
    // bob now has 10 spendable tx, 9 of them being revoked

    // let's say that bob published this tx
    val revokedTx = txs(3)
    // channel state for this revoked tx is as follows:
    // alice = 760 000
    //   bob = 200 000
    //  a->b =  10 000
    //  a->b =  10 000
    //  a->b =  10 000
    //  a->b =  10 000
    // two main outputs + 4 htlc
    assert(revokedTx.txOut.size == 6)
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, revokedTx)
    alice2bob.expectMsgType[Error]

    val mainTx = alice2blockchain.expectMsgType[PublishAsap].tx
    val mainPenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx
    val htlcPenaltyTxs = for (i <- 0 until 4) yield alice2blockchain.expectMsgType[PublishAsap].tx
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event == BITCOIN_TX_CONFIRMED(revokedTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event == BITCOIN_TX_CONFIRMED(mainTx))
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // main-penalty
    // let's make sure that htlc-penalty txs each spend a different output
    assert(htlcPenaltyTxs.map(_.txIn.head.outPoint.index).toSet.size === htlcPenaltyTxs.size)
    htlcPenaltyTxs.foreach(htlcPenaltyTx => assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT))
    alice2blockchain.expectNoMsg(1 second)

    Transaction.correctlySpends(mainTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(mainPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    htlcPenaltyTxs.foreach(htlcPenaltyTx => Transaction.correctlySpends(htlcPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))

    // two main outputs are 760 000 and 200 000
    assert(mainTx.txOut(0).amount == Satoshi(741490))
    assert(mainPenaltyTx.txOut(0).amount == Satoshi(195150))
    assert(htlcPenaltyTxs(0).txOut(0).amount == Satoshi(4530))
    assert(htlcPenaltyTxs(1).txOut(0).amount == Satoshi(4530))
    assert(htlcPenaltyTxs(2).txOut(0).amount == Satoshi(4530))
    assert(htlcPenaltyTxs(3).txOut(0).amount == Satoshi(4530))

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].revokedCommitPublished.size == 1)

  }

  test("recv BITCOIN_FUNDING_SPENT (revoked commit with identical htlcs)") { f =>
    import f._
    val sender = TestProbe()

    // initially we have :
    // alice = 800 000
    //   bob = 200 000

    val add = CMD_ADD_HTLC(10000000, "11" * 32, 400144)
    sender.send(alice, add)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)
    sender.send(alice, add)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    crossSign(alice, bob, alice2bob, bob2alice)
    // bob will publish this tx after it is revoked
    val revokedTx = bob.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx

    sender.send(alice, add)
    sender.expectMsg("ok")
    alice2bob.expectMsgType[UpdateAddHtlc]
    alice2bob.forward(bob)

    crossSign(alice, bob, alice2bob, bob2alice)

    // channel state for this revoked tx is as follows:
    // alice = 780 000
    //   bob = 200 000
    //  a->b =  10 000
    //  a->b =  10 000
    assert(revokedTx.txOut.size == 4)
    alice ! WatchEventSpent(BITCOIN_FUNDING_SPENT, revokedTx)
    alice2bob.expectMsgType[Error]

    val mainTx = alice2blockchain.expectMsgType[PublishAsap].tx
    val mainPenaltyTx = alice2blockchain.expectMsgType[PublishAsap].tx
    val htlcPenaltyTxs = for (i <- 0 until 2) yield alice2blockchain.expectMsgType[PublishAsap].tx
    // let's make sure that htlc-penalty txs each spend a different output
    assert(htlcPenaltyTxs.map(_.txIn.head.outPoint.index).toSet.size === htlcPenaltyTxs.size)
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event == BITCOIN_TX_CONFIRMED(revokedTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event == BITCOIN_TX_CONFIRMED(mainTx))
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT) // main-penalty
    htlcPenaltyTxs.foreach(htlcPenaltyTx => assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT))
    alice2blockchain.expectNoMsg(1 second)

    Transaction.correctlySpends(mainTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(mainPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    htlcPenaltyTxs.foreach(htlcPenaltyTx => Transaction.correctlySpends(htlcPenaltyTx, Seq(revokedTx), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS))
  }

  test("recv Error") { f =>
    import f._
    val (ra1, htlca1) = addHtlc(250000000, alice, bob, alice2bob, bob2alice)
    val (ra2, htlca2) = addHtlc(100000000, alice, bob, alice2bob, bob2alice)
    val (ra3, htlca3) = addHtlc(10000, alice, bob, alice2bob, bob2alice)
    val (rb1, htlcb1) = addHtlc(50000000, bob, alice, bob2alice, alice2bob)
    val (rb2, htlcb2) = addHtlc(55000000, bob, alice, bob2alice, alice2bob)
    crossSign(alice, bob, alice2bob, bob2alice)
    fulfillHtlc(1, ra2, bob, alice, bob2alice, alice2bob)
    fulfillHtlc(0, rb1, alice, bob, alice2bob, bob2alice)

    // at this point here is the situation from alice pov and what she should do when she publishes his commit tx:
    // balances :
    //    alice's balance : 449 999 990                             => nothing to do
    //    bob's balance   :  95 000 000                             => nothing to do
    // htlcs :
    //    alice -> bob    : 250 000 000 (bob does not have the preimage)   => wait for the timeout and spend using 2nd stage htlc-timeout
    //    alice -> bob    : 100 000 000 (bob has the preimage)             => if bob does not use the preimage, wait for the timeout and spend using 2nd stage htlc-timeout
    //    alice -> bob    :          10 (dust)                             => won't appear in the commitment tx
    //    bob -> alice    :  50 000 000 (alice has the preimage)           => spend immediately using the preimage using htlc-success
    //    bob -> alice    :  55 000 000 (alice does not have the preimage) => nothing to do, bob will get his money back after the timeout

    // an error occurs and alice publishes her commit tx
    val aliceCommitTx = alice.stateData.asInstanceOf[DATA_NORMAL].commitments.localCommit.publishableTxs.commitTx.tx
    alice ! Error("00" * 32, "oops".getBytes())
    alice2blockchain.expectMsg(PublishAsap(aliceCommitTx))
    assert(aliceCommitTx.txOut.size == 6) // two main outputs and 4 pending htlcs

    // alice can only claim 3 out of 4 htlcs, she can't do anything regarding the htlc sent by bob for which she does not have the htlc
    // so we expect 7 transactions:
    // - 1 tx to claim the main delayed output
    // - 3 txes for each htlc
    // - 3 txes for each delayed output of the claimed htlc
    val claimTxs = for (i <- 0 until 7) yield alice2blockchain.expectMsgType[PublishAsap].tx

    // the main delayed output spends the commitment transaction
    Transaction.correctlySpends(claimTxs(0), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    // 2nd stage transactions spend the commitment transaction
    Transaction.correctlySpends(claimTxs(1), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(claimTxs(2), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(claimTxs(3), aliceCommitTx :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    // 3rd stage transactions spend their respective HTLC-Success/HTLC-Timeout transactions
    Transaction.correctlySpends(claimTxs(4), claimTxs(1) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(claimTxs(5), claimTxs(2) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
    Transaction.correctlySpends(claimTxs(6), claimTxs(3) :: Nil, ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)

    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(aliceCommitTx))
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxs(0))) // main-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxs(4))) // htlc-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxs(5))) // htlc-delayed
    assert(alice2blockchain.expectMsgType[WatchConfirmed].event === BITCOIN_TX_CONFIRMED(claimTxs(6))) // htlc-delayed
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    assert(alice2blockchain.expectMsgType[WatchSpent].event === BITCOIN_OUTPUT_SPENT)
    alice2blockchain.expectNoMsg(1 second)

    awaitCond(alice.stateName == CLOSING)
    assert(alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.isDefined)
    val localCommitPublished = alice.stateData.asInstanceOf[DATA_CLOSING].localCommitPublished.get
    assert(localCommitPublished.commitTx == aliceCommitTx)
    assert(localCommitPublished.htlcSuccessTxs.size == 1)
    assert(localCommitPublished.htlcTimeoutTxs.size == 2)
    assert(localCommitPublished.claimHtlcDelayedTxs.size == 3)
  }

  test("recv BITCOIN_FUNDING_DEEPLYBURIED", Tag("channels_public")) { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42))
    val annSigs = alice2bob.expectMsgType[AnnouncementSignatures]
    // public channel: we don't send the channel_update directly to the peer
    alice2bob.expectNoMsg(1 second)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId == annSigs.shortChannelId && alice.stateData.asInstanceOf[DATA_NORMAL].buried == true)
    // we don't re-publish the same channel_update if there was no change
    channelUpdateListener.expectNoMsg(1 second)
  }

  test("recv BITCOIN_FUNDING_DEEPLYBURIED (short channel id changed)", Tag("channels_public")) { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400001, 22))
    val annSigs = alice2bob.expectMsgType[AnnouncementSignatures]
    // public channel: we don't send the channel_update directly to the peer
    alice2bob.expectNoMsg(1 second)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId == annSigs.shortChannelId && alice.stateData.asInstanceOf[DATA_NORMAL].buried == true)
    assert(channelUpdateListener.expectMsgType[LocalChannelUpdate].shortChannelId == alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId)
    channelUpdateListener.expectNoMsg(1 second)
  }

  test("recv BITCOIN_FUNDING_DEEPLYBURIED (private channel)") { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42))
    // private channel: we send the channel_update directly to the peer
    val channelUpdate = alice2bob.expectMsgType[ChannelUpdate]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId == channelUpdate.shortChannelId && alice.stateData.asInstanceOf[DATA_NORMAL].buried == true)
    // we don't re-publish the same channel_update if there was no change
    channelUpdateListener.expectNoMsg(1 second)
  }

  test("recv BITCOIN_FUNDING_DEEPLYBURIED (private channel, short channel id changed)") { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400001, 22))
    // private channel: we send the channel_update directly to the peer
    val channelUpdate = alice2bob.expectMsgType[ChannelUpdate]
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId == channelUpdate.shortChannelId && alice.stateData.asInstanceOf[DATA_NORMAL].buried == true)
    // LocalChannelUpdate should not be published
    assert(channelUpdateListener.expectMsgType[LocalChannelUpdate].shortChannelId == alice.stateData.asInstanceOf[DATA_NORMAL].shortChannelId)
    channelUpdateListener.expectNoMsg(1 second)
  }

  test("recv AnnouncementSignatures", Tag("channels_public")) { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    sender.send(alice, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42))
    val annSigsA = alice2bob.expectMsgType[AnnouncementSignatures]
    sender.send(bob, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42))
    val annSigsB = bob2alice.expectMsgType[AnnouncementSignatures]
    import initialState.commitments.{localParams, remoteParams}
    val channelAnn = Announcements.makeChannelAnnouncement(Alice.nodeParams.chainHash, annSigsA.shortChannelId, Alice.nodeParams.nodeId, remoteParams.nodeId, Alice.keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey, remoteParams.fundingPubKey, annSigsA.nodeSignature, annSigsB.nodeSignature, annSigsA.bitcoinSignature, annSigsB.bitcoinSignature)
    // actual test starts here
    bob2alice.forward(alice)
    awaitCond({
      val normal = alice.stateData.asInstanceOf[DATA_NORMAL]
      normal.shortChannelId == annSigsA.shortChannelId && normal.buried && normal.channelAnnouncement == Some(channelAnn) && normal.channelUpdate.shortChannelId == annSigsA.shortChannelId
    })
    assert(channelUpdateListener.expectMsgType[LocalChannelUpdate].channelAnnouncement_opt === Some(channelAnn))
  }

  test("recv AnnouncementSignatures (re-send)", Tag("channels_public")) { f =>
    import f._
    val initialState = alice.stateData.asInstanceOf[DATA_NORMAL]
    val sender = TestProbe()
    sender.send(alice, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 42, 10))
    val annSigsA = alice2bob.expectMsgType[AnnouncementSignatures]
    sender.send(bob, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 42, 10))
    val annSigsB = bob2alice.expectMsgType[AnnouncementSignatures]
    import initialState.commitments.{localParams, remoteParams}
    val channelAnn = Announcements.makeChannelAnnouncement(Alice.nodeParams.chainHash, annSigsA.shortChannelId, Alice.nodeParams.nodeId, remoteParams.nodeId, Alice.keyManager.fundingPublicKey(localParams.channelKeyPath).publicKey, remoteParams.fundingPubKey, annSigsA.nodeSignature, annSigsB.nodeSignature, annSigsA.bitcoinSignature, annSigsB.bitcoinSignature)
    bob2alice.forward(alice)
    awaitCond(alice.stateData.asInstanceOf[DATA_NORMAL].channelAnnouncement === Some(channelAnn))

    // actual test starts here
    // simulate bob re-sending its sigs
    bob2alice.send(alice, annSigsA)
    // alice re-sends her sigs
    alice2bob.expectMsg(annSigsA)
  }

  test("recv TickRefreshChannelUpdate", Tag("channels_public")) { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42))
    sender.send(bob, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42))
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    val update1 = channelUpdateListener.expectMsgType[LocalChannelUpdate]

    // actual test starts here
    Thread.sleep(1100)
    sender.send(alice, TickRefreshChannelUpdate)
    val update2 = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(update1.channelUpdate.timestamp < update2.channelUpdate.timestamp)
  }

  test("recv INPUT_DISCONNECTED", Tag("channels_public")) { f =>
    import f._
    val sender = TestProbe()
    sender.send(alice, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42))
    sender.send(bob, WatchEventConfirmed(BITCOIN_FUNDING_DEEPLYBURIED, 400000, 42))
    bob2alice.expectMsgType[AnnouncementSignatures]
    bob2alice.forward(alice)
    val update1 = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(Announcements.isEnabled(update1.channelUpdate.channelFlags) == true)

    // actual test starts here
    Thread.sleep(1100)
    sender.send(alice, INPUT_DISCONNECTED)
    val update2 = channelUpdateListener.expectMsgType[LocalChannelUpdate]
    assert(update1.channelUpdate.timestamp < update2.channelUpdate.timestamp)
    assert(Announcements.isEnabled(update2.channelUpdate.channelFlags) == false)
    awaitCond(alice.stateName == OFFLINE)
  }

  test("recv INPUT_DISCONNECTED (with pending unsigned htlcs)") { f =>
    import f._
    val sender = TestProbe()
    val (_, htlc1) = addHtlc(10000, alice, bob, alice2bob, bob2alice)
    val (_, htlc2) = addHtlc(10000, alice, bob, alice2bob, bob2alice)
    val aliceData = alice.stateData.asInstanceOf[DATA_NORMAL]
    assert(aliceData.commitments.localChanges.proposed.size == 2)

    // actual test starts here
    sender.send(alice, INPUT_DISCONNECTED)
    assert(relayerA.expectMsgType[Status.Failure].cause.asInstanceOf[AddHtlcFailed].paymentHash === htlc1.paymentHash)
    assert(relayerA.expectMsgType[Status.Failure].cause.asInstanceOf[AddHtlcFailed].paymentHash === htlc2.paymentHash)
    awaitCond(alice.stateName == OFFLINE)
  }

}
