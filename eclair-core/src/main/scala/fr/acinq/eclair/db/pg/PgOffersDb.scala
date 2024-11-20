/*
 * Copyright 2024 ACINQ SAS
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
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.{OfferData, OffersDb}
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.wire.protocol.OfferTypes
import grizzled.slf4j.Logging

import java.sql.Statement
import javax.sql.DataSource

object PgOffersDb {
  val DB_NAME = "offers"
  val CURRENT_VERSION = 1
}

class PgOffersDb(implicit ds: DataSource, lock: PgLock) extends OffersDb with Logging {

  import PgPaymentsDb._
  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import lock._

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>
      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA offers")

          statement.executeUpdate("CREATE TABLE offers.managed (offer_id TEXT NOT NULL PRIMARY KEY, offer TEXT NOT NULL, path_id TEXT, created_at TIMESTAMP WITH TIME ZONE NOT NULL, is_active BOOLEAN NOT NULL)")

          statement.executeUpdate("CREATE INDEX offer_is_active_idx ON offers.managed(is_active)")
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def addOffer(offer: OfferTypes.Offer, pathId_opt: Option[ByteVector32]): Unit = withMetrics("offers/add", DbBackends.Postgres){
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO offers.managed (offer_id, offer, path_id, created_at, is_active) VALUES (?, ?, ?, NOW, TRUE)")) { statement =>
        statement.setString(1, offer.offerId.toHex)
        statement.setString(2, offer.toString)
        pathId_opt match {
          case Some(pathId) => statement.setString(3, pathId.toHex)
          case None => statement.setNull(3, java.sql.Types.VARCHAR)
        }

        statement.executeUpdate()
      }
    }
  }

  override def disableOffer(offer: OfferTypes.Offer): Unit = ???

  override def listOffers(onlyActive: Boolean): Seq[OfferData] = ???
}
