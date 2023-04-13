/*
 * Copyright 2023 ACINQ SAS
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

package fr.acinq.eclair.payment.offer

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.eclair.message.OnionMessages.Recipient
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.payment.Bolt12Invoice
import fr.acinq.eclair.payment.offer.OfferManager._
import fr.acinq.eclair.payment.receive.MultiPartHandler
import fr.acinq.eclair.payment.receive.MultiPartHandler.GetIncomingPaymentActor.{ProcessPayment, RejectPayment}
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivingRoute
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer}
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.RouteBlindingDecryptedData
import fr.acinq.eclair.wire.protocol._
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, NodeParams, TestConstants, randomBytes32, randomKey}
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import org.scalatest.{Outcome, Tag}
import scodec.bits.{ByteVector, HexStringSyntax}

import scala.concurrent.duration.DurationInt

class OfferManagerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  private val ShortPaymentTimeout = "short_payment_timeout"

  case class FixtureParam(nodeParams: NodeParams, offerManager: ActorRef[Command], postman: TestProbe[Postman.Command], router: akka.testkit.TestProbe, paymentHandler: TestProbe[MultiPartHandler.GetIncomingPaymentActor.Command])

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = TestConstants.Alice.nodeParams
    val router = akka.testkit.TestProbe()(system.toClassic)
    val paymentTimeout = if (test.tags.contains(ShortPaymentTimeout)) 100 millis else 5 seconds
    val offerManager = testKit.spawn(OfferManager(nodeParams, router.ref, paymentTimeout))
    val postman = TestProbe[Postman.Command]()
    val paymentHandler = TestProbe[MultiPartHandler.GetIncomingPaymentActor.Command]()
    try {
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, offerManager, postman, router, paymentHandler)))
    } finally {
      testKit.stop(offerManager)
    }
  }

  def requestInvoice(payerKey: PrivateKey, offer: Offer, amount: MilliSatoshi, offerManager: ActorRef[Command], postman: ActorRef[Postman.Command], pathId_opt: Option[ByteVector32] = None): Unit = {
    val invoiceRequest = InvoiceRequest(offer, amount, 1, Features.empty, payerKey, offer.chains.head)
    val replyPath = OnionMessages.buildRoute(randomKey(), Nil, Recipient(payerKey.publicKey, None))
    val Right(messagePayload: MessageOnion.InvoiceRequestPayload) = MessageOnion.FinalPayload.validate(
      TlvStream(OnionMessagePayloadTlv.InvoiceRequest(invoiceRequest.records), OnionMessagePayloadTlv.ReplyPath(replyPath)),
      pathId_opt.map(pathId => TlvStream[RouteBlindingEncryptedDataTlv](RouteBlindingEncryptedDataTlv.PathId(pathId))).getOrElse(TlvStream.empty),
    )
    offerManager ! RequestInvoice(messagePayload, postman)
  }

  def receiveInvoice(f: FixtureParam, amount: MilliSatoshi, payerKey: PrivateKey, handler: TestProbe[HandlerCommand], pluginData_opt: Option[ByteVector] = None): Bolt12Invoice = {
    import f._

    val handleInvoiceRequest = handler.expectMessageType[HandleInvoiceRequest]
    assert(handleInvoiceRequest.invoiceRequest.isValid)
    assert(handleInvoiceRequest.invoiceRequest.payerId == payerKey.publicKey)
    handleInvoiceRequest.replyTo ! InvoiceRequestActor.ApproveRequest(amount, Seq(ReceivingRoute(Seq(nodeParams.nodeId), CltvExpiryDelta(1000), Nil)), pluginData_opt)
    val invoiceMessage = postman.expectMessageType[Postman.SendMessage]
    val Right(invoice) = Bolt12Invoice.validate(invoiceMessage.message.get[OnionMessagePayloadTlv.Invoice].get.tlvs)
    assert(invoice.validateFor(handleInvoiceRequest.invoiceRequest).isRight)
    assert(invoice.invoiceRequest.payerId == payerKey.publicKey)
    assert(invoice.amount == amount)
    invoice
  }

  def createPaymentPayload(f: FixtureParam, invoice: Bolt12Invoice): PaymentOnion.FinalPayload.Blinded = {
    import f._

    assert(invoice.blindedPaths.length == 1)
    val blindedPath = invoice.blindedPaths.head.route
    val Right(RouteBlindingDecryptedData(encryptedDataTlvs, _)) = RouteBlindingEncryptedDataCodecs.decode(nodeParams.privateKey, blindedPath.blindingKey, blindedPath.encryptedPayloads.head)
    val paymentTlvs = TlvStream[OnionPaymentPayloadTlv](
      OnionPaymentPayloadTlv.AmountToForward(invoice.amount),
      OnionPaymentPayloadTlv.TotalAmount(invoice.amount),
      OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(nodeParams.currentBlockHeight) + invoice.blindedPaths.head.paymentInfo.cltvExpiryDelta),
    )
    PaymentOnion.FinalPayload.Blinded(paymentTlvs, encryptedDataTlvs)
  }

  def payOffer(f: FixtureParam, pathId_opt: Option[ByteVector32]): Unit = {
    import f._

    val handler = TestProbe[HandlerCommand]()
    val amount = 10_000_000 msat
    val offer = Offer(Some(amount), "offer", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer, nodeParams.privateKey, pathId_opt, handler.ref)
    // Request invoice.
    val payerKey = randomKey()
    requestInvoice(payerKey, offer, amount, offerManager, postman.ref, pathId_opt)
    val invoice = receiveInvoice(f, amount, payerKey, handler, pluginData_opt = Some(hex"deadbeef"))
    // Pay invoice.
    val paymentPayload = createPaymentPayload(f, invoice)
    offerManager ! ReceivePayment(paymentHandler.ref, invoice.paymentHash, paymentPayload)
    val handlePayment = handler.expectMessageType[HandlePayment]
    assert(handlePayment.offerId == offer.offerId)
    assert(handlePayment.pluginData_opt.contains(hex"deadbeef"))
    handlePayment.replyTo ! PaymentActor.AcceptPayment()
    val ProcessPayment(incomingPayment) = paymentHandler.expectMessageType[ProcessPayment]
    assert(Crypto.sha256(incomingPayment.paymentPreimage) == invoice.paymentHash)
    assert(incomingPayment.invoice.nodeId == nodeParams.nodeId)
    assert(incomingPayment.invoice.paymentHash == invoice.paymentHash)
  }

  test("pay offer without path_id") { f =>
    payOffer(f, pathId_opt = None)
  }

  test("pay offer with path_id") { f =>
    payOffer(f, pathId_opt = Some(randomBytes32()))
  }

  test("invalid invoice request (amount too low)") { f =>
    import f._

    val handler = TestProbe[HandlerCommand]()
    val offer = Offer(Some(10_000_000 msat), "offer", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer, nodeParams.privateKey, None, handler.ref)
    requestInvoice(randomKey(), offer, 9_000_000 msat, offerManager, postman.ref)
    handler.expectNoMessage(50 millis)
  }

  test("invalid invoice request (missing path_id)") { f =>
    import f._

    val handler = TestProbe[HandlerCommand]()
    val pathId = randomBytes32()
    val offer = Offer(Some(10_000_000 msat), "offer with path_id", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer, nodeParams.privateKey, Some(pathId), handler.ref)
    requestInvoice(randomKey(), offer, 10_000_000 msat, offerManager, postman.ref)
    handler.expectNoMessage(50 millis)
  }

  test("invalid invoice request (invalid path_id)") { f =>
    import f._

    val handler = TestProbe[HandlerCommand]()
    val pathId = randomBytes32()
    val offer = Offer(Some(10_000_000 msat), "offer with path_id", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer, nodeParams.privateKey, Some(pathId), handler.ref)
    requestInvoice(randomKey(), offer, 10_000_000 msat, offerManager, postman.ref, pathId_opt = Some(pathId.reverse))
    handler.expectNoMessage(50 millis)
  }

  test("invalid invoice request (disabled offer)") { f =>
    import f._

    val handler = TestProbe[HandlerCommand]()
    val offer = Offer(Some(10_000_000 msat), "offer", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer, nodeParams.privateKey, None, handler.ref)
    offerManager ! DisableOffer(offer)
    requestInvoice(randomKey(), offer, 10_000_000 msat, offerManager, postman.ref)
    handler.expectNoMessage(50 millis)
  }

  test("invalid invoice request (rejected by plugin handler)") { f =>
    import f._

    val handler = TestProbe[HandlerCommand]()
    val offer = Offer(Some(10_000_000 msat), "offer", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer, nodeParams.privateKey, None, handler.ref)
    requestInvoice(randomKey(), offer, 10_000_000 msat, offerManager, postman.ref)
    val handleInvoiceRequest = handler.expectMessageType[HandleInvoiceRequest]
    handleInvoiceRequest.replyTo ! InvoiceRequestActor.RejectRequest("internal error")
    val invoiceMessage = postman.expectMessageType[Postman.SendMessage]
    val invoiceError = invoiceMessage.message.get[OnionMessagePayloadTlv.InvoiceError]
    assert(invoiceError.nonEmpty)
    assert(invoiceError.get.tlvs.get[OfferTypes.Error].map(_.message).contains("internal error"))
  }

  test("invalid payment (invalid blinded path)") { f =>
    import f._

    val handler = TestProbe[HandlerCommand]()
    val amount = 10_000_000 msat
    val offer1 = Offer(Some(amount), "offer #1", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    val offer2 = Offer(Some(amount), "offer #2", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer1, nodeParams.privateKey, None, handler.ref)
    offerManager ! RegisterOffer(offer2, nodeParams.privateKey, None, handler.ref)
    // Request invoices for offers #1 and #2.
    val payerKey = randomKey()
    requestInvoice(payerKey, offer1, amount, offerManager, postman.ref)
    val invoice1 = receiveInvoice(f, amount, payerKey, handler)
    requestInvoice(payerKey, offer2, amount, offerManager, postman.ref)
    val invoice2 = receiveInvoice(f, amount, payerKey, handler)
    // Try paying invoice #1 with data from invoice #2.
    val paymentPayload = createPaymentPayload(f, invoice2)
    offerManager ! ReceivePayment(paymentHandler.ref, invoice1.paymentHash, paymentPayload)
    paymentHandler.expectMessageType[RejectPayment]
    handler.expectNoMessage(50 millis)
  }

  test("invalid payment (invalid payment metadata)") { f =>
    import f._

    val handler = TestProbe[HandlerCommand]()
    val amount = 10_000_000 msat
    val offer = Offer(Some(amount), "offer", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer, nodeParams.privateKey, None, handler.ref)
    // Request invoice.
    val payerKey = randomKey()
    requestInvoice(payerKey, offer, amount, offerManager, postman.ref)
    val invoice = receiveInvoice(f, amount, payerKey, handler)
    // Try paying the invoice with a modified path_id.
    val paymentPayload = createPaymentPayload(f, invoice)
    val Some(pathId) = paymentPayload.blindedRecords.get[RouteBlindingEncryptedDataTlv.PathId].map(_.data)
    val invalidPathId = pathId.take(64).reverse ++ pathId.drop(64)
    val invalidPaymentPayload = paymentPayload.copy(
      blindedRecords = TlvStream(paymentPayload.blindedRecords.records.filterNot(_.isInstanceOf[RouteBlindingEncryptedDataTlv.PathId]) + RouteBlindingEncryptedDataTlv.PathId(invalidPathId))
    )
    offerManager ! ReceivePayment(paymentHandler.ref, invoice.paymentHash, invalidPaymentPayload)
    paymentHandler.expectMessageType[RejectPayment]
    handler.expectNoMessage(50 millis)
  }

  test("invalid payment (plugin handler timeout)", Tag(ShortPaymentTimeout)) { f =>
    import f._

    val handler = TestProbe[HandlerCommand]()
    val amount = 10_000_000 msat
    val offer = Offer(Some(amount), "offer", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer, nodeParams.privateKey, None, handler.ref)
    // Request invoice.
    val payerKey = randomKey()
    requestInvoice(payerKey, offer, amount, offerManager, postman.ref)
    val invoice = receiveInvoice(f, amount, payerKey, handler)
    // Try paying the invoice, but the plugin handler doesn't respond.
    val paymentPayload = createPaymentPayload(f, invoice)
    offerManager ! ReceivePayment(paymentHandler.ref, invoice.paymentHash, paymentPayload)
    handler.expectMessageType[HandlePayment]
    assert(paymentHandler.expectMessageType[RejectPayment].reason == "plugin timeout")
  }

  test("invalid payment (rejected by plugin handler)") { f =>
    import f._

    val handler = TestProbe[HandlerCommand]()
    val amount = 10_000_000 msat
    val offer = Offer(Some(amount), "offer", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer, nodeParams.privateKey, None, handler.ref)
    // Request invoice.
    val payerKey = randomKey()
    requestInvoice(payerKey, offer, amount, offerManager, postman.ref)
    val invoice = receiveInvoice(f, amount, payerKey, handler)
    // Try paying the invoice, but the plugin handler rejects the payment.
    val paymentPayload = createPaymentPayload(f, invoice)
    offerManager ! ReceivePayment(paymentHandler.ref, invoice.paymentHash, paymentPayload)
    val handlePayment = handler.expectMessageType[HandlePayment]
    handlePayment.replyTo ! PaymentActor.RejectPayment("internal error")
    assert(paymentHandler.expectMessageType[RejectPayment].reason == "internal error")
  }

}
