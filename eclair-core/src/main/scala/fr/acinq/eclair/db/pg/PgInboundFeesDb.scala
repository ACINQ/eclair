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

import fr.acinq.bitcoin.scalacompat.Crypto
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.db.InboundFeesDb
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.payment.relay.Relayer.InboundFees
import grizzled.slf4j.Logging

import javax.sql.DataSource

object PgInboundFeesDb {
  val DB_NAME = "inboundfees"
  val CURRENT_VERSION = 1
}

class PgInboundFeesDb(implicit ds: DataSource, lock: PgLock) extends InboundFeesDb with Logging {

  import PgUtils._
  import ExtendedResultSet._
  import PgInboundFeesDb._
  import lock._

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>
      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA inboundfees")
          statement.executeUpdate("CREATE TABLE inboundfees.inbound_fees (node_id TEXT NOT NULL PRIMARY KEY, fee_base_msat BIGINT NOT NULL, fee_proportional_millionths BIGINT NOT NULL)")
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def addOrUpdateInboundFees(nodeId: Crypto.PublicKey, fees: InboundFees): Unit = withMetrics("inbound-fees/add-or-update-inbound-fees", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement(
        """
      INSERT INTO inboundfees.inbound_fees (node_id, fee_base_msat, fee_proportional_millionths)
      VALUES (?, ?, ?)
      ON CONFLICT (node_id)
      DO UPDATE SET fee_base_msat = EXCLUDED.fee_base_msat, fee_proportional_millionths = EXCLUDED.fee_proportional_millionths
      """)) { statement =>
        statement.setString(1, nodeId.value.toHex)
        statement.setLong(2, fees.feeBase.toLong)
        statement.setLong(3, fees.feeProportionalMillionths)
        statement.executeUpdate()
      }
    }
  }

  override def getInboundFees(nodeId: Crypto.PublicKey): Option[InboundFees] = withMetrics("inbound-fees/get-inbound-fees", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT fee_base_msat, fee_proportional_millionths FROM inboundfees.inbound_fees WHERE node_id=?")) { statement =>
        statement.setString(1, nodeId.value.toHex)
        statement.executeQuery()
          .headOption
          .map(rs =>
            InboundFees(MilliSatoshi(rs.getLong("fee_base_msat")), rs.getLong("fee_proportional_millionths"))
          )
      }
    }
  }

  override def unsetInboundFees(nodeId: Crypto.PublicKey): Unit = withMetrics("inbound-fees/unset-inbound-fees", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("DELETE FROM inboundfees.inbound_fees WHERE node_id=?")) { statement =>
        statement.setString(1, nodeId.value.toHex)
        statement.executeUpdate()
      }
    }
  }
}