package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
import fr.acinq.eclair.db.migration.MigrateDb.{checkVersions, migrateTable}
import fr.acinq.eclair.wire.internal.channel.ChannelCodecs.channelDataCodec
import scodec.bits.BitVector

import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp}
import java.time.Instant

object MigrateChannelsDb {

  private def migrateChannelsTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "local_channels"
    val insertSql = "INSERT INTO local.channels (channel_id, data, json, is_closed, created_timestamp, last_payment_sent_timestamp, last_payment_received_timestamp, last_connected_timestamp, closed_timestamp) VALUES (?, ?, ?::JSONB, ?, ?, ?, ?, ?, ?)"

    import fr.acinq.eclair.json.JsonSerializers._

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector32("channel_id").toHex)
      insertStatement.setBytes(2, rs.getBytes("data"))
      val state = channelDataCodec.decode(BitVector(rs.getBytes("data"))).require.value
      val json = serialization.write(state)
      insertStatement.setString(3, json)
      insertStatement.setBoolean(4, rs.getBoolean("is_closed"))
      insertStatement.setTimestamp(5, rs.getLongNullable("created_timestamp").map(l => Timestamp.from(Instant.ofEpochMilli(l))).orNull)
      insertStatement.setTimestamp(6, rs.getLongNullable("last_payment_sent_timestamp").map(l => Timestamp.from(Instant.ofEpochMilli(l))).orNull)
      insertStatement.setTimestamp(7, rs.getLongNullable("last_payment_received_timestamp").map(l => Timestamp.from(Instant.ofEpochMilli(l))).orNull)
      insertStatement.setTimestamp(8, rs.getLongNullable("last_connected_timestamp").map(l => Timestamp.from(Instant.ofEpochMilli(l))).orNull)
      insertStatement.setTimestamp(9, rs.getLongNullable("closed_timestamp").map(l => Timestamp.from(Instant.ofEpochMilli(l))).orNull)
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateHtlcInfos(source: Connection, destination: Connection): Int = {
    val sourceTable = "htlc_infos"
    val insertSql = "INSERT INTO local.htlc_infos (channel_id, commitment_number, payment_hash, cltv_expiry) VALUES (?, ?, ?, ?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector32("channel_id").toHex)
      insertStatement.setLong(2, rs.getLong("commitment_number"))
      insertStatement.setString(3, rs.getByteVector32("payment_hash").toHex)
      insertStatement.setLong(4, rs.getLong("cltv_expiry"))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  def migrateAllTables(source: Connection, destination: Connection): Unit = {
    checkVersions(source, destination, "channels", 4, 7)
    migrateChannelsTable(source, destination)
    migrateHtlcInfos(source, destination)
  }

}
