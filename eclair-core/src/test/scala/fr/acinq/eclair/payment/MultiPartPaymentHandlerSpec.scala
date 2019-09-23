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
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.wire.{IncorrectOrUnknownPaymentDetails, UpdateAddHtlc}
import fr.acinq.eclair.{CltvExpiry, LongToBtcAmount, MilliSatoshi, NodeParams, TestConstants, randomBytes32, wire}
import org.scalatest.FunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by t-bast on 18/07/2019.
 */

class MultiPartPaymentHandlerSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  import MultiPartPaymentHandler._
  import MultiPartPaymentHandlerSpec._

  case class FixtureParam(nodeParams: NodeParams, handler: TestActorRef[MultiPartPaymentHandler], parent: TestProbe, eventListener: TestProbe, sender1: TestProbe, sender2: TestProbe) {
    def currentBlockHeight: Long = nodeParams.currentBlockHeight
  }

  def createFixture(paymentTimeout: FiniteDuration): FixtureParam = {
    val parent = TestProbe()
    val nodeParams = TestConstants.Alice.nodeParams.copy(multiPartPaymentExpiry = paymentTimeout)
    val handler = TestActorRef[MultiPartPaymentHandler](props(nodeParams, paymentHash, paymentPreimage, parent.ref))
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
    FixtureParam(nodeParams, handler, parent, eventListener, TestProbe(), TestProbe())
  }

  test("timeout waiting for first htlc") {
    val f = createFixture(250 millis)
    val monitor = TestProbe()
    f.handler ! SubscribeTransitionCallBack(monitor.ref)

    val CurrentState(_, WAITING_FOR_FIRST_HTLC) = monitor.expectMsgClass(classOf[CurrentState[_]])
    val Transition(_, WAITING_FOR_FIRST_HTLC, PAYMENT_FAILED) = monitor.expectMsgClass(classOf[Transition[_]])

    f.parent.expectMsg(MultiPartHtlcFailed(paymentHash, 0 msat))
    f.eventListener.expectNoMsg(50 millis)
  }

  test("timeout waiting for more htlcs") {
    val f = createFixture(250 millis)
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 150 msat, 1))
    f.sender2.send(f.handler, createMultiPartHtlc(1000 msat, 100 msat, 2))

    f.sender1.expectMsg(CMD_FAIL_HTLC(1, Right(wire.PaymentTimeout), commit = true))
    f.sender2.expectMsg(CMD_FAIL_HTLC(2, Right(wire.PaymentTimeout), commit = true))
    f.parent.expectMsg(MultiPartHtlcFailed(paymentHash, 250 msat))
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fail additional htlcs after timeout") {
    val f = createFixture(250 millis)
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 150 msat, 1))
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 100 msat, 2))
    f.sender1.expectMsg(CMD_FAIL_HTLC(1, Right(wire.PaymentTimeout), commit = true))
    f.sender1.expectMsg(CMD_FAIL_HTLC(2, Right(wire.PaymentTimeout), commit = true))
    f.parent.expectMsg(MultiPartHtlcFailed(paymentHash, 250 msat))

    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 300 msat, 3))
    f.sender1.expectMsg(CMD_FAIL_HTLC(3, Right(wire.PaymentTimeout), commit = true))
    f.parent.expectNoMsg(50 millis)
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fail all if total amount is not consistent") {
    val f = createFixture(250 millis)
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 600 msat, 1))
    f.sender2.send(f.handler, createMultiPartHtlc(1100 msat, 650 msat, 2))

    f.sender1.expectMsg(CMD_FAIL_HTLC(1, Right(IncorrectOrUnknownPaymentDetails(1100 msat, f.currentBlockHeight)), commit = true))
    f.sender2.expectMsg(CMD_FAIL_HTLC(2, Right(IncorrectOrUnknownPaymentDetails(1100 msat, f.currentBlockHeight)), commit = true))
    f.parent.expectMsg(MultiPartHtlcFailed(paymentHash, 600 msat))
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all when total amount reached") {
    val f = createFixture(10 seconds)
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 300 msat, 1))
    f.sender2.send(f.handler, createMultiPartHtlc(1000 msat, 340 msat, 2))
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 360 msat, 3))

    f.sender1.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    f.sender2.expectMsg(CMD_FULFILL_HTLC(2, paymentPreimage, commit = true))
    f.sender1.expectMsg(CMD_FULFILL_HTLC(3, paymentPreimage, commit = true))
    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(setTimestamp(paymentResult.paymentReceived, 0) === PaymentReceived(paymentHash, Seq(
      PartialPayment(300 msat, htlcIdToChannelId(1), 0),
      PartialPayment(340 msat, htlcIdToChannelId(2), 0),
      PartialPayment(360 msat, htlcIdToChannelId(3), 0)
    )))
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all with amount higher than total amount") {
    val f = createFixture(10 seconds)
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 300 msat, 1))
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 340 msat, 2))
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 400 msat, 3))

    f.sender1.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    f.sender1.expectMsg(CMD_FULFILL_HTLC(2, paymentPreimage, commit = true))
    f.sender1.expectMsg(CMD_FULFILL_HTLC(3, paymentPreimage, commit = true))
    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(setTimestamp(paymentResult.paymentReceived, 0) === PaymentReceived(paymentHash, Seq(
      PartialPayment(300 msat, htlcIdToChannelId(1), 0),
      PartialPayment(340 msat, htlcIdToChannelId(2), 0),
      PartialPayment(400 msat, htlcIdToChannelId(3), 0)
    )))
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all with single htlc") {
    val f = createFixture(10 seconds)
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 1000 msat, 1))
    f.sender1.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(setTimestamp(paymentResult.paymentReceived, 0) === PaymentReceived(paymentHash, Seq(PartialPayment(1000 msat, htlcIdToChannelId(1), 0))))
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill all with single htlc and amount higher than total amount") {
    val f = createFixture(10 seconds)
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 1100 msat, 1))
    f.sender1.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(setTimestamp(paymentResult.paymentReceived, 0) === PaymentReceived(paymentHash, Seq(PartialPayment(1100 msat, htlcIdToChannelId(1), 0))))
    f.eventListener.expectNoMsg(50 millis)
  }

  test("fulfill additional htlcs after total amount reached") {
    val f = createFixture(10 seconds)
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])

    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 600 msat, 1))
    f.sender2.send(f.handler, createMultiPartHtlc(1000 msat, 400 msat, 2))
    f.sender1.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    f.sender2.expectMsg(CMD_FULFILL_HTLC(2, paymentPreimage, commit = true))
    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(setTimestamp(paymentResult.paymentReceived, 0) === PaymentReceived(paymentHash, Seq(
      PartialPayment(600 msat, htlcIdToChannelId(1), 0),
      PartialPayment(400 msat, htlcIdToChannelId(2), 0)
    )))

    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 300 msat, 3))
    f.sender1.expectMsg(CMD_FULFILL_HTLC(3, paymentPreimage, commit = true))
    f.parent.expectNoMsg(50 millis)
    val extraneousPayment = f.eventListener.expectMsgType[PaymentReceived]
    assert(setTimestamp(extraneousPayment, 0) === PaymentReceived(paymentHash, Seq(PartialPayment(300 msat, htlcIdToChannelId(3), 0))))
  }

  test("actor restart") {
    val f = createFixture(10 seconds)
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 600 msat, 1))

    f.handler.suspend()
    f.handler.resume(new IllegalArgumentException("something went wrong"))

    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 400 msat, 2))
    f.sender1.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    f.sender1.expectMsg(CMD_FULFILL_HTLC(2, paymentPreimage, commit = true))
    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(setTimestamp(paymentResult.paymentReceived, 0) === PaymentReceived(paymentHash, Seq(
      PartialPayment(600 msat, htlcIdToChannelId(1), 0),
      PartialPayment(400 msat, htlcIdToChannelId(2), 0)
    )))
  }

  test("unknown message") {
    val f = createFixture(10 seconds)
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 600 msat, 1))
    f.sender1.send(f.handler, "hello")
    f.sender1.send(f.handler, createMultiPartHtlc(1000 msat, 400 msat, 2))

    f.sender1.expectMsg(CMD_FULFILL_HTLC(1, paymentPreimage, commit = true))
    f.sender1.expectMsg(CMD_FULFILL_HTLC(2, paymentPreimage, commit = true))
    val paymentResult = f.parent.expectMsgType[MultiPartHtlcSucceeded]
    assert(setTimestamp(paymentResult.paymentReceived, 0) === PaymentReceived(paymentHash, Seq(
      PartialPayment(600 msat, htlcIdToChannelId(1), 0),
      PartialPayment(400 msat, htlcIdToChannelId(2), 0)
    )))
  }

}

object MultiPartPaymentHandlerSpec {

  import MultiPartPaymentHandler.MultiPartHtlc

  val paymentPreimage = randomBytes32
  val paymentHash = Crypto.sha256(paymentPreimage)

  def htlcIdToChannelId(htlcId: Long) = ByteVector32(ByteVector.fromLong(htlcId).padLeft(32))

  def createMultiPartHtlc(totalAmount: MilliSatoshi, htlcAmount: MilliSatoshi, htlcId: Long) =
    MultiPartHtlc(totalAmount, UpdateAddHtlc(htlcIdToChannelId(htlcId), htlcId, htlcAmount, paymentHash, CltvExpiry(42), TestConstants.emptyOnionPacket))

  /** Sets all inner timestamps to the given value (useful to test equality when timestamp is set to current automatically) */
  def setTimestamp(p: PaymentReceived, timestamp: Long): PaymentReceived = p.copy(parts = p.parts.map(_.copy(timestamp = timestamp)))
}