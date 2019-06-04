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

package fr.acinq.eclair.payment

import java.util.UUID

import akka.actor.{ActorRef, Status}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{ByteVector32, Crypto, MilliSatoshi}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentLifecycle.buildCommand
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{ShortChannelId, TestConstants, TestkitBaseClass, UInt64, randomBytes32, randomKey}
import org.scalatest.Outcome
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
  * Created by PM on 29/08/2016.
  */

class RelayerSpec extends TestkitBaseClass {

  // let's reuse the existing test data
  import HtlcGenerationSpec._

  case class FixtureParam(relayer: ActorRef, register: TestProbe, paymentHandler: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    within(30 seconds) {
      val register = TestProbe()
      val paymentHandler = TestProbe()
      // we are node B in the route A -> B -> C -> ....
      //val relayer = system.actorOf(Relayer.props(TestConstants.Bob.nodeParams.copy(nodeKey = priv_b), register.ref, paymentHandler.ref))
      val relayer = system.actorOf(Relayer.props(TestConstants.Bob.nodeParams, register.ref, paymentHandler.ref))
      withFixture(test.toNoArgTest(FixtureParam(relayer, register, paymentHandler)))
    }
  }

  val channelId_ab = randomBytes32
  val channelId_bc = randomBytes32

  def makeCommitments(channelId: ByteVector32, availableBalanceMsat: Long = 50000000L) = new Commitments(null, null, 0.toByte, null, null,
    null, null, 0, 0, Map.empty, null, null, null, channelId) {
    override def availableBalanceForSendMsat: Long = availableBalanceMsat
  }

  test("relay an htlc-add") { f =>
    import f._
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd.message.upstream === Right(add_ab))

    sender.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("relay an htlc-add with retries") { f =>
    import f._
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)

    // we tell the relayer about channel B-C
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    // this is another channel B-C, with less balance (it will be preferred)
    val (channelId_bc_1, channelUpdate_bc_1) = (randomBytes32, channelUpdate_bc.copy(shortChannelId = ShortChannelId("500000x1x1")))
    relayer ! LocalChannelUpdate(null, channelId_bc_1, channelUpdate_bc_1.shortChannelId, c, None, channelUpdate_bc_1, makeCommitments(channelId_bc_1, availableBalanceMsat = 49000000L))

    sender.send(relayer, ForwardAdd(add_ab))

