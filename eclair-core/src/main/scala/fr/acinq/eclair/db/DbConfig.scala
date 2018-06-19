package fr.acinq.eclair.db

import java.sql.Connection

import com.typesafe.config.Config
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

class DbConfig(hikariConfig: HikariConfig) {

  private val dataSource: HikariDataSource = new HikariDataSource(hikariConfig)

  def getConnection(): Connection = dataSource.getConnection

  /** Closes [[com.zaxxer.hikari.HikariDataSource]] */
  def close(): Unit = dataSource.close()

  def isClosed(): Boolean = dataSource.isClosed

  def isRunning(): Boolean = dataSource.isRunning
}


object DbConfig {


  /** Reads the network you want from the reference.conf file */
  def fromConfig(config: Config): DbConfig = {
    val chain = config.getString("eclair.chain")
    fromConfig(config,chain)
  }

  private def fromConfig(config: Config, chain: String): DbConfig = {
    val driver = config.getString("eclair.db.driver")
    val dbUrl = config.getString(s"eclair.db.${chain}.url")
    val hikariConfig = new HikariConfig()
    hikariConfig.setJdbcUrl(dbUrl)
    new DbConfig(hikariConfig)
  }

  def mainnetConfig(config: Config): DbConfig = {
    fromConfig(config, "mainnet")
  }

  def testnetConfig(config: Config): DbConfig = {
    fromConfig(config,"testnet")
  }

  def regtestConfig(config: Config): DbConfig = {
    fromConfig(config,"regtest")
  }

  def unittestConfig(config: Config): DbConfig = {
    fromConfig(config,"unittest")
  }
}