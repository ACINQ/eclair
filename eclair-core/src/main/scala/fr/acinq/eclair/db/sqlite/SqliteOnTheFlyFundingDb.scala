/*
 * Copyright 2024 ACINQ SAS
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

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, TxId}
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.OnTheFlyFundingDb
import fr.acinq.eclair.payment.relay.OnTheFlyFunding
import fr.acinq.eclair.{MilliSatoshiLong, TimestampMilli}
import scodec.bits.BitVector

import java.sql.Connection

/**
 * Created by t-bast on 25/06/2024.
 */

object SqliteOnTheFlyFundingDb {
  val DB_NAME = "on_the_fly_funding"
  val CURRENT_VERSION = 1
}

class SqliteOnTheFlyFundingDb(val sqlite: Connection) extends OnTheFlyFundingDb {

  import SqliteOnTheFlyFundingDb._
  import SqliteUtils.ExtendedResultSet._
  import SqliteUtils._

  using(sqlite.createStatement(), inTransaction = true) { statement =>
    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE on_the_fly_funding_preimages (payment_hash BLOB NOT NULL PRIMARY KEY, preimage BLOB NOT NULL, received_at INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE on_the_fly_funding_pending (remote_node_id BLOB NOT NULL, payment_hash BLOB NOT NULL, channel_id BLOB NOT NULL, tx_id BLOB NOT NULL, funding_tx_index INTEGER NOT NULL, remaining_fees_msat INTEGER NOT NULL, proposed BLOB NOT NULL, funded_at INTEGER NOT NULL, PRIMARY KEY (remote_node_id, payment_hash))")
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addPreimage(preimage: ByteVector32): Unit = withMetrics("on-the-fly-funding/add-preimage", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO on_the_fly_funding_preimages (payment_hash, preimage, received_at) VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, Crypto.sha256(preimage).toArray)
      statement.setBytes(2, preimage.toArray)
      statement.setLong(3, TimestampMilli.now().toLong)
      statement.executeUpdate()
    }
  }

  override def getPreimage(paymentHash: ByteVector32): Option[ByteVector32] = withMetrics("on-the-fly-funding/get-preimage", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT preimage FROM on_the_fly_funding_preimages WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      statement.executeQuery().map { rs => rs.getByteVector32("preimage") }.lastOption
    }
  }

  override def addPending(remoteNodeId: Crypto.PublicKey, pending: OnTheFlyFunding.Pending): Unit = withMetrics("on-the-fly-funding/add-pending", DbBackends.Sqlite) {
    pending.status match {
      case _: OnTheFlyFunding.Status.Proposed => ()
      case status: OnTheFlyFunding.Status.Funded =>
        using(sqlite.prepareStatement("INSERT OR IGNORE INTO on_the_fly_funding_pending (remote_node_id, payment_hash, channel_id, tx_id, funding_tx_index, remaining_fees_msat, proposed, funded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
          statement.setBytes(1, remoteNodeId.value.toArray)
          statement.setBytes(2, pending.paymentHash.toArray)
          statement.setBytes(3, status.channelId.toArray)
          statement.setBytes(4, status.txId.value.toArray)
          statement.setLong(5, status.fundingTxIndex)
          statement.setLong(6, status.remainingFees.toLong)
          statement.setBytes(7, OnTheFlyFunding.Codecs.proposals.encode(pending.proposed).require.bytes.toArray)
          statement.setLong(8, TimestampMilli.now().toLong)
          statement.executeUpdate()
        }
    }
  }

  override def removePending(remoteNodeId: Crypto.PublicKey, paymentHash: ByteVector32): Unit = withMetrics("on-the-fly-funding/remove-pending", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("DELETE FROM on_the_fly_funding_pending WHERE remote_node_id = ? AND payment_hash = ?")) { statement =>
      statement.setBytes(1, remoteNodeId.value.toArray)
      statement.setBytes(2, paymentHash.toArray)
      statement.executeUpdate()
    }
  }

  override def listPending(remoteNodeId: Crypto.PublicKey): Map[ByteVector32, OnTheFlyFunding.Pending] = withMetrics("on-the-fly-funding/list-pending", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM on_the_fly_funding_pending WHERE remote_node_id = ?")) { statement =>
      statement.setBytes(1, remoteNodeId.value.toArray)
      statement.executeQuery().map { rs =>
        val paymentHash = rs.getByteVector32("payment_hash")
        val pending = OnTheFlyFunding.Pending(
          proposed = OnTheFlyFunding.Codecs.proposals.decode(BitVector(rs.getBytes("proposed"))).require.value,
          status = OnTheFlyFunding.Status.Funded(
            channelId = rs.getByteVector32("channel_id"),
            txId = TxId(rs.getByteVector32("tx_id")),
            fundingTxIndex = rs.getLong("funding_tx_index"),
            remainingFees = rs.getLong("remaining_fees_msat").msat
          )
        )
        paymentHash -> pending
      }.toMap
    }
  }

  override def listPendingPayments(): Map[Crypto.PublicKey, Set[ByteVector32]] = withMetrics("on-the-fly-funding/list-pending-payments", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT remote_node_id, payment_hash FROM on_the_fly_funding_pending")) { statement =>
      statement.executeQuery().map { rs =>
        val remoteNodeId = PublicKey(rs.getByteVector("remote_node_id"))
        val paymentHash = rs.getByteVector32("payment_hash")
        remoteNodeId -> paymentHash
      }.groupMap(_._1)(_._2).map {
        case (remoteNodeId, payments) => remoteNodeId -> payments.toSet
      }
    }
  }

}
