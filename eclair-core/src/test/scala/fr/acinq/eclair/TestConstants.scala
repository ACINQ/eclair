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

import com.opentable.db.postgres.embedded.EmbeddedPostgres
import fr.acinq.bitcoin.Crypto.PrivateKey
import fr.acinq.bitcoin.{Block, ByteVector32, SatoshiLong, Script}
import fr.acinq.eclair.FeatureSupport.Optional
import fr.acinq.eclair.Features._
import fr.acinq.eclair.NodeParams.BITCOIND
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeTargets, FeeratesPerKw, OnChainFeeConf, _}
import fr.acinq.eclair.crypto.keymanager.{LocalChannelKeyManager, LocalNodeKeyManager}
import fr.acinq.eclair.db._
import fr.acinq.eclair.db.pg.PgUtils.NoLock
import fr.acinq.eclair.db.pg._
import fr.acinq.eclair.db.sqlite._
import fr.acinq.eclair.io.{Peer, PeerConnection}
import fr.acinq.eclair.router.Router.RouterConf
import fr.acinq.eclair.wire.{Color, EncodingType, NodeAddress}
import org.scalatest.Tag
import scodec.bits.ByteVector

import java.sql.{Connection, DriverManager, Statement}
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._

/**
 * Created by PM on 26/04/2016.
 */
object TestConstants {

  val defaultBlockHeight = 400000
  val fundingSatoshis = 1000000L sat
  val pushMsat = 200000000L msat
  val feeratePerKw = FeeratePerKw(10000 sat)
  val anchorOutputsFeeratePerKw = FeeratePerKw(2500 sat)
  val emptyOnionPacket = wire.OnionRoutingPacket(0, ByteVector.fill(33)(0), ByteVector.fill(1300)(0), ByteVector32.Zeroes)

  class TestFeeEstimator extends FeeEstimator {
    private var currentFeerates = FeeratesPerKw.single(feeratePerKw)

    override def getFeeratePerKb(target: Int): FeeratePerKB = FeeratePerKB(currentFeerates.feePerBlock(target))

    override def getFeeratePerKw(target: Int): FeeratePerKw = currentFeerates.feePerBlock(target)

    def setFeerate(feeratesPerKw: FeeratesPerKw): Unit = {
      currentFeerates = feeratesPerKw
    }
  }

  sealed trait TestDatabases {
    // @formatter:off
    val connection: Connection
    def network(): NetworkDb
    def audit(): AuditDb
    def channels(): ChannelsDb
    def peers(): PeersDb
    def payments(): PaymentsDb
    def pendingRelay(): PendingRelayDb
    def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int
    def close(): Unit
    // @formatter:on
  }

  case class TestSqliteDatabases(connection: Connection = sqliteInMemory()) extends TestDatabases {
    // @formatter:off
    override def network(): NetworkDb = new SqliteNetworkDb(connection)
    override def audit(): AuditDb = new SqliteAuditDb(connection)
    override def channels(): ChannelsDb = new SqliteChannelsDb(connection)
    override def peers(): PeersDb = new SqlitePeersDb(connection)
    override def payments(): PaymentsDb = new SqlitePaymentsDb(connection)
    override def pendingRelay(): PendingRelayDb = new SqlitePendingRelayDb(connection)
    override def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int = SqliteUtils.getVersion(statement, db_name, currentVersion)
    override def close(): Unit = ()
    // @formatter:on
  }

  case class TestPgDatabases() extends TestDatabases {
    private val pg = EmbeddedPostgres.start()

    override val connection: Connection = pg.getPostgresDatabase.getConnection

    import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

    val config = new HikariConfig
    config.setDataSource(pg.getPostgresDatabase)

    implicit val ds = new HikariDataSource(config)
    implicit val lock = NoLock

    // @formatter:off
    override def network(): NetworkDb = new PgNetworkDb
    override def audit(): AuditDb = new PgAuditDb
    override def channels(): ChannelsDb = new PgChannelsDb
    override def peers(): PeersDb = new PgPeersDb
    override def payments(): PaymentsDb = new PgPaymentsDb
    override def pendingRelay(): PendingRelayDb = new PgPendingRelayDb
    override def getVersion(statement: Statement, db_name: String, currentVersion: Int): Int = PgUtils.getVersion(statement, db_name, currentVersion)
    override def close(): Unit = pg.close()
    // @formatter:on
  }

