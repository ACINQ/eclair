package fr.acinq.eclair.payment

import akka.actor.{ActorRef, Status}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.eclair.randomBytes
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.HtlcGenerationSpec.channelUpdate_bc
import fr.acinq.eclair.payment.PaymentLifecycle.buildCommand
import fr.acinq.eclair.router.Announcements
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{Globals, TestConstants, TestkitBaseClass}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 29/08/2016.
  */
@RunWith(classOf[JUnitRunner])
class RelayerSpec extends TestkitBaseClass {

  // let's reuse the existing test data
  import HtlcGenerationSpec._

  type FixtureParam = Tuple3[ActorRef, TestProbe, TestProbe]

  override def withFixture(test: OneArgTest) = {

    within(30 seconds) {
      val register = TestProbe()
      val paymentHandler = TestProbe()
      // we are node B in the route A -> B -> C -> ....
      //val relayer = system.actorOf(Relayer.props(TestConstants.Bob.nodeParams.copy(nodeKey = priv_b), register.ref, paymentHandler.ref))
      val relayer = system.actorOf(Relayer.props(TestConstants.Bob.nodeParams, register.ref, paymentHandler.ref))
      test((relayer, register, paymentHandler))
    }
  }

  val channelId_ab: BinaryData = randomBytes(32)
  val channelId_bc: BinaryData = randomBytes(32)

  def makeCommitments(channelId: BinaryData) = Commitments(null, null, 0.toByte, null, null, null, null, 0, 0, Map.empty, null, null, null, channelId)

