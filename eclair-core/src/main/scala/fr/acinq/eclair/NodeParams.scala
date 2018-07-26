/*
 * Copyright 2018 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

import com.google.common.net.InetAddresses
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.{BinaryData, Block}
import fr.acinq.eclair.NodeParams.WatcherType
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.sqlite._
import fr.acinq.eclair.wire.Color

import scala.collection.JavaConversions._
import scala.concurrent.duration.FiniteDuration

/**
  * Created by PM on 26/02/2017.
  */
case class NodeParams(keyManager: KeyManager,
                      alias: String,
                      color: Color,
                      publicAddresses: List[InetSocketAddress],
                      globalFeatures: BinaryData,
                      localFeatures: BinaryData,
                      dustLimitSatoshis: Long,
                      maxHtlcValueInFlightMsat: UInt64,
                      maxAcceptedHtlcs: Int,
                      expiryDeltaBlocks: Int,
                      htlcMinimumMsat: Int,
                      toRemoteDelayBlocks: Int,
                      maxToLocalDelayBlocks: Int,
                      minDepthBlocks: Int,
                      smartfeeNBlocks: Int,
                      feeBaseMsat: Int,
                      feeProportionalMillionth: Int,
                      reserveToFundingRatio: Double,
                      maxReserveToFundingRatio: Double,
                      channelsDb: ChannelsDb,
                      peersDb: PeersDb,
                      networkDb: NetworkDb,
                      pendingRelayDb: PendingRelayDb,
                      paymentsDb: PaymentsDb,
                      auditDb: AuditDb,
                      routerBroadcastInterval: FiniteDuration,
                      pingInterval: FiniteDuration,
                      maxFeerateMismatch: Double,
                      updateFeeMinDiffRatio: Double,
                      autoReconnect: Boolean,
                      chainHash: BinaryData,
                      channelFlags: Byte,
                      channelExcludeDuration: FiniteDuration,
                      watcherType: WatcherType,
                      paymentRequestExpiry: FiniteDuration,
                      maxPendingPaymentRequests: Int,
                      maxPaymentFee: Double,
                      minFundingSatoshis: Long) {
  val privateKey = keyManager.nodeKey.privateKey
  val nodeId = keyManager.nodeId
}

object NodeParams {

  sealed trait WatcherType

  object BITCOIND extends WatcherType

  object ELECTRUM extends WatcherType

  /**
    * Order of precedence for the configuration parameters:
    * 1) Java environment variables (-D...)
    * 2) Configuration file eclair.conf
    * 3) Optionally provided config
    * 4) Default values in reference.conf
    */
  def loadConfiguration(datadir: File, overrideDefaults: Config = ConfigFactory.empty()) =
    ConfigFactory.parseProperties(System.getProperties)
      .withFallback(ConfigFactory.parseFile(new File(datadir, "eclair.conf")))
      .withFallback(overrideDefaults)
      .withFallback(ConfigFactory.load()).getConfig("eclair")

  def getSeed(datadir: File): BinaryData = {
    val seedPath = new File(datadir, "seed.dat")
    seedPath.exists() match {
      case true => Files.readAllBytes(seedPath.toPath)
      case false =>
        datadir.mkdirs()
        val seed = randomKey.toBin
        Files.write(seedPath.toPath, seed)
        seed
    }
  }

  def makeChainHash(chain: String): BinaryData = {
    chain match {
      case "regtest" => Block.RegtestGenesisBlock.hash
      case "testnet" => Block.TestnetGenesisBlock.hash
      case "mainnet" => Block.LivenetGenesisBlock.hash
      case invalid => throw new RuntimeException(s"invalid chain '$invalid'")
    }
  }

