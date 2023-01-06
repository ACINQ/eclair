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

package fr.acinq.eclair.offer

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto}
import fr.acinq.eclair.message.OnionMessages.Recipient
import fr.acinq.eclair.message.{OnionMessages, Postman}
import fr.acinq.eclair.offer.OfferManager._
import fr.acinq.eclair.payment.Bolt12Invoice
import fr.acinq.eclair.payment.receive.MultiPartHandler
import fr.acinq.eclair.payment.receive.MultiPartHandler.GetIncomingPaymentActor.{PaymentFound, NoPayment}
import fr.acinq.eclair.payment.receive.MultiPartHandler.ReceivingRoute
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer, OfferPaths}
import fr.acinq.eclair.wire.protocol.RouteBlindingEncryptedDataCodecs.RouteBlindingDecryptedData
import fr.acinq.eclair.wire.protocol.{MessageOnion, OnionMessagePayloadTlv, OnionPaymentPayloadTlv, PaymentOnion, RouteBlindingEncryptedDataCodecs, RouteBlindingEncryptedDataTlv, TlvStream}
import fr.acinq.eclair.{CltvExpiry, CltvExpiryDelta, Features, MilliSatoshi, MilliSatoshiLong, NodeParams, TestConstants, randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.HexStringSyntax

class OfferManagerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(nodeParams: NodeParams, offerManager: ActorRef[Command], postman: TestProbe[Postman.Command], router: akka.testkit.TestProbe, paymentHandler: TestProbe[MultiPartHandler.GetIncomingPaymentActor.Command])

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = TestConstants.Alice.nodeParams
    val router = akka.testkit.TestProbe()(system.toClassic)
    val offerManager = testKit.spawn(OfferManager(nodeParams, router.ref))
    val postman = TestProbe[Postman.Command]()
    val paymentHandler = TestProbe[MultiPartHandler.GetIncomingPaymentActor.Command]()
    try {
      withFixture(test.toNoArgTest(FixtureParam(nodeParams, offerManager, postman, router, paymentHandler)))
    } finally {
      testKit.stop(offerManager)
    }
  }

  def requestInvoice(payerKey: PrivateKey, offer: Offer, amount: MilliSatoshi, offerManager: ActorRef[Command], postman: ActorRef[Postman.Command], pathId: Option[ByteVector32] = None): Unit = {
    val invoiceRequest = InvoiceRequest(offer, amount, 1, Features.empty, payerKey, offer.chains.head)
    val replyPath1 = OnionMessages.buildRoute(randomKey(), Nil, Recipient(payerKey.publicKey, None))
    offerManager ! RequestInvoice(MessageOnion.FinalPayload(TlvStream(OnionMessagePayloadTlv.InvoiceRequest(invoiceRequest.records), OnionMessagePayloadTlv.ReplyPath(replyPath1)), TlvStream(pathId.map(id => Set[RouteBlindingEncryptedDataTlv](RouteBlindingEncryptedDataTlv.PathId(id))).getOrElse(Set.empty))), postman)
  }

  test("multiple offers") { f =>
    import f._

    val handler1 = TestProbe[HandlerCommand]()
    val handler2 = TestProbe[HandlerCommand]()

    val pathId1 = randomBytes32()
    val offer1 = Offer(Some(11_000_000 msat), "test offer", nodeParams.nodeId, Features.empty, nodeParams.chainHash, Set(OfferPaths(Seq(OnionMessages.buildRoute(randomKey(), Nil, Recipient(nodeParams.nodeId, Some(pathId1)))))))
    offerManager ! RegisterOffer(offer1, nodeParams.privateKey, Some(pathId1), handler1.ref)
    val offer2 = Offer(Some(12_000_000 msat), "other offer", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer2, nodeParams.privateKey, None, handler2.ref)
    val offer3 = Offer(Some(13_000_000 msat), "third offer", nodeParams.nodeId, Features.empty, nodeParams.chainHash)
    offerManager ! RegisterOffer(offer3, nodeParams.privateKey, None, handler2.ref)
    val offer4 = Offer(Some(14_000_000 msat), "unhandled offer", nodeParams.nodeId, Features.empty, nodeParams.chainHash)

    val payerKey1 = randomKey()
    requestInvoice(payerKey1, offer1, 12_000_000 msat, offerManager, postman.ref, Some(pathId1))
    val payerKey2 = randomKey()
    requestInvoice(payerKey2, offer2, 12_000_000 msat, offerManager, postman.ref)
    val payerKey3 = randomKey()
    requestInvoice(payerKey3, offer3, 13_000_000 msat, offerManager, postman.ref)
    val payerKey4 = randomKey()
    requestInvoice(payerKey4, offer4, 15_000_000 msat, offerManager, postman.ref) // Will be ignored as offer4 is not registered
    val payerKey5 = randomKey()
    requestInvoice(payerKey5, offer1, 13_000_000 msat, offerManager, postman.ref) // Will be ignored because it doesn't use the blinded path
    val payerKey6 = randomKey()
    requestInvoice(payerKey6, offer2, 10_000_000 msat, offerManager, postman.ref) // Will be ignored as the request is invalid (amount too low)

    val handleInvoiceRequest1 = handler1.expectMessageType[HandleInvoiceRequest]
    assert(handleInvoiceRequest1.invoiceRequest.payerId == payerKey1.publicKey)
    handleInvoiceRequest1.replyTo ! InvoiceRequestActor.ApproveRequest(12_000_000 msat, Seq(ReceivingRoute(Seq(nodeParams.nodeId), CltvExpiryDelta(1000), Nil)), Features.empty, hex"01")
    val invoiceMessage1 = postman.expectMessageType[Postman.SendMessage]
    val Right(invoice1) = Bolt12Invoice.validate(invoiceMessage1.message.get[OnionMessagePayloadTlv.Invoice].get.tlvs)
    assert(invoice1.invoiceRequest.payerId == payerKey1.publicKey)
    assert(invoice1.amount == 12_000_000.msat)

    val handleInvoiceRequest2 = handler2.expectMessageType[HandleInvoiceRequest]
    assert(handleInvoiceRequest2.invoiceRequest.payerId == payerKey2.publicKey)
    handleInvoiceRequest2.replyTo ! InvoiceRequestActor.RejectRequest

    val handleInvoiceRequest3 = handler2.expectMessageType[HandleInvoiceRequest]
    assert(handleInvoiceRequest3.invoiceRequest.payerId == payerKey3.publicKey)
    handleInvoiceRequest3.replyTo ! InvoiceRequestActor.ApproveRequest(13_000_000 msat, Seq(ReceivingRoute(Seq(nodeParams.nodeId), CltvExpiryDelta(1000), Nil)), Features.empty, hex"03")
    val invoiceMessage3 = postman.expectMessageType[Postman.SendMessage]
    val Right(invoice3) = Bolt12Invoice.validate(invoiceMessage3.message.get[OnionMessagePayloadTlv.Invoice].get.tlvs)
    assert(invoice3.invoiceRequest.payerId == payerKey3.publicKey)
    assert(invoice3.amount == 13_000_000.msat)

    handler1.expectNoMessage()
    handler2.expectNoMessage()
    postman.expectNoMessage()

    { // Trying to pay invoice1 using blinded route from invoice3
      val Right(RouteBlindingDecryptedData(encryptedDataTlvs, _)) = RouteBlindingEncryptedDataCodecs.decode(nodeParams.privateKey, invoice3.blindedPaths.head.route.blindingKey, invoice3.blindedPaths.head.route.encryptedPayloads.head)
      val paymentTlvs = TlvStream[OnionPaymentPayloadTlv](
        OnionPaymentPayloadTlv.AmountToForward(12_000_000 msat),
        OnionPaymentPayloadTlv.TotalAmount(12_000_000 msat),
        OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(nodeParams.currentBlockHeight) + invoice3.blindedPaths.head.paymentInfo.cltvExpiryDelta),
      )
      offerManager ! Payment(paymentHandler.ref, invoice1.paymentHash, PaymentOnion.FinalPayload.Blinded(paymentTlvs, encryptedDataTlvs))
      handler1.expectNoMessage()
      handler2.expectNoMessage()
      paymentHandler.expectMessage(NoPayment)
    }

    { // Paying invoice1
      val Right(RouteBlindingDecryptedData(encryptedDataTlvs, _)) = RouteBlindingEncryptedDataCodecs.decode(nodeParams.privateKey, invoice1.blindedPaths.head.route.blindingKey, invoice1.blindedPaths.head.route.encryptedPayloads.head)
      val paymentTlvs = TlvStream[OnionPaymentPayloadTlv](
        OnionPaymentPayloadTlv.AmountToForward(12_000_000 msat),
        OnionPaymentPayloadTlv.TotalAmount(12_000_000 msat),
        OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(nodeParams.currentBlockHeight) + invoice1.blindedPaths.head.paymentInfo.cltvExpiryDelta),
      )
      offerManager ! Payment(paymentHandler.ref, invoice1.paymentHash, PaymentOnion.FinalPayload.Blinded(paymentTlvs, encryptedDataTlvs))
      val handlePayment = handler1.expectMessageType[HandlePayment]
      assert(handlePayment.offerId == offer1.offerId)
      assert(handlePayment.data == hex"01")
      handlePayment.replyTo ! PaymentActor.AcceptPayment(Nil, Nil)
      val PaymentFound(incomingPayment) = paymentHandler.expectMessageType[PaymentFound]
      assert(Crypto.sha256(incomingPayment.paymentPreimage) == invoice1.paymentHash)
    }

    { // Paying invoice3
      val Right(RouteBlindingDecryptedData(encryptedDataTlvs, _)) = RouteBlindingEncryptedDataCodecs.decode(nodeParams.privateKey, invoice3.blindedPaths.head.route.blindingKey, invoice3.blindedPaths.head.route.encryptedPayloads.head)
      val paymentTlvs = TlvStream[OnionPaymentPayloadTlv](
        OnionPaymentPayloadTlv.AmountToForward(13_000_000 msat),
        OnionPaymentPayloadTlv.TotalAmount(13_000_000 msat),
        OnionPaymentPayloadTlv.OutgoingCltv(CltvExpiry(nodeParams.currentBlockHeight) + invoice3.blindedPaths.head.paymentInfo.cltvExpiryDelta),
      )
      offerManager ! Payment(paymentHandler.ref, invoice3.paymentHash, PaymentOnion.FinalPayload.Blinded(paymentTlvs, encryptedDataTlvs))
      val handlePayment = handler2.expectMessageType[HandlePayment]
      assert(handlePayment.offerId == offer3.offerId)
      assert(handlePayment.data == hex"03")
      handlePayment.replyTo ! PaymentActor.AcceptPayment(Nil, Nil)
      val PaymentFound(incomingPayment) = paymentHandler.expectMessageType[PaymentFound]
      assert(Crypto.sha256(incomingPayment.paymentPreimage) == invoice3.paymentHash)
    }
  }
}
