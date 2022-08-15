package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.jdbc.JdbcUtils.ExtendedResultSet._
import fr.acinq.eclair.db.migration.MigrateDb.{checkVersions, migrateTable}
import fr.acinq.eclair.wire.protocol.LightningMessageCodecs.{channelAnnouncementCodec, channelUpdateCodec, nodeAnnouncementCodec}
import scodec.bits.BitVector

import java.sql.{Connection, PreparedStatement, ResultSet}

object MigrateNetworkDb {

  private def migrateNodesTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "nodes"
    val insertSql = "INSERT INTO network.nodes (node_id, data, json) VALUES (?, ?, ?::JSONB)"

    import fr.acinq.eclair.json.JsonSerializers._

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setString(1, rs.getByteVector("node_id").toHex)
      insertStatement.setBytes(2, rs.getBytes("data"))
      val state = nodeAnnouncementCodec.decode(BitVector(rs.getBytes("data"))).require.value
      val json = serialization.write(state)
      insertStatement.setString(3, json)
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migrateChannelsTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "channels"
    val insertSql = "INSERT INTO network.public_channels (short_channel_id, txid, channel_announcement, capacity_sat, channel_update_1, channel_update_2, channel_announcement_json, channel_update_1_json, channel_update_2_json) VALUES (?, ?, ?, ?, ?, ?, ?::JSONB, ?::JSONB, ?::JSONB)"

    import fr.acinq.eclair.json.JsonSerializers._

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setLong(1, rs.getLong("short_channel_id"))
      insertStatement.setString(2, rs.getString("txid"))
      insertStatement.setBytes(3, rs.getBytes("channel_announcement"))
      insertStatement.setLong(4, rs.getLong("capacity_sat"))
      insertStatement.setBytes(5, rs.getBytes("channel_update_1"))
      insertStatement.setBytes(6, rs.getBytes("channel_update_2"))
      val ann = channelAnnouncementCodec.decode(rs.getBitVectorOpt("channel_announcement").get).require.value
      val channel_update_1_opt = rs.getBitVectorOpt("channel_update_1").map(channelUpdateCodec.decode(_).require.value)
      val channel_update_2_opt = rs.getBitVectorOpt("channel_update_2").map(channelUpdateCodec.decode(_).require.value)
      val json = serialization.write(ann)
      val u1_json = channel_update_1_opt.map(serialization.write(_)).orNull
      val u2_json = channel_update_2_opt.map(serialization.write(_)).orNull
      insertStatement.setString(7, json)
      insertStatement.setString(8, u1_json)
      insertStatement.setString(9, u2_json)
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  private def migratePrunedTable(source: Connection, destination: Connection): Int = {
    val sourceTable = "pruned"
    val insertSql = "INSERT INTO network.pruned_channels (short_channel_id) VALUES (?)"

    def migrate(rs: ResultSet, insertStatement: PreparedStatement): Unit = {
      insertStatement.setLong(1, rs.getLong("short_channel_id"))
    }

    migrateTable(source, destination, sourceTable, insertSql, migrate)
  }

  def migrateAllTables(source: Connection, destination: Connection): Unit = {
    checkVersions(source, destination, "network", 2, 4)
    migrateNodesTable(source, destination)
    migrateChannelsTable(source, destination)
    migratePrunedTable(source, destination)
  }

}
