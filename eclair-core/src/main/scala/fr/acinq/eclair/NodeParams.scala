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

import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32}
import fr.acinq.eclair.NodeParams.WatcherType
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.sqlite._
import fr.acinq.eclair.router.RouterConf
import fr.acinq.eclair.tor.Socks5ProxyParams
import fr.acinq.eclair.wire.{Color, NodeAddress}
import scodec.bits.ByteVector

import scala.collection.JavaConversions._
import scala.concurrent.duration.FiniteDuration

/**
  * Created by PM on 26/02/2017.
  */
case class NodeParams(keyManager: KeyManager,
                      alias: String,
                      color: Color,
                      publicAddresses: List[NodeAddress],
                      globalFeatures: ByteVector,
                      localFeatures: ByteVector,
                      overrideFeatures: Map[PublicKey, (ByteVector, ByteVector)],
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
                      db: Databases,
                      revocationTimeout: FiniteDuration,
                      pingInterval: FiniteDuration,
                      pingTimeout: FiniteDuration,
                      pingDisconnect: Boolean,
                      maxFeerateMismatch: Double,
                      updateFeeMinDiffRatio: Double,
                      autoReconnect: Boolean,
                      chainHash: ByteVector32,
                      channelFlags: Byte,
                      watcherType: WatcherType,
                      paymentRequestExpiry: FiniteDuration,
                      minFundingSatoshis: Long,
                      routerConf: RouterConf,
                      socksProxy_opt: Option[Socks5ProxyParams],
                      maxPaymentAttempts: Int) {

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

  def getSeed(datadir: File): ByteVector = {
    val seedPath = new File(datadir, "seed.dat")
    seedPath.exists() match {
      case true => ByteVector(Files.readAllBytes(seedPath.toPath))
      case false =>
        datadir.mkdirs()
        val seed = randomKey.toBin
        Files.write(seedPath.toPath, seed.toArray)
        seed
    }
  }

  def makeChainHash(chain: String): ByteVector32 = {
    chain match {
      case "regtest" => Block.RegtestGenesisBlock.hash
      case "testnet" => Block.TestnetGenesisBlock.hash
      case "mainnet" => Block.LivenetGenesisBlock.hash
      case invalid => throw new RuntimeException(s"invalid chain '$invalid'")
    }
  }

  def makeNodeParams(config: Config, keyManager: KeyManager, torAddress_opt: Option[NodeAddress], database: Databases): NodeParams = {

    val chain = config.getString("chain")
    val chainHash = makeChainHash(chain)

    val color = ByteVector.fromValidHex(config.getString("node-color"))
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

    val maxToLocalCLTV = config.getInt("max-to-local-delay-blocks")
    val offeredCLTV = config.getInt("to-remote-delay-blocks")
    require(maxToLocalCLTV <= Channel.MAX_TO_SELF_DELAY && offeredCLTV <= Channel.MAX_TO_SELF_DELAY, s"CLTV delay values too high, max is ${Channel.MAX_TO_SELF_DELAY}")

    val nodeAlias = config.getString("node-alias")
    require(nodeAlias.getBytes("UTF-8").length <= 32, "invalid alias, too long (max allowed 32 bytes)")

    val overrideFeatures: Map[PublicKey, (ByteVector, ByteVector)] = config.getConfigList("override-features").map { e =>
      val p = PublicKey(ByteVector.fromValidHex(e.getString("nodeid")))
      val gf = ByteVector.fromValidHex(e.getString("global-features"))
      val lf = ByteVector.fromValidHex(e.getString("local-features"))
      (p -> (gf, lf))
    }.toMap

    val socksProxy_opt = if (config.getBoolean("socks5.enabled")) {
      Some(Socks5ProxyParams(
        address = new InetSocketAddress(config.getString("socks5.host"), config.getInt("socks5.port")),
        credentials_opt = None,
        randomizeCredentials = config.getBoolean("socks5.randomize-credentials"),
        useForIPv4 = config.getBoolean("socks5.use-for-ipv4"),
        useForIPv6 = config.getBoolean("socks5.use-for-ipv6"),
        useForTor = config.getBoolean("socks5.use-for-tor")
      ))
    } else {
      None
    }

    val addresses = config.getStringList("server.public-ips")
      .toList
      .map(ip => NodeAddress.fromParts(ip, config.getInt("server.port")).get) ++ torAddress_opt

    NodeParams(
      keyManager = keyManager,
      alias = nodeAlias,
      color = Color(color(0), color(1), color(2)),
      publicAddresses = addresses,
      globalFeatures = ByteVector.fromValidHex(config.getString("global-features")),
      localFeatures = ByteVector.fromValidHex(config.getString("local-features")),
      overrideFeatures = overrideFeatures,
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
      db = database,
      revocationTimeout = FiniteDuration(config.getDuration("revocation-timeout").getSeconds, TimeUnit.SECONDS),
      pingInterval = FiniteDuration(config.getDuration("ping-interval").getSeconds, TimeUnit.SECONDS),
      pingTimeout = FiniteDuration(config.getDuration("ping-timeout").getSeconds, TimeUnit.SECONDS),
      pingDisconnect = config.getBoolean("ping-disconnect"),
      maxFeerateMismatch = config.getDouble("max-feerate-mismatch"),
      updateFeeMinDiffRatio = config.getDouble("update-fee_min-diff-ratio"),
      autoReconnect = config.getBoolean("auto-reconnect"),
      chainHash = chainHash,
      channelFlags = config.getInt("channel-flags").toByte,
      watcherType = watcherType,
      paymentRequestExpiry = FiniteDuration(config.getDuration("payment-request-expiry").getSeconds, TimeUnit.SECONDS),
      minFundingSatoshis = config.getLong("min-funding-satoshis"),
      routerConf = RouterConf(
        channelExcludeDuration = FiniteDuration(config.getDuration("router.channel-exclude-duration").getSeconds, TimeUnit.SECONDS),
        routerBroadcastInterval = FiniteDuration(config.getDuration("router.broadcast-interval").getSeconds, TimeUnit.SECONDS),
        randomizeRouteSelection = config.getBoolean("router.randomize-route-selection"),
        searchMaxRouteLength = config.getInt("router.path-finding.max-route-length"),
        searchMaxCltv = config.getInt("router.path-finding.max-cltv"),
        searchMaxFeeBaseSat = config.getLong("router.path-finding.fee-threshold-sat"),
        searchMaxFeePct = config.getDouble("router.path-finding.max-fee-pct"),
        searchHeuristicsEnabled = config.getBoolean("router.path-finding.heuristics-enable"),
        searchRatioCltv = config.getDouble("router.path-finding.ratio-cltv"),
        searchRatioChannelAge = config.getDouble("router.path-finding.ratio-channel-age"),
        searchRatioChannelCapacity = config.getDouble("router.path-finding.ratio-channel-capacity")
      ),
      socksProxy_opt = socksProxy_opt,
      maxPaymentAttempts = config.getInt("max-payment-attempts")
    )
  }
}
