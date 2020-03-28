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

import java.sql.{Connection, DriverManager, Statement}
import java.util.concurrent.atomic.AtomicLong

import com.opentable.db.postgres.embedded.EmbeddedPostgres
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, Btc, ByteVector32, Script}
import fr.acinq.eclair.NodeParams.BITCOIND
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeTargets, FeeratesPerKw, OnChainFeeConf}
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.psql.PsqlUtils.NoLock
import fr.acinq.eclair.db.psql._
import fr.acinq.eclair.db.sqlite._
import fr.acinq.eclair.io.Peer
import fr.acinq.eclair.router.RouterConf
import fr.acinq.eclair.wire.{Color, EncodingType, NodeAddress}
import scodec.bits.ByteVector

import scala.concurrent.duration._

/**
 * Created by PM on 26/04/2016.
 */
object TestConstants {

  val defaultBlockHeight = 400000
  val fundingSatoshis = 1000000L sat
  val pushMsat = 200000000L msat
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

  sealed trait TestDatabases {
    val connection: Connection
    def network(): NetworkDb
    def audit(): AuditDb
    def channels(): ChannelsDb
    def peers(): PeersDb
    def payments(): PaymentsDb
    def pendingRelay(): PendingRelayDb
    def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int
    def close(): Unit
  }

  case class TestSqliteDatabases(connection: Connection = sqliteInMemory()) extends TestDatabases {
    override def network(): NetworkDb = new SqliteNetworkDb(connection)
    override def audit(): AuditDb = new SqliteAuditDb(connection)
    override def channels(): ChannelsDb = new SqliteChannelsDb(connection)
    override def peers(): PeersDb = new SqlitePeersDb(connection)
    override def payments(): PaymentsDb = new SqlitePaymentsDb(connection)
    override def pendingRelay(): PendingRelayDb = new SqlitePendingRelayDb(connection)
    override def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int = SqliteUtils.getVersion(statement, db_name, currentVersion)
    override def close(): Unit = ()
  }

  case class TestPsqlDatabases() extends TestDatabases {
    private val pg = EmbeddedPostgres.start()

    override val connection: Connection = pg.getPostgresDatabase.getConnection

    import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

    val config = new HikariConfig
    config.setDataSource(pg.getPostgresDatabase)

    implicit val ds = new HikariDataSource(config)

    implicit val lock = NoLock

    override def network(): NetworkDb = new PsqlNetworkDb
    override def audit(): AuditDb = new PsqlAuditDb
    override def channels(): ChannelsDb = new PsqlChannelsDb
    override def peers(): PeersDb = new PsqlPeersDb
    override def payments(): PaymentsDb = new PsqlPaymentsDb
    override def pendingRelay(): PendingRelayDb = new PsqlPendingRelayDb
    override def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int = PsqlUtils.getVersion(statement, db_name, currentVersion)
    override def close(): Unit = pg.close()
  }

