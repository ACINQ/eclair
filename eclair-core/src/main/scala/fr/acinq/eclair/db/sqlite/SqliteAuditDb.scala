/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.db.sqlite

import fr.acinq.bitcoin.scalacompat.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong, TxId}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.AuditDb.{NetworkFee, PublishedTransaction, Stats}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, Paginated, TimestampMilli}
import grizzled.slf4j.Logging

import java.sql.{Connection, Statement}
import java.util.UUID

object SqliteAuditDb {
  val DB_NAME = "audit"
  val CURRENT_VERSION = 11
}

class SqliteAuditDb(val sqlite: Connection) extends AuditDb with Logging {

  import SqliteUtils._
  import ExtendedResultSet._
  import SqliteAuditDb._

  case class RelayedPart(channelId: ByteVector32, amount: MilliSatoshi, direction: String, relayType: String, timestamp: TimestampMilli)

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    def migration12(statement: Statement): Unit = {
      val ZERO_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
      statement.executeUpdate(s"ALTER TABLE sent ADD id BLOB DEFAULT '${ZERO_UUID.toString}' NOT NULL")
    }

    def migration23(statement: Statement): Unit = {
      statement.executeUpdate("CREATE TABLE channel_errors (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, error_name TEXT NOT NULL, error_message TEXT NOT NULL, is_fatal INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE INDEX channel_errors_timestamp_idx ON channel_errors(timestamp)")
    }

