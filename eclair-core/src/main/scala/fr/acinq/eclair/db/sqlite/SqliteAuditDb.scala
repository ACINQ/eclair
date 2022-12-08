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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.channel._
import fr.acinq.eclair.db.AuditDb.{NetworkFee, Stats}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.transactions.Transactions.PlaceHolderPubKey
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, Paginated, TimestampMilli}
import grizzled.slf4j.Logging

import java.sql.{Connection, Statement}
import java.util.UUID

object SqliteAuditDb {
  val DB_NAME = "audit"
  val CURRENT_VERSION = 8
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

    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, recipient_amount_msat INTEGER NOT NULL, payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, recipient_node_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE relayed (payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, channel_id BLOB NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE relayed_trampoline (payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, next_node_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE channel_errors (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, error_name TEXT NOT NULL, error_message TEXT NOT NULL, is_fatal INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE channel_updates (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL, cltv_expiry_delta INTEGER NOT NULL, htlc_minimum_msat INTEGER NOT NULL, htlc_maximum_msat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE path_finding_metrics (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, status TEXT NOT NULL, duration_ms INTEGER NOT NULL, timestamp INTEGER NOT NULL, is_mpp INTEGER NOT NULL, experiment_name TEXT NOT NULL, recipient_node_id BLOB NOT NULL)")
        statement.executeUpdate("CREATE TABLE transactions_published (tx_id BLOB NOT NULL PRIMARY KEY, channel_id BLOB NOT NULL, node_id BLOB NOT NULL, mining_fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE transactions_confirmed (tx_id BLOB NOT NULL PRIMARY KEY, channel_id BLOB NOT NULL, node_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")

        statement.executeUpdate("CREATE INDEX sent_timestamp_idx ON sent(timestamp)")
        statement.executeUpdate("CREATE INDEX received_timestamp_idx ON received(timestamp)")
        statement.executeUpdate("CREATE INDEX relayed_timestamp_idx ON relayed(timestamp)")
        statement.executeUpdate("CREATE INDEX relayed_payment_hash_idx ON relayed(payment_hash)")
        statement.executeUpdate("CREATE INDEX relayed_trampoline_timestamp_idx ON relayed_trampoline(timestamp)")
        statement.executeUpdate("CREATE INDEX relayed_trampoline_payment_hash_idx ON relayed_trampoline(payment_hash)")
        statement.executeUpdate("CREATE INDEX channel_events_timestamp_idx ON channel_events(timestamp)")
        statement.executeUpdate("CREATE INDEX channel_errors_timestamp_idx ON channel_errors(timestamp)")
        statement.executeUpdate("CREATE INDEX channel_updates_cid_idx ON channel_updates(channel_id)")
        statement.executeUpdate("CREATE INDEX channel_updates_nid_idx ON channel_updates(node_id)")
        statement.executeUpdate("CREATE INDEX channel_updates_timestamp_idx ON channel_updates(timestamp)")
        statement.executeUpdate("CREATE INDEX metrics_status_idx ON path_finding_metrics(status)")
        statement.executeUpdate("CREATE INDEX metrics_timestamp_idx ON path_finding_metrics(timestamp)")
        statement.executeUpdate("CREATE INDEX metrics_mpp_idx ON path_finding_metrics(is_mpp)")
        statement.executeUpdate("CREATE INDEX metrics_name_idx ON path_finding_metrics(experiment_name)")
        statement.executeUpdate("CREATE INDEX transactions_published_timestamp_idx ON transactions_published(timestamp)")
        statement.executeUpdate("CREATE INDEX transactions_confirmed_timestamp_idx ON transactions_confirmed(timestamp)")
      case Some(v@(1 | 2 | 3 | 4 | 5 | 6 | 7)) =>
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
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def add(e: ChannelEvent): Unit = withMetrics("audit/add-channel-lifecycle", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO channel_events VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, e.channelId.toArray)
      statement.setBytes(2, e.remoteNodeId.value.toArray)
      statement.setLong(3, e.capacity.toLong)
      statement.setBoolean(4, e.isInitiator)
      statement.setBoolean(5, e.isPrivate)
      statement.setString(6, e.event.label)
      statement.setLong(7, TimestampMilli.now().toLong)
      statement.executeUpdate()
    }
  }

  override def add(e: PaymentSent): Unit = withMetrics("audit/add-payment-sent", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO sent VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
      e.parts.foreach(p => {
        statement.setLong(1, p.amount.toLong)
        statement.setLong(2, p.feesPaid.toLong)
        statement.setLong(3, e.recipientAmount.toLong)
        statement.setString(4, p.id.toString)
        statement.setString(5, e.id.toString)
        statement.setBytes(6, e.paymentHash.toArray)
        statement.setBytes(7, e.paymentPreimage.toArray)
        statement.setBytes(8, e.recipientNodeId.value.toArray)
        statement.setBytes(9, p.toChannelId.toArray)
        statement.setLong(10, p.timestamp.toLong)
        statement.addBatch()
      })
      statement.executeBatch()
    }
  }

  override def add(e: PaymentReceived): Unit = withMetrics("audit/add-payment-received", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO received VALUES (?, ?, ?, ?)")) { statement =>
      e.parts.foreach(p => {
        statement.setLong(1, p.amount.toLong)
        statement.setBytes(2, e.paymentHash.toArray)
        statement.setBytes(3, p.fromChannelId.toArray)
        statement.setLong(4, p.timestamp.toLong)
        statement.addBatch()
      })
      statement.executeBatch()
    }
  }

