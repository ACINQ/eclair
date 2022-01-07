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

import fr.acinq.bitcoin.{Block, ByteVector32, Satoshi, SatoshiLong, Script}
import fr.acinq.eclair.FeatureSupport.{Mandatory, Optional}
import fr.acinq.eclair.Features._
import fr.acinq.eclair.blockchain.fee._
import fr.acinq.eclair.channel.Channel.UnhandledExceptionStrategy
import fr.acinq.eclair.channel.LocalParams
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import fr.acinq.eclair.io.MessageRelay.RelayAll
import fr.acinq.eclair.io.{Peer, PeerConnection}
import fr.acinq.eclair.payment.relay.Relayer.{RelayFees, RelayParams}
import fr.acinq.eclair.router.Graph.WeightRatios
import fr.acinq.eclair.router.PathFindingExperimentConf
import fr.acinq.eclair.router.Router.{MultiPartParams, PathFindingConf, RouterConf, SearchBoundaries}
import fr.acinq.eclair.wire.protocol.{Color, EncodingType, NodeAddress, OnionRoutingPacket}
import org.scalatest.Tag
import scodec.bits.ByteVector

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._

/**
 * Created by PM on 26/04/2016.
 */
object TestConstants {

  val defaultBlockHeight = 400000
  val fundingSatoshis: Satoshi = 1000000L sat
  val pushMsat: MilliSatoshi = 200000000L msat
  val feeratePerKw: FeeratePerKw = FeeratePerKw(10000 sat)
  val anchorOutputsFeeratePerKw: FeeratePerKw = FeeratePerKw(2500 sat)
  val emptyOnionPacket: OnionRoutingPacket = OnionRoutingPacket(0, ByteVector.fill(33)(0), ByteVector.fill(1300)(0), ByteVector32.Zeroes)

  class TestFeeEstimator extends FeeEstimator {
    private var currentFeerates = FeeratesPerKw.single(feeratePerKw)

    // @formatter:off
    override def getFeeratePerKb(target: Int): FeeratePerKB = FeeratePerKB(currentFeerates.feePerBlock(target))
    override def getFeeratePerKw(target: Int): FeeratePerKw = currentFeerates.feePerBlock(target)
    override def getMempoolMinFeeratePerKw(): FeeratePerKw = currentFeerates.mempoolMinFee
    // @formatter:on

    def setFeerate(feeratesPerKw: FeeratesPerKw): Unit = {
      currentFeerates = feeratesPerKw
    }
  }

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

  val blockchainWatchdogSources = Seq(
    "bitcoinheaders.net",
    "blockcypher.com",
    "blockstream.info",
    "mempool.space"
  )


  object Alice {
    val seed: ByteVector32 = ByteVector32(ByteVector.fill(32)(1))
    val nodeKeyManager = new LocalNodeKeyManager(seed, Block.RegtestGenesisBlock.hash)
    val channelKeyManager = new LocalChannelKeyManager(seed, Block.RegtestGenesisBlock.hash)

