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
import fr.acinq.bitcoin.scalacompat.{ByteVector32, Crypto, Satoshi, TxId}
import fr.acinq.eclair.channel.{ChannelLiquidityPurchased, LiquidityPurchase}
import fr.acinq.eclair.db.LiquidityDb
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.payment.relay.OnTheFlyFunding
import fr.acinq.eclair.wire.protocol.LiquidityAds
import fr.acinq.eclair.{MilliSatoshi, MilliSatoshiLong, TimestampMilli}
import grizzled.slf4j.Logging
import scodec.bits.BitVector

import java.sql.Connection

/**
 * Created by t-bast on 13/09/2024.
 */

object SqliteLiquidityDb {
  val DB_NAME = "liquidity"
  val CURRENT_VERSION = 1
}

class SqliteLiquidityDb(val sqlite: Connection) extends LiquidityDb with Logging {

  import SqliteUtils._
  import ExtendedResultSet._
  import SqliteLiquidityDb._

  using(sqlite.createStatement(), inTransaction = true) { statement =>
    getVersion(statement, DB_NAME) match {
      case None =>
        // Liquidity purchases.
        statement.executeUpdate("CREATE TABLE liquidity_purchases (tx_id BLOB NOT NULL, channel_id BLOB NOT NULL, node_id BLOB NOT NULL, is_buyer BOOLEAN NOT NULL, amount_sat INTEGER NOT NULL, mining_fee_sat INTEGER NOT NULL, service_fee_sat INTEGER NOT NULL, funding_tx_index INTEGER NOT NULL, capacity_sat INTEGER NOT NULL, local_contribution_sat INTEGER NOT NULL, remote_contribution_sat INTEGER NOT NULL, local_balance_msat INTEGER NOT NULL, remote_balance_msat INTEGER NOT NULL, outgoing_htlc_count INTEGER NOT NULL, incoming_htlc_count INTEGER NOT NULL, created_at INTEGER NOT NULL, confirmed_at INTEGER)")
        // On-the-fly funding.
        statement.executeUpdate("CREATE TABLE on_the_fly_funding_preimages (payment_hash BLOB NOT NULL PRIMARY KEY, preimage BLOB NOT NULL, received_at INTEGER NOT NULL)")
        statement.executeUpdate("CREATE TABLE on_the_fly_funding_pending (node_id BLOB NOT NULL, payment_hash BLOB NOT NULL, channel_id BLOB NOT NULL, tx_id BLOB NOT NULL, funding_tx_index INTEGER NOT NULL, remaining_fees_msat INTEGER NOT NULL, proposed BLOB NOT NULL, funded_at INTEGER NOT NULL, PRIMARY KEY (node_id, payment_hash))")
        statement.executeUpdate("CREATE TABLE fee_credits (node_id BLOB NOT NULL PRIMARY KEY, amount_msat INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        // Indexes.
        statement.executeUpdate("CREATE INDEX liquidity_purchases_node_id_idx ON liquidity_purchases(node_id)")
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addPurchase(e: ChannelLiquidityPurchased): Unit = withMetrics("liquidity/add-purchase", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT INTO liquidity_purchases VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)")) { statement =>
      statement.setBytes(1, e.purchase.fundingTxId.value.toArray)
      statement.setBytes(2, e.channelId.toArray)
      statement.setBytes(3, e.remoteNodeId.value.toArray)
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
      statement.setLong(16, TimestampMilli.now().toLong)
      statement.executeUpdate()
    }
  }

  override def setConfirmed(remoteNodeId: PublicKey, txId: TxId): Unit = withMetrics("liquidity/set-confirmed", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("UPDATE liquidity_purchases SET confirmed_at=? WHERE node_id=? AND tx_id=?")) { statement =>
      statement.setLong(1, TimestampMilli.now().toLong)
      statement.setBytes(2, remoteNodeId.value.toArray)
      statement.setBytes(3, txId.value.toArray)
      statement.executeUpdate()
    }
  }