  test("relay an htlc-add") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc)

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd.message.upstream_opt === Some(add_ab))

    sender.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when we have no channel_update for the next channel") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)

    sender.send(relayer, ForwardAdd(add_ab))

    val fwdFail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwdFail.channelId === add_ab.channelId)
    assert(fwdFail.message.id === add_ab.id)
    assert(fwdFail.message.reason === Right(UnknownNextPeer))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when register returns an error") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc)

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd1 = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd1.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd1.message.upstream_opt === Some(add_ab))

    sender.send(relayer, Status.Failure(Register.ForwardShortIdFailure(fwd1)))

    val fwd2 = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd2.channelId === channelId_ab)
    assert(fwd2.message.id === add_ab.id)
    assert(fwd2.message.reason === Right(UnknownNextPeer))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when the channel is advertised as unusable (down)") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // check that payments are sent properly
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc)

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd.message.upstream_opt === Some(add_ab))

    sender.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)

    // now tell the relayer that the channel is down and try again
    relayer ! LocalChannelDown(sender.ref, channelId = channelId_bc, shortChannelId =  channelUpdate_bc.shortChannelId, remoteNodeId = TestConstants.Bob.nodeParams.nodeId)

    val (cmd1, _) = buildCommand(finalAmountMsat, finalExpiry, "02" * 32, hops)
    val add_ab1 = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd1.amountMsat, cmd1.paymentHash, cmd1.expiry, cmd1.onion)
    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason === Right(UnknownNextPeer))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when the requested channel is disabled") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    val channelUpdate_bc_disabled = channelUpdate_bc.copy(flags = Announcements.makeFlags(Announcements.isNode1(channelUpdate_bc.flags), enable = false))
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc_disabled)

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(ChannelDisabled(channelUpdate_bc_disabled.flags, channelUpdate_bc_disabled)))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when the onion is malformed") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, "00" * Sphinx.PacketLength)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc)

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_MALFORMED_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.onionHash == Crypto.sha256(add_ab.onionRoutingPacket))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when amount is below the next hop's requirements") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(channelUpdate_bc.htlcMinimumMsat - 1, finalExpiry, paymentHash, hops.map(hop => hop.copy(lastUpdate = hop.lastUpdate.copy(feeBaseMsat = 0, feeProportionalMillionths = 0))))
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc)

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(AmountBelowMinimum(cmd.amountMsat, channelUpdate_bc)))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail to relay an htlc-add when expiry does not match next hop's requirements") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    val hops1 = hops.updated(1, hops(1).copy(lastUpdate = hops(1).lastUpdate.copy(cltvExpiryDelta = 0)))
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc)

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(IncorrectCltvExpiry(cmd.expiry, channelUpdate_bc)))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail an htlc-add at the final node when amount has been modified by second-to-last node") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // to simulate this we use a zero-hop route A->B where A is the 'attacker'
    val hops1 = hops.head :: Nil
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
    // and then manually build an htlc with a wrong expiry
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat - 1, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc)

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(FinalIncorrectHtlcAmount(add_ab.amountMsat)))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("fail an htlc-add at the final node when expiry has been modified by second-to-last node") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // to simulate this we use a zero-hop route A->B where A is the 'attacker'
    val hops1 = hops.head :: Nil
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
    // and then manually build an htlc with a wrong expiry
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry - 1, cmd.onion)
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc)

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(FinalIncorrectCltvExpiry(add_ab.expiry)))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("correctly translates errors returned by channel when attempting to add an htlc") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    val paymentHash = randomBytes(32)
    val origin = Relayed(channelId_ab, originHtlcId = 42, amountMsatIn = 1100000, amountMsatOut = 1000000)

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, ExpiryTooSmall(channelId_bc, 100, 0, 0), origin, Some(channelUpdate_bc))))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(ExpiryTooSoon(channelUpdate_bc)))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, ExpiryTooBig(channelId_bc, 100, 200, 0), origin, Some(channelUpdate_bc))))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(ExpiryTooFar))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, InsufficientFunds(channelId_bc, origin.amountMsatOut, 100, 0, 0), origin, Some(channelUpdate_bc))))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(TemporaryChannelFailure(channelUpdate_bc)))

    val channelUpdate_bc_disabled = channelUpdate_bc.copy(flags = "0002")
    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, ChannelUnavailable(channelId_bc), origin, Some(channelUpdate_bc_disabled))))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(ChannelDisabled(channelUpdate_bc_disabled.flags, channelUpdate_bc_disabled)))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, HtlcTimedout(channelId_bc), origin, None)))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(PermanentChannelFailure))

    sender.send(relayer, Status.Failure(AddHtlcFailed(channelId_bc, paymentHash, HtlcTimedout(channelId_bc), origin, Some(channelUpdate_bc))))
    assert(register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message.reason === Right(PermanentChannelFailure))

    register.expectNoMsg(100 millis)
    paymentHandler.expectNoMsg(100 millis)
  }

  test("relay an htlc-fulfill") { case (relayer, register, _) =>
    val sender = TestProbe()
    val eventListener = TestProbe()

    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    // we build a fake htlc for the downstream channel
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 72, amountMsat = 10000000L, paymentHash = "00" * 32, expiry = 4200, onionRoutingPacket = "")
    val fulfill_ba = UpdateFulfillHtlc(channelId = channelId_bc, id = 42, paymentPreimage = "00" * 32)
    val origin = Relayed(channelId_ab, 150, 11000000L, 10000000L)
    sender.send(relayer, ForwardFulfill(fulfill_ba, origin, add_bc))

    val fwd = register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(fwd.channelId === origin.originChannelId)
    assert(fwd.message.id === origin.originHtlcId)

    eventListener.expectMsg(PaymentRelayed(MilliSatoshi(origin.amountMsatIn), MilliSatoshi(origin.amountMsatOut), Crypto.sha256(fulfill_ba.paymentPreimage)))
  }

  test("relay an htlc-fail") { case (relayer, register, _) =>
    val sender = TestProbe()

    // we build a fake htlc for the downstream channel
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 72, amountMsat = 10000000L, paymentHash = "00" * 32, expiry = 4200, onionRoutingPacket = "")
    val fail_ba = UpdateFailHtlc(channelId = channelId_bc, id = 42, reason = Sphinx.createErrorPacket(BinaryData("01" * 32), TemporaryChannelFailure(channelUpdate_cd)))
    val origin = Relayed(channelId_ab, 150, 11000000L, 10000000L)
    sender.send(relayer, ForwardFail(fail_ba, origin, add_bc))

    val fwd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId === origin.originChannelId)
    assert(fwd.message.id === origin.originHtlcId)
  }
}
