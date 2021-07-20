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

import java.sql.{Connection, DriverManager, Statement}
import fr.acinq.eclair.db.jdbc.JdbcUtils
import grizzled.slf4j.Logging

import java.io.File

object SqliteUtils extends JdbcUtils with Logging {

  /**
   * Several logical databases (channels, network, peers) may be stored in the same physical sqlite database.
   * We keep track of their respective version using a dedicated table. The version entry will be created if
   * there is none but will never be updated here (use setVersion to do that).
   */
  def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int = {
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS versions (db_name TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL)")
    // if there was no version for the current db, then insert the current version
    statement.executeUpdate(s"INSERT OR IGNORE INTO versions VALUES ('$db_name', $currentVersion)")
    // if there was a previous version installed, this will return a different value from current version
    val res = statement.executeQuery(s"SELECT version FROM versions WHERE db_name='$db_name'")
    res.getInt("version")
  }

  /**
   * Updates the version for a particular logical database, it will overwrite the previous version.
   */
  def setVersion(statement: Statement, db_name: String, newVersion: Int) = {
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS versions (db_name TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL)")
    // overwrite the existing version
    statement.executeUpdate(s"UPDATE versions SET version=$newVersion WHERE db_name='$db_name'")
  }

  def openSqliteFile(directory: File, filename: String, exclusiveLock: Boolean, journalMode: String, syncFlag: String): Connection = {
    var sqlite: Connection = null
    try {
      sqlite = DriverManager.getConnection(s"jdbc:sqlite:${new File(directory, filename)}")
      if (exclusiveLock) {
        obtainExclusiveLock(sqlite)
      }
      setJournalMode(sqlite, journalMode)
      setSynchronousFlag(sqlite, syncFlag)
      sqlite
    } catch {
      case t: Throwable =>
        logger.error("could not create connection to sqlite databases: ", t)
        if (sqlite != null) {
          sqlite.close()
        }
        throw t
    }
  }

  /**
   * Obtain an exclusive lock on a sqlite database. This is useful when we want to make sure that only one process
   * accesses the database file (see https://www.sqlite.org/pragma.html).
   *
   * The lock will be kept until the database is closed, or if the locking mode is explicitly reset.
   */
  def obtainExclusiveLock(sqlite: Connection) = synchronized {
    val statement = sqlite.createStatement()
    statement.execute("PRAGMA locking_mode = EXCLUSIVE")
    // we have to make a write to actually obtain the lock
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS dummy_table_for_locking (a INTEGER NOT NULL)")
    statement.executeUpdate("INSERT INTO dummy_table_for_locking VALUES (42)")
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

  /**
   * See https://www.sqlite.org/pragma.html#pragma_synchronous
   */
  def setSynchronousFlag(sqlite: Connection, flag: String): Unit = {
    using(sqlite.createStatement()) { statement =>
      statement.executeUpdate(s"PRAGMA synchronous=$flag")
    }
  }

}
