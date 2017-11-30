package fr.acinq.eclair.payment

import akka.actor.ActorRef
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
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
      val relayer = system.actorOf(Relayer.props(TestConstants.Bob.nodeParams.copy(privateKey = priv_b), register.ref, paymentHandler.ref))
      test((relayer, register, paymentHandler))
    }
  }

  // node c is the next node in the route
  val nodeId_a = PublicKey(a)
  val nodeId_c = PublicKey(c)
  val channelId_ab: BinaryData = "65514354" * 8
  val channelId_bc: BinaryData = "64864544" * 8
  val channel_flags = 0x00.toByte

  def makeCommitments(channelId: BinaryData) = Commitments(null, null, 0.toByte, null, null, null, null, 0, 0, Map.empty, null, null, null, channelId)

  test("relay an htlc-add") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! channelUpdate_bc

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd.message.upstream_opt === Some(add_ab))

    sender.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("fail to relay an htlc-add when we have no channel_update for the next channel") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(UnknownNextPeer))

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("fail to relay an htlc-add when the requested channel is disabled") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    val channelUpdate_bc_disabled = channelUpdate_bc.copy(flags = Announcements.makeFlags(Announcements.isNode1(channelUpdate_bc.flags), enable = false))
    relayer ! channelUpdate_bc_disabled

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(ChannelDisabled(channelUpdate_bc_disabled.flags, channelUpdate_bc_disabled)))

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("fail to relay an htlc-add when the onion is malformed") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, "00" * Sphinx.PacketLength)
    relayer ! channelUpdate_bc

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_MALFORMED_HTLC]
    assert(fail.id === add_ab.id)
    assert(fail.onionHash == Crypto.sha256(add_ab.onionRoutingPacket))

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("fail to relay an htlc-add when amount is below the next hop's requirements") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(channelUpdate_bc.htlcMinimumMsat - 1, finalExpiry, paymentHash, hops.map(hop => hop.copy(lastUpdate = hop.lastUpdate.copy(feeBaseMsat = 0, feeProportionalMillionths = 0))))
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! channelUpdate_bc

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(AmountBelowMinimum(cmd.amountMsat, channelUpdate_bc)))

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("fail to relay an htlc-add when expiry does not match next hop's requirements") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    val hops1 = hops.updated(1, hops(1).copy(lastUpdate = hops(1).lastUpdate.copy(cltvExpiryDelta = 0)))
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! channelUpdate_bc

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(IncorrectCltvExpiry(cmd.expiry, channelUpdate_bc)))

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("fail to relay an htlc-add when expiry is too soon") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    val (cmd, _) = buildCommand(finalAmountMsat, 0, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! channelUpdate_bc

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(ExpiryTooSoon(channelUpdate_bc)))

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("fail an htlc-add at the final node when amount has been modified by second-to-last node") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // to simulate this we use a zero-hop route A->B where A is the 'attacker'
    val hops1 = hops.head :: Nil
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
    // and then manually build an htlc with a wrong expiry
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat - 1, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! channelUpdate_bc

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(FinalIncorrectHtlcAmount(add_ab.amountMsat)))

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("fail an htlc-add at the final node when expiry has been modified by second-to-last node") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // to simulate this we use a zero-hop route A->B where A is the 'attacker'
    val hops1 = hops.head :: Nil
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
    // and then manually build an htlc with a wrong expiry
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry - 1, cmd.onion)
    relayer ! channelUpdate_bc

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(FinalIncorrectCltvExpiry(add_ab.expiry)))

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("fail to relay an htlc-add when next channel's balance is too low") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    val (cmd, _) = buildCommand(finalAmountMsat, Globals.blockCount.get().toInt + 10, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! channelUpdate_bc

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd.message.upstream_opt === Some(add_ab))

    sender.send(relayer, AddHtlcFailed(new InsufficientFunds(channelId_bc, cmd.amountMsat, 100, 0, 0), Relayed(add_ab.channelId, add_ab.id, add_ab.amountMsat, cmd.amountMsat), Some(channelUpdate_bc)))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(TemporaryChannelFailure(channelUpdate_bc)))

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("fail to relay an htlc-add when next channel has too many inflight htlcs") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    val (cmd, _) = buildCommand(finalAmountMsat, Globals.blockCount.get().toInt + 10, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! channelUpdate_bc

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd.message.upstream_opt === Some(add_ab))

    sender.send(relayer, AddHtlcFailed(new TooManyAcceptedHtlcs(channelId_bc, 30), Relayed(add_ab.channelId, add_ab.id, add_ab.amountMsat, cmd.amountMsat), Some(channelUpdate_bc)))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(TemporaryChannelFailure(channelUpdate_bc)))

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("fail to relay an htlc-add when next channel has a timed out htlc (and is thus closing)") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    val (cmd, _) = buildCommand(finalAmountMsat, Globals.blockCount.get().toInt + 10, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)
    relayer ! channelUpdate_bc

    sender.send(relayer, ForwardAdd(add_ab))

    val fwd = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd.message.upstream_opt === Some(add_ab))

    sender.send(relayer, AddHtlcFailed(new HtlcTimedout(channelId_bc), Relayed(add_ab.channelId, add_ab.id, add_ab.amountMsat, cmd.amountMsat), Some(channelUpdate_bc)))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.reason == Right(PermanentChannelFailure))

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)
  }

  test("relay an htlc-fulfill") { case (relayer, register, _) =>
    val sender = TestProbe()
    val eventListener = TestProbe()

    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    // there isn't any corresponding htlc, it does not matter here
    val fulfill_cb = UpdateFulfillHtlc(channelId = channelId_bc, id = 42, paymentPreimage = "00" * 32)
    val origin = Relayed(channelId_ab, 150, 11000000L, 10000000L)
    sender.send(relayer, ForwardFulfill(fulfill_cb, origin))

    val fwd = register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(fwd.channelId === origin.originChannelId)
    assert(fwd.message.id === origin.originHtlcId)

    eventListener.expectMsg(PaymentRelayed(MilliSatoshi(origin.amountMsatIn), MilliSatoshi(origin.amountMsatOut), Crypto.sha256(fulfill_cb.paymentPreimage)))
  }

  test("relay an htlc-fail") { case (relayer, register, _) =>
    val sender = TestProbe()

    // there isn't any corresponding htlc, it does not matter here
    val fail_cb = UpdateFailHtlc(channelId = channelId_bc, id = 42, reason = Sphinx.createErrorPacket(BinaryData("01" * 32), TemporaryChannelFailure(channelUpdate_cd)))
    val origin = Relayed(channelId_ab, 150, 11000000L, 10000000L)
    sender.send(relayer, ForwardFail(fail_cb, origin))

    val fwd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId === origin.originChannelId)
    assert(fwd.message.id === origin.originHtlcId)
  }
}
