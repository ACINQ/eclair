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

package fr.acinq.eclair.db.sqlite

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.TimestampMilli
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.sqlite.SqliteUtils.{getVersion, setVersion, using}
import fr.acinq.eclair.db.{OfferData, OffersDb}
import fr.acinq.eclair.wire.protocol.OfferTypes
import fr.acinq.eclair.wire.protocol.OfferTypes.Offer
import grizzled.slf4j.Logging

import java.sql.{Connection, ResultSet}

object SqliteOffersDb {
  val DB_NAME = "offers"
  val CURRENT_VERSION = 1
}

class SqliteOffersDb(val sqlite: Connection) extends OffersDb with Logging {
  import SqliteOffersDb._
  import SqliteUtils.ExtendedResultSet._

  using(sqlite.createStatement(), inTransaction = true) { statement =>
    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE managed_offers (offer_id BLOB NOT NULL PRIMARY KEY, offer TEXT NOT NULL, path_id BLOB, created_at INTEGER NOT NULL, is_active INTEGER NOT NULL)")
        statement.executeUpdate("CREATE INDEX offer_is_active_idx ON managed_offers(is_active)")
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)

  }

  override def addOffer(offer: OfferTypes.Offer, pathId_opt: Option[ByteVector32]): Unit = withMetrics("offers/add", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO managed_offers (offer_id, offer, path_id, created_at, is_active) VALUES (?, ?, ?, ?, TRUE)")) { statement =>
      statement.setBytes(1, offer.offerId.toArray)
      statement.setString(2, offer.toString)
      pathId_opt match {
        case Some(pathId) => statement.setBytes(3, pathId.toArray)
        case None => statement.setNull(3, java.sql.Types.VARBINARY)
      }
      statement.setLong(4, TimestampMilli.now().toLong)
      statement.executeUpdate()
    }
  }

  override def disableOffer(offer: OfferTypes.Offer): Unit = withMetrics("offers/disable", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE managed_offers SET is_active = FALSE WHERE offer_id = ?")) { statement =>
      statement.setBytes(1, offer.offerId.toArray)
      statement.executeUpdate()
    }
  }

  override def enableOffer(offer: OfferTypes.Offer): Unit = withMetrics("offers/enable", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE managed_offers SET is_active = TRUE WHERE offer_id = ?")) { statement =>
      statement.setBytes(1, offer.offerId.toArray)
      statement.executeUpdate()
    }
  }

  private def parseOfferData(rs: ResultSet): OfferData = {
    OfferData(
      Offer.decode(rs.getString("offer")).get,
      rs.getByteVector32Nullable("path_id"),
      TimestampMilli(rs.getLong("created_at")),
      rs.getBoolean("is_active")
    )
  }

  override def listOffers(onlyActive: Boolean): Seq[OfferData] = withMetrics("offers/list", DbBackends.Sqlite) {
    if (onlyActive) {
      using(sqlite.prepareStatement("SELECT * FROM managed_offers WHERE is_active = TRUE")) { statement =>
        statement.executeQuery().map(parseOfferData).toSeq
      }
    } else {
      using(sqlite.prepareStatement("SELECT * FROM managed_offers")) { statement =>
        statement.executeQuery().map(parseOfferData).toSeq
      }
    }
  }
}
