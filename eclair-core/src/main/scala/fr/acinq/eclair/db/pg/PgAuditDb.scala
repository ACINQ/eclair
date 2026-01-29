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

package fr.acinq.eclair.db.pg

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong, TxId}
import fr.acinq.eclair.blockchain.fee.FeeratePerKw
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.AuditDb._
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, Paginated, TimestampMilli}
import grizzled.slf4j.Logging

import java.sql.{Statement, Timestamp}
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

object PgAuditDb {
  val DB_NAME = "audit"
  val CURRENT_VERSION = 14
}

class PgAuditDb(implicit ds: DataSource) extends AuditDb with Logging {

  import PgUtils._
  import ExtendedResultSet._
  import PgAuditDb._
  import fr.acinq.eclair.json.JsonSerializers.{formats, serialization}

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>
      def migration45(statement: Statement): Unit = {
        statement.executeUpdate("CREATE TABLE relayed_trampoline (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, next_node_id TEXT NOT NULL, timestamp BIGINT NOT NULL)")
        statement.executeUpdate("CREATE INDEX relayed_trampoline_timestamp_idx ON relayed_trampoline(timestamp)")
        statement.executeUpdate("CREATE INDEX relayed_trampoline_payment_hash_idx ON relayed_trampoline(payment_hash)")
      }

      def migration56(statement: Statement): Unit = {
        statement.executeUpdate("ALTER TABLE sent ALTER COLUMN timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + timestamp * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE received ALTER COLUMN timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + timestamp * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE relayed ALTER COLUMN timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + timestamp * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE relayed_trampoline ALTER COLUMN timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + timestamp * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE network_fees ALTER COLUMN timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + timestamp * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE channel_events ALTER COLUMN timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + timestamp * interval '1 millisecond'")
        statement.executeUpdate("ALTER TABLE channel_errors ALTER COLUMN timestamp SET DATA TYPE TIMESTAMP WITH TIME ZONE USING timestamp with time zone 'epoch' + timestamp * interval '1 millisecond'")
      }

      def migration67(statement: Statement): Unit = {
        statement.executeUpdate("CREATE SCHEMA audit")
        statement.executeUpdate("ALTER TABLE sent SET SCHEMA audit")
        statement.executeUpdate("ALTER TABLE received SET SCHEMA audit")
        statement.executeUpdate("ALTER TABLE relayed SET SCHEMA audit")
        statement.executeUpdate("ALTER TABLE relayed_trampoline SET SCHEMA audit")
        statement.executeUpdate("ALTER TABLE network_fees SET SCHEMA audit")
        statement.executeUpdate("ALTER TABLE channel_events SET SCHEMA audit")
        statement.executeUpdate("ALTER TABLE channel_errors SET SCHEMA audit")
      }

