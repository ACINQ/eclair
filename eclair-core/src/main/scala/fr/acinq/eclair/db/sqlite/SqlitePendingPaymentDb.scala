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
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.eclair.db.{PendingPaymentDb, RiskInfo}
import fr.acinq.eclair.payment.PaymentSettlingOnChain

import scala.collection.immutable.Queue

/**
  * Created by anton on 12.09.18.
  */
class SqlitePendingPaymentDb(sqlite: Connection) extends PendingPaymentDb {

  import SqliteUtils._

  val DB_NAME = "pending_payments"
  val CURRENT_VERSION = 1

  using(sqlite.createStatement()) { statement =>
    require(getVersion(statement, DB_NAME, CURRENT_VERSION) == CURRENT_VERSION) // there is only one version currently deployed
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS pending (payment_hash BLOB NOT NULL UNIQUE, peer_node_id BLOB NOT NULL, target_node_id BLOB NOT NULL, peer_cltv_delta INTEGER NOT NULL, added INTEGER NOT NULL, delay INTEGER NOT NULL, expiry INTEGER NOT NULL, UNIQUE (payment_hash, peer_node_id) ON CONFLICT IGNORE)")
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS incoming_settling_on_chain (payment_hash BLOB NOT NULL, tx_id BLOB NOT NULL, refund_type STRING NOT NULL, is_done INTEGER NOT NULL, off_chain_amount INTEGER NOT NULL, on_chain_amount INTEGER NOT NULL, UNIQUE (payment_hash, tx_id) ON CONFLICT IGNORE)")

    statement.executeUpdate("CREATE INDEX IF NOT EXISTS payment_hash_idx ON pending(payment_hash)")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS target_node_id_idx ON pending(target_node_id)")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS added_idx ON pending(added)")

    statement.executeUpdate("CREATE INDEX IF NOT EXISTS payment_hash_idx ON incoming_settling_on_chain(payment_hash)")
    statement.executeUpdate("CREATE INDEX IF NOT EXISTS tx_id_idx ON incoming_settling_on_chain(tx_id)")
  }

  override def add(paymentHash: BinaryData, peerNodeId: PublicKey, targetNodeId: PublicKey,
                   peerCltvDelta: Long, added: Long, delay: Long, expiry: Long): Unit = {

    using(sqlite.prepareStatement("INSERT OR IGNORE INTO pending VALUES (?, ?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, paymentHash)
      statement.setBytes(2, peerNodeId.toBin)
      statement.setBytes(3, targetNodeId.toBin)
      statement.setLong(4, peerCltvDelta)
      statement.setLong(5, added)
      statement.setLong(6, delay)
      statement.setLong(7, expiry)
      statement.executeUpdate()
    }
  }

  override def updateDelay(paymentHash: BinaryData, peerNodeId: PublicKey, delay: Long): Unit = {
    using (sqlite.prepareStatement("UPDATE pending SET delay=? WHERE payment_hash=? AND peer_node_id=?")) { update =>
      update.setLong(1, delay)
      update.setBytes(2, paymentHash)
      update.setBytes(3, peerNodeId.toBin)
      update.executeUpdate()
    }
  }

  override def listDelays(targetNodeId: PublicKey, sinceBlockHeight: Long): Seq[Long] = {
    // "expiry - delay > peer_cltv_delta" to exclude cases where payment is delayed by our direct peer so payee has nothing to do with it
    // "delayed > 1" because a delay of one block may be caused naturally when another block appears while normal payment is in flight
    using(sqlite.prepareStatement("SELECT delay - added AS delayed FROM pending WHERE target_node_id = ? AND added > ? AND delayed > 1 AND expiry - delay > peer_cltv_delta")) { statement =>
      statement.setBytes(1, targetNodeId.toBin)
      statement.setLong(2, sinceBlockHeight)
      val rs = statement.executeQuery()
      var q: Queue[Long] = Queue()
      while (rs.next()) {
        q = q :+ rs.getLong("delayed")
      }
      q
    }
  }

  override def listBadPeers(sinceBlockHeight: Long): Seq[PublicKey] = {
    // "expiry - delay <= peer_cltv_delta" to catch cases where our direct peer should have failed a payment but did not
    using(sqlite.prepareStatement("SELECT peer_node_id FROM pending WHERE added > ? AND expiry - delay <= peer_cltv_delta")) { statement =>
      statement.setLong(1, sinceBlockHeight)
      val rs = statement.executeQuery()
      var q: Queue[PublicKey] = Queue()
      while (rs.next()) {
        q = q :+ PublicKey(rs.getBytes("peer_node_id"))
      }
      q
    }
  }

  override def riskInfo(targetNodeId: PublicKey, sinceBlockHeight: Long, sdTimes: Double): Option[RiskInfo] = {
    using(sqlite.prepareStatement(
      """
        |SELECT mean.value AS average, count(payment_hash) AS total, AVG((delay - added - mean.value) * (delay - added - mean.value)) AS variance
        |FROM pending, (SELECT AVG(delay - added) AS value FROM pending WHERE added > ? AND delay - added > 1) AS mean
        |WHERE added > ? AND delay - added > 1
      """.stripMargin)) { statement =>

      statement.setLong(1, sinceBlockHeight)
      statement.setLong(2, sinceBlockHeight)

      val rs = statement.executeQuery()
      if (rs.next()) {
        val total = rs.getLong("total")
        val mean = rs.getDouble("average")
        val sd = math.sqrt(rs.getDouble("variance"))
        val delays = listDelays(targetNodeId, sinceBlockHeight)
        val adjusted = delays.filter(_ >= mean + sd * sdTimes)
        Some(RiskInfo(targetNodeId, sinceBlockHeight, total, mean, sd * sdTimes, delays, adjusted))
      } else {
        None
      }
    }
  }


  override def add(paymentSettlingOnChain: PaymentSettlingOnChain): Unit = {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO incoming_settling_on_chain VALUES (?, ?, ?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, paymentSettlingOnChain.paymentHash)
      statement.setBytes(2, paymentSettlingOnChain.txid)
      statement.setString(3, paymentSettlingOnChain.refundType)
      statement.setBoolean(4, paymentSettlingOnChain.isDone)
      statement.setLong(5, paymentSettlingOnChain.offChainAmount.amount)
      statement.setLong(6, paymentSettlingOnChain.onChainAmount.amount)
      statement.executeUpdate()
    }
  }

  override def getSettlingOnChain(paymentHash: BinaryData): Option[PaymentSettlingOnChain] = {
    using(sqlite.prepareStatement("SELECT * FROM incoming_settling_on_chain WHERE payment_hash = ? ORDER BY is_done DESC")) { statement =>
      statement.setBytes(1, paymentHash)
      val rs = statement.executeQuery()
      if (rs.next()) {
        val txid = rs.getBytes("tx_id")
        val refundType = rs.getString("refund_type")
        val isDone = rs.getBoolean("is_done")
        val offChainAmount = rs.getLong("off_chain_amount")
        val onChainAmount = rs.getLong("on_chain_amount")
        Some(PaymentSettlingOnChain(MilliSatoshi(offChainAmount), MilliSatoshi(onChainAmount), paymentHash, txid, refundType, isDone))
      } else {
        None
      }
    }
  }

  override def setDone(txid: BinaryData): Unit = {
    using (sqlite.prepareStatement("UPDATE incoming_settling_on_chain SET is_done=? WHERE tx_id=?")) { update =>
      update.setBoolean(1, true)
      update.setBytes(2, txid)
      update.executeUpdate()
    }
  }
}
