package fr.acinq.eclair.payment

import akka.actor.{ActorSystem, Status}
import akka.actor.Status.Failure
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.{MilliSatoshi, Satoshi}
import fr.acinq.eclair.Globals
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.wire.{FinalExpiryTooSoon, UpdateAddHtlc}
import org.junit.runner.RunWith
import org.scalatest.FunSuiteLike
import org.scalatest.junit.JUnitRunner

import scala.concurrent.duration._

/**
  * Created by PM on 24/03/2017.
  */
@RunWith(classOf[JUnitRunner])
class PaymentHandlerSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  test("LocalPaymentHandler should reply with a fulfill/fail, emit a PaymentReceived and adds payment in DB") {
    val nodeParams = Alice.nodeParams
    val handler = system.actorOf(LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    val amountMsat = MilliSatoshi(42000)
    val expiry = Globals.blockCount.get() + 12

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      sender.send(handler, CheckPayment(pr.paymentHash))
      assert(sender.expectMsgType[Boolean] === false)
      val add = UpdateAddHtlc("11" * 32, 0, amountMsat.amount, pr.paymentHash, expiry, "")
      sender.send(handler, add)
      sender.expectMsgType[CMD_FULFILL_HTLC]
      eventListener.expectMsg(PaymentReceived(amountMsat, add.paymentHash))
      sender.send(handler, CheckPayment(pr.paymentHash))
      assert(sender.expectMsgType[Boolean] === true)
    }

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "another coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      sender.send(handler, CheckPayment(pr.paymentHash))
      assert(sender.expectMsgType[Boolean] === false)
      val add = UpdateAddHtlc("11" * 32, 0, amountMsat.amount, pr.paymentHash, expiry, "")
      sender.send(handler, add)
      sender.expectMsgType[CMD_FULFILL_HTLC]
      eventListener.expectMsg(PaymentReceived(amountMsat, add.paymentHash))
      sender.send(handler, CheckPayment(pr.paymentHash))
      assert(sender.expectMsgType[Boolean] === true)
    }

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "bad expiry"))
      val pr = sender.expectMsgType[PaymentRequest]
      sender.send(handler, CheckPayment(pr.paymentHash))
      assert(sender.expectMsgType[Boolean] === false)
      val add = UpdateAddHtlc("11" * 32, 0, amountMsat.amount, pr.paymentHash, expiry = Globals.blockCount.get() + 3, "")
      sender.send(handler, add)
      assert(sender.expectMsgType[CMD_FAIL_HTLC].reason == Right(FinalExpiryTooSoon))
      eventListener.expectNoMsg(300 milliseconds)
      sender.send(handler, CheckPayment(pr.paymentHash))
      assert(sender.expectMsgType[Boolean] === false)
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

    // large amount should fail (> 42.95 mBTC)
    sender.send(handler, ReceivePayment(Some(Satoshi(1) + PaymentRequest.MAX_AMOUNT), "1 coffee"))
    val largeAmountError = sender.expectMsgType[Failure]
    assert(largeAmountError.cause.getMessage.contains("amount is not valid"))

    // success with 1 mBTC
    sender.send(handler, ReceivePayment(Some(MilliSatoshi(100000000L)), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.amount.contains(MilliSatoshi(100000000L)) && pr.nodeId.toString == nodeParams.nodeId.toString)
  }

  test("Payment request generation should fail when there are too many pending requests") {
    val nodeParams = Alice.nodeParams.copy(maxPendingPaymentRequests = 42)
    val handler = system.actorOf(LocalPaymentHandler.props(nodeParams))
    val sender = TestProbe()

    for (i <- 0 to nodeParams.maxPendingPaymentRequests) {
      sender.send(handler, ReceivePayment(None, s"Request #$i"))
      sender.expectMsgType[PaymentRequest]
    }

    // over limit
    sender.send(handler, ReceivePayment(None, "This one should fail"))
    assert(sender.expectMsgType[Status.Failure].cause.getMessage === s"too many pending payment requests (max=${nodeParams.maxPendingPaymentRequests})")
  }

  test("Payment request generation should succeed when the amount is not set") {
    val handler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    sender.send(handler, ReceivePayment(None, "This is a donation PR"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.amount.isEmpty && pr.nodeId.toString == Alice.nodeParams.nodeId.toString)
  }
}
