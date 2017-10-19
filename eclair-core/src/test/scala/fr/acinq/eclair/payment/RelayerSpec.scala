package fr.acinq.eclair.payment

import akka.actor.ActorRef
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Crypto}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.PaymentLifecycle.buildCommand
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{TestConstants, TestkitBaseClass}
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

  test("fail to relay an htlc-add when there is no available upstream channel") { case (relayer, register, paymentHandler) =>
    val sender = TestProbe()

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.paymentHash, cmd.expiry, cmd.onion)

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.id === add_ab.id)

    register.expectNoMsg(500 millis)
    paymentHandler.expectNoMsg(500 millis)

  }

  test("fail to relay an htlc-add when the onion is malformed") { case (relayer, register, paymentHandler) =>

    // TODO: we should use the new update_fail_malformed_htlc message (see BOLT 2)
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
    val (cmd, secrets) = buildCommand(channelUpdate_bc.htlcMinimumMsat - 1, finalExpiry, paymentHash, hops.map(hop => hop.copy(lastUpdate = hop.lastUpdate.copy(feeBaseMsat = 0, feeProportionalMillionths = 0))))
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
    val (cmd, secrets) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
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

    val (cmd, secrets) = buildCommand(finalAmountMsat, 0, paymentHash, hops)
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
    val (cmd, secrets) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
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
    val (cmd, secrets) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
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

  test("relay an htlc-fulfill") { case (relayer, register, _) =>
    val sender = TestProbe()
    val eventListener = TestProbe()

    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    // there isn't any corresponding htlc, it does not matter here
    val fulfill_cb = UpdateFulfillHtlc(channelId = channelId_bc, id = 42, paymentPreimage = "00" * 32)
    val origin = Relayed(channelId_ab, 150)
    sender.send(relayer, ForwardFulfill(fulfill_cb, origin))

    val fwd = register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(fwd.channelId === origin.originChannelId)
    assert(fwd.message.id === origin.originHtlcId)

    //eventListener.expectMsg(PaymentRelayed(MilliSatoshi(add_ab.amountMsat), MilliSatoshi(add_ab.amountMsat - cmd_bc.amountMsat), add_ab.paymentHash))
  }

  test("relay an htlc-fail") { case (relayer, register, _) =>
    val sender = TestProbe()

    // there isn't any corresponding htlc, it does not matter here
    val fail_cb = UpdateFailHtlc(channelId = channelId_bc, id = 42, reason = Sphinx.createErrorPacket(BinaryData("01" * 32), TemporaryChannelFailure(channelUpdate_cd)))
    val origin = Relayed(channelId_ab, 150)
    sender.send(relayer, ForwardFail(fail_cb, origin))

    val fwd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    assert(fwd.channelId === origin.originChannelId)
    assert(fwd.message.id === origin.originHtlcId)
  }
}
