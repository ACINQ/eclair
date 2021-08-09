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

package fr.acinq.eclair.db.sqlite

import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.RelayFeesDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{getVersion, setVersion, using}
import fr.acinq.eclair.payment.relay.Relayer.RelayFees

import java.sql.Connection

class SqliteRelayFeesDb(sqlite: Connection) extends RelayFeesDb {

  import SqliteUtils.ExtendedResultSet._

  val DB_NAME = "relay_fees"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement(), inTransaction = true) { statement =>
    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE relay_fees (node_id BLOB NOT NULL, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE INDEX relay_fees_node_id_idx ON relay_fees(node_id)")
        statement.executeUpdate("CREATE INDEX relay_fees_timestamp_idx ON relay_fees(timestamp)")
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addOrUpdateFees(nodeId: Crypto.PublicKey, fees: RelayFees): Unit = withMetrics("relay_fees/add-or-update", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO relay_fees VALUES (?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.setLong(2, fees.feeBase.toLong)
      statement.setLong(3, fees.feeProportionalMillionths)
      statement.setLong(4, System.currentTimeMillis)
      statement.executeUpdate()
    }
  }

  override def getFees(nodeId: PublicKey): Option[RelayFees] = withMetrics("relay_fees/get", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT fee_base_msat, fee_proportional_millionths FROM relay_fees WHERE node_id=? ORDER BY timestamp DESC LIMIT 1")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeQuery()
        .headOption
        .map(rs =>
          RelayFees(MilliSatoshi(rs.getLong("fee_base_msat")), rs.getLong("fee_proportional_millionths"))
        )
    }
  }

  override def close(): Unit = sqlite.close()
}
