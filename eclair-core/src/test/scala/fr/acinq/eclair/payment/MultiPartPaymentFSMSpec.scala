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

import akka.actor.ActorRef
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.{TestActorRef, TestProbe}
import fr.acinq.bitcoin.scala.{Block, ByteVector32}
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM._
import fr.acinq.eclair.wire.{IncorrectOrUnknownPaymentDetails, PayToOpenRequest, UpdateAddHtlc}
import fr.acinq.eclair.{CltvExpiry, LongToBtcAmount, MilliSatoshi, NodeParams, TestConstants, TestKitBaseClass, ToMilliSatoshiConversion, randomBytes32, wire}
import org.scalatest.funsuite.AnyFunSuiteLike
import scodec.bits.ByteVector

import scala.collection.immutable.Queue
import scala.concurrent.duration._

/**
 * Created by t-bast on 18/07/2019.
 */

class MultiPartPaymentFSMSpec extends TestKitBaseClass with AnyFunSuiteLike {

  import MultiPartPaymentFSMSpec._

  case class FixtureParam(nodeParams: NodeParams, handler: TestActorRef[MultiPartPaymentFSM], parent: TestProbe, eventListener: TestProbe) {
    def currentBlockHeight: Long = nodeParams.currentBlockHeight
  }

  def createFixture(paymentTimeout: FiniteDuration, totalAmount: MilliSatoshi): FixtureParam = {
    val parent = TestProbe()
    val nodeParams = TestConstants.Alice.nodeParams.copy(multiPartPaymentExpiry = paymentTimeout)
    val handler = TestActorRef[MultiPartPaymentFSM](props(nodeParams, paymentHash, totalAmount, parent.ref))
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    FixtureParam(nodeParams, handler, parent, eventListener)
  }

  test("timeout waiting for first htlc") {
    val f = createFixture(250 millis, 1000 msat)
    val monitor = TestProbe()
    f.handler ! SubscribeTransitionCallBack(monitor.ref)

    val CurrentState(_, WAITING_FOR_HTLC) = monitor.expectMsgClass(classOf[CurrentState[_]])
    val Transition(_, WAITING_FOR_HTLC, PAYMENT_FAILED) = monitor.expectMsgClass(classOf[Transition[_]])

    f.parent.expectMsg(MultiPartPaymentFailed(paymentHash, wire.PaymentTimeout, Queue.empty))
    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("timeout waiting for more htlcs") {
    val f = createFixture(250 millis, 1000 msat)
    val parts = Seq(
      createMultiPartHtlc(1000 msat, 150 msat, 1),
      createMultiPartHtlc(1000 msat, 100 msat, 2)
    )
    parts.foreach(p => f.parent.send(f.handler, p))

    val fail = f.parent.expectMsgType[MultiPartPaymentFailed]
    assert(fail.paymentHash === paymentHash)
    assert(fail.failure === wire.PaymentTimeout)
    assert(fail.parts.toSet === parts.toSet)

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("receive additional htlcs after timeout") {
    val f = createFixture(250 millis, 1000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 150 msat, 1))
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 100 msat, 2))
    assert(f.parent.expectMsgType[MultiPartPaymentFailed].parts.length === 2)

