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

import fr.acinq.bitcoin.scalacompat.{Block, ByteVector32, Satoshi, SatoshiLong}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.blockchain.fee._
import fr.acinq.eclair.channel.fsm.Channel.{ChannelConf, RemoteRbfLimits, UnhandledExceptionStrategy}
import fr.acinq.eclair.channel.{ChannelFlags, LocalParams}
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import fr.acinq.eclair.db.RevokedHtlcInfoCleaner
import fr.acinq.eclair.io.MessageRelay.RelayAll
import fr.acinq.eclair.io.{OpenChannelInterceptor, PeerConnection}
import fr.acinq.eclair.message.OnionMessages.OnionMessageConfig
import fr.acinq.eclair.payment.relay.Relayer.{AsyncPaymentsParams, RelayFees, RelayParams}
import fr.acinq.eclair.router.Graph.{MessagePath, WeightRatios}
import fr.acinq.eclair.router.PathFindingExperimentConf
import fr.acinq.eclair.router.Router._
import fr.acinq.eclair.wire.protocol._
import org.scalatest.Tag
import scodec.bits.{ByteVector, HexStringSyntax}

import java.util.UUID
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.duration._

/**
 * Created by PM on 26/04/2016.
 */
object TestConstants {

  val defaultBlockHeight = 400_000
  val fundingSatoshis: Satoshi = 1_000_000 sat
  val nonInitiatorFundingSatoshis: Satoshi = 500_000 sat
  val initiatorPushAmount: MilliSatoshi = 200_000_000L msat
  val nonInitiatorPushAmount: MilliSatoshi = 100_000_000L msat
  val feeratePerKw: FeeratePerKw = FeeratePerKw(10_000 sat)
  val anchorOutputsFeeratePerKw: FeeratePerKw = FeeratePerKw(2_500 sat)
  val emptyOnionPacket: OnionRoutingPacket = OnionRoutingPacket(0, ByteVector.fill(33)(0), ByteVector.fill(1300)(0), ByteVector32.Zeroes)
  val defaultLeaseDuration: Int = 4032 // ~1 month
  val defaultLiquidityRates: LiquidityAds.LeaseRate = LiquidityAds.LeaseRate(defaultLeaseDuration, 500, 100, 100 sat, 10, 200 msat)

  case object TestFeature extends Feature with InitFeature with NodeFeature {
    val rfcName = "test_feature"
    val mandatory = 50000
  }

  val pluginParams: CustomFeaturePlugin = new CustomFeaturePlugin {
    // @formatter:off
    override def messageTags: Set[Int] = Set(60003)
    override def feature: Feature = TestFeature
    override def name: String = "plugin for testing"
    // @formatter:on
  }

  private val blockchainWatchdogSources = Seq(
    "bitcoinheaders.net",
    "blockcypher.com",
    "blockstream.info",
    "mempool.space"
  )

  object Alice {
    val seed: ByteVector32 = ByteVector32(hex"b4acd47335b25ab7b84b8c020997b12018592bb4631b868762154d77fa8b93a3") // 02aaaa...
    val nodeKeyManager = new LocalNodeKeyManager(seed, Block.RegtestGenesisBlock.hash)
    val channelKeyManager = new LocalChannelKeyManager(seed, Block.RegtestGenesisBlock.hash)

