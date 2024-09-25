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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, TxId}
import fr.acinq.eclair.channel.{ChannelLiquidityPurchased, LiquidityPurchase}
import fr.acinq.eclair.db.LiquidityDb
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.db.pg.PgUtils.PgLock.NoLock.withLock
import fr.acinq.eclair.payment.relay.OnTheFlyFunding
import fr.acinq.eclair.wire.protocol.LiquidityAds
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong}
import grizzled.slf4j.Logging
import scodec.bits.BitVector

import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

/**
 * Created by t-bast on 13/09/2024.
 */

object PgLiquidityDb {
  val DB_NAME = "liquidity"
  val CURRENT_VERSION = 1
}

class PgLiquidityDb(implicit ds: DataSource) extends LiquidityDb with Logging {

  import PgUtils._
  import ExtendedResultSet._
  import PgLiquidityDb._

  inTransaction { pg =>
    using(pg.createStatement()) { statement =>
      getVersion(statement, DB_NAME) match {
        case None =>
          statement.executeUpdate("CREATE SCHEMA liquidity")
          // Liquidity purchases.
          statement.executeUpdate("CREATE TABLE liquidity.purchases (tx_id TEXT NOT NULL, channel_id TEXT NOT NULL, node_id TEXT NOT NULL, is_buyer BOOLEAN NOT NULL, amount_sat BIGINT NOT NULL, mining_fee_sat BIGINT NOT NULL, service_fee_sat BIGINT NOT NULL, funding_tx_index BIGINT NOT NULL, capacity_sat BIGINT NOT NULL, local_contribution_sat BIGINT NOT NULL, remote_contribution_sat BIGINT NOT NULL, local_balance_msat BIGINT NOT NULL, remote_balance_msat BIGINT NOT NULL, outgoing_htlc_count BIGINT NOT NULL, incoming_htlc_count BIGINT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL, confirmed_at TIMESTAMP WITH TIME ZONE)")
          // On-the-fly funding.
          statement.executeUpdate("CREATE TABLE liquidity.on_the_fly_funding_preimages (payment_hash TEXT NOT NULL PRIMARY KEY, preimage TEXT NOT NULL, received_at TIMESTAMP WITH TIME ZONE NOT NULL)")
          statement.executeUpdate("CREATE TABLE liquidity.pending_on_the_fly_funding (node_id TEXT NOT NULL, payment_hash TEXT NOT NULL, channel_id TEXT NOT NULL, tx_id TEXT NOT NULL, funding_tx_index BIGINT NOT NULL, remaining_fees_msat BIGINT NOT NULL, proposed BYTEA NOT NULL, funded_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY (node_id, payment_hash))")
          // Indexes.
          statement.executeUpdate("CREATE INDEX liquidity_purchases_node_id_idx ON liquidity.purchases(node_id)")
        case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
        case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
      }
      setVersion(statement, DB_NAME, CURRENT_VERSION)
    }
  }

