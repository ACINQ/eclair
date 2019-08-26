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

import java.sql.{Connection, DriverManager}

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, ByteVector32, Satoshi, Script}
import fr.acinq.eclair.NodeParams.BITCOIND
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeTargets, FeeratesPerKw, OnChainFeeConf}
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.router.RouterConf
import fr.acinq.eclair.wire.{Color, NodeAddress}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 26/04/2016.
 */
object TestConstants {

  val fundingSatoshis = Satoshi(1000000L)
  val pushMsat = MilliSatoshi(200000000L)
  val feeratePerKw = 10000L
  val emptyOnionPacket = wire.OnionRoutingPacket(0, ByteVector.fill(33)(0), ByteVector.fill(1300)(0), ByteVector32.Zeroes)

  class TestFeeEstimator extends FeeEstimator {
    private var currentFeerates = FeeratesPerKw.single(feeratePerKw)

    override def getFeeratePerKb(target: Int): Long = feerateKw2KB(currentFeerates.feePerBlock(target))

    override def getFeeratePerKw(target: Int): Long = currentFeerates.feePerBlock(target)

    def setFeerate(feeratesPerKw: FeeratesPerKw): Unit = {
      currentFeerates = feeratesPerKw
    }
  }

  def sqliteInMemory() = DriverManager.getConnection("jdbc:sqlite::memory:")

  def inMemoryDb(connection: Connection = sqliteInMemory()): Databases = Databases.databaseByConnections(connection, connection, connection)

  object Alice {
    val seed = ByteVector32(ByteVector.fill(32)(1))
    val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)

    // This is a function, and not a val! When called will return a new NodeParams
    def nodeParams = NodeParams(
      keyManager = keyManager,
      alias = "alice",
      color = Color(1, 2, 3),
      publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil,
      globalFeatures = ByteVector.empty,
      localFeatures = ByteVector(0),
      overrideFeatures = Map.empty,
      dustLimit = Satoshi(1100),
      onChainFeeConf = OnChainFeeConf(
        feeTargets = FeeTargets(6, 2, 2, 6),
        feeEstimator = new TestFeeEstimator,
        maxFeerateMismatch = 1.5,
        updateFeeMinDiffRatio = 0.1
      ),
      maxHtlcValueInFlightMsat = UInt64(150000000),
      maxAcceptedHtlcs = 100,
      expiryDeltaBlocks = CltvExpiryDelta(144),
      fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
      htlcMinimum = MilliSatoshi(0),
      minDepthBlocks = 3,
      toRemoteDelayBlocks = CltvExpiryDelta(144),
      maxToLocalDelayBlocks = CltvExpiryDelta(1000),
      feeBase = MilliSatoshi(546000),
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      db = inMemoryDb(sqliteInMemory()),
      revocationTimeout = 20 seconds,
      pingInterval = 30 seconds,
      pingTimeout = 10 seconds,
      pingDisconnect = true,
      autoReconnect = false,
      initialRandomReconnectDelay = 5 seconds,
      maxReconnectInterval = 1 hour,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      minFundingSatoshis = Satoshi(1000L),
      routerConf = RouterConf(
        randomizeRouteSelection = false,
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 5 seconds,
        searchMaxFeeBase = Satoshi(21),
        searchMaxFeePct = 0.03,
        searchMaxCltv = CltvExpiryDelta(2016),
        searchMaxRouteLength = 20,
        searchHeuristicsEnabled = false,
        searchRatioCltv = 0.0,
        searchRatioChannelAge = 0.0,
        searchRatioChannelCapacity = 0.0
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5
    )

    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(randomBytes32).publicKey)),
      isFunder = true,
      fundingSatoshis).copy(
      channelReserve = Satoshi(10000) // Bob will need to keep that much satoshis as direct payment
    )
  }

  object Bob {
    val seed = ByteVector32(ByteVector.fill(32)(2))
    val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)

    def nodeParams = NodeParams(
      keyManager = keyManager,
      alias = "bob",
      color = Color(4, 5, 6),
      publicAddresses = NodeAddress.fromParts("localhost", 9732).get :: Nil,
      globalFeatures = ByteVector.empty,
      localFeatures = ByteVector.empty, // no announcement
      overrideFeatures = Map.empty,
      dustLimit = Satoshi(1000),
      onChainFeeConf = OnChainFeeConf(
        feeTargets = FeeTargets(6, 2, 2, 6),
        feeEstimator = new TestFeeEstimator,
        maxFeerateMismatch = 1.0,
        updateFeeMinDiffRatio = 0.1
      ),
      maxHtlcValueInFlightMsat = UInt64.MaxValue, // Bob has no limit on the combined max value of in-flight htlcs
      maxAcceptedHtlcs = 30,
      expiryDeltaBlocks = CltvExpiryDelta(144),
      fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
      htlcMinimum = MilliSatoshi(1000),
      minDepthBlocks = 3,
      toRemoteDelayBlocks = CltvExpiryDelta(144),
      maxToLocalDelayBlocks = CltvExpiryDelta(1000),
      feeBase = MilliSatoshi(546000),
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      db = inMemoryDb(sqliteInMemory()),
      revocationTimeout = 20 seconds,
      pingInterval = 30 seconds,
      pingTimeout = 10 seconds,
      pingDisconnect = true,
      autoReconnect = false,
      initialRandomReconnectDelay = 5 seconds,
      maxReconnectInterval = 1 hour,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      minFundingSatoshis = Satoshi(1000L),
      routerConf = RouterConf(
        randomizeRouteSelection = false,
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 5 seconds,
        searchMaxFeeBase = Satoshi(21),
        searchMaxFeePct = 0.03,
        searchMaxCltv = CltvExpiryDelta(2016),
        searchMaxRouteLength = 20,
        searchHeuristicsEnabled = false,
        searchRatioCltv = 0.0,
        searchRatioChannelAge = 0.0,
        searchRatioChannelCapacity = 0.0
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5
    )

    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(randomBytes32).publicKey)),
      isFunder = false,
      fundingSatoshis).copy(
      channelReserve = Satoshi(20000) // Alice will need to keep that much satoshis as direct payment
    )
  }

}
