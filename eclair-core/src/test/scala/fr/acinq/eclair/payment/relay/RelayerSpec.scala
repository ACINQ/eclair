/*
 * Copyright 2020 ACINQ SAS
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

package fr.acinq.eclair.payment.relay

import akka.actor.ActorRef
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{TypedActorContextOps, TypedActorRefOps}
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.payment.IncomingPaymentPacket.FinalPacket
import fr.acinq.eclair.payment.OutgoingPaymentPacket.{NodePayload, Upstream, buildOnion, buildOutgoingPayment}
import fr.acinq.eclair.payment.PaymentPacketSpec._
import fr.acinq.eclair.payment.relay.Relayer._
import fr.acinq.eclair.payment.send.{ClearRecipient, ClearTrampolineRecipient}
import fr.acinq.eclair.router.BaseRouterSpec.{blindedRouteFromHops, channelHopFromUpdate}
import fr.acinq.eclair.router.Router.{NodeHop, Route}
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair._
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.HexStringSyntax

import java.util.UUID
import scala.concurrent.duration.DurationInt

class RelayerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, relayer: akka.actor.ActorRef, router: TestProbe[Any], register: TestProbe[Any], childActors: ChildActors, paymentHandler: TestProbe[Any])

  override def withFixture(test: OneArgTest): Outcome = {
    // we are node B in the route A -> B -> C -> ....
    val disableTrampoline = test.tags.contains("trampoline-disabled")
    val nodeParams = TestConstants.Bob.nodeParams.copy(enableTrampolinePayment = !disableTrampoline)
    val router = TestProbe[Any]("router")
    val register = TestProbe[Any]("register")
    val paymentHandler = TestProbe[Any]("payment-handler")
    val triggerer = TestProbe[AsyncPaymentTriggerer.Command]("payment-triggerer")
    val probe = TestProbe[Any]()
    // we can't spawn top-level actors with akka typed
    testKit.spawn(Behaviors.setup[Any] { context =>
      val relayer = context.toClassic.actorOf(Relayer.props(nodeParams, router.ref.toClassic, register.ref.toClassic, paymentHandler.ref.toClassic, triggerer.ref))
      probe.ref ! relayer
      Behaviors.empty[Any]
    })
    val relayer = probe.expectMessageType[akka.actor.ActorRef]
    relayer ! GetChildActors(probe.ref.toClassic)
    val childActors = probe.expectMessageType[ChildActors]
    withFixture(test.toNoArgTest(FixtureParam(nodeParams, relayer, router, register, childActors, paymentHandler)))
  }

  val channelId_ab = randomBytes32()
  val channelId_bc = randomBytes32()

  test("relay an htlc-add") { f =>
    import f._

    // We make sure the channel relayer is initialized
    val sender = TestProbe[Relayer.OutgoingChannels]()
    childActors.channelRelayer ! ChannelRelayer.GetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels())
    assert(sender.expectMessageType[Relayer.OutgoingChannels].channels.isEmpty)

    // We publish a channel update, that should be picked up by the channel relayer
    val shortIds_bc = ShortIds(RealScidStatus.Final(RealShortChannelId(channelUpdate_bc.shortChannelId.toLong)), ShortChannelId.generateLocalAlias(), remoteAlias_opt = None)
    system.eventStream ! EventStream.Publish(LocalChannelUpdate(null, channelId_bc, shortIds_bc, c, None, channelUpdate_bc, makeCommitments(channelId_bc)))
    eventually(PatienceConfiguration.Timeout(30 seconds), PatienceConfiguration.Interval(1 second)) {
      childActors.channelRelayer ! ChannelRelayer.GetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels())
      val channels = sender.expectMessageType[Relayer.OutgoingChannels].channels
      require(channels.nonEmpty)
    }

    // we use this to build a valid onion
    val Right(payment) = buildOutgoingPayment(ActorRef.noSender, priv_a.privateKey, Upstream.Local(UUID.randomUUID()), paymentHash, Route(finalAmount, hops, None), ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret))
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(randomBytes32(), 123456, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None)
    relayer ! RelayForward(add_ab)
    register.expectMessageType[Register.Forward[CMD_ADD_HTLC]]
  }

  test("relay an htlc-add at the final node to the payment handler") { f =>
    import f._

    val Right(payment) = buildOutgoingPayment(ActorRef.noSender, priv_a.privateKey, Upstream.Local(UUID.randomUUID()), paymentHash, Route(finalAmount, hops.take(1), None), ClearRecipient(b, Features.empty, finalAmount, finalExpiry, paymentSecret))
    val add_ab = UpdateAddHtlc(channelId_ab, 123456, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None)
    relayer ! RelayForward(add_ab)

    val fp = paymentHandler.expectMessageType[FinalPacket]
    assert(fp.add == add_ab)
    assert(fp.payload == FinalPayload.Standard.createPayload(finalAmount, finalAmount, finalExpiry, paymentSecret))

    register.expectNoMessage(50 millis)
  }

  test("relay a trampoline htlc-add at the final node to the payment handler") { f =>
    import f._

    // We simulate a payment split between multiple trampoline routes.
    val totalAmount = finalAmount * 3
    val finalTrampolinePayload = NodePayload(b, FinalPayload.Standard.createPayload(finalAmount, totalAmount, finalExpiry, paymentSecret))
    val Right(trampolineOnion) = buildOnion(PaymentOnionCodecs.trampolineOnionPayloadLength, Seq(finalTrampolinePayload), paymentHash)
    val recipient = ClearRecipient(b, nodeParams.features.invoiceFeatures(), finalAmount, finalExpiry, randomBytes32(), nextTrampolineOnion_opt = Some(trampolineOnion.packet))
    val Right(payment) = buildOutgoingPayment(ActorRef.noSender, priv_a.privateKey, Upstream.Local(UUID.randomUUID()), paymentHash, Route(finalAmount, Seq(channelHopFromUpdate(priv_a.publicKey, b, channelUpdate_ab)), None), recipient)
    assert(payment.cmd.amount == finalAmount)
    assert(payment.cmd.cltvExpiry == finalExpiry)
    val add_ab = UpdateAddHtlc(channelId_ab, 123456, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None)
    relayer ! RelayForward(add_ab)

    val fp = paymentHandler.expectMessageType[FinalPacket]
    assert(fp.add == add_ab)
    assert(fp.payload.isInstanceOf[FinalPayload.Standard])
    assert(fp.payload.amount == finalAmount)
    assert(fp.payload.totalAmount == totalAmount)
    assert(fp.payload.expiry == finalExpiry)
    assert(fp.payload.asInstanceOf[FinalPayload.Standard].paymentSecret == paymentSecret)

    register.expectNoMessage(50 millis)
  }

  test("fail to relay an htlc-add when the onion is malformed") { f =>
    import f._

    // we use this to build a valid onion
    val Right(payment) = buildOutgoingPayment(ActorRef.noSender, priv_a.privateKey, Upstream.Local(UUID.randomUUID()), paymentHash, Route(finalAmount, hops, None), ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret))
    // and then manually build an htlc with an invalid onion (hmac)
    val add_ab = UpdateAddHtlc(channelId_ab, 123456, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion.copy(hmac = payment.cmd.onion.hmac.reverse), None)
    relayer ! RelayForward(add_ab)

    val fail = register.expectMessageType[Register.Forward[CMD_FAIL_MALFORMED_HTLC]].message
    assert(fail.id == add_ab.id)
    assert(fail.onionHash == Sphinx.hash(add_ab.onionRoutingPacket))
    assert(fail.failureCode == (FailureMessageCodecs.BADONION | FailureMessageCodecs.PERM | 5))

    register.expectNoMessage(50 millis)
  }

  test("fail to relay an htlc-add with invalid blinding data (introduction node)") { f =>
    import f._

    // we use an expired blinded route.
    val routeExpiry = CltvExpiry(nodeParams.currentBlockHeight - 10)
    val (_, blindedHop, recipient) = blindedRouteFromHops(finalAmount, finalExpiry, Seq(channelHopFromUpdate(b, c, channelUpdate_bc)), routeExpiry, paymentPreimage, hex"deadbeef")
    val route = Route(finalAmount, Seq(channelHopFromUpdate(priv_a.publicKey, b, channelUpdate_ab)), Some(blindedHop))
    val Right(payment) = buildOutgoingPayment(ActorRef.noSender, priv_a.privateKey, Upstream.Local(UUID.randomUUID()), paymentHash, route, recipient)
    val add_ab = UpdateAddHtlc(channelId_ab, 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextBlindingKey_opt)
    relayer ! RelayForward(add_ab)

    val fail = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id == add_ab.id)
    assert(fail.reason == Right(InvalidOnionBlinding(Sphinx.hash(add_ab.onionRoutingPacket))))
    assert(fail.delay_opt.nonEmpty)

    register.expectNoMessage(50 millis)
  }

  test("fail to relay an htlc-add with invalid blinding data (intermediate node)") { f =>
    import f._

    // we use an expired blinded route.
    val (route, recipient) = singleBlindedHop(routeExpiry = CltvExpiry(nodeParams.currentBlockHeight - 1))
    val Right(payment) = buildOutgoingPayment(ActorRef.noSender, priv_a.privateKey, Upstream.Local(UUID.randomUUID()), paymentHash, route, recipient)
    val add_ab = UpdateAddHtlc(channelId_ab, 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextBlindingKey_opt)
    relayer ! RelayForward(add_ab)

    val fail = register.expectMessageType[Register.Forward[CMD_FAIL_MALFORMED_HTLC]].message
    assert(fail.id == add_ab.id)
    assert(fail.onionHash == Sphinx.hash(add_ab.onionRoutingPacket))
    assert(fail.failureCode == (FailureMessageCodecs.BADONION | FailureMessageCodecs.PERM | 24))

    register.expectNoMessage(50 millis)
  }

  test("fail to relay a trampoline htlc-add when trampoline is disabled", Tag("trampoline-disabled")) { f =>
    import f._

    // we use this to build a valid trampoline onion inside a normal onion
    val invoiceFeatures = Features[Bolt11Feature](VariableLengthOnion -> Mandatory, PaymentSecret -> Mandatory, BasicMultiPartPayment -> Optional, PaymentMetadata -> Optional, TrampolinePaymentPrototype -> Optional)
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, None, paymentHash, priv_c.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures)
    val trampolineHop = NodeHop(b, c, channelUpdate_bc.cltvExpiryDelta, fee_b)
    val recipient = ClearTrampolineRecipient(invoice, finalAmount, finalExpiry, trampolineHop, randomBytes32())
    val Right(payment) = buildOutgoingPayment(ActorRef.noSender, priv_a.privateKey, Upstream.Local(UUID.randomUUID()), paymentHash, Route(recipient.trampolineAmount, Seq(channelHopFromUpdate(priv_a.publicKey, b, channelUpdate_ab)), Some(trampolineHop)), recipient)

    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId_ab, 123456, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None)
    relayer ! RelayForward(add_ab)

    val fail = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id == add_ab.id)
    assert(fail.reason == Right(RequiredNodeFeatureMissing()))

    register.expectNoMessage(50 millis)
  }

  test("relay htlc settled") { f =>
    import f._

    val replyTo = TestProbe[Any]()
    val add_ab = UpdateAddHtlc(channelId = channelId_ab, id = 42, amountMsat = 11000000 msat, paymentHash = ByteVector32.Zeroes, CltvExpiry(4200), TestConstants.emptyOnionPacket, None)
    val add_bc = UpdateAddHtlc(channelId_bc, 72, 1000 msat, paymentHash, CltvExpiry(1), TestConstants.emptyOnionPacket, None)
    val channelOrigin = Origin.ChannelRelayedHot(replyTo.ref.toClassic, add_ab, 1000 msat)
    val trampolineOrigin = Origin.TrampolineRelayedHot(replyTo.ref.toClassic, Seq(add_ab))

    val addSettled = Seq(
      RES_ADD_SETTLED(channelOrigin, add_bc, HtlcResult.OnChainFulfill(randomBytes32())),
      RES_ADD_SETTLED(channelOrigin, add_bc, HtlcResult.RemoteFulfill(UpdateFulfillHtlc(add_bc.channelId, add_bc.id, randomBytes32()))),
      RES_ADD_SETTLED(channelOrigin, add_bc, HtlcResult.OnChainFail(HtlcsTimedoutDownstream(channelId_bc, Set(add_bc)))),
      RES_ADD_SETTLED(channelOrigin, add_bc, HtlcResult.RemoteFail(UpdateFailHtlc(add_bc.channelId, add_bc.id, randomBytes32()))),
      RES_ADD_SETTLED(trampolineOrigin, add_bc, HtlcResult.OnChainFulfill(randomBytes32())),
      RES_ADD_SETTLED(trampolineOrigin, add_bc, HtlcResult.RemoteFulfill(UpdateFulfillHtlc(add_bc.channelId, add_bc.id, randomBytes32()))),
      RES_ADD_SETTLED(trampolineOrigin, add_bc, HtlcResult.OnChainFail(HtlcsTimedoutDownstream(channelId_bc, Set(add_bc)))),
      RES_ADD_SETTLED(trampolineOrigin, add_bc, HtlcResult.RemoteFail(UpdateFailHtlc(add_bc.channelId, add_bc.id, randomBytes32())))
    )

    for (res <- addSettled) {
      relayer ! res
      replyTo.expectMessage(res)
    }
  }

}
