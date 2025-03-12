/*
 * Copyright 2025 ACINQ SAS
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

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.TimestampMilli
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.db.{OfferData, OffersDb}
import fr.acinq.eclair.wire.protocol.OfferTypes
import fr.acinq.eclair.wire.protocol.OfferTypes.Offer
import grizzled.slf4j.Logging

import java.sql.ResultSet
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
          statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS payments")
          statement.executeUpdate("CREATE TABLE payments.offers (offer_id TEXT NOT NULL PRIMARY KEY, offer TEXT NOT NULL, path_id TEXT, created_at TIMESTAMP WITH TIME ZONE NOT NULL, is_active BOOLEAN NOT NULL, disabled_at TIMESTAMP WITH TIME ZONE)")
          statement.executeUpdate("CREATE INDEX offer_created_at_idx ON payments.offers(created_at)")
          statement.executeUpdate("CREATE INDEX offer_is_active_idx ON payments.offers(is_active)")
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def addOffer(offer: OfferTypes.Offer, pathId_opt: Option[ByteVector32], createdAt: TimestampMilli = TimestampMilli.now()): Option[OfferData] = withMetrics("offers/add", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO payments.offers (offer_id, offer, path_id, created_at, is_active, disabled_at) VALUES (?, ?, ?, ?, TRUE, NULL) ON CONFLICT DO NOTHING")) { statement =>
        statement.setString(1, offer.offerId.toHex)
        statement.setString(2, offer.toString)
        statement.setString(3, pathId_opt.map(_.toHex).orNull)
        statement.setTimestamp(4, createdAt.toSqlTimestamp)
        if (statement.executeUpdate() == 1) {
          Some(OfferData(offer, pathId_opt, createdAt, disabledAt_opt = None))
        } else {
          None
        }
      }
    }
  }

  override def disableOffer(offer: OfferTypes.Offer, disabledAt: TimestampMilli = TimestampMilli.now()): Unit = withMetrics("offers/disable", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("UPDATE payments.offers SET disabled_at = ?, is_active = FALSE WHERE offer_id = ?")) { statement =>
        statement.setTimestamp(1, disabledAt.toSqlTimestamp)
        statement.setString(2, offer.offerId.toHex)
        statement.executeUpdate()
      }
    }
  }

  private def parseOfferData(rs: ResultSet): OfferData = {
    OfferData(
      Offer.decode(rs.getString("offer")).get,
      rs.getStringNullable("path_id").map(ByteVector32.fromValidHex),
      TimestampMilli.fromSqlTimestamp(rs.getTimestamp("created_at")),
      rs.getTimestampNullable("disabled_at").map(TimestampMilli.fromSqlTimestamp)
    )
  }

  override def listOffers(onlyActive: Boolean): Seq[OfferData] = withMetrics("offers/list", DbBackends.Postgres) {
    withLock { pg =>
      if (onlyActive) {
        using(pg.prepareStatement("SELECT * FROM payments.offers WHERE is_active = TRUE ORDER BY created_at DESC")) { statement =>
          statement.executeQuery().map(parseOfferData).toSeq
        }
      } else {
        using(pg.prepareStatement("SELECT * FROM payments.offers ORDER BY created_at DESC")) { statement =>
          statement.executeQuery().map(parseOfferData).toSeq
        }
      }
    }
  }
}
