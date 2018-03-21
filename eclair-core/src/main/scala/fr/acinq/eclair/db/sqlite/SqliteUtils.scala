package fr.acinq.eclair.db.sqlite

import java.sql.{ResultSet, Statement}

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
  def using[T <: Statement, U](statement: T)(block: T => U): U = {
    try {
      block(statement)
    } finally {
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
}
