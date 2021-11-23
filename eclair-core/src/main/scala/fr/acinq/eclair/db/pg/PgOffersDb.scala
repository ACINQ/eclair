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

package fr.acinq.eclair.db.pg

import fr.acinq.bitcoin.scalacompat.Crypto
import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.payment._
import fr.acinq.eclair.wire.protocol.OfferTypes
import fr.acinq.eclair.wire.protocol.OfferTypes.InvoiceRequest
import fr.acinq.eclair.{MilliSatoshi, TimestampMilli}
import grizzled.slf4j.Logging

import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

object PgOffersDb {
  val DB_NAME = "offers"
  val CURRENT_VERSION = 1
}

class PgOffersDb(implicit ds: DataSource, lock: PgLock) extends OffersDb with Logging {

  import PgOffersDb._
  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import lock._

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>

      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA offers")
          statement.executeUpdate("CREATE TABLE offers.attempts_to_pay (id TEXT NOT NULL PRIMARY KEY, offer TEXT NOT NULL, request TEXT NOT NULL, payer_key TEXT NOT NULL, invoice TEXT, payment_id TEXT, created_at TIMESTAMP WITH TIME ZONE NOT NULL, completed_at TIMESTAMP WITH TIME ZONE)")
          statement.executeUpdate("CREATE INDEX attempts_offer_idx ON offers.attempts_to_pay(offer)")
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def addAttemptToPayOffer(offer: OfferTypes.Offer, request: InvoiceRequest, payerKey: Crypto.PrivateKey, attemptId: UUID): Unit = withMetrics("offers/add-attempt-to-pay", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO offers.attempts_to_pay (id, offer, request, payer_key, created_at) VALUES (?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, attemptId.toString)
        statement.setString(2, offer.toString)
        statement.setString(3, request.toString)
        statement.setString(4, payerKey.toHex)
        statement.setTimestamp(5, TimestampMilli.now().toSqlTimestamp)
        statement.executeUpdate()
      }
    }
  }

  def parseAttemptToPayOffer(rs: ResultSet): AttemptToPayOffer =
    AttemptToPayOffer(
      UUID.fromString(rs.getString("id")),
      OfferTypes.Offer.decode(rs.getString("offer")).get,
      InvoiceRequest.decode(rs.getString("request")).get,
      PrivateKey(rs.getByteVectorFromHex("payer_key")),
      rs.getStringNullable("invoice").map(Bolt12Invoice.fromString(_).get),
      rs.getStringNullable("payment_id").map(UUID.fromString),
    )

  override def getAttemptToPayOffer(attemptId: UUID): Option[AttemptToPayOffer] = withMetrics("offers/get-attempt-to-pay-by-id", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM offers.attempts_to_pay WHERE id = ?")) { statement =>
        statement.setString(1, attemptId.toString)
        statement.executeQuery().map(parseAttemptToPayOffer).headOption
      }
    }
  }

  override def getAttemptsToPayOffer(offer: OfferTypes.Offer): Seq[AttemptToPayOffer] = withMetrics("offers/get-attempts-to-pay-by-offer", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM offers.attempts_to_pay WHERE offer = ? ORDER BY created_at")) { statement =>
        statement.setString(1, offer.toString)
        statement.executeQuery().map(parseAttemptToPayOffer).toSeq
      }
    }
  }

  override def addOfferInvoice(attemptId: UUID, invoice: Option[Bolt12Invoice], paymentId_opt: Option[UUID]): Unit = withMetrics("offers/add-offer-invoice", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("UPDATE offers.attempts_to_pay SET (completed_at, invoice, payment_id) = (?, ?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
        statement.setTimestamp(1, TimestampMilli.now().toSqlTimestamp)
        statement.setString(2, invoice.map(_.toString).orNull)
        statement.setString(3, paymentId_opt.map(_.toString).orNull)
        statement.setString(4, attemptId.toString)
        if (statement.executeUpdate() == 0) throw new IllegalArgumentException(s"Tried to add an invoice to an attempt to pay an offer but it is already already marked completed (attemptId=$attemptId)")
      }
    }
  }
}