  def sqliteInMemory(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

  def forAllDbs(f: TestDatabases => Unit): Unit = {
    // @formatter:off
    def using(dbs: TestDatabases)(g: TestDatabases => Unit): Unit = try g(dbs) finally dbs.close()
    using(TestSqliteDatabases())(f)
    using(TestPgDatabases())(f)
    // @formatter:on
  }

  def inMemoryDb(connection: Connection = sqliteInMemory()): Databases = Databases.sqliteDatabaseByConnections(connection, connection, connection)

  case object TestFeature extends Feature {
    val rfcName = "test_feature"
    val mandatory = 50000
  }

  val pluginParams = new CustomFeaturePlugin {
    // @formatter:off
    override def messageTags: Set[Int] = Set(60003)
    override def feature: Feature = TestFeature
    override def name: String = "plugin for testing"
    // @formatter:on
  }

  object Alice {
    val seed = ByteVector32(ByteVector.fill(32)(1))
    val nodeKeyManager = new LocalNodeKeyManager(seed, Block.RegtestGenesisBlock.hash)
    val channelKeyManager = new LocalChannelKeyManager(seed, Block.RegtestGenesisBlock.hash)

    // This is a function, and not a val! When called will return a new NodeParams
    def nodeParams = NodeParams(
      nodeKeyManager,
      channelKeyManager,
      blockCount = new AtomicLong(defaultBlockHeight),
      alias = "alice",
      color = Color(1, 2, 3),
      publicAddresses = NodeAddress.fromParts("localhost", 9731).get :: Nil,
      features = Features(
        Map[Feature, FeatureSupport](
          OptionDataLossProtect -> Optional,
          ChannelRangeQueries -> Optional,
          ChannelRangeQueriesExtended -> Optional,
          VariableLengthOnion -> Optional,
          PaymentSecret -> Optional,
          BasicMultiPartPayment -> Optional
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
        defaultFeerateTolerance = FeerateTolerance(0.5, 8.0, anchorOutputsFeeratePerKw),
        perNodeFeerateTolerance = Map.empty
      ),
      maxHtlcValueInFlightMsat = UInt64(150000000),
      maxAcceptedHtlcs = 100,
      expiryDelta = CltvExpiryDelta(144),
      fulfillSafetyBeforeTimeout = CltvExpiryDelta(6),
      minFinalExpiryDelta = CltvExpiryDelta(18),
      htlcMinimum = 0 msat,
      minDepthBlocks = 3,
      toRemoteDelay = CltvExpiryDelta(144),
      maxToLocalDelay = CltvExpiryDelta(1000),
      feeBase = 546000 msat,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      db = inMemoryDb(sqliteInMemory()),
      revocationTimeout = 20 seconds,
      autoReconnect = false,
      initialRandomReconnectDelay = 5 seconds,
      maxReconnectInterval = 1 hour,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      watcherType = BITCOIND,
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
        maxRebroadcastDelay = 5 seconds
      ),
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
        searchRatioChannelCapacity = 0.0,
        mppMinPartAmount = 15000000 msat,
        mppMaxParts = 10
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5,
      enableTrampolinePayment = true,
      instanceId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    )

    def channelParams = Peer.makeChannelParams(
      nodeParams,
      nodeParams.features,
      Script.write(Script.pay2wpkh(PrivateKey(randomBytes32).publicKey)),
      None,
      isFunder = true,
      fundingSatoshis
    ).copy(
      channelReserve = 10000 sat // Bob will need to keep that much satoshis as direct payment
    )
  }

  object Bob {
    val seed = ByteVector32(ByteVector.fill(32)(2))
    val nodeKeyManager = new LocalNodeKeyManager(seed, Block.RegtestGenesisBlock.hash)
    val channelKeyManager = new LocalChannelKeyManager(seed, Block.RegtestGenesisBlock.hash)

    def nodeParams = NodeParams(
      nodeKeyManager,
      channelKeyManager,
      blockCount = new AtomicLong(defaultBlockHeight),
      alias = "bob",
      color = Color(4, 5, 6),
      publicAddresses = NodeAddress.fromParts("localhost", 9732).get :: Nil,
      features = Features(
        OptionDataLossProtect -> Optional,
        ChannelRangeQueries -> Optional,
        ChannelRangeQueriesExtended -> Optional,
        VariableLengthOnion -> Optional,
        PaymentSecret -> Optional,
        BasicMultiPartPayment -> Optional
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
        defaultFeerateTolerance = FeerateTolerance(0.75, 1.5, anchorOutputsFeeratePerKw),
        perNodeFeerateTolerance = Map.empty
      ),
      maxHtlcValueInFlightMsat = UInt64.MaxValue, // Bob has no limit on the combined max value of in-flight htlcs
      maxAcceptedHtlcs = 30,
      expiryDelta = CltvExpiryDelta(144),
      fulfillSafetyBeforeTimeout = CltvExpiryDelta(6),
      minFinalExpiryDelta = CltvExpiryDelta(18),
      htlcMinimum = 1000 msat,
      minDepthBlocks = 3,
      toRemoteDelay = CltvExpiryDelta(144),
      maxToLocalDelay = CltvExpiryDelta(1000),
      feeBase = 546000 msat,
      feeProportionalMillionth = 10,
      reserveToFundingRatio = 0.01, // note: not used (overridden below)
      maxReserveToFundingRatio = 0.05,
      db = inMemoryDb(sqliteInMemory()),
      revocationTimeout = 20 seconds,
      autoReconnect = false,
      initialRandomReconnectDelay = 5 seconds,
      maxReconnectInterval = 1 hour,
      chainHash = Block.RegtestGenesisBlock.hash,
      channelFlags = 1,
      watcherType = BITCOIND,
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
        maxRebroadcastDelay = 5 seconds
      ),
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
        searchRatioChannelCapacity = 0.0,
        mppMinPartAmount = 15000000 msat,
        mppMaxParts = 10
      ),
      socksProxy_opt = None,
      maxPaymentAttempts = 5,
      enableTrampolinePayment = true,
      instanceId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    )

    def channelParams = Peer.makeChannelParams(
      nodeParams,
      nodeParams.features,
      Script.write(Script.pay2wpkh(PrivateKey(randomBytes32).publicKey)),
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