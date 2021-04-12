package fr.acinq.eclair.db.migration

import fr.acinq.eclair.db.migration.CompareDb._
import scodec.bits.ByteVector

import java.sql.{Connection, ResultSet}

object CompareAuditDb {

  private def compareSentTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "sent"
    val table2 = "audit.sent"

    def hash1(rs: ResultSet): ByteVector = {
      long(rs, "amount_msat") ++
        long(rs, "fees_msat") ++
        long(rs, "recipient_amount_msat") ++
        string(rs, "payment_id") ++
        string(rs, "parent_payment_id") ++
        bytes(rs, "payment_hash") ++
        bytes(rs, "payment_preimage") ++
        bytes(rs, "recipient_node_id") ++
        bytes(rs, "to_channel_id") ++
        longts(rs, "timestamp")
    }

    def hash2(rs: ResultSet): ByteVector = {
      long(rs, "amount_msat") ++
        long(rs, "fees_msat") ++
        long(rs, "recipient_amount_msat") ++
        string(rs, "payment_id") ++
        string(rs, "parent_payment_id") ++
        hex(rs, "payment_hash") ++
        hex(rs, "payment_preimage") ++
        hex(rs, "recipient_node_id") ++
        hex(rs, "to_channel_id") ++
        ts(rs, "timestamp")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def compareReceivedTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "received"
    val table2 = "audit.received"

    def hash1(rs: ResultSet): ByteVector = {
      long(rs, "amount_msat") ++
        bytes(rs, "payment_hash") ++
        bytes(rs, "from_channel_id") ++
        longts(rs, "timestamp")
    }

    def hash2(rs: ResultSet): ByteVector = {
      long(rs, "amount_msat") ++
        hex(rs, "payment_hash") ++
        hex(rs, "from_channel_id") ++
        ts(rs, "timestamp")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def compareRelayedTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "relayed"
    val table2 = "audit.relayed"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "payment_hash") ++
        long(rs, "amount_msat") ++
        bytes(rs, "channel_id") ++
        string(rs, "direction") ++
        string(rs, "relay_type") ++
        longts(rs, "timestamp")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "payment_hash") ++
        long(rs, "amount_msat") ++
        hex(rs, "channel_id") ++
        string(rs, "direction") ++
        string(rs, "relay_type") ++
        ts(rs, "timestamp")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def compareRelayedTrampolineTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "relayed_trampoline"
    val table2 = "audit.relayed_trampoline"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "payment_hash") ++
        long(rs, "amount_msat") ++
        bytes(rs, "next_node_id") ++
        longts(rs, "timestamp")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "payment_hash") ++
        long(rs, "amount_msat") ++
        hex(rs, "next_node_id") ++
        ts(rs, "timestamp")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def compareTransactionsPublishedTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "transactions_published"
    val table2 = "audit.transactions_published"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "tx_id") ++
        bytes(rs, "channel_id") ++
        bytes(rs, "node_id") ++
        long(rs, "mining_fee_sat") ++
        string(rs, "tx_type") ++
        longts(rs, "timestamp")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "tx_id") ++
        hex(rs, "channel_id") ++
        hex(rs, "node_id") ++
        long(rs, "mining_fee_sat") ++
        string(rs, "tx_type") ++
        ts(rs, "timestamp")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def compareTransactionsConfirmedTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "transactions_confirmed"
    val table2 = "audit.transactions_confirmed"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "tx_id") ++
        bytes(rs, "channel_id") ++
        bytes(rs, "node_id") ++
        longts(rs, "timestamp")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "tx_id") ++
        hex(rs, "channel_id") ++
        hex(rs, "node_id") ++
        ts(rs, "timestamp")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def compareChannelEventsTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "channel_events"
    val table2 = "audit.channel_events"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "channel_id") ++
        bytes(rs, "node_id") ++
        long(rs, "capacity_sat") ++
        bool(rs, "is_funder") ++
        bool(rs, "is_private") ++
        string(rs, "event") ++
        longts(rs, "timestamp")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "channel_id") ++
        hex(rs, "node_id") ++
        long(rs, "capacity_sat") ++
        bool(rs, "is_funder") ++
        bool(rs, "is_private") ++
        string(rs, "event") ++
        ts(rs, "timestamp")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def compareChannelErrorsTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "channel_errors WHERE error_name <> 'CannotAffordFees'"
    val table2 = "audit.channel_errors"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "channel_id") ++
        bytes(rs, "node_id") ++
        string(rs, "error_name") ++
        string(rs, "error_message") ++
        bool(rs, "is_fatal") ++
        longts(rs, "timestamp")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "channel_id") ++
        hex(rs, "node_id") ++
        string(rs, "error_name") ++
        string(rs, "error_message") ++
        bool(rs, "is_fatal") ++
        ts(rs, "timestamp")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def compareChannelUpdatesTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "channel_updates"
    val table2 = "audit.channel_updates"

    def hash1(rs: ResultSet): ByteVector = {
      bytes(rs, "channel_id") ++
        bytes(rs, "node_id") ++
        long(rs, "fee_base_msat") ++
        long(rs, "fee_proportional_millionths") ++
        long(rs, "cltv_expiry_delta") ++
        long(rs, "htlc_minimum_msat") ++
        long(rs, "htlc_maximum_msat") ++
        longts(rs, "timestamp")
    }

    def hash2(rs: ResultSet): ByteVector = {
      hex(rs, "channel_id") ++
        hex(rs, "node_id") ++
        long(rs, "fee_base_msat") ++
        long(rs, "fee_proportional_millionths") ++
        long(rs, "cltv_expiry_delta") ++
        long(rs, "htlc_minimum_msat") ++
        long(rs, "htlc_maximum_msat") ++
        ts(rs, "timestamp")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  private def comparePathFindingMetricsTable(conn1: Connection, conn2: Connection): Boolean = {
    val table1 = "path_finding_metrics"
    val table2 = "audit.path_finding_metrics"

    def hash1(rs: ResultSet): ByteVector = {
      long(rs, "amount_msat") ++
        long(rs, "fees_msat") ++
        string(rs, "status") ++
        long(rs, "duration_ms") ++
        longts(rs, "timestamp") ++
        bool(rs, "is_mpp") ++
        string(rs, "experiment_name") ++
        bytes(rs, "recipient_node_id")

    }

    def hash2(rs: ResultSet): ByteVector = {
      long(rs, "amount_msat") ++
        long(rs, "fees_msat") ++
        string(rs, "status") ++
        long(rs, "duration_ms") ++
        ts(rs, "timestamp") ++
        bool(rs, "is_mpp") ++
        string(rs, "experiment_name") ++
        hex(rs, "recipient_node_id")
    }

    compareTable(conn1, conn2, table1, table2, hash1, hash2)
  }

  def compareAllTables(conn1: Connection, conn2: Connection): Boolean = {
    compareSentTable(conn1, conn2) &&
      compareReceivedTable(conn1, conn2) &&
      compareRelayedTable(conn1, conn2) &&
      compareRelayedTrampolineTable(conn1, conn2) &&
      compareTransactionsPublishedTable(conn1, conn2) &&
      compareTransactionsConfirmedTable(conn1, conn2) &&
      compareChannelEventsTable(conn1, conn2) &&
      compareChannelErrorsTable(conn1, conn2) &&
      compareChannelUpdatesTable(conn1, conn2) &&
      comparePathFindingMetricsTable(conn1, conn2)
  }

}
