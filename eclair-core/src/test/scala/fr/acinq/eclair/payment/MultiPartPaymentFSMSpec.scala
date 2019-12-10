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

import akka.actor.ActorSystem
import akka.actor.FSM.{CurrentState, SubscribeTransitionCallBack, Transition}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM.PendingPayment
import fr.acinq.eclair.wire.{IncorrectOrUnknownPaymentDetails, UpdateAddHtlc}
import fr.acinq.eclair.{CltvExpiry, LongToBtcAmount, MilliSatoshi, NodeParams, TestConstants, randomBytes32, wire}
import org.scalatest.FunSuiteLike
import scodec.bits.ByteVector

import scala.collection.immutable.Queue
import scala.concurrent.duration._

/**
 * Created by t-bast on 18/07/2019.
 */

class MultiPartPaymentFSMSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  import MultiPartPaymentFSM._
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

    f.parent.expectMsg(MultiPartHtlcFailed(paymentHash, wire.PaymentTimeout, Queue.empty))
    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("timeout waiting for more htlcs") {
    val f = createFixture(250 millis, 1000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 150 msat, 1))
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 100 msat, 2))

    val fail = f.parent.expectMsgType[MultiPartHtlcFailed]
    assert(fail.paymentHash === paymentHash)
    assert(fail.failure === wire.PaymentTimeout)
    assert(setTimestamp(fail.parts, 0).toSet === Set(
      PendingPayment(1, PartialPayment(150 msat, htlcIdToChannelId(1), 0)),
      PendingPayment(2, PartialPayment(100 msat, htlcIdToChannelId(2), 0))
    ))

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("receive additional htlcs after timeout") {
    val f = createFixture(250 millis, 1000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 150 msat, 1))
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 100 msat, 2))
    assert(f.parent.expectMsgType[MultiPartHtlcFailed].parts.length === 2)

    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 300 msat, 3))
    val fail = f.parent.expectMsgType[ExtraHtlcReceived]
    assert(fail.paymentHash === paymentHash)
    assert(fail.failure === Some(wire.PaymentTimeout))
    assert(setTimestamp(fail.payment :: Nil, 0) === Seq(PendingPayment(3, PartialPayment(300 msat, htlcIdToChannelId(3), 0))))

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fail all if total amount is not consistent") {
    val f = createFixture(250 millis, 1000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 600 msat, 1))
    f.parent.send(f.handler, createMultiPartHtlc(1100 msat, 650 msat, 2))
    val fail = f.parent.expectMsgType[MultiPartHtlcFailed]
    assert(fail.paymentHash === paymentHash)
    assert(fail.failure === IncorrectOrUnknownPaymentDetails(1100 msat, f.currentBlockHeight))
    assert(fail.parts.length === 2)

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all when total amount reached") {
    val f = createFixture(10 seconds, 1000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 300 msat, 1))
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 340 msat, 2))
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 360 msat, 3))

    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(paymentResult.paymentHash === paymentHash)
    assert(setTimestamp(paymentResult.parts, 0).toSet === Set(
      PendingPayment(1, PartialPayment(300 msat, htlcIdToChannelId(1), 0)),
      PendingPayment(2, PartialPayment(340 msat, htlcIdToChannelId(2), 0)),
      PendingPayment(3, PartialPayment(360 msat, htlcIdToChannelId(3), 0))
    ))
    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all with amount higher than total amount") {
    val f = createFixture(10 seconds, 1000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 300 msat, 1))
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 340 msat, 2))
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 400 msat, 3))

    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(paymentResult.paymentHash === paymentHash)
    assert(setTimestamp(paymentResult.parts, 0).toSet === Set(
      PendingPayment(1, PartialPayment(300 msat, htlcIdToChannelId(1), 0)),
      PendingPayment(2, PartialPayment(340 msat, htlcIdToChannelId(2), 0)),
      PendingPayment(3, PartialPayment(400 msat, htlcIdToChannelId(3), 0))
    ))

    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all with single htlc") {
    val f = createFixture(10 seconds, 1000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 1000 msat, 1))
    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(setTimestamp(paymentResult.parts, 0) === Seq(PendingPayment(1, PartialPayment(1000 msat, htlcIdToChannelId(1), 0))))
    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all with single htlc and amount higher than total amount") {
    val f = createFixture(10 seconds, 1000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 1100 msat, 1))
    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(setTimestamp(paymentResult.parts, 0) === Seq(PendingPayment(1, PartialPayment(1100 msat, htlcIdToChannelId(1), 0))))
    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("receive additional htlcs after total amount reached") {
    val f = createFixture(10 seconds, 1000 msat)

    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 600 msat, 1))
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 400 msat, 2))
    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(setTimestamp(paymentResult.parts, 0).toSet === Set(
      PendingPayment(1, PartialPayment(600 msat, htlcIdToChannelId(1), 0)),
      PendingPayment(2, PartialPayment(400 msat, htlcIdToChannelId(2), 0))
    ))
    f.parent.expectNoMsg(50 millis)

    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 300 msat, 3))
    val extraneousPayment = f.parent.expectMsgType[ExtraHtlcReceived]
    assert(extraneousPayment.paymentHash === paymentHash)
    assert(setTimestamp(extraneousPayment.payment :: Nil, 0) === Seq(PendingPayment(3, PartialPayment(300 msat, htlcIdToChannelId(3), 0))))
    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("actor restart") {
    val f = createFixture(10 seconds, 1000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 600 msat, 1))

    f.handler.suspend()
    f.handler.resume(new IllegalArgumentException("something went wrong"))

    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 400 msat, 2))
    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(paymentResult.paymentHash === paymentHash)
    assert(setTimestamp(paymentResult.parts, 0).toSet === Set(
      PendingPayment(1, PartialPayment(600 msat, htlcIdToChannelId(1), 0)),
      PendingPayment(2, PartialPayment(400 msat, htlcIdToChannelId(2), 0))
    ))
    f.parent.expectNoMsg(50 millis)
  }

  test("unknown message") {
    val f = createFixture(10 seconds, 1000 msat)
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 600 msat, 1))
    f.parent.send(f.handler, "hello")
    f.parent.send(f.handler, createMultiPartHtlc(1000 msat, 400 msat, 2))

    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(setTimestamp(paymentResult.parts, 0).toSet === Set(
      PendingPayment(1, PartialPayment(600 msat, htlcIdToChannelId(1), 0)),
      PendingPayment(2, PartialPayment(400 msat, htlcIdToChannelId(2), 0))
    ))
    f.parent.expectNoMsg(50 millis)
  }

}

object MultiPartPaymentFSMSpec {

  import MultiPartPaymentFSM.MultiPartHtlc

  val paymentHash = randomBytes32

  def htlcIdToChannelId(htlcId: Long) = ByteVector32(ByteVector.fromLong(htlcId).padLeft(32))

  def createMultiPartHtlc(totalAmount: MilliSatoshi, htlcAmount: MilliSatoshi, htlcId: Long) =
    MultiPartHtlc(totalAmount, UpdateAddHtlc(htlcIdToChannelId(htlcId), htlcId, htlcAmount, paymentHash, CltvExpiry(42), TestConstants.emptyOnionPacket))

  /** Sets all inner timestamps to the given value (useful to test equality when timestamp is set to current automatically) */
  def setTimestamp(parts: Seq[PendingPayment], timestamp: Long): Seq[PendingPayment] = parts.map(p => p.copy(payment = p.payment.copy(timestamp = timestamp)))
}