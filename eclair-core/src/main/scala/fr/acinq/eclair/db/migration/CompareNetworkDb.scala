package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.migration.CompareDb._
import scodec.bits.ByteVector

import java.sql.{Connection, ResultSet}

object CompareNetworkDb {

  private def compareNodesTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "nodes"
    val table2 = "network.nodes"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "node_id") ++
        bytes(rs, "data")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "node_id") ++
        bytes(rs, "data")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def compareChannelsTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "channels"
    val table2 = "network.public_channels"

    def hash1(rs: ResultSet): ByteVector = {
      long(rs, "short_channel_id") ++
        string(rs, "txid") ++
        bytes(rs, "channel_announcement") ++
        long(rs, "capacity_sat") ++
        bytesnull(rs, "channel_update_1") ++
        bytesnull(rs, "channel_update_2")
    }

    def hash2(rs: ResultSet): ByteVector = {
      long(rs, "short_channel_id") ++
        string(rs, "txid") ++
        bytes(rs, "channel_announcement") ++
        long(rs, "capacity_sat") ++
        bytesnull(rs, "channel_update_1") ++
        bytesnull(rs, "channel_update_2")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def comparePrunedTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "pruned"
    val table2 = "network.pruned_channels"

    def hash1(rs: ResultSet): ByteVector = {
      long(rs, "short_channel_id")
    }

    def hash2(rs: ResultSet): ByteVector = {
      long(rs, "short_channel_id")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  def compareAllTables(conn1: Connection, conn2: Connection): Boolean = {
    compareNodesTable(conn1, conn2) &&
      compareChannelsTable(conn1, conn2) &&
      comparePrunedTable(conn1, conn2)
  }

}