    // first try
    val fwd1 = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd1.shortChannelId === channelUpdate_bc_1.shortChannelId)
    assert(fwd1.message.upstream === Right(add_ab))

    // channel returns an error
    val origin = Relayed(channelId_ab, originHtlcId = 42, amountMsatIn = 1100000, amountMsatOut = 1000000)
    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc_1, paymentHash, HtlcValueTooHighInFlight(channelId_bc_1, UInt64(1000000000L), UInt64(1516977616L)), origin, Some(channelUpdate_bc_1), originalCommand = Some(fwd1.message))))

    // second try
    val fwd2 = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd2.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd2.message.upstream === Right(add_ab))

    // failure again
    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, HtlcValueTooHighInFlight(channelId_bc, UInt64(1000000000L), UInt64(1516977616L)), origin, Some(channelUpdate_bc), originalCommand = Some(fwd2.message))))

    // the relayer should give up
    val fwdFail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwdFail.channelId === add_ab.channelId)
    assert(fwdFail.message.id === add_ab.id)
    assert(fwdFail.message.reason === Right(TemporaryNodeFailure))

    sender.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when we have no channel_update for the next channel") { f =>
    import f._
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)

    sender.send(relayer, ForwardAdd(add_ab))

    val fwdFail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwdFail.channelId === add_ab.channelId)
    assert(fwdFail.message.id === add_ab.id)
    assert(fwdFail.message.reason === Right(UnknownNextPeer))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when register returns an error") { f =>
    import f._
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd1 = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd1.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd1.message.upstream === Right(add_ab))

    sender.send(relayer, Status.Failure(Register.ForwardShortIdFailure(fwd1)))

    val fwd2 = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd2.channelId === channelId_ab)
    assert(fwd2.message.id === add_ab.id)
    assert(fwd2.message.reason === Right(UnknownNextPeer))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when the channel is advertised as unusable (down)") { f =>
    import f._
    val sender = TestProbe()

    // check that payments are sent properly
    val (cmd, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops)
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd.message.upstream === Right(add_ab))

    sender.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)

    // now tell the relayer that the channel is down and try again
    relayer ! LocalChannelDown(sender.ref, channelId = channelId_bc, shortChannelId = channelUpdate_bc.shortChannelId, remoteNodeId = TestConstants.Bob.nodeParams.nodeId)

    val (cmd1, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, randomBytes32, hops)
    val add_ab1 = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd1.amountMsat, cmd1.paymentHash, cmd1.cltvExpiry, cmd1.onion)
    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason === Right(UnknownNextPeer))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when the requested channel is disabled") { f =>
    import f._
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    val channelUpdate_bc_disabled = channelUpdate_bc.copy(channelFlags = Announcements.makeChannelFlags(Announcements.isNode1(channelUpdate_bc.channelFlags), enable = false))
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc_disabled, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(ChannelDisabled(channelUpdate_bc_disabled.messageFlags, channelUpdate_bc_disabled.channelFlags, channelUpdate_bc_disabled)))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when the onion is malformed") { f =>
    import f._
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.cltvExpiry, ByteVector.fill(Sphinx.PacketLength)(0))
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_MALFORMED_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.onionHash == Crypto.sha256(add_ab.onionRoutingPacket))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when amount is below the next hop's requirements") { f =>
    import f._
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(UUID.randomUUID(), channelUpdate_bc.htlcMinimumMsat - 1, finalExpiry, paymentHash, hops.map(hop => hop.copy(lastUpdate = hop.lastUpdate.copy(feeBaseMsat = 0, feeProportionalMillionths = 0))))
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(AmountBelowMinimum(cmd.amountMsat, channelUpdate_bc)))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when expiry does not match next hop's requirements") { f =>
    import f._
    val sender = TestProbe()

    val hops1 = hops.updated(1, hops(1).copy(lastUpdate = hops(1).lastUpdate.copy(cltvExpiryDelta = 0)))
    val (cmd, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops1)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(IncorrectCltvExpiry(cmd.cltvExpiry, channelUpdate_bc)))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when relay fee isn't sufficient") { f =>
    import f._
    val sender = TestProbe()

    val hops1 = hops.updated(1, hops(1).copy(lastUpdate = hops(1).lastUpdate.copy(feeBaseMsat = hops(1).lastUpdate.feeBaseMsat / 2)))
    val (cmd, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops1)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(FeeInsufficient(add_ab.amountMsat, channelUpdate_bc)))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail an htlc-add at the final node when amount has been modified by second-to-last node") { f =>
    import f._
    val sender = TestProbe()

    // to simulate this we use a zero-hop route A->B where A is the 'attacker'
    val hops1 = hops.head :: Nil
    val (cmd, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops1)
    // and then manually build an htlc with a wrong expiry
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat - 1, cmd.paymentHash, cmd.cltvExpiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(FinalIncorrectHtlcAmount(add_ab.amountMsat)))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail an htlc-add at the final node when expiry has been modified by second-to-last node") { f =>
    import f._
    val sender = TestProbe()

    // to simulate this we use a zero-hop route A->B where A is the 'attacker'
    val hops1 = hops.head :: Nil
    val (cmd, _) = buildCommand(UUID.randomUUID(), finalAmountMsat, finalExpiry, paymentHash, hops1)
    // and then manually build an htlc with a wrong expiry
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.cltvExpiry - 1, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(FinalIncorrectCltvExpiry(add_ab.cltvExpiry)))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("correctly translates errors returned by channel when attempting to add an htlc") { f =>
    import f._
    val sender = TestProbe()

    val paymentHash = randomBytes32
    val origin = Relayed(channelId_ab, originHtlcId = 42, amountMsatIn = 1100000, amountMsatOut = 1000000)

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, ExpiryTooSmall(channelId_bc, 100, 0, 0), origin, Some(channelUpdate_bc), None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(ExpiryTooSoon(channelUpdate_bc)))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, ExpiryTooBig(channelId_bc, 100, 200, 0), origin, Some(channelUpdate_bc), None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(ExpiryTooFar))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, InsufficientFunds(channelId_bc, origin.amountMsatOut, 100, 0, 0), origin, Some(channelUpdate_bc), None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(TemporaryChannelFailure(channelUpdate_bc)))

    val channelUpdate_bc_disabled = channelUpdate_bc.copy(channelFlags = 2)
    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, ChannelUnavailable(channelId_bc), origin, Some(channelUpdate_bc_disabled), None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(ChannelDisabled(channelUpdate_bc_disabled.messageFlags, channelUpdate_bc_disabled.channelFlags, channelUpdate_bc_disabled)))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, HtlcTimedout(channelId_bc, Set.empty), origin, None, None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(PermanentChannelFailure))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, HtlcTimedout(channelId_bc, Set.empty), origin, Some(channelUpdate_bc), None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(PermanentChannelFailure))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("relay an htlc-fulfill") { f =>
    import f._
    val sender = TestProbe()
    val eventListener = TestProbe()

    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    // we build a fake htlc for the downstream channel
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 72, amountMsat = 10000000L, paymentHash = ByteVector32.Zeroes, cltvExpiry = 4200, onionRoutingPacket = ByteVector.empty)
    val fulfill_ba = UpdateFulfillHtlc(channelId = channelId_bc, id = 42, paymentPreimage = ByteVector32.Zeroes)
    val origin = Relayed(channelId_ab, 150, 11000000L, 10000000L)
    sender.send(relayer, ForwardFulfill(fulfill_ba, origin, add_bc))

    val fwd = register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(fwd.channelId === origin.originChannelId)
    assert(fwd.message.id === origin.originHtlcId)

    val paymentRelayed = eventListener.expectMsgType[PaymentRelayed]
    assert(paymentRelayed.copy(timestamp = 0) === PaymentRelayed(MilliSatoshi(origin.amountMsatIn), MilliSatoshi(origin.amountMsatOut), add_bc.paymentHash, channelId_ab, channelId_bc, timestamp = 0))
  }

  test("relay an htlc-fail") { f =>
    import f._
    val sender = TestProbe()

    // we build a fake htlc for the downstream channel
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 72, amountMsat = 10000000L, paymentHash = ByteVector32.Zeroes, cltvExpiry = 4200, onionRoutingPacket = ByteVector.empty)
    val fail_ba = UpdateFailHtlc(channelId = channelId_bc, id = 42, reason = Sphinx.createErrorPacket(ByteVector32(ByteVector.fill(32)(1)), TemporaryChannelFailure(channelUpdate_cd)))
    val origin = Relayed(channelId_ab, 150, 11000000L, 10000000L)
    sender.send(relayer, ForwardFail(fail_ba, origin, add_bc))

    val fwd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId === origin.originChannelId)
    assert(fwd.message.id === origin.originHtlcId)
  }
}
