package fr.acinq.eclair.payment

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.MilliSatoshi
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.channel.CMD_FULFILL_HTLC
import fr.acinq.eclair.wire.UpdateAddHtlc
import org.junit.runner.RunWith
import org.scalatest.FunSuiteLike
import org.scalatest.junit.JUnitRunner

/**
  * Created by PM on 24/03/2017.
  */
@RunWith(classOf[JUnitRunner])
class PaymentHandlerSpec extends TestKit(ActorSystem("test")) with FunSuiteLike {

  test("LocalPaymentHandler should send an event when receiving a payment") {
    val handler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    val amountMsat = MilliSatoshi(42000)
    sender.send(handler, ReceivePayment(amountMsat))
    val pr = sender.expectMsgType[PaymentRequest]

    val add = UpdateAddHtlc("11" * 32, 0, amountMsat.amount, 0, pr.paymentHash, "")
    sender.send(handler, add)
    sender.expectMsgType[CMD_FULFILL_HTLC]
    eventListener.expectMsg(PaymentReceived(amountMsat, add.paymentHash))
  }

  test("Payment request generation should fail when the amount asked in not valid") {
    val handler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    // negative amount should fail
    sender.send(handler, ReceivePayment(MilliSatoshi(-50)))
    val negativeError = sender.expectMsgType[Failure]
    assert(negativeError.cause.getMessage.contains("amount is not valid"))

    // amount = 0 should fail
    sender.send(handler, ReceivePayment(MilliSatoshi(0)))
    val zeroError = sender.expectMsgType[Failure]
    assert(zeroError.cause.getMessage.contains("amount is not valid"))

    // large amount should fail (> 42.95 mBTC)
    sender.send(handler, ReceivePayment(MilliSatoshi(PaymentRequest.maxAmountMsat + 10)))
    val largeAmountError = sender.expectMsgType[Failure]
    assert(largeAmountError.cause.getMessage.contains("amount is not valid"))

    // success with 1 mBTC
    sender.send(handler, ReceivePayment(MilliSatoshi(100000000L)))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.amountMsat == Some(MilliSatoshi(100000000L)) && pr.nodeId.toString == Alice.nodeParams.privateKey.publicKey.toString)
  }
}