    def migration34(statement: Statement): Unit = {
      statement.executeUpdate("DROP index sent_timestamp_idx")
      statement.executeUpdate("ALTER TABLE sent RENAME TO _sent_old")
      statement.executeUpdate("CREATE TABLE sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, recipient_amount_msat INTEGER NOT NULL, payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, recipient_node_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
      // Old rows will be missing a recipient node id, so we use an easy-to-spot default value.
      val defaultRecipientNodeId = PrivateKey(ByteVector32.One).publicKey
      statement.executeUpdate(s"INSERT INTO sent (amount_msat, fees_msat, recipient_amount_msat, payment_id, parent_payment_id, payment_hash, payment_preimage, recipient_node_id, to_channel_id, timestamp) SELECT amount_msat, fees_msat, amount_msat, id, id, payment_hash, payment_preimage, X'${defaultRecipientNodeId.toString}', to_channel_id, timestamp FROM _sent_old")
      statement.executeUpdate("DROP table _sent_old")

      statement.executeUpdate("DROP INDEX relayed_timestamp_idx")
      statement.executeUpdate("ALTER TABLE relayed RENAME TO _relayed_old")
      statement.executeUpdate("CREATE TABLE relayed (payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, channel_id BLOB NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("INSERT INTO relayed (payment_hash, amount_msat, channel_id, direction, relay_type, timestamp) SELECT payment_hash, amount_in_msat, from_channel_id, 'IN', 'channel', timestamp FROM _relayed_old")
      statement.executeUpdate("INSERT INTO relayed (payment_hash, amount_msat, channel_id, direction, relay_type, timestamp) SELECT payment_hash, amount_out_msat, to_channel_id, 'OUT', 'channel', timestamp FROM _relayed_old")
      statement.executeUpdate("DROP table _relayed_old")

      statement.executeUpdate("CREATE INDEX sent_timestamp_idx ON sent(timestamp)")
      statement.executeUpdate("CREATE INDEX relayed_timestamp_idx ON relayed(timestamp)")
      statement.executeUpdate("CREATE INDEX relayed_payment_hash_idx ON relayed(payment_hash)")
    }

    def migration45(statement: Statement): Unit = {
      statement.executeUpdate("CREATE TABLE relayed_trampoline (payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, next_node_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE INDEX relayed_trampoline_timestamp_idx ON relayed_trampoline(timestamp)")
      statement.executeUpdate("CREATE INDEX relayed_trampoline_payment_hash_idx ON relayed_trampoline(payment_hash)")
    }

    def migration56(statement: Statement): Unit = {
      statement.executeUpdate("CREATE TABLE channel_updates (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL, cltv_expiry_delta INTEGER NOT NULL, htlc_minimum_msat INTEGER NOT NULL, htlc_maximum_msat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE INDEX channel_updates_cid_idx ON channel_updates(channel_id)")
      statement.executeUpdate("CREATE INDEX channel_updates_nid_idx ON channel_updates(node_id)")
      statement.executeUpdate("CREATE INDEX channel_updates_timestamp_idx ON channel_updates(timestamp)")
    }

    def migration67(statement: Statement): Unit = {
      statement.executeUpdate("CREATE TABLE path_finding_metrics (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, status TEXT NOT NULL, duration_ms INTEGER NOT NULL, timestamp INTEGER NOT NULL, is_mpp INTEGER NOT NULL, experiment_name TEXT NOT NULL, recipient_node_id BLOB NOT NULL)")
      statement.executeUpdate("CREATE INDEX metrics_status_idx ON path_finding_metrics(status)")
      statement.executeUpdate("CREATE INDEX metrics_timestamp_idx ON path_finding_metrics(timestamp)")
      statement.executeUpdate("CREATE INDEX metrics_mpp_idx ON path_finding_metrics(is_mpp)")
      statement.executeUpdate("CREATE INDEX metrics_name_idx ON path_finding_metrics(experiment_name)")
    }

    def migration78(statement: Statement): Unit = {
      statement.executeUpdate("CREATE TABLE transactions_published (tx_id BLOB NOT NULL PRIMARY KEY, channel_id BLOB NOT NULL, node_id BLOB NOT NULL, mining_fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE transactions_confirmed (tx_id BLOB NOT NULL PRIMARY KEY, channel_id BLOB NOT NULL, node_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE INDEX transactions_published_timestamp_idx ON transactions_published(timestamp)")
      statement.executeUpdate("CREATE INDEX transactions_confirmed_timestamp_idx ON transactions_confirmed(timestamp)")
      // Migrate data from the network_fees table (which only stored data about confirmed transactions).
      statement.executeUpdate("INSERT OR IGNORE INTO transactions_published (tx_id, channel_id, node_id, mining_fee_sat, tx_type, timestamp) SELECT tx_id, channel_id, node_id, fee_sat, tx_type, timestamp FROM network_fees")
      statement.executeUpdate("INSERT OR IGNORE INTO transactions_confirmed (tx_id, channel_id, node_id, timestamp) SELECT tx_id, channel_id, node_id, timestamp FROM network_fees")
      statement.executeUpdate("DROP TABLE network_fees")
    }

    def migration89(statement: Statement): Unit = {
      statement.executeUpdate("CREATE INDEX transactions_published_channel_id_idx ON transactions_published(channel_id)")
    }

    def migration910(statement: Statement): Unit = {
      statement.executeUpdate("CREATE INDEX relayed_channel_id_idx ON relayed(channel_id)")
    }

    def migration1011(statement: Statement): Unit = {
      // We add the funding_txid and channel_type fields to channel_events and use TEXT instead of BLOBs.
      statement.executeUpdate("ALTER TABLE channel_events RENAME TO channel_events_before_v14")
      statement.executeUpdate("DROP INDEX channel_events_timestamp_idx")
      statement.executeUpdate("CREATE TABLE channel_events (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, funding_txid TEXT NOT NULL, channel_type TEXT NOT NULL, capacity_sat INTEGER NOT NULL, is_opener BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp INTEGER NOT NULL)")
      // We update the channel_updates table to use TEXT instead of BLOBs.
      statement.executeUpdate("ALTER TABLE channel_updates RENAME TO channel_updates_before_v14")
      statement.executeUpdate("DROP INDEX channel_updates_cid_idx")
      statement.executeUpdate("DROP INDEX channel_updates_nid_idx")
      statement.executeUpdate("DROP INDEX channel_updates_timestamp_idx")
      statement.executeUpdate("CREATE TABLE channel_updates (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL, cltv_expiry_delta INTEGER NOT NULL, htlc_minimum_msat INTEGER NOT NULL, htlc_maximum_msat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
      // We recreate indexes for the updated channel tables.
      statement.executeUpdate("CREATE INDEX channel_events_cid_idx ON channel_events(channel_id)")
      statement.executeUpdate("CREATE INDEX channel_events_nid_idx ON channel_events(node_id)")
      statement.executeUpdate("CREATE INDEX channel_events_timestamp_idx ON channel_events(timestamp)")
      statement.executeUpdate("CREATE INDEX channel_updates_cid_idx ON channel_updates(channel_id)")
      statement.executeUpdate("CREATE INDEX channel_updates_nid_idx ON channel_updates(node_id)")
      statement.executeUpdate("CREATE INDEX channel_updates_timestamp_idx ON channel_updates(timestamp)")
      // We add mining fee details, input and output counts to the transaction tables, and use TEXT instead of BLOBs.
      statement.executeUpdate("ALTER TABLE transactions_published RENAME TO transactions_published_before_v14")
      statement.executeUpdate("ALTER TABLE transactions_confirmed RENAME TO transactions_confirmed_before_v14")
      statement.executeUpdate("CREATE TABLE transactions_published (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, local_mining_fee_sat INTEGER NOT NULL, remote_mining_fee_sat INTEGER NOT NULL, feerate_sat_per_kw INTEGER NOT NULL, input_count INTEGER NOT NULL, output_count INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE TABLE transactions_confirmed (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, input_count INTEGER NOT NULL, output_count INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("DROP INDEX transactions_published_channel_id_idx")
      statement.executeUpdate("DROP INDEX transactions_published_timestamp_idx")
      statement.executeUpdate("DROP INDEX transactions_confirmed_timestamp_idx")
      // We recreate indexes for the updated transaction tables.
      statement.executeUpdate("CREATE INDEX transactions_published_channel_id_idx ON transactions_published(channel_id)")
      statement.executeUpdate("CREATE INDEX transactions_published_node_id_idx ON transactions_published(node_id)")
      statement.executeUpdate("CREATE INDEX transactions_published_timestamp_idx ON transactions_published(timestamp)")
      statement.executeUpdate("CREATE INDEX transactions_confirmed_channel_id_idx ON transactions_confirmed(channel_id)")
      statement.executeUpdate("CREATE INDEX transactions_confirmed_node_id_idx ON transactions_confirmed(node_id)")
      statement.executeUpdate("CREATE INDEX transactions_confirmed_timestamp_idx ON transactions_confirmed(timestamp)")
      // We update the sent payment table to include outgoing_node_id and started_at, rename columns for clarity and use TEXT instead of BLOBs.
      statement.executeUpdate("ALTER TABLE sent RENAME TO sent_before_v14")
      statement.executeUpdate("DROP INDEX sent_timestamp_idx")
      statement.executeUpdate("CREATE TABLE sent (payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash TEXT NOT NULL, payment_preimage TEXT NOT NULL, amount_with_fees_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, recipient_total_amount_msat INTEGER NOT NULL, recipient_node_id TEXT NOT NULL, outgoing_channel_id TEXT NOT NULL, outgoing_node_id TEXT NOT NULL, started_at INTEGER NOT NULL, settled_at INTEGER NOT NULL)")
      statement.executeUpdate("CREATE INDEX sent_settled_at_idx ON sent(settled_at)")
      // We update the received payment table to include the incoming_node_id, rename columns for clarity and use TEXT instead of BLOBs.
      statement.executeUpdate("ALTER TABLE received RENAME TO received_before_v14")
      statement.executeUpdate("DROP INDEX received_timestamp_idx")
      statement.executeUpdate("CREATE TABLE received (payment_hash TEXT NOT NULL, amount_msat INTEGER NOT NULL, incoming_channel_id TEXT NOT NULL, incoming_node_id TEXT NOT NULL, received_at INTEGER NOT NULL)")
      statement.executeUpdate("CREATE INDEX received_at_idx ON received(received_at)")
    }

    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE sent (payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash TEXT NOT NULL, payment_preimage TEXT NOT NULL, amount_with_fees_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, recipient_total_amount_msat INTEGER NOT NULL, recipient_node_id TEXT NOT NULL, outgoing_channel_id TEXT NOT NULL, outgoing_node_id TEXT NOT NULL, started_at INTEGER NOT NULL, settled_at INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE received (payment_hash TEXT NOT NULL, amount_msat INTEGER NOT NULL, incoming_channel_id TEXT NOT NULL, incoming_node_id TEXT NOT NULL, received_at INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE relayed (payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, channel_id BLOB NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE relayed_trampoline (payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, next_node_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE channel_events (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, funding_txid TEXT NOT NULL, channel_type TEXT NOT NULL, capacity_sat INTEGER NOT NULL, is_opener BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE channel_updates (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL, cltv_expiry_delta INTEGER NOT NULL, htlc_minimum_msat INTEGER NOT NULL, htlc_maximum_msat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE path_finding_metrics (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, status TEXT NOT NULL, duration_ms INTEGER NOT NULL, timestamp INTEGER NOT NULL, is_mpp INTEGER NOT NULL, experiment_name TEXT NOT NULL, recipient_node_id BLOB NOT NULL)")
        statement.executeUpdate("CREATE TABLE transactions_published (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, local_mining_fee_sat INTEGER NOT NULL, remote_mining_fee_sat INTEGER NOT NULL, feerate_sat_per_kw INTEGER NOT NULL, input_count INTEGER NOT NULL, output_count INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE transactions_confirmed (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, input_count INTEGER NOT NULL, output_count INTEGER NOT NULL, timestamp INTEGER NOT NULL)")

        statement.executeUpdate("CREATE INDEX sent_settled_at_idx ON sent(settled_at)")
        statement.executeUpdate("CREATE INDEX received_at_idx ON received(received_at)")
        statement.executeUpdate("CREATE INDEX relayed_timestamp_idx ON relayed(timestamp)")
        statement.executeUpdate("CREATE INDEX relayed_payment_hash_idx ON relayed(payment_hash)")
        statement.executeUpdate("CREATE INDEX relayed_channel_id_idx ON relayed(channel_id)")
        statement.executeUpdate("CREATE INDEX relayed_trampoline_timestamp_idx ON relayed_trampoline(timestamp)")
        statement.executeUpdate("CREATE INDEX relayed_trampoline_payment_hash_idx ON relayed_trampoline(payment_hash)")
        statement.executeUpdate("CREATE INDEX channel_events_cid_idx ON channel_events(channel_id)")
        statement.executeUpdate("CREATE INDEX channel_events_nid_idx ON channel_events(node_id)")
        statement.executeUpdate("CREATE INDEX channel_events_timestamp_idx ON channel_events(timestamp)")
        statement.executeUpdate("CREATE INDEX channel_updates_cid_idx ON channel_updates(channel_id)")
        statement.executeUpdate("CREATE INDEX channel_updates_nid_idx ON channel_updates(node_id)")
        statement.executeUpdate("CREATE INDEX channel_updates_timestamp_idx ON channel_updates(timestamp)")
        statement.executeUpdate("CREATE INDEX metrics_status_idx ON path_finding_metrics(status)")
        statement.executeUpdate("CREATE INDEX metrics_timestamp_idx ON path_finding_metrics(timestamp)")
        statement.executeUpdate("CREATE INDEX metrics_mpp_idx ON path_finding_metrics(is_mpp)")
        statement.executeUpdate("CREATE INDEX metrics_name_idx ON path_finding_metrics(experiment_name)")
        statement.executeUpdate("CREATE INDEX transactions_published_channel_id_idx ON transactions_published(channel_id)")
        statement.executeUpdate("CREATE INDEX transactions_published_node_id_idx ON transactions_published(node_id)")
        statement.executeUpdate("CREATE INDEX transactions_published_timestamp_idx ON transactions_published(timestamp)")
        statement.executeUpdate("CREATE INDEX transactions_confirmed_channel_id_idx ON transactions_confirmed(channel_id)")
        statement.executeUpdate("CREATE INDEX transactions_confirmed_node_id_idx ON transactions_confirmed(node_id)")
        statement.executeUpdate("CREATE INDEX transactions_confirmed_timestamp_idx ON transactions_confirmed(timestamp)")
      case Some(v@(1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10)) =>
        logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
        if (v < 2) {
          migration12(statement)
        }
        if (v < 3) {
          migration23(statement)
        }
        if (v < 4) {
          migration34(statement)
        }
        if (v < 5) {
          migration45(statement)
        }
        if (v < 6) {
          migration56(statement)
        }
        if (v < 7) {
          migration67(statement)
        }
        if (v < 8) {
          migration78(statement)
        }
        if (v < 9) {
          migration89(statement)
        }
        if (v < 10) {
          migration910(statement)
        }
        if (v < 11) {
          migration1011(statement)
        }
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def add(e: ChannelEvent): Unit = withMetrics("audit/add-channel-lifecycle", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO channel_events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, e.channelId.toHex)
      statement.setString(2, e.remoteNodeId.toHex)
      statement.setString(3, e.fundingTxId.value.toHex)
      statement.setString(4, e.channelType)
      statement.setLong(5, e.capacity.toLong)
      statement.setBoolean(6, e.isChannelOpener)
      statement.setBoolean(7, e.isPrivate)
      statement.setString(8, e.event)
      statement.setLong(9, e.timestamp.toLong)
      statement.executeUpdate()
    }
  }

  override def add(e: PaymentSent): Unit = withMetrics("audit/add-payment-sent", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO sent VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      e.parts.foreach(p => {
        statement.setString(1, p.id.toString)
        statement.setString(2, e.id.toString)
        statement.setString(3, e.paymentHash.toHex)
        statement.setString(4, e.paymentPreimage.toHex)
        statement.setLong(5, p.amountWithFees.toLong)
        statement.setLong(6, p.feesPaid.toLong)
        statement.setLong(7, e.recipientAmount.toLong)
        statement.setString(8, e.recipientNodeId.toHex)
        statement.setString(9, p.channelId.toHex)
        statement.setString(10, p.remoteNodeId.toHex)
        statement.setLong(11, p.startedAt.toLong)
        statement.setLong(12, p.settledAt.toLong)
        statement.addBatch()
      })
      statement.executeBatch()
    }
  }

  override def add(e: PaymentReceived): Unit = withMetrics("audit/add-payment-received", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO received VALUES (?, ?, ?, ?, ?)")) { statement =>
      e.parts.foreach(p => {
        statement.setString(1, e.paymentHash.toHex)
        statement.setLong(2, p.amount.toLong)
        statement.setString(3, p.channelId.toHex)
        statement.setString(4, p.remoteNodeId.toHex)
        statement.setLong(5, p.receivedAt.toLong)
        statement.addBatch()
      })
      statement.executeBatch()
    }
  }

  override def add(e: PaymentRelayed): Unit = withMetrics("audit/add-payment-relayed", DbBackends.Sqlite) {
    val payments = e match {
      case e: ChannelPaymentRelayed =>
        // non-trampoline relayed payments have one input and one output
        val in = e.incoming.map(i => RelayedPart(i.channelId, i.amount, "IN", "channel", i.receivedAt))
        val out = e.outgoing.map(o => RelayedPart(o.channelId, o.amount, "OUT", "channel", o.settledAt))
        in ++ out
      case TrampolinePaymentRelayed(_, incoming, outgoing, nextTrampolineNodeId, nextTrampolineAmount) =>
        using(sqlite.prepareStatement("INSERT INTO relayed_trampoline VALUES (?, ?, ?, ?)")) { statement =>
          statement.setBytes(1, e.paymentHash.toArray)
          statement.setLong(2, nextTrampolineAmount.toLong)
          statement.setBytes(3, nextTrampolineNodeId.value.toArray)
          statement.setLong(4, e.settledAt.toLong)
          statement.executeUpdate()
        }
        // trampoline relayed payments do MPP aggregation and may have M inputs and N outputs
        val in = incoming.map(i => RelayedPart(i.channelId, i.amount, "IN", "trampoline", i.receivedAt))
        val out = outgoing.map(o => RelayedPart(o.channelId, o.amount, "OUT", "trampoline", o.settledAt))
        in ++ out
      case OnTheFlyFundingPaymentRelayed(_, incoming, outgoing) =>
        val in = incoming.map(i => RelayedPart(i.channelId, i.amount, "IN", "on-the-fly-funding", i.receivedAt))
        val out = outgoing.map(o => RelayedPart(o.channelId, o.amount, "OUT", "on-the-fly-funding", o.settledAt))
        in ++ out
    }
    for (p <- payments) {
      using(sqlite.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setBytes(1, e.paymentHash.toArray)
        statement.setLong(2, p.amount.toLong)
        statement.setBytes(3, p.channelId.toArray)
        statement.setString(4, p.direction)
        statement.setString(5, p.relayType)
        statement.setLong(6, p.timestamp.toLong)
        statement.executeUpdate()
      }
    }
  }

  override def add(e: TransactionPublished): Unit = withMetrics("audit/add-transaction-published", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO transactions_published VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, e.tx.txid.value.toHex)
      statement.setString(2, e.channelId.toHex)
      statement.setString(3, e.remoteNodeId.toHex)
      statement.setLong(4, e.localMiningFee.toLong)
      statement.setLong(5, e.remoteMiningFee.toLong)
      statement.setLong(6, e.feerate.toLong)
      statement.setLong(7, e.tx.txIn.size)
      statement.setLong(8, e.tx.txOut.size)
      statement.setString(9, e.desc)
      statement.setLong(10, e.timestamp.toLong)
      statement.executeUpdate()
    }
  }

  override def add(e: TransactionConfirmed): Unit = withMetrics("audit/add-transaction-confirmed", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO transactions_confirmed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setString(1, e.tx.txid.value.toHex)
      statement.setString(2, e.channelId.toHex)
      statement.setString(3, e.remoteNodeId.toHex)
      statement.setLong(4, e.tx.txIn.size)
      statement.setLong(5, e.tx.txOut.size)
      statement.setLong(6, e.timestamp.toLong)
      statement.executeUpdate()
    }
  }

  override def addChannelUpdate(u: ChannelUpdateParametersChanged): Unit = withMetrics("audit/add-channel-update", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO channel_updates VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, u.channelId.toArray)
      statement.setBytes(2, u.remoteNodeId.value.toArray)
      statement.setLong(3, u.channelUpdate.feeBaseMsat.toLong)
      statement.setLong(4, u.channelUpdate.feeProportionalMillionths)
      statement.setLong(5, u.channelUpdate.cltvExpiryDelta.toInt)
      statement.setLong(6, u.channelUpdate.htlcMinimumMsat.toLong)
      statement.setLong(7, u.channelUpdate.htlcMaximumMsat.toLong)
      statement.setLong(8, TimestampMilli.now().toLong)
      statement.executeUpdate()
    }
  }

  override def addPathFindingExperimentMetrics(m: PathFindingExperimentMetrics): Unit = {
    using(sqlite.prepareStatement("INSERT INTO path_finding_metrics VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, m.amount.toLong)
      statement.setLong(2, m.fees.toLong)
      statement.setString(3, m.status)
      statement.setLong(4, m.duration.toMillis)
      statement.setLong(5, m.timestamp.toLong)
      statement.setBoolean(6, m.isMultiPart)
      statement.setString(7, m.experimentName)
      statement.setBytes(8, m.recipientNodeId.value.toArray)
      statement.executeUpdate()
    }
  }

  override def listPublished(channelId: ByteVector32): Seq[PublishedTransaction] = withMetrics("audit/list-published-by-channel-id", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM transactions_published WHERE channel_id = ?")) { statement =>
      statement.setString(1, channelId.toHex)
      statement.executeQuery().map { rs =>
        PublishedTransaction(
          txId = TxId(rs.getByteVector32FromHex("tx_id")),
          desc = rs.getString("tx_type"),
          localMiningFee = rs.getLong("local_mining_fee_sat").sat,
          remoteMiningFee = rs.getLong("remote_mining_fee_sat").sat,
          feerate = FeeratePerKw(rs.getLong("feerate_sat_per_kw").sat),
          timestamp = TimestampMilli(rs.getLong("timestamp"))
        )
      }.toSeq
    }
  }

  override def listPublished(remoteNodeId: PublicKey, from: TimestampMilli, to: TimestampMilli): Seq[PublishedTransaction] = withMetrics("audit/list-published-by-node-id", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM transactions_published WHERE node_id = ? AND timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setString(1, remoteNodeId.toHex)
      statement.setLong(2, from.toLong)
      statement.setLong(3, to.toLong)
      statement.executeQuery().map { rs =>
        PublishedTransaction(
          txId = TxId(rs.getByteVector32FromHex("tx_id")),
          desc = rs.getString("tx_type"),
          localMiningFee = rs.getLong("local_mining_fee_sat").sat,
          remoteMiningFee = rs.getLong("remote_mining_fee_sat").sat,
          feerate = FeeratePerKw(rs.getLong("feerate_sat_per_kw").sat),
          timestamp = TimestampMilli(rs.getLong("timestamp"))
        )
      }.toSeq
    }
  }

