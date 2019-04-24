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

import fr.acinq.bitcoin.ByteVector32
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}

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
    * We keep track of their respective version using a dedicated table. The version entry will be created if
    * there is none but will never be updated here (use setVersion to do that).
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
    * Updates the version for a particular logical database, it will overwrite the previous version.
    * @param statement
    * @param db_name
    * @param newVersion
    * @return
    */
  def setVersion(statement: Statement, db_name: String, newVersion: Int) = {
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS versions (db_name TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL)")
    // overwrite the existing version
    statement.executeUpdate(s"UPDATE versions SET version=$newVersion WHERE db_name='$db_name'")
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
    * This helper retrieves the value from a nullable integer column and interprets it as an option. This is needed
    * because `rs.getLong` would return `0` for a null value.
    * It is used on Android only
    *
    * @param label
    * @return
    */
  def getNullableLong(rs: ResultSet, label: String) : Option[Long] = {
    val result = rs.getLong(label)
    if (rs.wasNull()) None else Some(result)
  }

  /**
    * Obtain an exclusive lock on a sqlite database. This is useful when we want to make sure that only one process
    * accesses the database file (see https://www.sqlite.org/pragma.html).
    *
    * The lock will be kept until the database is closed, or if the locking mode is explicitly reset.
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

  case class ExtendedResultSet(rs: ResultSet) {

    def getByteVector(columnLabel: String): ByteVector = ByteVector(rs.getBytes(columnLabel))

    def getByteVector32(columnLabel: String): ByteVector32 = ByteVector32(ByteVector(rs.getBytes(columnLabel)))

    def getByteVector32Nullable(columnLabel: String): Option[ByteVector32] = {
      val bytes = rs.getBytes(columnLabel)
      if(rs.wasNull()) None else Some(ByteVector32(ByteVector(bytes)))
    }
  }

  object ExtendedResultSet {
    implicit def conv(rs: ResultSet): ExtendedResultSet = ExtendedResultSet(rs)
  }
}
