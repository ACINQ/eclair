package fr.acinq.eclair.db

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class DbConfigTest extends FunSuite {

  test("read regtest database configuration") {
    val config = ConfigFactory.load()
    val dbConfig = DbConfig.unittestConfig(config)
    val conn = dbConfig.getConnection()
    assert(!conn.isClosed)
    conn.close()
    assert(conn.isClosed)
    dbConfig.close()
    assert(dbConfig.isClosed())
  }
}
