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
import fr.acinq.bitcoin.{ByteVector32, Crypto}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC}
import fr.acinq.eclair.db.IncomingPaymentStatus
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.payment.PaymentRequest.ExtraHop
import fr.acinq.eclair.payment.receive.MultiPartHandler.{GetPendingPayments, PendingPayments, ReceivePayment}
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM.PendingPayment
import fr.acinq.eclair.payment.receive.{MultiPartPaymentFSM, PaymentHandler}
import fr.acinq.eclair.payment.relay.CommandBuffer
import fr.acinq.eclair.wire._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, LongToBtcAmount, NodeParams, ShortChannelId, TestConstants, randomKey}
import org.scalatest.{Outcome, fixture}

import scala.concurrent.duration._

/**
 * Created by PM on 24/03/2017.
 */

class MultiPartHandlerSpec extends TestKit(ActorSystem("test")) with fixture.FunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, defaultExpiry: CltvExpiry, handler: TestActorRef[PaymentHandler], commandBuffer: TestProbe, eventListener: TestProbe, sender: TestProbe)

  override def withFixture(test: OneArgTest): Outcome = {
    within(30 seconds) {
      val nodeParams = Alice.nodeParams
      val commandBuffer = TestProbe()
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, commandBuffer.ref))
      val eventListener = TestProbe()
      system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, CltvExpiryDelta(12).toCltvExpiry(nodeParams.currentBlockHeight), handler, commandBuffer, eventListener, TestProbe())))
    }
  }

  test("PaymentHandler should reply with a fulfill/fail, emit a PaymentReceived and add payment in DB") { f =>
    import f._

    val amountMsat = 42000 msat

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "1 coffee"))
      val pr = sender.expectMsgType[PaymentRequest]
      val incoming = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
      assert(incoming.isDefined)
      assert(incoming.get.status === IncomingPaymentStatus.Pending)
      assert(!incoming.get.paymentRequest.isExpired)
      assert(Crypto.sha256(incoming.get.paymentPreimage) === pr.paymentHash)

      val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
      sender.send(handler, IncomingPacket.FinalPacket(add, Onion.FinalLegacyPayload(add.amountMsat, add.cltvExpiry)))
      assert(commandBuffer.expectMsgType[CommandBuffer.CommandSend].cmd.isInstanceOf[CMD_FULFILL_HTLC])

      val paymentReceived = eventListener.expectMsgType[PaymentReceived]
      assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0))) === PaymentReceived(add.paymentHash, PartialPayment(amountMsat, add.channelId, timestamp = 0) :: Nil))
      val received = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
      assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
      assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0) === IncomingPaymentStatus.Received(amountMsat, 0))

      sender.expectNoMsg(50 millis)
    }

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "another coffee with multi-part", allowMultiPart = true))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(pr.features.allowMultiPart)
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)

      val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
      sender.send(handler, IncomingPacket.FinalPacket(add, Onion.FinalLegacyPayload(add.amountMsat, add.cltvExpiry)))
      assert(commandBuffer.expectMsgType[CommandBuffer.CommandSend].cmd.isInstanceOf[CMD_FULFILL_HTLC])

      val paymentReceived = eventListener.expectMsgType[PaymentReceived]
      assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0))) === PaymentReceived(add.paymentHash, PartialPayment(amountMsat, add.channelId, timestamp = 0) :: Nil))
      val received = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
      assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
      assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0) === IncomingPaymentStatus.Received(amountMsat, 0))

      sender.expectNoMsg(50 millis)
    }

    {
      sender.send(handler, ReceivePayment(Some(amountMsat), "bad expiry"))
      val pr = sender.expectMsgType[PaymentRequest]
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)

      val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, pr.paymentHash, CltvExpiryDelta(3).toCltvExpiry(nodeParams.currentBlockHeight), TestConstants.emptyOnionPacket)
      sender.send(handler, IncomingPacket.FinalPacket(add, Onion.FinalLegacyPayload(add.amountMsat, add.cltvExpiry)))
      assert(commandBuffer.expectMsgType[CommandBuffer.CommandSend].cmd.asInstanceOf[CMD_FAIL_HTLC].reason == Right(IncorrectOrUnknownPaymentDetails(amountMsat, nodeParams.currentBlockHeight)))
      assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)

      eventListener.expectNoMsg(100 milliseconds)
      sender.expectNoMsg(50 millis)
    }
  }

  test("Payment request generation should fail when the amount is not valid") { f =>
    import f._

    // negative amount should fail
    sender.send(handler, ReceivePayment(Some(-50 msat), "1 coffee"))
    val negativeError = sender.expectMsgType[Failure]
    assert(negativeError.cause.getMessage.contains("amount is not valid"))

    // amount = 0 should fail
    sender.send(handler, ReceivePayment(Some(0 msat), "1 coffee"))
    val zeroError = sender.expectMsgType[Failure]
    assert(zeroError.cause.getMessage.contains("amount is not valid"))

    // success with 1 mBTC
    sender.send(handler, ReceivePayment(Some(100000000 msat), "1 coffee"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.amount.contains(100000000 msat) && pr.nodeId.toString == nodeParams.nodeId.toString)
  }

  test("Payment request generation should succeed when the amount is not set") { f =>
    import f._

    sender.send(handler, ReceivePayment(None, "This is a donation PR"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.amount.isEmpty && pr.nodeId.toString == Alice.nodeParams.nodeId.toString)
  }

  test("Payment request generation should handle custom expiries or use the default otherwise") { f =>
    import f._

    sender.send(handler, ReceivePayment(Some(42000 msat), "1 coffee"))
    assert(sender.expectMsgType[PaymentRequest].expiry === Some(Alice.nodeParams.paymentRequestExpiry.toSeconds))

    sender.send(handler, ReceivePayment(Some(42000 msat), "1 coffee with custom expiry", expirySeconds_opt = Some(60)))
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
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = false), TestProbe().ref))
      sender.send(handler, ReceivePayment(Some(42 msat), "1 coffee", allowMultiPart = true))
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
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = true), TestProbe().ref))
      sender.send(handler, ReceivePayment(Some(42 msat), "1 coffee", allowMultiPart = true))
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

    sender.send(handler, ReceivePayment(Some(42000 msat), "1 coffee with additional routing info", extraHops = List(route_x_z, route_x_t)))
    assert(sender.expectMsgType[PaymentRequest].routingInfo === Seq(route_x_z, route_x_t))

    sender.send(handler, ReceivePayment(Some(42000 msat), "1 coffee without routing info"))
    assert(sender.expectMsgType[PaymentRequest].routingInfo === Nil)
  }

  test("PaymentHandler should reject incoming payments if the payment request is expired") { f =>
    import f._

    sender.send(handler, ReceivePayment(Some(1000 msat), "some desc", expirySeconds_opt = Some(0)))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(!pr.features.allowMultiPart)
    assert(pr.isExpired)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 1000 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handler, IncomingPacket.FinalPacket(add, Onion.FinalLegacyPayload(add.amountMsat, add.cltvExpiry)))
    assert(commandBuffer.expectMsgType[CommandBuffer.CommandSend].cmd.isInstanceOf[CMD_FAIL_HTLC])
    val Some(incoming) = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(incoming.paymentRequest.isExpired && incoming.status === IncomingPaymentStatus.Expired)
  }

  test("PaymentHandler should reject incoming multi-part payment if the payment request is expired") { f =>
    import f._

    sender.send(handler, ReceivePayment(Some(1000 msat), "multi-part expired", expirySeconds_opt = Some(0), allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)
    assert(pr.isExpired)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, pr.paymentSecret.get)))
    assert(commandBuffer.expectMsgType[CommandBuffer.CommandSend].cmd.asInstanceOf[CMD_FAIL_HTLC].reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    val Some(incoming) = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(incoming.paymentRequest.isExpired && incoming.status === IncomingPaymentStatus.Expired)
  }

  test("PaymentHandler should reject incoming multi-part payment if the payment request does not allow it") { f =>
    import f._

    sender.send(handler, ReceivePayment(Some(1000 msat), "no multi-part support"))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(!pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, pr.paymentSecret.get)))
    assert(commandBuffer.expectMsgType[CommandBuffer.CommandSend].cmd.asInstanceOf[CMD_FAIL_HTLC].reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an invalid expiry") { f =>
    import f._

    sender.send(handler, ReceivePayment(Some(1000 msat), "multi-part invalid expiry", allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, CltvExpiryDelta(1).toCltvExpiry(nodeParams.currentBlockHeight), TestConstants.emptyOnionPacket)
    sender.send(handler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, pr.paymentSecret.get)))
    assert(commandBuffer.expectMsgType[CommandBuffer.CommandSend].cmd.asInstanceOf[CMD_FAIL_HTLC].reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an unknown payment hash") { f =>
    import f._

    sender.send(handler, ReceivePayment(Some(1000 msat), "multi-part unknown payment hash", allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash.reverse, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, pr.paymentSecret.get)))
    assert(commandBuffer.expectMsgType[CommandBuffer.CommandSend].cmd.asInstanceOf[CMD_FAIL_HTLC].reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with a total amount too low") { f =>
    import f._

    sender.send(handler, ReceivePayment(Some(1000 msat), "multi-part total amount too low", allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 999 msat, add.cltvExpiry, pr.paymentSecret.get)))
    assert(commandBuffer.expectMsgType[CommandBuffer.CommandSend].cmd.asInstanceOf[CMD_FAIL_HTLC].reason == Right(IncorrectOrUnknownPaymentDetails(999 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with a total amount too high") { f =>
    import f._

    sender.send(handler, ReceivePayment(Some(1000 msat), "multi-part total amount too low", allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 2001 msat, add.cltvExpiry, pr.paymentSecret.get)))
    assert(commandBuffer.expectMsgType[CommandBuffer.CommandSend].cmd.asInstanceOf[CMD_FAIL_HTLC].reason == Right(IncorrectOrUnknownPaymentDetails(2001 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an invalid payment secret") { f =>
    import f._

    sender.send(handler, ReceivePayment(Some(1000 msat), "multi-part invalid payment secret", allowMultiPart = true))
    val pr = sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    // Invalid payment secret.
    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket)
    sender.send(handler, IncomingPacket.FinalPacket(add, Onion.createMultiPartPayload(add.amountMsat, 1000 msat, add.cltvExpiry, pr.paymentSecret.get.reverse)))
    assert(commandBuffer.expectMsgType[CommandBuffer.CommandSend].cmd.asInstanceOf[CMD_FAIL_HTLC].reason == Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(pr.paymentHash).get.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should handle multi-part payment timeout") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 200 millis)
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))

    // Partial payment missing additional parts.
    f.sender.send(handler, ReceivePayment(Some(1000 msat), "1 slow coffee", allowMultiPart = true))
    val pr1 = f.sender.expectMsgType[PaymentRequest]
    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr1.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add1, Onion.createMultiPartPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, pr1.paymentSecret.get)))

    // Partial payment exceeding the invoice amount, but incomplete because it promises to overpay.
    f.sender.send(handler, ReceivePayment(Some(1500 msat), "1 slow latte", allowMultiPart = true))
    val pr2 = f.sender.expectMsgType[PaymentRequest]
    val add2 = UpdateAddHtlc(ByteVector32.One, 1, 1600 msat, pr2.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add2, Onion.createMultiPartPayload(add2.amountMsat, 2000 msat, add2.cltvExpiry, pr2.paymentSecret.get)))

    f.sender.send(handler, GetPendingPayments)
    assert(f.sender.expectMsgType[PendingPayments].paymentHashes.nonEmpty)

    val commands = f.commandBuffer.expectMsgType[CommandBuffer.CommandSend] :: f.commandBuffer.expectMsgType[CommandBuffer.CommandSend] :: Nil
    assert(commands.toSet === Set(
      CommandBuffer.CommandSend(ByteVector32.One, 0, CMD_FAIL_HTLC(0, Right(PaymentTimeout), commit = true)),
      CommandBuffer.CommandSend(ByteVector32.One, 1, CMD_FAIL_HTLC(1, Right(PaymentTimeout), commit = true))
    ))
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    // Extraneous HTLCs should be failed.
    f.sender.send(handler, MultiPartPaymentFSM.ExtraHtlcReceived(pr1.paymentHash, PendingPayment(42, PartialPayment(200 msat, ByteVector32.One)), Some(PaymentTimeout)))
    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(ByteVector32.One, 42, CMD_FAIL_HTLC(42, Right(PaymentTimeout), commit = true)))

    // The payment should still be pending in DB.
    val Some(incomingPayment) = nodeParams.db.payments.getIncomingPayment(pr1.paymentHash)
    assert(incomingPayment.status === IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should handle multi-part payment success") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 500 millis)
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))

    f.sender.send(handler, ReceivePayment(Some(1000 msat), "1 fast coffee", allowMultiPart = true))
    val pr = f.sender.expectMsgType[PaymentRequest]

    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add1, Onion.createMultiPartPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, pr.paymentSecret.get)))
    // Invalid payment secret -> should be rejected.
    val add2 = UpdateAddHtlc(ByteVector32.Zeroes, 42, 200 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add2, Onion.createMultiPartPayload(add2.amountMsat, 1000 msat, add2.cltvExpiry, pr.paymentSecret.get.reverse)))
    val add3 = add2.copy(id = 43)
    f.sender.send(handler, IncomingPacket.FinalPacket(add3, Onion.createMultiPartPayload(add3.amountMsat, 1000 msat, add3.cltvExpiry, pr.paymentSecret.get)))

    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(add2.channelId, add2.id, CMD_FAIL_HTLC(add2.id, Right(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)), commit = true)))
    val cmd1 = f.commandBuffer.expectMsgType[CommandBuffer.CommandSend]
    assert(cmd1.htlcId === add1.id)
    assert(cmd1.channelId === add1.channelId)
    val fulfill1 = cmd1.cmd.asInstanceOf[CMD_FULFILL_HTLC]
    assert(Crypto.sha256(fulfill1.r) === pr.paymentHash)
    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(add3.channelId, add3.id, CMD_FULFILL_HTLC(add3.id, fulfill1.r, commit = true)))

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
    f.sender.send(handler, MultiPartPaymentFSM.ExtraHtlcReceived(pr.paymentHash, PendingPayment(44, PartialPayment(200 msat, ByteVector32.One, 0)), None))
    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(ByteVector32.One, 44, CMD_FULFILL_HTLC(44, fulfill1.r, commit = true)))
    assert(f.eventListener.expectMsgType[PaymentReceived].amount === 200.msat)
    val received2 = nodeParams.db.payments.getIncomingPayment(pr.paymentHash)
    assert(received2.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount === 1200.msat)

    f.sender.send(handler, GetPendingPayments)
    f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
  }

  test("PaymentHandler should handle multi-part payment timeout then success") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 250 millis)
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.commandBuffer.ref))

    f.sender.send(handler, ReceivePayment(Some(1000 msat), "1 coffee, no sugar", allowMultiPart = true))
    val pr = f.sender.expectMsgType[PaymentRequest]
    assert(pr.features.allowMultiPart)

    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add1, Onion.createMultiPartPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, pr.paymentSecret.get)))
    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(ByteVector32.One, 0, CMD_FAIL_HTLC(0, Right(PaymentTimeout), commit = true)))
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    val add2 = UpdateAddHtlc(ByteVector32.One, 2, 300 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add2, Onion.createMultiPartPayload(add2.amountMsat, 1000 msat, add2.cltvExpiry, pr.paymentSecret.get)))
    val add3 = UpdateAddHtlc(ByteVector32.Zeroes, 5, 700 msat, pr.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket)
    f.sender.send(handler, IncomingPacket.FinalPacket(add3, Onion.createMultiPartPayload(add3.amountMsat, 1000 msat, add3.cltvExpiry, pr.paymentSecret.get)))

    val cmd1 = f.commandBuffer.expectMsgType[CommandBuffer.CommandSend]
    assert(cmd1.channelId === add2.channelId)
    val fulfill1 = cmd1.cmd.asInstanceOf[CMD_FULFILL_HTLC]
    assert(fulfill1.id === 2)
    assert(Crypto.sha256(fulfill1.r) === pr.paymentHash)
    f.commandBuffer.expectMsg(CommandBuffer.CommandSend(add3.channelId, 5, CMD_FULFILL_HTLC(5, fulfill1.r, commit = true)))

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

}
