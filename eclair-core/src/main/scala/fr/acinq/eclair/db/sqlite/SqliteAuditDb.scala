/*
 * Copyright 2018 ACINQ SAS
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

import java.sql.Connection

import fr.acinq.bitcoin.{BinaryData, MilliSatoshi}
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.db.AuditDb
import fr.acinq.eclair.payment.{PaymentEvent, PaymentReceived, PaymentRelayed, PaymentSent}
import fr.acinq.eclair.wire.ChannelCodecs.stateDataCodec

import scala.collection.immutable.Queue

class SqliteAuditDb(sqlite: Connection) extends AuditDb {

  import SqliteUtils._

  val DB_NAME = "audit"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS sent (amount_msat INTEGER NOT NULL, fees_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, payment_preimage BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS received (amount_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS relayed (amount_in_msat INTEGER NOT NULL, amount_out_msat INTEGER NOT NULL, payment_hash BLOB NOT NULL, from_channel_id BLOB NOT NULL, to_channel_id BLOB NOT NULL, timestamp INTEGER NOT NULL)")
  }

  override def add(paymentSent: PaymentSent): Unit =
    using(sqlite.prepareStatement("INSERT INTO sent VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, paymentSent.amount.toLong)
      statement.setLong(2, paymentSent.feesPaid.toLong)
      statement.setBytes(3, paymentSent.paymentHash)
      statement.setBytes(4, paymentSent.paymentPreimage)
      statement.setBytes(5, paymentSent.toChannelId)
      statement.setLong(6, paymentSent.timestamp)
      statement.executeUpdate()
    }

  override def add(paymentReceived: PaymentReceived): Unit =
    using(sqlite.prepareStatement("INSERT INTO received VALUES (?, ?, ?, ?)")) { statement =>
      statement.setLong(1, paymentReceived.amount.toLong)
      statement.setBytes(2, paymentReceived.paymentHash)
      statement.setBytes(3, paymentReceived.fromChannelId)
      statement.setLong(4, paymentReceived.timestamp)
      statement.executeUpdate()
    }

  override def add(paymentRelayed: PaymentRelayed): Unit =
    using(sqlite.prepareStatement("INSERT INTO relayed VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setLong(1, paymentRelayed.amountIn.toLong)
      statement.setLong(2, paymentRelayed.amountOut.toLong)
      statement.setBytes(3, paymentRelayed.paymentHash)
      statement.setBytes(4, paymentRelayed.fromChannelId)
      statement.setBytes(5, paymentRelayed.toChannelId)
      statement.setLong(6, paymentRelayed.timestamp)
      statement.executeUpdate()
    }

  override def listSent: Seq[PaymentSent] =
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT * FROM sent")
      var q: Queue[PaymentSent] = Queue()
      while (rs.next()) {
        q = q :+ PaymentSent(
          amount = MilliSatoshi(rs.getLong("amount_msat")),
          feesPaid = MilliSatoshi(rs.getLong("fees_msat")),
          paymentHash = BinaryData(rs.getBytes("payment_hash")),
          paymentPreimage = BinaryData(rs.getBytes("payment_preimage")),
          toChannelId = BinaryData(rs.getBytes("to_channel_id")),
          timestamp = rs.getLong("timestamp"))
      }
      q
    }

  override def listReceived: Seq[PaymentReceived] =
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT * FROM received")
      var q: Queue[PaymentReceived] = Queue()
      while (rs.next()) {
        q = q :+ PaymentReceived(
          amount = MilliSatoshi(rs.getLong("amount_msat")),
          paymentHash = BinaryData(rs.getBytes("payment_hash")),
          fromChannelId = BinaryData(rs.getBytes("from_channel_id")),
          timestamp = rs.getLong("timestamp"))
      }
      q
    }

  override def listRelayed: Seq[PaymentRelayed] =
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT * FROM relayed")
      var q: Queue[PaymentRelayed] = Queue()
      while (rs.next()) {
        q = q :+ PaymentRelayed(
          amountIn = MilliSatoshi(rs.getLong("amount_in_msat")),
          amountOut = MilliSatoshi(rs.getLong("amount_out_msat")),
          paymentHash = BinaryData(rs.getBytes("payment_hash")),
          fromChannelId = BinaryData(rs.getBytes("from_channel_id")),
          toChannelId = BinaryData(rs.getBytes("to_channel_id")),
          timestamp = rs.getLong("timestamp"))
      }
      q
    }


}