  override def listChannelEvents(channelId: ByteVector32, from: TimestampMilli, to: TimestampMilli): Seq[ChannelEvent] = withMetrics("audit/list-channel-events-by-channel-id", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM channel_events WHERE channel_id = ? AND timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setString(1, channelId.toHex)
      statement.setLong(2, from.toLong)
      statement.setLong(3, to.toLong)
      statement.executeQuery().map { rs =>
        ChannelEvent(
          channelId = channelId,
          remoteNodeId = PublicKey(rs.getByteVectorFromHex("node_id")),
          fundingTxId = TxId(rs.getByteVector32FromHex("funding_txid")),
          channelType = rs.getString("channel_type"),
          capacity = Satoshi(rs.getLong("capacity_sat")),
          isChannelOpener = rs.getBoolean("is_opener"),
          isPrivate = rs.getBoolean("is_private"),
          event = rs.getString("event"),
          timestamp = TimestampMilli(rs.getLong("timestamp")),
        )
      }.toSeq
    }
  }

  override def listChannelEvents(remoteNodeId: PublicKey, from: TimestampMilli, to: TimestampMilli): Seq[ChannelEvent] = withMetrics("audit/list-channel-events-by-node-id", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM channel_events WHERE node_id = ? AND timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setString(1, remoteNodeId.toHex)
      statement.setLong(2, from.toLong)
      statement.setLong(3, to.toLong)
      statement.executeQuery().map { rs =>
        ChannelEvent(
          channelId = rs.getByteVector32FromHex("channel_id"),
          remoteNodeId = remoteNodeId,
          fundingTxId = TxId(rs.getByteVector32FromHex("funding_txid")),
          channelType = rs.getString("channel_type"),
          capacity = Satoshi(rs.getLong("capacity_sat")),
          isChannelOpener = rs.getBoolean("is_opener"),
          isPrivate = rs.getBoolean("is_private"),
          event = rs.getString("event"),
          timestamp = TimestampMilli(rs.getLong("timestamp")),
        )
      }.toSeq
    }
  }

  override def listSent(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentSent] =
    using(sqlite.prepareStatement("SELECT * FROM sent WHERE settled_at >= ? AND settled_at < ?")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      val result = statement.executeQuery()
        .foldLeft(Map.empty[UUID, PaymentSent]) { (sentByParentId, rs) =>
          val parentId = UUID.fromString(rs.getString("parent_payment_id"))
          val part = PaymentSent.PaymentPart(
            id = UUID.fromString(rs.getString("payment_id")),
            payment = PaymentEvent.OutgoingPayment(
              channelId = rs.getByteVector32FromHex("outgoing_channel_id"),
              remoteNodeId = PublicKey(rs.getByteVectorFromHex("outgoing_node_id")),
              amount = MilliSatoshi(rs.getLong("amount_with_fees_msat")),
              settledAt = TimestampMilli(rs.getLong("settled_at"))
            ),
            feesPaid = MilliSatoshi(rs.getLong("fees_msat")),
            route = None, // we don't store the route in the audit DB
            startedAt = TimestampMilli(rs.getLong("started_at")))
          val sent = sentByParentId.get(parentId) match {
            case Some(s) => s.copy(parts = s.parts :+ part, startedAt = Seq(s.startedAt, part.startedAt).min)
            case None => PaymentSent(
              parentId,
              rs.getByteVector32FromHex("payment_preimage"),
              MilliSatoshi(rs.getLong("recipient_total_amount_msat")),
              PublicKey(rs.getByteVectorFromHex("recipient_node_id")),
              Seq(part),
              None,
              part.startedAt)
          }
          sentByParentId + (parentId -> sent)
        }.values.toSeq.sortBy(_.settledAt)
      paginated_opt match {
        case Some(paginated) => result.slice(paginated.skip, paginated.skip + paginated.count)
        case None => result
      }
    }

  override def listReceived(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentReceived] =
    using(sqlite.prepareStatement("SELECT * FROM received WHERE received_at >= ? AND received_at < ?")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      val result = statement.executeQuery()
        .foldLeft(Map.empty[ByteVector32, PaymentReceived]) { (receivedByHash, rs) =>
          val paymentHash = rs.getByteVector32FromHex("payment_hash")
          val part = PaymentEvent.IncomingPayment(
            channelId = rs.getByteVector32FromHex("incoming_channel_id"),
            remoteNodeId = PublicKey(rs.getByteVectorFromHex("incoming_node_id")),
            amount = MilliSatoshi(rs.getLong("amount_msat")),
            receivedAt = TimestampMilli(rs.getLong("received_at")))
          val received = receivedByHash.get(paymentHash) match {
            case Some(r) => r.copy(parts = r.parts :+ part)
            case None => PaymentReceived(paymentHash, Seq(part))
          }
          receivedByHash + (paymentHash -> received)
        }.values.toSeq.sortBy(_.settledAt)
      paginated_opt match {
        case Some(paginated) => result.slice(paginated.skip, paginated.skip + paginated.count)
        case None => result
      }
    }

  override def listRelayed(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentRelayed] = {
    val trampolineByHash = using(sqlite.prepareStatement("SELECT * FROM relayed_trampoline WHERE timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.executeQuery()
        .map { rs =>
          val paymentHash = rs.getByteVector32("payment_hash")
          val amount = MilliSatoshi(rs.getLong("amount_msat"))
          val nodeId = PublicKey(rs.getByteVector("next_node_id"))
          paymentHash -> (amount, nodeId)
        }
        .toMap
    }
    val relayedByHash = using(sqlite.prepareStatement("SELECT * FROM relayed WHERE timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.executeQuery()
        .foldLeft(Map.empty[ByteVector32, Seq[RelayedPart]]) { (relayedByHash, rs) =>
          val paymentHash = rs.getByteVector32("payment_hash")
          val part = RelayedPart(
            rs.getByteVector32("channel_id"),
            MilliSatoshi(rs.getLong("amount_msat")),
            rs.getString("direction"),
            rs.getString("relay_type"),
            TimestampMilli(rs.getLong("timestamp")))
          relayedByHash + (paymentHash -> (relayedByHash.getOrElse(paymentHash, Nil) :+ part))
        }
    }
    val result = relayedByHash.flatMap {
      case (paymentHash, parts) =>
        // We may have been routing multiple payments for the same payment_hash (MPP) in both cases (trampoline and channel).
        // NB: we may link the wrong in-out parts, but the overall sum will be correct: we sort by amounts to minimize the risk of mismatch.
        val incoming = parts.filter(_.direction == "IN").map(p => PaymentEvent.IncomingPayment(p.channelId, PrivateKey(ByteVector32.One).publicKey, p.amount, p.timestamp)).sortBy(_.amount)
        val outgoing = parts.filter(_.direction == "OUT").map(p => PaymentEvent.OutgoingPayment(p.channelId, PrivateKey(ByteVector32.One).publicKey, p.amount, p.timestamp)).sortBy(_.amount)
        parts.headOption match {
          case Some(RelayedPart(_, _, _, "channel", _)) => incoming.zip(outgoing).map {
            case (in, out) => ChannelPaymentRelayed(paymentHash, Seq(in), Seq(out))
          }
          case Some(RelayedPart(_, _, _, "trampoline", _)) => trampolineByHash.get(paymentHash) match {
            case Some((nextTrampolineAmount, nextTrampolineNodeId)) => TrampolinePaymentRelayed(paymentHash, incoming, outgoing, nextTrampolineNodeId, nextTrampolineAmount) :: Nil
            case None => Nil
          }
          case Some(RelayedPart(_, _, _, "on-the-fly-funding", _)) =>
            Seq(OnTheFlyFundingPaymentRelayed(paymentHash, incoming, outgoing))
          case _ => Nil
        }
    }.toSeq.sortBy(_.settledAt)
    paginated_opt match {
      case Some(paginated) => result.slice(paginated.skip, paginated.skip + paginated.count)
      case None => result
    }
  }

  override def listNetworkFees(from: TimestampMilli, to: TimestampMilli): Seq[NetworkFee] =
    using(sqlite.prepareStatement("SELECT * FROM transactions_confirmed INNER JOIN transactions_published ON transactions_published.tx_id = transactions_confirmed.tx_id WHERE transactions_confirmed.timestamp >= ? AND transactions_confirmed.timestamp < ? ORDER BY transactions_confirmed.timestamp")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      statement.executeQuery()
        .map { rs =>
          NetworkFee(
            remoteNodeId = PublicKey(rs.getByteVectorFromHex("node_id")),
            channelId = rs.getByteVector32FromHex("channel_id"),
            txId = rs.getByteVector32FromHex("tx_id"),
            fee = Satoshi(rs.getLong("local_mining_fee_sat")),
            txType = rs.getString("tx_type"),
            timestamp = TimestampMilli(rs.getLong("timestamp")))
        }.toSeq
    }

  override def stats(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated]): Seq[Stats] = {
    case class Relayed(amount: MilliSatoshi, fee: MilliSatoshi, direction: String)

    def aggregateRelayStats(previous: Map[ByteVector32, Seq[Relayed]], incoming: Seq[PaymentEvent.IncomingPayment], outgoing: Seq[PaymentEvent.OutgoingPayment]): Map[ByteVector32, Seq[Relayed]] = {
      // We ensure trampoline payments are counted only once per channel and per direction (if multiple HTLCs were sent
      // from/to the same channel, we group them).
      val amountIn = incoming.map(_.amount).sum
      val amountOut = outgoing.map(_.amount).sum
      val in = incoming.groupBy(_.channelId).map { case (channelId, parts) => (channelId, Relayed(parts.map(_.amount).sum, 0 msat, "IN")) }.toSeq
      val out = outgoing.groupBy(_.channelId).map { case (channelId, parts) =>
        val fee = (amountIn - amountOut) * parts.length / outgoing.length // we split the fee among outgoing channels
        (channelId, Relayed(parts.map(_.amount).sum, fee, "OUT"))
      }.toSeq
      (in ++ out).groupBy(_._1).map { case (channelId, payments) => (channelId, payments.map(_._2) ++ previous.getOrElse(channelId, Nil)) }
    }

    val relayed = listRelayed(from, to).foldLeft(Map.empty[ByteVector32, Seq[Relayed]]) { (previous, e) =>
      // NB: we must avoid counting the fee twice: we associate it to the outgoing channels rather than the incoming ones.
      val current = aggregateRelayStats(previous, e.incoming, e.outgoing)
      previous ++ current
    }

    val networkFees = listNetworkFees(from, to).foldLeft(Map.empty[ByteVector32, Satoshi]) { (feeByChannelId, f) =>
      feeByChannelId + (f.channelId -> (feeByChannelId.getOrElse(f.channelId, 0 sat) + f.fee))
    }

    // Channels opened by our peers won't have any network fees paid by us, but we still want to compute stats for them.
    val allChannels = networkFees.keySet ++ relayed.keySet
    val result = allChannels.toSeq.flatMap(channelId => {
      val networkFee = networkFees.getOrElse(channelId, 0 sat)
      val (in, out) = relayed.getOrElse(channelId, Nil).partition(_.direction == "IN")
      ((in, "IN") :: (out, "OUT") :: Nil).map { case (r, direction) =>
        val paymentCount = r.length
        if (paymentCount == 0) {
          Stats(channelId, direction, 0 sat, 0, 0 sat, networkFee)
        } else {
          val avgPaymentAmount = r.map(_.amount).sum / paymentCount
          val relayFee = r.map(_.fee).sum
          Stats(channelId, direction, avgPaymentAmount.truncateToSatoshi, paymentCount, relayFee.truncateToSatoshi, networkFee)
        }
      }
    }).sortBy(s => s.channelId.toHex + s.direction)
    paginated_opt match {
      case Some(paginated) => result.slice(paginated.skip, paginated.skip + paginated.count)
      case None => result
    }

  }
}
