/*
 * Copyright 2022 ACINQ SAS
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

package fr.acinq.eclair.payment.receive

import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps
import akka.testkit.TestProbe
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.TestDatabases.TestPgDatabases
import fr.acinq.eclair.db.{IncomingPayment, IncomingPaymentStatus, PaymentType, PaymentsDbSpec}
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.payment.receive.InvoicePurger.{PurgeCompleted, PurgeEvent}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshiLong, TestKitBaseClass, TimestampMilli, TimestampMilliLong, TimestampSecondLong, randomBytes32}
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class InvoicePurgerSpec extends TestKitBaseClass with AnyFunSuiteLike {

  import PaymentsDbSpec._

  test("purge invoices") {
    val dbs = TestPgDatabases()
    val db = dbs.db.payments

    // can't receive a payment without an invoice associated with it
    val unknownPaymentHash = randomBytes32()
    assert(!db.receiveIncomingPayment(unknownPaymentHash, 12345678 msat))
    assert(db.getIncomingPayment(unknownPaymentHash).isEmpty)

    val expiredInvoice1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32(), alicePriv, Left("invoice #1"), CltvExpiryDelta(18), timestamp = 1 unixsec)
    val expiredInvoice2 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32(), bobPriv, Left("invoice #2"), CltvExpiryDelta(18), timestamp = 2 unixsec, expirySeconds = Some(30))
    val expiredPayment1 = IncomingPayment(expiredInvoice1, randomBytes32(), PaymentType.Standard, expiredInvoice1.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired)
    val expiredPayment2 = IncomingPayment(expiredInvoice2, randomBytes32(), PaymentType.Standard, expiredInvoice2.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired)

    val pendingInvoice1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32(), alicePriv, Left("invoice #3"), CltvExpiryDelta(18))
    val pendingInvoice2 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32(), bobPriv, Left("invoice #4"), CltvExpiryDelta(18), expirySeconds = Some(30))
    val pendingPayment1 = IncomingPayment(pendingInvoice1, randomBytes32(), PaymentType.Standard, pendingInvoice1.createdAt.toTimestampMilli, IncomingPaymentStatus.Pending)
    val pendingPayment2 = IncomingPayment(pendingInvoice2, randomBytes32(), PaymentType.SwapIn, pendingInvoice2.createdAt.toTimestampMilli, IncomingPaymentStatus.Pending)

    val paidInvoice1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(561 msat), randomBytes32(), alicePriv, Left("invoice #5"), CltvExpiryDelta(18))
    val paidInvoice2 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(1105 msat), randomBytes32(), bobPriv, Left("invoice #6"), CltvExpiryDelta(18), expirySeconds = Some(60))
    val receivedAt1 = TimestampMilli.now() + 1.milli
    val receivedAt2 = TimestampMilli.now() + 2.milli
    val payment1 = IncomingPayment(paidInvoice1, randomBytes32(), PaymentType.Standard, paidInvoice1.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(561 msat, receivedAt2))
    val payment2 = IncomingPayment(paidInvoice2, randomBytes32(), PaymentType.Standard, paidInvoice2.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(1111 msat, receivedAt2))

    db.addIncomingPayment(pendingInvoice1, pendingPayment1.paymentPreimage)
    db.addIncomingPayment(pendingInvoice2, pendingPayment2.paymentPreimage, PaymentType.SwapIn)
    db.addIncomingPayment(expiredInvoice1, expiredPayment1.paymentPreimage)
    db.addIncomingPayment(expiredInvoice2, expiredPayment2.paymentPreimage)
    db.addIncomingPayment(paidInvoice1, payment1.paymentPreimage)
    db.addIncomingPayment(paidInvoice2, payment2.paymentPreimage)

    assert(db.getIncomingPayment(pendingInvoice1.paymentHash) === Some(pendingPayment1))
    assert(db.getIncomingPayment(expiredInvoice2.paymentHash) === Some(expiredPayment2))
    assert(db.getIncomingPayment(paidInvoice1.paymentHash) === Some(payment1.copy(status = IncomingPaymentStatus.Pending)))

    val now = TimestampMilli.now()
    assert(db.listIncomingPayments(0 unixms, now) === Seq(expiredPayment1, expiredPayment2, pendingPayment1, pendingPayment2, payment1.copy(status = IncomingPaymentStatus.Pending), payment2.copy(status = IncomingPaymentStatus.Pending)))
    assert(db.listExpiredIncomingPayments(0 unixms, now) === Seq(expiredPayment1, expiredPayment2))
    assert(db.listReceivedIncomingPayments(0 unixms, now) === Nil)
    assert(db.listPendingIncomingPayments(0 unixms, now) === Seq(pendingPayment1, pendingPayment2, payment1.copy(status = IncomingPaymentStatus.Pending), payment2.copy(status = IncomingPaymentStatus.Pending)))

    db.receiveIncomingPayment(paidInvoice1.paymentHash, 461 msat, receivedAt1)
    db.receiveIncomingPayment(paidInvoice1.paymentHash, 100 msat, receivedAt2) // adding another payment to this invoice should sum
    db.receiveIncomingPayment(paidInvoice2.paymentHash, 1111 msat, receivedAt2)

    assert(db.getIncomingPayment(paidInvoice1.paymentHash) === Some(payment1))

    assert(db.listIncomingPayments(0 unixms, now) === Seq(expiredPayment1, expiredPayment2, pendingPayment1, pendingPayment2, payment1, payment2))
    assert(db.listIncomingPayments(now - 60.seconds, now) === Seq(pendingPayment1, pendingPayment2, payment1, payment2))
    assert(db.listPendingIncomingPayments(0 unixms, now) === Seq(pendingPayment1, pendingPayment2))
    assert(db.listReceivedIncomingPayments(0 unixms, now) === Seq(payment1, payment2))
    assert(db.listExpiredIncomingPayments(0 unixms, now) === Seq(expiredPayment1, expiredPayment2))

    val interval = 10 seconds
    val _ = system.spawn(InvoicePurger(db, interval), name = "purge-expired-invoices")

    val probe = TestProbe()
    system.eventStream.subscribe(probe.ref, classOf[PurgeEvent])

    probe.expectMsg(20 seconds, PurgeCompleted)
    probe.expectNoMessage()

    assert(db.listExpiredIncomingPayments(now - interval, now).isEmpty)
    assert(db.listIncomingPayments(0 unixms, now) === Seq(pendingPayment1, pendingPayment2, payment1, payment2))
  }
}
