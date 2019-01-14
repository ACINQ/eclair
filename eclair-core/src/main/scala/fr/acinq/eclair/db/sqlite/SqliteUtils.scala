/*
 * Copyright 2018 ACINQ SAS
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

import java.sql.{Connection, ResultSet, Statement}

import scodec.Codec
import scodec.bits.BitVector

import scala.collection.immutable.Queue

object SqliteUtils {

  /**
    * Manages closing of statement
    *
    * @param statement
    * @param block
    */
  def using[T <: Statement, U](statement: T, disableAutoCommit: Boolean = false)(block: T => U): U = {
    try {
      if (disableAutoCommit) statement.getConnection.setAutoCommit(false)
      block(statement)
    } finally {
      if (disableAutoCommit) statement.getConnection.setAutoCommit(true)
      if (statement != null) statement.close()
    }
  }

  /**
    * Several logical databases (channels, network, peers) may be stored in the same physical sqlite database.
    * We keep track of their respective version using a dedicated table.
    *
    * @param statement
    * @param db_name
    * @param currentVersion
    * @return
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
    * This helper assumes that there is a "data" column available, decodable with the provided codec
    *
    * TODO: we should use an scala.Iterator instead
    *
    * @param rs
    * @param codec
    * @tparam T
    * @return
    */
  def codecSequence[T](rs: ResultSet, codec: Codec[T]): Seq[T] = {
    var q: Queue[T] = Queue()
    while (rs.next()) {
      q = q :+ codec.decode(BitVector(rs.getBytes("data"))).require.value
    }
    q
  }

  /**
    * Obtain an exclusive lock on a sqlite database. This is useful when we want to make sure that only one process
    * accesses the database file (see https://www.sqlite.org/pragma.html).
    *
    * The lock will be kept until the database is closed, or if the locking mode is explicitely reset.
    *
    * @param sqlite
    */
  def obtainExclusiveLock(sqlite: Connection){
    val statement = sqlite.createStatement()
    statement.execute("PRAGMA locking_mode = EXCLUSIVE")
    // we have to make a write to actually obtain the lock
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS dummy_table_for_locking (a INTEGER NOT NULL)")
    statement.executeUpdate("INSERT INTO dummy_table_for_locking VALUES (42)")
  }
}
