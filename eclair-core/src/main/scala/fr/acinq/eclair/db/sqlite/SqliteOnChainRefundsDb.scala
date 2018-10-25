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
import fr.acinq.eclair.db.OnChainRefundsDb
import fr.acinq.eclair.payment.{PaymentLostOnChain, PaymentSettlingOnChain}

class SqliteOnChainRefundsDb(sqlite: Connection) extends OnChainRefundsDb {

  import SqliteUtils._

  val DB_NAME = "on_chain_refunds"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS settling_on_chain (payment_hash BLOB NOT NULL, tx_id BLOB NOT NULL, refund_type STRING NOT NULL, is_done INTEGER NOT NULL, off_chain_amount INTEGER NOT NULL, on_chain_amount INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS lost_on_chain (payment_hash BLOB NOT NULL UNIQUE, lost_amount INTEGER NOT NULL, timestamp INTEGER NOT NULL)")

    // lost_on_chain(payment_hash) INDEX is already there because it's unique
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS payment_hash_idx ON settling_on_chain(payment_hash)")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS tx_id_idx ON settling_on_chain(tx_id)")
  }

  override def addSettlingOnChain(paymentSettlingOnChain: PaymentSettlingOnChain): Unit = {
    using(sqlite.prepareStatement("INSERT INTO settling_on_chain VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, paymentSettlingOnChain.paymentHash)
      statement.setBytes(2, paymentSettlingOnChain.txid)
      statement.setString(3, paymentSettlingOnChain.refundType)
      statement.setBoolean(4, paymentSettlingOnChain.isDone)
      statement.setLong(5, paymentSettlingOnChain.offChainAmount.amount)
      statement.setLong(6, paymentSettlingOnChain.onChainAmount.amount)
      statement.setLong(7, paymentSettlingOnChain.timestamp)
      statement.executeUpdate()
    }
  }

  override def addLostOnChain(paymentLostOnChain: PaymentLostOnChain): Unit = {
    using(sqlite.prepareStatement("INSERT INTO lost_on_chain VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, paymentLostOnChain.paymentHash)
      statement.setLong(2, paymentLostOnChain.amount.amount)
      statement.setLong(3, paymentLostOnChain.timestamp)
      statement.executeUpdate()
    }
  }

  override def getSettlingOnChain(paymentHashOrTxid: BinaryData): Option[PaymentSettlingOnChain] = {
    // We may have multiple double-spends for the same payment_hash but only one of them can end up on chain so we order by is_done = 1 first
    using(sqlite.prepareStatement("SELECT * FROM settling_on_chain WHERE payment_hash = ? OR tx_id=? ORDER BY is_done DESC")) { statement =>
      statement.setBytes(1, paymentHashOrTxid)
      statement.setBytes(2, paymentHashOrTxid)
      val rs = statement.executeQuery()
      if (rs.next()) {
        val txid = rs.getBytes("tx_id")
        val paymentHash = rs.getBytes("payment_hash")
        val refundType = rs.getString("refund_type")
        val isDone = rs.getBoolean("is_done")
        val offChainAmount = rs.getLong("off_chain_amount")
        val onChainAmount = rs.getLong("on_chain_amount")
        val timestamp = rs.getLong("timestamp")
        Some(PaymentSettlingOnChain(MilliSatoshi(offChainAmount), MilliSatoshi(onChainAmount), paymentHash, txid, refundType, isDone, timestamp))
      } else {
        None
      }
    }
  }

  override def getLostOnChain(paymentHash: BinaryData): Option[PaymentLostOnChain] = {
    using(sqlite.prepareStatement("SELECT * FROM lost_on_chain WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash)
      val rs = statement.executeQuery()
      if (rs.next()) {
        val amount = rs.getLong("lost_amount")
        val timestamp = rs.getLong("timestamp")
        Some(PaymentLostOnChain(MilliSatoshi(amount), paymentHash, timestamp))
      } else {
        None
      }
    }
  }
}
