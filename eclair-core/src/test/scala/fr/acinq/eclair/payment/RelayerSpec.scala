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

import akka.actor.typed.scaladsl.adapter.TypedActorRefOps
import akka.actor.{ActorRef, typed}
import akka.testkit.TestProbe
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.IncomingPacket.FinalPacket
import fr.acinq.eclair.payment.OutgoingPacket.{Upstream, buildCommand, buildPacket}
import fr.acinq.eclair.payment.relay.Relayer._
import fr.acinq.eclair.payment.relay.{ChannelRelayer, NodeRelayer, Relayer}
import fr.acinq.eclair.router.Router.{ChannelHop, Ignore, NodeHop}
import fr.acinq.eclair.router._
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, LongToBtcAmount, NodeParams, TestConstants, TestKitBaseClass, UInt64, nodeFee, randomBytes32}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.matchers.must.Matchers
import scodec.bits.ByteVector

import scala.collection.immutable.Queue
import scala.concurrent.duration._

/**
 * Created by PM on 29/08/2016.
 */

class RelayerSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike with Matchers {

  import PaymentPacketSpec._

  case class FixtureParam(nodeParams: NodeParams, relayer: ActorRef, channelRelayer: typed.ActorRef[ChannelRelayer.Command], nodeRelayer: typed.ActorRef[NodeRelayer.Command], router: TestProbe, register: TestProbe, paymentHandler: TestProbe, sender: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    within(30 seconds) {
      val nodeParams = TestConstants.Bob.nodeParams
      val (router, register) = (TestProbe(), TestProbe())
      val paymentHandler = TestProbe()
      // we are node B in the route A -> B -> C -> ....
      val relayer = system.actorOf(Relayer.props(nodeParams, router.ref, register.ref, paymentHandler.ref))
      val sender = TestProbe()
      sender.send(relayer, Relayer.GetChildActors(sender.ref))
      val childRelayers = sender.expectMsgType[Relayer.ChildActors]
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, relayer, childRelayers.channelRelayer, childRelayers.nodeRelayer, router, register, paymentHandler, TestProbe())))
    }
  }

  val channelId_ab = randomBytes32
  val channelId_bc = randomBytes32

  ignore("relay a trampoline htlc-add with retries") { f =>
    import f._
    val a =  PaymentPacketSpec.a

    // we tell the relayer about channel B-C
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    val totalAmount = finalAmount * 3 // we simulate a payment split between multiple trampoline routes.
    val trampolineHops = NodeHop(a, b, channelUpdate_ab.cltvExpiryDelta, 0 msat) :: NodeHop(b, c, nodeParams.expiryDelta, nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, finalAmount)) :: Nil
    val (trampolineAmount, trampolineExpiry, trampolineOnion) = buildPacket(Sphinx.TrampolinePacket)(paymentHash, trampolineHops, Onion.createMultiPartPayload(finalAmount, totalAmount, finalExpiry, paymentSecret))

    // A sends a multi-part payment to trampoline node B
    val secret_ab = randomBytes32
    val (cmd1, _) = buildCommand(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, ChannelHop(a, b, channelUpdate_ab) :: Nil, Onion.createTrampolinePayload(trampolineAmount - 10000000.msat, trampolineAmount, trampolineExpiry, secret_ab, trampolineOnion.packet))
    val add_ab1 = UpdateAddHtlc(channelId_ab, 561, cmd1.amount, cmd1.paymentHash, cmd1.cltvExpiry, cmd1.onion)
    val (cmd2, _) = buildCommand(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, ChannelHop(a, b, channelUpdate_ab) :: Nil, Onion.createTrampolinePayload(10000000.msat, trampolineAmount, trampolineExpiry, secret_ab, trampolineOnion.packet))
    val add_ab2 = UpdateAddHtlc(channelId_ab, 565, cmd2.amount, cmd2.paymentHash, cmd2.cltvExpiry, cmd2.onion)
    sender.send(relayer, RelayForward(add_ab1))
    sender.send(relayer, RelayForward(add_ab2))

    // A multi-part payment FSM should start to relay the payment.
    val routeRequest1 = router.expectMsgType[Router.RouteRequest]
    assert(routeRequest1.source === b)
    assert(routeRequest1.target === c)
    assert(routeRequest1.amount === finalAmount)
    assert(routeRequest1.allowMultiPart)
    assert(routeRequest1.ignore === Ignore.empty)
    val mppFsm1 = router.lastSender
    router.send(mppFsm1, Router.RouteResponse(Router.Route(finalAmount, ChannelHop(b, c, channelUpdate_bc) :: Nil) :: Nil))

    // first try
    val fwd1 = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd1.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd1.message.origin === Origin.TrampolineRelayedHot(fwd1.replyTo, Queue(add_ab1, add_ab2)))

    // channel returns an error
    sender.send(fwd1.replyTo, RES_ADD_FAILED(fwd1.message, HtlcValueTooHighInFlight(channelId_bc, UInt64(1000000000L), 1516977616L msat), Some(channelUpdate_bc)))

    // second try
    val routeRequest2 = router.expectMsgType[Router.RouteRequest]
    assert(routeRequest2.ignore.channels.map(_.shortChannelId) === Set(channelUpdate_bc.shortChannelId))
    val mppFsm2 = router.lastSender
    router.send(mppFsm2, Router.RouteResponse(Router.Route(finalAmount, ChannelHop(b, c, channelUpdate_bc) :: Nil) :: Nil))

    val fwd2 = register.expectMsgType[Register.ForwardShortId[CMD_ADD_HTLC]]
    assert(fwd2.shortChannelId === channelUpdate_bc.shortChannelId)
    assert(fwd2.message.origin === Origin.TrampolineRelayedHot(fwd2.replyTo, Queue(add_ab1, add_ab2)))

    // the downstream HTLC is successfully fulfilled
    val add_bc = UpdateAddHtlc(channelId_bc, 72, cmd1.amount + cmd2.amount, paymentHash, cmd1.cltvExpiry, onionRoutingPacket = TestConstants.emptyOnionPacket)
    val fulfill_ba = UpdateFulfillHtlc(channelId_bc, 72, paymentPreimage)
    sender.send(relayer, RES_ADD_SETTLED(fwd2.message.origin, add_bc, HtlcResult.RemoteFulfill(fulfill_ba)))

    // it should trigger a fulfill on the upstream HTLCs
    val fwd3 = register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(fwd3.channelId === channelId_ab)
    assert(fwd3.message === CMD_FULFILL_HTLC(561, paymentPreimage, commit = true))
    val fwd4 = register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(fwd4.channelId === channelId_ab)
    assert(fwd4.message === CMD_FULFILL_HTLC(565, paymentPreimage, commit = true))
  }

  ignore("fail to relay an htlc-add when the onion is malformed") { f =>
    import f._

    // we use this to build a valid onion
    val (cmd, _) = buildCommand(ActorRef.noSender, Upstream.Local(UUID.randomUUID()), paymentHash, hops, FinalLegacyPayload(finalAmount, finalExpiry))
    // and then manually build an htlc with an invalid onion (hmac)
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 123456, cmd.amount, cmd.paymentHash, cmd.cltvExpiry, cmd.onion.copy(hmac = cmd.onion.hmac.reverse))
    relayer ! LocalChannelUpdate(null, channelId_bc, channelUpdate_bc.shortChannelId, c, None, channelUpdate_bc, makeCommitments(channelId_bc))

    sender.send(relayer, RelayForward(add_ab))

    val fail = register.expectMsgType[Register.Forward[CMD_FAIL_MALFORMED_HTLC]].message
    assert(fail.id === add_ab.id)
    assert(fail.onionHash == Sphinx.PaymentPacket.hash(add_ab.onionRoutingPacket))
    assert(fail.failureCode === (FailureMessageCodecs.BADONION | FailureMessageCodecs.PERM | 5))

    register.expectNoMsg(50 millis)
    paymentHandler.expectNoMsg(50 millis)
  }

  ignore("relay a trampoline htlc-fulfill") { f =>
    testRelayTrampolineHtlcFulfill(f, onChain = false)
  }

  ignore("relay a trampoline on-chain htlc-fulfill") { f =>
    testRelayTrampolineHtlcFulfill(f, onChain = true)
  }

  def testRelayTrampolineHtlcFulfill(f: FixtureParam, onChain: Boolean): Unit = {
    import f._
    val a =  PaymentPacketSpec.a

    // A sends a multi-payment to trampoline node B.
    val preimage = randomBytes32
    val paymentHash = Crypto.sha256(preimage)
    val trampolineHops = NodeHop(a, b, channelUpdate_ab.cltvExpiryDelta, 0 msat) :: NodeHop(b, c, nodeParams.expiryDelta, nodeFee(nodeParams.feeBase, nodeParams.feeProportionalMillionth, finalAmount)) :: Nil
    val (trampolineAmount, trampolineExpiry, trampolineOnion) = buildPacket(Sphinx.TrampolinePacket)(paymentHash, trampolineHops, Onion.createMultiPartPayload(finalAmount, finalAmount, finalExpiry, paymentSecret))
    val secret_ab = randomBytes32
    val payFSM = TestProbe()
    val (cmd1, _) = buildCommand(payFSM.ref, Upstream.Local(UUID.randomUUID()), paymentHash, ChannelHop(a, b, channelUpdate_ab) :: Nil, Onion.createTrampolinePayload(trampolineAmount - 10000000.msat, trampolineAmount, trampolineExpiry, secret_ab, trampolineOnion.packet))
    val add_ab1 = UpdateAddHtlc(channelId_ab, 561, cmd1.amount, cmd1.paymentHash, cmd1.cltvExpiry, cmd1.onion)
    val (cmd2, _) = buildCommand(payFSM.ref, Upstream.Local(UUID.randomUUID()), paymentHash, ChannelHop(a, b, channelUpdate_ab) :: Nil, Onion.createTrampolinePayload(10000000.msat, trampolineAmount, trampolineExpiry, secret_ab, trampolineOnion.packet))
    val add_ab2 = UpdateAddHtlc(channelId_ab, 565, cmd2.amount, cmd2.paymentHash, cmd2.cltvExpiry, cmd2.onion)
    sender.send(relayer, RelayForward(add_ab1))
    sender.send(relayer, RelayForward(add_ab2))

    // A multi-part payment FSM is started to relay the payment downstream.
    val routeRequest = router.expectMsgType[Router.RouteRequest]
    assert(routeRequest.allowMultiPart)

    // We simulate a fake htlc fulfill for the downstream channel.
    val add_bc = UpdateAddHtlc(channelId_bc, 72, 1000 msat, paymentHash, CltvExpiry(1), null)
    val forwardFulfill: RES_ADD_SETTLED[Origin, HtlcResult.Fulfill] = if (onChain) {
      RES_ADD_SETTLED(cmd1.origin, add_bc, HtlcResult.OnChainFulfill(preimage))
    } else {
      RES_ADD_SETTLED(cmd1.origin, add_bc, HtlcResult.RemoteFulfill(UpdateFulfillHtlc(add_bc.channelId, add_bc.id, preimage)))
    }
    sender.send(relayer, forwardFulfill)

    // the FSM responsible for the payment should receive the fulfill and emit a preimage event.
    payFSM.expectMsg(forwardFulfill)
    //    nodeRelayer ! NodeRelayer., NodeRelay.WrappedPreimageReceived(PreimageReceived(paymentHash, preimage)))

    // the payment should be immediately fulfilled upstream.
    val upstream1 = register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]
    val upstream2 = register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(Set(upstream1.message, upstream2.message) === Set(
      CMD_FULFILL_HTLC(561, preimage, commit = true),
      CMD_FULFILL_HTLC(565, preimage, commit = true)
    ))

    register.expectNoMsg(50 millis)
    payFSM.expectNoMsg(50 millis)
  }

  ignore("relay an htlc-fail") { f =>
    import f._

    // we build a fake htlc for the downstream channel
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 42, amountMsat = 11000000 msat, paymentHash = ByteVector32.Zeroes, CltvExpiry(4200), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 72, amountMsat = 10000000 msat, paymentHash = ByteVector32.Zeroes, CltvExpiry(4200), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val fail_ba = UpdateFailHtlc(channelId = channelId_bc, id = add_bc.id, reason = Sphinx.FailurePacket.create(ByteVector32(ByteVector.fill(32)(1)), TemporaryChannelFailure(channelUpdate_cd)))
    val origin = Origin.ChannelRelayedHot(channelRelayer.toClassic, add_ab, 10000000 msat)
    for (fail <- Seq(RES_ADD_SETTLED(origin, add_bc, HtlcResult.RemoteFail(fail_ba)), RES_ADD_SETTLED(origin, add_bc, HtlcResult.OnChainFail(HtlcOverriddenByLocalCommit(channelId_bc, add_bc))))) {
      sender.send(relayer, fail)
      val fwd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
      assert(fwd.channelId === origin.originChannelId)
      assert(fwd.message.id === origin.originHtlcId)
    }
  }

  ignore("relay a trampoline htlc-fail") { f =>
    import f._

    val payFSM = TestProbe()

    // upstream htlcs
    val upstream_add_1 = UpdateAddHtlc(channelId = channelId_ab, id = 42, amountMsat = 4000000 msat, paymentHash = ByteVector32.Zeroes, CltvExpiry(4200), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val upstream_add_2 = UpdateAddHtlc(channelId = randomBytes32, id = 7, amountMsat = 5000000 msat, paymentHash = ByteVector32.Zeroes, CltvExpiry(4200), onionRoutingPacket = TestConstants.emptyOnionPacket)

    // we build a fake htlc for the downstream channel
    val add_bc = UpdateAddHtlc(channelId = channelId_bc, id = 72, amountMsat = 10000000 msat, paymentHash = ByteVector32.Zeroes, CltvExpiry(4200), onionRoutingPacket = TestConstants.emptyOnionPacket)
    val fail_ba = UpdateFailHtlc(channelId = channelId_bc, id = 72, reason = Sphinx.FailurePacket.create(ByteVector32(ByteVector.fill(32)(1)), TemporaryChannelFailure(channelUpdate_cd)))
    val origin = Origin.TrampolineRelayedHot(payFSM.ref, upstream_add_1 :: upstream_add_2 :: Nil)

    // a remote failure should be forwarded to the FSM responsible for the payment to trigger the retry mechanism.
    val remoteFailure = RES_ADD_SETTLED(origin, add_bc, HtlcResult.RemoteFail(fail_ba))
    sender.send(relayer, remoteFailure)
    payFSM.expectMsg(remoteFailure)
    payFSM.expectNoMsg(100 millis)

    // same for an on-chain downstream failure.
    val onChainFailure = RES_ADD_SETTLED(origin, add_bc, HtlcResult.OnChainFail(HtlcsTimedoutDownstream(channelId_bc, Set(add_bc))))
    sender.send(relayer, onChainFailure)
    payFSM.expectMsg(onChainFailure)
    payFSM.expectNoMsg(100 millis)
  }

}