  override def listPurchases(remoteNodeId: PublicKey): Seq[LiquidityPurchase] = withMetrics("liquidity/list-purchases", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM liquidity_purchases WHERE node_id=? AND confirmed_at IS NOT NULL")) { statement =>
      statement.setBytes(1, remoteNodeId.value.toArray)
      statement.executeQuery().map { rs =>
        LiquidityPurchase(
          fundingTxId = TxId(rs.getByteVector32("tx_id")),
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

  override def addPendingOnTheFlyFunding(remoteNodeId: Crypto.PublicKey, pending: OnTheFlyFunding.Pending): Unit = withMetrics("liquidity/add-pending-on-the-fly-funding", DbBackends.Sqlite) {
    pending.status match {
      case _: OnTheFlyFunding.Status.Proposed => ()
      case _: OnTheFlyFunding.Status.AddedToFeeCredit => ()
      case status: OnTheFlyFunding.Status.Funded =>
        using(sqlite.prepareStatement("INSERT OR IGNORE INTO on_the_fly_funding_pending (node_id, payment_hash, channel_id, tx_id, funding_tx_index, remaining_fees_msat, proposed, funded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) { statement =>
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

  override def removePendingOnTheFlyFunding(remoteNodeId: Crypto.PublicKey, paymentHash: ByteVector32): Unit = withMetrics("liquidity/remove-pending-on-the-fly-funding", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("DELETE FROM on_the_fly_funding_pending WHERE node_id = ? AND payment_hash = ?")) { statement =>
      statement.setBytes(1, remoteNodeId.value.toArray)
      statement.setBytes(2, paymentHash.toArray)
      statement.executeUpdate()
    }
  }

  override def listPendingOnTheFlyFunding(remoteNodeId: Crypto.PublicKey): Map[ByteVector32, OnTheFlyFunding.Pending] = withMetrics("liquidity/list-pending-on-the-fly-funding", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM on_the_fly_funding_pending WHERE node_id = ?")) { statement =>
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

  override def listPendingOnTheFlyFunding(): Map[PublicKey, Map[ByteVector32, OnTheFlyFunding.Pending]] = withMetrics("liquidity/list-pending-on-the-fly-funding-all", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT * FROM on_the_fly_funding_pending")) { statement =>
      statement.executeQuery().map { rs =>
        val remoteNodeId = PublicKey(rs.getByteVector("node_id"))
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

  override def listPendingOnTheFlyPayments(): Map[Crypto.PublicKey, Set[ByteVector32]] = withMetrics("liquidity/list-pending-on-the-fly-payments", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT node_id, payment_hash FROM on_the_fly_funding_pending")) { statement =>
      statement.executeQuery().map { rs =>
        val remoteNodeId = PublicKey(rs.getByteVector("node_id"))
        val paymentHash = rs.getByteVector32("payment_hash")
        remoteNodeId -> paymentHash
      }.groupMap(_._1)(_._2).map {
        case (remoteNodeId, payments) => remoteNodeId -> payments.toSet
      }
    }
  }

  override def addOnTheFlyFundingPreimage(preimage: ByteVector32): Unit = withMetrics("liquidity/add-on-the-fly-funding-preimage", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("INSERT OR IGNORE INTO on_the_fly_funding_preimages (payment_hash, preimage, received_at) VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, Crypto.sha256(preimage).toArray)
      statement.setBytes(2, preimage.toArray)
      statement.setLong(3, TimestampMilli.now().toLong)
      statement.executeUpdate()
    }
  }

  override def getOnTheFlyFundingPreimage(paymentHash: ByteVector32): Option[ByteVector32] = withMetrics("liquidity/get-on-the-fly-funding-preimage", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT preimage FROM on_the_fly_funding_preimages WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash.toArray)
      statement.executeQuery().map { rs => rs.getByteVector32("preimage") }.lastOption
    }
  }

  override def addFeeCredit(nodeId: PublicKey, amount: MilliSatoshi, receivedAt: TimestampMilli): MilliSatoshi = withMetrics("liquidity/add-fee-credit", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT amount_msat FROM fee_credits WHERE node_id = ?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeQuery().map(_.getLong("amount_msat").msat).headOption match {
        case Some(current) => using(sqlite.prepareStatement("UPDATE fee_credits SET (amount_msat, updated_at) = (?, ?) WHERE node_id = ?")) { statement =>
          statement.setLong(1, (current + amount).toLong)
          statement.setLong(2, receivedAt.toLong)
          statement.setBytes(3, nodeId.value.toArray)
          statement.executeUpdate()
          amount + current
        }
        case None => using(sqlite.prepareStatement("INSERT OR IGNORE INTO fee_credits(node_id, amount_msat, updated_at) VALUES (?, ?, ?)")) { statement =>
          statement.setBytes(1, nodeId.value.toArray)
          statement.setLong(2, amount.toLong)
          statement.setLong(3, receivedAt.toLong)
          statement.executeUpdate()
          amount
        }
      }
    }
  }

  override def getFeeCredit(nodeId: PublicKey): MilliSatoshi = withMetrics("liquidity/get-fee-credit", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT amount_msat FROM fee_credits WHERE node_id = ?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeQuery().map(_.getLong("amount_msat").msat).headOption.getOrElse(0 msat)
    }
  }

  override def removeFeeCredit(nodeId: PublicKey, amountUsed: MilliSatoshi): MilliSatoshi = withMetrics("liquidity/remove-fee-credit", DbBackends.Sqlite) {
    using(sqlite.prepareStatement("SELECT amount_msat FROM fee_credits WHERE node_id = ?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeQuery().map(_.getLong("amount_msat").msat).headOption match {
        case Some(current) => using(sqlite.prepareStatement("UPDATE fee_credits SET (amount_msat, updated_at) = (?, ?) WHERE node_id = ?")) { statement =>
          val updated = (current - amountUsed).max(0 msat)
          statement.setLong(1, updated.toLong)
          statement.setLong(2, TimestampMilli.now().toLong)
          statement.setBytes(3, nodeId.value.toArray)
          statement.executeUpdate()
          updated
        }
        case None => 0 msat
      }
    }
  }

}
