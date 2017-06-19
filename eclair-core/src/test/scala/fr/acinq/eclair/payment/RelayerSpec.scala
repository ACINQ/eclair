package fr.acinq.eclair.payment

import akka.actor.ActorRef
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{BinaryData, Crypto, MilliSatoshi, OutPoint, Transaction, TxIn}
import fr.acinq.eclair.TestkitBaseClass
import fr.acinq.eclair.blockchain.WatchEventSpent
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.crypto.Sphinx.ErrorPacket
import fr.acinq.eclair.payment.PaymentLifecycle.buildCommand
import fr.acinq.eclair.transactions.Scripts
import fr.acinq.eclair.wire._
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

  def dummyDataNormal(channelId: BinaryData) = DATA_NORMAL(Commitments(null, null, null, null, null, null, 0, 0, null, null, null, channelId), None, None, None, None)

  type FixtureParam = Tuple2[ActorRef, TestProbe]

  override def withFixture(test: OneArgTest) = {

    within(30 seconds) {
      val paymentHandler = TestProbe()
      // we are node B in the route A -> B -> C -> ....
      val relayer = system.actorOf(Relayer.props(priv_b, paymentHandler.ref))
      relayer ! channelUpdate_bc
      test((relayer, paymentHandler))
    }
  }

  // node c is the next node in the route
  val nodeId_a = PublicKey(a)
  val nodeId_c = PublicKey(c)
  val channelId_ab: BinaryData = "65514354" * 8
  val channelId_bc: BinaryData = "64864544" * 8

  test("add a channel") { case (relayer, _) =>
    val sender = TestProbe()
    val channel_bc = TestProbe()
    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_bc)))
    sender.send(relayer, 'channels)
    val upstreams = sender.expectMsgType[Map[BinaryData, ActorRef]]
    assert(upstreams === Map(channelId_bc -> channel_bc.ref))
  }

  test("remove a channel (mutual close)") { case (relayer, _) =>
    val sender = TestProbe()
    val channel_bc = TestProbe()

    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, WAIT_FOR_FUNDING_LOCKED, NORMAL, DATA_NORMAL(Commitments(null, null, null, null, null, null, 0, 0, null, null, null, channelId_bc), None, None, None, None)))
    sender.send(relayer, 'channels)
    val upstreams1 = sender.expectMsgType[Map[BinaryData, ActorRef]]
    assert(upstreams1 === Map(channelId_bc -> channel_bc.ref))

    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, SHUTDOWN, NEGOTIATING, DATA_NEGOTIATING(Commitments(null, null, null, null, null, null, 0, 0, null, null, null, channelId_bc), null, null, null)))
    sender.send(relayer, 'channels)
    val upstreams2 = sender.expectMsgType[Map[BinaryData, ActorRef]]
    assert(upstreams2 === Map.empty)
  }

  test("remove a channel (unilateral close)") { case (relayer, _) =>
    val sender = TestProbe()
    val channel_bc = TestProbe()

    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_bc)))
    sender.send(relayer, 'channels)
    val upstreams1 = sender.expectMsgType[Map[BinaryData, ActorRef]]
    assert(upstreams1 === Map(channelId_bc -> channel_bc.ref))

    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, NORMAL, CLOSING, DATA_CLOSING(Commitments(null, null, null, null, null, null, 0, 0, null, null, null, channelId_bc), Some(null), None, None, None, Nil)))
    sender.send(relayer, 'channels)

    val upstreams2 = sender.expectMsgType[Map[BinaryData, ActorRef]]
    assert(upstreams2 === Map.empty)
  }

  test("relay an htlc-add") { case (relayer, paymentHandler) =>
    val sender = TestProbe()
    val channel_bc = TestProbe()

    val add_ab = {
      val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
      // and then manually build an htlc
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
    }

    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_bc)))
    sender.send(relayer, ShortChannelIdAssigned(channel_bc.ref, channelId_bc, channelUpdate_bc.shortChannelId))
    sender.send(relayer, ForwardAdd(add_ab))

    sender.expectNoMsg(1 second)
    val cmd_bc = channel_bc.expectMsgType[CMD_ADD_HTLC]

    paymentHandler.expectNoMsg(1 second)

    assert(cmd_bc.upstream_opt === Some(add_ab))
  }

  test("fail to relay an htlc-add when there is no available upstream channel") { case (relayer, paymentHandler) =>
    val sender = TestProbe()
    val channel_bc = TestProbe()

    val add_ab = {
      val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
      // and then manually build an htlc
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
    }

    sender.send(relayer, ShortChannelIdAssigned(channel_bc.ref, channelId_bc, channelUpdate_bc.shortChannelId))
    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    channel_bc.expectNoMsg(1 second)
    paymentHandler.expectNoMsg(1 second)

    assert(fail.id === add_ab.id)
  }

  test("fail to relay an htlc-add when the onion is malformed") { case (relayer, paymentHandler) =>

    // TODO: we should use the new update_fail_malformed_htlc message (see BOLT 2)
    val sender = TestProbe()
    val channel_bc = TestProbe()

    val add_ab = {
      val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
      // and then manually build an htlc
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.expiry, cmd.paymentHash, "00" * Sphinx.PacketLength)
    }

    sender.send(relayer, ShortChannelIdAssigned(channel_bc.ref, channelId_bc, channelUpdate_bc.shortChannelId))
    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_MALFORMED_HTLC]
    assert(fail.onionHash == Crypto.sha256(add_ab.onionRoutingPacket))
    channel_bc.expectNoMsg(1 second)
    paymentHandler.expectNoMsg(1 second)

    assert(fail.id === add_ab.id)
  }

  test("fail to relay an htlc-add when amount is below the next hop's requirements") { case (relayer, paymentHandler) =>
    val sender = TestProbe()
    val channel_bc = TestProbe()

    val (cmd, secrets) = buildCommand(channelUpdate_bc.htlcMinimumMsat - 1, finalExpiry, paymentHash, hops.map(hop => hop.copy(lastUpdate = hop.lastUpdate.copy(feeBaseMsat = 0, feeProportionalMillionths = 0))))
    val add_ab = {
      // and then manually build an htlc
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
    }

    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_bc)))
    sender.send(relayer, ShortChannelIdAssigned(channel_bc.ref, channelId_bc, channelUpdate_bc.shortChannelId))
    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.reason == Right(AmountBelowMinimum(cmd.amountMsat, channelUpdate_bc)))
    channel_bc.expectNoMsg(1 second)
    paymentHandler.expectNoMsg(1 second)

    assert(fail.id === add_ab.id)
  }

  test("fail to relay an htlc-add when expiry does not match next hop's requirements") { case (relayer, paymentHandler) =>
    val sender = TestProbe()
    val channel_bc = TestProbe()

    val hops1 = hops.updated(1, hops(1).copy(lastUpdate = hops(1).lastUpdate.copy(cltvExpiryDelta = 0)))
    val (cmd, secrets) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
    val add_ab = {
      // and then manually build an htlc
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
    }

    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_bc)))
    sender.send(relayer, ShortChannelIdAssigned(channel_bc.ref, channelId_bc, channelUpdate_bc.shortChannelId))
    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.reason == Right(IncorrectCltvExpiry(cmd.expiry, channelUpdate_bc)))
    channel_bc.expectNoMsg(1 second)
    paymentHandler.expectNoMsg(1 second)

    assert(fail.id === add_ab.id)
  }

  test("fail to relay an htlc-add when expiry is too soon") { case (relayer, paymentHandler) =>
    val sender = TestProbe()
    val channel_bc = TestProbe()

    val (cmd, secrets) = buildCommand(finalAmountMsat, 0, paymentHash, hops)
    val add_ab = {
      // and then manually build an htlc
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
    }

    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_bc)))
    sender.send(relayer, ShortChannelIdAssigned(channel_bc.ref, channelId_bc, channelUpdate_bc.shortChannelId))
    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.reason == Right(ExpiryTooSoon(channelUpdate_bc)))
    channel_bc.expectNoMsg(1 second)
    paymentHandler.expectNoMsg(1 second)

    assert(fail.id === add_ab.id)
  }
  
  test("fail an htlc-add at the final node when amount has been modified by second-to-last node") { case (relayer, paymentHandler) =>
    val sender = TestProbe()
    val channel_bc = TestProbe()

    // to simulate this we use a zero-hop route A->B where A is the 'attacker'
    val hops1 = hops.head :: Nil
    val (cmd, secrets) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
    val add_ab = {
      // and then manually build an htlc with a wrong expiry
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat - 1, cmd.expiry, cmd.paymentHash, cmd.onion)
    }

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.reason == Right(FinalIncorrectHtlcAmount(add_ab.amountMsat)))
    channel_bc.expectNoMsg(1 second)
    paymentHandler.expectNoMsg(1 second)

    assert(fail.id === add_ab.id)
  }

  test("fail an htlc-add at the final node when expiry has been modified by second-to-last node") { case (relayer, paymentHandler) =>
    val sender = TestProbe()
    val channel_bc = TestProbe()

    // to simulate this we use a zero-hop route A->B where A is the 'attacker'
    val hops1 = hops.head :: Nil
    val (cmd, secrets) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops1)
    val add_ab = {
      // and then manually build an htlc with a wrong expiry
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.expiry - 1, cmd.paymentHash, cmd.onion)
    }

    sender.send(relayer, ForwardAdd(add_ab))

    val fail = sender.expectMsgType[CMD_FAIL_HTLC]
    assert(fail.reason == Right(FinalIncorrectCltvExpiry(add_ab.expiry)))
    channel_bc.expectNoMsg(1 second)
    paymentHandler.expectNoMsg(1 second)

    assert(fail.id === add_ab.id)
  }

  test("relay an htlc-fulfill") { case (relayer, paymentHandler) =>
    val sender = TestProbe()
    val channel_ab = TestProbe()
    val channel_bc = TestProbe()
    val eventListener = TestProbe()

    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    val add_ab = {
      val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
      // and then manually build an htlc
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
    }

    sender.send(relayer, ChannelStateChanged(channel_ab.ref, null, nodeId_a, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_ab)))
    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_bc)))
    sender.send(relayer, ShortChannelIdAssigned(channel_bc.ref, channelId_bc, channelUpdate_bc.shortChannelId))
    sender.send(relayer, ForwardAdd(add_ab))
    val cmd_bc = channel_bc.expectMsgType[CMD_ADD_HTLC]
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 987451, amountMsat = cmd_bc.amountMsat, expiry = cmd_bc.expiry, paymentHash = cmd_bc.paymentHash, onionRoutingPacket = cmd_bc.onion)
    sender.send(relayer, AddHtlcSucceeded(add_bc, Relayed(channel_ab.ref, add_ab)))
    // preimage is wrong, does not matter here
    val fulfill_cb = UpdateFulfillHtlc(channelId = add_bc.channelId, id = add_bc.id, paymentPreimage = "00" * 32)
    sender.send(relayer, ForwardFulfill(fulfill_cb))

    val fulfill_ba = channel_ab.expectMsgType[CMD_FULFILL_HTLC]

    eventListener.expectMsg(PaymentRelayed(MilliSatoshi(add_ab.amountMsat), MilliSatoshi(add_ab.amountMsat - cmd_bc.amountMsat), add_ab.paymentHash))

    assert(fulfill_ba.id === add_ab.id)
  }

  test("relay an htlc-fail") { case (relayer, paymentHandler) =>
    val sender = TestProbe()
    val channel_ab = TestProbe()
    val channel_bc = TestProbe()

    val add_ab = {
      val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
      // and then manually build an htlc
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
    }

    sender.send(relayer, ChannelStateChanged(channel_ab.ref, null, nodeId_a, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_ab)))
    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_bc)))
    sender.send(relayer, ShortChannelIdAssigned(channel_bc.ref, channelId_bc, channelUpdate_bc.shortChannelId))
    sender.send(relayer, ForwardAdd(add_ab))
    val cmd_bc = channel_bc.expectMsgType[CMD_ADD_HTLC]
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 987451, amountMsat = cmd_bc.amountMsat, expiry = cmd_bc.expiry, paymentHash = cmd_bc.paymentHash, onionRoutingPacket = cmd_bc.onion)
    sender.send(relayer, AddHtlcSucceeded(add_bc, Relayed(channel_ab.ref, add_ab)))
    val fail_cb = UpdateFailHtlc(channelId = add_bc.channelId, id = add_bc.id, reason = Sphinx.createErrorPacket(BinaryData("01" * 32), TemporaryChannelFailure(channelUpdate_cd)))
    sender.send(relayer, ForwardFail(fail_cb))

    val fulfill_ba = channel_ab.expectMsgType[CMD_FAIL_HTLC]

    assert(fulfill_ba.id === add_ab.id)

  }

  test("extract a payment preimage from an onchain tx (extract from witnessHtlcSuccess script)") { case (relayer, paymentHandler) =>
    val sender = TestProbe()
    val channel_ab = TestProbe()
    val channel_bc = TestProbe()

    val add_ab = {
      val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
      // and then manually build an htlc
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
    }

    sender.send(relayer, ChannelStateChanged(channel_ab.ref, null, nodeId_a, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_ab)))
    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_bc)))
    sender.send(relayer, ShortChannelIdAssigned(channel_bc.ref, channelId_bc, channelUpdate_bc.shortChannelId))
    sender.send(relayer, ForwardAdd(add_ab))
    val cmd_bc = channel_bc.expectMsgType[CMD_ADD_HTLC]
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 987451, amountMsat = cmd_bc.amountMsat, expiry = cmd_bc.expiry, paymentHash = cmd_bc.paymentHash, onionRoutingPacket = cmd_bc.onion)
    sender.send(relayer, AddHtlcSucceeded(add_bc, Relayed(channel_ab.ref, add_ab)))

    // actual test starts here
    val tx = Transaction(version = 0, txIn = TxIn(outPoint = OutPoint("22" * 32, 0), signatureScript = "", sequence = 0, witness = Scripts.witnessHtlcSuccess("11" * 70, "22" * 70, paymentPreimage, "33" * 130)) :: Nil, txOut = Nil, lockTime = 0)
    sender.send(relayer, WatchEventSpent(BITCOIN_HTLC_SPENT, tx))
    val cmd_ab = channel_ab.expectMsgType[CMD_FULFILL_HTLC]

    assert(cmd_ab.id === add_ab.id)

  }

  test("extract a payment preimage from an onchain tx (extract from witnessClaimHtlcSuccessFromCommitTx script)") { case (relayer, paymentHandler) =>
    val sender = TestProbe()
    val channel_ab = TestProbe()
    val channel_bc = TestProbe()

    val add_ab = {
      val (cmd, _) = buildCommand(finalAmountMsat, finalExpiry, paymentHash, hops)
      // and then manually build an htlc
      UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amountMsat, cmd.expiry, cmd.paymentHash, cmd.onion)
    }

    sender.send(relayer, ChannelStateChanged(channel_ab.ref, null, nodeId_a, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_ab)))
    sender.send(relayer, ChannelStateChanged(channel_bc.ref, null, nodeId_c, WAIT_FOR_FUNDING_LOCKED, NORMAL, dummyDataNormal(channelId_bc)))
    sender.send(relayer, ShortChannelIdAssigned(channel_bc.ref, channelId_bc, channelUpdate_bc.shortChannelId))
    sender.send(relayer, ForwardAdd(add_ab))
    val cmd_bc = channel_bc.expectMsgType[CMD_ADD_HTLC]
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 987451, amountMsat = cmd_bc.amountMsat, expiry = cmd_bc.expiry, paymentHash = cmd_bc.paymentHash, onionRoutingPacket = cmd_bc.onion)
    sender.send(relayer, AddHtlcSucceeded(add_bc, Relayed(channel_ab.ref, add_ab)))

    // actual test starts here
    val tx = Transaction(version = 0, txIn = TxIn(outPoint = OutPoint("22" * 32, 0), signatureScript = "", sequence = 0, witness = Scripts.witnessClaimHtlcSuccessFromCommitTx("11" * 70, paymentPreimage, "33" * 130)) :: Nil, txOut = Nil, lockTime = 0)
    sender.send(relayer, WatchEventSpent(BITCOIN_HTLC_SPENT, tx))
    val cmd_ab = channel_ab.expectMsgType[CMD_FULFILL_HTLC]

    assert(cmd_ab.id === add_ab.id)

  }
}
