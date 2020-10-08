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

import java.sql.{Connection, Statement}
import java.util.UUID

import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.{ByteVector32, Satoshi}
import fr.acinq.eclair.channel.{ChannelErrorOccurred, LocalError, NetworkFeePaid, RemoteError}
import fr.acinq.eclair.db._
import fr.acinq.eclair.payment._
import fr.acinq.eclair.{LongToBtcAmount, MilliSatoshi}
import grizzled.slf4j.Logging

import scala.collection.immutable.Queue

class SqliteAuditDb(sqlite: Connection) extends AuditDb with Logging {

  import SqliteUtils._
  import ExtendedResultSet._

  val DB_NAME = "audit"
  val CURRENT_VERSION = 4

  case class RelayedPart(channelId: ByteVector32, amount: MilliSatoshi, direction: String, relayType: String, timestamp: Long)

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    def migration12(statement: Statement): Int = {
      val ZERO_UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
      statement.executeUpdate(s"ALTER TABLE sent ADD id BLOB DEFAULT '${ZERO_UUID.toString}' NOT NULL")
    }

    def migration23(statement: Statement): Int = {
      statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_errors (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, error_name TEXT NOT NULL, error_message TEXT NOT NULL, is_fatal INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_errors_timestamp_idx ON channel_errors(timestamp)")
    }

    def migration34(statement: Statement): Int = {
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

      statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_timestamp_idx ON sent(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_timestamp_idx ON relayed(timestamp)")
      statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_payment_hash_idx ON relayed(payment_hash)")
    }

    getVersion(statement, DB_NAME, CURRENT_VERSION) match {
      case 1 => // previous version let's migrate
        logger.warn(s"migrating db $DB_NAME, found version=1 current=$CURRENT_VERSION")
        migration12(statement)
        migration23(statement)
        migration34(statement)
        setVersion(statement, DB_NAME, CURRENT_VERSION)
      case 2 =>
        logger.warn(s"migrating db $DB_NAME, found version=2 current=$CURRENT_VERSION")
        migration23(statement)
        migration34(statement)
        setVersion(statement, DB_NAME, CURRENT_VERSION)
      case 3 =>
        logger.warn(s"migrating db $DB_NAME, found version=3 current=$CURRENT_VERSION")
        migration34(statement)
        setVersion(statement, DB_NAME, CURRENT_VERSION)
      case CURRENT_VERSION =>
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, recipient_amount_msat INTEGER NOT NULL, payment_id TEXT NOT NULL, parent_payment_id TEXT NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, recipient_node_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS relayed (payment_hash BLOB NOT NULL, amount_msat INTEGER NOT NULL, channel_id BLOB NOT NULL, direction TEXT NOT NULL, relay_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS network_fees (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, tx_id BLOB NOT NULL, fee_sat INTEGER NOT NULL, tx_type TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_events (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, capacity_sat INTEGER NOT NULL, is_funder BOOLEAN NOT NULL, is_private BOOLEAN NOT NULL, event TEXT NOT NULL, timestamp INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS channel_errors (channel_id BLOB NOT NULL, node_id BLOB NOT NULL, error_name TEXT NOT NULL, error_message TEXT NOT NULL, is_fatal INTEGER NOT NULL, timestamp INTEGER NOT NULL)")

        statement.executeUpdate("CREATE INDEX IF NOT EXISTS sent_timestamp_idx ON sent(timestamp)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS received_timestamp_idx ON received(timestamp)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_timestamp_idx ON relayed(timestamp)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS relayed_payment_hash_idx ON relayed(payment_hash)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS network_fees_timestamp_idx ON network_fees(timestamp)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_events_timestamp_idx ON channel_events(timestamp)")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS channel_errors_timestamp_idx ON channel_errors(timestamp)")
      case unknownVersion => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
  }

  override def add(e: ChannelLifecycleEvent): Unit =
    using(sqlite.prepareStatement("INSERT INTO channel_events VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, e.channelId.toArray)
      statement.setBytes(2, e.remoteNodeId.value.toArray)
      statement.setLong(3, e.capacity.toLong)
      statement.setBoolean(4, e.isFunder)
      statement.setBoolean(5, e.isPrivate)
      statement.setString(6, e.event)
      statement.setLong(7, System.currentTimeMillis)
      statement.executeUpdate()
    }

  override def add(e: PaymentSent): Unit =
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
        statement.setLong(10, p.timestamp)
        statement.addBatch()
      })
      statement.executeBatch()
    }

  override def add(e: PaymentReceived): Unit =
    using(sqlite.prepareStatement("INSERT INTO received VALUES (?, ?, ?, ?)")) { statement =>
      e.parts.foreach(p => {
        statement.setLong(1, p.amount.toLong)
        statement.setBytes(2, e.paymentHash.toArray)
        statement.setBytes(3, p.fromChannelId.toArray)
        statement.setLong(4, p.timestamp)
        statement.addBatch()
      })
      statement.executeBatch()
    }

  override def add(e: PaymentRelayed): Unit = {
    val payments = e match {
      case ChannelPaymentRelayed(amountIn, amountOut, _, fromChannelId, toChannelId, ts) =>
        // non-trampoline relayed payments have one input and one output
        Seq(RelayedPart(fromChannelId, amountIn, "IN", "channel", ts), RelayedPart(toChannelId, amountOut, "OUT", "channel", ts))
      case TrampolinePaymentRelayed(_, incoming, outgoing, ts) =>
        // trampoline relayed payments do MPP aggregation and may have M inputs and N outputs
        incoming.map(i => RelayedPart(i.channelId, i.amount, "IN", "trampoline", ts)) ++ outgoing.map(o => RelayedPart(o.channelId, o.amount, "OUT", "trampoline", ts))
    }
    for (p <- payments) {
      using(sqlite.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
        statement.setBytes(1, e.paymentHash.toArray)
        statement.setLong(2, p.amount.toLong)
        statement.setBytes(3, p.channelId.toArray)
        statement.setString(4, p.direction)
        statement.setString(5, p.relayType)
        statement.setLong(6, e.timestamp)
        statement.executeUpdate()
      }
    }
  }

  override def add(e: NetworkFeePaid): Unit =
    using(sqlite.prepareStatement("INSERT INTO network_fees VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, e.channelId.toArray)
      statement.setBytes(2, e.remoteNodeId.value.toArray)
      statement.setBytes(3, e.tx.txid.toArray)
      statement.setLong(4, e.fee.toLong)
      statement.setString(5, e.txType)
      statement.setLong(6, System.currentTimeMillis)
      statement.executeUpdate()
    }

  override def add(e: ChannelErrorOccurred): Unit =
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
      statement.setLong(6, System.currentTimeMillis)
      statement.executeUpdate()
    }

  override def listSent(from: Long, to: Long): Seq[PaymentSent] =
    using(sqlite.prepareStatement("SELECT * FROM sent WHERE timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setLong(1, from)
      statement.setLong(2, to)
      val rs = statement.executeQuery()
      var sentByParentId = Map.empty[UUID, PaymentSent]
      while (rs.next()) {
        val parentId = UUID.fromString(rs.getString("parent_payment_id"))
        val part = PaymentSent.PartialPayment(
          UUID.fromString(rs.getString("payment_id")),
          MilliSatoshi(rs.getLong("amount_msat")),
          MilliSatoshi(rs.getLong("fees_msat")),
          rs.getByteVector32("to_channel_id"),
          None, // we don't store the route in the audit DB
          rs.getLong("timestamp"))
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
        sentByParentId = sentByParentId + (parentId -> sent)
      }
      sentByParentId.values.toSeq.sortBy(_.timestamp)
    }

  override def listReceived(from: Long, to: Long): Seq[PaymentReceived] =
    using(sqlite.prepareStatement("SELECT * FROM received WHERE timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setLong(1, from)
      statement.setLong(2, to)
      val rs = statement.executeQuery()
      var receivedByHash = Map.empty[ByteVector32, PaymentReceived]
      while (rs.next()) {
        val paymentHash = rs.getByteVector32("payment_hash")
        val part = PaymentReceived.PartialPayment(
          MilliSatoshi(rs.getLong("amount_msat")),
          rs.getByteVector32("from_channel_id"),
          rs.getLong("timestamp"))
        val received = receivedByHash.get(paymentHash) match {
          case Some(r) => r.copy(parts = r.parts :+ part)
          case None => PaymentReceived(paymentHash, Seq(part))
        }
        receivedByHash = receivedByHash + (paymentHash -> received)
      }
      receivedByHash.values.toSeq.sortBy(_.timestamp)
    }

  override def listRelayed(from: Long, to: Long): Seq[PaymentRelayed] =
    using(sqlite.prepareStatement("SELECT * FROM relayed WHERE timestamp >= ? AND timestamp < ?")) { statement =>
      statement.setLong(1, from)
      statement.setLong(2, to)
      val rs = statement.executeQuery()
      var relayedByHash = Map.empty[ByteVector32, Seq[RelayedPart]]
      while (rs.next()) {
        val paymentHash = rs.getByteVector32("payment_hash")
        val part = RelayedPart(
          rs.getByteVector32("channel_id"),
          MilliSatoshi(rs.getLong("amount_msat")),
          rs.getString("direction"),
          rs.getString("relay_type"),
          rs.getLong("timestamp"))
        relayedByHash = relayedByHash + (paymentHash -> (relayedByHash.getOrElse(paymentHash, Nil) :+ part))
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
            case Some(RelayedPart(_, _, _, "trampoline", timestamp)) => TrampolinePaymentRelayed(paymentHash, incoming, outgoing, timestamp) :: Nil
            case _ => Nil
          }
      }.toSeq.sortBy(_.timestamp)
    }

  override def listNetworkFees(from: Long, to: Long): Seq[NetworkFee] =
    using(sqlite.prepareStatement("SELECT * FROM network_fees WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp")) { statement =>
      statement.setLong(1, from)
      statement.setLong(2, to)
      val rs = statement.executeQuery()
      var q: Queue[NetworkFee] = Queue()
      while (rs.next()) {
        q = q :+ NetworkFee(
          remoteNodeId = PublicKey(rs.getByteVector("node_id")),
          channelId = rs.getByteVector32("channel_id"),
          txId = rs.getByteVector32("tx_id"),
          fee = Satoshi(rs.getLong("fee_sat")),
          txType = rs.getString("tx_type"),
          timestamp = rs.getLong("timestamp"))
      }
      q
    }

  override def stats: Seq[Stats] = {
    val networkFees = listNetworkFees(0, System.currentTimeMillis + 1).foldLeft(Map.empty[ByteVector32, Satoshi]) { case (feeByChannelId, f) =>
      feeByChannelId + (f.channelId -> (feeByChannelId.getOrElse(f.channelId, 0 sat) + f.fee))
    }
    val relayed = listRelayed(0, System.currentTimeMillis + 1).foldLeft(Map.empty[ByteVector32, Seq[PaymentRelayed]]) { case (relayedByChannelId, e) =>
      val relayedTo = e match {
        case c: ChannelPaymentRelayed => Set(c.toChannelId)
        case t: TrampolinePaymentRelayed => t.outgoing.map(_.channelId).toSet
      }
      val updated = relayedTo.map(channelId => (channelId, relayedByChannelId.getOrElse(channelId, Nil) :+ e)).toMap
      relayedByChannelId ++ updated
    }
    // Channels opened by our peers won't have any entry in the network_fees table, but we still want to compute stats for them.
    val allChannels = networkFees.keySet ++ relayed.keySet
    allChannels.map(channelId => {
      val networkFee = networkFees.getOrElse(channelId, 0 sat)
      val r = relayed.getOrElse(channelId, Nil)
      val paymentCount = r.length
      if (paymentCount == 0) {
        Stats(channelId, 0 sat, 0, 0 sat, networkFee)
      } else {
        val avgPaymentAmount = r.map(_.amountOut).sum / paymentCount
        val relayFee = r.map {
          case c: ChannelPaymentRelayed => c.amountIn - c.amountOut
          case t: TrampolinePaymentRelayed => (t.amountIn - t.amountOut) * t.outgoing.count(_.channelId == channelId) / t.outgoing.length
        }.sum
        Stats(channelId, avgPaymentAmount.truncateToSatoshi, paymentCount, relayFee.truncateToSatoshi, networkFee)
      }
    }).toSeq
  }

  // used by mobile apps
  override def close(): Unit = sqlite.close()

}
