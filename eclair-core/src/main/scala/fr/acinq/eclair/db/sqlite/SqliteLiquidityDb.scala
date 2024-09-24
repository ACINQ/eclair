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
import fr.acinq.bitcoin.scalacompat.{Satoshi, TxId}
import fr.acinq.eclair.channel.{ChannelLiquidityPurchased, LiquidityPurchase}
import fr.acinq.eclair.db.LiquidityDb
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.db.Monitoring.Tags.DbBackends
import fr.acinq.eclair.wire.protocol.LiquidityAds
import fr.acinq.eclair.{MilliSatoshi, TimestampMilli}
import grizzled.slf4j.Logging

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
        statement.executeUpdate("CREATE TABLE liquidity_purchases (tx_id BLOB NOT NULL, channel_id BLOB NOT NULL, node_id BLOB NOT NULL, is_buyer BOOLEAN NOT NULL, amount_sat INTEGER NOT NULL, mining_fee_sat INTEGER NOT NULL, service_fee_sat INTEGER NOT NULL, funding_tx_index INTEGER NOT NULL, capacity_sat INTEGER NOT NULL, local_contribution_sat INTEGER NOT NULL, remote_contribution_sat INTEGER NOT NULL, local_balance_msat INTEGER NOT NULL, remote_balance_msat INTEGER NOT NULL, outgoing_htlc_count INTEGER NOT NULL, incoming_htlc_count INTEGER NOT NULL, created_at INTEGER NOT NULL, confirmed_at INTEGER)")
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

}
