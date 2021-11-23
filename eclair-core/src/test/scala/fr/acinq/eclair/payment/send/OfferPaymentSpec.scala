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

package fr.acinq.eclair.payment.send

import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.message.OnionMessages.Recipient
import fr.acinq.eclair.message.Postman
import fr.acinq.eclair.payment.{Bolt12Invoice, PaymentBlindedRoute}
import fr.acinq.eclair.payment.send.OfferPayment._
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.router.Router.RouteParams
import fr.acinq.eclair.wire.protocol.MessageOnion.FinalPayload
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer, PaymentInfo}
import fr.acinq.eclair.wire.protocol.{OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshiLong, NodeParams, TestConstants, randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.HexStringSyntax

import java.util.UUID
import scala.concurrent.duration.DurationInt

class OfferPaymentSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(offerPayment: ActorRef[Command], nodeParams: NodeParams, postman: TestProbe[Postman.Command], paymentInitiator: akka.testkit.TestProbe, routeParams: RouteParams)

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = TestConstants.Alice.nodeParams
    val postman = TestProbe[Postman.Command]("postman")
    val paymentInitiator = akka.testkit.TestProbe("paymentInitiator")(system.toClassic)
    val offerPayment = testKit.spawn(OfferPayment(nodeParams, postman.ref, paymentInitiator.ref))
    val routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams
    try {
      withFixture(test.toNoArgTest(FixtureParam(offerPayment, nodeParams, postman, paymentInitiator, routeParams)))
    } finally {
      testKit.stop(offerPayment)
    }
  }

  test("basic offer payment") { f =>
    import f._

    val probe = TestProbe[Result]()
    val merchantKey = randomKey()

    val offer = Offer(None, "amountless offer", merchantKey.publicKey, Features.empty, nodeParams.chainHash)
    offerPayment ! PayOffer(probe.ref, offer, 40_000_000 msat, 1, SendPaymentConfig(None, 1, routeParams))
    val Processing(uuid) = probe.expectMessageType[Processing]
    val Postman.SendMessage(_, Recipient(recipientId, _, _), _, message, replyTo, _) = postman.expectMessageType[Postman.SendMessage]
    assert(recipientId == merchantKey.publicKey)
    assert(message.get[OnionMessagePayloadTlv.InvoiceRequest].nonEmpty)
    val Right(invoiceRequest) = InvoiceRequest.validate(message.get[OnionMessagePayloadTlv.InvoiceRequest].get.tlvs)

    val preimage = randomBytes32()
    val paymentRoute = PaymentBlindedRoute(RouteBlinding.create(randomKey(), Seq(merchantKey.publicKey), Seq(hex"7777")).route, PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, 1_000_000_000 msat, Features.empty))
    val invoice = Bolt12Invoice(invoiceRequest, preimage, merchantKey, 1 minute, Features.empty, Seq(paymentRoute))
    replyTo ! Postman.Response(FinalPayload(TlvStream(OnionMessagePayloadTlv.Invoice(invoice.records)), TlvStream.empty))
    val send = paymentInitiator.expectMsgType[SendPaymentToNode]
    assert(send.invoice == invoice)

    val paymentId = UUID.randomUUID()
    send.replyTo ! paymentId

    probe.expectTerminated(offerPayment)

    val attempts = nodeParams.db.offers.getAttemptsToPayOffer(offer)
    assert(attempts.size == 1)
    assert(attempts.head.attemptId == uuid)
    assert(attempts.head.request == invoiceRequest)
    assert(attempts.head.invoice.contains(invoice))
    assert(attempts.head.paymentId_opt.contains(paymentId))
  }

  test("no reply to invoice request with retries") { f =>
    import f._

    val probe = TestProbe[Result]()
    val merchantKey = randomKey()

    val offer = Offer(None, "amountless offer", merchantKey.publicKey, Features.empty, nodeParams.chainHash)
    offerPayment ! PayOffer(probe.ref, offer, 40_000_000 msat, 1, SendPaymentConfig(None, 1, routeParams))
    val Processing(uuid) = probe.expectMessageType[Processing]
    val Postman.SendMessage(_, Recipient(recipientId, _, _), _, message, replyTo, _) = postman.expectMessageType[Postman.SendMessage]
    assert(recipientId == merchantKey.publicKey)
    assert(message.get[OnionMessagePayloadTlv.InvoiceRequest].nonEmpty)
    val Right(invoiceRequest) = InvoiceRequest.validate(message.get[OnionMessagePayloadTlv.InvoiceRequest].get.tlvs)

    replyTo ! Postman.NoReply
    postman.expectMessageType[Postman.SendMessage]
    replyTo ! Postman.NoReply
    paymentInitiator.expectNoMessage()

    probe.expectTerminated(offerPayment)

    val attempts = nodeParams.db.offers.getAttemptsToPayOffer(offer)
    assert(attempts.size == 1)
    assert(attempts.head.attemptId == uuid)
    assert(attempts.head.request == invoiceRequest)
    assert(attempts.head.invoice.isEmpty)
    assert(attempts.head.paymentId_opt.isEmpty)
  }

  test("invalid invoice") { f =>
    import f._

    val probe = TestProbe[Result]()
    val merchantKey = randomKey()

    val offer = Offer(None, "amountless offer", merchantKey.publicKey, Features.empty, nodeParams.chainHash)
    offerPayment ! PayOffer(probe.ref, offer, 40_000_000 msat, 1, SendPaymentConfig(None, 1, routeParams))
    val Processing(uuid) = probe.expectMessageType[Processing]
    val Postman.SendMessage(_, Recipient(recipientId, _, _), _, message, replyTo, _) = postman.expectMessageType[Postman.SendMessage]
    assert(recipientId == merchantKey.publicKey)
    assert(message.get[OnionMessagePayloadTlv.InvoiceRequest].nonEmpty)
    val Right(invoiceRequest) = InvoiceRequest.validate(message.get[OnionMessagePayloadTlv.InvoiceRequest].get.tlvs)

    val preimage = randomBytes32()
    val paymentRoute = PaymentBlindedRoute(RouteBlinding.create(randomKey(), Seq(merchantKey.publicKey), Seq(hex"7777")).route, PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, 1_000_000_000 msat, Features.empty))
    val invoice = Bolt12Invoice(invoiceRequest, preimage, randomKey(), 1 minute, Features.empty, Seq(paymentRoute))
    replyTo ! Postman.Response(FinalPayload(TlvStream(OnionMessagePayloadTlv.Invoice(invoice.records)), TlvStream.empty))
    paymentInitiator.expectNoMessage()

    probe.expectTerminated(offerPayment)

    val attempts = nodeParams.db.offers.getAttemptsToPayOffer(offer)
    assert(attempts.size == 1)
    assert(attempts.head.attemptId == uuid)
    assert(attempts.head.request == invoiceRequest)
    assert(attempts.head.invoice.contains(invoice))
    assert(attempts.head.paymentId_opt.isEmpty)
  }
}
