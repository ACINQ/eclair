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

import akka.actor.typed.scaladsl.adapter.actorRefAdapter
import akka.testkit.{TestActorRef, TestProbe}
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features.{KeySend, _}
import fr.acinq.eclair.TestConstants.Alice
import fr.acinq.eclair.channel.{CMD_FAIL_HTLC, CMD_FULFILL_HTLC, FailureAttributionData, FulfillAttributionData, Register}
import fr.acinq.eclair.db.{IncomingBlindedPayment, IncomingPaymentStatus, IncomingStandardPayment, PaymentType}
import fr.acinq.eclair.payment.Bolt11Invoice.ExtraHop
import fr.acinq.eclair.payment.PaymentReceived.PartialPayment
import fr.acinq.eclair.payment.offer.OfferManager
import fr.acinq.eclair.payment.receive.MultiPartHandler._
import fr.acinq.eclair.payment.receive.MultiPartPaymentFSM.HtlcPart
import fr.acinq.eclair.payment.receive.{MultiPartPaymentFSM, PaymentHandler}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees
import fr.acinq.eclair.reputation.Reputation
import fr.acinq.eclair.router.BlindedRouteCreation.aggregatePaymentInfo
import fr.acinq.eclair.router.Router
import fr.acinq.eclair.router.Router.ChannelHop
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer, PaymentInfo}
import fr.acinq.eclair.wire.protocol.OnionPaymentPayloadTlv._
import fr.acinq.eclair.wire.protocol.PaymentOnion.FinalPayload
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataTlv.{PathId, PaymentConstraints}
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, EncodedNodeId, Feature, Features, MilliSatoshi, MilliSatoshiLong, NodeParams, ShortChannelId, TestConstants, TestKitBaseClass, TimestampMilli, TimestampMilliLong, randomBytes, randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.collection.immutable.Queue
import scala.concurrent.duration._

/**
 * Created by PM on 24/03/2017.
 */

class MultiPartHandlerSpec extends TestKitBaseClass with FixtureAnyFunSuiteLike {

  val featuresWithoutMpp = Features[Feature](
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory,
  )

  val featuresWithMpp = Features[Feature](
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory,
    BasicMultiPartPayment -> Optional
  )

  val featuresWithKeySend = Features[Feature](
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory,
    KeySend -> Optional
  )

  val featuresWithRouteBlinding = Features[Feature](
    VariableLengthOnion -> Mandatory,
    PaymentSecret -> Mandatory,
    BasicMultiPartPayment -> Optional,
    RouteBlinding -> Optional,
  )

