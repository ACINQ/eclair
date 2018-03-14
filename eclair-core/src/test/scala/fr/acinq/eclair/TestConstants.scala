package fr.acinq.eclair

import java.net.InetSocketAddress
import java.sql.DriverManager

import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{BinaryData, Block, Script}
import fr.acinq.eclair.NodeParams.BITCOIND
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db.sqlite._
import fr.acinq.eclair.io.Peer
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
    val keyManager = new LocalKeyManager(seed)

    def sqlite = DriverManager.getConnection("jdbc:sqlite::memory:")

    // This is a function, and not a val! When called will return a new NodeParams
    def nodeParams = NodeParams(
      keyManager = keyManager,
      alias = "alice",
      color = Color(1, 2, 3),
      publicAddresses = new InetSocketAddress("localhost", 9731) :: Nil,
      globalFeatures = "",
      localFeatures = "00",
      dustLimitSatoshis = 546,
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
      routerBroadcastInterval = 60 seconds,
      routerValidateInterval = 2 seconds,
      pingInterval = 30 seconds,
      maxFeerateMismatch = 1.5,
      updateFeeMinDiffRatio = 0.1,
      autoReconnect = false,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      channelExcludeDuration = 5 seconds,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      maxPendingPaymentRequests = 10000000)

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
    val keyManager = new LocalKeyManager(seed)

    def sqlite = DriverManager.getConnection("jdbc:sqlite::memory:")

    def nodeParams = NodeParams(
      keyManager = keyManager,
      alias = "bob",
      color = Color(4, 5, 6),
      publicAddresses = new InetSocketAddress("localhost", 9732) :: Nil,
      globalFeatures = "",
      localFeatures = "00", // no announcement
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
      routerBroadcastInterval = 60 seconds,
      routerValidateInterval = 2 seconds,
      pingInterval = 30 seconds,
      maxFeerateMismatch = 1.0,
      updateFeeMinDiffRatio = 0.1,
      autoReconnect = false,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      channelExcludeDuration = 5 seconds,
      watcherType = BITCOIND,
      paymentRequestExpiry = 1 hour,
      maxPendingPaymentRequests = 10000000)

    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(Array.fill[Byte](32)(5), compressed = true).publicKey)),
      isFunder = false,
      fundingSatoshis).copy(
      channelReserveSatoshis = 20000 // Alice will need to keep that much satoshis as direct payment
    )
  }

}
