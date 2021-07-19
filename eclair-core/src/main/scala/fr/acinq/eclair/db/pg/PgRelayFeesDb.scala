/*
 * Copyright 2021 ACINQ SAS
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

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.RelayFeesDb
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import grizzled.slf4j.Logging

import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

class PgRelayFeesDb(implicit ds: DataSource, lock: PgLock) extends RelayFeesDb with Logging {

  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import lock._

  val DB_NAME = "relay_fees"
  val CURRENT_VERSION = 1

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>
      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS local")
          statement.executeUpdate("CREATE TABLE local.relay_fees (node_id TEXT NOT NULL, fee_base_msat BIGINT NOT NULL, fee_proportional_millionths BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE INDEX relay_fees_node_id_idx ON local.relay_fees(node_id)")
          statement.executeUpdate("CREATE INDEX relay_fees_timestamp_idx ON local.relay_fees(timestamp)")
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def addOrUpdateFees(nodeId: Crypto.PublicKey, feeBase: MilliSatoshi, feeProportionalMillionths: Long): Unit = withMetrics("relay_fees/add-or-update", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement(
        "INSERT INTO local.relay_fees VALUES (?, ?, ?, ?)")) { statement =>
        statement.setString(1, nodeId.value.toHex)
        statement.setLong(2, feeBase.toLong)
        statement.setLong(3, feeProportionalMillionths)
        statement.setTimestamp(4, Timestamp.from(Instant.now()))
        statement.executeUpdate()
      }
    }
  }

  override def getFees(nodeId: PublicKey): Option[(MilliSatoshi, Long)] = withMetrics("relay_fees/get", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT fee_base_msat, fee_proportional_millionths FROM local.relay_fees WHERE node_id=? ORDER BY timestamp DESC LIMIT 1")) { statement =>
        statement.setString(1, nodeId.value.toHex)
        statement.executeQuery()
          .headOption
          .map(rs =>
            (MilliSatoshi(rs.getLong("fee_base_msat")), rs.getLong("fee_proportional_millionths"))
          )
      }
    }
  }

  override def close(): Unit = ()
}