    val extraPart = createMultiPartHtlc(1000 msat, 300 msat, 3)
    f.parent.send(f.handler, extraPart)
    val fail = f.parent.expectMsgType[ExtraPaymentReceived]
    assert(fail.paymentHash === paymentHash)
    assert(fail.failure === Some(wire.PaymentTimeout))
    assert(fail.payment === extraPart)

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fail all if total amount is not consistent") {
    val f = createFixture(250 millis, 1000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 600 msat, 1))
    f.parent.send(f.handler, createMultiPartHtlc(1100 msat, 650 msat, 2))
    val fail = f.parent.expectMsgType[MultiPartPaymentFailed]
    assert(fail.paymentHash === paymentHash)
    assert(fail.failure === IncorrectOrUnknownPaymentDetails(1100 msat, f.currentBlockHeight))
    assert(fail.parts.length === 2)

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fail all if total pay-to-open is below minimum") {
    val f = createFixture(250 millis, 20000000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(20000000 msat, 16000000 msat, 1))
    val payToOpenAmount = 4000000.msat
    assert(payToOpenMinAmount > payToOpenAmount)
    f.parent.send(f.handler, createPayToOpenPart(20000000 msat, payToOpenAmount))
    val fail = f.parent.expectMsgType[MultiPartPaymentFailed]
    assert(fail.paymentHash === paymentHash)
    assert(fail.failure === IncorrectOrUnknownPaymentDetails(20000000 msat, f.currentBlockHeight))
    assert(fail.parts.length === 2)

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all when total amount reached") {
    val f = createFixture(10 seconds, 1000 msat)
    val parts = Seq(
      createMultiPartHtlc(1000 msat, 300 msat, 1),
      createMultiPartHtlc(1000 msat, 340 msat, 2),
      createMultiPartHtlc(1000 msat, 360 msat, 3)
    )
    parts.foreach(p => f.parent.send(f.handler, p))

    val paymentResult = f.parent.expectMsgType[MultiPartPaymentSucceeded]
    assert(paymentResult.paymentHash === paymentHash)
    assert(paymentResult.parts.toSet === parts.toSet)
    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all with amount higher than total amount") {
    val f = createFixture(10 seconds, 1000 msat)
    val parts = Seq(
      createMultiPartHtlc(1000 msat, 300 msat, 1),
      createMultiPartHtlc(1000 msat, 340 msat, 2),
      createMultiPartHtlc(1000 msat, 400 msat, 3)
    )
    parts.foreach(p => f.parent.send(f.handler, p))

    val paymentResult = f.parent.expectMsgType[MultiPartPaymentSucceeded]
    assert(paymentResult.paymentHash === paymentHash)
    assert(paymentResult.parts.toSet === parts.toSet)

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all with single htlc") {
    val f = createFixture(10 seconds, 1000 msat)
    val part = createMultiPartHtlc(1000 msat, 1000 msat, 1)
    f.parent.send(f.handler, part)

    val paymentResult = f.parent.expectMsgType[MultiPartPaymentSucceeded]
    assert(paymentResult.parts === Seq(part))

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all with single htlc and amount higher than total amount") {
    val f = createFixture(10 seconds, 1000 msat)
    val part = createMultiPartHtlc(1000 msat, 1100 msat, 1)
    f.parent.send(f.handler, part)

    val paymentResult = f.parent.expectMsgType[MultiPartPaymentSucceeded]
    assert(paymentResult.parts === Seq(part))

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all if total pay-to-open is above minimum") {
    val f = createFixture(250 millis, 20000000 msat)
    val parts = Seq(
      createMultiPartHtlc(20000000 msat, 6000000 msat, 1),
      createPayToOpenPart(20000000 msat, 7000000 msat),
      createPayToOpenPart(20000000 msat, 7000000 msat)
    )
    parts.foreach(p => f.parent.send(f.handler, p))

    val paymentResult = f.parent.expectMsgType[MultiPartPaymentSucceeded]
    assert(paymentResult.parts.toSet === parts.toSet)

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("receive additional htlcs after total amount reached") {
    val f = createFixture(10 seconds, 1000 msat)

    val parts = Seq(
      createMultiPartHtlc(1000 msat, 600 msat, 1),
      createMultiPartHtlc(1000 msat, 400 msat, 2)
    )
    parts.foreach(p => f.parent.send(f.handler, p))
    val paymentResult = f.parent.expectMsgType[MultiPartPaymentSucceeded]
    assert(paymentResult.parts.toSet === parts.toSet)
    f.parent.expectNoMsg(50 millis)

    val extra = createMultiPartHtlc(1000 msat, 300 msat, 3)
    f.parent.send(f.handler, extra)
    val extraneousPayment = f.parent.expectMsgType[ExtraPaymentReceived]
    assert(extraneousPayment.paymentHash === paymentHash)
    assert(extraneousPayment.payment === extra)

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("actor restart") {
    val f = createFixture(10 seconds, 1000 msat)
    val parts = Seq(
      createMultiPartHtlc(1000 msat, 600 msat, 1),
      createMultiPartHtlc(1000 msat, 400 msat, 2)
    )
    f.parent.send(f.handler, parts.head)

    f.handler.suspend()
    f.handler.resume(new IllegalArgumentException("something went wrong"))

    f.parent.send(f.handler, parts(1))
    val paymentResult = f.parent.expectMsgType[MultiPartPaymentSucceeded]
    assert(paymentResult.paymentHash === paymentHash)
    assert(paymentResult.parts.toSet === parts.toSet)
    f.parent.expectNoMsg(50 millis)
  }

  test("unknown message") {
    val f = createFixture(10 seconds, 1000 msat)
    val parts = Seq(
      createMultiPartHtlc(1000 msat, 600 msat, 1),
      createMultiPartHtlc(1000 msat, 400 msat, 2)
    )
    f.parent.send(f.handler, parts.head)
    f.parent.send(f.handler, "hello")
    f.parent.send(f.handler, parts(1))

    val paymentResult = f.parent.expectMsgType[MultiPartPaymentSucceeded]
    assert(paymentResult.parts.toSet === parts.toSet)
    f.parent.expectNoMsg(50 millis)
  }

}

object MultiPartPaymentFSMSpec {

  val paymentHash = randomBytes32

  def htlcIdToChannelId(htlcId: Long) = ByteVector32(ByteVector.fromLong(htlcId).padLeft(32))

  def createMultiPartHtlc(totalAmount: MilliSatoshi, htlcAmount: MilliSatoshi, htlcId: Long): HtlcPart = {
    val htlc = UpdateAddHtlc(htlcIdToChannelId(htlcId), htlcId, htlcAmount, paymentHash, CltvExpiry(42), TestConstants.emptyOnionPacket)
    HtlcPart(totalAmount, htlc)
  }

  val payToOpenMinAmount = 10000.sat.toMilliSatoshi

  def createPayToOpenPart(totalAmount: MilliSatoshi, payToOpenAmount: MilliSatoshi): PayToOpenPart =
    PayToOpenPart(
      totalAmount = totalAmount,
      payToOpen = PayToOpenRequest(
        chainHash = Block.RegtestGenesisBlock.blockId,
        fundingSatoshis = payToOpenAmount.truncateToSatoshi * 2,
        amountMsat = payToOpenAmount,
        payToOpenFee = 10 sat,
        paymentHash = paymentHash,
        expireAt = Long.MaxValue,
        htlc_opt = None,
        payToOpenMinAmount = payToOpenMinAmount),
      peer = ActorRef.noSender
    )

}