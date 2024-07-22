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

package fr.acinq.eclair.db.pg

import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, TxId}
import fr.acinq.eclair.MilliSatoshiLong
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.OnTheFlyFundingDb
import fr.acinq.eclair.db.pg.PgUtils.PgLock
import fr.acinq.eclair.payment.relay.OnTheFlyFunding
import scodec.bits.BitVector

import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * Created by t-bast on 25/06/2024.
 */

object PgOnTheFlyFundingDb {
  val DB_NAME = "on_the_fly_funding"
  val CURRENT_VERSION = 1
}

class PgOnTheFlyFundingDb(implicit ds: DataSource, lock: PgLock) extends OnTheFlyFundingDb {

  import PgOnTheFlyFundingDb._
  import PgUtils.ExtendedResultSet._
  import PgUtils._
  import lock._

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>
      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA IF NOT EXISTS on_the_fly_funding")
          statement.executeUpdate("CREATE TABLE on_the_fly_funding.preimages (payment_hash TEXT NOT NULL PRIMARY KEY, preimage TEXT NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE on_the_fly_funding.pending (remote_node_id TEXT NOT NULL, payment_hash TEXT NOT NULL, channel_id TEXT NOT NULL, tx_id TEXT NOT NULL, funding_tx_index BIGINT NOT NULL, remaining_fees_msat BIGINT NOT NULL, proposed BYTEA NOT NULL, funded_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (remote_node_id, payment_hash))")
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def addPreimage(preimage: ByteVector32): Unit = withMetrics("on-the-fly-funding/add-preimage", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO on_the_fly_funding.preimages (payment_hash, preimage, received_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) { statement =>
        statement.setString(1, Crypto.sha256(preimage).toHex)
        statement.setString(2, preimage.toHex)
        statement.setTimestamp(3, Timestamp.from(Instant.now()))
        statement.executeUpdate()
      }
    }
  }

  override def getPreimage(paymentHash: ByteVector32): Option[ByteVector32] = withMetrics("on-the-fly-funding/get-preimage", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT preimage FROM on_the_fly_funding.preimages WHERE payment_hash = ?")) { statement =>
        statement.setString(1, paymentHash.toHex)
        statement.executeQuery().map { rs => rs.getByteVector32FromHex("preimage") }.lastOption
      }
    }
  }

  override def addPending(remoteNodeId: Crypto.PublicKey, pending: OnTheFlyFunding.Pending): Unit = withMetrics("on-the-fly-funding/add-pending", DbBackends.Postgres) {
    pending.status match {
      case _: OnTheFlyFunding.Status.Proposed => ()
      case status: OnTheFlyFunding.Status.Funded => withLock { pg =>
        using(pg.prepareStatement("INSERT INTO on_the_fly_funding.pending (remote_node_id, payment_hash, channel_id, tx_id, funding_tx_index, remaining_fees_msat, proposed, funded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) { statement =>
          statement.setString(1, remoteNodeId.toHex)
          statement.setString(2, pending.paymentHash.toHex)
          statement.setString(3, status.channelId.toHex)
          statement.setString(4, status.txId.value.toHex)
          statement.setLong(5, status.fundingTxIndex)
          statement.setLong(6, status.remainingFees.toLong)
          statement.setBytes(7, OnTheFlyFunding.Codecs.proposals.encode(pending.proposed).require.bytes.toArray)
          statement.setTimestamp(8, Timestamp.from(Instant.now()))
          statement.executeUpdate()
        }
      }
    }
  }

  override def removePending(remoteNodeId: Crypto.PublicKey, paymentHash: ByteVector32): Unit = withMetrics("on-the-fly-funding/remove-pending", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("DELETE FROM on_the_fly_funding.pending WHERE remote_node_id = ? AND payment_hash = ?")) { statement =>
        statement.setString(1, remoteNodeId.toHex)
        statement.setString(2, paymentHash.toHex)
        statement.executeUpdate()
      }
    }
  }

  override def listPending(remoteNodeId: Crypto.PublicKey): Map[ByteVector32, OnTheFlyFunding.Pending] = withMetrics("on-the-fly-funding/list-pending", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM on_the_fly_funding.pending WHERE remote_node_id = ?")) { statement =>
        statement.setString(1, remoteNodeId.toHex)
        statement.executeQuery().map { rs =>
          val paymentHash = rs.getByteVector32FromHex("payment_hash")
          val pending = OnTheFlyFunding.Pending(
            proposed = OnTheFlyFunding.Codecs.proposals.decode(BitVector(rs.getBytes("proposed"))).require.value,
            status = OnTheFlyFunding.Status.Funded(
              channelId = rs.getByteVector32FromHex("channel_id"),
              txId = TxId(rs.getByteVector32FromHex("tx_id")),
              fundingTxIndex = rs.getLong("funding_tx_index"),
              remainingFees = rs.getLong("remaining_fees_msat").msat
            )
          )
          paymentHash -> pending
        }.toMap
      }
    }
  }

  override def listPendingPayments(): Map[Crypto.PublicKey, Set[ByteVector32]] = withMetrics("on-the-fly-funding/list-pending-payments", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT remote_node_id, payment_hash FROM on_the_fly_funding.pending")) { statement =>
        statement.executeQuery().map { rs =>
          val remoteNodeId = PublicKey(rs.getByteVectorFromHex("remote_node_id"))
          val paymentHash = rs.getByteVector32FromHex("payment_hash")
          remoteNodeId -> paymentHash
        }.groupMap(_._1)(_._2).map {
          case (remoteNodeId, payments) => remoteNodeId -> payments.toSet
        }
      }
    }
  }

}
