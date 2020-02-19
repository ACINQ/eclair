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
import akka.actor.Status.Failure
import akka.testkit.{TestActorRef, TestKit, TestProbe}
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.crypto.Sphinx
import fr.acinq.eclair.db.IncomingPaymentStatus
import fr.acinq.eclair.io.PayToOpenRequestEvent
import fr.acinq.eclair.payment.OutgoingPacket.buildOnion
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.receive.MultiPartHandler.{GetPendingPayments, PendingPayments, ReceivePayment}
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM.{HtlcPart, PayToOpenPart}
import fr.acinq.eclair.payment.receive.{MultiPartPaymentFSM, PaymentHandler}
import fr.acinq.eclair.payment.relay.CommandBuffer
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, LongToBtcAmount, MilliSatoshi, NodeParams, ShortChannelId, TestConstants, randomBytes32, randomKey}
import org.scalatest.{Outcome, fixture}
import scodec.bits.HexStringSyntax
import MultiPartHandlerSpec._

import scala.compat.Platform
import scala.concurrent.duration._

/**
 * Created by PM on 24/03/2017.
 */

class MultiPartHandlerSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, defaultExpiry: CltvExpiry, commandBuffer: TestProbe, eventListener: TestProbe, sender: TestProbe) {
    lazy val normalHandler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, commandBuffer.ref))
    lazy val mppHandler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams.copy(features = hex"028a8a"), commandBuffer.ref))
  }

  override def withFixture(test: OneArgTest): Outcome = {
    within(30 seconds) {
      val nodeParams = Alice.nodeParams
      val commandBuffer = TestProbe()
      val eventListener = TestProbe()
      system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, CltvExpiryDelta(12).toCltvExpiry(nodeParams.currentBlockHeight), commandBuffer, eventListener, TestProbe())))
    }
  }

  test("PaymentHandler should reply with a fulfill/fail, emit a PaymentReceived and add payment in DB") { f =>
    import f._

    val amountMsat = 42000 msat

    {
      sender.send(normalHandler, ReceivePayment(Some(amountMsat), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      val incoming = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
      assert(incoming.isDefined)
      assert(incoming.get.status === IncomingPaymentStatus.Pending)
      assert(!incoming.get.paymentRequest.isExpired)
      assert(Crypto.sha256(incoming.get.paymentPreimage) === pr.paymentHash)

      val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
      sender.send(normalHandler, IncomingPacket.FinalPacket(add, Onion.FinalLegacyPayload(add.amountMsat, add.cltvExpiry)))
      commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FULFILL_HTLC]]

      val paymentReceived = eventListener.expectMsgType[PaymentReceived]
      assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0))) === PaymentReceived(add.paymentHash, PartialPayment(amountMsat, add.channelId, timestamp = 0) :: Nil))
      val received = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
      assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
      assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0) === IncomingPaymentStatus.Received(amountMsat, 0))

      sender.expectNoMsg(50 millis)
    }

    {
      sender.send(mppHandler, ReceivePayment(Some(amountMsat), "another coffee with multi-part"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(pr.features.allowMultiPart)
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)

      val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
      sender.send(mppHandler, IncomingPacket.FinalPacket(add, Onion.FinalLegacyPayload(add.amountMsat, add.cltvExpiry)))
      commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FULFILL_HTLC]]

      val paymentReceived = eventListener.expectMsgType[PaymentReceived]
      assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0))) === PaymentReceived(add.paymentHash, PartialPayment(amountMsat, add.channelId, timestamp = 0) :: Nil))
      val received = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
      assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
      assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0) === IncomingPaymentStatus.Received(amountMsat, 0))

      sender.expectNoMsg(50 millis)
    }

    {
      sender.send(normalHandler, ReceivePayment(Some(amountMsat), "bad expiry"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)

      val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, pr.paymentHash, CltvExpiryDelta(3).toCltvExpiry(nodeParams.currentBlockHeight), TestConstants.emptyOnionPacket)
      sender.send(normalHandler, IncomingPacket.FinalPacket(add, Onion.FinalLegacyPayload(add.amountMsat, add.cltvExpiry)))
      val cmd = commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]].cmd
      assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(amountMsat, nodeParams.currentBlockHeight)))
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)

      eventListener.expectNoMsg(100 milliseconds)
      sender.expectNoMsg(50 millis)
    }
  }

  test("Payment request generation should fail when the amount is not valid") { f =>
    import f._

    // negative amount should fail
    sender.send(normalHandler, ReceivePayment(Some(-50 msat), "1 coffee"))
    val negativeError = sender.expectMsgType[Failure]
    assert(negativeError.cause.getMessage.contains("amount is not valid"))

    // amount = 0 should fail
    sender.send(normalHandler, ReceivePayment(Some(0 msat), "1 coffee"))
    val zeroError = sender.expectMsgType[Failure]
    assert(zeroError.cause.getMessage.contains("amount is not valid"))

    // success with 1 mBTC
    sender.send(normalHandler, ReceivePayment(Some(100000000 msat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.amount.contains(100000000 msat) && pr.nodeId.toString == nodeParams.nodeId.toString)
  }

  test("Payment request generation should succeed when the amount is not set") { f =>
    import f._

    sender.send(normalHandler, ReceivePayment(None, "This is a donation PR"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.amount.isEmpty && pr.nodeId.toString == Alice.nodeParams.nodeId.toString)
  }

  test("Payment request generation should handle custom expiries or use the default otherwise") { f =>
    import f._

    sender.send(normalHandler, ReceivePayment(Some(42000 msat), "1 coffee"))
    assert(sender.expectMsgType[PaymentRequest].expiry === Some(Alice.nodeParams.paymentRequestExpiry.toSeconds))

    sender.send(normalHandler, ReceivePayment(Some(42000 msat), "1 coffee with custom expiry", expirySeconds_opt = Some(60)))
    assert(sender.expectMsgType[PaymentRequest].expiry === Some(60))
  }

  test("Payment request generation with trampoline support") { _ =>
    val sender = TestProbe()

    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = false), TestProbe().ref))
      sender.send(handler, ReceivePayment(Some(42 msat), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(!pr.features.allowMultiPart)
      assert(!pr.features.allowTrampoline)
    }

    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = false, features = hex"028a8a"), TestProbe().ref))
      sender.send(handler, ReceivePayment(Some(42 msat), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(pr.features.allowMultiPart)
      assert(!pr.features.allowTrampoline)
    }

    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = true), TestProbe().ref))
      sender.send(handler, ReceivePayment(Some(42 msat), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(!pr.features.allowMultiPart)
      assert(pr.features.allowTrampoline)
    }

    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = true, features = hex"028a8a"), TestProbe().ref))
      sender.send(handler, ReceivePayment(Some(42 msat), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(pr.features.allowMultiPart)
      assert(pr.features.allowTrampoline)
    }
  }

  test("Generated payment request contains the provided extra hops") { f =>
    import f._

    val x = randomKey.publicKey
    val y = randomKey.publicKey
    val extraHop_x_y = ExtraHop(x, ShortChannelId(1), 10 msat, 11, CltvExpiryDelta(12))
    val extraHop_y_z = ExtraHop(y, ShortChannelId(2), 20 msat, 21, CltvExpiryDelta(22))
    val extraHop_x_t = ExtraHop(x, ShortChannelId(3), 30 msat, 31, CltvExpiryDelta(32))
    val route_x_z = extraHop_x_y :: extraHop_y_z :: Nil
    val route_x_t = extraHop_x_t :: Nil

    sender.send(normalHandler, ReceivePayment(Some(42000 msat), "1 coffee with additional routing info", extraHops = List(route_x_z, route_x_t)))
    assert(sender.expectMsgType[PaymentRequest].routingInfo === Seq(route_x_z, route_x_t))

    sender.send(normalHandler, ReceivePayment(Some(42000 msat), "1 coffee without routing info"))
    assert(sender.expectMsgType[PaymentRequest].routingInfo === Nil)
  }

  test("PaymentHandler should reject incoming payments if the payment request is expired") { f =>
    import f._

    sender.send(normalHandler, ReceivePayment(Some(1000 msat), "some desc", expirySeconds_opt = Some(0)))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(!pr.features.allowMultiPart)
    assert(pr.isExpired)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 1000 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(normalHandler, IncomingPacket.FinalPacket(add, Onion.FinalLegacyPayload(add.amountMsat, add.cltvExpiry)))
    commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]]
    val Some(incoming) = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(incoming.paymentRequest.isExpired && incoming.status === IncomingPaymentStatus.Expired)
  }

  test("PaymentHandler should reject incoming multi-part payment if the payment request is expired") { f =>
    import f._

    sender.send(mppHandler, ReceivePayment(Some(1000 msat), "multi-part expired", expirySeconds_opt = Some(0)))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)
    assert(pr.isExpired)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(mppHandler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, pr.paymentSecret.get)))
    val cmd = commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]].cmd
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    val Some(incoming) = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(incoming.paymentRequest.isExpired && incoming.status === IncomingPaymentStatus.Expired)
  }

  test("PaymentHandler should reject incoming multi-part payment if the payment request does not allow it") { f =>
    import f._

    sender.send(normalHandler, ReceivePayment(Some(1000 msat), "no multi-part support"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(!pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(normalHandler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, pr.paymentSecret.get)))
    val cmd = commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]].cmd
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an invalid expiry") { f =>
    import f._

    sender.send(mppHandler, ReceivePayment(Some(1000 msat), "multi-part invalid expiry"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, CltvExpiryDelta(1).toCltvExpiry(nodeParams.currentBlockHeight), TestConstants.emptyOnionPacket)
    sender.send(mppHandler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, pr.paymentSecret.get)))
    val cmd = commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]].cmd
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an unknown payment hash") { f =>
    import f._

    sender.send(mppHandler, ReceivePayment(Some(1000 msat), "multi-part unknown payment hash"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash.reverse, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(mppHandler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, pr.paymentSecret.get)))
    val cmd = commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]].cmd
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with a total amount too low") { f =>
    import f._

    sender.send(mppHandler, ReceivePayment(Some(1000 msat), "multi-part total amount too low"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(mppHandler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 999 msat, add.cltvExpiry, pr.paymentSecret.get)))
    val cmd = commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]].cmd
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(999 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with a total amount too high") { f =>
    import f._

    sender.send(mppHandler, ReceivePayment(Some(1000 msat), "multi-part total amount too low"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(mppHandler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 2001 msat, add.cltvExpiry, pr.paymentSecret.get)))
    val cmd = commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]].cmd
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(2001 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an invalid payment secret") { f =>
    import f._

    sender.send(mppHandler, ReceivePayment(Some(1000 msat), "multi-part invalid payment secret"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    // Invalid payment secret.
    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(mppHandler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, pr.paymentSecret.get.reverse)))
    val cmd = commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]].cmd
    assert(cmd.reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should handle multi-part payment timeout") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 200 millis, features = hex"028a8a")
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))

    // Partial payment missing additional parts.
    f.sender.send(handler, ReceivePayment(Some(1000 msat), "1 slow coffee"))
    val pr1 = f.sender.expectMsgType[PaymentRequest]
    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr1.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add1, Onion.createMultiPartPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, pr1.paymentSecret.get)))

    // Partial payment exceeding the invoice amount, but incomplete because it promises to overpay.
    f.sender.send(handler, ReceivePayment(Some(1500 msat), "1 slow latte"))
    val pr2 = f.sender.expectMsgType[PaymentRequest]
    val add2 = UpdateAddHtlc(ByteVector32.One, 1, 1600 msat, pr2.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add2, Onion.createMultiPartPayload(add2.amountMsat, 2000 msat, add2.cltvExpiry, pr2.paymentSecret.get)))

    f.sender.send(handler, GetPendingPayments)
    assert(f.sender.expectMsgType[PendingPayments].paymentHashes.nonEmpty)

    val commands = f.commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]] :: f.commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FAIL_HTLC]] :: Nil
    assert(commands.toSet === Set(
      CommandBuffer.CommandSend(ByteVector32.One, CMD_FAIL_HTLC(0, Right(PaymentTimeout), commit = true)),
      CommandBuffer.CommandSend(ByteVector32.One, CMD_FAIL_HTLC(1, Right(PaymentTimeout), commit = true))
    ))
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    // Extraneous HTLCs should be failed.
    val extraHtlc = UpdateAddHtlc(ByteVector32.One, 42, 200 msat, pr1.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, MultiPartPaymentFSM.ExtraPaymentReceived(pr1.paymentHash, HtlcPart(1000 msat, extraHtlc), Some(PaymentTimeout)))
    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(ByteVector32.One, CMD_FAIL_HTLC(42, Right(PaymentTimeout), commit = true)))

    // The payment should still be pending in DB.
    val Some(incomingPayment) = nodeParams.db.payments.getIncomingPayment(pr1.paymentHash)
    assert(incomingPayment.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should handle multi-part payment success") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 500 millis, features = hex"028a8a")
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))

    f.sender.send(handler, ReceivePayment(Some(1000 msat), "1 fast coffee"))
    val pr = f.sender.expectMsgType[PaymentRequest]

    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add1, Onion.createMultiPartPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, pr.paymentSecret.get)))
    // Invalid payment secret -> should be rejected.
    val add2 = UpdateAddHtlc(ByteVector32.Zeroes, 42, 200 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add2, Onion.createMultiPartPayload(add2.amountMsat, 1000 msat, add2.cltvExpiry, pr.paymentSecret.get.reverse)))
    val add3 = add2.copy(id = 43)
    f.sender.send(handler, IncomingPacket.FinalPacket(add3, Onion.createMultiPartPayload(add3.amountMsat, 1000 msat, add3.cltvExpiry, pr.paymentSecret.get)))

    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(add2.channelId, CMD_FAIL_HTLC(add2.id, Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)), commit = true)))
    val cmd1 = f.commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FULFILL_HTLC]]
    assert(cmd1.cmd.id === add1.id)
    assert(cmd1.channelId === add1.channelId)
    assert(Crypto.sha256(cmd1.cmd.r) === pr.paymentHash)
    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(add3.channelId, CMD_FULFILL_HTLC(add3.id, cmd1.cmd.r, commit = true)))

    f.sender.send(handler, CommandBuffer.CommandAck(add1.channelId, add1.id))
    f.commandBuffer.expectMsg(CommandBuffer.CommandAck(add1.channelId, add1.id))

    val paymentReceived = f.eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0))) === PaymentReceived(pr.paymentHash, PartialPayment(800 msat, ByteVector32.One, 0) :: PartialPayment(200 msat, ByteVector32.Zeroes, 0) :: Nil))
    val received = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === 1000.msat)
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    // Extraneous HTLCs should be fulfilled.
    val extraHtlc = UpdateAddHtlc(ByteVector32.One, 44, 200 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, MultiPartPaymentFSM.ExtraPaymentReceived(pr.paymentHash, HtlcPart(1000 msat, extraHtlc), None))
    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(ByteVector32.One, CMD_FULFILL_HTLC(44, cmd1.cmd.r, commit = true)))
    assert(f.eventListener.expectMsgType[PaymentReceived].amount === 200.msat)
    val received2 = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(received2.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === 1200.msat)

    f.sender.send(handler, GetPendingPayments)
    f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
  }

  test("PaymentHandler should handle multi-part payment timeout then success") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 250 millis, features = hex"028a8a")
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))

    f.sender.send(handler, ReceivePayment(Some(1000 msat), "1 coffee, no sugar"))
    val pr = f.sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add1, Onion.createMultiPartPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, pr.paymentSecret.get)))
    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(ByteVector32.One, CMD_FAIL_HTLC(0, Right(PaymentTimeout), commit = true)))
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    val add2 = UpdateAddHtlc(ByteVector32.One, 2, 300 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add2, Onion.createMultiPartPayload(add2.amountMsat, 1000 msat, add2.cltvExpiry, pr.paymentSecret.get)))
    val add3 = UpdateAddHtlc(ByteVector32.Zeroes, 5, 700 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add3, Onion.createMultiPartPayload(add3.amountMsat, 1000 msat, add3.cltvExpiry, pr.paymentSecret.get)))

    val cmd1 = f.commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FULFILL_HTLC]]
    assert(cmd1.channelId === add2.channelId)
    assert(cmd1.cmd.id === 2)
    assert(Crypto.sha256(cmd1.cmd.r) === pr.paymentHash)
    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(add3.channelId, CMD_FULFILL_HTLC(5, cmd1.cmd.r, commit = true)))

    val paymentReceived = f.eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0))) === PaymentReceived(pr.paymentHash, PartialPayment(300 msat, ByteVector32.One, 0) :: PartialPayment(700 msat, ByteVector32.Zeroes, 0) :: Nil))
    val received = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === 1000.msat)
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })
  }

  test("PaymentHandler should handle single-part payment success (htlc)") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 500 millis, features = hex"028a8a")
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))

    f.sender.send(handler, ReceivePayment(Some(1000 msat), "1 fast coffee"))
    val pr = f.sender.expectMsgType[PaymentRequest]

    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 1000 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add1, Onion.createMultiPartPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, pr.paymentSecret.get)))

    val cmd1 = f.commandBuffer.expectMsgType[CommandBuffer.CommandSend[CMD_FULFILL_HTLC]]
    assert(cmd1.cmd.id === add1.id)
    assert(cmd1.channelId === add1.channelId)
    assert(Crypto.sha256(cmd1.cmd.r) === pr.paymentHash)

    f.sender.send(handler, CommandBuffer.CommandAck(add1.channelId, add1.id))
    f.commandBuffer.expectMsg(CommandBuffer.CommandAck(add1.channelId, add1.id))

    val paymentReceived = f.eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0))) === PaymentReceived(pr.paymentHash, PartialPayment(1000 msat, ByteVector32.One, 0) :: Nil))
    val received = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === 1000.msat)
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    // Extraneous HTLCs should be fulfilled.
    val extraHtlc = UpdateAddHtlc(ByteVector32.One, 44, 200 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, MultiPartPaymentFSM.ExtraPaymentReceived(pr.paymentHash, HtlcPart(1000 msat, extraHtlc), None))
    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(ByteVector32.One, CMD_FULFILL_HTLC(44, cmd1.cmd.r, commit = true)))
    assert(f.eventListener.expectMsgType[PaymentReceived].amount === 200.msat)
    val received2 = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(received2.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === 1200.msat)

    f.sender.send(handler, GetPendingPayments)
    f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
  }

  test("PaymentHandler should handle single-part payment success (pay-to-open, no user confirmation)") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 500 millis, features = hex"028a8a")
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))

    f.sender.send(handler, ReceivePayment(Some(1000 msat), "1 fast coffee"))
    val pr = f.sender.expectMsgType[PaymentRequest]

    val p1 = PayToOpenRequest(Block.RegtestGenesisBlock.hash, 1 mbtc, 1000 msat, 0 sat, pr.paymentHash, payToOpenFeeThresholdSatoshis, payToOpenFeeProportionalMillionths, secondsFromNow(60), None)
    f.sender.send(handler, p1)

    val r1 = f.sender.expectMsgType[PayToOpenResponse]
    assert(Crypto.sha256(r1.paymentPreimage) === p1.paymentHash)

    val paymentReceived = f.eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0)).toList) === PaymentReceived(pr.paymentHash, PartialPayment(1000 msat, ByteVector32.Zeroes, 0) :: Nil))
    val received = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === 1000.msat)
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    // Extraneous pay-to-opens will be ignored
    val pExtra = PayToOpenRequest(Block.RegtestGenesisBlock.hash, 1 mbtc, 200 msat, 0 sat, pr.paymentHash, payToOpenFeeThresholdSatoshis, payToOpenFeeProportionalMillionths, secondsFromNow(60), None)
    f.sender.send(handler, MultiPartPaymentFSM.ExtraPaymentReceived(pr.paymentHash, PayToOpenPart(1000 msat, pExtra, f.sender.ref), None))
    f.sender.expectNoMsg()

    f.sender.send(handler, GetPendingPayments)
    f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
  }

  test("PaymentHandler should handle single-part payment success (pay-to-open, with user confirmation)") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 500 millis, features = hex"028a8a")
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PayToOpenRequestEvent])

    val amount = 20000000 msat
    val fee = PayToOpenRequest.computeFee(amount, 10000 sat, 1000)
    val funding = PayToOpenRequest.computeFunding(amount, fee)

    f.sender.send(handler, ReceivePayment(Some(20000000 msat), "1 fast coffee"))
    val pr = f.sender.expectMsgType[PaymentRequest]

    val p1 = PayToOpenRequest(Block.RegtestGenesisBlock.hash, funding, amount, fee, pr.paymentHash, payToOpenFeeThresholdSatoshis, payToOpenFeeProportionalMillionths, secondsFromNow(60), None)
    f.sender.send(handler, p1)

    val e1 = eventListener.expectMsgType[PayToOpenRequestEvent]
    assert(e1.payToOpenRequest === p1)
    assert(e1.peer === f.sender.ref)
    e1.decision.success(true)

    val r1 = f.sender.expectMsgType[PayToOpenResponse]
    assert(Crypto.sha256(r1.paymentPreimage) === p1.paymentHash)

    val paymentReceived = f.eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0)).toList) === PaymentReceived(pr.paymentHash, PartialPayment(amount - fee, ByteVector32.Zeroes, 0) :: Nil))
    val received = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === amount - fee)
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    f.sender.send(handler, GetPendingPayments)
    f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
  }

  test("PaymentHandler should handle single-part payment success (pay-to-open, user says no)") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 500 millis, features = hex"028a8a")
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PayToOpenRequestEvent])

    val amount = 20000000 msat
    val fee = PayToOpenRequest.computeFee(amount, 10000 sat, 1000)
    val funding = PayToOpenRequest.computeFunding(amount, fee)

    f.sender.send(handler, ReceivePayment(Some(20000000 msat), "1 fast coffee"))
    val pr = f.sender.expectMsgType[PaymentRequest]

    val p1 = PayToOpenRequest(Block.RegtestGenesisBlock.hash, funding, amount, fee, pr.paymentHash, payToOpenFeeThresholdSatoshis, payToOpenFeeProportionalMillionths, secondsFromNow(60), None)
    f.sender.send(handler, p1)

    val e1 = eventListener.expectMsgType[PayToOpenRequestEvent]
    assert(e1.payToOpenRequest === p1)
    assert(e1.peer === f.sender.ref)
    e1.decision.success(false) // user says no

    val r1 = f.sender.expectMsgType[PayToOpenResponse]
    assert(r1.paymentPreimage === ByteVector32.Zeroes)

    f.sender.send(handler, GetPendingPayments)
    f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
  }

  test("PaymentHandler should handle single-part payment success (pay-to-open, timeout)") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 500 millis, features = hex"028a8a")
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PayToOpenRequestEvent])

    val amount = 20000000 msat
    val fee = PayToOpenRequest.computeFee(amount, 10000 sat, 1000)
    val funding = PayToOpenRequest.computeFunding(amount, fee)

    f.sender.send(handler, ReceivePayment(Some(20000000 msat), "1 fast coffee"))
    val pr = f.sender.expectMsgType[PaymentRequest]

    val p1 = PayToOpenRequest(Block.RegtestGenesisBlock.hash, funding, amount, fee, pr.paymentHash, payToOpenFeeThresholdSatoshis, payToOpenFeeProportionalMillionths, secondsFromNow(2), None)
    f.sender.send(handler, p1)

    val e1 = eventListener.expectMsgType[PayToOpenRequestEvent]
    assert(e1.payToOpenRequest === p1)
    assert(e1.peer === f.sender.ref)
    Thread.sleep(3000) // timeout

    val r1 = f.sender.expectMsgType[PayToOpenResponse]
    assert(r1.paymentPreimage === ByteVector32.Zeroes)

    f.sender.send(handler, GetPendingPayments)
    f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
  }

  def mixPaymentSuccess(f: FixtureParam, invoiceAmount: Option[MilliSatoshi]) = {
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 500 millis, features = hex"028a8a")
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))
    val eventListener = TestProbe()
    system.eventStream.subscribe(eventListener.ref, classOf[PayToOpenRequestEvent])

    f.sender.send(handler, ReceivePayment(invoiceAmount, "1 bò bún"))
    val pr = f.sender.expectMsgType[PaymentRequest]

    val add1 = UpdateAddHtlc(randomBytes32, 0, 20000000 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add1, Onion.createMultiPartPayload(add1.amountMsat, 100000000 msat, add1.cltvExpiry, pr.paymentSecret.get)))

    val add2 = UpdateAddHtlc(randomBytes32, 0, 30000000 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add2, Onion.createMultiPartPayload(add2.amountMsat, 100000000 msat, add2.cltvExpiry, pr.paymentSecret.get)))

    val amount1 = 20000000 msat
    val fee1 = PayToOpenRequest.computeFee(amount1, 10000 sat, 1000)
    val funding1 = PayToOpenRequest.computeFunding(amount1, fee1)
    val payload1 = Onion.createMultiPartPayload(amount1, 100000000 msat, CltvExpiry(420000), pr.paymentSecret.get)
    val onion1 = buildOnion(Sphinx.PaymentPacket)(nodeParams.nodeId :: Nil, payload1 :: Nil, pr.paymentHash).packet
    val htlc1 = UpdateAddHtlc(ByteVector32.Zeroes, 0, payload1.amount, pr.paymentHash, payload1.expiry, onion1)
    val p1 = PayToOpenRequest(Block.RegtestGenesisBlock.hash, funding1, amount1, fee1, pr.paymentHash, payToOpenFeeThresholdSatoshis, payToOpenFeeProportionalMillionths, secondsFromNow(45), Some(htlc1))
    f.sender.send(handler, p1)

    val amount2 = 20000000 msat
    val fee2 = PayToOpenRequest.computeFee(amount2, 10000 sat, 1000)
    val funding2 = PayToOpenRequest.computeFunding(amount2, fee1)
    val payload2 = Onion.createMultiPartPayload(amount2, 100000000 msat, CltvExpiry(420000), pr.paymentSecret.get)
    val onion2 = buildOnion(Sphinx.PaymentPacket)(nodeParams.nodeId :: Nil, payload2 :: Nil, pr.paymentHash).packet
    val htlc2 = UpdateAddHtlc(ByteVector32.Zeroes, 0, payload2.amount, pr.paymentHash, payload2.expiry, onion2)
    val p2 = PayToOpenRequest(Block.RegtestGenesisBlock.hash, funding2, amount2, fee2, pr.paymentHash, payToOpenFeeThresholdSatoshis, payToOpenFeeProportionalMillionths, secondsFromNow(50), Some(htlc2))
    f.sender.send(handler, p2)

    val amount3 = 10000000 msat
    val fee3 = PayToOpenRequest.computeFee(amount3, 10000 sat, 1000)
    val funding3 = PayToOpenRequest.computeFunding(amount1, fee1)
    val payload3 = Onion.createMultiPartPayload(amount3, 100000000 msat, CltvExpiry(420000), pr.paymentSecret.get)
    val onion3 = buildOnion(Sphinx.PaymentPacket)(nodeParams.nodeId :: Nil, payload3 :: Nil, pr.paymentHash).packet
    val htlc3 = UpdateAddHtlc(ByteVector32.Zeroes, 0, payload3.amount, pr.paymentHash, payload3.expiry, onion3)
    val p3 = PayToOpenRequest(Block.RegtestGenesisBlock.hash, funding3, amount3, fee3, pr.paymentHash, payToOpenFeeThresholdSatoshis, payToOpenFeeProportionalMillionths, secondsFromNow(60), Some(htlc3))
    f.sender.send(handler, p3)

    val payToOpenAmount = amount1 + amount2 + amount3
    val payToOpenFee = PayToOpenRequest.computeFee(payToOpenAmount, 10000 sat, 1000)
    val payToOpenFunding = PayToOpenRequest.computeFunding(payToOpenAmount, payToOpenFee)

    val e1 = eventListener.expectMsgType[PayToOpenRequestEvent]
    assert(e1.payToOpenRequest === PayToOpenRequest(
      chainHash = p1.chainHash,
      fundingSatoshis = payToOpenFunding,
      amountMsat = payToOpenAmount,
      feeSatoshis = payToOpenFee,
      paymentHash = p1.paymentHash,
      feeThresholdSatoshis = payToOpenFeeThresholdSatoshis,
      feeProportionalMillionths = payToOpenFeeProportionalMillionths,
      expireAt = p1.expireAt,
      htlc_opt = None
    ))
    assert(e1.peer === f.sender.ref)
    e1.decision.success(true)

    val r1 = f.sender.expectMsgType[PayToOpenResponse]
    assert(Crypto.sha256(r1.paymentPreimage) === p1.paymentHash)
    // we only send one response
    f.sender.expectNoMsg()

    val paymentReceived = f.eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0)).toList) ===
      PaymentReceived(pr.paymentHash,
        PartialPayment(20000000 msat, add1.channelId, 0) ::
          PartialPayment(30000000 msat, add2.channelId, 0) ::
          PartialPayment(payToOpenAmount - payToOpenFee, ByteVector32.Zeroes, 0) :: Nil))
    val received = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === add1.amountMsat + add2.amountMsat + payToOpenAmount - payToOpenFee)
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })
  }

  test("PaymentHandler should handle single-part payment success (mix of htlc and pay-to-open, with user confirmation)") { f =>
    mixPaymentSuccess(f, Some(100000000 msat))
  }

  test("PaymentHandler should handle single-part payment success (amountless invoice, mix of htlc and pay-to-open, with user confirmation)") { f =>
    mixPaymentSuccess(f, None)
  }

}

object MultiPartHandlerSpec {

  /**
   * @param s number of seconds in the future
   * @return a unix timestamp
   */
  def secondsFromNow(s: Int): Long  = (Platform.currentTime.milliseconds + s.seconds).toSeconds

  val payToOpenFeeThresholdSatoshis = 10000 sat
  val payToOpenFeeProportionalMillionths = 1000
}