  case class FixtureParam(nodeParams: NodeParams, defaultExpiry: CltvExpiry, register: TestProbe, eventListener: TestProbe, sender: TestProbe, offerManager: TestProbe) {
    lazy val handlerWithoutMpp = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams.copy(features = featuresWithoutMpp), register.ref, offerManager.ref))
    lazy val handlerWithMpp = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams.copy(features = featuresWithMpp), register.ref, offerManager.ref))
    lazy val handlerWithKeySend = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams.copy(features = featuresWithKeySend), register.ref, offerManager.ref))
    lazy val handlerWithRouteBlinding = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams.copy(features = featuresWithRouteBlinding), register.ref, offerManager.ref))

    def createEmptyReceivingRoute(pathId: ByteVector): Seq[ReceivingRoute] = Seq(ReceivingRoute(Nil, pathId, CltvExpiryDelta(144), PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, 1_000_000_000 msat, ByteVector.empty)))
  }

  override def withFixture(test: OneArgTest): Outcome = {
    within(30 seconds) {
      val nodeParams = Alice.nodeParams
      val register = TestProbe()
      val eventListener = TestProbe()
      system.eventStream.subscribe(eventListener.ref, classOf[PaymentEvent])
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, nodeParams.channelConf.minFinalExpiryDelta.toCltvExpiry(nodeParams.currentBlockHeight), register, eventListener, TestProbe(), TestProbe())))
    }
  }

  def createBlindedPacket(amount: MilliSatoshi, paymentHash: ByteVector32, expiry: CltvExpiry, finalExpiry: CltvExpiry, pathId: ByteVector): IncomingPaymentPacket.FinalPacket = {
    val add = UpdateAddHtlc(ByteVector32.One, 0, amount, paymentHash, expiry, TestConstants.emptyOnionPacket, Some(randomKey().publicKey), Reputation.maxEndorsement, None)
    val payload = FinalPayload.Blinded(TlvStream(AmountToForward(amount), TotalAmount(amount), OutgoingCltv(finalExpiry), EncryptedRecipientData(hex"deadbeef")), TlvStream(PathId(pathId), PaymentConstraints(CltvExpiry(500_000), 1 msat)))
    IncomingPaymentPacket.FinalPacket(add, payload, TimestampMilli.now())
  }

  test("PaymentHandler should reply with a fulfill/fail, emit a PaymentReceived and add payment in DB") { f =>
    import f._

    val amountMsat = 42000 msat

    {
      sender.send(handlerWithoutMpp, ReceiveStandardPayment(sender.ref, Some(amountMsat), Left("1 coffee")))
      val invoice = sender.expectMsgType[Bolt11Invoice]
      val incoming = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
      assert(incoming.isDefined)
      assert(incoming.get.status == IncomingPaymentStatus.Pending)
      assert(!incoming.get.invoice.isExpired())
      assert(Crypto.sha256(incoming.get.paymentPreimage) == invoice.paymentHash)

      val add = UpdateAddHtlc(ByteVector32.One, 1, amountMsat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
      sender.send(handlerWithoutMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, add.amountMsat, add.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), TimestampMilli.now()))
      assert(register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]].message.id == add.id)

      val paymentReceived = eventListener.expectMsgType[PaymentReceived]
      assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0 unixms))) == PaymentReceived(add.paymentHash, PartialPayment(amountMsat, add.channelId, timestamp = 0 unixms) :: Nil))
      val received = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
      assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
      assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0 unixms) == IncomingPaymentStatus.Received(amountMsat, 0 unixms))

      sender.expectNoMessage(50 millis)
    }
    {
      sender.send(handlerWithoutMpp, ReceiveStandardPayment(sender.ref, Some(50_000 msat), Left("1 coffee with extra fees and expiry")))
      val invoice = sender.expectMsgType[Bolt11Invoice]

      val add = UpdateAddHtlc(ByteVector32.One, 1, 75_000 msat, invoice.paymentHash, defaultExpiry + CltvExpiryDelta(12), TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
      sender.send(handlerWithoutMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(70_000 msat, 70_000 msat, defaultExpiry, invoice.paymentSecret, invoice.paymentMetadata), TimestampMilli.now()))
      assert(register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]].message.id == add.id)

      val paymentReceived = eventListener.expectMsgType[PaymentReceived]
      assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0 unixms))) == PaymentReceived(add.paymentHash, PartialPayment(add.amountMsat, add.channelId, timestamp = 0 unixms) :: Nil))
      val received = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
      assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
      assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0 unixms) == IncomingPaymentStatus.Received(add.amountMsat, 0 unixms))

      sender.expectNoMessage(50 millis)
    }
    {
      sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(amountMsat), Left("another coffee with multi-part")))
      val invoice = sender.expectMsgType[Bolt11Invoice]
      assert(invoice.features.hasFeature(BasicMultiPartPayment))
      assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)

      val add = UpdateAddHtlc(ByteVector32.One, 2, amountMsat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
      sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, add.amountMsat, add.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), TimestampMilli.now()))
      assert(register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]].message.id == add.id)

      val paymentReceived = eventListener.expectMsgType[PaymentReceived]
      assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0 unixms))) == PaymentReceived(add.paymentHash, PartialPayment(amountMsat, add.channelId, timestamp = 0 unixms) :: Nil))
      val received = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
      assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
      assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0 unixms) == IncomingPaymentStatus.Received(amountMsat, 0 unixms))

      sender.expectNoMessage(50 millis)
    }
    {
      val privKey = randomKey()
      val offer = Offer(Some(amountMsat), Some("a blinded coffee please"), privKey.publicKey, Features.empty, Block.RegtestGenesisBlock.hash)
      val invoiceReq = InvoiceRequest(offer, amountMsat, 1, featuresWithRouteBlinding.bolt12Features(), randomKey(), Block.RegtestGenesisBlock.hash)
      val preimage = randomBytes32()
      val pathId = randomBytes32()
      val router = TestProbe()
      sender.send(handlerWithRouteBlinding, ReceiveOfferPayment(sender.ref, privKey, invoiceReq, createEmptyReceivingRoute(pathId), preimage))
      router.expectNoMessage(50 millis)
      val invoice = sender.expectMsgType[Bolt12Invoice]
      // Offer invoices shouldn't be stored in the DB until we receive a payment for it.
      assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).isEmpty)

      val finalPacket = createBlindedPacket(amountMsat, invoice.paymentHash, defaultExpiry, CltvExpiry(nodeParams.currentBlockHeight), pathId)
      sender.send(handlerWithRouteBlinding, finalPacket)
      val receivePayment = offerManager.expectMsgType[OfferManager.ReceivePayment]
      assert(receivePayment.paymentHash == invoice.paymentHash)
      assert(receivePayment.payload.pathId == pathId.bytes)
      val payment = IncomingBlindedPayment(MinimalBolt12Invoice(invoice.records), preimage, PaymentType.Blinded, TimestampMilli.now(), IncomingPaymentStatus.Pending)
      receivePayment.replyTo ! GetIncomingPaymentActor.ProcessPayment(payment, 0 msat)
      assert(register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]].message.id == finalPacket.add.id)

      val paymentReceived = eventListener.expectMsgType[PaymentReceived]
      assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0 unixms))) == PaymentReceived(finalPacket.add.paymentHash, PartialPayment(amountMsat, finalPacket.add.channelId, timestamp = 0 unixms) :: Nil))
      val received = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
      assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
      assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0 unixms) == IncomingPaymentStatus.Received(amountMsat, 0 unixms))

      sender.expectNoMessage(50 millis)
    }
    {
      sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(amountMsat), Left("bad expiry")))
      val invoice = sender.expectMsgType[Bolt11Invoice]
      assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)

      val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, invoice.paymentHash, CltvExpiryDelta(3).toCltvExpiry(nodeParams.currentBlockHeight), TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
      sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, add.amountMsat, add.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), TimestampMilli.now()))
      val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
      assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(amountMsat, nodeParams.currentBlockHeight)))
      assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)

      eventListener.expectNoMessage(100 milliseconds)
      sender.expectNoMessage(50 millis)
    }
  }

  test("Invoice generation should succeed when the amount is not set") { f =>
    import f._

    sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, None, Left("This is a donation PR")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.amount_opt.isEmpty && invoice.nodeId.toString == Alice.nodeParams.nodeId.toString)
  }

  test("Invoice generation should handle custom expiries or use the default otherwise") { f =>
    import f._

    sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(42000 msat), Left("1 coffee")))
    val pr1 = sender.expectMsgType[Bolt11Invoice]
    assert(pr1.minFinalCltvExpiryDelta == nodeParams.channelConf.minFinalExpiryDelta)
    assert(pr1.relativeExpiry == Alice.nodeParams.invoiceExpiry)

    sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(42000 msat), Left("1 coffee with custom expiry"), expirySeconds_opt = Some(60)))
    val pr2 = sender.expectMsgType[Bolt11Invoice]
    assert(pr2.minFinalCltvExpiryDelta == nodeParams.channelConf.minFinalExpiryDelta)
    assert(pr2.relativeExpiry == 60.seconds)
  }

  test("Invoice generation with trampoline support") { () =>
    val sender = TestProbe()

    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = false, features = featuresWithoutMpp), TestProbe().ref, TestProbe().ref))
      sender.send(handler, ReceiveStandardPayment(sender.ref, Some(42 msat), Left("1 coffee")))
      val invoice = sender.expectMsgType[Bolt11Invoice]
      assert(!invoice.features.hasFeature(BasicMultiPartPayment))
      assert(!invoice.features.hasFeature(TrampolinePaymentPrototype))
    }
    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = false, features = featuresWithMpp), TestProbe().ref, TestProbe().ref))
      sender.send(handler, ReceiveStandardPayment(sender.ref, Some(42 msat), Left("1 coffee")))
      val invoice = sender.expectMsgType[Bolt11Invoice]
      assert(invoice.features.hasFeature(BasicMultiPartPayment))
      assert(!invoice.features.hasFeature(TrampolinePaymentPrototype))
    }
    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = true, features = featuresWithoutMpp), TestProbe().ref, TestProbe().ref))
      sender.send(handler, ReceiveStandardPayment(sender.ref, Some(42 msat), Left("1 coffee")))
      val invoice = sender.expectMsgType[Bolt11Invoice]
      assert(!invoice.features.hasFeature(BasicMultiPartPayment))
      assert(invoice.features.hasFeature(TrampolinePaymentPrototype))
    }
    {
      val handler = TestActorRef[PaymentHandler](PaymentHandler.props(Alice.nodeParams.copy(enableTrampolinePayment = true, features = featuresWithMpp), TestProbe().ref, TestProbe().ref))
      sender.send(handler, ReceiveStandardPayment(sender.ref, Some(42 msat), Left("1 coffee")))
      val invoice = sender.expectMsgType[Bolt11Invoice]
      assert(invoice.features.hasFeature(BasicMultiPartPayment))
      assert(invoice.features.hasFeature(TrampolinePaymentPrototype))
    }
  }

  test("Invoice generation with route blinding support") { f =>
    import f._

    val privKey = randomKey()
    val amount = 25_000 msat
    val offer = Offer(Some(amount), Some("a blinded coffee please"), privKey.publicKey, Features.empty, Block.RegtestGenesisBlock.hash)
    val invoiceReq = InvoiceRequest(offer, amount, 1, featuresWithRouteBlinding.bolt12Features(), randomKey(), Block.RegtestGenesisBlock.hash)
    val (a, b, c, d) = (randomKey().publicKey, randomKey().publicKey, randomKey().publicKey, nodeParams.nodeId)
    val hop_ab = ChannelHop(ShortChannelId(1), a, b, Router.HopRelayParams.FromHint(Invoice.ExtraEdge(a, b, ShortChannelId(1), 1000 msat, 0, CltvExpiryDelta(100), 1 msat, None)))
    val hop_bd = ChannelHop(ShortChannelId(2), b, d, Router.HopRelayParams.FromHint(Invoice.ExtraEdge(b, d, ShortChannelId(2), 800 msat, 0, CltvExpiryDelta(50), 1 msat, None)))
    val hop_cd = ChannelHop(ShortChannelId(3), c, d, Router.HopRelayParams.FromHint(Invoice.ExtraEdge(c, d, ShortChannelId(3), 0 msat, 0, CltvExpiryDelta(75), 1 msat, None)))
    val hops1 = Seq(hop_ab, hop_bd, ChannelHop.dummy(d, 150 msat, 0, CltvExpiryDelta(25)))
    val hops2 = Seq(hop_cd, ChannelHop.dummy(d, 250 msat, 0, CltvExpiryDelta(10)), ChannelHop.dummy(d, 150 msat, 0, CltvExpiryDelta(80)))
    val receivingRoutes = Seq(
      ReceivingRoute(hops1, randomBytes32(), CltvExpiryDelta(100), aggregatePaymentInfo(amount, hops1, nodeParams.channelConf.minFinalExpiryDelta)),
      ReceivingRoute(hops2, randomBytes32(), CltvExpiryDelta(50), aggregatePaymentInfo(amount, hops2, nodeParams.channelConf.minFinalExpiryDelta)),
      ReceivingRoute(Nil, randomBytes32(), CltvExpiryDelta(250), PaymentInfo(0 msat, 0, nodeParams.channelConf.minFinalExpiryDelta, 0 msat, amount, ByteVector.empty)),
    )
    sender.send(handlerWithRouteBlinding, ReceiveOfferPayment(sender.ref, privKey, invoiceReq, receivingRoutes, randomBytes32()))
    val invoice = sender.expectMsgType[Bolt12Invoice]
    assert(invoice.amount == amount)
    assert(invoice.nodeId == privKey.publicKey)
    assert(invoice.blindedPaths.nonEmpty)
    assert(invoice.description.contains("a blinded coffee please"))
    assert(invoice.invoiceRequest.offer == offer)
    assert(invoice.blindedPaths.length == 3)
    assert(invoice.blindedPaths(0).route.blindedNodeIds.length == 4)
    assert(invoice.blindedPaths(0).route.firstNodeId == EncodedNodeId(a))
    assert(invoice.blindedPaths(0).paymentInfo == PaymentInfo(1950 msat, 0, CltvExpiryDelta(193), 1 msat, 25_000 msat, ByteVector.empty))
    assert(invoice.blindedPaths(1).route.blindedNodeIds.length == 4)
    assert(invoice.blindedPaths(1).route.firstNodeId == EncodedNodeId(c))
    assert(invoice.blindedPaths(1).paymentInfo == PaymentInfo(400 msat, 0, CltvExpiryDelta(183), 1 msat, 25_000 msat, ByteVector.empty))
    assert(invoice.blindedPaths(2).route.blindedNodeIds.length == 1)
    assert(invoice.blindedPaths(2).route.firstNodeId == EncodedNodeId(d))
    assert(invoice.blindedPaths(2).paymentInfo == PaymentInfo(0 msat, 0, CltvExpiryDelta(18), 0 msat, 25_000 msat, ByteVector.empty))
    // Offer invoices shouldn't be stored in the DB until we receive a payment for it.
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).isEmpty)
    // Check that all non-final encrypted payloads for blinded routes have the same length.
    assert(invoice.blindedPaths.flatMap(_.route.encryptedPayloads.dropRight(1)).map(_.length).toSet.size == 1)
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

    sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(42000 msat), Left("1 coffee with additional routing info"), extraHops = List(route_x_z, route_x_t)))
    assert(sender.expectMsgType[Bolt11Invoice].routingInfo == Seq(route_x_z, route_x_t))

    sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(42000 msat), Left("1 coffee without routing info")))
    assert(sender.expectMsgType[Bolt11Invoice].routingInfo == Nil)
  }

  test("PaymentHandler should reject incoming payments if the invoice is expired") { f =>
    import f._

    sender.send(handlerWithoutMpp, ReceiveStandardPayment(sender.ref, Some(1000 msat), Left("some desc"), expirySeconds_opt = Some(0)))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(!invoice.features.hasFeature(BasicMultiPartPayment))
    assert(invoice.isExpired())

    val add = UpdateAddHtlc(ByteVector32.One, 0, 1000 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithoutMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, add.amountMsat, add.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), TimestampMilli.now()))
    register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]]
    val Some(incoming) = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(incoming.invoice.isExpired() && incoming.status == IncomingPaymentStatus.Expired)
  }

  test("PaymentHandler should reject incoming multi-part payment if the invoice is expired") { f =>
    import f._

    sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(1000 msat), Left("multi-part expired"), expirySeconds_opt = Some(0)))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(BasicMultiPartPayment))
    assert(invoice.isExpired())

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, 1000 msat, add.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), TimestampMilli.now()))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    val Some(incoming) = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(incoming.invoice.isExpired() && incoming.status == IncomingPaymentStatus.Expired)
  }

  test("PaymentHandler should reject incoming multi-part payment if the invoice does not allow it") { f =>
    import f._

    sender.send(handlerWithoutMpp, ReceiveStandardPayment(sender.ref, Some(1000 msat), Left("no multi-part support")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(!invoice.features.hasFeature(BasicMultiPartPayment))

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithoutMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, 1000 msat, add.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), TimestampMilli.now()))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an invalid expiry") { f =>
    import f._

    sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(1000 msat), Left("multi-part invalid expiry")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(BasicMultiPartPayment))

    val lowCltvExpiry = nodeParams.channelConf.fulfillSafetyBeforeTimeout.toCltvExpiry(nodeParams.currentBlockHeight)
    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, lowCltvExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, 1000 msat, add.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), TimestampMilli.now()))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an unknown payment hash") { f =>
    import f._

    sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(1000 msat), Left("multi-part unknown payment hash")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(BasicMultiPartPayment))

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash.reverse, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, 1000 msat, add.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), TimestampMilli.now()))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with a total amount too low") { f =>
    import f._

    sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(1000 msat), Left("multi-part total amount too low")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(BasicMultiPartPayment))

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, 999 msat, add.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), TimestampMilli.now()))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(999 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with a total amount too high") { f =>
    import f._

    sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(1000 msat), Left("multi-part total amount too low")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(BasicMultiPartPayment))

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, 2001 msat, add.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), TimestampMilli.now()))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(2001 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming multi-part payment with an invalid payment secret") { f =>
    import f._

    sender.send(handlerWithMpp, ReceiveStandardPayment(sender.ref, Some(1000 msat), Left("multi-part invalid payment secret")))
    val invoice = sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(BasicMultiPartPayment))

    // Invalid payment secret.
    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, 1000 msat, add.cltvExpiry, invoice.paymentSecret.reverse, invoice.paymentMetadata), TimestampMilli.now()))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming blinded payment for Bolt 11 invoice") { f =>
    import f._

    sender.send(handlerWithRouteBlinding, ReceiveStandardPayment(sender.ref, Some(1000 msat), Left("non blinded payment")))
    val invoice = sender.expectMsgType[Bolt11Invoice]

    val packet = createBlindedPacket(1000 msat, invoice.paymentHash, defaultExpiry, CltvExpiry(nodeParams.currentBlockHeight), randomBytes32())
    sender.send(handlerWithRouteBlinding, packet)
    val receivePayment = offerManager.expectMsgType[OfferManager.ReceivePayment]
    assert(receivePayment.paymentHash == invoice.paymentHash)
    receivePayment.replyTo ! GetIncomingPaymentActor.RejectPayment("non blinded payment")
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status == IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should reject incoming standard payment for Bolt 12 invoice") { f =>
    import f._

    val nodeKey = randomKey()
    val offer = Offer(None, Some("a blinded coffee please"), nodeKey.publicKey, Features.empty, Block.RegtestGenesisBlock.hash)
    val invoiceReq = InvoiceRequest(offer, 5000 msat, 1, featuresWithRouteBlinding.bolt12Features(), randomKey(), Block.RegtestGenesisBlock.hash)
    sender.send(handlerWithRouteBlinding, ReceiveOfferPayment(sender.ref, nodeKey, invoiceReq, createEmptyReceivingRoute(randomBytes32()), randomBytes32()))
    val invoice = sender.expectMsgType[Bolt12Invoice]

    val add = UpdateAddHtlc(ByteVector32.One, 0, 5000 msat, invoice.paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, add.amountMsat, add.cltvExpiry, randomBytes32(), None), TimestampMilli.now()))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(5000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).isEmpty)
  }

  test("PaymentHandler should accept incoming blinded payment") { f =>
    import f._

    val nodeKey = randomKey()
    val offer = Offer(None, Some("a blinded coffee please"), nodeKey.publicKey, Features.empty, Block.RegtestGenesisBlock.hash)
    val invoiceReq = InvoiceRequest(offer, 5000 msat, 1, featuresWithRouteBlinding.bolt12Features(), randomKey(), Block.RegtestGenesisBlock.hash)
    val preimage = randomBytes32()
    val pathId = randomBytes32()
    sender.send(handlerWithRouteBlinding, ReceiveOfferPayment(sender.ref, nodeKey, invoiceReq, createEmptyReceivingRoute(pathId), preimage))
    val invoice = sender.expectMsgType[Bolt12Invoice]
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).isEmpty)

    val packet = createBlindedPacket(5000 msat, invoice.paymentHash, defaultExpiry, CltvExpiry(nodeParams.currentBlockHeight), pathId)
    sender.send(handlerWithRouteBlinding, packet)
    val receivePayment = offerManager.expectMsgType[OfferManager.ReceivePayment]
    assert(receivePayment.paymentHash == invoice.paymentHash)
    assert(receivePayment.payload.pathId == pathId.bytes)
    val payment = IncomingBlindedPayment(MinimalBolt12Invoice(invoice.records), preimage, PaymentType.Blinded, TimestampMilli.now(), IncomingPaymentStatus.Pending)
    receivePayment.replyTo ! GetIncomingPaymentActor.ProcessPayment(payment, 0 msat)
    register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).get.status.isInstanceOf[IncomingPaymentStatus.Received])
  }

  test("PaymentHandler should reject incoming blinded payment if the offer manager doesn't recognize it") { f =>
    import f._

    val nodeKey = randomKey()
    val preimage = randomBytes32()
    val pathId = randomBytes(128)
    val offer = Offer(None, Some("a blinded coffee please"), nodeKey.publicKey, Features.empty, Block.RegtestGenesisBlock.hash)
    val invoiceReq = InvoiceRequest(offer, 5000 msat, 1, featuresWithRouteBlinding.bolt12Features(), randomKey(), Block.RegtestGenesisBlock.hash)
    sender.send(handlerWithRouteBlinding, ReceiveOfferPayment(sender.ref, nodeKey, invoiceReq, createEmptyReceivingRoute(pathId), preimage))
    val invoice = sender.expectMsgType[Bolt12Invoice]

    val packet = createBlindedPacket(5000 msat, invoice.paymentHash, defaultExpiry, CltvExpiry(nodeParams.currentBlockHeight), pathId)
    sender.send(handlerWithRouteBlinding, packet)
    val payment = offerManager.expectMsgType[OfferManager.ReceivePayment]
    assert(payment.payload.pathId == pathId)
    payment.replyTo ! GetIncomingPaymentActor.RejectPayment("internal error")
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(5000 msat, nodeParams.currentBlockHeight)))
  }

  test("PaymentHandler should reject incoming blinded payment with unexpected expiry") { f =>
    import f._

    val nodeKey = randomKey()
    val offer = Offer(None, Some("a blinded coffee please"), nodeKey.publicKey, Features.empty, Block.RegtestGenesisBlock.hash)
    val invoiceReq = InvoiceRequest(offer, 5000 msat, 1, featuresWithRouteBlinding.bolt12Features(), randomKey(), Block.RegtestGenesisBlock.hash)
    val preimage = randomBytes32()
    val pathId = randomBytes32()
    sender.send(handlerWithRouteBlinding, ReceiveOfferPayment(sender.ref, nodeKey, invoiceReq, createEmptyReceivingRoute(pathId), preimage))
    val invoice = sender.expectMsgType[Bolt12Invoice]

    // We test the case where the HTLC's cltv_expiry is lower than expected and doesn't meet the min_final_expiry_delta.
    val packet = createBlindedPacket(5000 msat, invoice.paymentHash, defaultExpiry - CltvExpiryDelta(1), defaultExpiry, pathId)
    sender.send(handlerWithRouteBlinding, packet)
    val receivePayment = offerManager.expectMsgType[OfferManager.ReceivePayment]
    assert(receivePayment.paymentHash == invoice.paymentHash)
    assert(receivePayment.payload.pathId == pathId.bytes)
    val payment = IncomingBlindedPayment(MinimalBolt12Invoice(invoice.records), preimage, PaymentType.Blinded, TimestampMilli.now(), IncomingPaymentStatus.Pending)
    receivePayment.replyTo ! GetIncomingPaymentActor.ProcessPayment(payment, 0 msat)
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(5000 msat, nodeParams.currentBlockHeight)))
    assert(nodeParams.db.payments.getIncomingPayment(invoice.paymentHash).isEmpty)
  }

  test("PaymentHandler should handle multi-part payment timeout") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 200 millis, features = featuresWithMpp)
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.register.ref, f.offerManager.ref))

    // Partial payment missing additional parts.
    f.sender.send(handler, ReceiveStandardPayment(f.sender.ref, Some(1000 msat), Left("1 slow coffee")))
    val pr1 = f.sender.expectMsgType[Bolt11Invoice]
    val receivedAt1 = TimestampMilli.now()
    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, pr1.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add1, FinalPayload.Standard.createPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, pr1.paymentSecret, pr1.paymentMetadata), receivedAt1))

    // Partial payment exceeding the invoice amount, but incomplete because it promises to overpay.
    f.sender.send(handler, ReceiveStandardPayment(f.sender.ref, Some(1500 msat), Left("1 slow latte")))
    val pr2 = f.sender.expectMsgType[Bolt11Invoice]
    val receivedAt2 = receivedAt1 + 1.millis
    val add2 = UpdateAddHtlc(ByteVector32.One, 1, 1600 msat, pr2.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add2, FinalPayload.Standard.createPayload(add2.amountMsat, 2000 msat, add2.cltvExpiry, pr2.paymentSecret, pr2.paymentMetadata), receivedAt2))

    awaitCond {
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.nonEmpty
    }

    val commands = f.register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]] :: f.register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]] :: Nil
    assert(commands.toSet == Set(
      Register.Forward(null, ByteVector32.One, CMD_FAIL_HTLC(0, FailureReason.LocalFailure(PaymentTimeout()), Some(FailureAttributionData(receivedAt1, None)), commit = true)),
      Register.Forward(null, ByteVector32.One, CMD_FAIL_HTLC(1, FailureReason.LocalFailure(PaymentTimeout()), Some(FailureAttributionData(receivedAt2, None)), commit = true))
    ))
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    // Extraneous HTLCs should be failed.
    val receivedAt3 = receivedAt1 + 2.millis
    f.sender.send(handler, MultiPartPaymentFSM.ExtraPaymentReceived(pr1.paymentHash, HtlcPart(1000 msat, UpdateAddHtlc(ByteVector32.One, 42, 200 msat, pr1.paymentHash, add1.cltvExpiry, add1.onionRoutingPacket, None, Reputation.maxEndorsement, None), receivedAt3), Some(PaymentTimeout())))
    f.register.expectMsg(Register.Forward(null, ByteVector32.One, CMD_FAIL_HTLC(42, FailureReason.LocalFailure(PaymentTimeout()), Some(FailureAttributionData(receivedAt3, None)), commit = true)))

    // The payment should still be pending in DB.
    val Some(incomingPayment) = nodeParams.db.payments.getIncomingPayment(pr1.paymentHash)
    assert(incomingPayment.status == IncomingPaymentStatus.Pending)
  }

  test("PaymentHandler should handle multi-part payment success") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 500 millis, features = featuresWithMpp)
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.register.ref, f.offerManager.ref))

    val preimage = randomBytes32()
    f.sender.send(handler, ReceiveStandardPayment(f.sender.ref, Some(1000 msat), Left("1 fast coffee"), paymentPreimage_opt = Some(preimage)))
    val invoice = f.sender.expectMsgType[Bolt11Invoice]

    val receivedAt1 = TimestampMilli.now()
    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add1, FinalPayload.Standard.createPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), receivedAt1))
    // Invalid payment secret -> should be rejected.
    val receivedAt2 = receivedAt1 + 1.millis
    val add2 = UpdateAddHtlc(ByteVector32.Zeroes, 42, 200 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add2, FinalPayload.Standard.createPayload(add2.amountMsat, 1000 msat, add2.cltvExpiry, invoice.paymentSecret.reverse, invoice.paymentMetadata), receivedAt2))
    val receivedAt3 = receivedAt1 + 2.millis
    val add3 = add2.copy(id = 43)
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add3, FinalPayload.Standard.createPayload(add3.amountMsat, 1000 msat, add3.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), receivedAt3))

    f.register.expectMsgAllOf(
      Register.Forward(null, add2.channelId, CMD_FAIL_HTLC(add2.id, FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)), Some(FailureAttributionData(receivedAt2, None)), commit = true)),
      Register.Forward(null, add1.channelId, CMD_FULFILL_HTLC(add1.id, preimage, Some(FulfillAttributionData(receivedAt1, None, None)), commit = true)),
      Register.Forward(null, add3.channelId, CMD_FULFILL_HTLC(add3.id, preimage, Some(FulfillAttributionData(receivedAt3, None, None)), commit = true))
    )

    val paymentReceived = f.eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.parts.map(_.copy(timestamp = 0 unixms)).toSet == Set(PartialPayment(800 msat, ByteVector32.One, 0 unixms), PartialPayment(200 msat, ByteVector32.Zeroes, 0 unixms)))
    val received = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount == 1000.msat)
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    // Extraneous HTLCs should be fulfilled.
    val receivedAt4 = receivedAt1 + 3.millis
    f.sender.send(handler, MultiPartPaymentFSM.ExtraPaymentReceived(invoice.paymentHash, HtlcPart(1000 msat, UpdateAddHtlc(ByteVector32.One, 44, 200 msat, invoice.paymentHash, add1.cltvExpiry, add1.onionRoutingPacket, None, Reputation.maxEndorsement, None), receivedAt4), None))
    f.register.expectMsg(Register.Forward(null, ByteVector32.One, CMD_FULFILL_HTLC(44, preimage, Some(FulfillAttributionData(receivedAt4, None, None)), commit = true)))
    assert(f.eventListener.expectMsgType[PaymentReceived].amount == 200.msat)
    val received2 = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(received2.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount == 1200.msat)

    f.sender.send(handler, GetPendingPayments)
    f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
  }

  test("PaymentHandler should handle multi-part over-payment") { f =>
    val nodeParams = Alice.nodeParams.copy(features = featuresWithMpp)
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.register.ref, f.offerManager.ref))

    val preimage = randomBytes32()
    f.sender.send(handler, ReceiveStandardPayment(f.sender.ref, Some(1000 msat), Left("1 coffee with tip please"), paymentPreimage_opt = Some(preimage)))
    val invoice = f.sender.expectMsgType[Bolt11Invoice]

    val add1 = UpdateAddHtlc(randomBytes32(), 0, 1100 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    val receivedAt1 = TimestampMilli.now()
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add1, FinalPayload.Standard.createPayload(add1.amountMsat, 1500 msat, add1.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), receivedAt1))
    val add2 = UpdateAddHtlc(randomBytes32(), 1, 500 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    val receivedAt2 = TimestampMilli.now() + 5.millis
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add2, FinalPayload.Standard.createPayload(add2.amountMsat, 1500 msat, add2.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), receivedAt2))

    f.register.expectMsgAllOf(
      Register.Forward(null, add1.channelId, CMD_FULFILL_HTLC(add1.id, preimage, Some(FulfillAttributionData(receivedAt1, None, None)), commit = true)),
      Register.Forward(null, add2.channelId, CMD_FULFILL_HTLC(add2.id, preimage, Some(FulfillAttributionData(receivedAt2, None, None)), commit = true))
    )

    val paymentReceived = f.eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.parts.map(_.copy(timestamp = 0 unixms)).toSet == Set(PartialPayment(1100 msat, add1.channelId, 0 unixms), PartialPayment(500 msat, add2.channelId, 0 unixms)))
    val received = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount == 1600.msat)
  }

  test("PaymentHandler should handle multi-part payment timeout then success") { f =>
    val nodeParams = Alice.nodeParams.copy(multiPartPaymentExpiry = 250 millis, features = featuresWithMpp)
    val handler = TestActorRef[PaymentHandler](PaymentHandler.props(nodeParams, f.register.ref, f.offerManager.ref))

    val preimage = randomBytes32()
    f.sender.send(handler, ReceiveStandardPayment(f.sender.ref, Some(1000 msat), Left("1 coffee, no sugar"), paymentPreimage_opt = Some(preimage)))
    val invoice = f.sender.expectMsgType[Bolt11Invoice]
    assert(invoice.features.hasFeature(BasicMultiPartPayment))
    assert(invoice.paymentHash == Crypto.sha256(preimage))

    val add1 = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    val receivedAt1 = TimestampMilli.now()
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add1, FinalPayload.Standard.createPayload(add1.amountMsat, 1000 msat, add1.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), receivedAt1))
    f.register.expectMsg(Register.Forward(null, ByteVector32.One, CMD_FAIL_HTLC(0, FailureReason.LocalFailure(PaymentTimeout()), Some(FailureAttributionData(receivedAt1, None)), commit = true)))
    awaitCond({
      f.sender.send(handler, GetPendingPayments)
      f.sender.expectMsgType[PendingPayments].paymentHashes.isEmpty
    })

    val add2 = UpdateAddHtlc(ByteVector32.One, 2, 300 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    val receivedAt2 = TimestampMilli.now() + 10.millis
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add2, FinalPayload.Standard.createPayload(add2.amountMsat, 1000 msat, add2.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), receivedAt2))
    val add3 = UpdateAddHtlc(ByteVector32.Zeroes, 5, 700 msat, invoice.paymentHash, f.defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    val receivedAt3 = TimestampMilli.now() + 50.millis
    f.sender.send(handler, IncomingPaymentPacket.FinalPacket(add3, FinalPayload.Standard.createPayload(add3.amountMsat, 1000 msat, add3.cltvExpiry, invoice.paymentSecret, invoice.paymentMetadata), receivedAt3))

    // the fulfill are not necessarily in the same order as the commands
    f.register.expectMsgAllOf(
      Register.Forward(null, add2.channelId, CMD_FULFILL_HTLC(2, preimage, Some(FulfillAttributionData(receivedAt2, None, None)), commit = true)),
      Register.Forward(null, add3.channelId, CMD_FULFILL_HTLC(5, preimage, Some(FulfillAttributionData(receivedAt3, None, None)), commit = true))
    )

    val paymentReceived = f.eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.paymentHash == invoice.paymentHash)
    assert(paymentReceived.parts.map(_.copy(timestamp = 0 unixms)).toSet == Set(PartialPayment(300 msat, ByteVector32.One, 0 unixms), PartialPayment(700 msat, ByteVector32.Zeroes, 0 unixms)))
    val received = nodeParams.db.payments.getIncomingPayment(invoice.paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].amount == 1000.msat)
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
    val Right(payload) = FinalPayload.Standard.validate(TlvStream(OnionPaymentPayloadTlv.AmountToForward(amountMsat), OnionPaymentPayloadTlv.OutgoingCltv(defaultExpiry), OnionPaymentPayloadTlv.PaymentData(paymentSecret, 0 msat), OnionPaymentPayloadTlv.KeySend(paymentPreimage)))

    assert(nodeParams.db.payments.getIncomingPayment(paymentHash).isEmpty)

    val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithKeySend, IncomingPaymentPacket.FinalPacket(add, payload, TimestampMilli.now()))
    register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]

    val paymentReceived = eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0 unixms))) == PaymentReceived(add.paymentHash, PartialPayment(amountMsat, add.channelId, timestamp = 0 unixms) :: Nil))
    val received = nodeParams.db.payments.getIncomingPayment(paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0 unixms) == IncomingPaymentStatus.Received(amountMsat, 0 unixms))
  }

  test("PaymentHandler should handle single-part KeySend payment without payment secret") { f =>
    import f._

    val amountMsat = 42000 msat
    val paymentPreimage = randomBytes32()
    val paymentHash = Crypto.sha256(paymentPreimage)
    val payload = FinalPayload.Standard(TlvStream(OnionPaymentPayloadTlv.AmountToForward(amountMsat), OnionPaymentPayloadTlv.OutgoingCltv(defaultExpiry), OnionPaymentPayloadTlv.KeySend(paymentPreimage)))

    assert(nodeParams.db.payments.getIncomingPayment(paymentHash).isEmpty)

    val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithKeySend, IncomingPaymentPacket.FinalPacket(add, payload, TimestampMilli.now()))
    register.expectMsgType[Register.Forward[CMD_FULFILL_HTLC]]

    val paymentReceived = eventListener.expectMsgType[PaymentReceived]
    assert(paymentReceived.copy(parts = paymentReceived.parts.map(_.copy(timestamp = 0 unixms))) == PaymentReceived(add.paymentHash, PartialPayment(amountMsat, add.channelId, timestamp = 0 unixms) :: Nil))
    val received = nodeParams.db.payments.getIncomingPayment(paymentHash)
    assert(received.isDefined && received.get.status.isInstanceOf[IncomingPaymentStatus.Received])
    assert(received.get.status.asInstanceOf[IncomingPaymentStatus.Received].copy(receivedAt = 0 unixms) == IncomingPaymentStatus.Received(amountMsat, 0 unixms))
  }

  test("PaymentHandler should reject KeySend payment when feature is disabled") { f =>
    import f._

    val amountMsat = 42000 msat
    val paymentPreimage = randomBytes32()
    val paymentHash = Crypto.sha256(paymentPreimage)
    val paymentSecret = randomBytes32()
    val Right(payload) = FinalPayload.Standard.validate(TlvStream(OnionPaymentPayloadTlv.AmountToForward(amountMsat), OnionPaymentPayloadTlv.OutgoingCltv(defaultExpiry), OnionPaymentPayloadTlv.PaymentData(paymentSecret, 0 msat), OnionPaymentPayloadTlv.KeySend(paymentPreimage)))

    assert(nodeParams.db.payments.getIncomingPayment(paymentHash).isEmpty)

    val add = UpdateAddHtlc(ByteVector32.One, 0, amountMsat, paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    val receivedAt = TimestampMilli.now()
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, payload, receivedAt))

    f.register.expectMsg(Register.Forward(null, add.channelId, CMD_FAIL_HTLC(add.id, FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(42000 msat, nodeParams.currentBlockHeight)), Some(FailureAttributionData(receivedAt, None)), commit = true)))
    assert(nodeParams.db.payments.getIncomingPayment(paymentHash).isEmpty)
  }

  test("PaymentHandler should reject incoming payments if the invoice doesn't exist") { f =>
    import f._

    val paymentHash = randomBytes32()
    val paymentSecret = randomBytes32()
    assert(nodeParams.db.payments.getIncomingPayment(paymentHash).isEmpty)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 1000 msat, paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithoutMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, add.amountMsat, add.cltvExpiry, paymentSecret, None), TimestampMilli.now()))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.id == add.id)
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
  }

  test("PaymentHandler should reject incoming multi-part payment if the invoice doesn't exist") { f =>
    import f._

    val paymentHash = randomBytes32()
    val paymentSecret = randomBytes32()
    assert(nodeParams.db.payments.getIncomingPayment(paymentHash).isEmpty)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 800 msat, paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    sender.send(handlerWithMpp, IncomingPaymentPacket.FinalPacket(add, FinalPayload.Standard.createPayload(add.amountMsat, 1000 msat, add.cltvExpiry, paymentSecret, Some(hex"012345")), TimestampMilli.now()))
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.id == add.id)
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
  }

  test("PaymentHandler should fail fulfilling incoming payments if the invoice doesn't exist") { f =>
    import f._

    val paymentPreimage = randomBytes32()
    val paymentHash = Crypto.sha256(paymentPreimage)
    assert(nodeParams.db.payments.getIncomingPayment(paymentHash).isEmpty)

    val add = UpdateAddHtlc(ByteVector32.One, 0, 1000 msat, paymentHash, defaultExpiry, TestConstants.emptyOnionPacket, None, Reputation.maxEndorsement, None)
    val invoice = Bolt11Invoice(Block.Testnet3GenesisBlock.hash, None, paymentHash, randomKey(), Left("dummy"), CltvExpiryDelta(12))
    val incomingPayment = IncomingStandardPayment(invoice, paymentPreimage, PaymentType.Standard, invoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Pending)
    val fulfill = DoFulfill(incomingPayment, MultiPartPaymentFSM.MultiPartPaymentSucceeded(paymentHash, Queue(HtlcPart(1000 msat, add, TimestampMilli.now()))))
    sender.send(handlerWithoutMpp, fulfill)
    val cmd = register.expectMsgType[Register.Forward[CMD_FAIL_HTLC]].message
    assert(cmd.id == add.id)
    assert(cmd.reason == FailureReason.LocalFailure(IncorrectOrUnknownPaymentDetails(1000 msat, nodeParams.currentBlockHeight)))
  }

}
