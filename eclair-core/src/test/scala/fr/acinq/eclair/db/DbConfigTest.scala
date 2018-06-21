package fr.acinq.eclair.db

import java.io.File

import com.typesafe.config.{Config, ConfigFactory}
import org.junit.runner.RunWith
import org.scalatest.FunSuite
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class DbConfigTest extends FunSuite {

  test("read eclair db unittest database configuration") {
    val config = ConfigFactory.load().getConfig("eclair")
    val dbConfig = EclairDbConfig.unittestConfig(config)
    val conn = dbConfig.getConnection()
    assert(!conn.isClosed)
    conn.close()
    assert(conn.isClosed)
  }

  test("read network db unittest database configuration") {
    val config = ConfigFactory.load().getConfig("eclair")
    val dbConfig = NetworkDbConfig.unittestConfig(config)
    val conn = dbConfig.getConnection()
    assert(!conn.isClosed)
    conn.close()
    assert(conn.isClosed)
  }
}
