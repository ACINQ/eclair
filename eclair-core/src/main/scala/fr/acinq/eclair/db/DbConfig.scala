package fr.acinq.eclair.db

import java.sql.Connection

import com.typesafe.config.Config
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}



case class AppDbConfig(eclairDb: EclairDbConfig, networkDb: NetworkDbConfig)

abstract class DbConfig(hikariConfig: HikariConfig) {
  private val dataSource: HikariDataSource = new HikariDataSource(hikariConfig)

  def getConnection(): Connection = dataSource.getConnection

  /** Closes [[com.zaxxer.hikari.HikariDataSource]] */
  def close(): Unit = dataSource.close()

  def isClosed(): Boolean = dataSource.isClosed

  def isRunning(): Boolean = dataSource.isRunning
}

case class EclairDbConfig(hikariConfig: HikariConfig) extends DbConfig(hikariConfig)

case class NetworkDbConfig(hikariConfig: HikariConfig) extends DbConfig(hikariConfig)

object EclairDbConfig {

  private val eclairDbKey = "eclairDb"

  /** Reads the network you want from the reference.conf file */
  def fromConfig(config: Config): EclairDbConfig = {
    val chain = config.getString("eclair.chain")
    fromConfig(config,chain)
    ???
  }

  private def fromConfig(config: Config, chain: String): EclairDbConfig = {
    val driver = config.getString(s"eclair.${eclairDbKey}.driver")
    val dbChainConfig = config.getConfig(s"eclair.${eclairDbKey}.${chain}")
    val dbUrl = dbChainConfig.getString("url")
    val hikariConfig = new HikariConfig()
    hikariConfig.setDriverClassName(driver)
    hikariConfig.setJdbcUrl(dbUrl)
    EclairDbConfig(hikariConfig)
  }

  def mainnetConfig(config: Config): EclairDbConfig = {
    fromConfig(config, "mainnet")
  }

  def testnetConfig(config: Config): EclairDbConfig = {
    fromConfig(config,"testnet")
  }



  def regtestConfig(config: Config): EclairDbConfig = {
    fromConfig(config,"regtest")
  }

  def unittestConfig(config: Config): EclairDbConfig = {
    fromConfig(config,"unittest")
  }
}

object NetworkDbConfig {

  private val networkDbKey = "networkDb"

  /** Reads the network you want from the reference.conf file */
  def fromConfig(config: Config): NetworkDbConfig = {
    val chain = config.getString("eclair.chain")
    fromConfig(config,chain)
  }

  private def fromConfig(config: Config, chain: String): NetworkDbConfig = {
    val driver = config.getString(s"eclair.${networkDbKey}.driver")
    val dbChainConfig = config.getConfig(s"eclair.${networkDbKey}.${chain}")
    val dbUrl = dbChainConfig.getString("url")
    val hikariConfig = new HikariConfig()
    hikariConfig.setDriverClassName(driver)
    hikariConfig.setJdbcUrl(dbUrl)
    NetworkDbConfig(hikariConfig)
  }

  def mainnetConfig(config: Config): NetworkDbConfig = {
    fromConfig(config, "mainnet")
  }

  def testnetConfig(config: Config): NetworkDbConfig = {
    fromConfig(config,"testnet")
  }

  def regtestConfig(config: Config): NetworkDbConfig = {
    fromConfig(config,"regtest")
  }

  def unittestConfig(config: Config): NetworkDbConfig = {
    fromConfig(config,"unittest")
  }
}
