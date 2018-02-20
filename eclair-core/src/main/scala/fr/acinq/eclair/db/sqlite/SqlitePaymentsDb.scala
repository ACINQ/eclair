package fr.acinq.eclair.db.sqlite

import java.sql.Connection

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.db.sqlite.SqliteUtils.using
import fr.acinq.eclair.db.{Payment, PaymentsDb}
import grizzled.slf4j.Logging

/**
  * Payments are stored in the `payments` table.
  * The primary key in this DB is the `payment_hash` column. Columns are not nullable.
  * <p>
  * Types:
  * <ul>
  * <li>`payment_hash`: BLOB
  * <li>`amount_msat`: INTEGER
  * <li>`timestamp`: INTEGER (unix timestamp)
  */
class SqlitePaymentsDb(sqlite: Connection) extends PaymentsDb with Logging {

  using(sqlite.createStatement()) { statement =>
    statement.executeUpdate("CREATE TABLE IF NOT EXISTS payments (payment_hash BLOB NOT NULL PRIMARY KEY, amount_msat INTEGER NOT NULL, timestamp INTEGER NOT NULL)")
  }

  override def addPayment(payment: Payment): Unit = {
    using(sqlite.prepareStatement("INSERT INTO payments VALUES (?, ?, ?)")) { statement =>
      statement.setBytes(1, payment.payment_hash)
      statement.setLong(2, payment.amount_msat)
      statement.setLong(3, payment.timestamp)
      val res = statement.executeUpdate()
      logger.debug(s"inserted $res payment=${payment} in DB")
    }
  }

  @throws(classOf[NoSuchElementException])
  override def findByPaymentHash(paymentHash: BinaryData): Payment = {
    using(sqlite.prepareStatement("SELECT payment_hash, amount_msat, timestamp FROM payments WHERE payment_hash = ?")) { statement =>
      statement.setBytes(1, paymentHash)
      val rs = statement.executeQuery()
      if (rs.next()) {
        Payment(BinaryData(rs.getBytes("payment_hash")), rs.getLong("amount_msat"), rs.getLong("timestamp"))
      } else {
        throw new NoSuchElementException("payment not found")
      }
    }
  }

  override def listPayments(): List[Payment] = {
    using(sqlite.createStatement()) { statement =>
      val rs = statement.executeQuery("SELECT payment_hash, amount_msat, timestamp FROM payments")
      var l: List[Payment] = Nil
      while (rs.next()) {
        l = Payment(BinaryData(rs.getBytes("payment_hash")), rs.getLong("amount_msat"), rs.getLong("timestamp")) +: l
      }
      l
    }
  }

}
