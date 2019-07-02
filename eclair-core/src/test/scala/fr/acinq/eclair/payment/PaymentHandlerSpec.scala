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

import akka.actor.Status.Failure
import akka.actor.{ActorSystem, Status}
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, MilliSatoshi, Satoshi}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.payment.PaymentLifecycle.{ReceivePayment}
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.wire.{FinalExpiryTooSoon, UpdateAddHtlc}
import fr.acinq.eclair.{Globals, ShortChannelId, randomKey}
import org.scalatest.FunSuiteLike
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
  * Created by PM on 24/03/2017.
  */

class PaymentHandlerSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  test("LocalPaymentHandler should reply with a fulfill/fail, emit a PaymentReceived and adds payment in DB") {
    val nodeParams = Alice.nodeParams
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    val amountMsat = MilliSatoshi(42000)
    val expiry = Globals.blockCount.get() + 12

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).isEmpty)
      assert(nodeParams.db.payments.getPendingPaymentRequestAndPreimage(pr.paymentHash).isDefined)

      val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, amountMsat.amount, pr.paymentHash, expiry, ByteVector.empty)
      sender.send(handler, add)
      sender.expectMsgType[CMD_FULFILL_HTLC]

      val paymentRelayed = eventListener.expectMsgType[PaymentReceived]
      assert(paymentRelayed.copy(timestamp = 0) === PaymentReceived(amountMsat, add.paymentHash, add.channelId, timestamp = 0))
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.paymentHash == pr.paymentHash))
    }

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "another coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).isEmpty)

      val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, amountMsat.amount, pr.paymentHash, expiry, ByteVector.empty)
      sender.send(handler, add)
      sender.expectMsgType[CMD_FULFILL_HTLC]
      val paymentRelayed = eventListener.expectMsgType[PaymentReceived]
      assert(paymentRelayed.copy(timestamp = 0) === PaymentReceived(amountMsat, add.paymentHash, add.channelId, timestamp = 0))
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).exists(_.paymentHash == pr.paymentHash))
    }

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "bad expiry"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).isEmpty)

      val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, amountMsat.amount, pr.paymentHash, cltvExpiry = Globals.blockCount.get() + 3, ByteVector.empty)
      sender.send(handler, add)
      assert(sender.expectMsgType[CMD_FAIL_HTLC].reason == Right(FinalExpiryTooSoon))
      eventListener.expectNoMsg(300 milliseconds)
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).isEmpty)
    }
  }

  test("Payment request generation should fail when the amount asked in not valid") {
    val nodeParams = Alice.nodeParams
    val handler = system.actorOf(LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    // negative amount should fail
    sender.send(handler, ReceivePayment(Some(MilliSatoshi(-50)), "1 coffee"))
    val negativeError = sender.expectMsgType[Failure]
    assert(negativeError.cause.getMessage.contains("amount is not valid"))

    // amount = 0 should fail
    sender.send(handler, ReceivePayment(Some(MilliSatoshi(0)), "1 coffee"))
    val zeroError = sender.expectMsgType[Failure]
    assert(zeroError.cause.getMessage.contains("amount is not valid"))

    // success with 1 mBTC
    sender.send(handler, ReceivePayment(Some(MilliSatoshi(100000000L)), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.amount.contains(MilliSatoshi(100000000L)) && pr.nodeId.toString == nodeParams.nodeId.toString)
  }

  test("Payment request generation should succeed when the amount is not set") {
    val handler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val sender = TestProbe()

    sender.send(handler, ReceivePayment(None, "This is a donation PR"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.amount.isEmpty && pr.nodeId.toString == Alice.nodeParams.nodeId.toString)
  }

  test("Payment request generation should handle custom expiries or use the default otherwise") {
    val handler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val sender = TestProbe()

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(42000)), "1 coffee"))
    assert(sender.expectMsgType[PaymentRequest].expiry === Some(Alice.nodeParams.paymentRequestExpiry.toSeconds))

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(42000)), "1 coffee with custom expiry", expirySeconds_opt = Some(60)))
    assert(sender.expectMsgType[PaymentRequest].expiry === Some(60))
  }

  test("Generated payment request contains the provided extra hops") {
    val handler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val sender = TestProbe()

    val x = randomKey.publicKey
    val y = randomKey.publicKey
    val extraHop_x_y = ExtraHop(x, ShortChannelId(1), 10, 11, 12)
    val extraHop_y_z = ExtraHop(y, ShortChannelId(2), 20, 21, 22)
    val extraHop_x_t = ExtraHop(x, ShortChannelId(3), 30, 31, 32)
    val route_x_z = extraHop_x_y :: extraHop_y_z :: Nil
    val route_x_t = extraHop_x_t :: Nil

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(42000)), "1 coffee with additional routing info", extraHops = List(route_x_z, route_x_t)))
    assert(sender.expectMsgType[PaymentRequest].routingInfo === Seq(route_x_z, route_x_t))

    sender.send(handler, ReceivePayment(Some(MilliSatoshi(42000)), "1 coffee without routing info"))
    assert(sender.expectMsgType[PaymentRequest].routingInfo === Nil)
  }

  test("LocalPaymentHandler should reject incoming payments if the payment request is expired") {
    val nodeParams = Alice.nodeParams
    val handler = TestActorRef[LocalPaymentHandler](LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    val amountMsat = MilliSatoshi(42000)
    val expiry = Globals.blockCount.get() + 12

    sender.send(handler, ReceivePayment(Some(amountMsat), "some desc", expirySeconds_opt = Some(0)))
    val pr = sender.expectMsgType[PaymentRequest]

    val add = UpdateAddHtlc(ByteVector32(ByteVector.fill(32)(1)), 0, amountMsat.amount, pr.paymentHash, expiry, ByteVector.empty)
    sender.send(handler, add)

    sender.expectMsgType[CMD_FAIL_HTLC]
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).isEmpty)
  }
}
