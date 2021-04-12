package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.migration.CompareDb._
import scodec.bits.ByteVector

import java.sql.{Connection, ResultSet}

object ComparePaymentsDb {

  private def compareReceivedPaymentsTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "received_payments"
    val table2 = "payments.received"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "payment_hash") ++
        string(rs, "payment_type") ++
        bytes(rs, "payment_preimage") ++
        string(rs, "payment_request") ++
        longnull(rs, "received_msat") ++
        longts(rs, "created_at") ++
        longts(rs, "expire_at") ++
        longtsnull(rs, "received_at")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "payment_hash") ++
        string(rs, "payment_type") ++
        hex(rs, "payment_preimage") ++
        string(rs, "payment_request") ++
        longnull(rs, "received_msat") ++
        ts(rs, "created_at") ++
        ts(rs, "expire_at") ++
        tsnull(rs, "received_at")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def compareSentPaymentsTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "sent_payments"
    val table2 = "payments.sent"

    def hash1(rs: ResultSet): ByteVector = {
      string(rs, "id") ++
        string(rs, "parent_id") ++
        stringnull(rs, "external_id") ++
        bytes(rs, "payment_hash") ++
        bytesnull(rs, "payment_preimage") ++
        string(rs, "payment_type") ++
        long(rs, "amount_msat") ++
        longnull(rs, "fees_msat") ++
        long(rs, "recipient_amount_msat") ++
        bytes(rs, "recipient_node_id") ++
        stringnull(rs, "payment_request") ++
        bytesnull(rs, "payment_route") ++
        bytesnull(rs, "failures") ++
        longts(rs, "created_at") ++
        longtsnull(rs, "completed_at")
    }

    def hash2(rs: ResultSet): ByteVector = {
      string(rs, "id") ++
        string(rs, "parent_id") ++
        stringnull(rs, "external_id") ++
        hex(rs, "payment_hash") ++
        hexnull(rs, "payment_preimage") ++
        string(rs, "payment_type") ++
        long(rs, "amount_msat") ++
        longnull(rs, "fees_msat") ++
        long(rs, "recipient_amount_msat") ++
        hex(rs, "recipient_node_id") ++
        stringnull(rs, "payment_request") ++
        bytesnull(rs, "payment_route") ++
        bytesnull(rs, "failures") ++
        ts(rs, "created_at") ++
        tsnull(rs, "completed_at")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  def compareAllTables(conn1: Connection, conn2: Connection): Boolean = {
    compareReceivedPaymentsTable(conn1, conn2) &&
      compareSentPaymentsTable(conn1, conn2)
  }

}
