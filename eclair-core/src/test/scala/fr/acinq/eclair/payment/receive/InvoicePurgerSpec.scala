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

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.eventstream.EventStream
import com.typesafe.config.ConfigFactory
import fr.acinq.bitcoin.scalacompat.Block
import fr.acinq.eclair.TestDatabases.TestSqliteDatabases
import fr.acinq.eclair.db.{IncomingPaymentStatus, IncomingStandardPayment, PaymentType, PaymentsDbSpec}
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.payment.receive.InvoicePurger.{PurgeCompleted, PurgeEvent}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshiLong, TimestampMilli, TimestampMilliLong, TimestampSecond, TimestampSecondLong, randomBytes32}
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class InvoicePurgerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  import PaymentsDbSpec._

  test("purge invoices on startup") {
    val dbs = TestSqliteDatabases()
    val db = dbs.db.payments
    val count = 10

    // create expired invoices
    val expiredInvoices = Seq.fill(count)(Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(100 msat), randomBytes32(), alicePriv, Left("expired invoice"), CltvExpiryDelta(18),
      timestamp = 1 unixsec))
    val expiredPayments = expiredInvoices.map(invoice => IncomingStandardPayment(invoice, randomBytes32(), PaymentType.Standard, invoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired))
    expiredPayments.foreach(payment => db.addIncomingPayment(payment.invoice, payment.paymentPreimage))

    // create pending invoices
    val pendingInvoices = Seq.fill(count)(Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(100 msat), randomBytes32(), alicePriv, Left("pending invoice"), CltvExpiryDelta(18)))
    val pendingPayments = pendingInvoices.map(invoice => IncomingStandardPayment(invoice, randomBytes32(), PaymentType.Standard, invoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Pending))
    pendingPayments.foreach(payment => db.addIncomingPayment(payment.invoice, payment.paymentPreimage))

    // create paid invoices
    val receivedAt = TimestampMilli.now() + 1.milli
    val paidInvoices = Seq.fill(count)(Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(100 msat), randomBytes32(), alicePriv, Left("paid invoice"), CltvExpiryDelta(18)))
    val paidPayments = paidInvoices.map(invoice => IncomingStandardPayment(invoice, randomBytes32(), PaymentType.Standard, invoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(100 msat, receivedAt)))
    paidPayments.foreach(payment => {
      db.addIncomingPayment(payment.invoice, payment.paymentPreimage)
      // receive payment
      db.receiveIncomingPayment(payment.invoice.paymentHash, 100 msat, receivedAt)
    })

    val now = TimestampMilli.now()
    assert(db.listIncomingPayments(0 unixms, now, None) == expiredPayments ++ pendingPayments ++ paidPayments)
    assert(db.listIncomingPayments(now - 100.days, now, None) == pendingPayments ++ paidPayments)
    assert(db.listPendingIncomingPayments(0 unixms, now, None) == pendingPayments)
    assert(db.listReceivedIncomingPayments(0 unixms, now) == paidPayments)
    assert(db.listExpiredIncomingPayments(0 unixms, now) == expiredPayments)

    val probe = testKit.createTestProbe[PurgeEvent]()
    system.eventStream ! EventStream.Subscribe(probe.ref)

    val purger = testKit.spawn(InvoicePurger(db, 24.hours), name = "purge-expired-invoices")

    // check that purge runs before the default first interval of 24 hours
    probe.expectMessage(5 seconds, PurgeCompleted)
    probe.expectNoMessage()
    assert(db.listExpiredIncomingPayments(0 unixms, now).isEmpty)
    assert(db.listIncomingPayments(0 unixms, now, None) == pendingPayments ++ paidPayments)

    testKit.stop(purger)
  }

  test("purge invoices after interval") {
    val dbs = TestSqliteDatabases()
    val db = dbs.db.payments
    val interval = 5 seconds

    // add an expired invoice from before the 15 days look back period
    val expiredInvoice1 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(100 msat), randomBytes32(), alicePriv, Left("expired invoice2"), CltvExpiryDelta(18),
      timestamp = 5 unixsec)
    val expiredPayment1 = IncomingStandardPayment(expiredInvoice1, randomBytes32(), PaymentType.Standard, expiredInvoice1.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired)
    db.addIncomingPayment(expiredPayment1.invoice, expiredPayment1.paymentPreimage)

    // add an expired invoice from after the 15 day look back period
    val expiredInvoice2 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(100 msat), randomBytes32(), alicePriv, Left("expired invoice2"), CltvExpiryDelta(18),
      timestamp = TimestampSecond.now() - 10.days)
    val expiredPayment2 = IncomingStandardPayment(expiredInvoice2, randomBytes32(), PaymentType.Standard, expiredInvoice2.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired)
    db.addIncomingPayment(expiredPayment2.invoice, expiredPayment2.paymentPreimage)

    val probe = testKit.createTestProbe[PurgeEvent]()
    system.eventStream ! EventStream.Subscribe(probe.ref)

    val purger = testKit.spawn(InvoicePurger(db, interval), name = "purge-expired-invoices")

    // check that the initial purge scanned the entire database
    probe.expectMessage(10 seconds, PurgeCompleted)
    probe.expectNoMessage()
    assert(db.listExpiredIncomingPayments(0 unixms, TimestampMilli.now()).isEmpty)

    // add an expired invoice from before the 15 days look back period
    val expiredInvoice3 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(100 msat), randomBytes32(), alicePriv, Left("expired invoice3"), CltvExpiryDelta(18),
      timestamp = 5 unixsec)
    val expiredPayment3 = IncomingStandardPayment(expiredInvoice3, randomBytes32(), PaymentType.Standard, expiredInvoice3.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired)
    db.addIncomingPayment(expiredPayment3.invoice, expiredPayment3.paymentPreimage)

    // add another expired invoice from after the 15 day look back period
    val expiredInvoice4 = Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(100 msat), randomBytes32(), alicePriv, Left("expired invoice4"), CltvExpiryDelta(18),
      timestamp = TimestampSecond.now() - 10.days)
    val expiredPayment4 = IncomingStandardPayment(expiredInvoice4, randomBytes32(), PaymentType.Standard, expiredInvoice4.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired)
    db.addIncomingPayment(expiredPayment4.invoice, expiredPayment4.paymentPreimage)

    // check that subsequent purge runs do not go back > 15 days
    probe.expectMessage(10 seconds, PurgeCompleted)
    probe.expectNoMessage()
    assert(db.listExpiredIncomingPayments(0 unixms, TimestampMilli.now()) == Seq(expiredPayment3))

    testKit.stop(purger)
  }
}
