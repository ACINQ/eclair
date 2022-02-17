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
import fr.acinq.bitcoin.Block
import fr.acinq.eclair.TestDatabases.TestSqliteDatabases
import fr.acinq.eclair.db.{IncomingPayment, IncomingPaymentStatus, PaymentType, PaymentsDbSpec}
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.payment.receive.InvoicePurger.{PurgeCompleted, PurgeEvent}
import fr.acinq.eclair.{CltvExpiryDelta, MilliSatoshiLong, TimestampMilli, TimestampMilliLong, TimestampSecond, TimestampSecondLong, randomBytes32}
import org.scalatest.funsuite.AnyFunSuiteLike

import scala.concurrent.duration.DurationInt

class InvoicePurgerSpec extends ScalaTestWithActorTestKit(ConfigFactory.load("application")) with AnyFunSuiteLike {

  import PaymentsDbSpec._

  test("purge invoices") {
    val dbs = TestSqliteDatabases()
    val db = dbs.db.payments
    val count = 10

    // create expired invoices
    val expiredInvoices = Seq.fill(count)(Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(100 msat), randomBytes32(), alicePriv, Left("expired invoice"), CltvExpiryDelta(18),
      timestamp = 1 unixsec))
    val expiredPayments = expiredInvoices.map(invoice => IncomingPayment(invoice, randomBytes32(), PaymentType.Standard, invoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired))
    expiredPayments.foreach(payment => db.addIncomingPayment(payment.invoice, payment.paymentPreimage))

    // create pending invoices
    val pendingInvoices = Seq.fill(count)(Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(100 msat), randomBytes32(), alicePriv, Left("pending invoice"), CltvExpiryDelta(18),
      timestamp = TimestampSecond.now() - 600))
    val pendingPayments = pendingInvoices.map(invoice => IncomingPayment(invoice, randomBytes32(), PaymentType.Standard, invoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Pending))
    pendingPayments.foreach(payment => db.addIncomingPayment(payment.invoice, payment.paymentPreimage))

    // create paid invoices
    val receivedAt = TimestampMilli.now() + 1.milli
    val paidInvoices = Seq.fill(count)(Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(100 msat), randomBytes32(), alicePriv, Left("paid invoice"), CltvExpiryDelta(18),
      timestamp = TimestampSecond.now()))
    val paidPayments = paidInvoices.map(invoice => IncomingPayment(invoice, randomBytes32(), PaymentType.Standard, invoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Received(100 msat, receivedAt)))
    paidPayments.foreach(payment => {
      db.addIncomingPayment(payment.invoice, payment.paymentPreimage)
      // receive payment
      db.receiveIncomingPayment(payment.invoice.paymentHash, 100 msat, receivedAt)
    })

    val now = TimestampMilli.now()
    assert(db.listIncomingPayments(0 unixms, now) === expiredPayments ++ pendingPayments ++ paidPayments)
    assert(db.listIncomingPayments(expiredInvoices.last.createdAt.toTimestampMilli, now) === pendingPayments ++ paidPayments)
    assert(db.listPendingIncomingPayments(0 unixms, now) === pendingPayments)
    assert(db.listReceivedIncomingPayments(0 unixms, now) === paidPayments)
    assert(db.listExpiredIncomingPayments(0 unixms, now) === expiredPayments)

    val interval = 1 seconds

    val probe = testKit.createTestProbe[PurgeEvent]()
    system.eventStream ! EventStream.Subscribe(probe.ref)

    val _ = testKit.spawn(InvoicePurger(db, interval), name = "purge-expired-invoices")

    // check that purge completed
    probe.expectMessage(3 seconds, PurgeCompleted)
    probe.expectNoMessage()
    assert(db.listExpiredIncomingPayments(0 unixms, now).isEmpty)
    assert(db.listIncomingPayments(0 unixms, now) === pendingPayments ++ paidPayments)

    // add more expired invoices
    val expiredInvoices2 = Seq.fill(count)(Bolt11Invoice(Block.TestnetGenesisBlock.hash, Some(100 msat), randomBytes32(), alicePriv, Left("expired invoice2"), CltvExpiryDelta(18),
      timestamp = 2 unixsec))
    val expiredPayments2 = expiredInvoices2.map(invoice => IncomingPayment(invoice, randomBytes32(), PaymentType.Standard, invoice.createdAt.toTimestampMilli, IncomingPaymentStatus.Expired))
    expiredPayments2.foreach(payment => db.addIncomingPayment(payment.invoice, payment.paymentPreimage))

    // check that purge still running
    probe.expectMessage(3 seconds, PurgeCompleted)
    probe.expectNoMessage()
    assert(db.listExpiredIncomingPayments(0 unixms, TimestampMilli.now()).isEmpty)
    assert(db.listIncomingPayments(0 unixms, TimestampMilli.now()) === pendingPayments ++ paidPayments)
  }
}
