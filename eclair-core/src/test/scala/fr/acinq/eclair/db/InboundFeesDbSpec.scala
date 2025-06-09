package fr.acinq.eclair.db

import fr.acinq.eclair.TestDatabases.{TestPgDatabases, TestSqliteDatabases}
import fr.acinq.eclair._
import fr.acinq.eclair.db.pg.PgInboundFeesDb
import fr.acinq.eclair.db.sqlite.SqliteInboundFeesDb
import fr.acinq.eclair.payment.relay.Relayer.InboundFees
import org.scalatest.funsuite.AnyFunSuite

class InboundFeesDbSpec extends AnyFunSuite {

  import fr.acinq.eclair.TestDatabases.forAllDbs

  test("init database two times in a row") {
    forAllDbs {
      case sqlite: TestSqliteDatabases =>
        new SqliteInboundFeesDb(sqlite.connection)
        new SqliteInboundFeesDb(sqlite.connection)
      case pg: TestPgDatabases =>
        new PgInboundFeesDb()(pg.datasource, pg.lock)
        new PgInboundFeesDb()(pg.datasource, pg.lock)
    }
  }

  test("add and update inbound fees") {
    forAllDbs { dbs =>
      val db = dbs.inboundFees

      val a = randomKey().publicKey
      val b = randomKey().publicKey

      assert(db.getInboundFees(a).isEmpty)
      assert(db.getInboundFees(b).isEmpty)
      db.addOrUpdateInboundFees(a, InboundFees(1 msat, 123))
      assert(db.getInboundFees(a).contains(InboundFees(1 msat, 123)))
      assert(db.getInboundFees(b).isEmpty)
      db.addOrUpdateInboundFees(a, InboundFees(2 msat, 456))
      assert(db.getInboundFees(a).contains(InboundFees(2 msat, 456)))
      assert(db.getInboundFees(b).isEmpty)
      db.addOrUpdateInboundFees(b, InboundFees(3 msat, 789))
      assert(db.getInboundFees(a).contains(InboundFees(2 msat, 456)))
      assert(db.getInboundFees(b).contains(InboundFees(3 msat, 789)))
    }
  }
}
