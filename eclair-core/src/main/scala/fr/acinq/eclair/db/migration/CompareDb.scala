package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.Databases.{PostgresDatabases, SqliteDatabases}
import fr.acinq.eclair.db.DualDatabases
import fr.acinq.eclair.db.jdbc.JdbcUtils.using
import fr.acinq.eclair.db.pg.PgUtils
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import java.sql.{Connection, ResultSet}

object CompareDb extends Logging {

  def compareTable(conn1: Connection,
                   conn2: Connection,
                   table1: String,
                   table2: String,
                   hash1: ResultSet => ByteVector,
                   hash2: ResultSet => ByteVector): Boolean = {
    var hashes1 = List.empty[ByteVector]
    using(conn1.prepareStatement(s"SELECT * FROM $table1")) { statement =>
      val rs = statement.executeQuery()
      while (rs.next()) hashes1 = hash1(rs) +: hashes1
    }

    var hashes2 = List.empty[ByteVector]
    using(conn2.prepareStatement(s"SELECT * FROM $table2")) { statement =>
      val rs = statement.executeQuery()
      while (rs.next()) hashes2 = hash2(rs) +: hashes2
    }

    if (hashes1.sorted == hashes2.sorted) {
      logger.info(s"tables $table1/$table2 are identical")
      true
    } else {
      val diff1 = hashes1 diff hashes2
      val diff2 = hashes2 diff hashes1
      logger.warn(s"tables $table1/$table2 are different diff1=${diff1.take(3).map(_.toHex.take(128))} diff2=${diff2.take(3).map(_.toHex.take(128))}")
      false
    }
  }

  // @formatter:off
  import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
  def bytes(rs: ResultSet, columnName: String): ByteVector = rs.getByteVector(columnName)
  def bytesnull(rs: ResultSet, columnName: String): ByteVector = rs.getByteVectorNullable(columnName).getOrElse(ByteVector.fromValidHex("deadbeef"))
  def hex(rs: ResultSet, columnName: String): ByteVector = rs.getByteVectorFromHex(columnName)
  def hexnull(rs: ResultSet, columnName: String): ByteVector = rs.getByteVectorFromHexNullable(columnName).getOrElse(ByteVector.fromValidHex("deadbeef"))
  def string(rs: ResultSet, columnName: String): ByteVector = ByteVector(rs.getString(columnName).getBytes)
  def stringnull(rs: ResultSet, columnName: String): ByteVector = ByteVector(rs.getStringNullable(columnName).getOrElse("<null>").getBytes)
  def bool(rs: ResultSet, columnName: String): ByteVector = ByteVector.fromByte(if (rs.getBoolean(columnName)) 1 else 0)
  def long(rs: ResultSet, columnName: String): ByteVector = ByteVector.fromLong(rs.getLong(columnName))
  def longnull(rs: ResultSet, columnName: String): ByteVector = ByteVector.fromLong(rs.getLongNullable(columnName).getOrElse(42))
  def longts(rs: ResultSet, columnName: String): ByteVector = ByteVector.fromLong((rs.getLong(columnName).toDouble / 1_000_000).round)
  def longtsnull(rs: ResultSet, columnName: String): ByteVector = ByteVector.fromLong(rs.getLongNullable(columnName).map(l => (l.toDouble/1_000_000).round).getOrElse(42))
  def int(rs: ResultSet, columnName: String): ByteVector = ByteVector.fromInt(rs.getInt(columnName))
  def ts(rs: ResultSet, columnName: String): ByteVector = ByteVector.fromLong((rs.getTimestamp(columnName).getTime.toDouble / 1_000_000).round)
  def tsnull(rs: ResultSet, columnName: String): ByteVector = ByteVector.fromLong(rs.getTimestampNullable(columnName).map(t => (t.getTime.toDouble / 1_000_000).round).getOrElse(42))
  def tssec(rs: ResultSet, columnName: String): ByteVector = ByteVector.fromLong((rs.getTimestamp(columnName).toInstant.getEpochSecond.toDouble / 1_000_000).round)
  def tssecnull(rs: ResultSet, columnName: String): ByteVector = ByteVector.fromLong(rs.getTimestampNullable(columnName).map(t => (t.toInstant.getEpochSecond.toDouble / 1_000_000).round).getOrElse(42))
  // @formatter:on

  def compareAll(dualDatabases: DualDatabases): Unit = {
    logger.info("comparing all tables...")
    val (sqliteDb: SqliteDatabases, postgresDb: PostgresDatabases) = DualDatabases.getDatabases(dualDatabases)
    PgUtils.inTransaction { postgres =>
      val result = List(
        CompareChannelsDb.compareAllTables(sqliteDb.channels.sqlite, postgres),
        ComparePendingCommandsDb.compareAllTables(sqliteDb.pendingCommands.sqlite, postgres),
        ComparePeersDb.compareAllTables(sqliteDb.peers.sqlite, postgres),
        ComparePaymentsDb.compareAllTables(sqliteDb.payments.sqlite, postgres),
        CompareNetworkDb.compareAllTables(sqliteDb.network.sqlite, postgres),
        CompareAuditDb.compareAllTables(sqliteDb.audit.sqlite, postgres)
      ).forall(_ == true)
      logger.info(s"comparison complete identical=$result")
    }(postgresDb.dataSource)
  }

}