    // This is a function, and not a val! When called will return a new NodeParams
    def nodeParams: NodeParams = NodeParams(
      nodeKeyManager,
      channelKeyManager,
      blockCount = new AtomicLong(defaultBlockHeight),
      alias = "alice",
      color = Color(1, 2, 3),
      publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil,
      torAddress_opt = None,
      features = Features(
        Map[Feature, FeatureSupport](
          OptionDataLossProtect -> Optional,
          ChannelRangeQueries -> Optional,
          ChannelRangeQueriesExtended -> Optional,
          VariableLengthOnion -> Mandatory,
          PaymentSecret -> Mandatory,
          BasicMultiPartPayment -> Optional,
          PaymentMetadata -> Optional,
        ),
        Set(UnknownFeature(TestFeature.optional))
      ),
      pluginParams = List(pluginParams),
      overrideFeatures = Map.empty,
      syncWhitelist = Set.empty,
      dustLimit = 1100 sat,
      maxRemoteDustLimit = 1500 sat,
      onChainFeeConf = OnChainFeeConf(
        feeTargets = FeeTargets(6, 2, 2, 6),
        feeEstimator = new TestFeeEstimator,
        closeOnOfflineMismatch = true,
        updateFeeMinDiffRatio = 0.1,
        defaultFeerateTolerance = FeerateTolerance(0.5, 8.0, anchorOutputsFeeratePerKw, DustTolerance(25_000 sat, closeOnUpdateFeeOverflow = true)),
        perNodeFeerateTolerance = Map.empty
      ),
      maxHtlcValueInFlightMsat = UInt64(500000000),
      maxAcceptedHtlcs = 100,
      expiryDelta = CltvExpiryDelta(144),
      fulfillSafetyBeforeTimeout = CltvExpiryDelta(6),
      minFinalExpiryDelta = CltvExpiryDelta(18),
      maxBlockProcessingDelay = 10 millis,
      maxTxPublishRetryDelay = 10 millis,
      htlcMinimum = 0 msat,
      minDepthBlocks = 3,
      toRemoteDelay = CltvExpiryDelta(144),
      maxToLocalDelay = CltvExpiryDelta(1000),
      relayParams = RelayParams(
        publicChannelFees = RelayFees(
          feeBase = 546000 msat,
          feeProportionalMillionths = 10),
        privateChannelFees = RelayFees(
          feeBase = 547000 msat,
          feeProportionalMillionths = 20),
        minTrampolineFees = RelayFees(
          feeBase = 548000 msat,
          feeProportionalMillionths = 30)),
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      unhandledExceptionStrategy = UnhandledExceptionStrategy.LocalClose,
      db = TestDatabases.inMemoryDb(),
      revocationTimeout = 20 seconds,
      autoReconnect = false,
      initialRandomReconnectDelay = 5 seconds,
      maxReconnectInterval = 1 hour,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      watchSpentWindow = 1 second,
      paymentRequestExpiry = 1 hour,
      multiPartPaymentExpiry = 30 seconds,
      minFundingSatoshis = 1000 sat,
      maxFundingSatoshis = 16777215 sat,
      peerConnectionConf = PeerConnection.Conf(
        authTimeout = 10 seconds,
        initTimeout = 10 seconds,
        pingInterval = 30 seconds,
        pingTimeout = 10 seconds,
        pingDisconnect = true,
        maxRebroadcastDelay = 5 seconds,
        killIdleDelay = 1 seconds,
        maxOnionMessagesPerSecond = 10
      ),
      routerConf = RouterConf(
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 5 seconds,
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
          experimentPercentage = 100)))
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5,
      enableTrampolinePayment = true,
      instanceId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
      balanceCheckInterval = 1 hour,
      blockchainWatchdogSources = blockchainWatchdogSources,
      onionMessageRelayPolicy = RelayAll
    )

    def channelParams: LocalParams = Peer.makeChannelParams(
      nodeParams,
      nodeParams.features,
      Script.write(Script.pay2wpkh(randomKey().publicKey)),
      None,
      isFunder = true,
      fundingSatoshis
    ).copy(
      channelReserve = 10000 sat // Bob will need to keep that much satoshis as direct payment
    )
  }

  object Bob {
    val seed: ByteVector32 = ByteVector32(ByteVector.fill(32)(2))
    val nodeKeyManager = new LocalNodeKeyManager(seed, Block.RegtestGenesisBlock.hash)
    val channelKeyManager = new LocalChannelKeyManager(seed, Block.RegtestGenesisBlock.hash)

    def nodeParams: NodeParams = NodeParams(
      nodeKeyManager,
      channelKeyManager,
      blockCount = new AtomicLong(defaultBlockHeight),
      alias = "bob",
      color = Color(4, 5, 6),
      publicAddresses = NodeAddress.fromParts("localhost", 9732).get :: Nil,
      torAddress_opt = None,
      features = Features(
        OptionDataLossProtect -> Optional,
        ChannelRangeQueries -> Optional,
        ChannelRangeQueriesExtended -> Optional,
        VariableLengthOnion -> Mandatory,
        PaymentSecret -> Mandatory,
        BasicMultiPartPayment -> Optional,
        PaymentMetadata -> Optional,
      ),
      pluginParams = Nil,
      overrideFeatures = Map.empty,
      syncWhitelist = Set.empty,
      dustLimit = 1000 sat,
      maxRemoteDustLimit = 1500 sat,
      onChainFeeConf = OnChainFeeConf(
        feeTargets = FeeTargets(6, 2, 2, 6),
        feeEstimator = new TestFeeEstimator,
        closeOnOfflineMismatch = true,
        updateFeeMinDiffRatio = 0.1,
        defaultFeerateTolerance = FeerateTolerance(0.75, 1.5, anchorOutputsFeeratePerKw, DustTolerance(30_000 sat, closeOnUpdateFeeOverflow = true)),
        perNodeFeerateTolerance = Map.empty
      ),
      maxHtlcValueInFlightMsat = UInt64.MaxValue, // Bob has no limit on the combined max value of in-flight htlcs
      maxAcceptedHtlcs = 30,
      expiryDelta = CltvExpiryDelta(144),
      fulfillSafetyBeforeTimeout = CltvExpiryDelta(6),
      minFinalExpiryDelta = CltvExpiryDelta(18),
      maxBlockProcessingDelay = 10 millis,
      maxTxPublishRetryDelay = 10 millis,
      htlcMinimum = 1000 msat,
      minDepthBlocks = 3,
      toRemoteDelay = CltvExpiryDelta(144),
      maxToLocalDelay = CltvExpiryDelta(1000),
      relayParams = RelayParams(
        publicChannelFees = RelayFees(
          feeBase = 546000 msat,
          feeProportionalMillionths = 10),
        privateChannelFees = RelayFees(
          feeBase = 547000 msat,
          feeProportionalMillionths = 20),
        minTrampolineFees = RelayFees(
          feeBase = 548000 msat,
          feeProportionalMillionths = 30)),
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      unhandledExceptionStrategy = UnhandledExceptionStrategy.LocalClose,
      db = TestDatabases.inMemoryDb(),
      revocationTimeout = 20 seconds,
      autoReconnect = false,
      initialRandomReconnectDelay = 5 seconds,
      maxReconnectInterval = 1 hour,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      watchSpentWindow = 1 second,
      paymentRequestExpiry = 1 hour,
      multiPartPaymentExpiry = 30 seconds,
      minFundingSatoshis = 1000 sat,
      maxFundingSatoshis = 16777215 sat,
      peerConnectionConf = PeerConnection.Conf(
        authTimeout = 10 seconds,
        initTimeout = 10 seconds,
        pingInterval = 30 seconds,
        pingTimeout = 10 seconds,
        pingDisconnect = true,
        maxRebroadcastDelay = 5 seconds,
        killIdleDelay = 10 seconds,
        maxOnionMessagesPerSecond = 10
      ),
      routerConf = RouterConf(
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 5 seconds,
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
          experimentPercentage = 100)))
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5,
      enableTrampolinePayment = true,
      instanceId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
      balanceCheckInterval = 1 hour,
      blockchainWatchdogSources = blockchainWatchdogSources,
      onionMessageRelayPolicy = RelayAll
    )

    def channelParams: LocalParams = Peer.makeChannelParams(
      nodeParams,
      nodeParams.features,
      Script.write(Script.pay2wpkh(randomKey().publicKey)),
      None,
      isFunder = false,
      fundingSatoshis).copy(
      channelReserve = 20000 sat // Alice will need to keep that much satoshis as direct payment
    )
  }

}

object TestTags {

  // Tests that call an external API (which may start failing independently of our code).
  object ExternalApi extends Tag("external-api")

}
