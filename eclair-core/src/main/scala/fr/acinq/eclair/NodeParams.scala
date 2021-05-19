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

import com.typesafe.config.{Config, ConfigFactory, ConfigValueType}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin.{Block, ByteVector32, Crypto, Satoshi}
import fr.acinq.eclair.Setup.Seeds
import fr.acinq.eclair.blockchain.fee._
import fr.acinq.eclair.channel.Channel
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.crypto.keymanager.{ChannelKeyManager, NodeKeyManager}
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.PeerConnection
import fr.acinq.eclair.router.Router.RouterConf
import fr.acinq.eclair.tor.Socks5ProxyParams
import fr.acinq.eclair.wire.protocol.{Color, EncodingType, NodeAddress}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/**
 * Created by PM on 26/02/2017.
 */
case class NodeParams(nodeKeyManager: NodeKeyManager,
                      channelKeyManager: ChannelKeyManager,
                      instanceId: UUID, // a unique instance ID regenerated after each restart
                      private val blockCount: AtomicLong,
                      alias: String,
                      color: Color,
                      publicAddresses: List[NodeAddress],
                      features: Features,
                      private val overrideFeatures: Map[PublicKey, Features],
                      syncWhitelist: Set[PublicKey],
                      pluginParams: Seq[PluginParams],
                      dustLimit: Satoshi,
                      maxRemoteDustLimit: Satoshi,
                      onChainFeeConf: OnChainFeeConf,
                      maxHtlcValueInFlightMsat: UInt64,
                      maxAcceptedHtlcs: Int,
                      expiryDelta: CltvExpiryDelta,
                      fulfillSafetyBeforeTimeout: CltvExpiryDelta,
                      minFinalExpiryDelta: CltvExpiryDelta,
                      htlcMinimum: MilliSatoshi,
                      toRemoteDelay: CltvExpiryDelta,
                      maxToLocalDelay: CltvExpiryDelta,
                      minDepthBlocks: Int,
                      feeBase: MilliSatoshi,
                      feeProportionalMillionth: Int,
                      reserveToFundingRatio: Double,
                      maxReserveToFundingRatio: Double,
                      db: Databases,
                      revocationTimeout: FiniteDuration,
                      autoReconnect: Boolean,
                      initialRandomReconnectDelay: FiniteDuration,
                      maxReconnectInterval: FiniteDuration,
                      chainHash: ByteVector32,
                      channelFlags: Byte,
                      watchSpentWindow: FiniteDuration,
                      paymentRequestExpiry: FiniteDuration,
                      multiPartPaymentExpiry: FiniteDuration,
                      minFundingSatoshis: Satoshi,
                      maxFundingSatoshis: Satoshi,
                      peerConnectionConf: PeerConnection.Conf,
                      routerConf: RouterConf,
                      socksProxy_opt: Option[Socks5ProxyParams],
                      maxPaymentAttempts: Int,
                      enableTrampolinePayment: Boolean) {
  val privateKey: Crypto.PrivateKey = nodeKeyManager.nodeKey.privateKey

  val nodeId: PublicKey = nodeKeyManager.nodeId

  val keyPair: KeyPair = KeyPair(nodeId.value, privateKey.value)

  val pluginMessageTags: Set[Int] = pluginParams.collect { case p: CustomFeaturePlugin => p.messageTags }.toSet.flatten

  def currentBlockHeight: Long = blockCount.get

  def featuresFor(nodeId: PublicKey): Features = overrideFeatures.getOrElse(nodeId, features)
}

object NodeParams extends Logging {

  // @formatter:off
  sealed trait WatcherType
  object BITCOIND extends WatcherType
  // @formatter:on

  /**
   * Order of precedence for the configuration parameters:
   * 1) Java environment variables (-D...)
   * 2) Configuration file eclair.conf
   * 3) Optionally provided config
   * 4) Default values in reference.conf
   */
  def loadConfiguration(datadir: File): Config =
    ConfigFactory.parseProperties(System.getProperties)
      .withFallback(ConfigFactory.parseFile(new File(datadir, "eclair.conf")))
      .withFallback(ConfigFactory.load())

