package fr.acinq.eclair.db

import java.sql.Connection

import com.typesafe.config.Config
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import grizzled.slf4j.Logging

trait DbConfig {
  def dataSource: HikariDataSource

  def getConnection(): Connection = dataSource.getConnection

  /** Closes [[com.zaxxer.hikari.HikariDataSource]] */
  def close(): Unit = dataSource.close()

  def isClosed(): Boolean = dataSource.isClosed

  def isRunning(): Boolean = dataSource.isRunning
}


object DbConfig extends Logging {
  private case class DbConfigImpl(dataSource: HikariDataSource) extends DbConfig

  /** Reads the network you want from the reference.conf file */
  def fromConfig(config: Config): DbConfig = {
    val chain = config.getString("eclair.chain")
    fromConfig(config,chain)
    ???
  }

  private def fromConfig(config: Config, chain: String): HikariConfig = {
    val driver = config.getString("eclair.db.driver")
    val dbUrl = config.getString(s"eclair.db.${chain}.url")
    val hikariConfig = new HikariConfig()
    hikariConfig.setJdbcUrl(dbUrl)
    logger.info(s"hikariConfig ${hikariConfig}")
    hikariConfig
  }

  def mainnetConfig(config: Config): DbConfig = {
    fromConfig(config, "mainnet")
    ???
  }

  def testnetConfig(config: Config): DbConfig = {
    fromConfig(config,"testnet")
    ???
  }


  private var regtest: Option[DbConfig] = None

  def regtestConfig(config: Config): DbConfig = {
    regtest.getOrElse {
      val c = fromConfig(config,"regtest")
      logger.info(s"before creating dbConfig")
      regtest = Some(DbConfigImpl(new HikariDataSource(c)))
      logger.info(s"regtest ${regtest}")
      regtest.get
    }
  }

  private var unittest: Option[DbConfig] = None

  def unittestConfig(config: Config): DbConfig = {
    unittest.getOrElse {
      val c = fromConfig(config,"unittest")
      unittest = Some(DbConfigImpl(new HikariDataSource(c)))
      unittest.get
    }
  }
}