  override def addPurchase(e: ChannelLiquidityPurchased): Unit = withMetrics("liquidity/add-purchase", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("INSERT INTO liquidity.purchases VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)")) { statement =>
        statement.setString(1, e.purchase.fundingTxId.value.toHex)
        statement.setString(2, e.channelId.toHex)
        statement.setString(3, e.remoteNodeId.toHex)
        statement.setBoolean(4, e.purchase.isBuyer)
        statement.setLong(5, e.purchase.amount.toLong)
        statement.setLong(6, e.purchase.fees.miningFee.toLong)
        statement.setLong(7, e.purchase.fees.serviceFee.toLong)
        statement.setLong(8, e.purchase.fundingTxIndex)
        statement.setLong(9, e.purchase.capacity.toLong)
        statement.setLong(10, e.purchase.localContribution.toLong)
        statement.setLong(11, e.purchase.remoteContribution.toLong)
        statement.setLong(12, e.purchase.localBalance.toLong)
        statement.setLong(13, e.purchase.remoteBalance.toLong)
        statement.setLong(14, e.purchase.outgoingHtlcCount)
        statement.setLong(15, e.purchase.incomingHtlcCount)
        statement.setTimestamp(16, Timestamp.from(Instant.now()))
        statement.executeUpdate()
      }
    }
  }

  override def setConfirmed(remoteNodeId: PublicKey, txId: TxId): Unit = withMetrics("liquidity/set-confirmed", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("UPDATE liquidity.purchases SET confirmed_at=? WHERE node_id=? AND tx_id=?")) { statement =>
        statement.setTimestamp(1, Timestamp.from(Instant.now()))
        statement.setString(2, remoteNodeId.toHex)
        statement.setString(3, txId.value.toHex)
        statement.executeUpdate()
      }
    }
  }

  override def listPurchases(remoteNodeId: PublicKey): Seq[LiquidityPurchase] = withMetrics("liquidity/list-purchases", DbBackends.Postgres) {
    inTransaction { pg =>
      using(pg.prepareStatement("SELECT * FROM liquidity.purchases WHERE node_id=? AND confirmed_at IS NOT NULL")) { statement =>
        statement.setString(1, remoteNodeId.toHex)
        statement.executeQuery().map { rs =>
          LiquidityPurchase(
            fundingTxId = TxId(rs.getByteVector32FromHex("tx_id")),
            fundingTxIndex = rs.getLong("funding_tx_index"),
            isBuyer = rs.getBoolean("is_buyer"),
            amount = Satoshi(rs.getLong("amount_sat")),
            fees = LiquidityAds.Fees(miningFee = Satoshi(rs.getLong("mining_fee_sat")), serviceFee = Satoshi(rs.getLong("service_fee_sat"))),
            capacity = Satoshi(rs.getLong("capacity_sat")),
            localContribution = Satoshi(rs.getLong("local_contribution_sat")),
            remoteContribution = Satoshi(rs.getLong("remote_contribution_sat")),
            localBalance = MilliSatoshi(rs.getLong("local_balance_msat")),
            remoteBalance = MilliSatoshi(rs.getLong("remote_balance_msat")),
            outgoingHtlcCount = rs.getLong("outgoing_htlc_count"),
            incomingHtlcCount = rs.getLong("incoming_htlc_count")
          )
        }.toSeq
      }
    }
  }

  override def addPendingOnTheFlyFunding(remoteNodeId: Crypto.PublicKey, pending: OnTheFlyFunding.Pending): Unit = withMetrics("liquidity/add-pending-on-the-fly-funding", DbBackends.Postgres) {
    pending.status match {
      case _: OnTheFlyFunding.Status.Proposed => ()
      case status: OnTheFlyFunding.Status.Funded => withLock { pg =>
        using(pg.prepareStatement("INSERT INTO liquidity.pending_on_the_fly_funding (node_id, payment_hash, channel_id, tx_id, funding_tx_index, remaining_fees_msat, proposed, funded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) { statement =>
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

  override def removePendingOnTheFlyFunding(remoteNodeId: Crypto.PublicKey, paymentHash: ByteVector32): Unit = withMetrics("liquidity/remove-pending-on-the-fly-funding", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("DELETE FROM liquidity.pending_on_the_fly_funding WHERE node_id = ? AND payment_hash = ?")) { statement =>
        statement.setString(1, remoteNodeId.toHex)
        statement.setString(2, paymentHash.toHex)
        statement.executeUpdate()
      }
    }
  }

  override def listPendingOnTheFlyFunding(remoteNodeId: Crypto.PublicKey): Map[ByteVector32, OnTheFlyFunding.Pending] = withMetrics("liquidity/list-pending-on-the-fly-funding", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM liquidity.pending_on_the_fly_funding WHERE node_id = ?")) { statement =>
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

  override def listPendingOnTheFlyFunding(): Map[PublicKey, Map[ByteVector32, OnTheFlyFunding.Pending]] = withMetrics("liquidity/list-pending-on-the-fly-funding-all", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT * FROM liquidity.pending_on_the_fly_funding")) { statement =>
        statement.executeQuery().map { rs =>
          val remoteNodeId = PublicKey(rs.getByteVectorFromHex("node_id"))
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
          (remoteNodeId, paymentHash, pending)
        }.groupBy {
          case (remoteNodeId, _, _) => remoteNodeId
        }.map {
          case (remoteNodeId, payments) =>
            val paymentsMap = payments.map { case (_, paymentHash, pending) => paymentHash -> pending }.toMap
            remoteNodeId -> paymentsMap
        }
      }
    }
  }

  override def listPendingOnTheFlyPayments(): Map[Crypto.PublicKey, Set[ByteVector32]] = withMetrics("liquidity/list-pending-on-the-fly-payments", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT node_id, payment_hash FROM liquidity.pending_on_the_fly_funding")) { statement =>
        statement.executeQuery().map { rs =>
          val remoteNodeId = PublicKey(rs.getByteVectorFromHex("node_id"))
          val paymentHash = rs.getByteVector32FromHex("payment_hash")
          remoteNodeId -> paymentHash
        }.groupMap(_._1)(_._2).map {
          case (remoteNodeId, payments) => remoteNodeId -> payments.toSet
        }
      }
    }
  }

  override def addOnTheFlyFundingPreimage(preimage: ByteVector32): Unit = withMetrics("liquidity/add-on-the-fly-funding-preimage", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("INSERT INTO liquidity.on_the_fly_funding_preimages (payment_hash, preimage, received_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) { statement =>
        statement.setString(1, Crypto.sha256(preimage).toHex)
        statement.setString(2, preimage.toHex)
        statement.setTimestamp(3, Timestamp.from(Instant.now()))
        statement.executeUpdate()
      }
    }
  }

  override def getOnTheFlyFundingPreimage(paymentHash: ByteVector32): Option[ByteVector32] = withMetrics("liquidity/get-on-the-fly-funding-preimage", DbBackends.Postgres) {
    withLock { pg =>
      using(pg.prepareStatement("SELECT preimage FROM liquidity.on_the_fly_funding_preimages WHERE payment_hash = ?")) { statement =>
        statement.setString(1, paymentHash.toHex)
        statement.executeQuery().map { rs => rs.getByteVector32FromHex("preimage") }.lastOption
      }
    }
  }

}
