/*
 * Copyright 2019 ACINQ SAS
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32, Satoshi}
import fr.acinq.eclair.NodeParams.WatcherType
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeTargets, OnChainFeeConf}
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.crypto.KeyManager
import fr.acinq.eclair.db._
import fr.acinq.eclair.router.RouterConf
import fr.acinq.eclair.tor.Socks5ProxyParams
import fr.acinq.eclair.wire.{Color, EncodingType, NodeAddress}
import scodec.bits.ByteVector

import scala.collection.JavaConversions._
import scala.concurrent.duration.FiniteDuration

/**
 * Created by PM on 26/02/2017.
 */
case class NodeParams(keyManager: KeyManager,
                      private val blockCount: AtomicLong,
                      alias: String,
                      color: Color,
                      publicAddresses: List[NodeAddress],
                      globalFeatures: ByteVector,
                      localFeatures: ByteVector,
                      overrideFeatures: Map[PublicKey, (ByteVector, ByteVector)],
                      syncWhitelist: Set[PublicKey],
                      dustLimit: Satoshi,
                      onChainFeeConf: OnChainFeeConf,
                      maxHtlcValueInFlightMsat: UInt64,
                      maxAcceptedHtlcs: Int,
                      expiryDeltaBlocks: CltvExpiryDelta,
                      fulfillSafetyBeforeTimeoutBlocks: CltvExpiryDelta,
                      htlcMinimum: MilliSatoshi,
                      toRemoteDelayBlocks: CltvExpiryDelta,
                      maxToLocalDelayBlocks: CltvExpiryDelta,
                      minDepthBlocks: Int,
                      feeBase: MilliSatoshi,
                      feeProportionalMillionth: Int,
                      reserveToFundingRatio: Double,
                      maxReserveToFundingRatio: Double,
                      db: Databases,
                      revocationTimeout: FiniteDuration,
                      pingInterval: FiniteDuration,
                      pingTimeout: FiniteDuration,
                      pingDisconnect: Boolean,
                      autoReconnect: Boolean,
                      initialRandomReconnectDelay: FiniteDuration,
                      maxReconnectInterval: FiniteDuration,
                      chainHash: ByteVector32,
                      channelFlags: Byte,
                      watcherType: WatcherType,
                      paymentRequestExpiry: FiniteDuration,
                      multiPartPaymentExpiry: FiniteDuration,
                      minFundingSatoshis: Satoshi,
                      routerConf: RouterConf,
                      socksProxy_opt: Option[Socks5ProxyParams],
                      maxPaymentAttempts: Int,
                      enableTrampolineRouting: Boolean) {
  val privateKey = keyManager.nodeKey.privateKey
  val nodeId = keyManager.nodeId
  def currentBlockHeight: Long = blockCount.get
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
      .withFallback(ConfigFactory.load())

  def getSeed(datadir: File): ByteVector = {
    val seedPath = new File(datadir, "seed.dat")
    if (seedPath.exists()) {
      ByteVector(Files.readAllBytes(seedPath.toPath))
    } else {
      datadir.mkdirs()
      val seed = randomBytes32
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

  def makeNodeParams(config: Config, keyManager: KeyManager, torAddress_opt: Option[NodeAddress], database: Databases, blockCount: AtomicLong, feeEstimator: FeeEstimator): NodeParams = {
    // check configuration for keys that have been renamed in v0.3.2
    val deprecatedKeyPaths = Map(
      "default-feerates" -> "on-chain-fees.default-feerates",
      "max-feerate-mismatch" -> "on-chain-fees.max-feerate-mismatch",
      "update-fee_min-diff-ratio" -> "on-chain-fees.update-fee-min-diff-ratio"
    )
    deprecatedKeyPaths.foreach {
      case (old, new_) => require(!config.hasPath(old), s"configuration key '$old' has been replaced by '$new_'")
    }

    val chain = config.getString("chain")
    val chainHash = makeChainHash(chain)

    val color = ByteVector.fromValidHex(config.getString("node-color"))
    require(color.size == 3, "color should be a 3-bytes hex buffer")

    val watcherType = config.getString("watcher-type") match {
      case "electrum" => ELECTRUM
      case _ => BITCOIND
    }

    val dustLimitSatoshis = Satoshi(config.getLong("dust-limit-satoshis"))
    if (chainHash == Block.LivenetGenesisBlock.hash) {
      require(dustLimitSatoshis >= Channel.MIN_DUSTLIMIT, s"dust limit must be greater than ${Channel.MIN_DUSTLIMIT}")
    }

    val maxAcceptedHtlcs = config.getInt("max-accepted-htlcs")
    require(maxAcceptedHtlcs <= Channel.MAX_ACCEPTED_HTLCS, s"max-accepted-htlcs must be lower than ${Channel.MAX_ACCEPTED_HTLCS}")

    val maxToLocalCLTV = CltvExpiryDelta(config.getInt("max-to-local-delay-blocks"))
    val offeredCLTV = CltvExpiryDelta(config.getInt("to-remote-delay-blocks"))
    require(maxToLocalCLTV <= Channel.MAX_TO_SELF_DELAY && offeredCLTV <= Channel.MAX_TO_SELF_DELAY, s"CLTV delay values too high, max is ${Channel.MAX_TO_SELF_DELAY}")

    val expiryDeltaBlocks = CltvExpiryDelta(config.getInt("expiry-delta-blocks"))
    val fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(config.getInt("fulfill-safety-before-timeout-blocks"))
    require(fulfillSafetyBeforeTimeoutBlocks < expiryDeltaBlocks, "fulfill-safety-before-timeout-blocks must be smaller than expiry-delta-blocks")

    val nodeAlias = config.getString("node-alias")
    require(nodeAlias.getBytes("UTF-8").length <= 32, "invalid alias, too long (max allowed 32 bytes)")

    val overrideFeatures: Map[PublicKey, (ByteVector, ByteVector)] = config.getConfigList("override-features").map { e =>
      val p = PublicKey(ByteVector.fromValidHex(e.getString("nodeid")))
      val gf = ByteVector.fromValidHex(e.getString("global-features"))
      val lf = ByteVector.fromValidHex(e.getString("local-features"))
      p -> (gf, lf)
    }.toMap

    val syncWhitelist: Set[PublicKey] = config.getStringList("sync-whitelist").map(s => PublicKey(ByteVector.fromValidHex(s))).toSet

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

    val feeTargets = FeeTargets(
      fundingBlockTarget = config.getInt("on-chain-fees.target-blocks.funding"),
      commitmentBlockTarget = config.getInt("on-chain-fees.target-blocks.commitment"),
      mutualCloseBlockTarget = config.getInt("on-chain-fees.target-blocks.mutual-close"),
      claimMainBlockTarget = config.getInt("on-chain-fees.target-blocks.claim-main")
    )

    val feeBase = MilliSatoshi(config.getInt("fee-base-msat"))
    // fee base is in msat but is encoded on 32 bits and not 64 in the BOLTs, which is why it has
    // to be below 0x100000000 msat which is about 42 mbtc
    require(feeBase <= MilliSatoshi(0xFFFFFFFFL), "fee-base-msat must be below 42 mbtc")

    val routerSyncEncodingType = config.getString("router.sync.encoding-type") match {
      case "uncompressed" => EncodingType.UNCOMPRESSED
      case "zlib" => EncodingType.COMPRESSED_ZLIB
    }

    NodeParams(
      keyManager = keyManager,
      blockCount = blockCount,
      alias = nodeAlias,
      color = Color(color(0), color(1), color(2)),
      publicAddresses = addresses,
      globalFeatures = ByteVector.fromValidHex(config.getString("global-features")),
      localFeatures = ByteVector.fromValidHex(config.getString("local-features")),
      overrideFeatures = overrideFeatures,
      syncWhitelist = syncWhitelist,
      dustLimit = dustLimitSatoshis,
      onChainFeeConf = OnChainFeeConf(
        feeTargets = feeTargets,
        feeEstimator = feeEstimator,
        maxFeerateMismatch = config.getDouble("on-chain-fees.max-feerate-mismatch"),
        closeOnOfflineMismatch = config.getBoolean("on-chain-fees.close-on-offline-feerate-mismatch"),
        updateFeeMinDiffRatio = config.getDouble("on-chain-fees.update-fee-min-diff-ratio")
      ),
      maxHtlcValueInFlightMsat = UInt64(config.getLong("max-htlc-value-in-flight-msat")),
      maxAcceptedHtlcs = maxAcceptedHtlcs,
      expiryDeltaBlocks = expiryDeltaBlocks,
      fulfillSafetyBeforeTimeoutBlocks = fulfillSafetyBeforeTimeoutBlocks,
      htlcMinimum = MilliSatoshi(config.getInt("htlc-minimum-msat")),
      toRemoteDelayBlocks = CltvExpiryDelta(config.getInt("to-remote-delay-blocks")),
      maxToLocalDelayBlocks = CltvExpiryDelta(config.getInt("max-to-local-delay-blocks")),
      minDepthBlocks = config.getInt("mindepth-blocks"),
      feeBase = feeBase,
      feeProportionalMillionth = config.getInt("fee-proportional-millionths"),
      reserveToFundingRatio = config.getDouble("reserve-to-funding-ratio"),
      maxReserveToFundingRatio = config.getDouble("max-reserve-to-funding-ratio"),
      db = database,
      revocationTimeout = FiniteDuration(config.getDuration("revocation-timeout").getSeconds, TimeUnit.SECONDS),
      pingInterval = FiniteDuration(config.getDuration("ping-interval").getSeconds, TimeUnit.SECONDS),
      pingTimeout = FiniteDuration(config.getDuration("ping-timeout").getSeconds, TimeUnit.SECONDS),
      pingDisconnect = config.getBoolean("ping-disconnect"),
      autoReconnect = config.getBoolean("auto-reconnect"),
      initialRandomReconnectDelay = FiniteDuration(config.getDuration("initial-random-reconnect-delay").getSeconds, TimeUnit.SECONDS),
      maxReconnectInterval = FiniteDuration(config.getDuration("max-reconnect-interval").getSeconds, TimeUnit.SECONDS),
      chainHash = chainHash,
      channelFlags = config.getInt("channel-flags").toByte,
      watcherType = watcherType,
      paymentRequestExpiry = FiniteDuration(config.getDuration("payment-request-expiry").getSeconds, TimeUnit.SECONDS),
      multiPartPaymentExpiry = FiniteDuration(config.getDuration("multi-part-payment-expiry").getSeconds, TimeUnit.SECONDS),
      minFundingSatoshis = Satoshi(config.getLong("min-funding-satoshis")),
      routerConf = RouterConf(
        channelExcludeDuration = FiniteDuration(config.getDuration("router.channel-exclude-duration").getSeconds, TimeUnit.SECONDS),
        routerBroadcastInterval = FiniteDuration(config.getDuration("router.broadcast-interval").getSeconds, TimeUnit.SECONDS),
        networkStatsRefreshInterval = FiniteDuration(config.getDuration("router.network-stats-interval").getSeconds, TimeUnit.SECONDS),
        randomizeRouteSelection = config.getBoolean("router.randomize-route-selection"),
        requestNodeAnnouncements = config.getBoolean("router.sync.request-node-announcements"),
        encodingType = routerSyncEncodingType,
        channelRangeChunkSize = config.getInt("router.sync.channel-range-chunk-size"),
        channelQueryChunkSize = config.getInt("router.sync.channel-query-chunk-size"),
        searchMaxRouteLength = config.getInt("router.path-finding.max-route-length"),
        searchMaxCltv = CltvExpiryDelta(config.getInt("router.path-finding.max-cltv")),
        searchMaxFeeBase = Satoshi(config.getLong("router.path-finding.fee-threshold-sat")),
        searchMaxFeePct = config.getDouble("router.path-finding.max-fee-pct"),
        searchHeuristicsEnabled = config.getBoolean("router.path-finding.heuristics-enable"),
        searchRatioCltv = config.getDouble("router.path-finding.ratio-cltv"),
        searchRatioChannelAge = config.getDouble("router.path-finding.ratio-channel-age"),
        searchRatioChannelCapacity = config.getDouble("router.path-finding.ratio-channel-capacity")
      ),
      socksProxy_opt = socksProxy_opt,
      maxPaymentAttempts = config.getInt("max-payment-attempts"),
      enableTrampolineRouting = config.getBoolean("trampoline-routing-enable")
    )
  }
}
