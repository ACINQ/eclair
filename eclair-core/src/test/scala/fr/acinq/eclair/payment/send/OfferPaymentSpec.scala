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

import akka.actor.ActorSystem
import akka.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, TestProbe => TypedProbe}
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.message.OnionMessages.Recipient
import fr.acinq.eclair.message.Postman
import fr.acinq.eclair.payment.send.OfferPayment._
import fr.acinq.eclair.payment.send.PaymentInitiator.SendPaymentToNode
import fr.acinq.eclair.payment.{Bolt12Invoice, PaymentBlindedRoute}
import fr.acinq.eclair.router.Router.RouteParams
import fr.acinq.eclair.wire.protocol.MessageOnion.FinalPayload
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer, PaymentInfo}
import fr.acinq.eclair.wire.protocol.{OnionMessagePayloadTlv, TlvStream}
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshiLong, NodeParams, TestConstants, randomBytes32, randomKey}
import org.scalatest.Outcome
import org.scalatest.funsuite.FixtureAnyFunSuiteLike
import scodec.bits.HexStringSyntax

import scala.concurrent.duration.DurationInt

class OfferPaymentSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with FixtureAnyFunSuiteLike {

  case class FixtureParam(offerPayment: ActorRef[Command], nodeParams: NodeParams, postman: TypedProbe[Postman.Command], paymentInitiator: TestProbe, routeParams: RouteParams)

  override def withFixture(test: OneArgTest): Outcome = {
    val nodeParams = TestConstants.Alice.nodeParams
    val postman = TypedProbe[Postman.Command]("postman")
    val paymentInitiator = TestProbe("paymentInitiator")(system.toClassic)
    val offerPayment = testKit.spawn(OfferPayment(nodeParams, postman.ref, paymentInitiator.ref))
    val routeParams = nodeParams.routerConf.pathFindingExperimentConf.getRandomConf().getDefaultRouteParams
    try {
      withFixture(test.toNoArgTest(FixtureParam(offerPayment, nodeParams, postman, paymentInitiator, routeParams)))
    } finally {
      testKit.stop(offerPayment)
    }
  }

  implicit val classicSystem: ActorSystem = system.classicSystem

  test("basic offer payment") { f =>
    import f._

    val probe = TestProbe()
    val merchantKey = randomKey()

    val offer = Offer(None, "amountless offer", merchantKey.publicKey, Features.empty, nodeParams.chainHash)
    offerPayment ! PayOffer(probe.ref, offer, 40_000_000 msat, 1, SendPaymentConfig(None, 1, routeParams, blocking = false))
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

    TypedProbe().expectTerminated(offerPayment)
  }

  test("no reply to invoice request with retries") { f =>
    import f._

    val probe = TestProbe()
    val merchantKey = randomKey()

    val offer = Offer(None, "amountless offer", merchantKey.publicKey, Features.empty, nodeParams.chainHash)
    offerPayment ! PayOffer(probe.ref, offer, 40_000_000 msat, 1, SendPaymentConfig(None, 1, routeParams, blocking = false))
    for (i <- 1 to nodeParams.onionMessageConfig.maxAttempts) {
      val Postman.SendMessage(_, Recipient(recipientId, _, _), _, message, replyTo, _) = postman.expectMessageType[Postman.SendMessage]
      assert(recipientId == merchantKey.publicKey)
      assert(message.get[OnionMessagePayloadTlv.InvoiceRequest].nonEmpty)
      val Right(invoiceRequest) = InvoiceRequest.validate(message.get[OnionMessagePayloadTlv.InvoiceRequest].get.tlvs)
      assert(invoiceRequest.isValidFor(offer))
      replyTo ! Postman.NoReply
    }
    probe.expectMsg(NoInvoice)
    paymentInitiator.expectNoMessage(50 millis)
    TypedProbe().expectTerminated(offerPayment)
  }

  test("invalid invoice") { f =>
    import f._

    val probe = TestProbe()
    val merchantKey = randomKey()

    val offer = Offer(None, "amountless offer", merchantKey.publicKey, Features.empty, nodeParams.chainHash)
    offerPayment ! PayOffer(probe.ref, offer, 40_000_000 msat, 1, SendPaymentConfig(None, 1, routeParams, blocking = false))
    val Postman.SendMessage(_, Recipient(recipientId, _, _), _, message, replyTo, _) = postman.expectMessageType[Postman.SendMessage]
    assert(recipientId == merchantKey.publicKey)
    assert(message.get[OnionMessagePayloadTlv.InvoiceRequest].nonEmpty)
    val Right(invoiceRequest) = InvoiceRequest.validate(message.get[OnionMessagePayloadTlv.InvoiceRequest].get.tlvs)

    val preimage = randomBytes32()
    val paymentRoute = PaymentBlindedRoute(RouteBlinding.create(randomKey(), Seq(merchantKey.publicKey), Seq(hex"7777")).route, PaymentInfo(0 msat, 0, CltvExpiryDelta(0), 0 msat, 1_000_000_000 msat, Features.empty))
    val invoice = Bolt12Invoice(invoiceRequest, preimage, randomKey(), 1 minute, Features.empty, Seq(paymentRoute))
    replyTo ! Postman.Response(FinalPayload(TlvStream(OnionMessagePayloadTlv.Invoice(invoice.records)), TlvStream.empty))

    probe.expectMsgType[InvalidInvoice]
    paymentInitiator.expectNoMessage(50 millis)

    TypedProbe().expectTerminated(offerPayment)
  }
}
