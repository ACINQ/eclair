package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.migration.CompareDb._
import scodec.bits.ByteVector

import java.sql.{Connection, ResultSet}

object ComparePeersDb {

  private def comparePeersTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "peers"
    val table2 = "local.peers"

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

  private def compareRelayFeesTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "relay_fees"
    val table2 = "local.relay_fees"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "node_id") ++
        long(rs, "fee_base_msat") ++
        long(rs, "fee_proportional_millionths")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "node_id") ++
        long(rs, "fee_base_msat") ++
        long(rs, "fee_proportional_millionths")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  def compareAllTables(conn1: Connection, conn2: Connection): Boolean = {
    comparePeersTable(conn1, conn2) &&
      compareRelayFeesTable(conn1, conn2)
  }

}
