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
import akka.actor.Status.Failure
import akka.testkit.{TestActorRef, TestProbe}
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, Register}
import fr.acinq.eclair.db.IncomingPaymentStatus
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.payment.receive.MultiPartHandler.{DoFulfill, GetPendingPayments, PendingPayments, ReceivePayment}
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM.HtlcPart
import fr.acinq.eclair.payment.receive.{MultiPartPaymentFSM, PaymentHandler}
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalTlvPayload
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, FeatureScope, Features, MilliSatoshiLong, NodeParams, ShortChannelId, TestConstants, TestKitBaseClass, TimestampMilliLong, randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.collection.immutable.Queue
import scala.concurrent.duration._

/**
 * Created by PM on 24/03/2017.
 */

class MultiPartHandlerSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike {

  val featuresWithoutMpp = Features[FeatureScope](
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory,
  )

  val featuresWithMpp = Features[FeatureScope](
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory,
    BasicMultiPartPayment -> Optional
  )

  val featuresWithKeySend = Features[FeatureScope](
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory,
    KeySend -> Optional
  )

  case class FixtureParam(nodeParams: NodeParams, defaultExpiry: CltvExpiry, register: TestProbe, eventListener: TestProbe, sender: TestProbe) {
    lazy val handlerWithoutMpp = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams.copy(features = featuresWithoutMpp), register.ref))
    lazy val handlerWithMpp = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams.copy(features = featuresWithMpp), register.ref))
    lazy val handlerWithKeySend = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams.copy(features = featuresWithKeySend), register.ref))
  }

  override def withFixture(test: OneArgTest): Outcome = {
    within(30 seconds) {
      val nodeParams = Alice.nodeParams
      val register = TestProbe()
      val eventListener = TestProbe()
      system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, nodeParams.channelConf.minFinalExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight), register, eventListener, TestProbe())))
    }
  }

  test("PaymentHandler should reply with a fulfill/fail, emit a PaymentReceived and add payment in DB") { f =>
    import f._

    val amountMsat = 42000 msat

    {
      sender.send(handlerWithoutMpp, ReceivePayment(Some(amountMsat), Left("1 coffee")))
      val invoice = sender.expectMsgType[Invoice]
      val incoming = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
      assert(incoming.isDefined)
      assert(incoming.get.status === IncomingPaymentStatus.Pending)
      assert(!incoming.get.invoice.isExpired())
      assert(Crypto.sha256(incoming.get.paymentPreimage) === invoice.paymentHash)

      val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
      sender.send(handlerWithoutMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createSinglePartPayload(add.amountMsat, add.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
      register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]

      val paymentReceived = eventListener.expectMsgType[PaymentReceived]
      assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0 unixms))) === PaymentReceived(add.paymentHash, PartialPayment(amountMsat, add.channelId, timestamp = 0 unixms) :: Nil))
      val received = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
      assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
      assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0 unixms) === IncomingPaymentStatus.Received(amountMsat, 0 unixms))

      sender.expectNoMessage(50 millis)
    }

    {
      sender.send(handlerWithMpp, ReceivePayment(Some(amountMsat), Left("another coffee with multi-part")))
      val invoice = sender.expectMsgType[Invoice]
      assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
      assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status === IncomingPaymentStatus.Pending)

      val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
      sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createSinglePartPayload(add.amountMsat, add.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
      register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]

      val paymentReceived = eventListener.expectMsgType[PaymentReceived]
      assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0 unixms))) === PaymentReceived(add.paymentHash, PartialPayment(amountMsat, add.channelId, timestamp = 0 unixms) :: Nil))
      val received = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
      assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
      assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0 unixms) === IncomingPaymentStatus.Received(amountMsat, 0 unixms))

      sender.expectNoMessage(50 millis)
    }

    {
      sender.send(handlerWithMpp, ReceivePayment(Some(amountMsat), Left("bad expiry")))
      val invoice = sender.expectMsgType[Invoice]
      assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status === IncomingPaymentStatus.Pending)

      val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, invoice.paymentHash, CltvExpiryDelta(3).toCltvExpiry(nodeParams.currentBlockHeight), TestConstants.emptyOnionPacket)
      sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createSinglePartPayload(add.amountMsat, add.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
      val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
      assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(amountMsat, nodeParams.currentBlockHeight)))
      assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status === IncomingPaymentStatus.Pending)

      eventListener.expectNoMessage(100 milliseconds)
      sender.expectNoMessage(50 millis)
    }
  }

  test("Invoice generation should fail when the amount is not valid") { f =>
    import f._

    // negative amount should fail
    sender.send(handlerWithMpp, ReceivePayment(Some(-50 msat), Left("1 coffee")))
    val negativeError = sender.expectMsgType[Failure]
    assert(negativeError.cause.getMessage.contains("amount is not valid"))

    // amount = 0 should fail
    sender.send(handlerWithMpp, ReceivePayment(Some(0 msat), Left("1 coffee")))
    val zeroError = sender.expectMsgType[Failure]
    assert(zeroError.cause.getMessage.contains("amount is not valid"))

    // success with 1 mBTC
    sender.send(handlerWithMpp, ReceivePayment(Some(100000000 msat), Left("1 coffee")))
    val invoice = sender.expectMsgType[Invoice]
    assert(invoice.amount_opt.contains(100000000 msat) && invoice.nodeId.toString == nodeParams.nodeId.toString)
  }

  test("Invoice generation should succeed when the amount is not set") { f =>
    import f._

    sender.send(handlerWithMpp, ReceivePayment(None, Left("This is a donation PR")))
    val invoice = sender.expectMsgType[Invoice]
    assert(invoice.amount_opt.isEmpty && invoice.nodeId.toString == Alice.nodeParams.nodeId.toString)
  }

  test("Invoice generation should handle custom expiries or use the default otherwise") { f =>
    import f._

    sender.send(handlerWithMpp, ReceivePayment(Some(42000 msat), Left("1 coffee")))
    val pr1 = sender.expectMsgType[Invoice]
    assert(pr1.minFinalCltvExpiryDelta === Some(nodeParams.channelConf.minFinalExpiryDelta))
    assert(pr1.relativeExpiry === Alice.nodeParams.invoiceExpiry)

    sender.send(handlerWithMpp, ReceivePayment(Some(42000 msat), Left("1 coffee with custom expiry"), expirySeconds_opt = Some(60)))
    val pr2 = sender.expectMsgType[Invoice]
    assert(pr2.minFinalCltvExpiryDelta === Some(nodeParams.channelConf.minFinalExpiryDelta))
    assert(pr2.relativeExpiry === 60.seconds)
  }

  test("Invoice generation with trampoline support") { _ =>
    val sender = TestProbe()

    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = false, features = featuresWithoutMpp), TestProbe().ref))
      sender.send(handler, ReceivePayment(Some(42 msat), Left("1 coffee")))
      val invoice = sender.expectMsgType[Invoice]
      assert(!invoice.features.hasFeature(Features.BasicMultiPartPayment))
      assert(!invoice.features.hasFeature(Features.TrampolinePayment))
    }
    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = false, features = featuresWithMpp), TestProbe().ref))
      sender.send(handler, ReceivePayment(Some(42 msat), Left("1 coffee")))
      val invoice = sender.expectMsgType[Invoice]
      assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
      assert(!invoice.features.hasFeature(Features.TrampolinePayment))
    }
    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = true, features = featuresWithoutMpp), TestProbe().ref))
      sender.send(handler, ReceivePayment(Some(42 msat), Left("1 coffee")))
      val invoice = sender.expectMsgType[Invoice]
      assert(!invoice.features.hasFeature(Features.BasicMultiPartPayment))
      assert(invoice.features.hasFeature(Features.TrampolinePayment))
    }
    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = true, features = featuresWithMpp), TestProbe().ref))
      sender.send(handler, ReceivePayment(Some(42 msat), Left("1 coffee")))
      val invoice = sender.expectMsgType[Invoice]
      assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
      assert(invoice.features.hasFeature(Features.TrampolinePayment))
    }
  }

  test("Generated invoice contains the provided extra hops") { f =>
    import f._

    val x = randomKey().publicKey
    val y = randomKey().publicKey
    val extraHop_x_y = ExtraHop(x, ShortChannelId(1), 10 msat, 11, CltvExpiryDelta(12))
    val extraHop_y_z = ExtraHop(y, ShortChannelId(2), 20 msat, 21, CltvExpiryDelta(22))
    val extraHop_x_t = ExtraHop(x, ShortChannelId(3), 30 msat, 31, CltvExpiryDelta(32))
    val route_x_z = extraHop_x_y :: extraHop_y_z :: Nil
    val route_x_t = extraHop_x_t :: Nil

    sender.send(handlerWithMpp, ReceivePayment(Some(42000 msat), Left("1 coffee with additional routing info"), extraHops = List(route_x_z, route_x_t)))
    assert(sender.expectMsgType[Invoice].routingInfo === Seq(route_x_z, route_x_t))

    sender.send(handlerWithMpp, ReceivePayment(Some(42000 msat), Left("1 coffee without routing info")))
    assert(sender.expectMsgType[Invoice].routingInfo === Nil)
  }

  test("PaymentHandler should reject incoming payments if the invoice is expired") { f =>
    import f._

    sender.send(handlerWithoutMpp, ReceivePayment(Some(1000 msat), Left("some desc"), expirySeconds_opt = Some(0)))
    val invoice = sender.expectMsgType[Invoice]
    assert(!invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.isExpired())

    val add = UpdateAddHtlc(ByteVector32.One, 0, 1000 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithoutMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createSinglePartPayload(add.amountMsat, add.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
    register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    val Some(incoming) = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(incoming.invoice.isExpired() && incoming.status === IncomingPaymentStatus.Expired)
  }

  test("PaymentHandler should reject incoming multi-part payment if the invoice is expired") { f =>
    import f._

    sender.send(handlerWithMpp, ReceivePayment(Some(1000 msat), Left("multi-part expired"), expirySeconds_opt = Some(0)))
    val invoice = sender.expectMsgType[Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.isExpired())

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    val Some(incoming) = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(incoming.invoice.isExpired() && incoming.status === IncomingPaymentStatus.Expired)
  }

  test("PaymentHandler should reject incoming multi-part payment if the invoice does not allow it") { f =>
    import f._

    sender.send(handlerWithoutMpp, ReceivePayment(Some(1000 msat), Left("no multi-part support")))
    val invoice = sender.expectMsgType[Invoice]
    assert(!invoice.features.hasFeature(Features.BasicMultiPartPayment))

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithoutMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an invalid expiry") { f =>
    import f._

    sender.send(handlerWithMpp, ReceivePayment(Some(1000 msat), Left("multi-part invalid expiry")))
    val invoice = sender.expectMsgType[Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    val lowCltvExpiry = nodeParams.channelConf.fulfillSafetyBeforeTimeout.toCltvExpiry(nodeParams.currentBlockHeight)
    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, lowCltvExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an unknown payment hash") { f =>
    import f._

    sender.send(handlerWithMpp, ReceivePayment(Some(1000 msat), Left("multi-part unknown payment hash")))
    val invoice = sender.expectMsgType[Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash.reverse, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with a total amount too low") { f =>
    import f._

    sender.send(handlerWithMpp, ReceivePayment(Some(1000 msat), Left("multi-part total amount too low")))
    val invoice = sender.expectMsgType[Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createMultiPartPayload(add.amountMsat, 999 msat, add.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(999 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with a total amount too high") { f =>
    import f._

    sender.send(handlerWithMpp, ReceivePayment(Some(1000 msat), Left("multi-part total amount too low")))
    val invoice = sender.expectMsgType[Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createMultiPartPayload(add.amountMsat, 2001 msat, add.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(2001 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an invalid payment secret") { f =>
    import f._

    sender.send(handlerWithMpp, ReceivePayment(Some(1000 msat), Left("multi-part invalid payment secret")))
    val invoice = sender.expectMsgType[Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))

    // Invalid payment secret.
    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, invoice.paymentSecret.get.reverse, invoice.paymentMetadata)))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should handle multi-part payment timeout") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 200 millis, features = featuresWithMpp)
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.register.ref))

    // Partial payment missing additional parts.
    f.sender.send(handler, ReceivePayment(Some(1000 msat), Left("1 slow coffee")))
    val pr1 = f.sender.expectMsgType[Invoice]
    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr1.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add1, PaymentOnion.createMultiPartPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, pr1.paymentSecret.get, pr1.paymentMetadata)))

    // Partial payment exceeding the invoice amount, but incomplete because it promises to overpay.
    f.sender.send(handler, ReceivePayment(Some(1500 msat), Left("1 slow latte")))
    val pr2 = f.sender.expectMsgType[Invoice]
    val add2 = UpdateAddHtlc(ByteVector32.One, 1, 1600 msat, pr2.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add2, PaymentOnion.createMultiPartPayload(add2.amountMsat, 2000 msat, add2.cltvExpiry, pr2.paymentSecret.get, pr2.paymentMetadata)))

    awaitCond {
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.nonEmpty
    }

    val commands = f.register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]] :: f.register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]] :: Nil
    assert(commands.toSet === Set(
      Register.Forward(ActorRef.noSender, ByteVector32.One, CMD_FAIL_HTLC(0, Right(PaymentTimeout), commit = true)),
      Register.Forward(ActorRef.noSender, ByteVector32.One, CMD_FAIL_HTLC(1, Right(PaymentTimeout), commit = true))
    ))
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    // Extraneous HTLCs should be failed.
    f.sender.send(handler, MultiPartPaymentFSM.ExtraPaymentReceived(pr1.paymentHash, HtlcPart(1000 msat, UpdateAddHtlc(ByteVector32.One, 42, 200 msat, pr1.paymentHash, add1.cltvExpiry, add1.onionRoutingPacket)), Some(PaymentTimeout)))
    f.register.expectMsg(Register.Forward(ActorRef.noSender, ByteVector32.One, CMD_FAIL_HTLC(42, Right(PaymentTimeout), commit = true)))

    // The payment should still be pending in DB.
    val Some(incomingPayment) = nodeParams.db.payments.getIncomingPayment(pr1.paymentHash)
    assert(incomingPayment.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should handle multi-part payment success") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 500 millis, features = featuresWithMpp)
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.register.ref))

    val preimage = randomBytes32()
    f.sender.send(handler, ReceivePayment(Some(1000 msat), Left("1 fast coffee"), paymentPreimage_opt = Some(preimage)))
    val invoice = f.sender.expectMsgType[Invoice]

    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add1, PaymentOnion.createMultiPartPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
    // Invalid payment secret -> should be rejected.
    val add2 = UpdateAddHtlc(ByteVector32.Zeroes, 42, 200 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add2, PaymentOnion.createMultiPartPayload(add2.amountMsat, 1000 msat, add2.cltvExpiry, invoice.paymentSecret.get.reverse, invoice.paymentMetadata)))
    val add3 = add2.copy(id = 43)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add3, PaymentOnion.createMultiPartPayload(add3.amountMsat, 1000 msat, add3.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))

    f.register.expectMsgAllOf(
      Register.Forward(ActorRef.noSender, add2.channelId, CMD_FAIL_HTLC(add2.id, Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)), commit = true)),
      Register.Forward(ActorRef.noSender, add1.channelId, CMD_FULFILL_HTLC(add1.id, preimage, commit = true)),
      Register.Forward(ActorRef.noSender, add3.channelId, CMD_FULFILL_HTLC(add3.id, preimage, commit = true))
    )

    val paymentReceived = f.eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.parts.map(_.copy(timestamp = 0 unixms)).toSet === Set(PartialPayment(800 msat, ByteVector32.One, 0 unixms), PartialPayment(200 msat, ByteVector32.Zeroes, 0 unixms)))
    val received = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === 1000.msat)
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    // Extraneous HTLCs should be fulfilled.
    f.sender.send(handler, MultiPartPaymentFSM.ExtraPaymentReceived(invoice.paymentHash, HtlcPart(1000 msat, UpdateAddHtlc(ByteVector32.One, 44, 200 msat, invoice.paymentHash, add1.cltvExpiry, add1.onionRoutingPacket)), None))
    f.register.expectMsg(Register.Forward(ActorRef.noSender, ByteVector32.One, CMD_FULFILL_HTLC(44, preimage, commit = true)))
    assert(f.eventListener.expectMsgType[PaymentReceived].amount === 200.msat)
    val received2 = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(received2.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === 1200.msat)

    f.sender.send(handler, GetPendingPayments)
    f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
  }

  test("PaymentHandler should handle multi-part payment timeout then success") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 250 millis, features = featuresWithMpp)
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.register.ref))

    val preimage = randomBytes32()
    f.sender.send(handler, ReceivePayment(Some(1000 msat), Left("1 coffee, no sugar"), paymentPreimage_opt = Some(preimage)))
    val invoice = f.sender.expectMsgType[Invoice]
    assert(invoice.features.hasFeature(Features.BasicMultiPartPayment))
    assert(invoice.paymentHash == Crypto.sha256(preimage))

    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add1, PaymentOnion.createMultiPartPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
    f.register.expectMsg(Register.Forward(ActorRef.noSender, ByteVector32.One, CMD_FAIL_HTLC(0, Right(PaymentTimeout), commit = true)))
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    val add2 = UpdateAddHtlc(ByteVector32.One, 2, 300 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add2, PaymentOnion.createMultiPartPayload(add2.amountMsat, 1000 msat, add2.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))
    val add3 = UpdateAddHtlc(ByteVector32.Zeroes, 5, 700 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add3, PaymentOnion.createMultiPartPayload(add3.amountMsat, 1000 msat, add3.cltvExpiry, invoice.paymentSecret.get, invoice.paymentMetadata)))

    // the fulfill are not necessarily in the same order as the commands
    f.register.expectMsgAllOf(
      Register.Forward(ActorRef.noSender, add2.channelId, CMD_FULFILL_HTLC(2, preimage, commit = true)),
      Register.Forward(ActorRef.noSender, add3.channelId, CMD_FULFILL_HTLC(5, preimage, commit = true))
    )

    val paymentReceived = f.eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.paymentHash === invoice.paymentHash)
    assert(paymentReceived.parts.map(_.copy(timestamp = 0 unixms)).toSet === Set(PartialPayment(300 msat, ByteVector32.One, 0 unixms), PartialPayment(700 msat, ByteVector32.Zeroes, 0 unixms)))
    val received = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === 1000.msat)
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })
  }

  test("PaymentHandler should handle single-part KeySend payment") { f =>
    import f._

    val amountMsat = 42000 msat
    val paymentPreimage = randomBytes32()
    val paymentHash = Crypto.sha256(paymentPreimage)
    val paymentSecret = randomBytes32()
    val payload = FinalTlvPayload(TlvStream(Seq(OnionPaymentPayloadTlv.AmountToForward(amountMsat), OnionPaymentPayloadTlv.OutgoingCltv(defaultExpiry), OnionPaymentPayloadTlv.PaymentData(paymentSecret, 0 msat), OnionPaymentPayloadTlv.KeySend(paymentPreimage))))

    assert(nodeParams.db.payments.getIncomingPayment(paymentHash) === None)

    val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithKeySend, IncomingPaymentPacket.FinalPacket(add, payload))
    register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]

    val paymentReceived = eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0 unixms))) === PaymentReceived(add.paymentHash, PartialPayment(amountMsat, add.channelId, timestamp = 0 unixms) :: Nil))
    val received = nodeParams.db.payments.getIncomingPayment(paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0 unixms) === IncomingPaymentStatus.Received(amountMsat, 0 unixms))
  }

  test("PaymentHandler should reject KeySend payment when feature is disabled") { f =>
    import f._

    val amountMsat = 42000 msat
    val paymentPreimage = randomBytes32()
    val paymentHash = Crypto.sha256(paymentPreimage)
    val paymentSecret = randomBytes32()
    val payload = FinalTlvPayload(TlvStream(Seq(OnionPaymentPayloadTlv.AmountToForward(amountMsat), OnionPaymentPayloadTlv.OutgoingCltv(defaultExpiry), OnionPaymentPayloadTlv.PaymentData(paymentSecret, 0 msat), OnionPaymentPayloadTlv.KeySend(paymentPreimage))))

    assert(nodeParams.db.payments.getIncomingPayment(paymentHash) === None)

    val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, payload))

    f.register.expectMsg(Register.Forward(ActorRef.noSender, add.channelId, CMD_FAIL_HTLC(add.id, Right(IncorrectOrUnknownPaymentDetails(42000 msat, nodeParams.currentBlockHeight)), commit = true)))
    assert(nodeParams.db.payments.getIncomingPayment(paymentHash) === None)
  }

  test("PaymentHandler should reject incoming payments if the invoice doesn't exist") { f =>
    import f._

    val paymentHash = randomBytes32()
    val paymentSecret = randomBytes32()
    assert(nodeParams.db.payments.getIncomingPayment(paymentHash) === None)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 1000 msat, paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithoutMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createSinglePartPayload(add.amountMsat, add.cltvExpiry, paymentSecret, None)))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.id === add.id)
    assert(cmd.reason === Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
  }

  test("PaymentHandler should reject incoming multi-part payment if the invoice doesn't exist") { f =>
    import f._

    val paymentHash = randomBytes32()
    val paymentSecret = randomBytes32()
    assert(nodeParams.db.payments.getIncomingPayment(paymentHash) === None)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, PaymentOnion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, paymentSecret, Some(hex"012345"))))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.id === add.id)
    assert(cmd.reason === Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
  }

  test("PaymentHandler should fail fulfilling incoming payments if the invoice doesn't exist") { f =>
    import f._

    val paymentPreimage = randomBytes32()
    val paymentHash = Crypto.sha256(paymentPreimage)
    assert(nodeParams.db.payments.getIncomingPayment(paymentHash) === None)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 1000 msat, paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    val fulfill = DoFulfill(paymentPreimage, MultiPartPaymentFSM.MultiPartPaymentSucceeded(paymentHash, Queue(HtlcPart(1000 msat, add))))
    sender.send(handlerWithoutMpp, fulfill)
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.id === add.id)
    assert(cmd.reason === Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
  }
}
