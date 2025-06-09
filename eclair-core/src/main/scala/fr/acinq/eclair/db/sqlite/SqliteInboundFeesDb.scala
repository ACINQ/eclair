package fr.acinq.eclair.db.sqlite

import fr.acinq.bitcoin.scalacompat.Crypto
import fr.acinq.eclair.MilliSatoshi
import fr.acinq.eclair.db.InboundFeesDb
import fr.acinq.eclair.db.sqlite.SqliteUtils.{getVersion, setVersion, using}
import fr.acinq.eclair.payment.relay.Relayer
import fr.acinq.eclair.payment.relay.Relayer.{InboundFees, RelayFees}
import grizzled.slf4j.Logging

import java.sql.Connection

object SqliteInboundFeesDb {
  val DB_NAME = "inboundfees"
  val CURRENT_VERSION = 1
}

class SqliteInboundFeesDb(val sqlite: Connection) extends InboundFeesDb with Logging {

  import SqliteInboundFeesDb._
  import SqliteUtils.ExtendedResultSet._

  using(sqlite.createStatement(), inTransaction = true) { statement =>
    getVersion(statement, DB_NAME) match {
      case None =>
        statement.executeUpdate("CREATE TABLE inbound_fees (node_id BLOB NOT NULL PRIMARY KEY, fee_base_msat INTEGER NOT NULL, fee_proportional_millionths INTEGER NOT NULL)")
      case Some(CURRENT_VERSION) => () // table is up-to-date, nothing to do
      case Some(unknownVersion) => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }
    setVersion(statement, DB_NAME, CURRENT_VERSION)
  }

  override def addOrUpdateInboundFees(nodeId: Crypto.PublicKey, fees: Relayer.InboundFees): Unit = {
    using(sqlite.prepareStatement("UPDATE inbound_fees SET fee_base_msat=?, fee_proportional_millionths=? WHERE node_id=?")) { update =>
      update.setLong(1, fees.feeBase.toLong)
      update.setLong(2, fees.feeProportionalMillionths)
      update.setBytes(3, nodeId.value.toArray)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO inbound_fees VALUES (?, ?, ?)")) { statement =>
          statement.setBytes(1, nodeId.value.toArray)
          statement.setLong(2, fees.feeBase.toLong)
          statement.setLong(3, fees.feeProportionalMillionths)
          statement.executeUpdate()
        }
      }
    }
  }

  override def getInboundFees(nodeId: Crypto.PublicKey): Option[Relayer.InboundFees] = {
    using(sqlite.prepareStatement("SELECT fee_base_msat, fee_proportional_millionths FROM inbound_fees WHERE node_id=?")) { statement =>
      statement.setBytes(1, nodeId.value.toArray)
      statement.executeQuery()
        .headOption
        .map(rs =>
          InboundFees(MilliSatoshi(rs.getLong("fee_base_msat")), rs.getLong("fee_proportional_millionths"))
        )
    }

  }

}