  private def readSeedFromFile(seedPath: File): ByteVector = {
    logger.info(s"use seed file: ${seedPath.getCanonicalPath}")
    ByteVector(Files.readAllBytes(seedPath.toPath))
  }

  private def writeSeedToFile(path: File, seed: ByteVector): Unit = {
    Files.write(path.toPath, seed.toArray)
    logger.info(s"create new seed file: ${path.getCanonicalPath}")
  }

  private def migrateSeedFile(source: File, destination: File): Unit = {
    if (source.exists() && !destination.exists()) {
      Files.copy(source.toPath, destination.toPath)
      logger.info(s"migrate seed file: ${source.getCanonicalPath} → ${destination.getCanonicalPath}")
    }
  }

  def getSeeds(datadir: File): Seeds = {
    // Previously we used one seed file ("seed.dat") to generate the node and the channel private keys
    // Now we use two separate files and thus we need to migrate the old seed file if necessary
    val oldSeedPath = new File(datadir, "seed.dat")
    val nodeSeedFilename: String = "node_seed.dat"
    val channelSeedFilename: String = "channel_seed.dat"

    def getSeed(filename: String): ByteVector = {
      val seedPath = new File(datadir, filename)
      if (seedPath.exists()) {
        readSeedFromFile(seedPath)
      } else if (oldSeedPath.exists()) {
        migrateSeedFile(oldSeedPath, seedPath)
        readSeedFromFile(seedPath)
      } else {
        val randomSeed = randomBytes32()
        writeSeedToFile(seedPath, randomSeed)
        randomSeed.bytes
      }
    }

    val nodeSeed = getSeed(nodeSeedFilename)
    val channelSeed = getSeed(channelSeedFilename)
    Seeds(nodeSeed, channelSeed)
  }

  private val chain2Hash: Map[String, ByteVector32] = Map(
    "regtest" -> Block.RegtestGenesisBlock.hash,
    "testnet" -> Block.TestnetGenesisBlock.hash,
    "mainnet" -> Block.LivenetGenesisBlock.hash
  )

  def hashFromChain(chain: String): ByteVector32 = chain2Hash.getOrElse(chain, throw new RuntimeException(s"invalid chain '$chain'"))

  def chainFromHash(chainHash: ByteVector32): String = chain2Hash.map(_.swap).getOrElse(chainHash, throw new RuntimeException(s"invalid chainHash '$chainHash'"))