  override def add(e: PaymentRelayed): Unit = withMetrics("audit/add-payment-relayed", DbBackends.Sqlite) {
    val payments = e match {
      case ChannelPaymentRelayed(amountIn, amountOut, _, fromChannelId, toChannelId, ts) =>
        // non-trampoline relayed payments have one input and one output
        Seq(RelayedPart(fromChannelId, amountIn, "IN", "channel", ts), RelayedPart(toChannelId, amountOut, "OUT", "channel", ts))
      case TrampolinePaymentRelayed(_, incoming, outgoing, nextTrampolineNodeId, nextTrampolineAmount, ts) =>
        using(sqlite.prepareStatement("INSERT INTO relayed_trampoline VALUES (?, ?, ?, ?)")) { statement =>
          statement.setBytes(1, e.paymentHash.toArray)
          statement.setLong(2, nextTrampolineAmount.toLong)
          statement.setBytes(3, nextTrampolineNodeId.value.toArray)
          statement.setLong(4, e.timestamp.toLong)
          statement.executeUpdate()
        }
        // trampoline relayed payments do MPP aggregation and may have M inputs and N outputs
        incoming.map(i => RelayedPart(i.channelId, i.amount, "IN", "trampoline", ts)) ++
          outgoing.map(o => RelayedPart(o.channelId, o.amount, "OUT", "trampoline", ts))
    }
    for (p <- payments) {
      using(sqlite.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setBytes(1, e.paymentHash.toArray)
        statement.setLong(2, p.amount.toLong)
        statement.setBytes(3, p.channelId.toArray)
        statement.setString(4, p.direction)
        statement.setString(5, p.relayType)
        statement.setLong(6, e.timestamp.toLong)
        statement.executeUpdate()
      }
    }
  }

