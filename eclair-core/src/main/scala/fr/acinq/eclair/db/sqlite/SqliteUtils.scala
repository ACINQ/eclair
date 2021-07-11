/*
 * Copyright 2019 ACINQ SAS
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

import fr.acinq.eclair.db.jdbc.JdbcUtils

import java.sql.Connection

object SqliteUtils extends JdbcUtils {

  /**
   * Obtain an exclusive lock on a sqlite database. This is useful when we want to make sure that only one process
   * accesses the database file (see https://www.sqlite.org/pragma.html).
   *
   * The lock will be kept until the database is closed, or if the locking mode is explicitly reset.
   */
  def obtainExclusiveLock(sqlite: Connection): Unit = synchronized {
    using(sqlite.createStatement()) { statement =>
      statement.execute("PRAGMA locking_mode = EXCLUSIVE")
      // we have to make a write to actually obtain the lock
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS dummy_table_for_locking (a INTEGER NOT NULL)")
      statement.executeUpdate("INSERT INTO dummy_table_for_locking VALUES (42)")
    }
  }

  /**
   * See https://www.sqlite.org/pragma.html#pragma_journal_mode
   */
  def setJournalMode(sqlite: Connection, mode: String): Unit = {
    using(sqlite.createStatement()) { statement =>
      val res = statement.executeQuery(s"PRAGMA journal_mode=$mode")
      res.next()
      val currentMode = res.getString(1)
      assert(currentMode == mode, s"couldn't activate mode=$mode")
    }
  }

}