      def migration78(statement: Statement): Unit = {
        statement.executeUpdate("CREATE TABLE audit.channel_updates (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, fee_base_msat BIGINT NOT NULL, fee_proportional_millionths BIGINT NOT NULL, cltv_expiry_delta BIGINT NOT NULL, htlc_minimum_msat BIGINT NOT NULL, htlc_maximum_msat BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
        statement.executeUpdate("CREATE INDEX channel_updates_cid_idx ON audit.channel_updates(channel_id)")
        statement.executeUpdate("CREATE INDEX channel_updates_nid_idx ON audit.channel_updates(node_id)")
        statement.executeUpdate("CREATE INDEX channel_updates_timestamp_idx ON audit.channel_updates(timestamp)")
      }

      def migration89(statement: Statement): Unit = {
        statement.executeUpdate("CREATE TABLE audit.path_finding_metrics (amount_msat BIGINT NOT NULL, fees_msat BIGINT NOT NULL, status TEXT NOT NULL, duration_ms BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL, is_mpp BOOLEAN NOT NULL, experiment_name TEXT NOT NULL, recipient_node_id TEXT NOT NULL)")
        statement.executeUpdate("CREATE INDEX metrics_status_idx ON audit.path_finding_metrics(status)")
        statement.executeUpdate("CREATE INDEX metrics_timestamp_idx ON audit.path_finding_metrics(timestamp)")
        statement.executeUpdate("CREATE INDEX metrics_mpp_idx ON audit.path_finding_metrics(is_mpp)")
        statement.executeUpdate("CREATE INDEX metrics_name_idx ON audit.path_finding_metrics(experiment_name)")
      }

      def migration910(statement: Statement): Unit = {
        statement.executeUpdate("CREATE TABLE audit.transactions_published (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, mining_fee_sat BIGINT NOT NULL, tx_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
        statement.executeUpdate("CREATE TABLE audit.transactions_confirmed (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
        statement.executeUpdate("CREATE INDEX transactions_published_timestamp_idx ON audit.transactions_published(timestamp)")
        statement.executeUpdate("CREATE INDEX transactions_confirmed_timestamp_idx ON audit.transactions_confirmed(timestamp)")
        // Migrate data from the network_fees table (which only stored data about confirmed transactions).
        statement.executeUpdate("INSERT INTO audit.transactions_published (tx_id, channel_id, node_id, mining_fee_sat, tx_type, timestamp) SELECT tx_id, channel_id, node_id, fee_sat, tx_type, timestamp FROM audit.network_fees ON CONFLICT DO NOTHING")
        statement.executeUpdate("INSERT INTO audit.transactions_confirmed (tx_id, channel_id, node_id, timestamp) SELECT tx_id, channel_id, node_id, timestamp FROM audit.network_fees ON CONFLICT DO NOTHING")
        statement.executeUpdate("DROP TABLE audit.network_fees")
      }

      def migration1011(statement: Statement): Unit = {
        statement.executeUpdate("ALTER TABLE audit.path_finding_metrics ADD COLUMN payment_hash TEXT")
        statement.executeUpdate("ALTER TABLE audit.path_finding_metrics ADD COLUMN routing_hints JSONB")
        statement.executeUpdate("CREATE INDEX metrics_hash_idx ON audit.path_finding_metrics(payment_hash)")
        statement.executeUpdate("CREATE INDEX metrics_recipient_idx ON audit.path_finding_metrics(recipient_node_id)")
      }

      def migration1112(statement: Statement): Unit = {
        statement.executeUpdate("CREATE INDEX transactions_published_channel_id_idx ON audit.transactions_published(channel_id)")
      }

      def migration1213(statement: Statement): Unit = {
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_channel_id_idx ON audit.relayed(channel_id)")
      }

      def migration1314(statement: Statement): Unit = {
        // We add the funding_txid and channel_type fields to channel_events.
        statement.executeUpdate("ALTER TABLE audit.channel_events RENAME TO channel_events_before_v14")
        statement.executeUpdate("DROP INDEX audit.channel_events_timestamp_idx")
        statement.executeUpdate("CREATE TABLE audit.channel_events (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, funding_txid TEXT NOT NULL, channel_type TEXT NOT NULL, capacity_sat BIGINT NOT NULL, is_opener BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
        // We recreate indexes for updated channel tables.
        statement.executeUpdate("CREATE INDEX channel_events_cid_idx ON audit.channel_events(channel_id)")
        statement.executeUpdate("CREATE INDEX channel_events_nid_idx ON audit.channel_events(node_id)")
        statement.executeUpdate("CREATE INDEX channel_events_timestamp_idx ON audit.channel_events(timestamp)")
        // We add mining fee details, input and output counts to the transaction tables.
        statement.executeUpdate("ALTER TABLE audit.transactions_published RENAME TO transactions_published_before_v14")
        statement.executeUpdate("ALTER TABLE audit.transactions_confirmed RENAME TO transactions_confirmed_before_v14")
        statement.executeUpdate("DROP INDEX audit.transactions_published_channel_id_idx")
        statement.executeUpdate("DROP INDEX audit.transactions_published_timestamp_idx")
        statement.executeUpdate("DROP INDEX audit.transactions_confirmed_timestamp_idx")
        statement.executeUpdate("CREATE TABLE audit.transactions_published (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, local_mining_fee_sat BIGINT NOT NULL, remote_mining_fee_sat BIGINT NOT NULL, feerate_sat_per_kw BIGINT NOT NULL, input_count BIGINT NOT NULL, output_count BIGINT NOT NULL, tx_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
        statement.executeUpdate("CREATE TABLE audit.transactions_confirmed (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, input_count BIGINT NOT NULL, output_count BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
        // We recreate indexes for the updated transaction tables.
        statement.executeUpdate("CREATE INDEX transactions_published_channel_id_idx ON audit.transactions_published(channel_id)")
        statement.executeUpdate("CREATE INDEX transactions_published_node_id_idx ON audit.transactions_published(node_id)")
        statement.executeUpdate("CREATE INDEX transactions_published_timestamp_idx ON audit.transactions_published(timestamp)")
        statement.executeUpdate("CREATE INDEX transactions_confirmed_channel_id_idx ON audit.transactions_confirmed(channel_id)")
        statement.executeUpdate("CREATE INDEX transactions_confirmed_node_id_idx ON audit.transactions_confirmed(node_id)")
        statement.executeUpdate("CREATE INDEX transactions_confirmed_timestamp_idx ON audit.transactions_confirmed(timestamp)")
        // We update the sent payment table to include outgoing_node_id and started_at, and rename columns for clarity.
        statement.executeUpdate("ALTER TABLE audit.sent RENAME TO sent_before_v14")
        statement.executeUpdate("DROP INDEX audit.sent_timestamp_idx")
        statement.executeUpdate("CREATE TABLE audit.sent (payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash TEXT NOT NULL, payment_preimage TEXT NOT NULL, amount_with_fees_msat BIGINT NOT NULL, fees_msat BIGINT NOT NULL, recipient_total_amount_msat BIGINT NOT NULL, recipient_node_id TEXT NOT NULL, outgoing_channel_id TEXT NOT NULL, outgoing_node_id TEXT NOT NULL, started_at TIMESTAMP WITH TIME ZONE NOT NULL, settled_at TIMESTAMP WITH TIME ZONE NOT NULL)")
        statement.executeUpdate("CREATE INDEX sent_settled_at_idx ON audit.sent(settled_at)")
        // We update the received payment table to include the incoming_node_id, and rename columns for clarity.
        statement.executeUpdate("ALTER TABLE audit.received RENAME TO received_before_v14")
        statement.executeUpdate("DROP INDEX audit.received_timestamp_idx")
        statement.executeUpdate("CREATE TABLE audit.received (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, incoming_channel_id TEXT NOT NULL, incoming_node_id TEXT NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL)")
        statement.executeUpdate("CREATE INDEX received_at_idx ON audit.received(received_at)")
        // We update the relayed payment table to include our channel peer's node_id, rename columns for clarity.
        statement.executeUpdate("ALTER TABLE audit.relayed RENAME TO relayed_before_v14")
        statement.executeUpdate("ALTER TABLE audit.relayed_trampoline RENAME TO relayed_trampoline_before_v14")
        statement.executeUpdate("DROP INDEX audit.relayed_timestamp_idx")
        statement.executeUpdate("DROP INDEX audit.relayed_payment_hash_idx")
        statement.executeUpdate("DROP INDEX audit.relayed_channel_id_idx")
        statement.executeUpdate("DROP INDEX audit.relayed_trampoline_timestamp_idx")
        statement.executeUpdate("DROP INDEX audit.relayed_trampoline_payment_hash_idx")
        statement.executeUpdate("CREATE TABLE audit.relayed (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
        statement.executeUpdate("CREATE TABLE audit.relayed_trampoline (payment_hash TEXT NOT NULL, next_trampoline_amount_msat BIGINT NOT NULL, next_trampoline_node_id TEXT NOT NULL, settled_at TIMESTAMP WITH TIME ZONE NOT NULL)")
        statement.executeUpdate("CREATE INDEX relayed_timestamp_idx ON audit.relayed(timestamp)")
        statement.executeUpdate("CREATE INDEX relayed_payment_hash_idx ON audit.relayed(payment_hash)")
        statement.executeUpdate("CREATE INDEX relayed_channel_id_idx ON audit.relayed(channel_id)")
        statement.executeUpdate("CREATE INDEX relayed_node_id_idx ON audit.relayed(node_id)")
        statement.executeUpdate("CREATE INDEX relayed_trampoline_payment_hash_idx ON audit.relayed_trampoline(payment_hash)")
      }

      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA audit")

          statement.executeUpdate("CREATE TABLE audit.sent (payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash TEXT NOT NULL, payment_preimage TEXT NOT NULL, amount_with_fees_msat BIGINT NOT NULL, fees_msat BIGINT NOT NULL, recipient_total_amount_msat BIGINT NOT NULL, recipient_node_id TEXT NOT NULL, outgoing_channel_id TEXT NOT NULL, outgoing_node_id TEXT NOT NULL, started_at TIMESTAMP WITH TIME ZONE NOT NULL, settled_at TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.received (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, incoming_channel_id TEXT NOT NULL, incoming_node_id TEXT NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.relayed (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.relayed_trampoline (payment_hash TEXT NOT NULL, next_trampoline_amount_msat BIGINT NOT NULL, next_trampoline_node_id TEXT NOT NULL, settled_at TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.channel_events (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, funding_txid TEXT NOT NULL, channel_type TEXT NOT NULL, capacity_sat BIGINT NOT NULL, is_opener BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.channel_updates (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, fee_base_msat BIGINT NOT NULL, fee_proportional_millionths BIGINT NOT NULL, cltv_expiry_delta BIGINT NOT NULL, htlc_minimum_msat BIGINT NOT NULL, htlc_maximum_msat BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.path_finding_metrics (amount_msat BIGINT NOT NULL, fees_msat BIGINT NOT NULL, status TEXT NOT NULL, duration_ms BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL, is_mpp BOOLEAN NOT NULL, experiment_name TEXT NOT NULL, recipient_node_id TEXT NOT NULL, payment_hash TEXT, routing_hints JSONB)")
          statement.executeUpdate("CREATE TABLE audit.transactions_published (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, local_mining_fee_sat BIGINT NOT NULL, remote_mining_fee_sat BIGINT NOT NULL, feerate_sat_per_kw BIGINT NOT NULL, input_count BIGINT NOT NULL, output_count BIGINT NOT NULL, tx_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.transactions_confirmed (tx_id TEXT NOT NULL PRIMARY KEY, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, input_count BIGINT NOT NULL, output_count BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")

          statement.executeUpdate("CREATE INDEX sent_settled_at_idx ON audit.sent(settled_at)")
          statement.executeUpdate("CREATE INDEX received_at_idx ON audit.received(received_at)")
          statement.executeUpdate("CREATE INDEX relayed_timestamp_idx ON audit.relayed(timestamp)")
          statement.executeUpdate("CREATE INDEX relayed_payment_hash_idx ON audit.relayed(payment_hash)")
          statement.executeUpdate("CREATE INDEX relayed_channel_id_idx ON audit.relayed(channel_id)")
          statement.executeUpdate("CREATE INDEX relayed_node_id_idx ON audit.relayed(node_id)")
          statement.executeUpdate("CREATE INDEX relayed_trampoline_payment_hash_idx ON audit.relayed_trampoline(payment_hash)")
          statement.executeUpdate("CREATE INDEX channel_events_cid_idx ON audit.channel_events(channel_id)")
          statement.executeUpdate("CREATE INDEX channel_events_nid_idx ON audit.channel_events(node_id)")
          statement.executeUpdate("CREATE INDEX channel_events_timestamp_idx ON audit.channel_events(timestamp)")
          statement.executeUpdate("CREATE INDEX channel_updates_cid_idx ON audit.channel_updates(channel_id)")
          statement.executeUpdate("CREATE INDEX channel_updates_nid_idx ON audit.channel_updates(node_id)")
          statement.executeUpdate("CREATE INDEX channel_updates_timestamp_idx ON audit.channel_updates(timestamp)")
          statement.executeUpdate("CREATE INDEX metrics_status_idx ON audit.path_finding_metrics(status)")
          statement.executeUpdate("CREATE INDEX metrics_timestamp_idx ON audit.path_finding_metrics(timestamp)")
          statement.executeUpdate("CREATE INDEX metrics_mpp_idx ON audit.path_finding_metrics(is_mpp)")
          statement.executeUpdate("CREATE INDEX metrics_name_idx ON audit.path_finding_metrics(experiment_name)")
          statement.executeUpdate("CREATE INDEX metrics_recipient_idx ON audit.path_finding_metrics(recipient_node_id)")
          statement.executeUpdate("CREATE INDEX metrics_hash_idx ON audit.path_finding_metrics(payment_hash)")
          statement.executeUpdate("CREATE INDEX transactions_published_channel_id_idx ON audit.transactions_published(channel_id)")
          statement.executeUpdate("CREATE INDEX transactions_published_node_id_idx ON audit.transactions_published(node_id)")
          statement.executeUpdate("CREATE INDEX transactions_published_timestamp_idx ON audit.transactions_published(timestamp)")
          statement.executeUpdate("CREATE INDEX transactions_confirmed_channel_id_idx ON audit.transactions_confirmed(channel_id)")
          statement.executeUpdate("CREATE INDEX transactions_confirmed_node_id_idx ON audit.transactions_confirmed(node_id)")
          statement.executeUpdate("CREATE INDEX transactions_confirmed_timestamp_idx ON audit.transactions_confirmed(timestamp)")
        case Some(v@(4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13)) =>
          logger.warn(s"migrating db $DB_NAME, found version=$v current=$CURRENT_VERSION")
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
          if (v < 12) {
            migration1112(statement)
          }
          if (v < 13) {
            migration1213(statement)
          }
          if (v < 14) {
            migration1314(statement)
          }
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def add(e: ChannelEvent): Unit = withMetrics("audit/add-channel-lifecycle", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.channel_events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, e.channelId.toHex)
        statement.setString(2, e.remoteNodeId.toHex)
        statement.setString(3, e.fundingTxId.value.toHex)
        statement.setString(4, e.channelType)
        statement.setLong(5, e.capacity.toLong)
        statement.setBoolean(6, e.isChannelOpener)
        statement.setBoolean(7, e.isPrivate)
        statement.setString(8, e.event)
        statement.setTimestamp(9, e.timestamp.toSqlTimestamp)
        statement.executeUpdate()
      }
    }
  }

  override def add(e: PaymentSent): Unit = withMetrics("audit/add-payment-sent", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.sent VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
        e.parts.foreach(p => {
          statement.setString(1, p.id.toString)
          statement.setString(2, e.id.toString)
          statement.setString(3, e.paymentHash.toHex)
          statement.setString(4, e.paymentPreimage.toHex)
          statement.setLong(5, p.amountWithFees.toLong)
          statement.setLong(6, p.feesPaid.toLong)
          statement.setLong(7, e.recipientAmount.toLong)
          statement.setString(8, e.recipientNodeId.value.toHex)
          statement.setString(9, p.payment.channelId.toHex)
          statement.setString(10, p.payment.remoteNodeId.toHex)
          statement.setTimestamp(11, p.startedAt.toSqlTimestamp)
          statement.setTimestamp(12, p.settledAt.toSqlTimestamp)
          statement.addBatch()
        })
        statement.executeBatch()
      }
    }
  }

  override def add(e: PaymentReceived): Unit = withMetrics("audit/add-payment-received", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.received VALUES (?, ?, ?, ?, ?)")) { statement =>
        e.parts.foreach(p => {
          statement.setString(1, e.paymentHash.toHex)
          statement.setLong(2, p.amount.toLong)
          statement.setString(3, p.channelId.toHex)
          statement.setString(4, p.remoteNodeId.toHex)
          statement.setTimestamp(5, p.receivedAt.toSqlTimestamp)
          statement.addBatch()
        })
        statement.executeBatch()
      }
    }
  }

