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

import java.sql.DriverManager

import com.google.common.net.HostAndPort
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, Block, Script}
import fr.acinq.eclair.NodeParams.BITCOIND
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db.sqlite._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.wire.{Color, NodeAddress}
import fr.acinq.eclair.router.RouterConf
import fr.acinq.eclair.wire.Color

import scala.concurrent.duration._

/**
  * Created by PM on 26/04/2016.
  */
object TestConstants {
  val fundingSatoshis = 1000000L
  val pushMsat = 200000000L
  val feeratePerKw = 10000L

  object Alice {
    val seed = BinaryData("01" * 32)
    val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)

    def sqlite = DriverManager.getConnection("jdbc:sqlite::memory:")

    // This is a function, and not a val! When called will return a new NodeParams
    def nodeParams = NodeParams(
      keyManager = keyManager,
      alias = "alice",
      color = Color(1, 2, 3),
      publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil,
      globalFeatures = "",
      localFeatures = "00",
      overrideFeatures = Map.empty,
      dustLimitSatoshis = 1100,
      maxHtlcValueInFlightMsat = UInt64(150000000),
      maxAcceptedHtlcs = 100,
      expiryDeltaBlocks = 144,
      htlcMinimumMsat = 0,
      minDepthBlocks = 3,
      toRemoteDelayBlocks = 144,
      maxToLocalDelayBlocks = 1000,
      smartfeeNBlocks = 3,
      feeBaseMsat = 546000,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      channelsDb = new SqliteChannelsDb(sqlite),
      peersDb = new SqlitePeersDb(sqlite),
      networkDb = new SqliteNetworkDb(sqlite),
      pendingRelayDb = new SqlitePendingRelayDb(sqlite),
      paymentsDb = new SqlitePaymentsDb(sqlite),
      auditDb = new SqliteAuditDb(sqlite),
      revocationTimeout = 20 seconds,
      pingInterval = 30 seconds,
      pingTimeout = 10 seconds,
      pingDisconnect = true,
      maxFeerateMismatch = 1.5,
      updateFeeMinDiffRatio = 0.1,
      autoReconnect = false,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      maxPendingPaymentRequests = 10000000,
      minFundingSatoshis = 1000L,
      routerConf = RouterConf(
        randomizeRouteSelection = false,
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 5 seconds,
        searchMaxFeeBaseSat = 21,
        searchMaxFeePct = 0.03,
        searchMaxCltv = 2016,
        searchMaxRouteLength = 20,
        searchHeuristicsEnabled = false,
        searchRatioCltv = 0.0,
        searchRatioChannelAge = 0.0,
        searchRatioChannelCapacity = 0.0
      ),
      socksProxy_opt = None
    )

    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(4), compressed = true).publicKey)),
      isFunder = true,
      fundingSatoshis).copy(
      channelReserveSatoshis = 10000 // Bob will need to keep that much satoshis as direct payment
    )
  }

  object Bob {
    val seed = BinaryData("02" * 32)
    val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)

    def sqlite = DriverManager.getConnection("jdbc:sqlite::memory:")

    def nodeParams = NodeParams(
      keyManager = keyManager,
      alias = "bob",
      color = Color(4, 5, 6),
      publicAddresses = NodeAddress.fromParts("localhost", 9732).get :: Nil,
      globalFeatures = "",
      localFeatures = "00", // no announcement
      overrideFeatures = Map.empty,
      dustLimitSatoshis = 1000,
      maxHtlcValueInFlightMsat = UInt64.MaxValue, // Bob has no limit on the combined max value of in-flight htlcs
      maxAcceptedHtlcs = 30,
      expiryDeltaBlocks = 144,
      htlcMinimumMsat = 1000,
      minDepthBlocks = 3,
      toRemoteDelayBlocks = 144,
      maxToLocalDelayBlocks = 1000,
      smartfeeNBlocks = 3,
      feeBaseMsat = 546000,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      channelsDb = new SqliteChannelsDb(sqlite),
      peersDb = new SqlitePeersDb(sqlite),
      networkDb = new SqliteNetworkDb(sqlite),
      pendingRelayDb = new SqlitePendingRelayDb(sqlite),
      paymentsDb = new SqlitePaymentsDb(sqlite),
      auditDb = new SqliteAuditDb(sqlite),
      revocationTimeout = 20 seconds,
      pingInterval = 30 seconds,
      pingTimeout = 10 seconds,
      pingDisconnect = true,
      maxFeerateMismatch = 1.0,
      updateFeeMinDiffRatio = 0.1,
      autoReconnect = false,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      maxPendingPaymentRequests = 10000000,
      minFundingSatoshis = 1000L,
      routerConf = RouterConf(
        randomizeRouteSelection = false,
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 5 seconds,
        searchMaxFeeBaseSat = 21,
        searchMaxFeePct = 0.03,
        searchMaxCltv = 2016,
        searchMaxRouteLength = 20,
        searchHeuristicsEnabled = false,
        searchRatioCltv = 0.0,
        searchRatioChannelAge = 0.0,
        searchRatioChannelCapacity = 0.0
      ),
      socksProxy_opt = None
    )

    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(5), compressed = true).publicKey)),
      isFunder = false,
      fundingSatoshis).copy(
      channelReserveSatoshis = 20000 // Alice will need to keep that much satoshis as direct payment
    )
  }

}
