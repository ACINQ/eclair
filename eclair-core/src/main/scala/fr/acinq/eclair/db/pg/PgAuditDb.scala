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

import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.channel.{ChannelErrorOccurred, LocalChannelUpdate, LocalError, NetworkFeePaid, RemoteError}
import fr.acinq.eclair.db.AuditDb.{NetworkFee, Stats}
import fr.acinq.eclair.db.DbEventHandler.ChannelEvent
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.transactions.Transactions.PlaceHolderPubKey
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong}
import grizzled.slf4j.Logging

import java.sql.{Statement, Timestamp}
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class PgAuditDb(implicit ds: DataSource) extends AuditDb with Logging {

  import PgUtils._
  import ExtendedResultSet._

  val DB_NAME = "audit"
  val CURRENT_VERSION = 8

  case class RelayedPart(channelId: ByteVector32, amount: MilliSatoshi, direction: String, relayType: String, timestamp: Long)

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

      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA audit")

          statement.executeUpdate("CREATE TABLE audit.sent (amount_msat BIGINT NOT NULL, fees_msat BIGINT NOT NULL, recipient_amount_msat BIGINT NOT NULL, payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash TEXT NOT NULL, payment_preimage TEXT NOT NULL, recipient_node_id TEXT NOT NULL, to_channel_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.received (amount_msat BIGINT NOT NULL, payment_hash TEXT NOT NULL, from_channel_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.relayed (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, channel_id TEXT NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.relayed_trampoline (payment_hash TEXT NOT NULL, amount_msat BIGINT NOT NULL, next_node_id TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.network_fees (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, tx_id TEXT NOT NULL, fee_sat BIGINT NOT NULL, tx_type TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.channel_events (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, capacity_sat BIGINT NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE audit.channel_updates (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, fee_base_msat BIGINT NOT NULL, fee_proportional_millionths BIGINT NOT NULL, cltv_expiry_delta BIGINT NOT NULL, htlc_minimum_msat BIGINT NOT NULL, htlc_maximum_msat BIGINT NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")

          statement.executeUpdate("CREATE TABLE audit.channel_errors (channel_id TEXT NOT NULL, node_id TEXT NOT NULL, error_name TEXT NOT NULL, error_message TEXT NOT NULL, is_fatal BOOLEAN NOT NULL, timestamp TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE INDEX sent_timestamp_idx ON audit.sent(timestamp)")
          statement.executeUpdate("CREATE INDEX received_timestamp_idx ON audit.received(timestamp)")
          statement.executeUpdate("CREATE INDEX relayed_timestamp_idx ON audit.relayed(timestamp)")
          statement.executeUpdate("CREATE INDEX relayed_payment_hash_idx ON audit.relayed(payment_hash)")
          statement.executeUpdate("CREATE INDEX relayed_trampoline_timestamp_idx ON audit.relayed_trampoline(timestamp)")
          statement.executeUpdate("CREATE INDEX relayed_trampoline_payment_hash_idx ON audit.relayed_trampoline(payment_hash)")
          statement.executeUpdate("CREATE INDEX network_fees_timestamp_idx ON audit.network_fees(timestamp)")
          statement.executeUpdate("CREATE INDEX channel_events_timestamp_idx ON audit.channel_events(timestamp)")
          statement.executeUpdate("CREATE INDEX channel_errors_timestamp_idx ON audit.channel_errors(timestamp)")
          statement.executeUpdate("CREATE INDEX channel_updates_cid_idx ON audit.channel_updates(channel_id)")
          statement.executeUpdate("CREATE INDEX channel_updates_nid_idx ON audit.channel_updates(node_id)")
          statement.executeUpdate("CREATE INDEX channel_updates_timestamp_idx ON audit.channel_updates(timestamp)")
        case Some(v@(4 | 5 | 6 | 7)) =>
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
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def add(e: ChannelEvent): Unit = withMetrics("audit/add-channel-lifecycle", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.channel_events VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, e.channelId.toHex)
        statement.setString(2, e.remoteNodeId.value.toHex)
        statement.setLong(3, e.capacity.toLong)
        statement.setBoolean(4, e.isFunder)
        statement.setBoolean(5, e.isPrivate)
        statement.setString(6, e.event.label)
        statement.setTimestamp(7, Timestamp.from(Instant.now()))
        statement.executeUpdate()
      }
    }
  }

  override def add(e: PaymentSent): Unit = withMetrics("audit/add-payment-sent", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.sent VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
        e.parts.foreach(p => {
          statement.setLong(1, p.amount.toLong)
          statement.setLong(2, p.feesPaid.toLong)
          statement.setLong(3, e.recipientAmount.toLong)
          statement.setString(4, p.id.toString)
          statement.setString(5, e.id.toString)
          statement.setString(6, e.paymentHash.toHex)
          statement.setString(7, e.paymentPreimage.toHex)
          statement.setString(8, e.recipientNodeId.value.toHex)
          statement.setString(9, p.toChannelId.toHex)
          statement.setTimestamp(10, Timestamp.from(Instant.ofEpochMilli(p.timestamp)))
          statement.addBatch()
        })
        statement.executeBatch()
      }
    }
  }

  override def add(e: PaymentReceived): Unit = withMetrics("audit/add-payment-received", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.received VALUES (?, ?, ?, ?)")) { statement =>
        e.parts.foreach(p => {
          statement.setLong(1, p.amount.toLong)
          statement.setString(2, e.paymentHash.toHex)
          statement.setString(3, p.fromChannelId.toHex)
          statement.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(p.timestamp)))
          statement.addBatch()
        })
        statement.executeBatch()
      }
    }
  }

  override def add(e: PaymentRelayed): Unit = withMetrics("audit/add-payment-relayed", DbBackends.Postgres) {
    inTransaction { pg =>
      val payments = e match {
        case ChannelPaymentRelayed(amountIn, amountOut, _, fromChannelId, toChannelId, ts) =>
          // non-trampoline relayed payments have one input and one output
          Seq(RelayedPart(fromChannelId, amountIn, "IN", "channel", ts), RelayedPart(toChannelId, amountOut, "OUT", "channel", ts))
        case TrampolinePaymentRelayed(_, incoming, outgoing, nextTrampolineNodeId, nextTrampolineAmount, ts) =>
          using(pg.prepareStatement("INSERT INTO audit.relayed_trampoline VALUES (?, ?, ?, ?)")) { statement =>
            statement.setString(1, e.paymentHash.toHex)
            statement.setLong(2, nextTrampolineAmount.toLong)
            statement.setString(3, nextTrampolineNodeId.value.toHex)
            statement.setTimestamp(4, Timestamp.from(Instant.ofEpochMilli(e.timestamp)))
            statement.executeUpdate()
          }
          // trampoline relayed payments do MPP aggregation and may have M inputs and N outputs
          incoming.map(i => RelayedPart(i.channelId, i.amount, "IN", "trampoline", ts)) ++ outgoing.map(o => RelayedPart(o.channelId, o.amount, "OUT", "trampoline", ts))
      }
      for (p <- payments) {
        using(pg.prepareStatement("INSERT INTO audit.relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setString(1, e.paymentHash.toHex)
          statement.setLong(2, p.amount.toLong)
          statement.setString(3, p.channelId.toHex)
          statement.setString(4, p.direction)
          statement.setString(5, p.relayType)
          statement.setTimestamp(6, Timestamp.from(Instant.ofEpochMilli(e.timestamp)))
          statement.executeUpdate()
        }
      }
    }
  }

  override def add(e: NetworkFeePaid): Unit = withMetrics("audit/add-network-fee", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.network_fees VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, e.channelId.toHex)
        statement.setString(2, e.remoteNodeId.value.toHex)
        statement.setString(3, e.tx.txid.toHex)
        statement.setLong(4, e.fee.toLong)
        statement.setString(5, e.txType)
        statement.setTimestamp(6, Timestamp.from(Instant.now()))
        statement.executeUpdate()
      }
    }
  }

  override def add(e: ChannelErrorOccurred): Unit = withMetrics("audit/add-channel-error", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.channel_errors VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
        val (errorName, errorMessage) = e.error match {
          case LocalError(t) => (t.getClass.getSimpleName, t.getMessage)
          case RemoteError(error) => ("remote", error.toAscii)
        }
        statement.setString(1, e.channelId.toHex)
        statement.setString(2, e.remoteNodeId.value.toHex)
        statement.setString(3, errorName)
        statement.setString(4, errorMessage)
        statement.setBoolean(5, e.isFatal)
        statement.setTimestamp(6, Timestamp.from(Instant.now()))
        statement.executeUpdate()
      }
    }
  }

  override def addChannelUpdate(u: LocalChannelUpdate): Unit = withMetrics("audit/add-channel-update", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO audit.channel_updates VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setString(1, u.channelId.toHex)
        statement.setString(2, u.remoteNodeId.value.toHex)
        statement.setLong(3, u.channelUpdate.feeBaseMsat.toLong)
        statement.setLong(4, u.channelUpdate.feeProportionalMillionths)
        statement.setLong(5, u.channelUpdate.cltvExpiryDelta.toInt)
        statement.setLong(6, u.channelUpdate.htlcMinimumMsat.toLong)
        statement.setLong(7, u.channelUpdate.htlcMaximumMsat.map(_.toLong).getOrElse(-1))
        statement.setTimestamp(8, Timestamp.from(Instant.now()))
        statement.executeUpdate()
      }
    }
  }

  override def listSent(from: Long, to: Long): Seq[PaymentSent] =
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT * FROM audit.sent WHERE timestamp BETWEEN ? AND ?")) { statement =>
        statement.setTimestamp(1, Timestamp.from(Instant.ofEpochMilli(from)))
        statement.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(to)))
        statement.executeQuery()
          .foldLeft(Map.empty[UUID, PaymentSent]) { (sentByParentId, rs) =>
            val parentId = UUID.fromString(rs.getString("parent_payment_id"))
            val part = PaymentSent.PartialPayment(
              UUID.fromString(rs.getString("payment_id")),
              MilliSatoshi(rs.getLong("amount_msat")),
              MilliSatoshi(rs.getLong("fees_msat")),
              rs.getByteVector32FromHex("to_channel_id"),
              None, // we don't store the route in the audit DB
              rs.getTimestamp("timestamp").getTime)
            val sent = sentByParentId.get(parentId) match {
              case Some(s) => s.copy(parts = s.parts :+ part)
              case None => PaymentSent(
                parentId,
                rs.getByteVector32FromHex("payment_hash"),
                rs.getByteVector32FromHex("payment_preimage"),
                MilliSatoshi(rs.getLong("recipient_amount_msat")),
                PublicKey(rs.getByteVectorFromHex("recipient_node_id")),
                Seq(part))
            }
            sentByParentId + (parentId -> sent)
          }.values.toSeq.sortBy(_.timestamp)
      }
    }

  override def listReceived(from: Long, to: Long): Seq[PaymentReceived] =
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT * FROM audit.received WHERE timestamp BETWEEN ? AND ?")) { statement =>
        statement.setTimestamp(1, Timestamp.from(Instant.ofEpochMilli(from)))
        statement.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(to)))
        statement.executeQuery()
          .foldLeft(Map.empty[ByteVector32, PaymentReceived]) { (receivedByHash, rs) =>
            val paymentHash = rs.getByteVector32FromHex("payment_hash")
            val part = PaymentReceived.PartialPayment(
              MilliSatoshi(rs.getLong("amount_msat")),
              rs.getByteVector32FromHex("from_channel_id"),
              rs.getTimestamp("timestamp").getTime)
            val received = receivedByHash.get(paymentHash) match {
              case Some(r) => r.copy(parts = r.parts :+ part)
              case None => PaymentReceived(paymentHash, Seq(part))
            }
            receivedByHash + (paymentHash -> received)
          }.values.toSeq.sortBy(_.timestamp)
      }
    }

  override def listRelayed(from: Long, to: Long): Seq[PaymentRelayed] =
    inTransaction { pg =>
      val trampolineByHash = using(pg.prepareStatement("SELECT * FROM audit.relayed_trampoline WHERE timestamp BETWEEN ? and ?")) { statement =>
        statement.setTimestamp(1, Timestamp.from(Instant.ofEpochMilli(from)))
        statement.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(to)))
        statement.executeQuery()
          .foldLeft(Map.empty[ByteVector32, (MilliSatoshi, PublicKey)]) { (trampolineByHash, rs) =>
            val paymentHash = rs.getByteVector32FromHex("payment_hash")
            val amount = MilliSatoshi(rs.getLong("amount_msat"))
            val nodeId = PublicKey(rs.getByteVectorFromHex("next_node_id"))
            trampolineByHash + (paymentHash -> (amount, nodeId))
          }
      }
      val relayedByHash = using(pg.prepareStatement("SELECT * FROM audit.relayed WHERE timestamp BETWEEN ? and ?")) { statement =>
        statement.setTimestamp(1, Timestamp.from(Instant.ofEpochMilli(from)))
        statement.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(to)))
        statement.executeQuery()
          .foldLeft(Map.empty[ByteVector32, Seq[RelayedPart]]) { (relayedByHash, rs) =>
            val paymentHash = rs.getByteVector32FromHex("payment_hash")
            val part = RelayedPart(
              rs.getByteVector32FromHex("channel_id"),
              MilliSatoshi(rs.getLong("amount_msat")),
              rs.getString("direction"),
              rs.getString("relay_type"),
              rs.getTimestamp("timestamp").getTime)
            relayedByHash + (paymentHash -> (relayedByHash.getOrElse(paymentHash, Nil) :+ part))
          }
      }
      relayedByHash.flatMap {
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
    }

  override def listNetworkFees(from: Long, to: Long): Seq[NetworkFee] =
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT * FROM audit.network_fees WHERE timestamp BETWEEN ? and ? ORDER BY timestamp")) { statement =>
        statement.setTimestamp(1, Timestamp.from(Instant.ofEpochMilli(from)))
        statement.setTimestamp(2, Timestamp.from(Instant.ofEpochMilli(to)))
        statement.executeQuery().map { rs =>
          NetworkFee(
            remoteNodeId = PublicKey(rs.getByteVectorFromHex("node_id")),
            channelId = rs.getByteVector32FromHex("channel_id"),
            txId = rs.getByteVector32FromHex("tx_id"),
            fee = Satoshi(rs.getLong("fee_sat")),
            txType = rs.getString("tx_type"),
            timestamp = rs.getTimestamp("timestamp").getTime)
        }.toSeq
      }
    }

  override def stats(from: Long, to: Long): Seq[Stats] = {
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
    // Channels opened by our peers won't have any entry in the network_fees table, but we still want to compute stats for them.
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

  override def close(): Unit = ()

}
