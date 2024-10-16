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

import java.sql.{Connection, ResultSet, Statement}
import java.util.UUID

import fr.acinq.bitcoin.scala.ByteVector32
import fr.acinq.eclair.MilliSatoshi
import javax.sql.DataSource
import scodec.Codec
import scodec.bits.{BitVector, ByteVector}

import scala.collection.immutable.Queue

trait JdbcUtils {

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

  /**
   * This helper assumes that there is a "data" column available, decodable with the provided codec
   *
   * TODO: we should use an scala.Iterator instead
   */
  def codecSequence[T](rs: ResultSet, codec: Codec[T], onError: String => Unit = { _ => Unit }): Seq[T] = {
    var q: Queue[T] = Queue()
    while (rs.next()) {
      val data = BitVector(rs.getBytes("data"))
      try {
        q = q :+ codec.decode(data).require.value
      } catch {
        case t: Throwable =>
          onError(s"unreadable data=${data.toHex}")
          throw t
      }
    }
    q
  }

  case class ExtendedResultSet(rs: ResultSet) {

    def getByteVectorFromHex(columnLabel: String): ByteVector = {
      val s = rs.getString(columnLabel).stripPrefix("\\x")
      ByteVector.fromValidHex(s)
    }

    def getByteVector32FromHex(columnLabel: String): ByteVector32 = {
      val s = rs.getString(columnLabel)
      ByteVector32(ByteVector.fromValidHex(s))
    }

    def getByteVector32FromHexNullable(columnLabel: String): Option[ByteVector32] = {
      val s = rs.getString(columnLabel)
      if (rs.wasNull()) None else {
        Some(ByteVector32(ByteVector.fromValidHex(s)))
      }
    }

    def getBitVectorOpt(columnLabel: String): Option[BitVector] = Option(rs.getBytes(columnLabel)).map(BitVector(_))

    def getByteVector(columnLabel: String): ByteVector = ByteVector(rs.getBytes(columnLabel))

    def getByteVectorNullable(columnLabel: String): ByteVector = {
      val result = rs.getBytes(columnLabel)
      if (rs.wasNull()) ByteVector.empty else ByteVector(result)
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

    def getUUIDNullable(label: String): Option[UUID] = {
      val result = rs.getString(label)
      if (rs.wasNull()) None else Some(UUID.fromString(result))
    }

    def getMilliSatoshiNullable(label: String): Option[MilliSatoshi] = {
      val result = rs.getLong(label)
      if (rs.wasNull()) None else Some(MilliSatoshi(result))
    }

  }

  object ExtendedResultSet {
    implicit def conv(rs: ResultSet): ExtendedResultSet = ExtendedResultSet(rs)
  }

}

object JdbcUtils extends JdbcUtils
