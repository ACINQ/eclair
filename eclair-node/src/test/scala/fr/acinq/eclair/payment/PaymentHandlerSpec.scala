package fr.acinq.eclair.payment

import akka.actor.{ActorSystem, Props}
import akka.testkit.{TestKit, TestProbe}
import fr.acinq.bitcoin.BinaryData
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
    val handler = system.actorOf(Props[LocalPaymentHandler])

    val sender = TestProbe()
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PaymentReceived])

    sender.send(handler, 'genh)
    val paymentHash = sender.expectMsgType[BinaryData]

    sender.send(handler, (UpdateAddHtlc("11" * 32, 0, 0, 0, paymentHash, ""), ""))
    sender.expectMsgType[CMD_FULFILL_HTLC]
    eventListener.expectMsgType[PaymentReceived]
  }

}
