package fr.acinq.eclair.db.sqlite

import java.sql.ResultSet

import scodec.Codec
import scodec.bits.BitVector

object SqliteUtils {
  /**
    * This helper assumes that there is a "data" column available, decodable with the provided codec
    *
    * @param rs
    * @param codec
    * @tparam T
    * @return
    */
  def codecIterator[T](rs: ResultSet, codec: Codec[T]): Iterator[T] = new Iterator[T] {
    override def hasNext: Boolean = rs.next()

    override def next(): T = codec.decode(BitVector(rs.getBytes("data"))).require.value
  }
}
