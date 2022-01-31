package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.migration.CompareDb._
import scodec.bits.ByteVector

import java.sql.{Connection, ResultSet}

object ComparePendingCommandsDb {

  private def comparePendingSettlementCommandsTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "pending_settlement_commands"
    val table2 = "local.pending_settlement_commands"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "channel_id") ++
      long(rs, "htlc_id") ++
        bytes(rs, "data")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "channel_id") ++
        long(rs, "htlc_id") ++
        bytes(rs, "data")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  def compareAllTables(conn1: Connection, conn2: Connection): Boolean = {
    comparePendingSettlementCommandsTable(conn1, conn2)
  }

}
