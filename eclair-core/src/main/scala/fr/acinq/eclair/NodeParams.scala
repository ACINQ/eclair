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
import fr.acinq.bitcoin.scalacompat.Crypto.PublicKey
import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Crypto, Satoshi}
import fr.acinq.eclair.Setup.Seeds
import fr.acinq.eclair.blockchain.fee._
import fr.acinq.eclair.channel.ChannelFlags
import fr.acinq.eclair.channel.fsm.Channel
import fr.acinq.eclair.channel.fsm.Channel.{ChannelConf, UnhandledExceptionStrategy}
import fr.acinq.eclair.crypto.Noise.KeyPair
import fr.acinq.eclair.crypto.keymanager.{ChannelKeyManager, NodeKeyManager}
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.MessageRelay.{NoRelay, RelayAll, RelayChannelsOnly, RelayPolicy}
import fr.acinq.eclair.io.PeerConnection
import fr.acinq.eclair.message.OnionMessages.OnionMessageConfig
import fr.acinq.eclair.payment.relay.Relayer.{AsyncPaymentsParams, RelayFees, RelayParams}
import fr.acinq.eclair.router.Announcements.AddressException
import fr.acinq.eclair.router.Graph.{HeuristicsConstants, WeightRatios}
import fr.acinq.eclair.router.PathFindingExperimentConf
import fr.acinq.eclair.router.Router.{MultiPartParams, PathFindingConf, RouterConf, SearchBoundaries}
import fr.acinq.eclair.tor.Socks5ProxyParams
import fr.acinq.eclair.wire.protocol._
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
                      private val blockHeight: AtomicLong,
                      alias: String,
                      color: Color,
                      publicAddresses: List[NodeAddress],
                      torAddress_opt: Option[NodeAddress],
                      features: Features[Feature],
                      private val overrideInitFeatures: Map[PublicKey, Features[InitFeature]],
                      syncWhitelist: Set[PublicKey],
                      pluginParams: Seq[PluginParams],
                      channelConf: ChannelConf,
                      onChainFeeConf: OnChainFeeConf,
                      relayParams: RelayParams,
                      db: Databases,
                      autoReconnect: Boolean,
                      initialRandomReconnectDelay: FiniteDuration,
                      maxReconnectInterval: FiniteDuration,
                      chainHash: ByteVector32,
                      invoiceExpiry: FiniteDuration,
                      multiPartPaymentExpiry: FiniteDuration,
                      peerConnectionConf: PeerConnection.Conf,
                      routerConf: RouterConf,
                      socksProxy_opt: Option[Socks5ProxyParams],
                      maxPaymentAttempts: Int,
                      paymentFinalExpiry: PaymentFinalExpiryConf,
                      enableTrampolinePayment: Boolean,
                      balanceCheckInterval: FiniteDuration,
                      blockchainWatchdogThreshold: Int,
                      blockchainWatchdogSources: Seq[String],
                      onionMessageConfig: OnionMessageConfig,
                      purgeInvoicesInterval: Option[FiniteDuration]) {
  val privateKey: Crypto.PrivateKey = nodeKeyManager.nodeKey.privateKey

  val nodeId: PublicKey = nodeKeyManager.nodeId

  val keyPair: KeyPair = KeyPair(nodeId.value, privateKey.value)

  val pluginMessageTags: Set[Int] = pluginParams.collect { case p: CustomFeaturePlugin => p.messageTags }.toSet.flatten

  val pluginOpenChannelInterceptor: Option[InterceptOpenChannelPlugin] = pluginParams.collectFirst { case p: InterceptOpenChannelPlugin => p }

  def currentBlockHeight: BlockHeight = BlockHeight(blockHeight.get)

  /** Returns the features that should be used in our init message with the given peer. */
  def initFeaturesFor(nodeId: PublicKey): Features[InitFeature] = overrideInitFeatures.getOrElse(nodeId, features).initFeatures()
}