  override def add(e: PaymentRelayed): Unit = withMetrics("audit/add-payment-relayed", DbBackends.Postgres) {
    inTransaction { pg =>
      e match {
        case e: TrampolinePaymentRelayed =>
          // For trampoline payments, we store additional metadata about the payment in a dedicated table.
          using(pg.prepareStatement("INSERT INTO audit.relayed_trampoline VALUES (?, ?, ?, ?)")) { statement =>
            statement.setString(1, e.paymentHash.toHex)
            statement.setLong(2, e.nextTrampolineAmount.toLong)
            statement.setString(3, e.nextTrampolineNodeId.toHex)
            statement.setTimestamp(4, e.settledAt.toSqlTimestamp)
            statement.executeUpdate()
          }
        case _ => ()
      }
      // We store each incoming and outgoing part in a dedicated row, to support multi-part payments.
      e.incoming.foreach(i => using(pg.prepareStatement("INSERT INTO audit.relayed VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, e.paymentHash.toHex)
        statement.setLong(2, i.amount.toLong)
        statement.setString(3, i.channelId.toHex)
        statement.setString(4, i.remoteNodeId.toHex)
        statement.setString(5, "IN")
        statement.setString(6, relayType(e))
        statement.setTimestamp(7, i.receivedAt.toSqlTimestamp)
        statement.executeUpdate()
      })
      e.outgoing.foreach(o => using(pg.prepareStatement("INSERT INTO audit.relayed VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, e.paymentHash.toHex)
        statement.setLong(2, o.amount.toLong)
        statement.setString(3, o.channelId.toHex)
        statement.setString(4, o.remoteNodeId.toHex)
        statement.setString(5, "OUT")
        statement.setString(6, relayType(e))
        statement.setTimestamp(7, o.settledAt.toSqlTimestamp)
        statement.executeUpdate()
      })
    }
  }

  override def add(e: TransactionPublished): Unit = withMetrics("audit/add-transaction-published", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.transactions_published VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) { statement =>
        statement.setString(1, e.tx.txid.value.toHex)
        statement.setString(2, e.channelId.toHex)
        statement.setString(3, e.remoteNodeId.toHex)
        statement.setLong(4, e.localMiningFee.toLong)
        statement.setLong(5, e.remoteMiningFee.toLong)
        statement.setLong(6, e.feerate.toLong)
        statement.setLong(7, e.tx.txIn.size)
        statement.setLong(8, e.tx.txOut.size)
        statement.setString(9, e.desc)
        statement.setTimestamp(10, e.timestamp.toSqlTimestamp)
        statement.executeUpdate()
      }
    }
  }

