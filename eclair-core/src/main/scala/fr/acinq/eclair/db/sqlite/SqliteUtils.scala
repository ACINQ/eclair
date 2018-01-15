package fr.acinq.eclair.db.sqlite

import java.sql.{ResultSet, Statement}

import scodec.Codec
import scodec.bits.BitVector

object SqliteUtils {

  /**
    * Manages closing of statement
    *
    * @param statement
    * @param block
    */
  def using[T <: Statement, U](statement: T)(block: T => U): U = {
    try {
      block(statement)
    } finally {
      if (statement != null) statement.close()
    }
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
  def codecList[T](rs: ResultSet, codec: Codec[T]): List[T] = {
    var l: List[T] = Nil
    while (rs.next()) {
      l = l :+ codec.decode(BitVector(rs.getBytes("data"))).require.value
    }
    l
  }
}
