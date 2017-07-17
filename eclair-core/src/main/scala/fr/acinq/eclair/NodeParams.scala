package fr.acinq.eclair

import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.bitcoin.Crypto.{PrivateKey, PublicKey}
import fr.acinq.bitcoin.DeterministicWallet.ExtendedPrivateKey
import fr.acinq.bitcoin.{BinaryData, DeterministicWallet}
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.db.{Dbs, SimpleFileDb, SimpleTypedDb}
import fr.acinq.eclair.io.PeerRecord
import fr.acinq.eclair.wire.LightningMessage

import scala.collection.JavaConversions._
import scala.concurrent.duration.FiniteDuration

/**
  * Created by PM on 26/02/2017.
  */
case class NodeParams(extendedPrivateKey: ExtendedPrivateKey,
                      privateKey: PrivateKey,
                      alias: String,
                      color: (Byte, Byte, Byte),
                      publicAddresses: List[InetSocketAddress],
                      globalFeatures: BinaryData,
                      localFeatures: BinaryData,
                      dustLimitSatoshis: Long,
                      maxHtlcValueInFlightMsat: UInt64,
                      maxAcceptedHtlcs: Int,
                      expiryDeltaBlocks: Int,
                      htlcMinimumMsat: Int,
                      delayBlocks: Int,
                      minDepthBlocks: Int,
                      smartfeeNBlocks: Int,
                      feeBaseMsat: Int,
                      feeProportionalMillionth: Int,
                      reserveToFundingRatio: Double,
                      maxReserveToFundingRatio: Double,
                      defaultFinalScriptPubKey: BinaryData,
                      channelsDb: SimpleTypedDb[BinaryData, HasCommitments],
                      peersDb: SimpleTypedDb[PublicKey, PeerRecord],
                      announcementsDb: SimpleTypedDb[String, LightningMessage],
                      routerBroadcastInterval: FiniteDuration,
                      routerValidateInterval: FiniteDuration,
                      pingInterval: FiniteDuration,
                      maxFeerateMismatch: Double,
                      updateFeeMinDiffRatio: Double,
                      autoReconnect: Boolean,
                      chainHash: BinaryData,
                      channelFlags: Byte)

object NodeParams {

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
      .withFallback(ConfigFactory.load()).getConfig("eclair")

  def makeNodeParams(datadir: File, config: Config, chainHash: BinaryData, defaultFinalScriptPubKey: BinaryData): NodeParams = {

    datadir.mkdirs()

    val seedPath = new File(datadir, "seed.dat")
    val seed: BinaryData = seedPath.exists() match {
      case true => Files.readAllBytes(seedPath.toPath)
      case false =>
        val seed = randomKey.toBin
        Files.write(seedPath.toPath, seed)
        seed
    }
    val master = DeterministicWallet.generate(seed)
    val extendedPrivateKey = DeterministicWallet.derivePrivateKey(master, DeterministicWallet.hardened(46) :: DeterministicWallet.hardened(0) :: Nil)

    val dbDir = new File(datadir, "db")
    val db = new SimpleFileDb(dbDir)

    val color = BinaryData(config.getString("node-color"))
    require(color.size == 3, "color should be a 3-bytes hex buffer")

    NodeParams(
      extendedPrivateKey = extendedPrivateKey,
      privateKey = extendedPrivateKey.privateKey,
      alias = config.getString("node-alias").take(32),
      color = (color.data(0), color.data(1), color.data(2)),
      publicAddresses = config.getStringList("server.public-ips").toList.map(ip => new InetSocketAddress(ip, config.getInt("server.port"))),
      globalFeatures = BinaryData(config.getString("global-features")),
      localFeatures = BinaryData(config.getString("local-features")),
      dustLimitSatoshis = config.getLong("dust-limit-satoshis"),
      maxHtlcValueInFlightMsat = UInt64(config.getLong("max-htlc-value-in-flight-msat")),
      maxAcceptedHtlcs = config.getInt("max-accepted-htlcs"),
      expiryDeltaBlocks = config.getInt("expiry-delta-blocks"),
      htlcMinimumMsat = config.getInt("htlc-minimum-msat"),
      delayBlocks = config.getInt("delay-blocks"),
      minDepthBlocks = config.getInt("mindepth-blocks"),
      smartfeeNBlocks = 3,
      feeBaseMsat = config.getInt("fee-base-msat"),
      feeProportionalMillionth = config.getInt("fee-proportional-millionth"),
      reserveToFundingRatio = config.getDouble("reserve-to-funding-ratio"),
      maxReserveToFundingRatio = config.getDouble("max-reserve-to-funding-ratio"),
      defaultFinalScriptPubKey = defaultFinalScriptPubKey,
      channelsDb = Dbs.makeChannelDb(db),
      peersDb = Dbs.makePeerDb(db),
      announcementsDb = Dbs.makeAnnouncementDb(db),
      routerBroadcastInterval = FiniteDuration(config.getDuration("router-broadcast-interval").getSeconds, TimeUnit.SECONDS),
      routerValidateInterval = FiniteDuration(config.getDuration("router-validate-interval").getSeconds, TimeUnit.SECONDS),
      pingInterval = FiniteDuration(config.getDuration("ping-interval").getSeconds, TimeUnit.SECONDS),
      maxFeerateMismatch = config.getDouble("max-feerate-mismatch"),
      updateFeeMinDiffRatio = config.getDouble("update-fee_min-diff-ratio"),
      autoReconnect = config.getBoolean("auto-reconnect"),
      chainHash = chainHash,
      channelFlags = config.getInt("channel-flags").toByte)
  }
}