  override def add(e: TransactionPublished): Unit = withMetrics("audit/add-transaction-published", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO transactions_published VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, e.tx.txid.toArray)
      statement.setBytes(2, e.channelId.toArray)
      statement.setBytes(3, e.remoteNodeId.value.toArray)
      statement.setLong(4, e.miningFee.toLong)
      statement.setString(5, e.desc)
      statement.setLong(6, TimestampMilli.now().toLong)
      statement.executeUpdate()
    }
  }

  override def add(e: TransactionConfirmed): Unit = withMetrics("audit/add-transaction-confirmed", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO transactions_confirmed VALUES (?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, e.tx.txid.toArray)
      statement.setBytes(2, e.channelId.toArray)
      statement.setBytes(3, e.remoteNodeId.value.toArray)
      statement.setLong(4, TimestampMilli.now().toLong)
      statement.executeUpdate()
    }
  }

  override def add(e: ChannelErrorOccurred): Unit = withMetrics("audit/add-channel-error", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO channel_errors VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      val (errorName, errorMessage) = e.error match {
        case LocalError(t) => (t.getClass.getSimpleName, t.getMessage)
        case RemoteError(error) => ("remote", error.toAscii)
      }
      statement.setBytes(1, e.channelId.toArray)
      statement.setBytes(2, e.remoteNodeId.value.toArray)
      statement.setString(3, errorName)
      statement.setString(4, errorMessage)
      statement.setBoolean(5, e.isFatal)
      statement.setLong(6, TimestampMilli.now().toLong)
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

  override def listSent(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentSent] =
    using(sqlite.prepareStatement("SELECT * FROM sent WHERE timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      val result = statement.executeQuery()
        .foldLeft(Map.empty[UUID, PaymentSent]) { (sentByParentId, rs) =>
          val parentId = UUID.fromString(rs.getString("parent_payment_id"))
          val part = PaymentSent.PartialPayment(
            UUID.fromString(rs.getString("payment_id")),
            MilliSatoshi(rs.getLong("amount_msat")),
            MilliSatoshi(rs.getLong("fees_msat")),
            rs.getByteVector32("to_channel_id"),
            None, // we don't store the route in the audit DB
            TimestampMilli(rs.getLong("timestamp")))
          val sent = sentByParentId.get(parentId) match {
            case Some(s) => s.copy(parts = s.parts :+ part)
            case None => PaymentSent(
              parentId,
              rs.getByteVector32("payment_hash"),
              rs.getByteVector32("payment_preimage"),
              MilliSatoshi(rs.getLong("recipient_amount_msat")),
              PublicKey(rs.getByteVector("recipient_node_id")),
              Seq(part))
          }
          sentByParentId + (parentId -> sent)
        }.values.toSeq.sortBy(_.timestamp)
      paginated_opt match {
        case Some(paginated) => result.slice(paginated.skip, paginated.skip + paginated.count)
        case None => result
      }
    }

  override def listReceived(from: TimestampMilli, to: TimestampMilli, paginated_opt: Option[Paginated] = None): Seq[PaymentReceived] =
    using(sqlite.prepareStatement("SELECT * FROM received WHERE timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setLong(1, from.toLong)
      statement.setLong(2, to.toLong)
      val result = statement.executeQuery()
        .foldLeft(Map.empty[ByteVector32, PaymentReceived]) { (receivedByHash, rs) =>
          val paymentHash = rs.getByteVector32("payment_hash")
          val part = PaymentReceived.PartialPayment(
            MilliSatoshi(rs.getLong("amount_msat")),
            rs.getByteVector32("from_channel_id"),
            TimestampMilli(rs.getLong("timestamp")))
          val received = receivedByHash.get(paymentHash) match {
            case Some(r) => r.copy(parts = r.parts :+ part)
            case None => PaymentReceived(paymentHash, Seq(part))
          }
          receivedByHash + (paymentHash -> received)
        }.values.toSeq.sortBy(_.timestamp)
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
        val incoming = parts.filter(_.direction == "IN").map(p => PaymentRelayed.Part(p.amount, p.channelId)).sortBy(_.amount)
        val outgoing = parts.filter(_.direction == "OUT").map(p => PaymentRelayed.Part(p.amount, p.channelId)).sortBy(_.amount)
        parts.headOption match {
          case Some(RelayedPart(_, _, _, "channel", timestamp)) => incoming.zip(outgoing).map {
            case (in, out) => ChannelPaymentRelayed(in.amount, out.amount, paymentHash, in.channelId, out.channelId, timestamp)
          }
          case Some(RelayedPart(_, _, _, "trampoline", timestamp)) =>
            val (nextTrampolineAmount, nextTrampolineNodeId) = trampolineByHash.getOrElse(paymentHash, (0 msat, PlaceHolderPubKey))
            TrampolinePaymentRelayed(paymentHash, incoming, outgoing, nextTrampolineNodeId, nextTrampolineAmount, timestamp) :: Nil
          case _ => Nil
        }
    }.toSeq.sortBy(_.timestamp)
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
            remoteNodeId = PublicKey(rs.getByteVector("node_id")),
            channelId = rs.getByteVector32("channel_id"),
            txId = rs.getByteVector32("tx_id"),
            fee = Satoshi(rs.getLong("mining_fee_sat")),
            txType = rs.getString("tx_type"),
            timestamp = TimestampMilli(rs.getLong("timestamp")))
        }.toSeq
    }

  override def stats(from: TimestampMilli, to: TimestampMilli): Seq[Stats] = {
    val networkFees = listNetworkFees(from, to).foldLeft(Map.empty[ByteVector32, Satoshi]) { (feeByChannelId, f) =>
      feeByChannelId + (f.channelId -> (feeByChannelId.getOrElse(f.channelId, 0 sat) + f.fee))
    }
    case class Relayed(amount: MilliSatoshi, fee: MilliSatoshi, direction: String)
    val relayed = listRelayed(from, to).foldLeft(Map.empty[ByteVector32, Seq[Relayed]]) { (previous, e) =>
      // NB: we must avoid counting the fee twice: we associate it to the outgoing channels rather than the incoming ones.
      val current = e match {
        case c: ChannelPaymentRelayed => Map(
          c.fromChannelId -> (Relayed(c.amountIn, 0 msat, "IN") +: previous.getOrElse(c.fromChannelId, Nil)),
          c.toChannelId -> (Relayed(c.amountOut, c.amountIn - c.amountOut, "OUT") +: previous.getOrElse(c.toChannelId, Nil)),
        )
        case t: TrampolinePaymentRelayed =>
          // We ensure a trampoline payment is counted only once per channel and per direction (if multiple HTLCs were
          // sent from/to the same channel, we group them).
          val in = t.incoming.groupBy(_.channelId).map { case (channelId, parts) => (channelId, Relayed(parts.map(_.amount).sum, 0 msat, "IN")) }.toSeq
          val out = t.outgoing.groupBy(_.channelId).map { case (channelId, parts) =>
            val fee = (t.amountIn - t.amountOut) * parts.length / t.outgoing.length // we split the fee among outgoing channels
            (channelId, Relayed(parts.map(_.amount).sum, fee, "OUT"))
          }.toSeq
          (in ++ out).groupBy(_._1).map { case (channelId, payments) => (channelId, payments.map(_._2) ++ previous.getOrElse(channelId, Nil)) }
      }
      previous ++ current
    }
    // Channels opened by our peers won't have any network fees paid by us, but we still want to compute stats for them.
    val allChannels = networkFees.keySet ++ relayed.keySet
    allChannels.toSeq.flatMap(channelId => {
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
    })
  }
}
