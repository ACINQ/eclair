package fr.acinq.eclair.payment

import akka.actor.ActorSystem
import akka.actor.Status.Failure
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
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

    sender.send(handler, 'genh)
    val paymentHash = sender.expectMsgType[BinaryData]

    val add = UpdateAddHtlc("11" * 32, 0, 42000, 0, paymentHash, "")
    sender.send(handler, add)
    sender.expectMsgType[CMD_FULFILL_HTLC]
    eventListener.expectMsg(PaymentReceived(MilliSatoshi(add.amountMsat), add.paymentHash))

    sender.send(handler, ReceivePayment(MilliSatoshi(100000000))) // 1 milliBTC
    sender.expectMsgType[String]
  }

  test("Payment request generation should fail when the amount asked in not valid") {
    val handler = system.actorOf(LocalPaymentHandler.props(Alice.nodeParams))
    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    // negative amount should fail
    sender.send(handler, ReceivePayment(MilliSatoshi(-50)))
    val negativeError = sender.expectMsgType[Failure]
    assert(new String(negativeError.cause.getMessage) === "amount is not valid: must be > 0 and < 42.95 mBTC")

    // amount = 0 should fail
    sender.send(handler, ReceivePayment(MilliSatoshi(0)))
    val zeroError = sender.expectMsgType[Failure]
    assert(new String(zeroError.cause.getMessage) === "amount is not valid: must be > 0 and < 42.95 mBTC")

    // large amount should fail (> 42.95 mBTC)
    sender.send(handler, ReceivePayment(MilliSatoshi(4294967296L)))
    val largeAmountError = sender.expectMsgType[Failure]
    assert(new String(largeAmountError.cause.getMessage) === "amount is not valid: must be > 0 and < 42.95 mBTC")

    // success with 1 mBTC
    sender.send(handler, ReceivePayment(MilliSatoshi(100000000L)))
    val success = sender.expectMsgType[String]
    assert(success.contains(s"${Alice.nodeParams.privateKey.publicKey}:100000000:"))
  }
}
