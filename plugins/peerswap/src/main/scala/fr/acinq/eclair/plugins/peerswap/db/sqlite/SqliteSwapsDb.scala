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

package fr.acinq.eclair.plugins.peerswap.db.sqlite

import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.plugins.peerswap.SwapData
import fr.acinq.eclair.plugins.peerswap.SwapEvents.SwapEvent
import fr.acinq.eclair.plugins.peerswap.db.SwapsDb
import fr.acinq.eclair.plugins.peerswap.db.SwapsDb.{getSwapData, setSwapData}
import grizzled.slf4j.Logging

import java.sql.Connection

object SqliteSwapsDb {
  val DB_NAME = "swaps"
  val CURRENT_VERSION = 1
}

class SqliteSwapsDb (val sqlite: Connection) extends SwapsDb with Logging {

  import fr.acinq.eclair.db.sqlite.SqliteUtils._
  import ExtendedResultSet._
  import SqliteSwapsDb._

  using(sqlite.createStatement(), inTransaction = true) { statement =>
    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE swaps (swap_id STRING NOT NULL PRIMARY KEY, request STRING NOT NULL, agreement STRING NOT NULL, invoice STRING NOT NULL, opening_tx_broadcasted STRING NOT NULL, swap_role INTEGER NOT NULL, is_initiator BOOLEAN NOT NULL, result STRING NOT NULL)")
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def add(swapData: SwapData): Unit = withMetrics("swaps/add", DbBackends.Sqlite) {
      using(sqlite.prepareStatement(
        """INSERT INTO swaps (swap_id, request, agreement, invoice, opening_tx_broadcasted, swap_role, is_initiator, result)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (swap_id) DO NOTHING""")) { statement =>
        setSwapData(statement, swapData)
        statement.executeUpdate()
      }
    }

  override def addResult(swapEvent: SwapEvent): Unit = withMetrics("swaps/add_result", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE swaps SET result=? WHERE swap_id=?")) { statement =>
      statement.setString(1, swapEvent.toString)
      statement.setString(2, swapEvent.swapId)
      statement.executeUpdate()
    }
  }

  override def remove(swapId: String): Unit = withMetrics("swaps/remove", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("DELETE FROM swaps WHERE swap_id=?")) { statement =>
      statement.setString(1, swapId)
      statement.executeUpdate()
    }
  }

  override def restore(): Seq[SwapData] = withMetrics("swaps/restore", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT swap_id, request, agreement, invoice, opening_tx_broadcasted, swap_role, is_initiator, result FROM swaps WHERE result=?")) { statement =>
      statement.setString(1, "")
      statement.executeQuery().map(rs => getSwapData(rs)).toSeq
    }
  }

  override def list(): Seq[SwapData] = withMetrics("swaps/list", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT swap_id, request, agreement, invoice, opening_tx_broadcasted, swap_role, is_initiator, result FROM swaps")) { statement =>
      statement.executeQuery().map(rs => getSwapData(rs)).toSeq
    }
  }

}