    // This is a function, and not a val! When called will return a new NodeParams
    def nodeParams: NodeParams = NodeParams(
      nodeKeyManager,
      channelKeyManager,
      onChainKeyManager_opt = None,
      blockHeight = new AtomicLong(defaultBlockHeight),
      feerates = new AtomicReference(FeeratesPerKw.single(feeratePerKw)),
      alias = "alice",
      color = Color(1, 2, 3),
      publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil,
      torAddress_opt = None,
      features = Features(
        Map(
          DataLossProtect -> Optional,
          ChannelRangeQueries -> Optional,
          ChannelRangeQueriesExtended -> Optional,
          VariableLengthOnion -> Mandatory,
          PaymentSecret -> Mandatory,
          BasicMultiPartPayment -> Optional,
          Wumbo -> Optional,
          PaymentMetadata -> Optional,
          RouteBlinding -> Optional,
        ),
        unknown = Set(UnknownFeature(TestFeature.optional))
      ),
      pluginParams = List(pluginParams),
      overrideInitFeatures = Map.empty,
      syncWhitelist = Set.empty,
      channelConf = ChannelConf(
        dustLimit = 1100 sat,
        maxRemoteDustLimit = 1500 sat,
        maxHtlcValueInFlightMsat = 500_000_000 msat,
        maxHtlcValueInFlightPercent = 100,
        maxAcceptedHtlcs = 100,
        expiryDelta = CltvExpiryDelta(144),
        maxExpiryDelta = CltvExpiryDelta(2016),
        fulfillSafetyBeforeTimeout = CltvExpiryDelta(6),
        minFinalExpiryDelta = CltvExpiryDelta(18),
        maxRestartWatchDelay = 0 millis,
        maxBlockProcessingDelay = 10 millis,
        maxTxPublishRetryDelay = 10 millis,
        maxChannelSpentRescanBlocks = 144,
        htlcMinimum = 0 msat,
        minDepthBlocks = 3,
        toRemoteDelay = CltvExpiryDelta(144),
        maxToLocalDelay = CltvExpiryDelta(1000),
        reserveToFundingRatio = 0.01, // note: not used (overridden below)
        maxReserveToFundingRatio = 0.05,
        unhandledExceptionStrategy = UnhandledExceptionStrategy.LocalClose,
        revocationTimeout = 20 seconds,
        channelFlags = ChannelFlags.Public,
        minFundingPublicSatoshis = 1000 sat,
        minFundingPrivateSatoshis = 900 sat,
        requireConfirmedInputsForDualFunding = false,
        channelOpenerWhitelist = Set.empty,
        maxPendingChannelsPerPeer = 3,
        maxTotalPendingChannelsPrivateNodes = 99,
        remoteRbfLimits = RemoteRbfLimits(5, 0),
        quiescenceTimeout = 2 minutes,
        balanceThresholds = Nil,
        minTimeBetweenUpdates = 0 hours,
      ),
      onChainFeeConf = OnChainFeeConf(
        feeTargets = FeeTargets(funding = ConfirmationPriority.Medium, closing = ConfirmationPriority.Medium),
        safeUtxosThreshold = 0,
        spendAnchorWithoutHtlcs = true,
        anchorWithoutHtlcsMaxFee = 100_000.sat,
        closeOnOfflineMismatch = true,
        updateFeeMinDiffRatio = 0.1,
        defaultFeerateTolerance = FeerateTolerance(0.5, 8.0, anchorOutputsFeeratePerKw, DustTolerance(25_000 sat, closeOnUpdateFeeOverflow = true)),
        perNodeFeerateTolerance = Map.empty
      ),
      relayParams = RelayParams(
        publicChannelFees = RelayFees(
          feeBase = 546000 msat,
          feeProportionalMillionths = 10),
        privateChannelFees = RelayFees(
          feeBase = 547000 msat,
          feeProportionalMillionths = 20),
        minTrampolineFees = RelayFees(
          feeBase = 548000 msat,
          feeProportionalMillionths = 30),
        enforcementDelay = 10 minutes,
        asyncPaymentsParams = AsyncPaymentsParams(1008, CltvExpiryDelta(144))),
      db = TestDatabases.inMemoryDb(),
      autoReconnect = false,
      initialRandomReconnectDelay = 5 seconds,
      maxReconnectInterval = 1 hour,
      chainHash = Block.RegtestGenesisBlock.hash,
      invoiceExpiry = 1 hour,
      multiPartPaymentExpiry = 30 seconds,
      peerConnectionConf = PeerConnection.Conf(
        authTimeout = 10 seconds,
        initTimeout = 10 seconds,
        pingInterval = 30 seconds,
        pingTimeout = 10 seconds,
        pingDisconnect = true,
        maxRebroadcastDelay = 5 seconds,
        killIdleDelay = 1 seconds,
        maxOnionMessagesPerSecond = 10,
        sendRemoteAddressInit = true,
        maxNoChannels = 250,
      ),
      routerConf = RouterConf(
        watchSpentWindow = 1 second,
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 1 day, // "disables" rebroadcast
        requestNodeAnnouncements = true,
        encodingType = EncodingType.COMPRESSED_ZLIB,
        channelRangeChunkSize = 20,
        channelQueryChunkSize = 5,
        pathFindingExperimentConf = PathFindingExperimentConf(Map("alice-test-experiment" -> PathFindingConf(
          randomize = false,
          boundaries = SearchBoundaries(
            maxFeeFlat = (21 sat).toMilliSatoshi,
            maxFeeProportional = 0.03,
            maxCltv = CltvExpiryDelta(2016),
            maxRouteLength = 20),
          heuristics = Left(WeightRatios(
            baseFactor = 1.0,
            cltvDeltaFactor = 0.0,
            ageFactor = 0.0,
            capacityFactor = 0.0,
            hopCost = RelayFees(0 msat, 0),
          )),
          mpp = MultiPartParams(
            minPartAmount = 15000000 msat,
            maxParts = 10,
          ),
          experimentName = "alice-test-experiment",
          experimentPercentage = 100))),
        messageRouteParams = MessageRouteParams(8, MessagePath.WeightRatios(0.7, 0.1, 0.2)),
        balanceEstimateHalfLife = 1 day,
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5,
      paymentFinalExpiry = PaymentFinalExpiryConf(CltvExpiryDelta(1), CltvExpiryDelta(1)),
      enableTrampolinePayment = true,
      instanceId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
      balanceCheckInterval = 1 hour,
      blockchainWatchdogThreshold = 6,
      blockchainWatchdogSources = blockchainWatchdogSources,
      onionMessageConfig = OnionMessageConfig(
        relayPolicy = RelayAll,
        minIntermediateHops = 9,
        timeout = 200 millis,
        maxAttempts = 2,
      ),
      purgeInvoicesInterval = None,
      revokedHtlcInfoCleanerConfig = RevokedHtlcInfoCleaner.Config(10, 100 millis),
      liquidityAdsConfig_opt = Some(LiquidityAds.SellerConfig(Seq(LiquidityAds.LeaseRateConfig(defaultLiquidityRates, minAmount = 10_000 sat)))),
    )

    def channelParams: LocalParams = OpenChannelInterceptor.makeChannelParams(
      nodeParams,
      nodeParams.features.initFeatures(),
      None,
      None,
      isInitiator = true,
      dualFunded = false,
      fundingSatoshis,
      unlimitedMaxHtlcValueInFlight = false,
    ).copy(
      initialRequestedChannelReserve_opt = Some(10_000 sat) // Bob will need to keep that much satoshis in his balance
    )
  }