  def sqliteInMemory(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

  def forAllDbs(f: TestDatabases => Unit): Unit = {
    def using(dbs: TestDatabases)(g: TestDatabases => Unit): Unit = try g(dbs) finally dbs.close()
    using(TestSqliteDatabases())(f)
    using(TestPsqlDatabases())(f)
  }

  def inMemoryDb(connection: Connection = sqliteInMemory()): Databases = Databases.sqliteDatabaseByConnections(connection, connection, connection)

  object Alice {
    val seed = ByteVector32(ByteVector.fill(32)(1))
    val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)

    // This is a function, and not a val! When called will return a new NodeParams
    def nodeParams = NodeParams(
      keyManager = keyManager,
      blockCount = new AtomicLong(defaultBlockHeight),
      alias = "alice",
      color = Color(1, 2, 3),
      publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil,
      features = ByteVector.fromValidHex("0a8a"),
      overrideFeatures = Map.empty,
      syncWhitelist = Set.empty,
      dustLimit = 1100 sat,
      onChainFeeConf = OnChainFeeConf(
        feeTargets = FeeTargets(6, 2, 2, 6),
        feeEstimator = new TestFeeEstimator,
        maxFeerateMismatch = 1.5,
        closeOnOfflineMismatch = true,
        updateFeeMinDiffRatio = 0.1
      ),
      maxHtlcValueInFlightMsat = UInt64(150000000),
      maxAcceptedHtlcs = 100,
      expiryDeltaBlocks = CltvExpiryDelta(144),
      fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
      htlcMinimum = 0 msat,
      minDepthBlocks = 3,
      toRemoteDelayBlocks = CltvExpiryDelta(144),
      maxToLocalDelayBlocks = CltvExpiryDelta(1000),
      feeBase = 546000 msat,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      db = inMemoryDb(sqliteInMemory()),
      revocationTimeout = 20 seconds,
      authTimeout = 10 seconds,
      initTimeout = 10 seconds,
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
      multiPartPaymentExpiry = 30 seconds,
      minFundingSatoshis = 1000 sat,
      maxFundingSatoshis = 16777215 sat,
      routerConf = RouterConf(
        randomizeRouteSelection = false,
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 5 seconds,
        networkStatsRefreshInterval = 1 hour,
        requestNodeAnnouncements = true,
        encodingType = EncodingType.COMPRESSED_ZLIB,
        channelRangeChunkSize = 20,
        channelQueryChunkSize = 5,
        searchMaxFeeBase = 21 sat,
        searchMaxFeePct = 0.03,
        searchMaxCltv = CltvExpiryDelta(2016),
        searchMaxRouteLength = 20,
        searchHeuristicsEnabled = false,
        searchRatioCltv = 0.0,
        searchRatioChannelAge = 0.0,
        searchRatioChannelCapacity = 0.0
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5,
      enableTrampolinePayment = true
    )

    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(randomBytes32).publicKey)),
      isFunder = true,
      fundingSatoshis).copy(
      channelReserve = 10000 sat // Bob will need to keep that much satoshis as direct payment
    )
  }

  object Bob {
    val seed = ByteVector32(ByteVector.fill(32)(2))
    val keyManager = new LocalKeyManager(seed, Block.RegtestGenesisBlock.hash)

    def nodeParams = NodeParams(
      keyManager = keyManager,
      blockCount = new AtomicLong(defaultBlockHeight),
      alias = "bob",
      color = Color(4, 5, 6),
      publicAddresses = NodeAddress.fromParts("localhost", 9732).get :: Nil,
      features = ByteVector.fromValidHex("0200"), // variable_length_onion, no announcement
      overrideFeatures = Map.empty,
      syncWhitelist = Set.empty,
      dustLimit = 1000 sat,
      onChainFeeConf = OnChainFeeConf(
        feeTargets = FeeTargets(6, 2, 2, 6),
        feeEstimator = new TestFeeEstimator,
        maxFeerateMismatch = 1.0,
        closeOnOfflineMismatch = true,
        updateFeeMinDiffRatio = 0.1
      ),
      maxHtlcValueInFlightMsat = UInt64.MaxValue, // Bob has no limit on the combined max value of in-flight htlcs
      maxAcceptedHtlcs = 30,
      expiryDeltaBlocks = CltvExpiryDelta(144),
      fulfillSafetyBeforeTimeoutBlocks = CltvExpiryDelta(6),
      htlcMinimum = 1000 msat,
      minDepthBlocks = 3,
      toRemoteDelayBlocks = CltvExpiryDelta(144),
      maxToLocalDelayBlocks = CltvExpiryDelta(1000),
      feeBase = 546000 msat,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      db = inMemoryDb(sqliteInMemory()),
      revocationTimeout = 20 seconds,
      authTimeout = 10 seconds,
      initTimeout = 10 seconds,
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
      multiPartPaymentExpiry = 30 seconds,
      minFundingSatoshis = 1000 sat,
      maxFundingSatoshis = 16777215 sat,
      routerConf = RouterConf(
        randomizeRouteSelection = false,
        channelExcludeDuration = 60 seconds,
        routerBroadcastInterval = 5 seconds,
        networkStatsRefreshInterval = 1 hour,
        requestNodeAnnouncements = true,
        encodingType = EncodingType.UNCOMPRESSED,
        channelRangeChunkSize = 20,
        channelQueryChunkSize = 5,
        searchMaxFeeBase = 21 sat,
        searchMaxFeePct = 0.03,
        searchMaxCltv = CltvExpiryDelta(2016),
        searchMaxRouteLength = 20,
        searchHeuristicsEnabled = false,
        searchRatioCltv = 0.0,
        searchRatioChannelAge = 0.0,
        searchRatioChannelCapacity = 0.0
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5,
      enableTrampolinePayment = true
    )

    def channelParams = Peer.makeChannelParams(
      nodeParams = nodeParams,
      defaultFinalScriptPubKey = Script.write(Script.pay2wpkh(PrivateKey(randomBytes32).publicKey)),
      isFunder = false,
      fundingSatoshis).copy(
      channelReserve = 20000 sat // Alice will need to keep that much satoshis as direct payment
    )
  }

}