/*
 * Copyright 2020 ACINQ SAS
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

import java.sql.Connection

import fr.acinq.eclair.blockchain.fee.FeeratesPerKB
import fr.acinq.eclair.db.FeeratesDb


class SqliteFeeratesDb(sqlite: Connection) extends FeeratesDb {

  import SqliteUtils._

  val DB_NAME = "feerates"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement(), inTransaction = true) { statement =>
    getVersion(statement, DB_NAME, CURRENT_VERSION) match {
      case CURRENT_VERSION =>
        // Create feerates table. Rates are in kb.
        statement.executeUpdate(
          """
            |CREATE TABLE IF NOT EXISTS feerates_per_kb (
            |rate_block_1 INTEGER NOT NULL, rate_blocks_2 INTEGER NOT NULL, rate_blocks_6 INTEGER NOT NULL, rate_blocks_12 INTEGER NOT NULL, rate_blocks_36 INTEGER NOT NULL, rate_blocks_72 INTEGER NOT NULL, rate_blocks_144 INTEGER NOT NULL,
            |timestamp INTEGER NOT NULL)""".stripMargin)
      case unknownVersion => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
  }

  override def addOrUpdateFeerates(feeratesPerKB: FeeratesPerKB): Unit = {
    using(sqlite.prepareStatement("UPDATE feerates_per_kb SET rate_block_1=?, rate_blocks_2=?, rate_blocks_6=?, rate_blocks_12=?, rate_blocks_36=?, rate_blocks_72=?, rate_blocks_144=?, timestamp=?")) { update =>
      update.setLong(1, feeratesPerKB.block_1)
      update.setLong(2, feeratesPerKB.blocks_2)
      update.setLong(3, feeratesPerKB.blocks_6)
      update.setLong(4, feeratesPerKB.blocks_12)
      update.setLong(5, feeratesPerKB.blocks_36)
      update.setLong(6, feeratesPerKB.blocks_72)
      update.setLong(7, feeratesPerKB.blocks_144)
      update.setLong(8, System.currentTimeMillis())
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO feerates_per_kb VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { insert =>
          insert.setLong(1, feeratesPerKB.block_1)
          insert.setLong(2, feeratesPerKB.blocks_2)
          insert.setLong(3, feeratesPerKB.blocks_6)
          insert.setLong(4, feeratesPerKB.blocks_12)
          insert.setLong(5, feeratesPerKB.blocks_36)
          insert.setLong(6, feeratesPerKB.blocks_72)
          insert.setLong(7, feeratesPerKB.blocks_144)
          insert.setLong(8, System.currentTimeMillis())
          insert.executeUpdate()
        }
      }
    }
  }

  override def getFeerates(): Option[FeeratesPerKB] = {
    using(sqlite.prepareStatement("SELECT rate_block_1, rate_blocks_2, rate_blocks_6, rate_blocks_12, rate_blocks_36, rate_blocks_72, rate_blocks_144 FROM feerates_per_kb")) { statement =>
      val rs = statement.executeQuery()
      if (rs.next()) {
        Some(FeeratesPerKB(
          block_1 = rs.getLong("rate_block_1"),
          blocks_2 = rs.getLong("rate_blocks_2"),
          blocks_6 = rs.getLong("rate_blocks_6"),
          blocks_12 = rs.getLong("rate_blocks_12"),
          blocks_36 = rs.getLong("rate_blocks_36"),
          blocks_72 = rs.getLong("rate_blocks_72"),
          blocks_144 = rs.getLong("rate_blocks_144")))
      } else {
        None
      }
    }
  }

  // used by mobile apps
  override def close(): Unit = sqlite.close()
}