  def makeNodeParams(config: Config, instanceId: UUID, nodeKeyManager: NodeKeyManager, channelKeyManager: ChannelKeyManager,
                     torAddress_opt: Option[NodeAddress], database: Databases, blockCount: AtomicLong, feeEstimator: FeeEstimator,
                     pluginParams: Seq[PluginParams] = Nil): NodeParams = {
    // check configuration for keys that have been renamed
    val deprecatedKeyPaths = Map(
      // v0.3.2
      "default-feerates" -> "on-chain-fees.default-feerates",
      "max-feerate-mismatch" -> "on-chain-fees.max-feerate-mismatch",
      "update-fee_min-diff-ratio" -> "on-chain-fees.update-fee-min-diff-ratio",
      // v0.3.3
      "global-features" -> "features",
      "local-features" -> "features",
      // v0.4.1
      "on-chain-fees.max-feerate-mismatch" -> "on-chain-fees.feerate-tolerance.ratio-low / on-chain-fees.feerate-tolerance.ratio-high",
      // v0.4.3
      "min-feerate" -> "on-chain-fees.min-feerate",
      "smooth-feerate-window" -> "on-chain-fees.smoothing-window",
      "feerate-provider-timeout" -> "on-chain-fees.provider-timeout"
    )
    deprecatedKeyPaths.foreach {
      case (old, new_) => require(!config.hasPath(old), s"configuration key '$old' has been replaced by '$new_'")
    }

    // since v0.4.1 features cannot be a byte vector (hex string)
    val isFeatureByteVector = config.getValue("features").valueType() == ConfigValueType.STRING
    require(!isFeatureByteVector, "configuration key 'features' have moved from bytevector to human readable (ex: 'feature-name' = optional/mandatory)")

    val chain = config.getString("chain")
    val chainHash = hashFromChain(chain)

    val color = ByteVector.fromValidHex(config.getString("node-color"))
    require(color.size == 3, "color should be a 3-bytes hex buffer")

    val watchSpentWindow = FiniteDuration(config.getDuration("watch-spent-window").getSeconds, TimeUnit.SECONDS)
    require(watchSpentWindow > 0.seconds, "watch-spent-window must be strictly greater than 0")

    val dustLimitSatoshis = Satoshi(config.getLong("dust-limit-satoshis"))
    if (chainHash == Block.LivenetGenesisBlock.hash) {
      require(dustLimitSatoshis >= Channel.MIN_DUSTLIMIT, s"dust limit must be greater than ${Channel.MIN_DUSTLIMIT}")
    }

    val htlcMinimum = MilliSatoshi(config.getInt("htlc-minimum-msat"))
    require(htlcMinimum > 0.msat, "htlc-minimum-msat must be strictly greater than 0")

    val maxAcceptedHtlcs = config.getInt("max-accepted-htlcs")
    require(maxAcceptedHtlcs <= Channel.MAX_ACCEPTED_HTLCS, s"max-accepted-htlcs must be lower than ${Channel.MAX_ACCEPTED_HTLCS}")

    val maxToLocalCLTV = CltvExpiryDelta(config.getInt("max-to-local-delay-blocks"))
    val offeredCLTV = CltvExpiryDelta(config.getInt("to-remote-delay-blocks"))
    require(maxToLocalCLTV <= Channel.MAX_TO_SELF_DELAY && offeredCLTV <= Channel.MAX_TO_SELF_DELAY, s"CLTV delay values too high, max is ${Channel.MAX_TO_SELF_DELAY}")

    val expiryDelta = CltvExpiryDelta(config.getInt("expiry-delta-blocks"))
    val fulfillSafetyBeforeTimeout = CltvExpiryDelta(config.getInt("fulfill-safety-before-timeout-blocks"))
    require(fulfillSafetyBeforeTimeout * 2 < expiryDelta, "fulfill-safety-before-timeout-blocks must be smaller than expiry-delta-blocks / 2 because it effectively reduces that delta; if you want to increase this value, you may want to increase expiry-delta-blocks as well")
    val minFinalExpiryDelta = CltvExpiryDelta(config.getInt("min-final-expiry-delta-blocks"))
    require(minFinalExpiryDelta > fulfillSafetyBeforeTimeout, "min-final-expiry-delta-blocks must be strictly greater than fulfill-safety-before-timeout-blocks; otherwise it may lead to undesired channel closure")

    val nodeAlias = config.getString("node-alias")
    require(nodeAlias.getBytes("UTF-8").length <= 32, "invalid alias, too long (max allowed 32 bytes)")

    def validateFeatures(features: Features): Unit = {
      val featuresErr = Features.validateFeatureGraph(features)
      require(featuresErr.isEmpty, featuresErr.map(_.message))
      require(features.hasFeature(Features.VariableLengthOnion, Some(FeatureSupport.Mandatory)), s"${Features.VariableLengthOnion.rfcName} must be enabled and mandatory")
      require(features.hasFeature(Features.PaymentSecret, Some(FeatureSupport.Mandatory)), s"${Features.PaymentSecret.rfcName} must be enabled and mandatory")
      require(!features.hasFeature(Features.InitialRoutingSync), s"${Features.InitialRoutingSync.rfcName} is not supported anymore, use ${Features.ChannelRangeQueries.rfcName} instead")
    }

    val pluginMessageParams = pluginParams.collect { case p: CustomFeaturePlugin => p }
    val features = Features.fromConfiguration(config)
    validateFeatures(features)

    require(pluginMessageParams.forall(_.feature.mandatory > 128), "Plugin mandatory feature bit is too low, must be > 128")
    require(pluginMessageParams.forall(_.feature.mandatory % 2 == 0), "Plugin mandatory feature bit is odd, must be even")
    require(pluginMessageParams.flatMap(_.messageTags).forall(_ > 32768), "Plugin messages tags must be > 32768")
    val pluginFeatureSet = pluginMessageParams.map(_.feature.mandatory).toSet
    require(Features.knownFeatures.map(_.mandatory).intersect(pluginFeatureSet).isEmpty, "Plugin feature bit overlaps with known feature bit")
    require(pluginFeatureSet.size == pluginMessageParams.size, "Duplicate plugin feature bits found")

    val coreAndPluginFeatures = features.copy(unknown = features.unknown ++ pluginMessageParams.map(_.pluginFeature))

    val overrideFeatures: Map[PublicKey, Features] = config.getConfigList("override-features").asScala.map { e =>
      val p = PublicKey(ByteVector.fromValidHex(e.getString("nodeid")))
      val f = Features.fromConfiguration(e)
      validateFeatures(f)
      p -> f.copy(unknown = f.unknown ++ pluginMessageParams.map(_.pluginFeature))
    }.toMap

    val syncWhitelist: Set[PublicKey] = config.getStringList("sync-whitelist").asScala.map(s => PublicKey(ByteVector.fromValidHex(s))).toSet

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
      .asScala
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
      nodeKeyManager = nodeKeyManager,
      channelKeyManager = channelKeyManager,
      instanceId = instanceId,
      blockCount = blockCount,
      alias = nodeAlias,
      color = Color(color(0), color(1), color(2)),
      publicAddresses = addresses,
      features = coreAndPluginFeatures,
      pluginParams = pluginParams,
      overrideFeatures = overrideFeatures,
      syncWhitelist = syncWhitelist,
      dustLimit = dustLimitSatoshis,
      maxRemoteDustLimit = Satoshi(config.getLong("max-remote-dust-limit-satoshis")),
      onChainFeeConf = OnChainFeeConf(
        feeTargets = feeTargets,
        feeEstimator = feeEstimator,
        closeOnOfflineMismatch = config.getBoolean("on-chain-fees.close-on-offline-feerate-mismatch"),
        updateFeeMinDiffRatio = config.getDouble("on-chain-fees.update-fee-min-diff-ratio"),
        defaultFeerateTolerance = FeerateTolerance(
          config.getDouble("on-chain-fees.feerate-tolerance.ratio-low"),
          config.getDouble("on-chain-fees.feerate-tolerance.ratio-high"),
          FeeratePerKw(FeeratePerByte(Satoshi(config.getLong("on-chain-fees.feerate-tolerance.anchor-output-max-commit-feerate"))))
        ),
        perNodeFeerateTolerance = config.getConfigList("on-chain-fees.override-feerate-tolerance").asScala.map { e =>
          val nodeId = PublicKey(ByteVector.fromValidHex(e.getString("nodeid")))
          val tolerance = FeerateTolerance(
            e.getDouble("feerate-tolerance.ratio-low"),
            e.getDouble("feerate-tolerance.ratio-high"),
            FeeratePerKw(FeeratePerByte(Satoshi(e.getLong("feerate-tolerance.anchor-output-max-commit-feerate"))))
          )
          nodeId -> tolerance
        }.toMap
      ),
      maxHtlcValueInFlightMsat = UInt64(config.getLong("max-htlc-value-in-flight-msat")),
      maxAcceptedHtlcs = maxAcceptedHtlcs,
      expiryDelta = expiryDelta,
      fulfillSafetyBeforeTimeout = fulfillSafetyBeforeTimeout,
      minFinalExpiryDelta = minFinalExpiryDelta,
      htlcMinimum = htlcMinimum,
      toRemoteDelay = CltvExpiryDelta(config.getInt("to-remote-delay-blocks")),
      maxToLocalDelay = CltvExpiryDelta(config.getInt("max-to-local-delay-blocks")),
      minDepthBlocks = config.getInt("mindepth-blocks"),
      feeBase = feeBase,
      feeProportionalMillionth = config.getInt("fee-proportional-millionths"),
      reserveToFundingRatio = config.getDouble("reserve-to-funding-ratio"),
      maxReserveToFundingRatio = config.getDouble("max-reserve-to-funding-ratio"),
      db = database,
      revocationTimeout = FiniteDuration(config.getDuration("revocation-timeout").getSeconds, TimeUnit.SECONDS),
      autoReconnect = config.getBoolean("auto-reconnect"),
      initialRandomReconnectDelay = FiniteDuration(config.getDuration("initial-random-reconnect-delay").getSeconds, TimeUnit.SECONDS),
      maxReconnectInterval = FiniteDuration(config.getDuration("max-reconnect-interval").getSeconds, TimeUnit.SECONDS),
      chainHash = chainHash,
      channelFlags = config.getInt("channel-flags").toByte,
      watchSpentWindow = watchSpentWindow,
      paymentRequestExpiry = FiniteDuration(config.getDuration("payment-request-expiry").getSeconds, TimeUnit.SECONDS),
      multiPartPaymentExpiry = FiniteDuration(config.getDuration("multi-part-payment-expiry").getSeconds, TimeUnit.SECONDS),
      minFundingSatoshis = Satoshi(config.getLong("min-funding-satoshis")),
      maxFundingSatoshis = Satoshi(config.getLong("max-funding-satoshis")),
      peerConnectionConf = PeerConnection.Conf(
        authTimeout = FiniteDuration(config.getDuration("peer-connection.auth-timeout").getSeconds, TimeUnit.SECONDS),
        initTimeout = FiniteDuration(config.getDuration("peer-connection.init-timeout").getSeconds, TimeUnit.SECONDS),
        pingInterval = FiniteDuration(config.getDuration("peer-connection.ping-interval").getSeconds, TimeUnit.SECONDS),
        pingTimeout = FiniteDuration(config.getDuration("peer-connection.ping-timeout").getSeconds, TimeUnit.SECONDS),
        pingDisconnect = config.getBoolean("peer-connection.ping-disconnect"),
        maxRebroadcastDelay = FiniteDuration(config.getDuration("router.broadcast-interval").getSeconds, TimeUnit.SECONDS) // it makes sense to not delay rebroadcast by more than the rebroadcast period
      ),
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
        searchRatioBias = config.getDouble("router.path-finding.ratio-bias"),
        searchRatioCltv = config.getDouble("router.path-finding.ratio-cltv"),
        searchRatioChannelAge = config.getDouble("router.path-finding.ratio-channel-age"),
        searchRatioChannelCapacity = config.getDouble("router.path-finding.ratio-channel-capacity"),
        searchHopCostBase = MilliSatoshi(config.getLong("router.path-finding.hop-cost-base-msat")),
        searchHopCostMillionths = config.getLong("router.path-finding.hop-cost-millionths"),
        mppMinPartAmount = Satoshi(config.getLong("router.path-finding.mpp.min-amount-satoshis")).toMilliSatoshi,
        mppMaxParts = config.getInt("router.path-finding.mpp.max-parts")
      ),
      socksProxy_opt = socksProxy_opt,
      maxPaymentAttempts = config.getInt("max-payment-attempts"),
      enableTrampolinePayment = config.getBoolean("trampoline-payments-enable")
    )
  }
}
