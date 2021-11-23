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

package fr.acinq.eclair.db.sqlite

import fr.acinq.bitcoin.scalacompat.Crypto.PrivateKey
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.sqlite.SqliteUtils._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.wire.protocol.OfferTypes
import fr.acinq.eclair.wire.protocol.OfferTypes.InvoiceRequest
import fr.acinq.eclair.{MilliSatoshi, TimestampMilli}
import grizzled.slf4j.Logging

import java.sql.{Connection, ResultSet}
import java.util.UUID

class SqliteOffersDb(val sqlite: Connection) extends OffersDb with Logging {

  import SqliteOffersDb._
  import SqliteUtils.ExtendedResultSet._

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE attempts_to_pay_offer (id TEXT NOT NULL PRIMARY KEY, offer TEXT NOT NULL, request TEXT NOT NULL, payer_key BLOB NOT NULL, invoice TEXT, payment_id TEXT, created_at INTEGER NOT NULL, completed_at INTEGER)")
        statement.executeUpdate("CREATE INDEX attempts_offer_idx ON attempts_to_pay_offer(offer)")
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)

  }

  override def addAttemptToPayOffer(offer: OfferTypes.Offer, request: InvoiceRequest, payerKey: PrivateKey, attemptId: UUID): Unit = withMetrics("offers/add-attempt-to-pay", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO attempts_to_pay_offer (id, offer, request, payer_key, created_at) VALUES (?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, attemptId.toString)
      statement.setString(2, offer.toString)
      statement.setString(3, request.toString)
      statement.setBytes(4, payerKey.value.toArray)
      statement.setLong(5, TimestampMilli.now().toLong)
      statement.executeUpdate()
    }
  }

  def parseAttemptToPayOffer(rs: ResultSet): AttemptToPayOffer =
    AttemptToPayOffer(
      UUID.fromString(rs.getString("id")),
      OfferTypes.Offer.decode(rs.getString("offer")).get,
      InvoiceRequest.decode(rs.getString("request")).get,
      PrivateKey(rs.getByteVector("payer_key")),
      rs.getStringNullable("invoice").map(Bolt12Invoice.fromString(_).get),
      rs.getStringNullable("payment_id").map(UUID.fromString),
    )

  override def getAttemptToPayOffer(attemptId: UUID): Option[AttemptToPayOffer] = withMetrics("offers/get-attempt-to-pay-by-id", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM attempts_to_pay_offer WHERE id = ?")) { statement =>
      statement.setString(1, attemptId.toString)
      statement.executeQuery().map(parseAttemptToPayOffer).headOption
    }
  }

  override def getAttemptsToPayOffer(offer: OfferTypes.Offer): Seq[AttemptToPayOffer] = withMetrics("offers/get-attempts-to-pay-by-offer", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM attempts_to_pay_offer WHERE offer = ? ORDER BY created_at")) { statement =>
      statement.setString(1, offer.toString)
      statement.executeQuery().map(parseAttemptToPayOffer).toSeq
    }
  }

  override def addOfferInvoice(attemptId: UUID, invoice: Option[Bolt12Invoice], paymentId_opt: Option[UUID]): Unit = withMetrics("offers/add-offer-invoice", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE attempts_to_pay_offer SET (completed_at, invoice, payment_id) = (?, ?, ?) WHERE id = ? AND completed_at IS NULL")) { statement =>
      statement.setLong(1, TimestampMilli.now().toLong)
      statement.setString(2, invoice.map(_.toString).orNull)
      statement.setString(3, paymentId_opt.map(_.toString).orNull)
      statement.setString(4, attemptId.toString)
      if (statement.executeUpdate() == 0) throw new IllegalArgumentException(s"Tried to add an invoice to an attempt to pay an offer but it is already already marked completed (attemptId=$attemptId)")
    }
  }
}

object SqliteOffersDb {
  val DB_NAME = "offers"
  val CURRENT_VERSION = 1
}