  override def add(e: TransactionConfirmed): Unit = withMetrics("audit/add-transaction-confirmed", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.transactions_confirmed VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) { statement =>
        statement.setString(1, e.tx.txid.value.toHex)
        statement.setString(2, e.channelId.toHex)
        statement.setString(3, e.remoteNodeId.toHex)
        statement.setLong(4, e.tx.txIn.size)
        statement.setLong(5, e.tx.txOut.size)
        statement.setTimestamp(6, e.timestamp.toSqlTimestamp)
        statement.executeUpdate()
      }
    }
  }

  override def addChannelUpdate(u: ChannelUpdateParametersChanged): Unit = withMetrics("audit/add-channel-update", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.channel_updates VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, u.channelId.toHex)
        statement.setString(2, u.remoteNodeId.value.toHex)
        statement.setLong(3, u.channelUpdate.feeBaseMsat.toLong)
        statement.setLong(4, u.channelUpdate.feeProportionalMillionths)
        statement.setLong(5, u.channelUpdate.cltvExpiryDelta.toInt)
        statement.setLong(6, u.channelUpdate.htlcMinimumMsat.toLong)
        statement.setLong(7, u.channelUpdate.htlcMaximumMsat.toLong)
        statement.setTimestamp(8, Timestamp.from(Instant.now()))
        statement.executeUpdate()
      }
    }
  }

  override def addPathFindingExperimentMetrics(m: PathFindingExperimentMetrics): Unit = withMetrics("audit/add-experiment-metrics", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.path_finding_metrics VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::JSONB)")) { statement =>
        statement.setLong(1, m.amount.toLong)
        statement.setLong(2, m.fees.toLong)
        statement.setString(3, m.status)
        statement.setLong(4, m.duration.toMillis)
        statement.setTimestamp(5, m.timestamp.toSqlTimestamp)
        statement.setBoolean(6, m.isMultiPart)
        statement.setString(7, m.experimentName)
        statement.setString(8, m.recipientNodeId.value.toHex)
        statement.setString(9, m.paymentHash.toHex)
        statement.setString(10, serialization.write(m.extraEdges))
        statement.executeUpdate()
      }
    }
  }

  override def listPublished(channelId: ByteVector32): Seq[PublishedTransaction] = withMetrics("audit/list-published-by-channel-id", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT * FROM audit.transactions_published WHERE channel_id = ?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.executeQuery().map { rs =>
          PublishedTransaction(
            txId = TxId(rs.getByteVector32FromHex("tx_id")),
            desc = rs.getString("tx_type"),
            localMiningFee = rs.getLong("local_mining_fee_sat").sat,
            remoteMiningFee = rs.getLong("remote_mining_fee_sat").sat,
            feerate = FeeratePerKw(rs.getLong("feerate_sat_per_kw").sat),
            timestamp = TimestampMilli.fromSqlTimestamp(rs.getTimestamp("timestamp"))
          )
        }.toSeq
      }
    }
  }

  override def listPublished(remoteNodeId: PublicKey, from: TimestampMilli, to: TimestampMilli): Seq[PublishedTransaction] = withMetrics("audit/list-published-by-node-id", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT * FROM audit.transactions_published WHERE node_id = ? AND timestamp BETWEEN ? AND ?")) { statement =>
        statement.setString(1, remoteNodeId.toHex)
        statement.setTimestamp(2, from.toSqlTimestamp)
        statement.setTimestamp(3, to.toSqlTimestamp)
        statement.executeQuery().map { rs =>
          PublishedTransaction(
            txId = TxId(rs.getByteVector32FromHex("tx_id")),
            desc = rs.getString("tx_type"),
            localMiningFee = rs.getLong("local_mining_fee_sat").sat,
            remoteMiningFee = rs.getLong("remote_mining_fee_sat").sat,
            feerate = FeeratePerKw(rs.getLong("feerate_sat_per_kw").sat),
            timestamp = TimestampMilli.fromSqlTimestamp(rs.getTimestamp("timestamp"))
          )
        }.toSeq
      }
    }
  }

  override def listChannelEvents(channelId: ByteVector32, from: TimestampMilli, to: TimestampMilli): Seq[ChannelEvent] = withMetrics("audit/list-channel-events-by-channel-id", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT * FROM audit.channel_events WHERE channel_id = ? AND timestamp BETWEEN ? AND ?")) { statement =>
        statement.setString(1, channelId.toHex)
        statement.setTimestamp(2, from.toSqlTimestamp)
        statement.setTimestamp(3, to.toSqlTimestamp)
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
            timestamp = TimestampMilli.fromSqlTimestamp(rs.getTimestamp("timestamp")),
          )
        }.toSeq
      }
    }
  }

  override def listChannelEvents(remoteNodeId: PublicKey, from: TimestampMilli, to: TimestampMilli): Seq[ChannelEvent] = withMetrics("audit/list-channel-events-by-node-id", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT * FROM audit.channel_events WHERE node_id = ? AND timestamp BETWEEN ? AND ?")) { statement =>
        statement.setString(1, remoteNodeId.toHex)
        statement.setTimestamp(2, from.toSqlTimestamp)
        statement.setTimestamp(3, to.toSqlTimestamp)
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
            timestamp = TimestampMilli.fromSqlTimestamp(rs.getTimestamp("timestamp")),
          )
        }.toSeq
      }
    }
  }

  override def listSent(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentSent] =
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT * FROM audit.sent WHERE settled_at BETWEEN ? AND ?")) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        val result = statement.executeQuery()
          .foldLeft(Map.empty[UUID, PaymentSent]) { (sentByParentId, rs) =>
            val parentId = UUID.fromString(rs.getString("parent_payment_id"))
            val part = PaymentSent.PaymentPart(
              id = UUID.fromString(rs.getString("payment_id")),
              payment = PaymentEvent.OutgoingPayment(
                channelId = rs.getByteVector32FromHex("outgoing_channel_id"),
                remoteNodeId = PublicKey(rs.getByteVectorFromHex("outgoing_node_id")),
                amount = MilliSatoshi(rs.getLong("amount_with_fees_msat")),
                settledAt = TimestampMilli.fromSqlTimestamp(rs.getTimestamp("settled_at"))
              ),
              feesPaid = MilliSatoshi(rs.getLong("fees_msat")),
              route = None, // we don't store the route in the audit DB
              startedAt = TimestampMilli.fromSqlTimestamp(rs.getTimestamp("started_at")))
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
    }

  override def listReceived(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentReceived] =
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT * FROM audit.received WHERE received_at BETWEEN ? AND ?")) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        val result = statement.executeQuery()
          .foldLeft(Map.empty[ByteVector32, PaymentReceived]) { (receivedByHash, rs) =>
            val paymentHash = rs.getByteVector32FromHex("payment_hash")
            val part = PaymentEvent.IncomingPayment(
              channelId = rs.getByteVector32FromHex("incoming_channel_id"),
              remoteNodeId = PublicKey(rs.getByteVectorFromHex("incoming_node_id")),
              amount = MilliSatoshi(rs.getLong("amount_msat")),
              receivedAt = TimestampMilli.fromSqlTimestamp(rs.getTimestamp("received_at")))
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
    }

  override def listRelayed(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentRelayed] =
    inTransaction { pg =>
      val relayedByHash = using(pg.prepareStatement("SELECT * FROM audit.relayed WHERE timestamp BETWEEN ? and ?")) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        statement.executeQuery().foldLeft(Map.empty[ByteVector32, Seq[RelayedPart]]) { (relayedByHash, rs) =>
          val paymentHash = rs.getByteVector32FromHex("payment_hash")
          val part = RelayedPart(
            rs.getByteVector32FromHex("channel_id"),
            PublicKey(rs.getByteVectorFromHex("node_id")),
            MilliSatoshi(rs.getLong("amount_msat")),
            rs.getString("direction"),
            rs.getString("relay_type"),
            TimestampMilli.fromSqlTimestamp(rs.getTimestamp("timestamp")))
          relayedByHash + (paymentHash -> (relayedByHash.getOrElse(paymentHash, Nil) :+ part))
        }
      }
      val trampolineDetails = relayedByHash
        .filter { case (_, parts) => parts.exists(_.relayType == "trampoline") }
        .map {
          case (paymentHash, _) => using(pg.prepareStatement("SELECT * FROM audit.relayed_trampoline WHERE payment_hash = ?")) { statement =>
            statement.setString(1, paymentHash.toHex)
            statement.executeQuery().headOption match {
              case Some(rs) =>
                val nextTrampolineNode = PublicKey(rs.getByteVectorFromHex("next_trampoline_node_id"))
                val nextTrampolineAmount = MilliSatoshi(rs.getLong("next_trampoline_amount_msat"))
                Some(paymentHash -> (nextTrampolineNode, nextTrampolineAmount))
              case None => None
            }
          }
        }.flatten.toMap
      listRelayedInternal(relayedByHash, trampolineDetails, paginated_opt)
    }

  override def listNetworkFees(from: TimestampMilli, to: TimestampMilli): Seq[NetworkFee] =
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT * FROM audit.transactions_confirmed INNER JOIN audit.transactions_published ON audit.transactions_published.tx_id = audit.transactions_confirmed.tx_id  WHERE audit.transactions_confirmed.timestamp BETWEEN ? and ? ORDER BY audit.transactions_confirmed.timestamp")) { statement =>
        statement.setTimestamp(1, from.toSqlTimestamp)
        statement.setTimestamp(2, to.toSqlTimestamp)
        statement.executeQuery().map { rs =>
          NetworkFee(
            remoteNodeId = PublicKey(rs.getByteVectorFromHex("node_id")),
            channelId = rs.getByteVector32FromHex("channel_id"),
            txId = rs.getByteVector32FromHex("tx_id"),
            fee = Satoshi(rs.getLong("local_mining_fee_sat")),
            txType = rs.getString("tx_type"),
            timestamp = TimestampMilli.fromSqlTimestamp(rs.getTimestamp("timestamp")))
        }.toSeq
      }
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
