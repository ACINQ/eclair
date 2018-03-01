package fr.acinq.eclair.db

import java.sql.DriverManager

import fr.acinq.bitcoin.BinaryData
import fr.acinq.eclair.db.sqlite.SqlitePaymentsDb
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class SqlitePaymentsDbSpec extends FunSuite {

  def inmem = DriverManager.getConnection("jdbc:sqlite::memory:")

  test("init sqlite 2 times in a row") {
    val sqlite = inmem
    val db1 = new SqlitePaymentsDb(sqlite)
    val db2 = new SqlitePaymentsDb(sqlite)
  }

  test("add/list payments/find 1 payment that exists/find 1 payment that does not exist") {
    val sqlite = inmem
    val db = new SqlitePaymentsDb(sqlite)

    val p1 = Payment(BinaryData("08d47d5f7164d4b696e8f6b62a03094d4f1c65f16e9d7b11c4a98854707e55cf"), 12345678, 1513871928275L)
    val p2 = Payment(BinaryData("0f059ef9b55bb70cc09069ee4df854bf0fab650eee6f2b87ba26d1ad08ab114f"), 12345678, 1513871928275L)
    assert(db.listPayments() === Nil)
    db.addPayment(p1)
    db.addPayment(p2)
    assert(db.listPayments().toList === List(p1, p2))
    assert(db.findByPaymentHash(p1.payment_hash) === Some(p1))
    assert(db.findByPaymentHash("6e7e8018f05e169cf1d99e77dc22cb372d09f10b6a81f1eae410718c56cad187") === None)
  }
}
