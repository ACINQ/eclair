package fr.acinq.eclair.db.sqlite

import java.sql.ResultSet

import scodec.Codec
import scodec.bits.BitVector

object SqliteUtils {

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
