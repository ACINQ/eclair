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

package fr.acinq.eclair.db.jdbc

import fr.acinq.bitcoin.scalacompat.ByteVector32
import fr.acinq.eclair.{MilliSatoshi, Paginated}
import grizzled.slf4j.Logger
import org.sqlite.SQLiteConnection
import scodec.Decoder
import scodec.bits.{BitVector, ByteVector}

import java.sql.{Connection, PreparedStatement, ResultSet, Statement, Timestamp}
import java.util.UUID
import javax.sql.DataSource

trait JdbcUtils {

  import ExtendedResultSet._

  def withConnection[T](f: Connection => T)(implicit dataSource: DataSource): T = {
    val connection = dataSource.getConnection()
    try {
      f(connection)
    } finally {
      connection.close()
    }
  }

  /**
   * This helper makes sure statements are correctly closed.
   *
   * @param inTransaction if set to true, all updates in the block will be run in a transaction.
   */
  def using[T <: Statement, U](statement: T, inTransaction: Boolean = false)(block: T => U): U = {
    val autoCommit = statement.getConnection.getAutoCommit
    try {
      if (inTransaction) statement.getConnection.setAutoCommit(false)
      val res = block(statement)
      if (inTransaction) statement.getConnection.commit()
      res
    } catch {
      case t: Exception =>
        if (inTransaction) statement.getConnection.rollback()
        throw t
    } finally {
      if (inTransaction) statement.getConnection.setAutoCommit(autoCommit)
      if (statement != null) statement.close()
    }
  }

  private def createVersionTable(statement: Statement): Unit = {
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS versions (db_name TEXT NOT NULL PRIMARY KEY, version INTEGER NOT NULL)")
  }

  /**
   * Several logical databases (channels, network, peers) may be stored in the same physical database.
   * We keep track of their respective version using a dedicated table. The version entry will be created if
   * there is none but will never be updated here (use setVersion to do that).
   */
  def getVersion(statement: Statement, db_name: String): Option[Int] = {
    createVersionTable(statement)
    // if there was a previous version installed, this will return a different value from current version
    statement.executeQuery(s"SELECT version FROM versions WHERE db_name='$db_name'")
      .map(rs => rs.getInt("version"))
      .headOption
  }

  /**
   * Updates the version for a particular logical database, it will overwrite the previous version.
   *
   * NB: we could define this method in [[fr.acinq.eclair.db.sqlite.SqliteUtils]] and [[fr.acinq.eclair.db.pg.PgUtils]]
   * but it would make testing more complicated because we need to use one or the other depending on the backend.
   */
  def setVersion(statement: Statement, db_name: String, newVersion: Int): Unit = {
    createVersionTable(statement)
    statement.getConnection match {
      case _: SQLiteConnection =>
        // if there was no version for the current db, then insert the current version
        statement.executeUpdate(s"INSERT OR IGNORE INTO versions VALUES ('$db_name', $newVersion)")
        // if there was an existing version, then previous step was a no-op, now we overwrite the existing version
        statement.executeUpdate(s"UPDATE versions SET version=$newVersion WHERE db_name='$db_name'")
      case _ => // if it isn't an sqlite connection, we assume it's postgres
        // insert or update the version
        statement.executeUpdate(s"INSERT INTO versions VALUES ('$db_name', $newVersion) ON CONFLICT (db_name) DO UPDATE SET version = EXCLUDED.version ;")
    }
  }

  /**
   * A utility method that efficiently migrate a table using a provided migration method
   */
  def migrateTable(source: Connection,
                   destination: Connection,
                   sourceTable: String,
                   migrateSql: String,
                   migrate: (ResultSet, PreparedStatement) => Unit)(implicit logger: Logger): Int = {
    val insertStatement = destination.prepareStatement(migrateSql)
    val batchSize = 50
    using(source.prepareStatement(s"SELECT * FROM $sourceTable")) { queryStatement =>
      val rs = queryStatement.executeQuery()
      var inserted = 0
      var batchCount = 0
      while (rs.next()) {
        migrate(rs, insertStatement)
        insertStatement.addBatch()
        batchCount = batchCount + 1
        if (batchCount % batchSize == 0) {
          inserted = inserted + insertStatement.executeBatch().sum
          batchCount = 0
        }
      }
      inserted = inserted + insertStatement.executeBatch().sum
      logger.info(s"migrated $inserted rows from table $sourceTable")
      inserted
    }
  }

