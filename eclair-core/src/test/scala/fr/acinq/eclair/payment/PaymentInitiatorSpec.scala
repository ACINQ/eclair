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

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.Features.BASIC_MULTI_PART_PAYMENT_OPTIONAL
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.payment.HtlcGenerationSpec._
import fr.acinq.eclair.payment.PaymentRequest.{ExtraHop, Features}
import fr.acinq.eclair.payment.send.MultiPartPaymentLifecycle.SendMultiPartPayment
import fr.acinq.eclair.payment.send.PaymentInitiator
import fr.acinq.eclair.payment.send.PaymentInitiator.{SendPaymentConfig, SendPaymentRequest}
import fr.acinq.eclair.payment.send.PaymentLifecycle.{SendPayment, SendPaymentToRoute}
import fr.acinq.eclair.router.RouteParams
import fr.acinq.eclair.wire.Onion.FinalLegacyPayload
import fr.acinq.eclair.{CltvExpiryDelta, LongToBtcAmount, NodeParams, TestConstants, randomKey}
import org.scalatest.{Outcome, fixture}

/**
 * Created by t-bast on 25/07/2019.
 */

class PaymentInitiatorSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, initiator: TestActorRef[PaymentInitiator], payFsm: TestProbe, multiPartPayFsm: TestProbe, sender: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = TestConstants.Alice.nodeParams
    val (sender, payFsm, multiPartPayFsm) = (TestProbe(), TestProbe(), TestProbe())
    class TestPaymentInitiator extends PaymentInitiator(nodeParams, TestProbe().ref, TestProbe().ref, TestProbe().ref) {
      // @formatter:off
      override def spawnPaymentFsm(cfg: SendPaymentConfig): ActorRef = {
        payFsm.ref ! cfg
        payFsm.ref
      }
      override def spawnMultiPartPaymentFsm(cfg: SendPaymentConfig): ActorRef = {
        multiPartPayFsm.ref ! cfg
        multiPartPayFsm.ref
      }
      // @formatter:on
    }
    val initiator = TestActorRef(new TestPaymentInitiator().asInstanceOf[PaymentInitiator])
    withFixture(test.toNoArgTest(FixtureParam(nodeParams, initiator, payFsm, multiPartPayFsm, sender)))
  }

  test("reject payment with unknown mandatory feature") { f =>
    import f._
    val unknownFeature = 42
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmountMsat), paymentHash, randomKey, "Some invoice", features = Some(Features(unknownFeature)))
    val req = SendPaymentRequest(finalAmountMsat + 100.msat, paymentHash, c, 1, CltvExpiryDelta(42), Some(pr))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    val fail = sender.expectMsgType[PaymentFailed]
    assert(fail.id === id)
    assert(fail.failures.head.isInstanceOf[LocalFailure])
  }

  test("forward payment with pre-defined route") { f =>
    import f._
    sender.send(initiator, SendPaymentRequest(finalAmountMsat, paymentHash, c, 1, predefinedRoute = Seq(a, b, c)))
    val paymentId = sender.expectMsgType[UUID]
    payFsm.expectMsg(SendPaymentConfig(paymentId, paymentId, None, paymentHash, c, None, storeInDb = true, publishEvent = true))
    payFsm.expectMsg(SendPaymentToRoute(paymentHash, Seq(a, b, c), FinalLegacyPayload(finalAmountMsat, Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(nodeParams.currentBlockHeight + 1))))
  }

  test("forward legacy payment") { f =>
    import f._
    val hints = Seq(Seq(ExtraHop(b, channelUpdate_bc.shortChannelId, feeBase = 10 msat, feeProportionalMillionths = 1, cltvExpiryDelta = CltvExpiryDelta(12))))
    val routeParams = RouteParams(randomize = true, 15 msat, 1.5, 5, CltvExpiryDelta(561), None)
    sender.send(initiator, SendPaymentRequest(finalAmountMsat, paymentHash, c, 1, CltvExpiryDelta(42), assistedRoutes = hints, routeParams = Some(routeParams)))
    val id1 = sender.expectMsgType[UUID]
    payFsm.expectMsg(SendPaymentConfig(id1, id1, None, paymentHash, c, None, storeInDb = true, publishEvent = true))
    payFsm.expectMsg(SendPayment(paymentHash, c, FinalLegacyPayload(finalAmountMsat, CltvExpiryDelta(42).toCltvExpiry(nodeParams.currentBlockHeight + 1)), 1, hints, Some(routeParams)))

    sender.send(initiator, SendPaymentRequest(finalAmountMsat, paymentHash, e, 3))
    val id2 = sender.expectMsgType[UUID]
    payFsm.expectMsg(SendPaymentConfig(id2, id2, None, paymentHash, e, None, storeInDb = true, publishEvent = true))
    payFsm.expectMsg(SendPayment(paymentHash, e, FinalLegacyPayload(finalAmountMsat, Channel.MIN_CLTV_EXPIRY_DELTA.toCltvExpiry(nodeParams.currentBlockHeight + 1)), 3))
  }

  test("forward multi-part payment") { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmountMsat), paymentHash, randomKey, "Some invoice", features = Some(Features(BASIC_MULTI_PART_PAYMENT_OPTIONAL)))
    val req = SendPaymentRequest(finalAmountMsat + 100.msat, paymentHash, c, 1, CltvExpiryDelta(42), Some(pr))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    multiPartPayFsm.expectMsg(SendPaymentConfig(id, id, None, paymentHash, c, Some(pr), storeInDb = true, publishEvent = true))
    multiPartPayFsm.expectMsg(SendMultiPartPayment(paymentHash, pr.paymentSecret.get, c, finalAmountMsat + 100.msat, req.finalExpiry(nodeParams.currentBlockHeight), 1))
  }

  test("forward multi-part payment with pre-defined route") { f =>
    import f._
    val pr = PaymentRequest(Block.LivenetGenesisBlock.hash, Some(finalAmountMsat), paymentHash, randomKey, "Some invoice", features = Some(Features(BASIC_MULTI_PART_PAYMENT_OPTIONAL)))
    val req = SendPaymentRequest(finalAmountMsat / 2, paymentHash, c, 1, paymentRequest = Some(pr), predefinedRoute = Seq(a, b, c))
    sender.send(initiator, req)
    val id = sender.expectMsgType[UUID]
    payFsm.expectMsg(SendPaymentConfig(id, id, None, paymentHash, c, Some(pr), storeInDb = true, publishEvent = true))
    val msg = payFsm.expectMsgType[SendPaymentToRoute]
    assert(msg.paymentHash === paymentHash)
    assert(msg.hops === Seq(a, b, c))
    assert(msg.finalPayload.amount === finalAmountMsat / 2)
    assert(msg.finalPayload.paymentSecret === pr.paymentSecret)
    assert(msg.finalPayload.totalAmount === finalAmountMsat)
  }

}