case class PaymentFinalExpiryConf(min: CltvExpiryDelta, max: CltvExpiryDelta) {
  require(min.toInt >= 0, "cltv-expiry-delta must be positive")
  require(min <= max, "maximum cltv-expiry-delta cannot be smaller than minimum-cltv-expiry-delta")

  /**
   * When sending a payment, if the cltv expiry used for the final node is very close to the current block height, it
   * lets intermediate nodes figure out their position in the route. To protect against this, a random delta is added
   * to the current block height, which makes it look like there are more hops after the final node.
   */
  def computeFinalExpiry(currentBlockHeight: BlockHeight, minFinalExpiryDelta: CltvExpiryDelta): CltvExpiry = {
    val additionalDelta = if (min < max) {
      min + (randomLong() % (max - min + 1).toInt).toInt.abs
    } else {
      max
    }
    (minFinalExpiryDelta + additionalDelta).toCltvExpiry(currentBlockHeight)
  }
}

object NodeParams extends Logging {

  /**
   * Order of precedence for the configuration parameters:
   * 1) Java environment variables (-D...)
   * 2) Configuration file eclair.conf
   * 3) Default values in reference.conf
   */
  def loadConfiguration(datadir: File): Config =
    ConfigFactory.systemProperties()
      .withFallback(ConfigFactory.parseFile(new File(datadir, "eclair.conf")))
      .withFallback(ConfigFactory.load())
      .resolve()

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
    "signet" -> Block.SignetGenesisBlock.hash,
    "mainnet" -> Block.LivenetGenesisBlock.hash
  )

  def hashFromChain(chain: String): ByteVector32 = chain2Hash.getOrElse(chain, throw new RuntimeException(s"invalid chain '$chain'"))

  def chainFromHash(chainHash: ByteVector32): String = chain2Hash.map(_.swap).getOrElse(chainHash, throw new RuntimeException(s"invalid chainHash '$chainHash'"))

  def parseSocks5ProxyParams(config: Config): Option[Socks5ProxyParams] = {
    if (config.getBoolean("socks5.enabled")) {
      Some(Socks5ProxyParams(
        address = new InetSocketAddress(config.getString("socks5.host"), config.getInt("socks5.port")),
        credentials_opt = None,
        randomizeCredentials = config.getBoolean("socks5.randomize-credentials"),
        useForIPv4 = config.getBoolean("socks5.use-for-ipv4"),
        useForIPv6 = config.getBoolean("socks5.use-for-ipv6"),
        useForTor = config.getBoolean("socks5.use-for-tor"),
        useForWatchdogs = config.getBoolean("socks5.use-for-watchdogs"),
        useForDnsHostnames = config.getBoolean("socks5.use-for-dnshostnames"),
      ))
    } else {
      None
    }
  }

  def makeNodeParams(config: Config, instanceId: UUID, nodeKeyManager: NodeKeyManager, channelKeyManager: ChannelKeyManager,
                     torAddress_opt: Option[NodeAddress], database: Databases, blockHeight: AtomicLong, feeEstimator: FeeEstimator,
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
      "feerate-provider-timeout" -> "on-chain-fees.provider-timeout",
      // v0.6.1
      "enable-db-backup" -> "file-backup.enabled",
      "backup-notify-script" -> "file-backup.notify-script",
      // v0.6.2
      "fee-base-msat" -> "relay.fees.public-channels.fee-base-msat",
      "fee-proportional-millionths" -> "relay.fees.public-channels.fee-proportional-millionths",
      "router.randomize-route-selection" -> "router.path-finding.default.randomize-route-selection",
      "router.path-finding.max-route-length" -> "router.path-finding.default.boundaries.max-route-length",
      "router.path-finding.max-cltv" -> "router.path-finding.default.boundaries.max-cltv",
      "router.path-finding.fee-threshold-sat" -> "router.path-finding.default.boundaries.max-fee-flat-sat",
      "router.path-finding.max-fee-pct" -> "router.path-finding.default.boundaries.max-fee-proportional-percent",
      "router.path-finding.ratio-base" -> "router.path-finding.default.ratios.base",
      "router.path-finding.ratio-cltv" -> "router.path-finding.default.ratios.cltv",
      "router.path-finding.ratio-channel-age" -> "router.path-finding.default.ratios.channel-age",
      "router.path-finding.ratio-channel-capacity" -> "router.path-finding.default.ratios.channel-capacity",
      "router.path-finding.hop-cost-base-msat" -> "router.path-finding.default.hop-cost.fee-base-msat",
      "router.path-finding.hop-cost-millionths" -> "router.path-finding.default.hop-cost.fee-proportional-millionths",
      // v0.6.3
      "channel-flags" -> "channel.channel-flags",
      "dust-limit-satoshis" -> "channel.dust-limit-satoshis",
      "max-remote-dust-limit-satoshis" -> "channel.max-remote-dust-limit-satoshis",
      "htlc-minimum-msat" -> "channel.htlc-minimum-msat",
      "max-htlc-value-in-flight-msat" -> "channel.max-htlc-value-in-flight-msat",
      "max-accepted-htlcs" -> "channel.max-accepted-htlcs",
      "reserve-to-funding-ratio" -> "channel.reserve-to-funding-ratio",
      "max-reserve-to-funding-ratio" -> "channel.max-reserve-to-funding-ratio",
      "min-funding-satoshis" -> "channel.min-funding-satoshis",
      "max-funding-satoshis" -> "channel.max-funding-satoshis",
      "to-remote-delay-blocks" -> "channel.to-remote-delay-blocks",
      "max-to-local-delay-blocks" -> "channel.max-to-local-delay-blocks",
      "mindepth-blocks" -> "channel.mindepth-blocks",
      "expiry-delta-blocks" -> "channel.expiry-delta-blocks",
      "fulfill-safety-before-timeout-blocks" -> "channel.fulfill-safety-before-timeout-blocks",
      "min-final-expiry-delta-blocks" -> "channel.min-final-expiry-delta-blocks",
      "max-block-processing-delay" -> "channel.max-block-processing-delay",
      "max-tx-publish-retry-delay" -> "channel.max-tx-publish-retry-delay",
      "unhandled-exception-strategy" -> "channel.unhandled-exception-strategy",
      "revocation-timeout" -> "channel.revocation-timeout",
      "watch-spent-window" -> "router.watch-spent-window",
      // v0.7.1
      "payment-request-expiry" -> "invoice-expiry",
      "override-features" -> "override-init-features",
      "channel.min-funding-satoshis" -> "channel.min-public-funding-satoshis, channel.min-private-funding-satoshis",
      "bitcoind.batch-requests" -> "bitcoind.batch-watcher-requests"
    )
    deprecatedKeyPaths.foreach {
      case (old, new_) => require(!config.hasPath(old), s"configuration key '$old' has been replaced by '$new_'")
    }

    // since v0.4.1 features cannot be a byte vector (hex string)
    val isFeatureByteVector = config.getValue("features").valueType() == ConfigValueType.STRING
    require(!isFeatureByteVector, "configuration key 'features' have moved from bytevector to human readable (ex: 'feature-name' = optional/mandatory)")

    val chain = config.getString("chain")
    val chainHash = hashFromChain(chain)

    val channelFlags = ChannelFlags(announceChannel = config.getBoolean("channel.channel-flags.announce-channel"))

    val color = ByteVector.fromValidHex(config.getString("node-color"))
    require(color.size == 3, "color should be a 3-bytes hex buffer")

    val watchSpentWindow = FiniteDuration(config.getDuration("router.watch-spent-window").getSeconds, TimeUnit.SECONDS)
    require(watchSpentWindow > 0.seconds, "router.watch-spent-window must be strictly greater than 0")

    val dustLimitSatoshis = Satoshi(config.getLong("channel.dust-limit-satoshis"))
    if (chainHash == Block.LivenetGenesisBlock.hash) {
      require(dustLimitSatoshis >= Channel.MIN_DUST_LIMIT, s"dust limit must be greater than ${Channel.MIN_DUST_LIMIT}")
    }

    val htlcMinimum = MilliSatoshi(config.getInt("channel.htlc-minimum-msat"))
    require(htlcMinimum > 0.msat, "channel.htlc-minimum-msat must be strictly greater than 0")

    val maxAcceptedHtlcs = config.getInt("channel.max-accepted-htlcs")
    require(maxAcceptedHtlcs <= Channel.MAX_ACCEPTED_HTLCS, s"channel.max-accepted-htlcs must be lower than ${Channel.MAX_ACCEPTED_HTLCS}")

    val maxToLocalCLTV = CltvExpiryDelta(config.getInt("channel.max-to-local-delay-blocks"))
    val offeredCLTV = CltvExpiryDelta(config.getInt("channel.to-remote-delay-blocks"))
    require(maxToLocalCLTV <= Channel.MAX_TO_SELF_DELAY && offeredCLTV <= Channel.MAX_TO_SELF_DELAY, s"CLTV delay values too high, max is ${Channel.MAX_TO_SELF_DELAY}")

    val expiryDelta = CltvExpiryDelta(config.getInt("channel.expiry-delta-blocks"))
    val fulfillSafetyBeforeTimeout = CltvExpiryDelta(config.getInt("channel.fulfill-safety-before-timeout-blocks"))
    require(fulfillSafetyBeforeTimeout * 2 < expiryDelta, "channel.fulfill-safety-before-timeout-blocks must be smaller than channel.expiry-delta-blocks / 2 because it effectively reduces that delta; if you want to increase this value, you may want to increase expiry-delta-blocks as well")
    val minFinalExpiryDelta = CltvExpiryDelta(config.getInt("channel.min-final-expiry-delta-blocks"))
    require(minFinalExpiryDelta > fulfillSafetyBeforeTimeout, "channel.min-final-expiry-delta-blocks must be strictly greater than channel.fulfill-safety-before-timeout-blocks; otherwise it may lead to undesired channel closure")

    val nodeAlias = config.getString("node-alias")
    require(nodeAlias.getBytes("UTF-8").length <= 32, "invalid alias, too long (max allowed 32 bytes)")

    def validateFeatures(features: Features[Feature]): Unit = {
      val featuresErr = Features.validateFeatureGraph(features)
      require(featuresErr.isEmpty, featuresErr.map(_.message))
      require(features.hasFeature(Features.VariableLengthOnion, Some(FeatureSupport.Mandatory)), s"${Features.VariableLengthOnion.rfcName} must be enabled and mandatory")
      require(features.hasFeature(Features.PaymentSecret, Some(FeatureSupport.Mandatory)), s"${Features.PaymentSecret.rfcName} must be enabled and mandatory")
      require(!features.hasFeature(Features.InitialRoutingSync), s"${Features.InitialRoutingSync.rfcName} is not supported anymore, use ${Features.ChannelRangeQueries.rfcName} instead")
      require(features.hasFeature(Features.ChannelType), s"${Features.ChannelType.rfcName} must be enabled")
    }

    def validateAddresses(addresses: List[NodeAddress]): Unit = {
      val addressesError = if (addresses.count(_.isInstanceOf[DnsHostname]) > 1) {
        Some(AddressException(s"Invalid server.public-ip addresses: can not have more than one DNS host name."))
      } else {
        addresses.collectFirst {
          case address if address.isInstanceOf[Tor2] => AddressException(s"invalid server.public-ip address `$address`: Tor v2 is deprecated.")
          case address if address.port == 0 && !address.isInstanceOf[Tor3] => AddressException(s"invalid server.public-ip address `$address`: A non-Tor address can not use port 0.")
        }
      }

      require(addressesError.isEmpty, addressesError.map(_.message))
    }

    val pluginMessageParams = pluginParams.collect { case p: CustomFeaturePlugin => p }
    val features = Features.fromConfiguration(config.getConfig("features"))
    validateFeatures(features)
    require(!features.hasFeature(Features.ZeroConf), s"${Features.ZeroConf.rfcName} cannot be enabled for all peers: you have to use override-init-features to enable it on a per-peer basis")

    require(pluginMessageParams.forall(_.feature.mandatory > 128), "Plugin mandatory feature bit is too low, must be > 128")
    require(pluginMessageParams.forall(_.feature.mandatory % 2 == 0), "Plugin mandatory feature bit is odd, must be even")
    require(pluginMessageParams.flatMap(_.messageTags).forall(_ > 32768), "Plugin messages tags must be > 32768")
    val pluginFeatureSet = pluginMessageParams.map(_.feature.mandatory).toSet
    require(Features.knownFeatures.map(_.mandatory).intersect(pluginFeatureSet).isEmpty, "Plugin feature bit overlaps with known feature bit")
    require(pluginFeatureSet.size == pluginMessageParams.size, "Duplicate plugin feature bits found")

    val interceptOpenChannelPlugins = pluginParams.collect { case p: InterceptOpenChannelPlugin => p }
    require(interceptOpenChannelPlugins.size <= 1, s"At most one plugin is allowed to intercept channel open messages, but multiple such plugins were registered: ${interceptOpenChannelPlugins.map(_.getClass.getSimpleName).mkString(", ")}. Disable conflicting plugins and restart eclair.")

    val coreAndPluginFeatures: Features[Feature] = features.copy(unknown = features.unknown ++ pluginMessageParams.map(_.pluginFeature))

    val overrideInitFeatures: Map[PublicKey, Features[InitFeature]] = config.getConfigList("override-init-features").asScala.map { e =>
      val p = PublicKey(ByteVector.fromValidHex(e.getString("nodeid")))
      val f = Features.fromConfiguration[InitFeature](e.getConfig("features"), Features.knownFeatures.collect { case f: InitFeature => f }, features.initFeatures())
      validateFeatures(f.unscoped())
      p -> (f.copy(unknown = f.unknown ++ pluginMessageParams.map(_.pluginFeature)): Features[InitFeature])
    }.toMap

    val syncWhitelist: Set[PublicKey] = config.getStringList("sync-whitelist").asScala.map(s => PublicKey(ByteVector.fromValidHex(s))).toSet

    val socksProxy_opt = parseSocks5ProxyParams(config)

    val publicTorAddress_opt = if (config.getBoolean("tor.publish-onion-address")) torAddress_opt else None

    val addresses = config.getStringList("server.public-ips")
      .asScala
      .toList
      .map(ip => NodeAddress.fromParts(ip, config.getInt("server.port")).get) ++ publicTorAddress_opt

    validateAddresses(addresses)

    val feeTargets = FeeTargets(
      fundingBlockTarget = config.getInt("on-chain-fees.target-blocks.funding"),
      commitmentBlockTarget = config.getInt("on-chain-fees.target-blocks.commitment"),
      commitmentWithoutHtlcsBlockTarget = config.getInt("on-chain-fees.target-blocks.commitment-without-htlcs"),
      mutualCloseBlockTarget = config.getInt("on-chain-fees.target-blocks.mutual-close"),
      claimMainBlockTarget = config.getInt("on-chain-fees.target-blocks.claim-main"),
      safeUtxosThreshold = config.getInt("on-chain-fees.target-blocks.safe-utxos-threshold"),
    )

    def getRelayFees(relayFeesConfig: Config): RelayFees = {
      val feeBase = MilliSatoshi(relayFeesConfig.getInt("fee-base-msat"))
      // fee base is in msat but is encoded on 32 bits and not 64 in the BOLTs, which is why it has
      // to be below 0x100000000 msat which is about 42 mbtc
      require(feeBase <= MilliSatoshi(0xFFFFFFFFL), "fee-base-msat must be below 42 mbtc")
      RelayFees(feeBase, relayFeesConfig.getInt("fee-proportional-millionths"))
    }

    def getPathFindingConf(config: Config, name: String): PathFindingConf = PathFindingConf(
      randomize = config.getBoolean("randomize-route-selection"),
      boundaries = SearchBoundaries(
        maxRouteLength = config.getInt("boundaries.max-route-length"),
        maxCltv = CltvExpiryDelta(config.getInt("boundaries.max-cltv")),
        maxFeeFlat = Satoshi(config.getLong("boundaries.max-fee-flat-sat")).toMilliSatoshi,
        maxFeeProportional = config.getDouble("boundaries.max-fee-proportional-percent") / 100.0),
      heuristics = if (config.getBoolean("use-ratios")) {
        Left(WeightRatios(
          baseFactor = config.getDouble("ratios.base"),
          cltvDeltaFactor = config.getDouble("ratios.cltv"),
          ageFactor = config.getDouble("ratios.channel-age"),
          capacityFactor = config.getDouble("ratios.channel-capacity"),
          hopCost = getRelayFees(config.getConfig("hop-cost")),
        ))
      } else {
        Right(HeuristicsConstants(
          lockedFundsRisk = config.getDouble("locked-funds-risk"),
          failureCost = getRelayFees(config.getConfig("failure-cost")),
          hopCost = getRelayFees(config.getConfig("hop-cost")),
          useLogProbability = config.getBoolean("use-log-probability"),
        ))
      },
      mpp = MultiPartParams(
        Satoshi(config.getLong("mpp.min-amount-satoshis")).toMilliSatoshi,
        config.getInt("mpp.max-parts")),
      experimentName = name,
      experimentPercentage = config.getInt("percentage"))


    def getPathFindingExperimentConf(config: Config): PathFindingExperimentConf = {
      val experiments = config.root.asScala.keys.map(name => name -> getPathFindingConf(config.getConfig(name), name))
      PathFindingExperimentConf(experiments.toMap)
    }

    val unhandledExceptionStrategy = config.getString("channel.unhandled-exception-strategy") match {
      case "local-close" => UnhandledExceptionStrategy.LocalClose
      case "stop" => UnhandledExceptionStrategy.Stop
    }

    val onionMessageRelayPolicy: RelayPolicy = config.getString("onion-messages.relay-policy") match {
      case "no-relay" => NoRelay
      case "channels-only" => RelayChannelsOnly
      case "relay-all" => RelayAll
    }

    val purgeInvoicesInterval = if (config.getBoolean("purge-expired-invoices.enabled")) {
      Some(FiniteDuration(config.getDuration("purge-expired-invoices.interval").toMinutes, TimeUnit.MINUTES))
    } else {
      None
    }

    val asyncPaymentCancelSafetyBeforeTimeoutBlocks = CltvExpiryDelta(config.getInt("relay.async-payments.cancel-safety-before-timeout-blocks"))
    require(asyncPaymentCancelSafetyBeforeTimeoutBlocks >= expiryDelta, "relay.async-payments.cancel-safety-before-timeout-blocks must not be less than channel.expiry-delta-blocks; this may lead to undesired channel closure")

    val asyncPaymentHoldTimeoutBlocks = config.getInt("relay.async-payments.hold-timeout-blocks")
    require(asyncPaymentHoldTimeoutBlocks >= (asyncPaymentCancelSafetyBeforeTimeoutBlocks + expiryDelta).toInt, "relay.async-payments.hold-timeout-blocks must not be less than relay.async-payments.cancel-safety-before-timeout-blocks + channel.expiry-delta-blocks; otherwise it will have no effect")

    val channelOpenerWhitelist: Set[PublicKey] = config.getStringList("channel.channel-open-limits.channel-opener-whitelist").asScala.map(s => PublicKey(ByteVector.fromValidHex(s))).toSet
    val maxPendingChannelsPerPeer = config.getInt("channel.channel-open-limits.max-pending-channels-per-peer")
    val maxTotalPendingChannelsPrivateNodes = config.getInt("channel.channel-open-limits.max-total-pending-channels-private-nodes")

    NodeParams(
      nodeKeyManager = nodeKeyManager,
      channelKeyManager = channelKeyManager,
      instanceId = instanceId,
      blockHeight = blockHeight,
      alias = nodeAlias,
      color = Color(color(0), color(1), color(2)),
      publicAddresses = addresses,
      torAddress_opt = torAddress_opt,
      features = coreAndPluginFeatures,
      pluginParams = pluginParams,
      overrideInitFeatures = overrideInitFeatures,
      syncWhitelist = syncWhitelist,
      channelConf = ChannelConf(
        channelFlags = channelFlags,
        dustLimit = dustLimitSatoshis,
        maxRemoteDustLimit = Satoshi(config.getLong("channel.max-remote-dust-limit-satoshis")),
        htlcMinimum = htlcMinimum,
        maxHtlcValueInFlightMsat = MilliSatoshi(config.getLong("channel.max-htlc-value-in-flight-msat")),
        maxHtlcValueInFlightPercent = config.getInt("channel.max-htlc-value-in-flight-percent"),
        maxAcceptedHtlcs = maxAcceptedHtlcs,
        reserveToFundingRatio = config.getDouble("channel.reserve-to-funding-ratio"),
        maxReserveToFundingRatio = config.getDouble("channel.max-reserve-to-funding-ratio"),
        minFundingPublicSatoshis = Satoshi(config.getLong("channel.min-public-funding-satoshis")),
        minFundingPrivateSatoshis = Satoshi(config.getLong("channel.min-private-funding-satoshis")),
        maxFundingSatoshis = Satoshi(config.getLong("channel.max-funding-satoshis")),
        toRemoteDelay = offeredCLTV,
        maxToLocalDelay = maxToLocalCLTV,
        minDepthBlocks = config.getInt("channel.mindepth-blocks"),
        expiryDelta = expiryDelta,
        fulfillSafetyBeforeTimeout = fulfillSafetyBeforeTimeout,
        minFinalExpiryDelta = minFinalExpiryDelta,
        maxBlockProcessingDelay = FiniteDuration(config.getDuration("channel.max-block-processing-delay").getSeconds, TimeUnit.SECONDS),
        maxTxPublishRetryDelay = FiniteDuration(config.getDuration("channel.max-tx-publish-retry-delay").getSeconds, TimeUnit.SECONDS),
        unhandledExceptionStrategy = unhandledExceptionStrategy,
        revocationTimeout = FiniteDuration(config.getDuration("channel.revocation-timeout").getSeconds, TimeUnit.SECONDS),
        requireConfirmedInputsForDualFunding = config.getBoolean("channel.require-confirmed-inputs-for-dual-funding"),
        channelOpenerWhitelist = channelOpenerWhitelist,
        maxPendingChannelsPerPeer = maxPendingChannelsPerPeer,
        maxTotalPendingChannelsPrivateNodes = maxTotalPendingChannelsPrivateNodes,
        remoteRbfLimits = Channel.RemoteRbfLimits(config.getInt("channel.funding.remote-rbf-limits.max-attempts"), config.getInt("channel.funding.remote-rbf-limits.attempt-delta-blocks"))
      ),
      onChainFeeConf = OnChainFeeConf(
        feeTargets = feeTargets,
        feeEstimator = feeEstimator,
        spendAnchorWithoutHtlcs = config.getBoolean("on-chain-fees.spend-anchor-without-htlcs"),
        closeOnOfflineMismatch = config.getBoolean("on-chain-fees.close-on-offline-feerate-mismatch"),
        updateFeeMinDiffRatio = config.getDouble("on-chain-fees.update-fee-min-diff-ratio"),
        defaultFeerateTolerance = FeerateTolerance(
          config.getDouble("on-chain-fees.feerate-tolerance.ratio-low"),
          config.getDouble("on-chain-fees.feerate-tolerance.ratio-high"),
          FeeratePerKw(FeeratePerByte(Satoshi(config.getLong("on-chain-fees.feerate-tolerance.anchor-output-max-commit-feerate")))),
          DustTolerance(
            Satoshi(config.getLong("on-chain-fees.feerate-tolerance.dust-tolerance.max-exposure-satoshis")),
            config.getBoolean("on-chain-fees.feerate-tolerance.dust-tolerance.close-on-update-fee-overflow")
          )
        ),
        perNodeFeerateTolerance = config.getConfigList("on-chain-fees.override-feerate-tolerance").asScala.map { e =>
          val nodeId = PublicKey(ByteVector.fromValidHex(e.getString("nodeid")))
          val tolerance = FeerateTolerance(
            e.getDouble("feerate-tolerance.ratio-low"),
            e.getDouble("feerate-tolerance.ratio-high"),
            FeeratePerKw(FeeratePerByte(Satoshi(e.getLong("feerate-tolerance.anchor-output-max-commit-feerate")))),
            DustTolerance(
              Satoshi(e.getLong("feerate-tolerance.dust-tolerance.max-exposure-satoshis")),
              e.getBoolean("feerate-tolerance.dust-tolerance.close-on-update-fee-overflow")
            )
          )
          nodeId -> tolerance
        }.toMap
      ),
      relayParams = RelayParams(
        publicChannelFees = getRelayFees(config.getConfig("relay.fees.public-channels")),
        privateChannelFees = getRelayFees(config.getConfig("relay.fees.private-channels")),
        minTrampolineFees = getRelayFees(config.getConfig("relay.fees.min-trampoline")),
        enforcementDelay = FiniteDuration(config.getDuration("relay.fees.enforcement-delay").getSeconds, TimeUnit.SECONDS),
        asyncPaymentsParams = AsyncPaymentsParams(asyncPaymentHoldTimeoutBlocks, asyncPaymentCancelSafetyBeforeTimeoutBlocks)
      ),
      db = database,
      autoReconnect = config.getBoolean("auto-reconnect"),
      initialRandomReconnectDelay = FiniteDuration(config.getDuration("initial-random-reconnect-delay").getSeconds, TimeUnit.SECONDS),
      maxReconnectInterval = FiniteDuration(config.getDuration("max-reconnect-interval").getSeconds, TimeUnit.SECONDS),
      chainHash = chainHash,
      invoiceExpiry = FiniteDuration(config.getDuration("invoice-expiry").getSeconds, TimeUnit.SECONDS),
      multiPartPaymentExpiry = FiniteDuration(config.getDuration("multi-part-payment-expiry").getSeconds, TimeUnit.SECONDS),
      peerConnectionConf = PeerConnection.Conf(
        authTimeout = FiniteDuration(config.getDuration("peer-connection.auth-timeout").getSeconds, TimeUnit.SECONDS),
        initTimeout = FiniteDuration(config.getDuration("peer-connection.init-timeout").getSeconds, TimeUnit.SECONDS),
        pingInterval = FiniteDuration(config.getDuration("peer-connection.ping-interval").getSeconds, TimeUnit.SECONDS),
        pingTimeout = FiniteDuration(config.getDuration("peer-connection.ping-timeout").getSeconds, TimeUnit.SECONDS),
        pingDisconnect = config.getBoolean("peer-connection.ping-disconnect"),
        maxRebroadcastDelay = FiniteDuration(config.getDuration("router.broadcast-interval").getSeconds, TimeUnit.SECONDS), // it makes sense to not delay rebroadcast by more than the rebroadcast period
        killIdleDelay = FiniteDuration(config.getDuration("onion-messages.kill-transient-connection-after").getSeconds, TimeUnit.SECONDS),
        maxOnionMessagesPerSecond = config.getInt("onion-messages.max-per-peer-per-second"),
        sendRemoteAddressInit = config.getBoolean("peer-connection.send-remote-address-init"),
      ),
      routerConf = RouterConf(
        watchSpentWindow = watchSpentWindow,
        channelExcludeDuration = FiniteDuration(config.getDuration("router.channel-exclude-duration").getSeconds, TimeUnit.SECONDS),
        routerBroadcastInterval = FiniteDuration(config.getDuration("router.broadcast-interval").getSeconds, TimeUnit.SECONDS),
        requestNodeAnnouncements = config.getBoolean("router.sync.request-node-announcements"),
        encodingType = EncodingType.UNCOMPRESSED,
        channelRangeChunkSize = config.getInt("router.sync.channel-range-chunk-size"),
        channelQueryChunkSize = config.getInt("router.sync.channel-query-chunk-size"),
        pathFindingExperimentConf = getPathFindingExperimentConf(config.getConfig("router.path-finding.experiments")),
        balanceEstimateHalfLife = FiniteDuration(config.getDuration("router.balance-estimate-half-life").getSeconds, TimeUnit.SECONDS),
      ),
      socksProxy_opt = socksProxy_opt,
      maxPaymentAttempts = config.getInt("max-payment-attempts"),
      paymentFinalExpiry = PaymentFinalExpiryConf(CltvExpiryDelta(config.getInt("send.recipient-final-expiry.min-delta")), CltvExpiryDelta(config.getInt("send.recipient-final-expiry.max-delta"))),
      enableTrampolinePayment = config.getBoolean("trampoline-payments-enable"),
      balanceCheckInterval = FiniteDuration(config.getDuration("balance-check-interval").getSeconds, TimeUnit.SECONDS),
      blockchainWatchdogThreshold = config.getInt("blockchain-watchdog.missing-blocks-threshold"),
      blockchainWatchdogSources = config.getStringList("blockchain-watchdog.sources").asScala.toSeq,
      onionMessageConfig = OnionMessageConfig(
        relayPolicy = onionMessageRelayPolicy,
        timeout = FiniteDuration(config.getDuration("onion-messages.reply-timeout").getSeconds, TimeUnit.SECONDS),
        maxAttempts = config.getInt("onion-messages.max-attempts"),
      ),
      purgeInvoicesInterval = purgeInvoicesInterval
    )
  }
}