  case class ExtendedResultSet(rs: ResultSet) extends Iterable[ResultSet] {

    /**
     * Iterates over all rows of a result set.
     *
     * Careful: the iterator is lazy, it must be materialized before the [[ResultSet]] is closed, by converting the end
     * result in a collection or an option.
     */
    override def iterator: Iterator[ResultSet] = {
      // @formatter:off
      new Iterator[ResultSet] {
        def hasNext: Boolean = rs.next()
        def next(): ResultSet = rs
      }
      // @formatter:on
    }

    /** This helper assumes that there is a "data" column available, that can be decoded with the provided codec */
    def mapCodec[T](codec: Decoder[T]): Iterable[T] = rs.map(rs => codec.decode(BitVector(rs.getBytes("data"))).require.value)

    def getByteVectorFromHex(columnLabel: String): ByteVector = {
      val s = rs.getString(columnLabel).stripPrefix("\\x")
      ByteVector.fromValidHex(s)
    }

    def getByteVectorFromHexNullable(columnLabel: String): Option[ByteVector] = {
      val s = rs.getString(columnLabel)
      if (rs.wasNull()) None else Some(ByteVector.fromValidHex(s))
    }

    def getByteVector32FromHex(columnLabel: String): ByteVector32 = {
      val s = rs.getString(columnLabel)
      ByteVector32(ByteVector.fromValidHex(s))
    }

    def getByteVector32FromHexNullable(columnLabel: String): Option[ByteVector32] = {
      val s = rs.getString(columnLabel)
      if (rs.wasNull()) None else Some(ByteVector32(ByteVector.fromValidHex(s)))
    }

    def getBitVectorOpt(columnLabel: String): Option[BitVector] = Option(rs.getBytes(columnLabel)).map(BitVector(_))

    def getByteVector(columnLabel: String): ByteVector = ByteVector(rs.getBytes(columnLabel))

    def getByteVectorNullable(columnLabel: String): Option[ByteVector] = {
      val result = rs.getBytes(columnLabel)
      if (rs.wasNull()) None else Some(ByteVector(result))
    }

    def getByteVector32(columnLabel: String): ByteVector32 = ByteVector32(ByteVector(rs.getBytes(columnLabel)))

    def getByteVector32Nullable(columnLabel: String): Option[ByteVector32] = {
      val bytes = rs.getBytes(columnLabel)
      if (rs.wasNull()) None else Some(ByteVector32(ByteVector(bytes)))
    }

    def getStringNullable(columnLabel: String): Option[String] = {
      val result = rs.getString(columnLabel)
      if (rs.wasNull()) None else Some(result)
    }

    def getLongNullable(columnLabel: String): Option[Long] = {
      val result = rs.getLong(columnLabel)
      if (rs.wasNull()) None else Some(result)
    }

    def getMilliSatoshiNullable(label: String): Option[MilliSatoshi] = {
      val result = rs.getLong(label)
      if (rs.wasNull()) None else Some(MilliSatoshi(result))
    }

    def getTimestampNullable(label: String): Option[Timestamp] = {
      val result = rs.getTimestamp(label)
      if (rs.wasNull()) None else Some(result)
    }
  }

  object ExtendedResultSet {
    implicit def conv(rs: ResultSet): ExtendedResultSet = ExtendedResultSet(rs)
  }

  def limited(sql: String, paginated_opt: Option[Paginated]): String = paginated_opt match {
    case Some(paginated) => s"$sql LIMIT ${paginated.count} OFFSET ${paginated.skip}"
    case None => sql
  }

}

object JdbcUtils extends JdbcUtils