  object Bob {
    val seed: ByteVector32 = ByteVector32(hex"7620226fec887b0b2ebe76492e5a3fd3eb0e47cd3773263f6a81b59a704dc492") // 02bbbb...
    val nodeKeyManager = new LocalNodeKeyManager(seed, Block.RegtestGenesisBlock.hash)
    val channelKeyManager = new LocalChannelKeyManager(seed, Block.RegtestGenesisBlock.hash)

    def nodeParams: NodeParams = NodeParams(
      nodeKeyManager,
      channelKeyManager,
      onChainKeyManager_opt = None,
      blockHeight = new AtomicLong(defaultBlockHeight),
      feerates = new AtomicReference(FeeratesPerKw.single(feeratePerKw)),
      alias = "bob",
      color = Color(4, 5, 6),
      publicAddresses = NodeAddress.fromParts("localhost", 9732).get :: Nil,
      torAddress_opt = None,
      features = Features(
        DataLossProtect -> Optional,
        ChannelRangeQueries -> Optional,
        ChannelRangeQueriesExtended -> Optional,
        VariableLengthOnion -> Mandatory,
        PaymentSecret -> Mandatory,
        BasicMultiPartPayment -> Optional,
        Wumbo -> Optional,
        PaymentMetadata -> Optional,
        RouteBlinding -> Optional,
      ),
      pluginParams = Nil,
      overrideInitFeatures = Map.empty,
      syncWhitelist = Set.empty,
      channelConf = ChannelConf(
        dustLimit = 1000 sat,
        maxRemoteDustLimit = 1500 sat,
        maxHtlcValueInFlightMsat = Long.MaxValue.msat, // Bob has no limit on the combined max value of in-flight htlcs
        maxHtlcValueInFlightPercent = 100,
        maxAcceptedHtlcs = 30,
        expiryDelta = CltvExpiryDelta(144),
        maxExpiryDelta = CltvExpiryDelta(2016),
        fulfillSafetyBeforeTimeout = CltvExpiryDelta(6),
        minFinalExpiryDelta = CltvExpiryDelta(18),
        maxRestartWatchDelay = 5 millis,
        maxBlockProcessingDelay = 10 millis,
        maxTxPublishRetryDelay = 10 millis,
        maxChannelSpentRescanBlocks = 144,
        htlcMinimum = 1000 msat,
        minDepthBlocks = 3,
        toRemoteDelay = CltvExpiryDelta(144),
        maxToLocalDelay = CltvExpiryDelta(1000),
        reserveToFundingRatio = 0.01, // note: not used (overridden below)
        maxReserveToFundingRatio = 0.05,
        unhandledExceptionStrategy = UnhandledExceptionStrategy.LocalClose,
        revocationTimeout = 20 seconds,
        channelFlags = ChannelFlags.Public,
        minFundingPublicSatoshis = 1000 sat,
        minFundingPrivateSatoshis = 900 sat,
        requireConfirmedInputsForDualFunding = false,
        channelOpenerWhitelist = Set.empty,
        maxPendingChannelsPerPeer = 3,
        maxTotalPendingChannelsPrivateNodes = 99,
        remoteRbfLimits = RemoteRbfLimits(5, 0),
        quiescenceTimeout = 2 minutes,
        balanceThresholds = Nil,
        minTimeBetweenUpdates = 0 hour,
      ),
      onChainFeeConf = OnChainFeeConf(
        feeTargets = FeeTargets(funding = ConfirmationPriority.Medium, closing = ConfirmationPriority.Medium),
        safeUtxosThreshold = 0,
        spendAnchorWithoutHtlcs = true,
        anchorWithoutHtlcsMaxFee = 100_000.sat,
        closeOnOfflineMismatch = true,
        updateFeeMinDiffRatio = 0.1,
        defaultFeerateTolerance = FeerateTolerance(0.75, 1.5, anchorOutputsFeeratePerKw, DustTolerance(30_000 sat, closeOnUpdateFeeOverflow = true)),
        perNodeFeerateTolerance = Map.empty
      ),
      relayParams = RelayParams(
        publicChannelFees = RelayFees(
          feeBase = 546000 msat,
          feeProportionalMillionths = 10),
        privateChannelFees = RelayFees(
          feeBase = 547000 msat,
          feeProportionalMillionths = 20),
        minTrampolineFees = RelayFees(
          feeBase = 548000 msat,
          feeProportionalMillionths = 30),
        enforcementDelay = 10 minutes,
        asyncPaymentsParams = AsyncPaymentsParams(1008, CltvExpiryDelta(144))),
      db = TestDatabases.inMemoryDb(),
      autoReconnect = false,
      initialRandomReconnectDelay = 5 seconds,
      maxReconnectInterval = 1 hour,
      chainHash = Block.RegtestGenesisBlock.hash,
      invoiceExpiry = 1 hour,
      multiPartPaymentExpiry = 30 seconds,
      peerConnectionConf = PeerConnection.Conf(
        authTimeout = 10 seconds,
        initTimeout = 10 seconds,
        pingInterval = 30 seconds,
        pingTimeout = 10 seconds,
        pingDisconnect = true,
        maxRebroadcastDelay = 5 seconds,
        killIdleDelay = 10 seconds,
        maxOnionMessagesPerSecond = 10,
        sendRemoteAddressInit = true,
        maxNoChannels = 250,
      ),
      routerConf = RouterConf(
        watchSpentWindow = 1 second,
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 1 day, // "disables" rebroadcast
        requestNodeAnnouncements = true,
        encodingType = EncodingType.UNCOMPRESSED,
        channelRangeChunkSize = 20,
        channelQueryChunkSize = 5,
        pathFindingExperimentConf = PathFindingExperimentConf(Map("bob-test-experiment" -> PathFindingConf(
          randomize = false,
          boundaries = SearchBoundaries(
            maxFeeFlat = (21 sat).toMilliSatoshi,
            maxFeeProportional = 0.03,
            maxCltv = CltvExpiryDelta(2016),
            maxRouteLength = 20),
          heuristics = Left(WeightRatios(
            baseFactor = 1.0,
            cltvDeltaFactor = 0.0,
            ageFactor = 0.0,
            capacityFactor = 0.0,
            hopCost = RelayFees(0 msat, 0),
          )),
          mpp = MultiPartParams(
            minPartAmount = 15000000 msat,
            maxParts = 10,
          ),
          experimentName = "bob-test-experiment",
          experimentPercentage = 100))),
        messageRouteParams = MessageRouteParams(9, MessagePath.WeightRatios(0.5, 0.2, 0.3)),
        balanceEstimateHalfLife = 1 day,
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5,
      paymentFinalExpiry = PaymentFinalExpiryConf(CltvExpiryDelta(1), CltvExpiryDelta(1)),
      enableTrampolinePayment = true,
      instanceId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
      balanceCheckInterval = 1 hour,
      blockchainWatchdogThreshold = 6,
      blockchainWatchdogSources = blockchainWatchdogSources,
      onionMessageConfig = OnionMessageConfig(
        relayPolicy = RelayAll,
        minIntermediateHops = 8,
        timeout = 100 millis,
        maxAttempts = 2,
      ),
      purgeInvoicesInterval = None,
      revokedHtlcInfoCleanerConfig = RevokedHtlcInfoCleaner.Config(10, 100 millis),
      liquidityAdsConfig_opt = Some(LiquidityAds.SellerConfig(Seq(LiquidityAds.LeaseRateConfig(defaultLiquidityRates, minAmount = 10_000 sat)))),
    )

    def channelParams: LocalParams = OpenChannelInterceptor.makeChannelParams(
      nodeParams,
      nodeParams.features.initFeatures(),
      None,
      None,
      isInitiator = false,
      dualFunded = false,
      fundingSatoshis,
      unlimitedMaxHtlcValueInFlight = false,
    ).copy(
      initialRequestedChannelReserve_opt = Some(20_000 sat) // Alice will need to keep that much satoshis in her balance
    )
  }

}

object TestTags {

  // Tests that call an external API (which may start failing independently of our code).
  object ExternalApi extends Tag("external-api")

}
