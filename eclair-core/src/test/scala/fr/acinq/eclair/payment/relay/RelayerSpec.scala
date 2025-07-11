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

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.eventstream.EventStream
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.{TypedActorContextOps, TypedActorRefOps}
import akka.testkit.TestKit.awaitCond
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, TxId}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.payment.IncomingPaymentPacket.FinalPacket
import fr.acinq.eclair.payment.OutgoingPaymentPacket.{NodePayload, buildOnion, buildOutgoingPayment}
import fr.acinq.eclair.payment.PaymentPacketSpec._
import fr.acinq.eclair.payment.relay.Relayer._
import fr.acinq.eclair.payment.send.{ClearRecipient, TrampolinePayment}
import fr.acinq.eclair.reputation.{Reputation, ReputationRecorder}
import fr.acinq.eclair.router.BaseRouterSpec.{blindedRouteFromHops, channelHopFromUpdate}
import fr.acinq.eclair.router.Router.Route
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload
import fr.acinq.eclair.wire.protocol._
import org.scalatest.concurrent.PatienceConfiguration
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

class RelayerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, relayer: akka.actor.ActorRef, router: TestProbe[Any], register: TestProbe[Any], childActors: ChildActors, paymentHandler: TestProbe[Any], reputationRecorder: TestProbe[ReputationRecorder.Command]) {
    def receiveConfidence(score: Reputation.Score): Unit = {
      val getConfidence = reputationRecorder.expectMessageType[ReputationRecorder.GetConfidence]
      assert(getConfidence.upstream.asInstanceOf[Upstream.Hot.Channel].receivedFrom == TestConstants.Alice.nodeParams.nodeId)
      getConfidence.replyTo ! score
    }
  }

  override def withFixture(test: OneArgTest): Outcome = {
    // we are node B in the route A -> B -> C -> ....
    val disableTrampoline = test.tags.contains("trampoline-disabled")
    val nodeParams = TestConstants.Bob.nodeParams.copy(enableTrampolinePayment = !disableTrampoline)
    val router = TestProbe[Any]("router")
    val register = TestProbe[Any]("register")
    val paymentHandler = TestProbe[Any]("payment-handler")
    val reputationRecorder = TestProbe[ReputationRecorder.Command]("reputation-recorder")
    val probe = TestProbe[Any]()
    // we can't spawn top-level actors with akka typed
    testKit.spawn(Behaviors.setup[Any] { context =>
      val relayer = context.toClassic.actorOf(Relayer.props(nodeParams, router.ref.toClassic, register.ref.toClassic, paymentHandler.ref.toClassic, Some(reputationRecorder.ref)))
      probe.ref ! relayer
      Behaviors.empty[Any]
    })
    val relayer = probe.expectMessageType[akka.actor.ActorRef]
    relayer ! GetChildActors(probe.ref.toClassic)
    val childActors = probe.expectMessageType[ChildActors]
    withFixture(test.toNoArgTest(FixtureParam(nodeParams, relayer, router, register, childActors, paymentHandler, reputationRecorder)))
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
    val aliases_bc = ShortIdAliases(Alias(channelUpdate_bc.shortChannelId.toLong), remoteAlias_opt = None)
    system.eventStream ! EventStream.Publish(LocalChannelUpdate(null, channelId_bc, aliases_bc, c, None, channelUpdate_bc, makeCommitments(channelId_bc)))
    eventually(PatienceConfiguration.Timeout(30 seconds), PatienceConfiguration.Interval(1 second)) {
      childActors.channelRelayer ! ChannelRelayer.GetOutgoingChannels(sender.ref.toClassic, GetOutgoingChannels())
      val channels = sender.expectMessageType[Relayer.OutgoingChannels].channels
      require(channels.nonEmpty)
    }

    // we use this to build a valid onion
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, hops, None), ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret), Reputation.Score.max)
    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(randomBytes32(), 123456, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None, Reputation.maxEndorsement, None)
    relayer ! RelayForward(add_ab, priv_a.publicKey, 0.05)
    receiveConfidence(Reputation.Score(0.3, 0.6))
    register.expectMessageType[Register.Forward[CMD_ADD_HTLC]]
  }

  test("relay an htlc-add at the final node to the payment handler") { f =>
    import f._

    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, hops.take(1), None), ClearRecipient(b, Features.empty, finalAmount, finalExpiry, paymentSecret), Reputation.Score.max)
    val add_ab = UpdateAddHtlc(channelId_ab, 123456, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None, Reputation.maxEndorsement, None)
    relayer ! RelayForward(add_ab, priv_a.publicKey, 0.05)

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
    val Right(trampolineOnion) = buildOnion(Seq(finalTrampolinePayload), paymentHash, None)
    val recipient = ClearRecipient(b, nodeParams.features.invoiceFeatures(), finalAmount, finalExpiry, randomBytes32(), nextTrampolineOnion_opt = Some(trampolineOnion.packet))
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, Seq(channelHopFromUpdate(priv_a.publicKey, b, channelUpdate_ab)), None), recipient, Reputation.Score.max)
    assert(payment.cmd.amount == finalAmount)
    assert(payment.cmd.cltvExpiry == finalExpiry)
    val add_ab = UpdateAddHtlc(channelId_ab, 123456, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, None, Reputation.maxEndorsement, None)
    relayer ! RelayForward(add_ab, priv_a.publicKey, 0.05)

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
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, Route(finalAmount, hops, None), ClearRecipient(e, Features.empty, finalAmount, finalExpiry, paymentSecret), Reputation.Score.max)
    // and then manually build an htlc with an invalid onion (hmac)
    val add_ab = UpdateAddHtlc(channelId_ab, 123456, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion.copy(hmac = payment.cmd.onion.hmac.reverse), None, Reputation.maxEndorsement, None)
    relayer ! RelayForward(add_ab, priv_a.publicKey, 0.05)

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
    val Right(payment) = buildOutgoingPayment(TestConstants.emptyOrigin, paymentHash, route, recipient, Reputation.Score.max)
    val add_ab = UpdateAddHtlc(channelId_ab, 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, Reputation.maxEndorsement, payment.cmd.fundingFee_opt)
    relayer ! RelayForward(add_ab, priv_a.publicKey, 0.05)

    val fail = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id == add_ab.id)
    assert(fail.reason == FailureReason.LocalFailure(InvalidOnionBlinding(Sphinx.hash(add_ab.onionRoutingPacket))))
    assert(fail.delay_opt.nonEmpty)

    register.expectNoMessage(50 millis)
  }

  test("fail to relay an htlc-add with invalid blinding data (intermediate node)") { f =>
    import f._

    // we use an expired blinded route.
    val Right(payment) = buildOutgoingBlindedPaymentAB(paymentHash, routeExpiry = CltvExpiry(nodeParams.currentBlockHeight - 1))
    val add_ab = UpdateAddHtlc(channelId_ab, 0, payment.cmd.amount, payment.cmd.paymentHash, payment.cmd.cltvExpiry, payment.cmd.onion, payment.cmd.nextPathKey_opt, Reputation.maxEndorsement, payment.cmd.fundingFee_opt)
    relayer ! RelayForward(add_ab, priv_a.publicKey, 0.05)

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
    val invoice = Bolt11Invoice(Block.RegtestGenesisBlock.hash, Some(finalAmount), paymentHash, priv_c.privateKey, Left("invoice"), CltvExpiryDelta(6), paymentSecret = paymentSecret, features = invoiceFeatures)
    val payment = TrampolinePayment.buildOutgoingPayment(b, invoice, finalExpiry)

    // and then manually build an htlc
    val add_ab = UpdateAddHtlc(channelId_ab, 123456, payment.trampolineAmount, invoice.paymentHash, payment.trampolineExpiry, payment.onion.packet, None, Reputation.maxEndorsement, None)
    relayer ! RelayForward(add_ab, priv_a.publicKey, 0.05)

    val fail = register.expectMessageType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(fail.id == add_ab.id)
    assert(fail.reason == FailureReason.LocalFailure(RequiredNodeFeatureMissing()))

    register.expectNoMessage(50 millis)
  }

  test("relay htlc settled") { f =>
    import f._

    val replyTo = TestProbe[Any]()
    val add_ab = UpdateAddHtlc(channelId_ab, 42, 11000000 msat, ByteVector32.Zeroes, CltvExpiry(4200), TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    val add_bc = UpdateAddHtlc(channelId_bc, 72, 1000 msat, paymentHash, CltvExpiry(1), TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    val channelOrigin = Origin.Hot(replyTo.ref.toClassic, Upstream.Hot.Channel(add_ab, TimestampMilli.now(), priv_a.publicKey, 0.1))
    val trampolineOrigin = Origin.Hot(replyTo.ref.toClassic, Upstream.Hot.Trampoline(List(Upstream.Hot.Channel(add_ab, TimestampMilli.now(), priv_a.publicKey, 0.1))))

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

  test("store on-the-fly funding preimage") { f =>
    import f._

    val replyTo = TestProbe[Any]()
    val add_ab = UpdateAddHtlc(channelId_ab, 17, 50_000 msat, paymentHash, CltvExpiry(800_000), TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    val add_bc = UpdateAddHtlc(channelId_bc, 21, 45_000 msat, paymentHash, CltvExpiry(799_000), TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, Some(LiquidityAds.FundingFee(1000 msat, TxId(randomBytes32()))))
    val originHot = Origin.Hot(replyTo.ref.toClassic, Upstream.Hot.Channel(add_ab, TimestampMilli.now(), randomKey().publicKey, 0.1))
    val originCold = Origin.Cold(originHot)

    val addFulfilled = Seq(
      RES_ADD_SETTLED(originHot, add_bc, HtlcResult.OnChainFulfill(randomBytes32())),
      RES_ADD_SETTLED(originHot, add_bc, HtlcResult.RemoteFulfill(UpdateFulfillHtlc(add_bc.channelId, add_bc.id, randomBytes32()))),
      RES_ADD_SETTLED(originCold, add_bc, HtlcResult.OnChainFulfill(randomBytes32())),
      RES_ADD_SETTLED(originCold, add_bc, HtlcResult.RemoteFulfill(UpdateFulfillHtlc(add_bc.channelId, add_bc.id, randomBytes32()))),
    )

    for (res <- addFulfilled) {
      val preimage = res.result.paymentPreimage
      val paymentHash = Crypto.sha256(preimage)
      assert(nodeParams.db.liquidity.getOnTheFlyFundingPreimage(paymentHash).isEmpty)
      relayer ! res
      awaitCond(nodeParams.db.liquidity.getOnTheFlyFundingPreimage(paymentHash).contains(preimage), 10 seconds)
    }
  }

}