  def makeNodeParams(datadir: File, config: Config, keyManager: KeyManager): NodeParams = {

    datadir.mkdirs()

    val chain = config.getString("chain")
    val chainHash = makeChainHash(chain)

    val chaindir = new File(datadir, chain)
    chaindir.mkdir()

    val sqlite = DriverManager.getConnection(s"jdbc:sqlite:${new File(chaindir, "eclair.sqlite")}")
    val channelsDb = new SqliteChannelsDb(sqlite)
    val peersDb = new SqlitePeersDb(sqlite)
    val pendingRelayDb = new SqlitePendingRelayDb(sqlite)
    val paymentsDb = new SqlitePaymentsDb(sqlite)

    val sqliteNetwork = DriverManager.getConnection(s"jdbc:sqlite:${new File(chaindir, "network.sqlite")}")
    val networkDb = new SqliteNetworkDb(sqliteNetwork)

    val sqliteAudit = DriverManager.getConnection(s"jdbc:sqlite:${new File(chaindir, "audit.sqlite")}")
    val auditDb = new SqliteAuditDb(sqliteAudit)

    val color = BinaryData(config.getString("node-color"))
    require(color.size == 3, "color should be a 3-bytes hex buffer")

    val watcherType = config.getString("watcher-type") match {
      case "electrum" => ELECTRUM
      case _ => BITCOIND
    }

    val dustLimitSatoshis = config.getLong("dust-limit-satoshis")
    if (chainHash == Block.LivenetGenesisBlock.hash) {
      require(dustLimitSatoshis >= Channel.MIN_DUSTLIMIT, s"dust limit must be greater than ${Channel.MIN_DUSTLIMIT}")
    }

    val maxAcceptedHtlcs = config.getInt("max-accepted-htlcs")
    require(maxAcceptedHtlcs <= Channel.MAX_ACCEPTED_HTLCS, s"max-accepted-htlcs must be lower than ${Channel.MAX_ACCEPTED_HTLCS}")

    NodeParams(
      keyManager = keyManager,
      alias = config.getString("node-alias").take(32),
      color = Color(color.data(0), color.data(1), color.data(2)),
      publicAddresses = config.getStringList("server.public-ips").toList.map(ip => new InetSocketAddress(InetAddresses.forString(ip), config.getInt("server.port"))),
      globalFeatures = BinaryData(config.getString("global-features")),
      localFeatures = BinaryData(config.getString("local-features")),
      dustLimitSatoshis = dustLimitSatoshis,
      maxHtlcValueInFlightMsat = UInt64(config.getLong("max-htlc-value-in-flight-msat")),
      maxAcceptedHtlcs = maxAcceptedHtlcs,
      expiryDeltaBlocks = config.getInt("expiry-delta-blocks"),
      htlcMinimumMsat = config.getInt("htlc-minimum-msat"),
      toRemoteDelayBlocks = config.getInt("to-remote-delay-blocks"),
      maxToLocalDelayBlocks = config.getInt("max-to-local-delay-blocks"),
      minDepthBlocks = config.getInt("mindepth-blocks"),
      smartfeeNBlocks = 3,
      feeBaseMsat = config.getInt("fee-base-msat"),
      feeProportionalMillionth = config.getInt("fee-proportional-millionths"),
      reserveToFundingRatio = config.getDouble("reserve-to-funding-ratio"),
      maxReserveToFundingRatio = config.getDouble("max-reserve-to-funding-ratio"),
      channelsDb = channelsDb,
      peersDb = peersDb,
      networkDb = networkDb,
      pendingRelayDb = pendingRelayDb,
      paymentsDb = paymentsDb,
      auditDb = auditDb,
      routerBroadcastInterval = FiniteDuration(config.getDuration("router-broadcast-interval").getSeconds, TimeUnit.SECONDS),
      pingInterval = FiniteDuration(config.getDuration("ping-interval").getSeconds, TimeUnit.SECONDS),
      maxFeerateMismatch = config.getDouble("max-feerate-mismatch"),
      updateFeeMinDiffRatio = config.getDouble("update-fee_min-diff-ratio"),
      autoReconnect = config.getBoolean("auto-reconnect"),
      chainHash = chainHash,
      channelFlags = config.getInt("channel-flags").toByte,
      channelExcludeDuration = FiniteDuration(config.getDuration("channel-exclude-duration").getSeconds, TimeUnit.SECONDS),
      watcherType = watcherType,
      paymentRequestExpiry = FiniteDuration(config.getDuration("payment-request-expiry").getSeconds, TimeUnit.SECONDS),
      maxPendingPaymentRequests = config.getInt("max-pending-payment-requests"),
      maxPaymentFee = config.getDouble("max-payment-fee"),
      minFundingSatoshis = config.getLong("min-funding-satoshis")
    )
  }
}
