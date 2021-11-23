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

package fr.acinq.eclair.db

import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases, forAllDbs}
import fr.acinq.eclair.crypto.Sphinx.RouteBlinding
import fr.acinq.eclair.db.pg.PgOffersDb
import fr.acinq.eclair.db.sqlite.SqliteOffersDb
import fr.acinq.eclair.payment.{Bolt12Invoice, PaymentBlindedRoute}
import fr.acinq.eclair.wire.protocol.OfferTypes.{InvoiceRequest, Offer, PaymentInfo}
import fr.acinq.eclair.{CltvExpiryDelta, Features, MilliSatoshiLong, randomBytes, randomBytes32, randomKey}
import org.scalatest.funsuite.AnyFunSuite

import java.util.UUID
import scala.concurrent.duration.DurationInt


class OffersDbSpec extends AnyFunSuite {

  test("init database two times in a row") {
    forAllDbs {
      case sqlite: TestSqliteDatabases =>
        new SqliteOffersDb(sqlite.connection)
        new SqliteOffersDb(sqlite.connection)
      case pg: TestPgDatabases =>
        new PgOffersDb()(pg.datasource, pg.lock)
        new PgOffersDb()(pg.datasource, pg.lock)
    }
  }

  test("add attempts to pay offers and list them") {
    forAllDbs { dbs =>
      val db = dbs.offers

      val (a, b, c, d, e, f, g) = (randomKey(), randomKey(), randomKey(), randomKey(), randomKey(), randomKey(), randomKey())
      val (u, v, w, x, y, z) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
      val chain = randomBytes32()
      val offer1 = Offer(None, "offer1", a.publicKey, Features.empty, chain)
      val offer2 = Offer(Some(10_000 msat), "offer2", b.publicKey, Features.empty, chain)
      val offer3 = Offer(None, "offer3", c.publicKey, Features.empty, chain)
      val request1 = InvoiceRequest(offer1, 500_000 msat, 1, Features.empty, d, chain)
      val request2 = InvoiceRequest(offer1, 400_000 msat, 1, Features.empty, e, chain)
      val request3 = InvoiceRequest(offer2, 300_000 msat, 1, Features.empty, f, chain)
      val request4 = InvoiceRequest(offer3, 200_000 msat, 1, Features.empty, g, chain)
      val dummyRoute = PaymentBlindedRoute(RouteBlinding.create(randomKey(), Seq(randomKey().publicKey), Seq(randomBytes(5))).route, PaymentInfo(1 msat, 2, CltvExpiryDelta(3), 4 msat, 5 msat, Features.empty))
      val invoice2 = Bolt12Invoice(request2, randomBytes32(), a, 300 seconds, Features.empty, Seq(dummyRoute))
      val invoice3 = Bolt12Invoice(request3, randomBytes32(), b, 300 seconds, Features.empty, Seq(dummyRoute))
      val invoice4 = Bolt12Invoice(request4, randomBytes32(), a, 300 seconds, Features.empty, Seq(dummyRoute))

      assert(db.getAttemptToPayOffer(u).isEmpty)
      assert(db.getAttemptToPayOffer(v).isEmpty)
      assert(db.getAttemptToPayOffer(w).isEmpty)
      assert(db.getAttemptToPayOffer(x).isEmpty)
      db.addAttemptToPayOffer(offer1, request1, d, u)
      assert(db.getAttemptToPayOffer(u).contains(AttemptToPayOffer(u, offer1, request1, d, None, None)))
      db.addAttemptToPayOffer(offer1, request2, e, v)
      assert(db.getAttemptToPayOffer(v).contains(AttemptToPayOffer(v, offer1, request2, e, None, None)))
      db.addOfferInvoice(u, None, None)
      db.addOfferInvoice(v, Some(invoice2), Some(y))
      assert(db.getAttemptsToPayOffer(offer1) == Seq(AttemptToPayOffer(u, offer1, request1, d, None, None), AttemptToPayOffer(v, offer1, request2, e, Some(invoice2), Some(y))))
      db.addAttemptToPayOffer(offer2, request3, f, w)
      assert(db.getAttemptToPayOffer(w).contains(AttemptToPayOffer(w, offer2, request3, f, None, None)))
      db.addOfferInvoice(w, Some(invoice3), Some(z))
      assert(db.getAttemptsToPayOffer(offer2) == Seq(AttemptToPayOffer(w, offer2, request3, f, Some(invoice3), Some(z))))
      db.addAttemptToPayOffer(offer3, request4, g, x)
      assert(db.getAttemptToPayOffer(x).contains(AttemptToPayOffer(x, offer3, request4, g, None, None)))
      db.addOfferInvoice(x, Some(invoice4), None)
      assert(db.getAttemptToPayOffer(x).contains(AttemptToPayOffer(x, offer3, request4, g, Some(invoice4), None)))
      assertThrows[IllegalArgumentException](db.addOfferInvoice(x, Some(invoice4), None))
    }
  }
}


