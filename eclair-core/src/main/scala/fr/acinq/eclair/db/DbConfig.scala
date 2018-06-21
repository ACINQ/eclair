package fr.acinq.eclair.db

import java.io.File
import java.sql.Connection

import com.typesafe.config.Config
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import fr.acinq.eclair.db.EclairDbConfig.logger
import grizzled.slf4j.Logging



case class AppDbConfig(eclairDb: EclairDbConfig, networkDb: NetworkDbConfig)

abstract class DbConfig(hikariConfig: HikariConfig) extends Logging {
  private val dataSource: HikariDataSource = {
    new HikariDataSource(hikariConfig)
  }

  def getConnection(): Connection = dataSource.getConnection

  /** Closes [[com.zaxxer.hikari.HikariDataSource]] */
  def close(): Unit = dataSource.close()

  def isClosed(): Boolean = dataSource.isClosed

  def isRunning(): Boolean = dataSource.isRunning
}

case class EclairDbConfig(hikariConfig: HikariConfig) extends DbConfig(hikariConfig)

case class NetworkDbConfig(hikariConfig: HikariConfig) extends DbConfig(hikariConfig)

object EclairDbConfig extends Logging {

  /** Reads the network you want from the reference.conf file */
  def fromConfig(config: Config): EclairDbConfig = {
    val chain = config.getString("eclair.chain")
    fromConfig(config,chain)
  }

  private def fromConfig(config: Config, chain: String): EclairDbConfig = {
    val driver = config.getString(s"eclair.db.driver")
    val dbChainConfig = config.getConfig(s"eclair.db.${chain}")
    val dbUrl = dbChainConfig.getString("url")
    val hikariConfig = new HikariConfig()
    hikariConfig.setDriverClassName(driver)

    //create file if it DNE
    val filePath = dbUrl.split(":").last
    val file = new File(filePath)
    file.mkdirs()
    val db = new File(file.getAbsolutePath + "/eclair.sqlite")
    db.createNewFile()

    hikariConfig.setJdbcUrl(dbUrl + "/eclair.sqlite")
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

object NetworkDbConfig extends Logging  {

  /** Reads the network you want from the reference.conf file */
  def fromConfig(config: Config): NetworkDbConfig = {
    val chain = config.getString("eclair.chain")
    fromConfig(config,chain)
  }

  private def fromConfig(config: Config, chain: String): NetworkDbConfig = {
    val driver = config.getString(s"eclair.db.driver")
    val dbChainConfig = config.getConfig(s"eclair.db.${chain}")
    val dbUrl = dbChainConfig.getString("url")
    val hikariConfig = new HikariConfig()
    hikariConfig.setDriverClassName(driver)
    hikariConfig.setJdbcUrl(dbUrl)

    //create file if it DNE
    val filePath = dbUrl.split(":").last
    val file = new File(filePath)
    file.mkdirs()
    val db = new File(file.getAbsolutePath + "/network.sqlite")
    db.createNewFile()

    hikariConfig.setJdbcUrl(dbUrl + "/network.sqlite")
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
